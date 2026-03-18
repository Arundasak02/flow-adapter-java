package com.flow.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flow.adapter.Model.UnifiedGraphModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Enriches exported graphs with build metadata and stable hash information.
 */
public final class GraphMetadataUtil {

  private static final String HASH_KEY = "graphHash";
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private GraphMetadataUtil() {
  }

  public static void enrich(UnifiedGraphModel model, Path repoRoot, String adapterVersion) {
    if (model.metadata == null) {
      model.metadata = new HashMap<>();
    }

    model.metadata.put("sourceLanguage", "java");
    model.metadata.put("adapterVersion", adapterVersion == null ? "unknown" : adapterVersion);
    model.metadata.put("buildTimestamp", Instant.now().toString());
    String gitCommit = resolveGitCommit(repoRoot);
    if (gitCommit != null) {
      model.metadata.put("gitCommit", gitCommit);
    }
    model.metadata.put(HASH_KEY, computeGraphHash(model));
  }

  /**
   * Computes hash excluding the graphHash field itself to avoid recursion.
   */
  public static String computeGraphHash(UnifiedGraphModel model) {
    try {
      Map<String, Object> originalMetadata = model.metadata;
      Map<String, Object> copy = originalMetadata == null ? new HashMap<>() : new HashMap<>(originalMetadata);
      copy.remove(HASH_KEY);
      model.metadata = copy;
      byte[] json = MAPPER.writeValueAsBytes(model);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(json);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      model.metadata = originalMetadata;
      return "sha256:" + hex;
    } catch (Exception e) {
      return "sha256:unknown";
    }
  }

  static String resolveGitCommit(Path repoRoot) {
    if (repoRoot == null) {
      return null;
    }
    Path gitDir = repoRoot.resolve(".git");
    if (!Files.exists(gitDir)) {
      return null;
    }
    try {
      Path headFile = gitDir.resolve("HEAD");
      if (!Files.exists(headFile)) {
        return null;
      }
      String head = Files.readString(headFile, StandardCharsets.UTF_8).trim();
      if (head.startsWith("ref: ")) {
        String ref = head.substring(5).trim();
        Path refFile = gitDir.resolve(ref);
        if (Files.exists(refFile)) {
          return Files.readString(refFile, StandardCharsets.UTF_8).trim();
        }
        Path packedRefs = gitDir.resolve("packed-refs");
        if (Files.exists(packedRefs)) {
          return lookupPackedRef(packedRefs, ref);
        }
        return null;
      }
      return head;
    } catch (IOException e) {
      return null;
    }
  }

  private static String lookupPackedRef(Path packedRefs, String ref) throws IOException {
    for (String line : Files.readAllLines(packedRefs, StandardCharsets.UTF_8)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("^")) {
        continue;
      }
      String[] parts = trimmed.split(" ", 2);
      if (parts.length == 2 && parts[1].equals(ref)) {
        return parts[0];
      }
    }
    return null;
  }
}
