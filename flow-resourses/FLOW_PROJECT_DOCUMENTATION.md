# Flow Platform — High-Level Documentation

**Version:** 1.0  
**Last Updated:** March 7, 2026

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [What Is Flow?](#2-what-is-flow)
3. [Project Inventory](#3-project-inventory)
4. [Architecture Overview](#4-architecture-overview)
5. [How the Projects Relate](#5-how-the-projects-relate)
6. [Data Flow — End to End](#6-data-flow--end-to-end)
7. [Project Deep Dives](#7-project-deep-dives)
   - [7.1 flow-java-adapter](#71-flow-java-adapter--the-source-code-scanner)
   - [7.2 flow-engine](#72-flow-engine--the-pure-java-processing-library)
   - [7.3 flow-core-service](#73-flow-core-service--the-central-microservice)
   - [7.4 flow-core](#74-flow-core--the-original-spring-boot-prototype)
8. [Key Concepts](#8-key-concepts)
9. [Technology Stack](#9-technology-stack)
10. [Build & Run Guide](#10-build--run-guide)
11. [Repository Map](#11-repository-map)
12. [Dependency Graph](#12-dependency-graph)
13. [Current Status & Roadmap](#13-current-status--roadmap)

---

## 1. Executive Summary

**Flow** is a platform for **understanding how execution moves through Java applications**. It combines **static code analysis** (what *could* happen) with **runtime tracing** (what *actually* happens) to produce rich, zoomable graph visualizations of application behavior.

Think of it as a system that answers: *"When a user hits `POST /orders`, what methods get called, what messages get published, and how long does each step take?"*

The platform is composed of **four projects** that form a pipeline:

```
┌──────────────┐     ┌─────────────┐     ┌───────────────────┐     ┌──────────┐
│ flow-java-   │────▶│ flow-engine │────▶│ flow-core-service │────▶│  Flow UI │
│ adapter      │     │ (library)   │     │ (microservice)    │     │ (future) │
│              │     │             │     │                   │     │          │
│ Scans source │     │ Processes   │     │ Ingests, merges,  │     │ Queries  │
│ code, emits  │     │ graphs,     │     │ stores, serves    │     │ & shows  │
│ flow.json    │     │ zooms,      │     │ via REST API      │     │ graphs   │
│              │     │ merges,     │     │                   │     │          │
│              │     │ exports     │     │                   │     │          │
└──────────────┘     └─────────────┘     └───────────────────┘     └──────────┘

         + flow-core (original Spring Boot prototype, predates the split)
```

---

## 2. What Is Flow?

### The Problem

Modern Java applications (especially Spring Boot + Kafka microservices) are hard to understand:

- Dozens of REST endpoints, each calling layers of services and repositories
- Asynchronous hops through Kafka topics that break linear tracing
- No single tool shows the full picture from endpoint → service → database → message broker

### The Solution

Flow builds a **directed graph** of your application's architecture by:

1. **Scanning source code** at build time to extract the *static call graph* (endpoints, methods, topics, call relationships)
2. **Instrumenting runtime** to capture *execution traces* (actual method calls, timings, errors, async hops)
3. **Merging both** into a unified, enriched graph model
4. **Serving the result** through a REST API with zoom-level filtering for progressive disclosure

### Core Concepts

| Concept | Description |
|---------|-------------|
| **Static Graph** | Directed graph extracted from source code: classes, methods, endpoints, Kafka topics, and their relationships |
| **Runtime Events** | Live execution data: method enter/exit, message produce/consume, errors, checkpoints |
| **Merged Graph** | Static structure + runtime evidence combined — shows what actually ran, how long it took, what failed |
| **Zoom Levels** | 5-level hierarchy for progressive disclosure (Business → Service → Public → Private → Runtime) |
| **Flow** | A single execution path through the graph — e.g., one API request traced from endpoint to database |

---

## 3. Project Inventory

| Project | Type | Java | Framework | Purpose |
|---------|------|------|-----------|---------|
| **flow-java-adapter** | Multi-module CLI tool | 17 | JavaParser, Picocli, SPI | Scans Java source code, extracts architectural graph to `flow.json` |
| **flow-engine** | Pure Java library (JAR) | 17 | None (Jackson only) | Core graph processing: load, validate, zoom, merge, extract, export |
| **flow-core-service** | Spring Boot microservice | 21 | Spring Boot 3.2 | Central HTTP service: ingestion, querying, persistence, monitoring |
| **flow-core** | Spring Boot app (prototype) | 17 | Spring Boot 4.0, Neo4j | Original monolithic prototype — predecessor to the engine+service split |

---

## 4. Architecture Overview

```
                           THE FLOW PLATFORM
 ═══════════════════════════════════════════════════════════════

 BUILD TIME                                    RUNTIME
 ──────────                                    ───────

 ┌─────────────────────┐              ┌──────────────────────┐
 │  Your Java Project  │              │  Your Running App    │
 │  (source code)      │              │  (instrumented)      │
 └──────────┬──────────┘              └──────────┬───────────┘
            │                                    │
            │ scans source files                 │ emits runtime events
            ▼                                    ▼
 ┌─────────────────────┐              ┌──────────────────────┐
 │  FLOW JAVA ADAPTER  │              │  Flow Runtime Plugin │
 │                     │              │  (future / external) │
 │  • Spring scanner   │              │                      │
 │  • Kafka scanner    │              │  • Method enter/exit │
 │  • Call analyzer    │              │  • Topic prod/cons   │
 │                     │              │  • Errors, timings   │
 └──────────┬──────────┘              └──────────┬───────────┘
            │                                    │
            │ flow.json                          │ POST /ingest/runtime
            │ (static graph)                     │ (events)
            ▼                                    ▼
 ┌───────────────────────────────────────────────────────────┐
 │                   FLOW CORE SERVICE                       │
 │                   (Spring Boot 3.2)                       │
 │                                                           │
 │  ┌─────────────────────────────────────────────────────┐  │
 │  │                  FLOW ENGINE (library)               │  │
 │  │                                                      │  │
 │  │  StaticGraphLoader → ZoomEngine → GraphValidator     │  │
 │  │  RuntimeEventIngestor → MergeEngine                  │  │
 │  │  FlowExtractor → Neo4jExporter                       │  │
 │  └─────────────────────────────────────────────────────┘  │
 │                                                           │
 │  REST API:                                                │
 │    POST /ingest/static      (accept static graphs)       │
 │    POST /ingest/runtime     (accept runtime events)      │
 │    GET  /graphs             (list all graphs)             │
 │    GET  /graphs/{id}        (get graph)                   │
 │    GET  /flow/{id}?zoom=N   (zoomed graph view)           │
 │    GET  /trace/{traceId}    (trace timeline)              │
 │    GET  /export/neo4j/{id}  (Cypher export)               │
 │                                                           │
 │  Infrastructure:                                          │
 │    IngestionQueue, GraphStore, RuntimeTraceBuffer          │
 │    Swagger UI, Prometheus metrics, Health checks           │
 └──────────────────────┬───────────────────────────────────┘
                        │
            ┌───────────┼───────────┐
            ▼           ▼           ▼
     ┌───────────┐ ┌─────────┐ ┌──────────┐
     │  Flow UI  │ │  Neo4j  │ │ Other    │
     │  (future) │ │  (graph │ │ tools /  │
     │           │ │   DB)   │ │ exports  │
     └───────────┘ └─────────┘ └──────────┘
```

---

## 5. How the Projects Relate

### Dependency Chain

```
flow-java-adapter  ──(produces)──▶  flow.json  ──(consumed by)──▶  flow-core-service
                                                                          │
                                                                    (depends on)
                                                                          │
                                                                          ▼
                                                                    flow-engine
```

### Relationships Explained

| Relationship | Description |
|---|---|
| **flow-java-adapter → flow.json** | The adapter scans Java source code and produces a `flow.json` file containing nodes (methods, endpoints, topics) and edges (calls, produces, consumes) |
| **flow.json → flow-core-service** | The `flow.json` is POSTed to `flow-core-service` via `POST /ingest/static` |
| **flow-core-service → flow-engine** | `flow-core-service` uses `flow-engine` as a **Maven dependency** (`com.flow:flow-engine:1.0-SNAPSHOT`). All graph processing logic lives in `flow-engine`; the service adds HTTP, queuing, persistence, and monitoring |
| **flow-core (standalone)** | The **original prototype** that combined everything into one Spring Boot + Neo4j app. The project was later **refactored** into the cleaner `flow-engine` (pure library) + `flow-core-service` (HTTP wrapper) architecture |

### Why the Split?

The original `flow-core` was a monolithic Spring Boot application. It was split into:

- **`flow-engine`** — Pure Java library with zero framework dependencies. Can be embedded anywhere, tested easily, and has no runtime overhead from Spring
- **`flow-core-service`** — Thin Spring Boot wrapper that adds HTTP endpoints, async ingestion queues, in-memory stores, monitoring, and export orchestration

This follows the **clean architecture** principle: business logic (`flow-engine`) is decoupled from infrastructure (`flow-core-service`).

---

## 6. Data Flow — End to End

### Phase 1: Build-Time Static Analysis

```
Developer's Java Project (Spring Boot + Kafka)
    │
    │  flow-java-adapter scans the source tree
    │
    │  1. JavaSourceScanner walks all .java files
    │  2. SpringEndpointScanner finds @GetMapping, @PostMapping, etc.
    │  3. KafkaScanner finds @KafkaListener, @Input, @Output
    │  4. MethodCallAnalyzer traces method call chains
    │  5. GraphModel aggregates all nodes + edges
    │  6. GraphExporterJson writes flow.json
    │
    ▼
flow.json
  {
    "graphId": "payment-service",
    "nodes": [ endpoints, methods, topics, classes ],
    "edges": [ calls, produces, consumes, handles ]
  }
```

### Phase 2: Graph Ingestion & Processing

```
flow.json ──POST /ingest/static──▶  flow-core-service
                                         │
                                    StaticGraphHandler
                                         │
                                    ┌────┴────┐
                                    ▼         ▼
                              flow-engine   GraphStore
                              pipeline:     (in-memory)
                                │
                          1. StaticGraphLoader (parse JSON → CoreGraph)
                          2. ZoomEngine (assign zoom levels 1-5)
                          3. GraphValidator (check structure)
                          4. FlowExtractor (BFS from endpoints)
                                │
                                ▼
                          Enriched CoreGraph stored in memory
```

### Phase 3: Runtime Event Merging

```
Running Application
    │
    │  Flow Runtime Plugin emits events
    │
    ▼
POST /ingest/runtime ──▶  IngestionQueue (bounded, async)
                                │
                           IngestionWorker (polling)
                                │
                           RuntimeTraceBuffer (grouped by traceId)
                                │
                           MergeEngine (static + runtime → enriched)
                                │
                           Updated CoreGraph with:
                             • Runtime nodes (zoom level 5)
                             • Execution durations
                             • Error annotations
                             • Async hop stitching
                             • Checkpoints
```

### Phase 4: Querying & Export

```
Flow UI / API Consumer
    │
    │  GET /graphs/payment-service?zoom=2
    │  GET /trace/req-12345
    │  GET /export/neo4j/payment-service
    │
    ▼
flow-core-service reads from in-memory stores
    │
    │  GraphStore → CoreGraph → zoom-filtered view
    │  RuntimeTraceBuffer → trace timeline
    │  Neo4jExporter → Cypher statements
    │
    ▼
JSON response (< 50ms target)
```

---

## 7. Project Deep Dives

### 7.1 flow-java-adapter — The Source Code Scanner

**Purpose:** Scan Java source code and extract an architectural graph.

**Type:** Multi-module Maven project, CLI executable (shaded JAR).

#### Sub-modules

| Module | Responsibility |
|--------|---------------|
| **flow-adapter** | Core framework: `ScanCommand`, `GraphModel`, `JavaSourceScanner`, `MethodCallAnalyzer`, `GraphExporterJson`, plugin SPI (`FlowPlugin`) |
| **flow-spring-plugin** | Plugin that scans Spring annotations (`@GetMapping`, `@PostMapping`, `@RequestMapping`) — discovers HTTP endpoints, produces/consumes media types |
| **flow-kafka-plugin** | Plugin that scans Kafka annotations (`@KafkaListener`, `@Input`, `@Output`) — discovers topic nodes and messaging edges |
| **flow-runner** | Executable entry point — shaded JAR that bundles all modules for CLI execution |

#### Plugin Architecture

Uses **Java SPI (ServiceLoader)** for extensibility:

```
META-INF/services/com.flow.adapter.FlowPlugin
    → com.flow.plugin.spring.SpringEndpointPlugin
    → com.flow.plugin.kafka.KafkaPlugin
```

New language/framework scanners can be added as plugins without modifying core code.

#### Output Format

Produces a **GEF 1.1** (Graph Exchange Format) JSON file:

```json
{
  "graphId": "payment-service-project",
  "nodes": [
    { "id": "com.example.OrderService#placeOrder", "type": "METHOD", "name": "...", "data": {...} },
    { "id": "endpoint:POST /api/orders",           "type": "ENDPOINT", "name": "...", "data": {...} },
    { "id": "topic:order.events",                  "type": "TOPIC", "name": "...", "data": {...} }
  ],
  "edges": [
    { "from": "endpoint:POST /api/orders", "to": "...OrderService#placeOrder", "type": "HANDLES" },
    { "from": "...OrderService#placeOrder", "to": "topic:order.events", "type": "PRODUCES" }
  ]
}
```

#### Key Technologies

- **JavaParser** — AST parsing of Java source files
- **Picocli** — CLI argument parsing
- **Jackson** — JSON serialization
- **SLF4J + Logback** — Logging
- **Maven Shade** — Fat JAR packaging

#### Sample Project

Includes `sample/greens-order/` — a sample Spring/Kafka order service used to test the scanner.

---

### 7.2 flow-engine — The Pure Java Processing Library

**Purpose:** Core graph processing engine. Transforms raw graphs into enriched, zoomable, queryable models.

**Type:** Pure Java library JAR. **No Spring, no HTTP, no frameworks.**

#### Pipeline

```
Input (flow.json) → Load → Zoom → Validate → Extract → [Merge Runtime] → Export
```

| Stage | Component | What It Does |
|-------|-----------|-------------|
| **Load** | `StaticGraphLoader` | Parses GEF JSON into `CoreGraph` (nodes + edges) |
| **Zoom** | `ZoomEngine` + `ZoomPolicy` | Assigns zoom levels 1–5 based on node type |
| **Validate** | `GraphValidator` | Checks structure integrity, zoom assignment, edge references |
| **Extract** | `FlowExtractor` | BFS traversal from endpoint nodes to discover complete flows |
| **Merge** | `MergeEngine` | Merges runtime events (traces, durations, errors) into static graph |
| **Export** | `Neo4jExporter`, `GEFExporter` | Generates Cypher queries or GEF JSON output |

#### Runtime Engine

A complete runtime event processing system:

| Component | Responsibility |
|-----------|---------------|
| `RuntimeEvent` | Model: traceId, timestamp, eventType, nodeId, spanId, data |
| `RuntimeTraceBuffer` | Thread-safe in-memory buffer, groups events by traceId, auto-expires |
| `RuntimeEngine` | Orchestrator: accepts events, triggers merge, extracts flows |
| `RuntimeFlowExtractor` | Generates ordered execution paths from events |
| `RuntimeEventHandler` | Strategy interface — extensible handlers per event type |
| `RuntimeEventHandlerRegistry` | Registry of handlers (method enter/exit, topic produce/consume, checkpoint, error) |

#### Zoom Level System

| Level | Name | Node Types | Use Case |
|-------|------|-----------|----------|
| 1 | BUSINESS | Endpoints, Topics | High-level architecture overview |
| 2 | SERVICE | Classes, Services | Service-level view |
| 3 | PUBLIC | Public methods | API-level detail |
| 4 | PRIVATE | Private methods | Implementation detail |
| 5 | RUNTIME | Runtime traces | Execution-level instrumentation |

#### Graph Data Model

```
CoreGraph
  ├── id: String
  ├── nodes: Map<String, CoreNode>
  │     ├── id, name, type (NodeType enum)
  │     ├── visibility (PUBLIC/PRIVATE/PROTECTED)
  │     ├── zoom (1-5, starts at -1 unassigned)
  │     └── data: Map<String, Object> (arbitrary metadata)
  └── edges: List<CoreEdge>
        ├── id, from, to
        ├── type (EdgeType enum: CALL, HANDLES, PRODUCES, CONSUMES, BELONGS_TO, DEFINES, RUNTIME_CALL, ASYNC_HOP)
        └── data: Map<String, Object>
```

#### Design Patterns Used

- **Strategy Pattern** — `GraphExporter` interface + `ExporterFactory` for pluggable export formats
- **Strategy Pattern** — `RuntimeEventHandler` interface for extensible event processing
- **Registry Pattern** — `RuntimeEventHandlerRegistry` for handler lookup
- **Builder Pattern** — Graph construction via `CoreGraph` methods
- **Pipeline Pattern** — Ordered processing stages in `FlowCoreEngine`

---

### 7.3 flow-core-service — The Central Microservice

**Purpose:** Spring Boot HTTP service that wraps `flow-engine`, adds queuing, persistence, monitoring, and REST APIs.

**Type:** Spring Boot 3.2 microservice (Java 21).

#### REST API

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/ingest/static` | POST | Accept static graph from adapter |
| `/ingest/runtime` | POST | Accept runtime events |
| `/graphs` | GET | List all stored graphs |
| `/graphs/{graphId}` | GET | Get complete graph |
| `/flow/{graphId}?zoom=N` | GET | Get zoom-filtered graph view |
| `/trace/{traceId}` | GET | Get trace timeline with events |
| `/export/neo4j/{graphId}` | GET | Export graph as Cypher / push to Neo4j |
| `/actuator/health` | GET | Health check |
| `/actuator/prometheus` | GET | Prometheus metrics |
| `/swagger-ui.html` | GET | Interactive API docs |

#### Internal Architecture

| Package | Components | Responsibility |
|---------|-----------|---------------|
| `api.controller` | `StaticIngestController`, `RuntimeIngestController`, `GraphController`, `TraceController`, `ExportController` | HTTP request/response handling |
| `api.dto` | Request/response DTOs | API contract models |
| `api.advice` | Global exception handlers | Error response formatting |
| `api.health` | Custom health indicators | System health reporting |
| `ingest` | `IngestionQueue`, `IngestionWorker`, `StaticGraphHandler`, `RuntimeEventHandler` | Async ingestion pipeline with backpressure |
| `engine` | `GraphStore`, `InMemoryGraphStore`, `MergeEngineAdapter`, `FlowExtractorAdapter` | Adapters over flow-engine library |
| `runtime` | `RuntimeTraceBuffer`, `InMemoryRuntimeTraceBuffer`, `EventDeduplicator` | Runtime event buffering & dedup |
| `persistence` | `Neo4jWriter`, `CypherBuilder` | Neo4j export & persistence |
| `config` | Configuration beans | Spring configuration |

#### Key Non-Functional Features

- **Bounded ingestion queue** with backpressure (configurable capacity + threshold)
- **In-memory primary store** for sub-50ms query response
- **Async Neo4j export** — DB issues never block ingestion
- **Event deduplication** — tolerates duplicate/out-of-order events
- **Prometheus metrics** — ingestion rates, queue depth, latency
- **OpenAPI/Swagger** — auto-generated API documentation
- **Configurable TTLs** — trace expiration, graph retention

---

### 7.4 flow-core — The Original Spring Boot Prototype

**Purpose:** The **first iteration** of the Flow platform — a monolithic Spring Boot application that combined graph processing, HTTP endpoints, and Neo4j persistence in one project.

**Type:** Spring Boot 4.0 application with Neo4j integration.

**Status:** **Superseded** by the `flow-engine` + `flow-core-service` split. Retained for reference and potential Neo4j-native features.

#### What It Contains

| Package | Components |
|---------|-----------|
| `controller` | `GraphController`, `FlowController`, `ZoomController`, `ExportController` |
| `service` | `GraphService` (in-memory ConcurrentHashMap store) |
| `graph` | `CoreGraph`, `CoreNode`, `CoreEdge`, `GraphValidator` |
| `ingest` | `StaticGraphLoader` |
| `zoom` | Zoom engine and policies |
| `flow` | Flow extraction |
| `export` | Neo4j export |

#### Why It Was Superseded

The original `flow-core` had tight coupling between:
- Graph processing logic (pure business logic)
- Spring Boot framework (HTTP, dependency injection)
- Neo4j persistence (database layer)

This made the core logic **hard to test**, **hard to reuse**, and **hard to evolve independently**. The refactoring into `flow-engine` (pure library) + `flow-core-service` (infrastructure wrapper) follows clean architecture principles and allows:
- The engine to be embedded in any Java application
- The service to swap persistence layers without touching business logic
- Independent versioning and testing

---

## 8. Key Concepts

### 8.1 The Graph Model (GEF 1.1 Format)

All projects share a common graph exchange format:

```
Graph
 ├── graphId: "payment-service"
 ├── nodes[]
 │    ├── ENDPOINT  — REST endpoints (POST /orders, GET /users)
 │    ├── METHOD    — Public Java methods
 │    ├── PRIVATE_METHOD — Private Java methods
 │    ├── CLASS     — Java classes
 │    ├── SERVICE   — Logical service groupings
 │    └── TOPIC     — Kafka topics
 └── edges[]
      ├── CALL        — Method invokes method
      ├── HANDLES     — Endpoint handled by method
      ├── PRODUCES    — Method publishes to topic
      ├── CONSUMES    — Method consumes from topic
      ├── BELONGS_TO  — Method belongs to class
      ├── DEFINES     — Class defines method
      ├── RUNTIME_CALL— Runtime-observed method call
      └── ASYNC_HOP   — Asynchronous message hop (Kafka)
```

### 8.2 Zoom Levels — Progressive Disclosure

Zoom levels let users drill into or out of detail:

```
Zoom 1 (BUSINESS)   →  "Show me endpoints and topics"
Zoom 2 (SERVICE)    →  "Show me classes and services too"
Zoom 3 (PUBLIC)     →  "Show me public method calls"
Zoom 4 (PRIVATE)    →  "Show me private implementation"
Zoom 5 (RUNTIME)    →  "Show me actual execution traces"
```

### 8.3 Static + Runtime Merge

The merge process combines build-time and runtime knowledge:

```
STATIC GRAPH (what could happen)
  + RUNTIME EVENTS (what did happen)
  ────────────────────────────────
  = ENRICHED GRAPH
      • Actual execution paths highlighted
      • Duration annotations (ms)
      • Error annotations (exceptions)
      • Checkpoint data (developer markers)
      • Async hop stitching (Kafka producer ↔ consumer linked)
```

---

## 9. Technology Stack

| Layer | Technology | Used By |
|-------|-----------|---------|
| **Language** | Java 17 / 21 | All projects |
| **Build** | Maven | All projects |
| **Source Parsing** | JavaParser 3.26 | flow-java-adapter |
| **CLI** | Picocli 4.7 | flow-java-adapter |
| **Plugin System** | Java SPI (ServiceLoader) | flow-java-adapter |
| **JSON** | Jackson 2.16–2.20 | All projects |
| **Web Framework** | Spring Boot 3.2 | flow-core-service |
| **API Docs** | SpringDoc OpenAPI 2.3 | flow-core-service |
| **Metrics** | Micrometer + Prometheus | flow-core-service |
| **Graph Database** | Neo4j (driver 5.15) | flow-core-service, flow-core |
| **Testing** | JUnit 5, Spring Boot Test | All projects |
| **Logging** | SLF4J + Logback | All projects |

---

## 10. Build & Run Guide

### Prerequisites

- **Java 17+** (Java 21 recommended for flow-core-service)
- **Maven 3.8+**
- **Neo4j** (optional, for graph export)

### Build Order (respects dependencies)

```bash
# 1. Build the engine library first (installs to local Maven repo)
cd flow-engine
mvn clean install

# 2. Build the adapter (standalone, no internal dependencies)
cd ../flow-java-adapter
mvn -T 1C -DskipTests clean package

# 3. Build the core service (depends on flow-engine)
cd ../flow-core-service
mvn clean package

# 4. (Optional) Build the original prototype
cd ../flow-core
mvn clean package
```

### Run the Full Pipeline

```bash
# Step 1: Scan a Java project to produce flow.json
cd flow-java-adapter
java -jar flow-runner/target/flow-runner-0.3.0.jar scan \
  --src /path/to/your/java/project/src \
  --out flow.json \
  --project my-service

# Step 2: Start the core service
cd ../flow-core-service
java -jar target/flow-core-service-0.0.1-SNAPSHOT.jar

# Step 3: Ingest the static graph
curl -X POST http://localhost:8080/ingest/static \
  -H "Content-Type: application/json" \
  -d @flow.json

# Step 4: Query the graph
curl http://localhost:8080/graphs
curl http://localhost:8080/flow/my-service?zoom=2

# Step 5: Export to Neo4j
curl http://localhost:8080/export/neo4j/my-service?mode=cypher
```

---

## 11. Repository Map

```
Flow/
│
├── flow-java-adapter/              ← SOURCE CODE SCANNER
│   ├── flow-adapter/               ← Core framework (GraphModel, ScanCommand, plugins SPI)
│   ├── flow-spring-plugin/         ← Spring annotation scanner plugin
│   ├── flow-kafka-plugin/          ← Kafka annotation scanner plugin
│   ├── flow-runner/                ← Executable shaded JAR
│   ├── sample/greens-order/        ← Sample Spring/Kafka project for testing
│   ├── flow.json                   ← Example scanner output
│   └── pom.xml                     ← Parent POM (multi-module)
│
├── flow-engine/                    ← PURE JAVA GRAPH ENGINE
│   └── src/main/java/com/flow/core/
│       ├── FlowCoreEngine.java     ← Main orchestrator
│       ├── graph/                  ← CoreGraph, CoreNode, CoreEdge, Validator
│       ├── ingest/                 ← StaticGraphLoader, MergeEngine
│       ├── runtime/                ← RuntimeEngine, TraceBuffer, EventHandlers
│       ├── zoom/                   ← ZoomEngine, ZoomPolicy, ZoomLevel
│       ├── flow/                   ← FlowExtractor, FlowModel, FlowStep
│       └── export/                 ← Neo4jExporter, ExporterFactory
│
├── flow-core-service/              ← SPRING BOOT MICROSERVICE
│   └── src/main/java/com/flow/core/service/
│       ├── FlowCoreServiceApplication.java
│       ├── api/controller/         ← REST controllers (5 controllers)
│       ├── api/dto/                ← Request/response models
│       ├── api/advice/             ← Global error handling
│       ├── api/health/             ← Health indicators
│       ├── ingest/                 ← IngestionQueue, Workers, Handlers
│       ├── engine/                 ← Adapters over flow-engine
│       ├── runtime/                ← TraceBuffer, Deduplicator
│       ├── persistence/            ← Neo4j writer, Cypher builder
│       └── config/                 ← Spring configuration
│
├── flow-core/                      ← ORIGINAL PROTOTYPE (superseded)
│   └── src/main/java/com/flow/core/
│       ├── FlowCoreApplication.java  ← Spring Boot main
│       ├── controller/             ← Graph, Flow, Zoom, Export controllers
│       ├── service/                ← GraphService (in-memory store)
│       ├── graph/                  ← Core data model
│       ├── ingest/                 ← Static graph loader
│       ├── zoom/                   ← Zoom engine
│       ├── flow/                   ← Flow extraction
│       └── export/                 ← Neo4j export
│
└── FLOW_PROJECT_DOCUMENTATION.md   ← THIS FILE
```

---

## 12. Dependency Graph

```
                    ┌──────────────────────────┐
                    │    flow-java-adapter      │
                    │    (com.flow:flow-parent)  │
                    │    v0.3.0                  │
                    └────────────┬─────────────┘
                                 │
                          produces flow.json
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │    flow-core-service      │
                    │    (com.flow:flow-core-   │
                    │     service)              │
                    │    v0.0.1-SNAPSHOT        │
                    └────────────┬─────────────┘
                                 │
                     Maven dependency (compile)
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │    flow-engine            │
                    │    (com.flow:flow-engine) │
                    │    v1.0-SNAPSHOT          │
                    └──────────────────────────┘


                    ┌──────────────────────────┐
                    │    flow-core              │
                    │    (com.flow:flow-core)   │
                    │    v0.0.1-SNAPSHOT        │
                    │    [STANDALONE/LEGACY]     │
                    └──────────────────────────┘
```

**Internal dependencies of flow-java-adapter:**
```
flow-runner
  ├── flow-adapter (core framework)
  ├── flow-spring-plugin
  │     └── flow-adapter
  └── flow-kafka-plugin
        └── flow-adapter
```

---

## 13. Current Status & Roadmap

### What's Built ✅

| Component | Status |
|-----------|--------|
| **flow-java-adapter** — Spring & Kafka scanning, CLI runner, GEF export | ✅ Complete |
| **flow-engine** — Full pipeline: load, zoom, validate, extract, merge, export | ✅ Complete |
| **flow-engine** — Runtime engine: events, trace buffer, merge, flow extraction | ✅ Complete |
| **flow-core-service** — REST API, ingestion queue, graph store, trace buffer, Neo4j export | ✅ Complete |
| **flow-core** — Original prototype | ✅ Complete (superseded) |

### What's Next 🔄

| Feature | Project | Priority |
|---------|---------|----------|
| **Flow UI** — Web-based graph visualization | New project | High |
| **Flow Runtime Plugin** — Java agent for automatic instrumentation | New project | High |
| **Additional language adapters** (Python, Node.js, .NET) | flow-*-adapter | Medium |
| **Graph compression/summarization** | flow-engine | Medium |
| **Cycle detection and handling** | flow-engine | Medium |
| **Path finding** (shortest path, all paths) | flow-engine | Medium |
| **Community detection** (graph clustering) | flow-engine | Low |
| **Multi-tenancy** | flow-core-service | Future |
| **Persistent storage** (beyond in-memory) | flow-core-service | Future |
| **GraphML / Protobuf export formats** | flow-engine | Future |
| **Custom zoom policies** | flow-engine | Future |

---

> **This document provides a living overview of the Flow platform. As projects evolve, update the relevant sections to keep this documentation current.**

