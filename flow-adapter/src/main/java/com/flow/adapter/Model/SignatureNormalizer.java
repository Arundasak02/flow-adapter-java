package com.flow.adapter.Model;

import java.util.regex.Pattern;

public class SignatureNormalizer {

  /**
   * Normalizes signature types to match the runtime agent's NodeIdBuilder.simplifyType().
   *
   * RULES (must match agent exactly):
   * - Strips all package segments, keeping only the simple class name.
   *   e.g. java.lang.String → String, com.example.dto.Order → Order
   * - Matches what the scanner naturally produces from source-level type names (post-import resolution).
   */
  private static final Pattern FQN_PATTERN =
      Pattern.compile("\\b(?:[a-z][a-z0-9]*\\.)*([a-z][a-z0-9$_]*)\\.([A-Z][a-zA-Z0-9$_]*)");

  public static String normalizeSignature(String signature) {
    if (signature == null || signature.isEmpty()) {
      return signature;
    }
    return FQN_PATTERN.matcher(signature).replaceAll("$2");
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

