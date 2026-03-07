# Flow Core Service - API Documentation

> Complete API reference with real-world examples and test data

---

## Table of Contents

1. [Overview](#overview)
2. [Base URL & Authentication](#base-url--authentication)
3. [Common Response Format](#common-response-format)
4. [Endpoints](#endpoints)
   - [Static Graph Ingestion](#1-static-graph-ingestion)
   - [Runtime Event Ingestion](#2-runtime-event-ingestion)
   - [Graph Management](#3-graph-management)
   - [Trace Queries](#4-trace-queries)
   - [Flow Extraction](#5-flow-extraction)
   - [Neo4j Export](#6-neo4j-export)
   - [Health & Metrics](#7-health--metrics)
5. [Error Codes](#error-codes)
6. [Test Data Examples](#test-data-examples)

---

## Overview

Flow Core Service is the central hub for managing application flow graphs. It receives static graph definitions from build-time analysis and runtime events from application execution, merging them to create enriched flow visualizations.

### Key Concepts

| Concept | Description |
|---------|-------------|
| **Static Graph** | Graph structure extracted at build-time (classes, methods, endpoints, topics) |
| **Runtime Event** | Execution event captured at runtime (method enter/exit, errors, checkpoints) |
| **Trace** | A complete execution flow through the system (e.g., one API request) |
| **Zoom Level** | Hierarchical view level (0=high-level, 5=detailed) |

---

## Base URL & Authentication

```
Base URL: http://localhost:8080
Authentication: None (configure in production)
Content-Type: application/json
```

---

## Common Response Format

All endpoints return a consistent response structure:

```json
{
  "success": true,
  "data": { ... },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

**Error Response:**
```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error message",
    "details": "Additional context"
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

## Endpoints

### 1. Static Graph Ingestion

Ingest a static graph definition extracted from source code analysis.

#### `POST /ingest/static`

**Purpose:** Submit a static graph for processing and storage.

**When to use:** After build-time analysis extracts the application structure.

**Request Body:**

```json
{
  "graphId": "order-service-v1.2.3",
  "version": "1.2.3",
  "nodes": [
    {
      "nodeId": "com.ecommerce.OrderController#createOrder",
      "type": "ENDPOINT",
      "name": "OrderController.createOrder",
      "label": "POST /api/orders",
      "attributes": {
        "httpMethod": "POST",
        "path": "/api/orders",
        "package": "com.ecommerce",
        "className": "OrderController"
      }
    },
    {
      "nodeId": "com.ecommerce.OrderService#processOrder",
      "type": "METHOD",
      "name": "OrderService.processOrder",
      "label": "Process Order",
      "attributes": {
        "visibility": "PUBLIC",
        "returnType": "Order",
        "async": false
      }
    }
  ],
  "edges": [
    {
      "edgeId": "e1",
      "sourceNodeId": "com.ecommerce.OrderController#createOrder",
      "targetNodeId": "com.ecommerce.OrderService#processOrder",
      "type": "CALL",
      "label": "invokes",
      "attributes": {
        "invocationType": "SYNC"
      }
    }
  ],
  "metadata": {
    "application": "order-service",
    "team": "orders",
    "environment": "production",
    "buildNumber": "12345",
    "gitCommit": "abc123def"
  }
}
```

**Response (202 Accepted):**
```json
{
  "success": true,
  "data": "order-service-v1.2.3",
  "timestamp": "2025-12-05T10:30:00Z"
}
```

**Response (429 Too Many Requests):**
```json
{
  "success": false,
  "error": {
    "code": "QUEUE_FULL",
    "message": "Ingestion queue is full, please retry later",
    "details": "Queue utilization: 95%"
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

### 2. Runtime Event Ingestion

Ingest runtime execution events captured during application execution.

#### `POST /ingest/runtime`

**Purpose:** Submit runtime events for a specific trace.

**When to use:** When the Flow Runtime Plugin captures method executions, checkpoints, or errors.

**Request Body:**

```json
{
  "graphId": "order-service-v1.2.3",
  "traceId": "trace-550e8400-e29b-41d4-a716-446655440000",
  "events": [
    {
      "eventId": "evt-001",
      "type": "METHOD_ENTER",
      "timestamp": "2025-12-05T10:30:00.100Z",
      "nodeId": "com.ecommerce.OrderController#createOrder",
      "spanId": "span-001",
      "parentSpanId": null,
      "durationMs": null,
      "attributes": {
        "userId": "user-123",
        "orderId": "order-456"
      }
    },
    {
      "eventId": "evt-002",
      "type": "METHOD_ENTER",
      "timestamp": "2025-12-05T10:30:00.150Z",
      "nodeId": "com.ecommerce.OrderService#processOrder",
      "spanId": "span-002",
      "parentSpanId": "span-001",
      "durationMs": null,
      "attributes": {}
    },
    {
      "eventId": "evt-003",
      "type": "METHOD_EXIT",
      "timestamp": "2025-12-05T10:30:00.350Z",
      "nodeId": "com.ecommerce.OrderService#processOrder",
      "spanId": "span-002",
      "parentSpanId": "span-001",
      "durationMs": 200,
      "attributes": {
        "success": true
      }
    },
    {
      "eventId": "evt-004",
      "type": "METHOD_EXIT",
      "timestamp": "2025-12-05T10:30:00.400Z",
      "nodeId": "com.ecommerce.OrderController#createOrder",
      "spanId": "span-001",
      "parentSpanId": null,
      "durationMs": 300,
      "attributes": {
        "httpStatus": 201
      }
    }
  ],
  "traceComplete": true,
  "metadata": {
    "source": "flow-runtime-plugin",
    "hostName": "order-service-pod-1"
  }
}
```

**Response (202 Accepted):**
```json
{
  "success": true,
  "data": "trace-550e8400-e29b-41d4-a716-446655440000",
  "timestamp": "2025-12-05T10:30:00Z"
}
```

**Response (404 Not Found):**
```json
{
  "success": false,
  "error": {
    "code": "GRAPH_NOT_FOUND",
    "message": "Graph not found: order-service-v1.2.3"
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

### 3. Graph Management

Manage stored graphs.

#### `GET /graphs`

**Purpose:** List all stored graphs with summary information.

**Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "graphId": "order-service-v1.2.3",
      "version": "1.2.3",
      "nodeCount": 45,
      "edgeCount": 67,
      "createdAt": "2025-12-05T09:00:00Z",
      "lastUpdatedAt": "2025-12-05T10:30:00Z",
      "hasRuntimeData": true,
      "traceCount": 1523,
      "metadata": {
        "application": "order-service"
      }
    },
    {
      "graphId": "payment-service-v2.0.0",
      "version": "2.0.0",
      "nodeCount": 32,
      "edgeCount": 41,
      "createdAt": "2025-12-05T08:00:00Z",
      "lastUpdatedAt": "2025-12-05T08:00:00Z",
      "hasRuntimeData": false,
      "traceCount": 0,
      "metadata": {
        "application": "payment-service"
      }
    }
  ],
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

#### `GET /graphs/{graphId}`

**Purpose:** Get complete graph details including all nodes and edges.

**Path Parameters:**
- `graphId` (required): The graph identifier

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "graphId": "order-service-v1.2.3",
    "version": "1.2.3",
    "createdAt": "2025-12-05T09:00:00Z",
    "lastUpdatedAt": "2025-12-05T10:30:00Z",
    "hasRuntimeData": true,
    "nodes": [
      {
        "nodeId": "com.ecommerce.OrderController#createOrder",
        "type": "ENDPOINT",
        "name": "OrderController.createOrder",
        "label": "POST /api/orders",
        "attributes": {
          "serviceId": "com.ecommerce.OrderController",
          "visibility": "PUBLIC",
          "zoomLevel": 1
        }
      },
      {
        "nodeId": "com.ecommerce.OrderService#processOrder",
        "type": "METHOD",
        "name": "OrderService.processOrder",
        "label": "Process Order",
        "attributes": {
          "serviceId": "com.ecommerce.OrderService",
          "visibility": "PUBLIC",
          "zoomLevel": 2
        }
      }
    ],
    "edges": [
      {
        "edgeId": "e1",
        "sourceNodeId": "com.ecommerce.OrderController#createOrder",
        "targetNodeId": "com.ecommerce.OrderService#processOrder",
        "type": "CALL",
        "label": "CALL",
        "attributes": {
          "executionCount": 1523
        }
      }
    ],
    "metadata": {
      "application": "order-service"
    }
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

**Response (404 Not Found):**
```json
{
  "success": false,
  "error": {
    "code": "NOT_FOUND",
    "message": "Graph not found"
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

#### `DELETE /graphs/{graphId}`

**Purpose:** Delete a graph and all associated traces.

**Path Parameters:**
- `graphId` (required): The graph identifier

**Response (204 No Content):** *(empty body)*

---

### 4. Trace Queries

Query runtime trace information.

#### `GET /trace/{traceId}`

**Purpose:** Get complete trace details including all events, checkpoints, and errors.

**Path Parameters:**
- `traceId` (required): The trace identifier

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "traceId": "trace-550e8400-e29b-41d4-a716-446655440000",
    "graphId": "order-service-v1.2.3",
    "startedAt": "2025-12-05T10:30:00.100Z",
    "completedAt": "2025-12-05T10:30:00.400Z",
    "durationMs": 300,
    "completed": true,
    "hasErrors": false,
    "events": [
      {
        "eventId": "evt-001",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T10:30:00.100Z",
        "nodeId": "com.ecommerce.OrderController#createOrder",
        "spanId": "span-001",
        "durationMs": null,
        "attributes": {
          "userId": "user-123"
        }
      },
      {
        "eventId": "evt-004",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T10:30:00.400Z",
        "nodeId": "com.ecommerce.OrderController#createOrder",
        "spanId": "span-001",
        "durationMs": 300,
        "attributes": {
          "httpStatus": 201
        }
      }
    ],
    "checkpoints": [
      {
        "checkpointId": "cp-001",
        "name": "Order Validated",
        "timestamp": "2025-12-05T10:30:00.200Z",
        "nodeId": "com.ecommerce.OrderService#validateOrder",
        "data": {
          "itemCount": 3,
          "totalAmount": 149.99
        }
      }
    ],
    "errors": []
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

**Response with Errors (200 OK):**
```json
{
  "success": true,
  "data": {
    "traceId": "trace-error-example",
    "graphId": "order-service-v1.2.3",
    "startedAt": "2025-12-05T10:30:00.100Z",
    "completedAt": "2025-12-05T10:30:00.250Z",
    "durationMs": 150,
    "completed": true,
    "hasErrors": true,
    "events": [...],
    "checkpoints": [],
    "errors": [
      {
        "errorId": "err-001",
        "type": "InsufficientInventoryException",
        "message": "Not enough stock for item SKU-12345",
        "stackTrace": "com.ecommerce.InventoryService.reserve(InventoryService.java:45)...",
        "timestamp": "2025-12-05T10:30:00.200Z",
        "nodeId": "com.ecommerce.InventoryService#reserve"
      }
    ]
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

### 5. Flow Extraction

Extract zoomed views of graphs for visualization.

#### `GET /flow/{graphId}`

**Purpose:** Get a zoom-level filtered view of the graph for UI rendering.

**Path Parameters:**
- `graphId` (required): The graph identifier

**Query Parameters:**
- `zoom` (optional, default: 0): Zoom level (0 = highest level, 5 = most detailed)

**Zoom Level Guide:**

| Level | Description | Shows |
|-------|-------------|-------|
| 0 | Business Overview | Endpoints, Topics, External Systems |
| 1 | Service View | Public methods, key integrations |
| 2 | Component View | Protected methods, internal services |
| 3 | Detailed View | Private methods, utilities |
| 4-5 | Debug View | All nodes including generated code |

**Request:**
```
GET /flow/order-service-v1.2.3?zoom=1
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "graphId": "order-service-v1.2.3",
    "zoomLevel": 1,
    "nodes": [
      {
        "id": "com.ecommerce.OrderController#createOrder",
        "name": "POST /api/orders",
        "type": "ENDPOINT",
        "zoomLevel": 1,
        "metadata": {
          "executionCount": 1523,
          "avgDurationMs": 245
        }
      },
      {
        "id": "com.ecommerce.OrderService#processOrder",
        "name": "processOrder",
        "type": "METHOD",
        "zoomLevel": 1,
        "metadata": {}
      }
    ],
    "edges": [
      {
        "source": "com.ecommerce.OrderController#createOrder",
        "target": "com.ecommerce.OrderService#processOrder",
        "type": "CALL",
        "executionCount": 1523
      }
    ]
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

### 6. Neo4j Export

Export graphs to Neo4j database for advanced querying and visualization.

#### `GET /export/neo4j/{graphId}`

**Purpose:** Generate Cypher statements or push graph directly to Neo4j.

**Path Parameters:**
- `graphId` (required): The graph identifier

**Query Parameters:**
- `mode` (optional, default: "cypher"): Export mode
  - `cypher`: Return Cypher statements as JSON
  - `push`: Push directly to configured Neo4j instance

**Request (Cypher mode):**
```
GET /export/neo4j/order-service-v1.2.3?mode=cypher
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "graphId": "order-service-v1.2.3",
    "cypherStatements": [
      "MERGE (g:FlowGraph {graphId: 'order-service-v1.2.3'}) SET g.version = '1.2.3', g.nodeCount = 45, g.edgeCount = 67",
      "MERGE (n:FlowNode {id: 'com.ecommerce.OrderController#createOrder'}) SET n.type = 'ENDPOINT', n.name = 'OrderController.createOrder', n.zoomLevel = 1",
      "MERGE (n:FlowNode {id: 'com.ecommerce.OrderService#processOrder'}) SET n.type = 'METHOD', n.name = 'OrderService.processOrder', n.zoomLevel = 2",
      "MATCH (a:FlowNode {id: 'com.ecommerce.OrderController#createOrder'}), (b:FlowNode {id: 'com.ecommerce.OrderService#processOrder'}) MERGE (a)-[r:CALL]->(b) SET r.executionCount = 1523"
    ],
    "nodeCount": 45,
    "edgeCount": 67
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

**Request (Push mode):**
```
GET /export/neo4j/order-service-v1.2.3?mode=push
```

**Response (202 Accepted):**
```json
{
  "success": true,
  "data": {
    "message": "Export to Neo4j initiated",
    "graphId": "order-service-v1.2.3"
  },
  "timestamp": "2025-12-05T10:30:00Z"
}
```

---

### 7. Health & Metrics

Monitor service health and performance.

#### `GET /actuator/health`

**Purpose:** Check service health and component status.

**Response (200 OK):**
```json
{
  "status": "UP",
  "components": {
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 500107862016,
        "free": 350000000000
      }
    },
    "ingestionQueue": {
      "status": "UP",
      "details": {
        "size": 42,
        "capacity": 10000,
        "utilizationPercent": 0.42
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

---

#### `GET /actuator/metrics`

**Purpose:** List available metrics.

**Response (200 OK):**
```json
{
  "names": [
    "flow.store.graphs.count",
    "flow.ingest.queue.size",
    "flow.ingest.queue.utilization",
    "flow.runtime.events.ingested",
    "flow.runtime.events.deduplicated",
    "jvm.memory.used",
    "http.server.requests"
  ]
}
```

---

#### `GET /actuator/prometheus`

**Purpose:** Get metrics in Prometheus format for scraping.

**Response (200 OK):**
```
# HELP flow_store_graphs_count Number of graphs in memory
# TYPE flow_store_graphs_count gauge
flow_store_graphs_count 5

# HELP flow_ingest_queue_size Current ingestion queue size
# TYPE flow_ingest_queue_size gauge
flow_ingest_queue_size 42

# HELP flow_runtime_events_ingested_total Total runtime events ingested
# TYPE flow_runtime_events_ingested_total counter
flow_runtime_events_ingested_total 152345
```

---

## Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `VALIDATION_ERROR` | 400 | Request validation failed |
| `NOT_FOUND` | 404 | Resource not found |
| `GRAPH_NOT_FOUND` | 404 | Graph with specified ID not found |
| `QUEUE_FULL` | 429 | Ingestion queue is at capacity |
| `NEO4J_EXPORT_DISABLED` | 503 | Neo4j export feature is disabled |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

---

## Test Data Examples

### Complete E-commerce Order Flow

Use these examples to test the full flow from static ingestion through runtime events.

#### Step 1: Ingest Static Graph

```bash
curl -X POST http://localhost:8080/ingest/static \
  -H "Content-Type: application/json" \
  -d '{
    "graphId": "ecommerce-order-service",
    "version": "1.0.0",
    "nodes": [
      {
        "nodeId": "endpoint-create-order",
        "type": "ENDPOINT",
        "name": "POST /api/orders",
        "label": "Create Order API",
        "attributes": {"httpMethod": "POST", "path": "/api/orders"}
      },
      {
        "nodeId": "service-order-process",
        "type": "METHOD",
        "name": "OrderService.processOrder",
        "label": "Process Order",
        "attributes": {"class": "OrderService", "visibility": "PUBLIC"}
      },
      {
        "nodeId": "service-inventory-check",
        "type": "METHOD",
        "name": "InventoryService.checkAvailability",
        "label": "Check Inventory",
        "attributes": {"class": "InventoryService", "visibility": "PUBLIC"}
      },
      {
        "nodeId": "service-payment-process",
        "type": "METHOD",
        "name": "PaymentService.processPayment",
        "label": "Process Payment",
        "attributes": {"class": "PaymentService", "visibility": "PUBLIC"}
      },
      {
        "nodeId": "service-notification-send",
        "type": "METHOD",
        "name": "NotificationService.sendConfirmation",
        "label": "Send Confirmation",
        "attributes": {"class": "NotificationService", "visibility": "PUBLIC"}
      },
      {
        "nodeId": "topic-order-events",
        "type": "TOPIC",
        "name": "order-events",
        "label": "Order Events Topic",
        "attributes": {"broker": "kafka", "partitions": 6}
      },
      {
        "nodeId": "external-payment-gateway",
        "type": "EXTERNAL",
        "name": "Stripe API",
        "label": "Payment Gateway",
        "attributes": {"provider": "stripe", "endpoint": "api.stripe.com"}
      }
    ],
    "edges": [
      {"edgeId": "e1", "sourceNodeId": "endpoint-create-order", "targetNodeId": "service-order-process", "type": "CALL", "label": "invokes"},
      {"edgeId": "e2", "sourceNodeId": "service-order-process", "targetNodeId": "service-inventory-check", "type": "CALL", "label": "checks"},
      {"edgeId": "e3", "sourceNodeId": "service-order-process", "targetNodeId": "service-payment-process", "type": "CALL", "label": "processes"},
      {"edgeId": "e4", "sourceNodeId": "service-payment-process", "targetNodeId": "external-payment-gateway", "type": "HTTP", "label": "calls"},
      {"edgeId": "e5", "sourceNodeId": "service-order-process", "targetNodeId": "topic-order-events", "type": "PRODUCES", "label": "publishes"},
      {"edgeId": "e6", "sourceNodeId": "topic-order-events", "targetNodeId": "service-notification-send", "type": "CONSUMES", "label": "triggers"}
    ],
    "metadata": {
      "application": "order-service",
      "team": "commerce-platform",
      "environment": "production"
    }
  }'
```

#### Step 2: Ingest Runtime Events (Successful Order)

```bash
curl -X POST http://localhost:8080/ingest/runtime \
  -H "Content-Type: application/json" \
  -d '{
    "graphId": "ecommerce-order-service",
    "traceId": "trace-order-success-001",
    "events": [
      {
        "eventId": "evt-001",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:30:00.000Z",
        "nodeId": "endpoint-create-order",
        "spanId": "span-001",
        "parentSpanId": null,
        "attributes": {"userId": "cust-789", "cartId": "cart-456"}
      },
      {
        "eventId": "evt-002",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:30:00.010Z",
        "nodeId": "service-order-process",
        "spanId": "span-002",
        "parentSpanId": "span-001"
      },
      {
        "eventId": "evt-003",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:30:00.020Z",
        "nodeId": "service-inventory-check",
        "spanId": "span-003",
        "parentSpanId": "span-002"
      },
      {
        "eventId": "evt-004",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:30:00.050Z",
        "nodeId": "service-inventory-check",
        "spanId": "span-003",
        "parentSpanId": "span-002",
        "durationMs": 30,
        "attributes": {"available": true, "reservedItems": 3}
      },
      {
        "eventId": "evt-005",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:30:00.060Z",
        "nodeId": "service-payment-process",
        "spanId": "span-004",
        "parentSpanId": "span-002"
      },
      {
        "eventId": "evt-006",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:30:00.260Z",
        "nodeId": "service-payment-process",
        "spanId": "span-004",
        "parentSpanId": "span-002",
        "durationMs": 200,
        "attributes": {"paymentId": "pay-123", "amount": 149.99, "currency": "USD"}
      },
      {
        "eventId": "evt-007",
        "type": "PRODUCE_TOPIC",
        "timestamp": "2025-12-05T14:30:00.270Z",
        "nodeId": "topic-order-events",
        "spanId": "span-005",
        "parentSpanId": "span-002",
        "attributes": {"orderId": "order-12345", "eventType": "ORDER_CREATED"}
      },
      {
        "eventId": "evt-008",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:30:00.280Z",
        "nodeId": "service-order-process",
        "spanId": "span-002",
        "parentSpanId": "span-001",
        "durationMs": 270,
        "attributes": {"orderId": "order-12345", "status": "CONFIRMED"}
      },
      {
        "eventId": "evt-009",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:30:00.285Z",
        "nodeId": "endpoint-create-order",
        "spanId": "span-001",
        "parentSpanId": null,
        "durationMs": 285,
        "attributes": {"httpStatus": 201}
      }
    ],
    "traceComplete": true,
    "metadata": {"source": "flow-runtime-plugin", "hostName": "order-service-pod-1"}
  }'
```

#### Step 3: Ingest Runtime Events (Failed Order - Payment Declined)

```bash
curl -X POST http://localhost:8080/ingest/runtime \
  -H "Content-Type: application/json" \
  -d '{
    "graphId": "ecommerce-order-service",
    "traceId": "trace-order-failed-001",
    "events": [
      {
        "eventId": "evt-f01",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:35:00.000Z",
        "nodeId": "endpoint-create-order",
        "spanId": "span-f01",
        "parentSpanId": null,
        "attributes": {"userId": "cust-111", "cartId": "cart-222"}
      },
      {
        "eventId": "evt-f02",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:35:00.010Z",
        "nodeId": "service-order-process",
        "spanId": "span-f02",
        "parentSpanId": "span-f01"
      },
      {
        "eventId": "evt-f03",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:35:00.020Z",
        "nodeId": "service-inventory-check",
        "spanId": "span-f03",
        "parentSpanId": "span-f02"
      },
      {
        "eventId": "evt-f04",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:35:00.040Z",
        "nodeId": "service-inventory-check",
        "spanId": "span-f03",
        "durationMs": 20,
        "attributes": {"available": true}
      },
      {
        "eventId": "evt-f05",
        "type": "METHOD_ENTER",
        "timestamp": "2025-12-05T14:35:00.050Z",
        "nodeId": "service-payment-process",
        "spanId": "span-f04",
        "parentSpanId": "span-f02"
      },
      {
        "eventId": "evt-f06",
        "type": "ERROR",
        "timestamp": "2025-12-05T14:35:00.150Z",
        "nodeId": "service-payment-process",
        "spanId": "span-f04",
        "parentSpanId": "span-f02",
        "errorMessage": "Payment declined: Insufficient funds",
        "errorType": "PaymentDeclinedException",
        "attributes": {"declineCode": "insufficient_funds"}
      },
      {
        "eventId": "evt-f07",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:35:00.160Z",
        "nodeId": "service-payment-process",
        "spanId": "span-f04",
        "durationMs": 110,
        "attributes": {"success": false}
      },
      {
        "eventId": "evt-f08",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:35:00.170Z",
        "nodeId": "service-order-process",
        "spanId": "span-f02",
        "durationMs": 160,
        "attributes": {"status": "PAYMENT_FAILED"}
      },
      {
        "eventId": "evt-f09",
        "type": "METHOD_EXIT",
        "timestamp": "2025-12-05T14:35:00.175Z",
        "nodeId": "endpoint-create-order",
        "spanId": "span-f01",
        "durationMs": 175,
        "attributes": {"httpStatus": 402}
      }
    ],
    "traceComplete": true,
    "metadata": {"source": "flow-runtime-plugin"}
  }'
```

#### Step 4: Query the Graph

```bash
# List all graphs
curl http://localhost:8080/graphs

# Get specific graph
curl http://localhost:8080/graphs/ecommerce-order-service

# Get zoomed view (service level)
curl "http://localhost:8080/flow/ecommerce-order-service?zoom=1"
```

#### Step 5: Query Traces

```bash
# Get successful trace
curl http://localhost:8080/trace/trace-order-success-001

# Get failed trace
curl http://localhost:8080/trace/trace-order-failed-001
```

#### Step 6: Export to Neo4j

```bash
# Generate Cypher statements
curl "http://localhost:8080/export/neo4j/ecommerce-order-service?mode=cypher"

# Push to Neo4j (if configured)
curl "http://localhost:8080/export/neo4j/ecommerce-order-service?mode=push"
```

#### Step 7: Check Health and Metrics

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus
```

#### Step 8: Cleanup

```bash
# Delete graph and all traces
curl -X DELETE http://localhost:8080/graphs/ecommerce-order-service
```

---

### Microservices Architecture Example

A more complex example showing multiple services communicating.

```bash
# User Service Graph
curl -X POST http://localhost:8080/ingest/static \
  -H "Content-Type: application/json" \
  -d '{
    "graphId": "user-service-v1.0.0",
    "version": "1.0.0",
    "nodes": [
      {"nodeId": "user-controller-register", "type": "ENDPOINT", "name": "POST /api/users/register", "label": "User Registration"},
      {"nodeId": "user-service-create", "type": "METHOD", "name": "UserService.createUser", "label": "Create User"},
      {"nodeId": "user-repository-save", "type": "METHOD", "name": "UserRepository.save", "label": "Save to DB"},
      {"nodeId": "topic-user-created", "type": "TOPIC", "name": "user-created-events", "label": "User Created Events"}
    ],
    "edges": [
      {"edgeId": "e1", "sourceNodeId": "user-controller-register", "targetNodeId": "user-service-create", "type": "CALL"},
      {"edgeId": "e2", "sourceNodeId": "user-service-create", "targetNodeId": "user-repository-save", "type": "CALL"},
      {"edgeId": "e3", "sourceNodeId": "user-service-create", "targetNodeId": "topic-user-created", "type": "PRODUCES"}
    ],
    "metadata": {"application": "user-service", "team": "identity"}
  }'
```

---

## Swagger UI

Interactive API documentation is available at:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI spec is available at:

```
http://localhost:8080/v3/api-docs
```

---

*Generated for Flow Core Service v0.0.1-SNAPSHOT*

