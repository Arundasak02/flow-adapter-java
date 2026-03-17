package com.flow.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.flow.adapter.Model.Node;
import com.flow.adapter.Model.Edge;
import com.flow.adapter.Model.UnifiedGraphModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes a {@link UnifiedGraphModel} to Flow Core Service's {@code POST /ingest/static} endpoint.
 *
 * <h3>Adapter → FCS DTO mapping</h3>
 * <pre>
 *   Node: { id, type, name, data }  →  { nodeId, type, name, attributes }
 *   Edge: { id, from, to, type }    →  { edgeId, sourceNodeId, targetNodeId, type }
 * </pre>
 *
 * <p>A SHA-256 {@code graphHash} is included so FCS can reject unchanged graphs
 * (idempotent delivery — safe for rolling restarts and multi-pod deployments).
 *
 * <p>If FCS is unreachable the build is <em>not</em> failed — {@code flow.json} was already
 * written locally. The caller receives a {@link PublishResult} so it can log a clear,
 * actionable message without catching exceptions.
 */
public class GraphPublisher {

    private static final Logger log = LoggerFactory.getLogger(GraphPublisher.class);

    /** Fast connection probe timeout — fail quickly if FCS is down. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** Full request timeout — generous enough for a large graph, short enough not to hang CI. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Maximum publish attempts before giving up. */
    private static final int MAX_ATTEMPTS = 3;
    /** Base retry delay in ms — doubles on each subsequent attempt (exponential backoff). */
    private static final long RETRY_BASE_MS = 500;

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String serverUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    public GraphPublisher(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl.endsWith("/")
                ? serverUrl.substring(0, serverUrl.length() - 1)
                : serverUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // ── Result type ────────────────────────────────────────────────────────────

    /**
     * Outcome of a {@link #publish} call. Never throws for connectivity problems.
     */
    public enum PublishResult {
        /** Graph accepted by FCS (2xx). */
        OK,
        /** FCS was not reachable (connection refused / timeout). Build continues safely. */
        FCS_UNREACHABLE,
        /** FCS replied with a non-2xx HTTP status. */
        FCS_ERROR,
        /** Local failure before the request was sent (serialisation, hashing, etc.). */
        LOCAL_ERROR
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Attempts to publish {@code model} to FCS with up to {@value MAX_ATTEMPTS} retries
     * and exponential backoff. Never throws for connectivity failures.
     *
     * @param model the unified graph to publish
     * @return a {@link PublishResult} describing the outcome
     */
    public PublishResult publish(UnifiedGraphModel model) {
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(buildRequest(model));
        } catch (Exception e) {
            log.error("[flow-adapter] Cannot serialise graph for publishing: {}", e.getMessage(), e);
            return PublishResult.LOCAL_ERROR;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = buildHttpRequest(body);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("[flow-adapter] Graph published to FCS (HTTP {}) on attempt {}/{}",
                            response.statusCode(), attempt, MAX_ATTEMPTS);
                    return PublishResult.OK;
                } else {
                    // A non-2xx from a reachable FCS is a server-side problem — retrying is unlikely to help.
                    log.warn("[flow-adapter] FCS returned HTTP {} on attempt {}/{}: {}",
                            response.statusCode(), attempt, MAX_ATTEMPTS, response.body());
                    return PublishResult.FCS_ERROR;
                }

            } catch (HttpConnectTimeoutException | ConnectException e) {
                log.warn("[flow-adapter] FCS unreachable on attempt {}/{} ({}): {}",
                        attempt, MAX_ATTEMPTS, e.getClass().getSimpleName(), e.getMessage());
                backoffIfNotLast(attempt);

            } catch (java.net.http.HttpTimeoutException e) {
                log.warn("[flow-adapter] FCS request timed out on attempt {}/{}", attempt, MAX_ATTEMPTS);
                backoffIfNotLast(attempt);

            } catch (Exception e) {
                log.error("[flow-adapter] Unexpected error on attempt {}/{}: {}",
                        attempt, MAX_ATTEMPTS, e.getMessage(), e);
                return PublishResult.LOCAL_ERROR;
            }
        }
        return PublishResult.FCS_UNREACHABLE;
    }

    // ── Request building ───────────────────────────────────────────────────────

    private HttpRequest buildHttpRequest(byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/ingest/static"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    /**
     * Transforms {@link UnifiedGraphModel} into the FCS {@code StaticGraphIngestRequest} DTO.
     *
     * <pre>
     * {
     *   "graphId":   "order-service",
     *   "version":   "1",
     *   "graphHash": "sha256:abc123...",
     *   "nodes": [ { "nodeId", "type", "name", "attributes" }, ... ],
     *   "edges": [ { "edgeId", "sourceNodeId", "targetNodeId", "type" }, ... ]
     * }
     * </pre>
     */
    private Map<String, Object> buildRequest(UnifiedGraphModel model) {
        List<Map<String, Object>> nodes = new ArrayList<>(model.nodes.size());
        for (Node node : model.nodes) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("nodeId", node.id);           // adapter: "id"   → service: "nodeId"
            n.put("type", node.type);
            n.put("name", node.name);
            n.put("attributes", node.data);     // adapter: "data" → service: "attributes"
            nodes.add(n);
        }

        List<Map<String, Object>> edges = new ArrayList<>(model.edges.size());
        for (Edge edge : model.edges) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("edgeId", edge.id);           // adapter: "id"   → service: "edgeId"
            e.put("sourceNodeId", edge.from);   // adapter: "from" → service: "sourceNodeId"
            e.put("targetNodeId", edge.to);     // adapter: "to"   → service: "targetNodeId"
            e.put("type", edge.type);
            edges.add(e);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("graphId", model.graphId);
        request.put("version", model.version);
        request.put("graphHash", computeHash(model));
        request.put("nodes", nodes);
        request.put("edges", edges);
        return request;
    }

    /**
     * Computes a stable SHA-256 hash of the serialised model so FCS can deduplicate
     * identical graph pushes (safe for rolling restarts / multi-pod deployments).
     */
    private String computeHash(UnifiedGraphModel model) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(model);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex;
        } catch (Exception e) {
            log.debug("[flow-adapter] Could not compute graph hash: {}", e.getMessage());
            return "sha256:unknown";
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Sleep between retries; returns immediately on the last attempt. */
    private void backoffIfNotLast(int attempt) {
        if (attempt < MAX_ATTEMPTS) {
            try {
                Thread.sleep(RETRY_BASE_MS * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
