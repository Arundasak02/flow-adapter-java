# Flow Core Service - Quick Reference

> Quick cheat sheet for common API operations

## Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/ingest/static` | Ingest static graph |
| `POST` | `/ingest/runtime` | Ingest runtime events |
| `GET` | `/graphs` | List all graphs |
| `GET` | `/graphs/{id}` | Get graph details |
| `DELETE` | `/graphs/{id}` | Delete graph |
| `GET` | `/flow/{id}?zoom=N` | Get zoomed view |
| `GET` | `/trace/{id}` | Get trace details |
| `GET` | `/export/neo4j/{id}?mode=cypher` | Export Cypher |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Metrics |

---

## Quick Start Commands

### 1. Ingest a Static Graph

```bash
curl -X POST http://localhost:8080/ingest/static \
  -H "Content-Type: application/json" \
  -d '{
    "graphId": "my-service",
    "version": "1.0.0",
    "nodes": [
      {"nodeId": "api-endpoint", "type": "ENDPOINT", "name": "POST /api/data"},
      {"nodeId": "service-method", "type": "METHOD", "name": "DataService.process"}
    ],
    "edges": [
      {"edgeId": "e1", "sourceNodeId": "api-endpoint", "targetNodeId": "service-method", "type": "CALL"}
    ]
  }'
```

### 2. Ingest Runtime Events

```bash
curl -X POST http://localhost:8080/ingest/runtime \
  -H "Content-Type: application/json" \
  -d '{
    "graphId": "my-service",
    "traceId": "trace-001",
    "events": [
      {"eventId": "e1", "type": "METHOD_ENTER", "timestamp": "2025-12-05T10:00:00Z", "nodeId": "api-endpoint", "spanId": "s1"},
      {"eventId": "e2", "type": "METHOD_EXIT", "timestamp": "2025-12-05T10:00:01Z", "nodeId": "api-endpoint", "spanId": "s1", "durationMs": 1000}
    ],
    "traceComplete": true
  }'
```

### 3. Query Graph

```bash
# List all
curl http://localhost:8080/graphs

# Get specific
curl http://localhost:8080/graphs/my-service

# Zoomed view
curl "http://localhost:8080/flow/my-service?zoom=1"
```

### 4. Query Trace

```bash
curl http://localhost:8080/trace/trace-001
```

### 5. Export to Neo4j

```bash
curl "http://localhost:8080/export/neo4j/my-service?mode=cypher"
```

---

## Node Types

| Type | Description | Example |
|------|-------------|---------|
| `ENDPOINT` | HTTP/REST endpoint | `POST /api/orders` |
| `METHOD` | Java method | `OrderService.process` |
| `TOPIC` | Kafka/messaging topic | `order-events` |
| `EXTERNAL` | External service | `Stripe API` |

## Edge Types

| Type | Description |
|------|-------------|
| `CALL` | Synchronous method call |
| `HTTP` | HTTP request to external service |
| `PRODUCES` | Publishes to a topic |
| `CONSUMES` | Consumes from a topic |

## Event Types

| Type | Description |
|------|-------------|
| `METHOD_ENTER` | Method entry point |
| `METHOD_EXIT` | Method exit point |
| `CHECKPOINT` | Custom checkpoint |
| `ERROR` | Error occurred |
| `PRODUCE_TOPIC` | Published to topic |
| `CONSUME_TOPIC` | Consumed from topic |

## Zoom Levels

| Level | Description |
|-------|-------------|
| 0 | Business overview (endpoints, topics) |
| 1 | Service view (public methods) |
| 2 | Component view (protected methods) |
| 3 | Detailed view (private methods) |
| 4-5 | Debug view (all nodes) |

---

## Response Format

**Success:**
```json
{"success": true, "data": {...}, "timestamp": "..."}
```

**Error:**
```json
{"success": false, "error": {"code": "...", "message": "..."}, "timestamp": "..."}
```

## Error Codes

| Code | HTTP | Meaning |
|------|------|---------|
| `VALIDATION_ERROR` | 400 | Bad request |
| `NOT_FOUND` | 404 | Resource not found |
| `GRAPH_NOT_FOUND` | 404 | Graph doesn't exist |
| `QUEUE_FULL` | 429 | Too many requests |

---

## Health Check

```bash
curl http://localhost:8080/actuator/health
```

Expected: `{"status": "UP"}`

## Swagger UI

Open in browser: http://localhost:8080/swagger-ui.html

