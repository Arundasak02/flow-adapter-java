# Flow Runtime Engine - Implementation Design Document

**Version:** 1.0  
**Date:** November 29, 2025  
**Status:** Implemented

---

## 1. Overview

The Flow Runtime Engine processes live execution traces from instrumented applications and merges them with static code graphs to produce animated execution flows for the UI.

### Key Responsibilities
- Accept runtime events from Flow Runtime Plugins
- Buffer events by trace ID
- Merge runtime data with static graphs
- Generate ordered execution paths
- Support distributed tracing across services
- Handle async hops (Kafka, PubSub, SQS, etc.)

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Flow Runtime Engine                       │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   │
│  │  Runtime     │   │   Merge      │   │   Runtime    │   │
│  │  Event       │──▶│   Engine     │──▶│   Flow       │   │
│  │  Ingestor    │   │              │   │  Extractor   │   │
│  └──────────────┘   └──────────────┘   └──────────────┘   │
│         │                   │                   │           │
│         ▼                   ▼                   ▼           │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   │
│  │  Runtime     │   │   Enriched   │   │   Runtime    │   │
│  │  Trace       │   │   Core       │   │    Flow      │   │
│  │  Buffer      │   │   Graph      │   │  (UI Data)   │   │
│  └──────────────┘   └──────────────┘   └──────────────┘   │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Core Components

### 3.1 RuntimeEvent

Represents a single runtime execution event.

```java
class RuntimeEvent {
    String traceId;          // Mandatory - groups related events
    long timestamp;          // UNIX epoch milliseconds
    EventType type;          // METHOD_ENTER, METHOD_EXIT, etc.
    String nodeId;           // Maps to static graph node
    String spanId;           // Distributed tracing span ID
    String parentSpanId;     // Parent span for call hierarchy
    Map<String, Object> data; // Additional event data
}
```

**Event Types:**
- `METHOD_ENTER` - Method execution starts
- `METHOD_EXIT` - Method execution ends
- `PRODUCE_TOPIC` - Message published to topic
- `CONSUME_TOPIC` - Message consumed from topic
- `CHECKPOINT` - Developer checkpoint
- `ERROR` - Exception/error occurred

### 3.2 RuntimeTraceBuffer

Buffers events in memory, grouped by traceId.

**Key Features:**
- Thread-safe concurrent access
- Automatic sorting by timestamp
- Automatic expiration of old traces (default: 5 minutes)
- O(1) trace lookup

**Methods:**
```java
void addEvent(RuntimeEvent event)
List<RuntimeEvent> getEventsByTrace(String traceId)
void expireOldTraces()
```

### 3.3 MergeEngine

Merges static and runtime graphs into enriched graph.

**Merge Steps:**

1. **Add Runtime Nodes** (Zoom Level 5)
   - METHOD_ENTER → runtime method node
   - PRODUCE_TOPIC → runtime produce node
   - CONSUME_TOPIC → runtime consume node

2. **Add Runtime Edges**
   - METHOD → METHOD = `RUNTIME_CALL`
   - METHOD → TOPIC = `PRODUCES_RUNTIME`
   - TOPIC → LISTENER = `ASYNC_HOP`

3. **Apply Durations**
   ```
   durationMs = exit.timestamp - enter.timestamp
   ```

4. **Apply Checkpoints**
   ```
   node.data.checkpoints.{key} = value
   ```

5. **Stitch Async Hops**
   ```
   PRODUCE_TOPIC + CONSUME_TOPIC → ASYNC_HOP edge
   ```

6. **Attach Errors**
   ```
   node.data.error = {type, message, stack}
   node.data.status = "FAILED"
   ```

**Design Rules:**
- ✅ Runtime nodes ALWAYS have zoom = 5
- ✅ Static nodes are NEVER overwritten
- ✅ Runtime paths take precedence
- ✅ Merge is deterministic (idempotent)

### 3.4 RuntimeFlowExtractor

Generates ordered execution path from events and graph.

**Output: RuntimeFlow**
```java
{
  "traceId": "abc-123",
  "steps": [
    {
      "nodeId": "endpoint:POST /orders",
      "timestamp": 1735412200000,
      "durationMs": null,
      "checkpoints": {},
      "error": null
    },
    {
      "nodeId": "OrderService#placeOrder",
      "timestamp": 1735412200100,
      "durationMs": 30,
      "checkpoints": {"cart_total": 250},
      "error": null
    }
  ],
  "totalDurationMs": 500
}
```

**Processing:**
1. Sort events by timestamp
2. Map events to graph nodes
3. Calculate durations from ENTER/EXIT pairs
4. Attach checkpoints
5. Attach errors
6. Generate ordered steps

### 3.5 RuntimeEngine

Main orchestrator for the entire runtime system.

```java
// Accept events
runtimeEngine.acceptEvent(event);

// Process trace
RuntimeFlow flow = runtimeEngine.processTrace(traceId, staticGraph);

// UI receives flow for animation
```

---

## 4. Zoom Levels

```
Z1 = ENDPOINT, TOPIC          (Business level)
Z2 = SERVICE, CLASS           (Service level)
Z3 = METHOD                   (Public methods)
Z4 = PRIVATE_METHOD           (Private methods)
Z5 = RUNTIME_METHOD, RUNTIME_EVENT  (Runtime execution)
```

**Rule:** All runtime-created nodes = Zoom Level 5

---

## 5. Checkpoint System

Developers instrument code:
```java
Flow.checkpoint("cart_total", 250);
Flow.checkpoint("discount", "APPLIED20");
```

Stored in:
```java
node.data.checkpoints = {
  "cart_total": 250,
  "discount": "APPLIED20"
}
```

UI displays these values at the corresponding node during animation.

---

## 6. Async Hop Stitching

**Scenario:** Kafka message flow

```
Producer → Topic → Consumer
```

**Events:**
```java
PRODUCE_TOPIC(topicX) at t1
CONSUME_TOPIC(topicX) at t2
```

**Result:**
```
Edge: topicX → listenerMethod
Type: ASYNC_HOP
```

**Supported Platforms:**
- Kafka
- Google PubSub
- AWS SQS
- RabbitMQ
- Any async messaging

---

## 7. Distributed Tracing

**Same traceId across services = unified flow**

```
Service A (traceId: abc-123)
  ↓ HTTP call
Service B (traceId: abc-123)
  ↓ Kafka message
Service C (traceId: abc-123)
```

All events with `traceId=abc-123` merge into one complete flow.

**Cross-Service Edges:**
- Automatically created via span parent-child relationships
- No manual configuration needed

---

## 8. Runtime Timings

**Method Duration:**
```java
METHOD_ENTER (timestamp: 1000)
METHOD_EXIT  (timestamp: 1030)
→ durationMs = 30
```

**Stored in:**
```java
node.data.durationMs = 30
```

**UI Usage:**
- Display execution time per node
- Show critical path
- Identify bottlenecks

---

## 9. Error Handling

**Error Event:**
```java
{
  "traceId": "abc-123",
  "type": "ERROR",
  "nodeId": "OrderService#placeOrder",
  "data": {
    "type": "NullPointerException",
    "message": "Cart is null",
    "stack": "..."
  }
}
```

**Stored in:**
```java
node.data.error = {type, message, stack}
node.data.status = "FAILED"
```

**UI Behavior:**
- Highlight failed nodes in red
- Show error details on hover
- Stop animation at error point

---

## 10. End-to-End Flow

```
1. Runtime Plugin emits RuntimeEvent
   ↓
2. Flow Core Service receives event
   ↓
3. RuntimeEventIngestor buffers in RuntimeTraceBuffer
   ↓
4. UI requests trace processing
   ↓
5. RuntimeEngine.processTrace(traceId, staticGraph)
   ↓
6. MergeEngine merges static + runtime
   ↓
7. RuntimeFlowExtractor generates RuntimeFlow
   ↓
8. UI receives RuntimeFlow
   ↓
9. UI animates execution path
```

---

## 11. API Examples

### Accept Events
```java
RuntimeEngine engine = new RuntimeEngine();

RuntimeEvent event = new RuntimeEvent(
    "trace-123",                    // traceId
    System.currentTimeMillis(),     // timestamp
    EventType.METHOD_ENTER,         // type
    "OrderService#placeOrder",      // nodeId
    "span-1",                       // spanId
    "span-0",                       // parentSpanId
    Map.of("args", "[cart123]")     // data
);

engine.acceptEvent(event);
```

### Process Trace
```java
CoreGraph staticGraph = loadStaticGraph();
RuntimeFlow flow = engine.processTrace("trace-123", staticGraph);

// Send to UI
return JsonSerializer.serialize(flow);
```

### Clear Old Traces
```java
engine.expireOldTraces(); // Auto-cleanup
```

---

## 12. Performance Considerations

**Memory:**
- Default expiration: 5 minutes
- ~1KB per event
- 10,000 events = ~10MB
- Use expireOldTraces() for cleanup

**Latency:**
- Event ingestion: O(1)
- Trace retrieval: O(1)
- Merge + extract: O(N) where N = event count
- Typical: < 100ms for 1000 events

**Concurrency:**
- RuntimeTraceBuffer is thread-safe
- Multiple traces processed in parallel
- No locks during event ingestion

---

## 13. Extension Points

### Custom Event Types
Add to `EventType` enum and handle in MergeEngine.

### Custom Edge Types
Add to `EdgeType` enum for new relationship types.

### Custom Checkpoint Storage
Extend CoreNode with metadata field for rich data.

### Plugins
- Spring Boot Runtime Plugin
- Quarkus Runtime Plugin
- Micronaut Runtime Plugin

---

## 14. Testing Strategy

**Unit Tests:**
- RuntimeTraceBuffer sorting
- MergeEngine determinism
- RuntimeFlowExtractor ordering

**Integration Tests:**
- End-to-end trace processing
- Multi-service flows
- Async hop stitching

**Performance Tests:**
- 10K events per trace
- 100 concurrent traces
- Memory leak detection

---

## 15. Future Enhancements

- [ ] Persistent trace storage (DB)
- [ ] Real-time streaming to UI
- [ ] Performance regression detection
- [ ] Automatic SLA violation alerts
- [ ] AI-powered anomaly detection
- [ ] Cost analysis per trace

---

## 16. Implementation Files

**Core Classes:**
- `EventType.java` - Runtime event types enum
- `RuntimeEvent.java` - Event data model
- `RuntimeTraceBuffer.java` - Event buffer
- `FlowStep.java` - Single execution step
- `RuntimeFlow.java` - Complete trace flow
- `RuntimeEventIngestor.java` - Event ingestion
- `MergeEngine.java` - Static + runtime merger
- `RuntimeFlowExtractor.java` - Flow generation
- `RuntimeEngine.java` - Main orchestrator

**Package Structure:**
```
com.flow.core.runtime/
  ├── EventType.java
  ├── RuntimeEvent.java
  ├── RuntimeTraceBuffer.java
  ├── FlowStep.java
  ├── RuntimeFlow.java
  ├── RuntimeFlowExtractor.java
  └── RuntimeEngine.java

com.flow.core.ingest/
  ├── RuntimeEventIngestor.java
  └── MergeEngine.java
```

---

## 17. Compliance

✅ Framework-agnostic (pure Java)  
✅ No external runtime dependencies  
✅ Thread-safe concurrent access  
✅ Deterministic merge behavior  
✅ Memory-efficient buffering  
✅ Automatic cleanup  
✅ Type-safe enum usage  
✅ Builder pattern for complex objects  

---

**End of Document**

