package com.flow.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.adapter.Model.Edge;
import com.flow.adapter.Model.Node;
import com.flow.adapter.Model.UnifiedGraphModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes a UnifiedGraphModel to Flow Core Service's /ingest/static endpoint.
 *
 * Transforms the adapter's native format to the core-service DTO format:
 *   Node: {id, type, name, data} → {nodeId, type, name, attributes}
 *   Edge: {id, from, to, type}  → {edgeId, sourceNodeId, targetNodeId, type}
 */
public class GraphPublisher {

    private final String serverUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GraphPublisher(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public void publish(UnifiedGraphModel model) throws Exception {
        Map<String, Object> request = buildRequest(model);
        byte[] body = mapper.writeValueAsBytes(request);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl + "/ingest/static"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (apiKey != null && !apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            System.out.println("Publish successful (HTTP " + response.statusCode() + ")");
        } else {
            System.err.println("Publish failed (HTTP " + response.statusCode() + "): " + response.body());
            throw new RuntimeException("Failed to publish graph: HTTP " + response.statusCode());
        }
    }

    /**
     * Transforms UnifiedGraphModel to StaticGraphIngestRequest format.
     *
     * Core-service expects:
     * {
     *   "graphId": "...",
     *   "version": "1",
     *   "nodes": [{ "nodeId", "type", "name", "attributes": {...} }],
     *   "edges": [{ "edgeId", "sourceNodeId", "targetNodeId", "type" }]
     * }
     */
    private Map<String, Object> buildRequest(UnifiedGraphModel model) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("graphId", model.graphId);
        request.put("version", model.version != null ? model.version : "1");
        request.put("nodes", convertNodes(model.nodes));
        request.put("edges", convertEdges(model.edges));
        return request;
    }

    private List<Map<String, Object>> convertNodes(List<Node> nodes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Node node : nodes) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("nodeId", node.id);           // adapter: "id" → service: "nodeId"
            n.put("type", node.type);
            n.put("name", node.name);
            n.put("attributes", node.data);     // adapter: "data" → service: "attributes"
            result.add(n);
        }
        return result;
    }

    private List<Map<String, Object>> convertEdges(List<Edge> edges) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Edge edge : edges) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("edgeId", edge.id);            // adapter: "id" → service: "edgeId"
            e.put("sourceNodeId", edge.from);    // adapter: "from" → service: "sourceNodeId"
            e.put("targetNodeId", edge.to);      // adapter: "to" → service: "targetNodeId"
            e.put("type", edge.type);
            result.add(e);
        }
        return result;
    }
}

