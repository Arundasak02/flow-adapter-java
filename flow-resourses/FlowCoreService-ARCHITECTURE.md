# Flow Core Service (FCS) – Architecture & API Documentation

_Last updated: 2025-12-05_

> This document is the implementation blueprint for the **`flow-core-service`** repository. Developers and tools (including Copilot) should follow this structure and these contracts when creating modules, classes, and endpoints.

## 1. Purpose of This Document

This document defines the design, responsibilities, and API of the **Flow Core Service (FCS)**—a new Spring Boot–based service that uses the existing `flow-engine` project as a library dependency.

It is intended for:
- Backend developers implementing or extending FCS
- DevOps engineers deploying and operating FCS
- UI/Adapter developers integrating with FCS via HTTP APIs

---

## 2. What Is Flow / Flow Core Service?

### 2.1 What Is Flow (Conceptually)?

**Flow** is a model for representing how execution moves through a system:
- **Static Flow Graph** – a directed graph of components, steps, and edges describing *possible* paths through the system.
- **Runtime Events** – actual execution events (start/stop, checkpoint, error, async hop, etc.) produced when the system runs.
- **Merged Flow** – a combination of static structure and runtime evidence showing what actually happened: traces, durations, bottlenecks, hops, and errors.

The separate `flow-engine` project already provides the **graph model**, **merge logic**, and **zooming/extraction** capabilities (e.g., `MergeEngine`, `FlowExtractor`, `CoreGraph`, `FlowModel`, etc.).

### 2.2 What Is Flow Core Service (FCS)?

**Flow Core Service** is a separate, central Spring Boot application that:
- Receives **static graphs** at build time from a **Flow Adapter**
- Receives **runtime events** at execution time from a **Flow Runtime Plugin**
- Serves **UI queries** from Flow UI and other tools (graph slices, traces, exports)

FCS **does not analyze code** and **does not run inside customer applications**. Instead:
- Code analysis and static graph creation is done by **Flow Adapter** (build-time tool)
- Runtime event emission is done by **Flow Runtime Plugin** (in-app agent/instrumentation)
- FCS runs as a **central service** (e.g., one per environment/cluster)

### 2.3 FCS Responsibilities

**FCS receives:**
- Static graphs (build time)
- Runtime events (execution time)
- UI/API queries (human and system consumers)

**FCS produces:**
- **Merged, enriched graphs**
- **Trace timelines** (ordered events with durations, errors, async hops)
- **Zoom-level slices** of graphs for visualization
- **Exports** for analytics: Neo4j, GEF, JSON, etc.

FCS’s job is to turn raw static & runtime data into a fast, queryable in-memory model, backed by optional Neo4j export for deeper analysis.

---

## 3. Core Requirements (Non‑Negotiable)

The architecture of FCS is shaped by these runtime and product constraints:

1. **Very fast ingestion**
   - Runtime events may be high-volume and continuous.
   - Ingestion paths must be lightweight and non-blocking.

2. **Do NOT block ingestion on DB writes**
   - Ingestion cannot wait on Neo4j or any external store.
   - DB issues must not break ingestion.

3. **UI queries must be instant (< 50 ms)**
   - Graph queries and trace lookups should be in-memory.

4. **Graph model must be flexible**
   - Should support new node/edge types and attributes without schema migration pain.

5. **Runtime merging must be stable and predictable**
   - Merging rules are pure and deterministic, provided by `flow-core`.

6. **Events may arrive out-of-order and duplicated**
   - System must tolerate reordering and duplicates.

7. **Must support async hops (Kafka / queues)**
   - Asynchronous edges and message-based flows supported.

8. **Multi-tenant in future**
   - Design now so that tenant boundaries can be added later.

9. **Export to Neo4j for analytics**
   - Neo4j is used as a secondary store for heavy analytics and cross-run comparisons.

10. **Easy to test + iterate**
    - Clean separation of concerns; pure Java core; Spring Boot wiring.

Everything else in this document flows from these constraints.

---

## 4. High-Level Architecture

### 4.1 Component Overview

```text
Flow Adapter
    │
    ├─ POST /ingest/static  → StaticGraphIngestor
    ├─ POST /ingest/runtime → RuntimeEventIngestor
    │
Flow Core Service (Spring Boot)
    │
    ├── Ingestion Queue (bounded)
    ├── RuntimeTraceBuffer  (in-memory)
    ├── GraphStore          (in-memory primary)
    ├── MergeEngine         (from flow-core)
    ├── FlowExtractor       (from flow-core)
    ├── Exporters           (Neo4j, GEF, JSON)
    │
Flow UI
```

- **Primary store**: in-memory (GraphStore, RuntimeTraceBuffer)
- **Secondary store**: Neo4j (async persistence & analytics)

FCS uses the **existing `flow-core` project as a dependency**, exposing it over HTTP and enriching it with queues, buffering, and exports.

### 4.2 Process Flow

1. **Static Graph Ingestion**
   - Flow Adapter calls `POST /ingest/static` with a static graph payload.
   - FCS validates and stores the graph into `GraphStore`.

2. **Runtime Event Ingestion**
   - Flow Runtime Plugin calls `POST /ingest/runtime` with runtime events.
   - Events go into an **IngestionQueue**.
   - Events are then handled by a worker that writes into `RuntimeTraceBuffer`.
   - On trace completion or batch timer, FCS triggers `MergeEngine` to update the graph.

3. **Query / Visualization**
   - Flow UI queries FCS for graphs, traces, zoomed views, or exports via HTTP endpoints.
   - Responses are served from in-memory `GraphStore` and `RuntimeTraceBuffer`.

4. **Export**
   - After merges, an async pipeline exports graphs to Neo4j and other formats.

---

## 5. Module & Package Structure

The new project/repository is called **`flow-core-service`**. It will depend on the existing `flow-engine` module (this repo) via Maven/Gradle.

### 5.1 Top-Level Layout

```text
flow-core-service/
├── pom.xml                      # Maven module (depends on flow-engine)
├── README.md
├── FlowCoreService-ARCHITECTURE.md  # This document
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/flow/core/service/
│   │   │       ├── FlowCoreServiceApplication.java
│   │   │       ├── api/
│   │   │       ├── ingest/
│   │   │       ├── engine/
│   │   │       ├── runtime/
│   │   │       ├── persistence/
│   │   │       └── config/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── logback-spring.xml
│   └── test/
│       └── java/
│           └── com/flow/core/service/
│               └── api/
│                   └── FlowCoreServiceSmokeTest.java
└── ...
```

### 5.2 Package-Level Breakdown

#### `com.flow.core.service.api`

Spring MVC controllers for all HTTP endpoints:
- `StaticIngestController`
- `RuntimeIngestController`
- `GraphController`
- `TraceController`
- `ExportController`
- Global exception handlers, API response models, and request DTOs.

#### `com.flow.core.service.ingest`

Ingestion pipeline, entry point from controllers to internal processing:
- `IngestionQueue` – bounded queue abstraction
- `StaticGraphHandler` – applies incoming static graphs to the in-memory `GraphStore`
- `RuntimeEventHandler` – writes incoming events into the `RuntimeTraceBuffer` and triggers merges

#### `com.flow.core.service.engine`

Adaptation layer over `flow-core` engine:
- `GraphStore` – in-memory map of `graphId → CoreGraph`
- `MergeEngineAdapter` – thin wrapper calling `flow.core.ingest.MergeEngine` (or equivalent) with correct data
- `FlowExtractorAdapter` – wrapper using `flow.core.flow.FlowExtractor` and related zoom/slice utilities

#### `com.flow.core.service.runtime`

Runtime trace representation and deduplication:
- `RuntimeTraceBuffer` – in-memory store `traceId → RuntimeTrace`
- `EventDeduplicator` – logic for eliminating duplicate events based on event hash or composite key
- Supporting models (e.g., `RuntimeEvent`, `RuntimeTrace`), mapped from runtime payloads

#### `com.flow.core.service.persistence`

Secondary storage and export facilities:
- `Neo4jWriter` – pushes graphs to Neo4j (or returns Cypher for client-side execution)
- `CypherBuilder` – converts in-memory `CoreGraph` to Neo4j Cypher statements
- Optionally: other exporters (GEF/JSON) wrapping existing `flow-core` exporters

#### `com.flow.core.service.config`

Application and domain configuration:
- `FlowConfig` – overall toggles, feature flags, tenant model hooks
- `RetentionConfig` – TTLs for graphs and traces, eviction schedules
- `IngestionConfig` – queue sizes, worker pool sizes, backpressure thresholds, timeouts

---

## 6. Tech Stack & Dependencies

### 6.1 Core Technologies

- **Language**: Java 17+ (align with `flow-engine` default; adjust if repo mandates another LTS)
- **Framework**: Spring Boot 3.x
  - Spring Web (REST controllers)
  - Spring Validation
  - Spring Actuator (health, metrics)
- **JSON Serialization**: Jackson (via Spring Boot starter)
- **Build Tool**: Maven
- **Documentation**: OpenAPI/Swagger (e.g., springdoc-openapi)
- **Monitoring**: Micrometer + Prometheus (via Spring Boot Actuator + registry)
- **Database (optional)**: Neo4j (bolt driver) – for exports only in v1

### 6.2 Dependency on This Project (`flow-engine`)

The new `flow-core-service` repository will declare a dependency on the existing `flow-engine` artifact (group/artifact/version to be configured based on how this repo is published):

```xml
<dependency>
  <groupId>com.flow</groupId>
  <artifactId>flow-engine</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

This provides:
- Graph model (`CoreGraph`, `CoreNode`, `CoreEdge`, etc.)
- Merge logic (`MergeEngine`, runtime ingest utilities)
- Flow model (`FlowModel`, `FlowStep`, etc.)
- Exporters (`Neo4jExporter`, `GraphExporter`, etc.)

The service will primarily call into:
- `com.flow.core.ingest.MergeEngine`
- `com.flow.core.flow.FlowExtractor`
- `com.flow.core.export.Neo4jExporter` (or `GraphExporter` interfaces)

Exact classes should be verified in this repo and then referenced through thin adapters in `flow-core-service`.

---

## 7. In-Memory Components (Heart of FCS)

### 7.1 IngestionQueue

**Responsibility**: Buffer all incoming ingestion work (both static and runtime) and decouple HTTP endpoints from heavy processing.

- **Type**: Bounded queue (e.g., `BlockingQueue` or customized ring buffer)
- **Capacity**: Configurable, default ~10,000 items
- **Behavior on Full**:
  - HTTP endpoints SHOULD return `429 Too Many Requests` with a clear error body.
  - Optionally log structured metrics to Prometheus.

**Why a queue?**
- Protects the core from spikes in ingestion.
- Enables single-threaded or controlled concurrency merging.
- Allows simple backpressure model.

### 7.2 RuntimeTraceBuffer

**Responsibility**: Temporary in-memory store for runtime traces before and after merging.

- Data structure: `Map<traceId, RuntimeTrace>`
- Contains per-trace:
  - events
  - checkpoints
  - errors
  - durations
  - async-hop events
- **TTL-based eviction**: configurable (default: 10 minutes) after trace completion or last update.
- **Deduplication**:
  - Deduplicate using event hash or composite key (e.g., `{traceId, spanId, eventType, timestamp}`)

**Why in-memory?**
- Fast, local, no DB latency.
- Fits Jaeger/Datadog-like architectures where trace backend is optimized for writes and queries.

### 7.3 GraphStore

**Responsibility**: Primary source of truth for current graphs, static + merged runtime.

- Data structure: `Map<graphId, CoreGraph>` (or variant keyed by `{tenantId, graphId}` in future)
- Populated by:
  - Static graph ingestion
  - Merge results from runtime

**Reasons it MUST be in memory:**
- Merging operations are CPU-heavy and must avoid DB round-trips.
- UI queries often request many slices and zoom levels on the same graph.
- Repeated queries should be cheap and cached in memory.
- Local/dev mode must work without any external DB.

---

## 8. Merge Pipeline (Critical Path)

### 8.1 Runtime Event Flow

```text
POST /ingest/runtime
       ↓
IngestionQueue
       ↓
RuntimeEventHandler
       ↓
RuntimeTraceBuffer
       ↓ (on trace completion or batch timer)
MergeEngine (flow-core)
       ↓
GraphStore (updated in memory)
       ↓
Async export → Neo4jWriter.write(graph)
```

### 8.2 Design Principles

- **Merging never blocks ingestion**
  - IngestionQueue is always the first step.
  - Workers that perform merging do so off the request thread.

- **Exporting never blocks merging**
  - Graph update finishes first.
  - Export to Neo4j happens asynchronously (e.g., via a task executor or secondary queue).

- **Batching and triggers**
  - Merging can be triggered by:
    - explicit **trace completion** events
    - periodic batching (timer-based)
    - explicit API endpoint (for maintenance/testing)

This design keeps the service resilient and responsive under load.

---

## 9. HTTP API – Endpoints & Contracts

All endpoints are served under the base path (configurable, defaults to `/`). Example: `http://fcs.local/ingest/runtime`.

The exact JSON schemas will be refined during implementation, but this section defines the semantic contracts.

### 9.1 Static Graph Ingestion

#### `POST /ingest/static`

**Purpose**: Ingest or update a static graph into `GraphStore`.

- **Request Body**: JSON representation of a static graph.
  - Must include a `graphId` (string, unique per graph/version).
  - Should map to the existing `flow-core` graph model (e.g., nodes, edges, metadata).

- **Behavior**:
  - If `graphId` is new → add a new entry in `GraphStore`.
  - If `graphId` already exists → behavior is **configurable**:
    - default: overwrite existing graph (replace static definition)
    - alternative (optional future mode): reject or version

- **Responses**:
  - `202 Accepted` – graph accepted for processing
  - `400 Bad Request` – invalid schema or missing `graphId`
  - `429 Too Many Requests` – ingestion queue full

Controller: `StaticIngestController`

### 9.2 Runtime Events Ingestion

#### `POST /ingest/runtime`

**Purpose**: Ingest runtime events for a given trace and graph.

- **Request Body**: JSON payload with runtime events.
  - Required fields (conceptual):
    - `graphId`: string – which static graph this trace relates to
    - `traceId`: string – unique identifier of runtime execution
    - `events`: array of event objects
  - Event object typically includes:
    - `eventId` or hash-related fields
    - `timestamp`
    - `type` (start, end, checkpoint, error, async-send, async-receive, etc.)
    - `nodeId` / `edgeId` or other linking metadata

- **Behavior**:
  - Events go into `IngestionQueue` and then into `RuntimeTraceBuffer`.
  - Deduplication is applied.
  - On trace completion or batch, `MergeEngine` merges runtime into the corresponding graph in `GraphStore`.

- **Responses**:
  - `202 Accepted` – events accepted for ingestion
  - `400 Bad Request` – invalid payload
  - `404 Not Found` – unknown `graphId` (static graph missing)
  - `429 Too Many Requests` – ingestion queue full

Controller: `RuntimeIngestController`

### 9.3 Graph Query Endpoints

#### `GET /graphs`

**Purpose**: Return summaries of all graphs currently known to FCS.

- **Response**: JSON array of graph summaries:
  - `graphId`
  - optional metadata: last updated time, node/edge counts, version, tags

- **Status Codes**:
  - `200 OK` – always, even if empty list

Controller: `GraphController`

#### `GET /graphs/{graphId}`

**Purpose**: Return the **full** merged graph (or static-only if no runtime yet).

- **Path Parameter**:
  - `graphId` – id of the graph

- **Response**:
  - complete graph representation as JSON (nodes, edges, attributes)

- **Status Codes**:
  - `200 OK` – graph found
  - `404 Not Found` – graph not present in `GraphStore`

Controller: `GraphController`

#### `GET /flow/{graphId}?zoom={zoomLevel}`

**Purpose**: Return a **zoom-level sliced** view of a graph for UI.

- **Path Parameter**:
  - `graphId`

- **Query Parameters**:
  - `zoom` (int) – zoom level (e.g., 0..N where 0=highest-level summary)

- **Behavior**:
  - Uses `FlowExtractorAdapter` → `FlowExtractor` from `flow-core` to generate zoomed flows.

- **Response**:
  - JSON representation of the zoomed graph / flow view.

- **Status Codes**:
  - `200 OK`
  - `404 Not Found`

Controller: `GraphController` (or separate `FlowController` if preferred)

### 9.4 Trace Query Endpoints

#### `GET /trace/{traceId}`

**Purpose**: Fetch a detailed view of a single trace.

- **Path Parameter**:
  - `traceId`

- **Response**:
  - structured trace object:
    - ordered events
    - checkpoints
    - errors
    - async hops
    - durations per span/node
    - optional correlation to graph nodes/edges

- **Status Codes**:
  - `200 OK` – trace found
  - `404 Not Found` – trace not in buffer or has been evicted

Controller: `TraceController`

### 9.5 Export Endpoints

#### `GET /export/neo4j/{graphId}`

**Purpose**: Export a graph to Neo4j.

Two possible modes (configurable):
1. **Return Cypher** – the endpoint returns raw Cypher statements.
2. **Push to Neo4j** – backend connects to Neo4j and executes the statements.

- **Path Parameter**:
  - `graphId`

- **Query Parameters**:
  - `mode` (optional): `"cypher"` (default) or `"push"`

- **Behavior**:
  - Uses `CypherBuilder` (and optionally `Neo4jExporter` from `flow-core`) to build Cypher.
  - If `mode=push`, uses `Neo4jWriter` to push asynchronously.

- **Responses**:
  - `200 OK` – for `mode=cypher`, body contains Cypher string or list
  - `202 Accepted` – for `mode=push` when export accepted
  - `404 Not Found` – unknown graph

Controller: `ExportController`

### 9.6 Maintenance Endpoints

#### `DELETE /graphs/{graphId}`

**Purpose**: Delete a graph and its associated runtime traces.

- **Path Parameter**:
  - `graphId`

- **Behavior**:
  - Removes entry from `GraphStore`.
  - Evicts related traces from `RuntimeTraceBuffer`.

- **Responses**:
  - `204 No Content` – delete succeeded (idempotent – OK even if not present)

Controller: `GraphController`

### 9.7 Health & Metrics

#### `GET /health`

- Provided by Spring Boot Actuator.
- Extended to include:
  - queue health (current depth vs capacity)
  - Neo4j connectivity (if export enabled)

#### `GET /metrics`

- Provided by Micrometer + Actuator.
- Includes custom metrics:
  - ingestion queue depth
  - ingestion rate
  - merge duration
  - export duration
  - number of graphs / traces in memory

---

## 10. Neo4j Integration

### 10.1 Role of Neo4j in v1

In **v1**, Neo4j is strictly a **secondary store**. FCS **never reads** from Neo4j.

Neo4j is used only for:
- Persistence (snapshotting graphs)
- Analytics (complex queries, cross-graph joins)
- Visualization by external tools
- Cross-version comparisons

FCS’s primary read/write path is always in-memory.

### 10.2 When to Consider Neo4j as Primary Store (Future)

Future evolution may move towards Neo4j (or another DB) as a primary store when:
- Graphs reach multi-million nodes/edges.
- Long-term trace retention is required (historical queries).
- UI needs to access very old/historical flow data.

The current architecture makes this **optional**, not required.

---

## 11. Why This Architecture

This architecture is chosen because it is:

- **Simple** – No distributed locking, no complex DB schemas in v1.
- **Fast** – In-memory primary store for critical paths.
- **Resilient** – Decoupled ingestion, merge, and export paths.
- **Evolvable** – Clear separation of layers and modules.
- **Familiar** – Mirrors trace systems like Datadog/APM/Jaeger.
- **Dev-friendly** – Easy to run locally with only a JVM.
- **Adapter-friendly** – Flow Adapter & Runtime Plugin have simple HTTP contracts.

It optimizes for **developer productivity today** while leaving room to scale tomorrow.

---

## 12. Implementation Plan (Next Steps)

### 12.1 Initial Skeleton

1. **Create Spring Boot project `flow-core-service`**
   - Set up Maven with Spring Boot dependencies
   - Add dependency on `flow-engine`

2. **Add package structure and empty classes**
   - `FlowCoreServiceApplication`
   - Controllers in `api` package with stub methods for all endpoints
   - `IngestionQueue`, `GraphStore`, `RuntimeTraceBuffer` as in-memory implementations

3. **Wire basic configuration**
   - `application.yml` with port, logging, basic configs
   - `FlowConfig`, `IngestionConfig`, `RetentionConfig` with `@ConfigurationProperties`

4. **Add health/metrics**
   - Enable Actuator endpoints
   - Add a simple custom metric (e.g., queue depth)

5. **Add OpenAPI documentation**
   - Integrate `springdoc-openapi` for auto-doc of endpoints

6. **Add smoke tests**
   - `FlowCoreServiceSmokeTest` that boots the context, hits `/health`, optionally tests a simple ingest + query

### 12.2 Iterative Enhancements

- Implement actual mapping between API payloads and `flow-core` model.
- Implement `RuntimeTraceBuffer` and deduplication logic.
- Integrate `MergeEngine` and `FlowExtractor` via adapters.
- Implement `Neo4jWriter` and `CypherBuilder` using `flow-core` exporters.
- Add retention jobs to evict old graphs and traces.
- Add authentication/authorization if needed for production.

---

## 13. Example Usage Scenarios

### 13.1 Build-Time Static Graph Upload

1. Build pipeline runs Flow Adapter.
2. Adapter generates a static graph JSON for service `payments-service` with `graphId="payments:v1"`.
3. CI pipeline calls `POST /ingest/static` on FCS with that payload.
4. FCS parses and stores the graph in `GraphStore`.

### 13.2 Runtime Observation of a Request Trace

1. A user request goes through `payments-service`.
2. Flow Runtime Plugin emits events to FCS using `POST /ingest/runtime` with `traceId="req-123"`.
3. Events are buffered in `RuntimeTraceBuffer`.
4. At completion, FCS merges runtime into `payments:v1` graph.
5. Flow UI calls `GET /trace/req-123` and `GET /flow/payments:v1?zoom=2` to display what happened.

### 13.3 Export to Neo4j for Deep Analysis

1. An analyst wants to run complex graph queries on `payments:v1`.
2. They call `GET /export/neo4j/payments:v1?mode=push`.
3. FCS builds Cypher and pushes the graph into Neo4j.
4. Analyst uses Neo4j Browser or other tools for deeper analysis.

---

## 14. Open Questions & Future Extensions

- **Multi-tenancy**: How to model tenants (header, path, or config)? Likely: `tenantId` included in graph and trace IDs.
- **AuthN/AuthZ**: What security mechanisms are required at the ingress level?
- **Data retention policies**: Exact TTLs and eviction rules for graphs vs traces.
- **Horizontal scaling**: Strategy for scaling FCS horizontally (shared queue? sharding by graphId? external cache?).
- **Pluggable exporters**: Generalizing Neo4j export to a pluggable export registry.

These can be captured in future revisions of this document.
