package com.flow.adapter.Model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GraphModelConverter {


  public static UnifiedGraphModel convert(GraphModel legacy) {
    UnifiedGraphModel unified = new UnifiedGraphModel(legacy.projectId);

    Map<String, GraphModel.MethodNode> normalizedMethods = deduplicateMethods(legacy);
    Map<String, String> classToServiceMap = addMethodNodes(unified, normalizedMethods);

    addEndpointNodes(unified, legacy);
    addTopicNodes(unified, legacy);
    addClassAndServiceNodes(unified, classToServiceMap);

    addCallEdges(unified, legacy);
    addEndpointEdges(unified, legacy);
    addMessagingEdges(unified, legacy);
    addMethodToClassEdges(unified, normalizedMethods);

    return unified;
  }

  private static Map<String, GraphModel.MethodNode> deduplicateMethods(GraphModel legacy) {
    Set<String> processedIds = new HashSet<>();
    Map<String, GraphModel.MethodNode> normalizedMethods = new HashMap<>();

    for (GraphModel.MethodNode method : legacy.methods.values()) {
      String normalizedId = SignatureNormalizer.createNormalizedMethodId(
          method.className, method.methodName, method.signature);

      if (!processedIds.contains(normalizedId)) {
        normalizedMethods.put(normalizedId, method);
        processedIds.add(normalizedId);
      }
    }
    return normalizedMethods;
  }

  private static Map<String, String> addMethodNodes(UnifiedGraphModel unified,
                                                     Map<String, GraphModel.MethodNode> methods) {
    Map<String, String> classToServiceMap = new HashMap<>();

    for (Map.Entry<String, GraphModel.MethodNode> entry : methods.entrySet()) {
      String normalizedId = entry.getKey();
      GraphModel.MethodNode method = entry.getValue();

      ClassInfo classInfo = extractClassInfo(method);
      String normalizedSig = normalizeSignature(method.signature);

      unified.addMethod(normalizedId, method.methodName, method.visibility,
          classInfo.className, classInfo.packageName, method.moduleName, normalizedSig);

      if (classInfo.isValid()) {
        String classId = classInfo.getFullClassName();
        String serviceName = SignatureNormalizer.deriveServiceName(method.moduleName, classInfo.packageName);
        classToServiceMap.put(classId, serviceName);
      }
    }
    return classToServiceMap;
  }

  private static ClassInfo extractClassInfo(GraphModel.MethodNode method) {
    String className = method.className;
    String packageName = method.packageName;

    if (className != null && className.contains(".")) {
      int lastDot = className.lastIndexOf('.');
      packageName = className.substring(0, lastDot);
      className = className.substring(lastDot + 1);
    }

    return new ClassInfo(className, packageName);
  }

  private static String normalizeSignature(String signature) {
    return signature != null ? SignatureNormalizer.normalizeSignature(signature) : "";
  }

  private static void addEndpointNodes(UnifiedGraphModel unified, GraphModel legacy) {
    for (GraphModel.EndpointNode endpoint : legacy.endpoints.values()) {
      String normalizedId = SignatureNormalizer.normalizeEndpointId(endpoint.httpMethod, endpoint.path);
      Node n = unified.ensureNode(normalizedId, "ENDPOINT", endpoint.httpMethod + " " + endpoint.path);
      n.data.put("httpMethod", endpoint.httpMethod);
      n.data.put("path", endpoint.path);
      addIfNotEmpty(n.data, "produces", endpoint.produces);
      addIfNotEmpty(n.data, "consumes", endpoint.consumes);
    }
  }

  private static void addTopicNodes(UnifiedGraphModel unified, GraphModel legacy) {
    for (GraphModel.TopicNode topic : legacy.topics.values()) {
      String normalizedId = SignatureNormalizer.normalizeTopicId(topic.id);
      unified.ensureNode(normalizedId, "TOPIC", topic.name);
    }
  }

  private static void addClassAndServiceNodes(UnifiedGraphModel unified, Map<String, String> classToServiceMap) {
    for (Map.Entry<String, String> entry : classToServiceMap.entrySet()) {
      String classId = entry.getKey();
      String serviceName = entry.getValue();

      ClassInfo classInfo = ClassInfo.fromFullClassName(classId);
      Node classNode = unified.ensureNode(classId, "CLASS", classInfo.className);
      classNode.data.put("className", classInfo.className);
      classNode.data.put("packageName", classInfo.packageName);
      classNode.data.put("moduleName", serviceName);

      String serviceId = "service:" + serviceName;
      unified.addService(serviceName, serviceName);
      unified.addClassToServiceEdge(classId, serviceId);
    }
  }

  private static void addCallEdges(UnifiedGraphModel unified, GraphModel legacy) {
    int counter = 0;
    for (GraphModel.CallEdge call : legacy.calls) {
      counter++;
      String normalizedFrom = normalizeMethodIdInEdge(call.from);
      String normalizedTo = normalizeMethodIdInEdge(call.to);
      unified.addEdge("e-call-" + counter, normalizedFrom, normalizedTo, EdgeType.CALL);
    }
  }

  private static void addEndpointEdges(UnifiedGraphModel unified, GraphModel legacy) {
    int counter = 0;
    for (GraphModel.EndpointEdge edge : legacy.endpointEdges) {
      counter++;
      String normalizedEndpointId = SignatureNormalizer.normalizeEndpointId(
          extractHttpMethodFromEndpointId(edge.fromEndpoint),
          extractPathFromEndpointId(edge.fromEndpoint));
      String normalizedMethodId = normalizeMethodIdInEdge(edge.toMethod);
      unified.addEdge("e-endpoint-" + counter, normalizedEndpointId, normalizedMethodId, EdgeType.HANDLES);
    }
  }

  private static void addMessagingEdges(UnifiedGraphModel unified, GraphModel legacy) {
    int counter = 0;
    for (GraphModel.MessagingEdge edge : legacy.messaging) {
      counter++;
      String edgeId = "e-" + edge.kind + "-" + counter;

      if ("produces".equals(edge.kind)) {
        addProducesEdge(unified, edgeId, edge);
      } else if ("consumes".equals(edge.kind)) {
        addConsumesEdge(unified, edgeId, edge);
      }
    }
  }

  private static void addProducesEdge(UnifiedGraphModel unified, String edgeId, GraphModel.MessagingEdge edge) {
    String normalizedFrom = normalizeMethodIdInEdge(edge.from);
    String normalizedTo = SignatureNormalizer.normalizeTopicId(edge.to);
    unified.addEdge(edgeId, normalizedFrom, normalizedTo, EdgeType.PRODUCES);
  }

  private static void addConsumesEdge(UnifiedGraphModel unified, String edgeId, GraphModel.MessagingEdge edge) {
    String topicId = extractTopicNameFromMessagingEdge(edge.to);
    String methodId = normalizeMethodIdInEdge(edge.from);
    String normalizedTopic = SignatureNormalizer.normalizeTopicId(topicId);
    unified.addEdge(edgeId, normalizedTopic, methodId, EdgeType.CONSUMES);
  }

  private static void addMethodToClassEdges(UnifiedGraphModel unified, Map<String, GraphModel.MethodNode> methods) {
    for (Map.Entry<String, GraphModel.MethodNode> entry : methods.entrySet()) {
      String methodId = entry.getKey();
      ClassInfo classInfo = extractClassInfo(entry.getValue());

      if (classInfo.isValid()) {
        String classId = classInfo.getFullClassName();
        if (unified.getNode(classId) != null) {
          unified.addMethodToClassEdge(methodId, classId);
        }
      }
    }
  }

  private static void addIfNotEmpty(Map<String, Object> data, String key, java.util.List<String> values) {
    if (values != null && !values.isEmpty()) {
      data.put(key, values);
    }
  }

  private static String normalizeMethodIdInEdge(String methodId) {
    if (methodId == null || !methodId.contains("#")) {
      return methodId;
    }

    String[] parts = methodId.split("#", 2);
    if (parts.length == 2) {
      String classPath = parts[0];
      String methodPart = parts[1];
      String normalizedMethod = SignatureNormalizer.normalizeSignature(methodPart);
      return classPath + "#" + normalizedMethod;
    }
    return methodId;
  }

  private static String extractHttpMethodFromEndpointId(String endpointId) {
    if (endpointId == null || !endpointId.startsWith("endpoint:")) {
      return "GET";
    }
    String withoutPrefix = endpointId.substring("endpoint:".length()).trim();
    String[] parts = withoutPrefix.split(" ", 2);
    return parts.length > 0 ? parts[0] : "GET";
  }

  private static String extractPathFromEndpointId(String endpointId) {
    if (endpointId == null || !endpointId.startsWith("endpoint:")) {
      return "/";
    }
    String withoutPrefix = endpointId.substring("endpoint:".length()).trim();
    String[] parts = withoutPrefix.split(" ", 2);
    return parts.length > 1 ? parts[1] : "/";
  }

  private static String extractTopicNameFromMessagingEdge(String value) {
    if (value == null) {
      return "";
    }
    return value.startsWith("topic:") ? value.substring(6) : value;
  }

  private static class ClassInfo {
    final String className;
    final String packageName;

    ClassInfo(String className, String packageName) {
      this.className = className;
      this.packageName = packageName;
    }

    static ClassInfo fromFullClassName(String fullClassName) {
      int lastDot = fullClassName.lastIndexOf('.');
      String packageName = lastDot > 0 ? fullClassName.substring(0, lastDot) : "";
      String className = lastDot > 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
      return new ClassInfo(className, packageName);
    }

    boolean isValid() {
      return className != null && packageName != null;
    }

    String getFullClassName() {
      return packageName + "." + className;
    }
  }
}

