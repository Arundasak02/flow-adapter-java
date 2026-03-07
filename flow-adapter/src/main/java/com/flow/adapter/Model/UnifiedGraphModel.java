package com.flow.adapter.Model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified graph model that uses nodes and edges for the FLOW platform.
 * This is the new format that replaces the legacy format.
 */
public class UnifiedGraphModel {

  public String graphId;
  public List<Node> nodes = new ArrayList<>();
  public List<Edge> edges = new ArrayList<>();

  // Helper maps for quick lookup
  private Map<String, Node> nodeMap = new HashMap<>();
  private int edgeCounter = 0;

  public UnifiedGraphModel() {
  }

  public UnifiedGraphModel(String graphId) {
    this.graphId = graphId;
  }

  /**
   * Get or create a node by ID
   */
  public Node ensureNode(String id, String type, String name) {
    return nodeMap.computeIfAbsent(id, k -> {
      Node n = new Node(id, type, name);
      nodes.add(n);
      return n;
    });
  }

  /**
   * Get an existing node
   */
  public Node getNode(String id) {
    return nodeMap.get(id);
  }

  public void addEdge(String from, String to, EdgeType type) {
    edgeCounter++;
    String edgeId = "e-" + type.name().toLowerCase() + "-" + edgeCounter;
    addEdge(edgeId, from, to, type);
  }

  public void addEdge(String edgeId, String from, String to, EdgeType type) {
    Edge e = new Edge(edgeId, from, to, type.name());
    edges.add(e);
  }

  public void addEdge(String edgeId, String from, String to, String type) {
    Edge e = new Edge(edgeId, from, to, type);
    edges.add(e);
  }

  /**
   * Create an ENDPOINT node
   */
  public Node addEndpoint(String httpMethod, String path) {
    String id = "endpoint:" + httpMethod + " " + path;
    String name = httpMethod + " " + path;
    Node n = ensureNode(id, "ENDPOINT", name);
    n.data.put("httpMethod", httpMethod);
    n.data.put("path", path);
    return n;
  }

  /**
   * Create a TOPIC node
   */
  public Node addTopic(String topicName) {
    String id = "topic:" + topicName;
    Node n = ensureNode(id, "TOPIC", topicName);
    return n;
  }

  public Node addMethod(String methodId, String methodName, Visibility visibility,
                        String className, String packageName, String moduleName, String signature) {
    String type = visibility == Visibility.PRIVATE ? "PRIVATE_METHOD" : "METHOD";
    String displayName = className != null ? className + "." + methodName : methodName;
    Node n = ensureNode(methodId, type, displayName);
    n.data.put("visibility", visibility.name());
    n.data.put("className", className);
    n.data.put("packageName", packageName);
    if (moduleName != null) {
      n.data.put("moduleName", moduleName);
    }
    if (signature != null) {
      n.data.put("signature", signature);
    }
    return n;
  }

  public Node addMethod(String methodId, String methodName, String visibility,
                        String className, String packageName, String moduleName, String signature) {
    return addMethod(methodId, methodName, Visibility.fromString(visibility),
        className, packageName, moduleName, signature);
  }

  /**
   * Create a CLASS node
   */
  public Node addClass(String className, String packageName, String moduleName) {
    String id = packageName != null ? packageName + "." + className : className;
    String displayName = className;
    Node n = ensureNode(id, "CLASS", displayName);
    n.data.put("className", className);
    if (packageName != null) {
      n.data.put("packageName", packageName);
    }
    if (moduleName != null) {
      n.data.put("moduleName", moduleName);
    }
    return n;
  }

  /**
   * Create a SERVICE node
   */
  public Node addService(String serviceName, String moduleName) {
    String id = "service:" + serviceName;
    Node n = ensureNode(id, "SERVICE", serviceName);
    if (moduleName != null) {
      n.data.put("moduleName", moduleName);
    }
    return n;
  }

  public void addMethodToClassEdge(String methodId, String classId) {
    String edgeId = "e-method-class-" + edges.size();
    addEdge(edgeId, methodId, classId, EdgeType.DEFINES);
  }

  public void addClassToServiceEdge(String classId, String serviceId) {
    String edgeId = "e-class-service-" + edges.size();
    addEdge(edgeId, classId, serviceId, EdgeType.BELONGS_TO);
  }
}

