package com.flow.adapter.Model;

public class SignatureNormalizer {

  /**
   * Normalizes signature types to match the runtime agent's NodeIdBuilder.simplifyType().
   *
   * RULES (must match agent exactly):
   * - java.lang.{String,Integer,Long,Boolean,Double,Float,Object,Byte,Short,Character} → simple name
   * - java.util.{List,Map,Set,Optional} → simple name
   * - All other FQN types remain fully qualified
   */
  public static String normalizeSignature(String signature) {
    if (signature == null || signature.isEmpty()) {
      return signature;
    }
    String result = signature;

    // java.lang.*
    result = result.replace("java.lang.String", "String");
    result = result.replace("java.lang.Integer", "Integer");
    result = result.replace("java.lang.Long", "Long");
    result = result.replace("java.lang.Boolean", "Boolean");
    result = result.replace("java.lang.Double", "Double");
    result = result.replace("java.lang.Float", "Float");
    result = result.replace("java.lang.Object", "Object");
    result = result.replace("java.lang.Byte", "Byte");
    result = result.replace("java.lang.Short", "Short");
    result = result.replace("java.lang.Character", "Character");

    // java.util.*
    result = result.replace("java.util.List", "List");
    result = result.replace("java.util.Map", "Map");
    result = result.replace("java.util.Set", "Set");
    result = result.replace("java.util.Optional", "Optional");

    return result;
  }

  public static String createNormalizedMethodId(String className, String methodName, String signature) {
    if (className == null || methodName == null) {
      return null;
    }

    String normalizedSig = normalizeSignature(signature);

    if (isValidSignature(normalizedSig) && normalizedSig.startsWith(methodName)) {
      return className + "#" + normalizedSig;
    }
    return className + "#" + methodName;
  }

  private static boolean isValidSignature(String sig) {
    return sig != null && !sig.isEmpty();
  }

  public static String normalizeEndpointId(String httpMethod, String path) {
    if (httpMethod == null || path == null) {
      return null;
    }
    return "endpoint:" + httpMethod.toUpperCase() + " " + path;
  }

  public static String normalizeTopicId(String topicName) {
    if (topicName == null) {
      return null;
    }
    String cleanName = topicName.startsWith("topic:") ? topicName.substring(6) : topicName;
    return "topic:" + cleanName;
  }

  public static String normalizeClassId(String className) {
    return className;
  }

  public static String deriveServiceName(String moduleName, String packageName) {
    if (moduleName != null && !moduleName.isEmpty()) {
      return moduleName;
    }

    if (packageName != null && !packageName.isEmpty()) {
      return extractServiceFromPackage(packageName);
    }

    return "default-service";
  }

  private static String extractServiceFromPackage(String packageName) {
    String[] parts = packageName.split("\\.");
    for (int i = parts.length - 1; i >= 0; i--) {
      if (isValidServiceName(parts[i])) {
        return parts[i];
      }
    }
    return "default-service";
  }

  private static boolean isValidServiceName(String part) {
    return !part.isEmpty() && !part.equals("com") && !part.equals("greens");
  }
}

