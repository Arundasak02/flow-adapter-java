# Flow Runtime Plugin — Architecture & Design Document

**Version:** 0.1 (Draft — Brainstorming)  
**Date:** March 7, 2026  
**Status:** Planning  
**Authors:** Flow Team

---

## Table of Contents

1. [The Problem Statement](#1-the-problem-statement)
2. [What We Have Today](#2-what-we-have-today)
3. [What Is Missing](#3-what-is-missing)
4. [Design Goals for a SaaS Product](#4-design-goals-for-a-saas-product)
5. [Architecture — The Big Picture](#5-architecture--the-big-picture)
6. [The Three-Layer Agent Architecture](#6-the-three-layer-agent-architecture)
7. [Layer 1 — Bytecode Instrumentation Engine](#7-layer-1--bytecode-instrumentation-engine)
8. [Layer 2 — Event Pipeline (In-Process)](#8-layer-2--event-pipeline-in-process)
9. [Layer 3 — Transport & Delivery](#9-layer-3--transport--delivery)
10. [nodeId Contract — Static ↔ Runtime Matching](#10-nodeid-contract--static--runtime-matching)
11. [Trace Context Propagation](#11-trace-context-propagation)
12. [Kafka / Async Hop Stitching](#12-kafka--async-hop-stitching)
13. [Filtering & Noise Reduction](#13-filtering--noise-reduction)
14. [Sampling Strategy](#14-sampling-strategy)
15. [Resilience & Failure Modes](#15-resilience--failure-modes)
16. [Performance Budget](#16-performance-budget)
17. [Security Considerations](#17-security-considerations)
18. [Packaging & Distribution](#18-packaging--distribution)
19. [Configuration Model](#19-configuration-model)
20. [Module Structure](#20-module-structure)
21. [Phased Delivery Plan](#21-phased-delivery-plan)
22. [Open Questions](#22-open-questions)

---

## 1. The Problem Statement

Today the Flow platform can:
- ✅ **Scan** Java source code → produce `flow.json` (static graph) via `flow-java-adapter`
- ✅ **Receive** runtime events via `POST /ingest/runtime` on `flow-core-service`
- ✅ **Merge** static + runtime graphs via `MergeEngine` in `flow-engine`
- ✅ **Serve** enriched graphs via REST API with zoom-level filtering

But **nobody is sending runtime events**. The receiver exists. The emitter does not.

Without the runtime plugin, the Flow platform is blind to:
- What actually executed at runtime (vs what could execute)
- How long each method took
- Which paths threw errors
- How Kafka producer ↔ consumer hops actually connected
- What the real hot paths and bottlenecks are

The runtime plugin is the **last critical piece** that closes the static ↔ runtime feedback loop.

---

## 2. What We Have Today

### flow-core-service — The Receiver

```
POST /ingest/runtime
  Body: {
    "graphId": "payment-service",
    "traceId": "req-123",
    "events": [
      {
        "traceId": "req-123",
        "timestamp": 1735412200000,
        "type": "METHOD_ENTER",
        "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
        "spanId": "span-1",
        "parentSpanId": "span-0",
        "data": {}
      }
    ]
  }
```

### flow-engine — The Processor

Already handles these event types:
- `METHOD_ENTER` / `METHOD_EXIT` → duration calculation
- `PRODUCE_TOPIC` / `CONSUME_TOPIC` → async hop stitching
- `CHECKPOINT` → developer markers
- `ERROR` → exception annotations

### The nodeId Contract (from flow.json)

The static graph uses fully-qualified method signatures as node IDs:
```
com.greens.order.core.OrderService#placeOrder(String):String
com.greens.order.core.OrderService#validateCart(String):void
com.greens.order.core.PaymentService#charge(String):void
endpoint:POST /api/orders/{id}
topic:orders.v1
```

**The runtime plugin MUST emit the exact same nodeId format** so that `MergeEngine` can link runtime events to static graph nodes.

---

## 3. What Is Missing

```
┌─────────────────────────────────────────────────────────┐
│              THE GAP                                     │
│                                                          │
│   Customer's Running Application                         │
│      │                                                   │
│      │  ← WHO INTERCEPTS METHOD CALLS?                  │
│      │  ← WHO CAPTURES TIMINGS?                         │
│      │  ← WHO PROPAGATES TRACE CONTEXT?                 │
│      │  ← WHO BATCHES & SHIPS EVENTS?                   │
│      │  ← WHO HANDLES KAFKA HEADER INJECTION?           │
│      │  ← WHO DEALS WITH SPRING PROXIES / CGLIB?        │
│      │  ← WHO MANAGES BACKPRESSURE?                     │
│      │  ← WHO DOES ALL THIS WITH < 3% OVERHEAD?         │
│      │                                                   │
│      ▼                                                   │
│   POST /ingest/runtime → flow-core-service               │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 4. Design Goals for a SaaS Product

This is NOT a POC. It will run inside **customer production JVMs**. The bar is high.

### Non-Negotiable Requirements

| # | Requirement | Why |
|---|-------------|-----|
| 1 | **Zero code changes** in customer app | Adoption friction kills SaaS products. `-javaagent` attachment only. |
| 2 | **< 3% latency overhead** | Customers won't tolerate visible performance degradation in production. |
| 3 | **< 50MB memory overhead** | Must not steal heap from the application. |
| 4 | **Graceful degradation** | If FCS is down, the customer app MUST NOT be affected. Events are dropped, not queued forever. |
| 5 | **Thread-safe, async-safe** | Must work with `@Async`, `CompletableFuture`, virtual threads (Java 21), reactive. |
| 6 | **Exact nodeId matching** | Runtime node IDs must precisely match static graph node IDs from `flow.json`. |
| 7 | **Framework-aware** | Must understand Spring proxies (CGLIB), Kafka interceptors, RestTemplate/WebClient/Feign. |
| 8 | **Configurable filtering** | Customers control what gets traced (by package, annotation, class, method). |
| 9 | **Adaptive sampling** | 100% trace in dev/staging, rate-limited in production. |
| 10 | **Secure** | No sensitive data captured by default. PII masking. TLS to FCS. |
| 11 | **Observable** | The agent itself must expose health/metrics (via JMX or log). |
| 12 | **Backward compatible** | Must work with Java 17 and Java 21+. |

---

## 5. Architecture — The Big Picture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CUSTOMER'S JVM                                        │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                  FLOW RUNTIME AGENT                               │   │
│  │              (-javaagent:flow-agent.jar)                          │   │
│  │                                                                   │   │
│  │   ┌─────────────────────────────────────────────────────────┐    │   │
│  │   │  LAYER 1: INSTRUMENTATION ENGINE                        │    │   │
│  │   │                                                          │    │   │
│  │   │  ByteBuddy Transformer                                   │    │   │
│  │   │    ├── MethodInterceptor (enter/exit/error)             │    │   │
│  │   │    ├── KafkaProducerInterceptor (header injection)      │    │   │
│  │   │    ├── KafkaConsumerInterceptor (header extraction)     │    │   │
│  │   │    ├── SpringProxyResolver (CGLIB → real class)         │    │   │
│  │   │    └── HttpClientInterceptor (trace propagation)        │    │   │
│  │   │                                                          │    │   │
│  │   │  Filter Chain:                                           │    │   │
│  │   │    PackageFilter → AnnotationFilter → MethodFilter       │    │   │
│  │   │                                                          │    │   │
│  │   │  nodeId Builder:                                         │    │   │
│  │   │    (class, method, params) → exact flow.json nodeId      │    │   │
│  │   └──────────────────────────┬──────────────────────────────┘    │   │
│  │                               │ raw events (METHOD_ENTER, etc.)  │   │
│  │                               ▼                                  │   │
│  │   ┌─────────────────────────────────────────────────────────┐    │   │
│  │   │  LAYER 2: EVENT PIPELINE (in-process)                   │    │   │
│  │   │                                                          │    │   │
│  │   │  ┌──────────┐   ┌──────────┐   ┌──────────────────┐    │    │   │
│  │   │  │ Sampling  │──▶│ Ring     │──▶│ Duration         │    │    │   │
│  │   │  │ Decision  │   │ Buffer   │   │ Calculator       │    │    │   │
│  │   │  │           │   │ (LMAX    │   │ (pair ENTER/EXIT │    │    │   │
│  │   │  │ head-based│   │ Disruptor│   │  compute ms)     │    │    │   │
│  │   │  │ sampling) │   │ or       │   │                  │    │    │   │
│  │   │  │           │   │ bounded  │   │                  │    │    │   │
│  │   │  └──────────┘   │ queue)   │   └──────────────────┘    │    │   │
│  │   │                  └──────────┘                            │    │   │
│  │   │                       │                                  │    │   │
│  │   │                       ▼                                  │    │   │
│  │   │              ┌──────────────────┐                        │    │   │
│  │   │              │  Batch Assembler │                        │    │   │
│  │   │              │  (time OR count  │                        │    │   │
│  │   │              │   trigger)       │                        │    │   │
│  │   │              └────────┬─────────┘                        │    │   │
│  │   └───────────────────────┼──────────────────────────────────┘    │   │
│  │                           │ batch of events                       │   │
│  │                           ▼                                       │   │
│  │   ┌─────────────────────────────────────────────────────────┐    │   │
│  │   │  LAYER 3: TRANSPORT & DELIVERY                          │    │   │
│  │   │                                                          │    │   │
│  │   │  ┌──────────────┐   ┌────────────┐   ┌──────────┐      │    │   │
│  │   │  │ Async HTTP   │   │ Circuit    │   │ Retry    │      │    │   │
│  │   │  │ Sender       │──▶│ Breaker    │──▶│ (limited)│      │    │   │
│  │   │  │ (non-blocking│   │ (half-open │   │          │      │    │   │
│  │   │  │  HttpClient) │   │  probe)    │   │          │      │    │   │
│  │   │  └──────────────┘   └────────────┘   └──────────┘      │    │   │
│  │   │                                                          │    │   │
│  │   │  Fallback: DROP (never block the app)                    │    │   │
│  │   └──────────────────────────────────────────────────────────┘    │   │
│  │                                                                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                  CUSTOMER APPLICATION                             │   │
│  │  Spring Boot / Kafka / any Java app — ZERO changes               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└──────────────────────────────────────┬──────────────────────────────────┘
                                       │
                              HTTP POST /ingest/runtime
                              (batched, compressed, async)
                                       │
                                       ▼
                          ┌─────────────────────────┐
                          │    FLOW CORE SERVICE     │
                          │    (external service)    │
                          └─────────────────────────┘
```

---

## 6. The Three-Layer Agent Architecture

### Why Three Layers?

Each layer has a distinct concern with different performance characteristics:

| Layer | Concern | Thread Model | Failure Mode |
|-------|---------|-------------|--------------|
| **L1: Instrumentation** | Intercept bytecode, build events | Runs on the APPLICATION thread — must be nanosecond-fast | Must never throw — silent catch |
| **L2: Event Pipeline** | Buffer, pair, batch, sample | Runs on a DEDICATED agent thread — decoupled from app | Ring buffer overflow → drop oldest |
| **L3: Transport** | Ship to FCS over HTTP | Runs on a DAEMON thread — fully async | Circuit break → drop batch |

**Critical design rule:** Layer 1 code runs on customer request threads. It must NEVER allocate objects excessively, NEVER block, NEVER throw exceptions that propagate to the application.

---

## 7. Layer 1 — Bytecode Instrumentation Engine

### 7.1 Why ByteBuddy (Not ASM, Not AspectJ)

| Tool | Approach | Why Not / Why Yes |
|------|----------|-------------------|
| **ASM** | Raw bytecode manipulation | Too low-level for SaaS product maintenance. Error-prone, hard to debug. |
| **AspectJ** | Compile-time or load-time weaving | Requires AspectJ runtime in customer classpath. Conflicts with Spring AOP. |
| **ByteBuddy** | High-level bytecode generation | ✅ **Best fit.** Agent-friendly API. Used by Elastic APM, Datadog, New Relic. Handles proxies, generics, lambdas. Java 17-21 compatible. |

### 7.2 What Gets Instrumented

ByteBuddy transforms classes at load time. We install **Advice** on methods that match our filter criteria.

```
For each class loaded by the JVM:
  1. Does it match package filter? (e.g., com.greens.order.*)
  2. Is it a Spring proxy? → resolve to real class
  3. For each method in the class:
     a. Does it match method filter? (not toString, hashCode, etc.)
     b. Does it match annotation filter? (optional: only @Service, @Component, etc.)
  4. If yes → install ENTER/EXIT/ERROR advice
```

### 7.3 Advice Code (What Gets Injected)

ByteBuddy `@Advice` is inlined into the target method's bytecode. It does NOT create proxy objects or extra stack frames.

**Conceptual advice (not actual code, for illustration):**

```
@Advice.OnMethodEnter
static long onEnter(@Advice.Origin String methodDescriptor) {
    if (!FlowSampler.shouldTrace()) return 0L;
    long enterTime = System.nanoTime();
    FlowContext.pushSpan(methodDescriptor);
    return enterTime;
}

@Advice.OnMethodExit(onThrowable = Throwable.class)
static void onExit(
    @Advice.Enter long enterTime,
    @Advice.Origin String methodDescriptor,
    @Advice.Thrown Throwable error) {

    if (enterTime == 0L) return; // not sampled
    long durationNs = System.nanoTime() - enterTime;
    String nodeId = NodeIdBuilder.buildNodeId(methodDescriptor);
    FlowEventSink.emit(nodeId, durationNs, error);
    FlowContext.popSpan();
}
```

**Key properties:**
- `@Advice` is inlined — no extra method call overhead
- `System.nanoTime()` — cheapest way to capture timing (~25ns)
- `FlowEventSink.emit()` — writes to lock-free ring buffer, never blocks
- If anything fails → silently caught, never propagates to customer code

### 7.4 Spring Proxy Resolution

Spring creates CGLIB proxies like `OrderService$$SpringCGLIB$$0`. The runtime class name doesn't match the static graph nodeId `com.greens.order.core.OrderService`.

**Solution: Resolve at instrumentation time:**
```
Class loaded: OrderService$$SpringCGLIB$$0
  → superclass: OrderService
  → use "com.greens.order.core.OrderService" as the nodeId prefix
```

ByteBuddy can inspect the class hierarchy at transform time. We resolve once per class load, not per method call.

### 7.5 Interceptors for Framework-Specific Behavior

Beyond general method advice, we need specialized interceptors:

| Interceptor | Target | What It Does |
|-------------|--------|-------------|
| `KafkaProducerInterceptor` | `KafkaProducer.send()` | Injects traceId + spanId into Kafka record headers |
| `KafkaConsumerInterceptor` | `@KafkaListener` methods | Extracts traceId from incoming record headers, starts new span |
| `RestTemplateInterceptor` | `RestTemplate.exchange()` | Injects traceId into outbound HTTP headers |
| `WebClientInterceptor` | `WebClient` filter chain | Same for reactive HTTP calls |
| `FeignInterceptor` | `@FeignClient` methods | Same for Feign calls |
| `AsyncInterceptor` | `@Async` / `CompletableFuture` | Propagates trace context across thread boundary |

---

## 8. Layer 2 — Event Pipeline (In-Process)

### 8.1 Ring Buffer Design

Events from Layer 1 must be moved off the application thread as fast as possible. A **lock-free ring buffer** (inspired by LMAX Disruptor pattern) achieves this:

```
Application Thread                  Agent Pipeline Thread
      │                                    │
      │  emit(event) ──→ [ring buffer] ──→ drain()
      │    ~50ns             lock-free        │
      │    never blocks      bounded          │
      │                      overwrites       │
      │                      if full          │
      │                      (DROP policy)    │
      │                                    batch & ship
```

**Why not `BlockingQueue`?**
- `BlockingQueue.offer()` can contend under high throughput
- A single-producer ring buffer (one app thread writes, one agent thread reads) is wait-free
- Disruptor-style achieves ~50ns per event publish

**In practice:** We can start with `ConcurrentLinkedQueue` or a bounded `ArrayBlockingQueue` with `offer()` (non-blocking put) and evolve to Disruptor if benchmarks demand it. The interface stays the same.

### 8.2 Duration Pairing

Layer 1 emits raw ENTER/EXIT events. Layer 2 pairs them:

```
ENTER(OrderService#placeOrder, t=1000ns)
  ... other events ...
EXIT(OrderService#placeOrder, t=1030ns)

→ Paired event: {
    nodeId: "...OrderService#placeOrder(String):String",
    type: METHOD_EXIT,
    durationMs: 30,
    error: null
  }
```

**Optimization:** Rather than emitting both ENTER and EXIT to FCS, Layer 2 can **collapse** them into a single `METHOD_EXECUTION` event with `durationMs` pre-computed. This halves the event volume sent over the wire.

### 8.3 Batch Assembler

Events accumulate in the buffer. The batch assembler triggers a flush when EITHER:
- **Count threshold** reached (e.g., 100 events)
- **Time threshold** elapsed (e.g., 200ms)

Whichever comes first. This balances latency vs efficiency:
- High-traffic: batches fill by count → shipped quickly
- Low-traffic: timer fires → shipped within 200ms

---

## 9. Layer 3 — Transport & Delivery

### 9.1 HTTP Client

Uses Java's built-in `java.net.http.HttpClient` (available since Java 11):
- **Async** — `sendAsync()` returns `CompletableFuture`
- **Non-blocking** — never blocks the agent pipeline thread
- **No external dependency** — no OkHttp, no Apache HttpClient

### 9.2 Payload Format

```json
{
  "graphId": "payment-service",
  "agentVersion": "0.1.0",
  "hostInfo": { "hostname": "prod-node-3", "pid": 12345 },
  "batch": [
    {
      "traceId": "req-abc-123",
      "spanId": "span-7",
      "parentSpanId": "span-3",
      "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
      "type": "METHOD_EXIT",
      "timestamp": 1735412200100,
      "durationMs": 30,
      "data": {}
    },
    {
      "traceId": "req-abc-123",
      "spanId": "span-8",
      "parentSpanId": "span-7",
      "nodeId": "com.greens.order.core.OrderService#validateCart(String):void",
      "type": "METHOD_EXIT",
      "timestamp": 1735412200050,
      "durationMs": 5,
      "data": {}
    }
  ]
}
```

**Compression:** GZIP the body. Typical 5–10x compression ratio for JSON event batches.

### 9.3 Circuit Breaker

If FCS is down, we must NOT:
- Queue events indefinitely (OOM risk)
- Retry aggressively (DDoS the recovering service)
- Log excessively (spam customer logs)

**Circuit breaker states:**

```
CLOSED (normal)  ──→  OPEN (after 3 consecutive failures)
                           │
                    wait 30 seconds
                           │
                       HALF-OPEN (send 1 probe request)
                           │
                    ┌──────┴──────┐
                    ▼             ▼
                 SUCCESS       FAILURE
                 → CLOSED      → OPEN (reset timer)
```

**When circuit is OPEN:** Events are DROPPED silently. No buffering, no retry. The customer app is completely unaffected.

### 9.4 Retry Policy

When circuit is CLOSED or HALF-OPEN:
- **Max retries:** 1 (total 2 attempts)
- **Retry delay:** 500ms
- **Retryable:** 5xx, connection timeout
- **Not retryable:** 4xx (bad payload — log once, drop)

---

## 10. nodeId Contract — Static ↔ Runtime Matching

This is the **most critical design challenge**. If nodeIds don't match, the merge produces disconnected graphs.

### 10.1 The Format

From `flow.json` (produced by `flow-java-adapter`):
```
{className_FQCN}#{methodName}({paramTypes}):{returnType}
```

Examples from our sample graph:
```
com.greens.order.core.OrderService#placeOrder(String):String
com.greens.order.core.OrderService#validateCart(String):void
com.greens.order.core.PaymentService#charge(String):void
com.greens.order.config.KafkaProducerConfig#kafkaTemplate():KafkaTemplate<String, String>
```

### 10.2 What the Agent Sees at Runtime

At runtime, ByteBuddy gives us:
- `Class<?>` — the actual loaded class (may be CGLIB proxy)
- `Method` or method descriptor — name + parameter types + return type

### 10.3 The NodeId Builder — Translation Rules

```
Input from JVM:
  class = com.greens.order.core.OrderService$$SpringCGLIB$$0
  method = placeOrder
  paramTypes = [java.lang.String]
  returnType = java.lang.String

Step 1: Resolve proxy → com.greens.order.core.OrderService
Step 2: Simplify param types → String (strip java.lang.)
Step 3: Simplify return type → String
Step 4: Handle generics → KafkaTemplate<String, String> (preserve from erasure? OR match erased form)

Output: com.greens.order.core.OrderService#placeOrder(String):String
```

### 10.4 Known Hard Cases

| Case | Problem | Solution |
|------|---------|---------|
| **CGLIB proxy** | `OrderService$$SpringCGLIB$$0` | Resolve superclass chain until we hit a non-proxy class |
| **Method overloading** | `placeOrder(String)` vs `placeOrder(String, User)` | Include param types in nodeId — already done |
| **Generics erasure** | Runtime: `KafkaTemplate` vs static: `KafkaTemplate<String, String>` | **This is a real problem.** Options: (a) adapter emits erased types too, (b) agent uses a lookup table from flow.json, (c) fuzzy match |
| **Lambdas** | `OrderService$$Lambda$42` | Skip — lambdas are not in the static graph |
| **Bridge methods** | JVM-generated bridge methods for generics | Skip — filter by `isBridge()` |
| **Default methods** | Interface default methods called on impl class | Use declaring class from the Method object |
| **Inherited methods** | `toString()` on `Object` | Filtered out by package filter |

### 10.5 Recommendation: Ship flow.json Manifest to Agent

**Big idea:** At startup, the agent downloads (or is given) the `flow.json` from FCS. It builds a **lookup table** of all known nodeIds. At runtime, instead of constructing nodeIds from scratch, it:

1. Builds a candidate nodeId from the runtime class/method
2. Looks it up in the manifest
3. If exact match → use it
4. If no match → try fuzzy match (erased generics, proxy resolution)
5. If still no match → this method is not in the static graph → skip it (don't emit)

**Benefits:**
- Guarantees nodeId match with static graph
- Acts as a **whitelist** — only methods in the static graph get instrumented
- Reduces event volume dramatically (no noise from framework internals)
- Solves the generics erasure problem completely

**Tradeoff:**
- Requires FCS connectivity at agent startup (or bundled flow.json file)
- Agent must re-sync when code changes and a new flow.json is ingested

---

## 11. Trace Context Propagation

### 11.1 The FlowContext (In-Process)

Each request thread needs a trace context:

```
FlowContext (ThreadLocal-based):
  ├── traceId: String (UUID, generated at entry point)
  ├── spanStack: Deque<SpanInfo>
  │     ├── spanId: String
  │     ├── parentSpanId: String
  │     └── startTimeNs: long
  └── sampled: boolean (decided at trace start, applies to all events in trace)
```

### 11.2 Entry Point Detection

A new trace starts when execution enters a "root" node:
- **HTTP endpoint** → Spring DispatcherServlet / Filter intercept
- **Kafka consumer** → `@KafkaListener` method entry
- **Scheduled job** → `@Scheduled` method entry

At entry, the agent:
1. Checks incoming HTTP/Kafka headers for an existing `flow-trace-id`
2. If found → continue existing trace (distributed)
3. If not found → generate new traceId + make sampling decision

### 11.3 Cross-Thread Propagation

| Scenario | Problem | Solution |
|----------|---------|---------|
| `@Async` | Spring runs the method on a different thread — ThreadLocal is empty | Intercept `AsyncTaskExecutor.submit()` → wrap `Runnable` with context snapshot |
| `CompletableFuture.supplyAsync()` | Same — new thread pool thread | Intercept `CompletableFuture.supplyAsync(supplier, executor)` → wrap supplier |
| `ExecutorService.submit()` | General thread pool | Intercept executor → wrap all submitted tasks |
| Virtual Threads (Java 21) | `ThreadLocal` works but `ScopedValue` is preferred | Support both — detect Java version at startup |
| Reactive (WebFlux) | Context propagates via `Context` / `ContextView` in Reactor | Intercept `Mono`/`Flux` subscription to inject FlowContext |

### 11.4 Cross-Service Propagation (HTTP)

When the customer app makes an outbound HTTP call:

```
Service A → RestTemplate.exchange("http://service-b/api/foo") → Service B

Agent intercepts RestTemplate.exchange():
  → Adds headers:
     flow-trace-id: req-abc-123
     flow-span-id: span-42
     flow-parent-span-id: span-7

Agent on Service B receives request:
  → Reads headers from HttpServletRequest
  → Sets FlowContext with existing traceId
  → Continues the trace
```

---

## 12. Kafka / Async Hop Stitching

This is the most complex propagation scenario and directly matches what `flow-engine`'s `MergeEngine` expects.

### 12.1 Producer Side

```
Customer code: kafkaTemplate.send("orders.v1", orderJson)

Agent intercepts KafkaProducer.send():
  1. Read current FlowContext (traceId, spanId)
  2. Inject into Kafka record headers:
     flow-trace-id: req-abc-123
     flow-span-id: span-15
  3. Emit event:
     {
       type: PRODUCE_TOPIC,
       nodeId: "topic:orders.v1",
       traceId: "req-abc-123",
       spanId: "span-15",
       timestamp: ...
     }
```

### 12.2 Consumer Side

```
Customer code: @KafkaListener(topics = "orders.v1")
               void onMessage(String message) { ... }

Agent intercepts @KafkaListener entry:
  1. Extract headers from ConsumerRecord:
     flow-trace-id: req-abc-123
     flow-span-id: span-15 (becomes parentSpanId)
  2. Set FlowContext with extracted traceId, new spanId
  3. Emit event:
     {
       type: CONSUME_TOPIC,
       nodeId: "com.greens.order.messaging.OrderConsumer#onMessage(String):void",
       traceId: "req-abc-123",
       spanId: "span-22",
       parentSpanId: "span-15",
       timestamp: ...
     }
```

### 12.3 How MergeEngine Stitches This

The `MergeEngine` in `flow-engine` sees:
- `PRODUCE_TOPIC` event → links to `topic:orders.v1` node
- `CONSUME_TOPIC` event → links to `OrderConsumer#onMessage` node
- Both share `traceId: req-abc-123`
- Creates `ASYNC_HOP` edge: `topic:orders.v1` → `OrderConsumer#onMessage`

This is already implemented in `flow-engine`. The agent just needs to emit the right events.

---

## 13. Filtering & Noise Reduction

### 13.1 Why Filtering Matters

A typical Spring Boot app loads 10,000+ classes. Most are framework internals:
- Spring framework classes (~3000)
- Jackson/JSON classes (~500)
- Hibernate/JPA classes (~1000)
- Servlet container, logging, etc.

We ONLY want to trace **customer business code** that appears in the static graph.

### 13.2 Filter Chain (Evaluated at Class Load Time)

```
Class being loaded
  │
  ├─ PackageIncludeFilter: Does package match include list?
  │    e.g., com.greens.order.**
  │    NO → skip class entirely (most classes eliminated here)
  │
  ├─ PackageExcludeFilter: Is it in exclude list?
  │    e.g., com.greens.order.config.internal.**
  │    YES → skip
  │
  ├─ ProxyFilter: Is it a CGLIB/JDK proxy?
  │    YES → resolve to real class, continue with resolved name
  │
  ├─ ManifestFilter (if flow.json loaded): Does this class have ANY methods in the static graph?
  │    NO → skip (most powerful filter — only traces what matters)
  │
  └─ PASS → Instrument this class
       │
       For each method:
       ├─ MethodExcludeFilter: toString, hashCode, equals, getters/setters?
       │    YES → skip
       ├─ BridgeMethodFilter: Is it a synthetic bridge method?
       │    YES → skip
       ├─ ManifestMethodFilter: Is this specific method in the static graph?
       │    NO → skip
       └─ PASS → Install advice on this method
```

### 13.3 Filter Performance

All filters run at **class load time**, not at method call time. Once a class is loaded and transformed (or skipped), there is ZERO per-call overhead from filtering. This is a key advantage of bytecode instrumentation over AOP — the decision is made once.

---

## 14. Sampling Strategy

### 14.1 Head-Based Sampling

Sampling decision is made at the **trace entry point** (the first event in a trace). All subsequent events in the same trace follow the same decision.

```
New request arrives at POST /api/orders/{id}
  │
  Sampler.shouldSample()?
  │
  ├─ YES (record this trace) → all methods in this request are traced
  │
  └─ NO (skip this trace) → zero overhead for this request (no events emitted at all)
```

**Why head-based, not tail-based?**
- Head-based: sampling decision is cheap (one check per request)
- Tail-based: requires buffering ALL events, then deciding — too memory-heavy for the agent

### 14.2 Sampling Modes

| Mode | Rate | Use Case |
|------|------|----------|
| `ALWAYS` | 100% | Development, staging, testing |
| `RATE` | N traces/sec | Production with steady load |
| `PROBABILITY` | e.g., 10% | Production with variable load |
| `ADAPTIVE` | Auto-adjusts based on throughput | Production — ideal for SaaS |

### 14.3 Adaptive Sampling

```
if (current_throughput < 100 req/sec)  → sample 100%
if (current_throughput < 1000 req/sec) → sample 10%
if (current_throughput < 10000 req/sec)→ sample 1%
if (current_throughput > 10000 req/sec)→ sample 0.1%

Always guarantee: at least 1 trace per endpoint per minute
```

This ensures every endpoint gets at least some traces, even under high load.

---

## 15. Resilience & Failure Modes

### 15.1 Failure Scenarios & Behavior

| Scenario | Impact on Customer App | Agent Behavior |
|----------|----------------------|----------------|
| **FCS is down** | None | Circuit breaker opens. Events dropped. Log once per minute. |
| **FCS is slow (>5s)** | None | HTTP timeout fires. Treated as failure. Circuit breaker counts it. |
| **Ring buffer full** | None | Oldest events overwritten (or dropped). No backpressure to app thread. |
| **Agent throws exception** | None | All advice code wrapped in try-catch. Exception logged once, swallowed. |
| **OOM in agent** | None | Agent uses bounded structures only. Can't OOM. If JVM OOMs, it's the app, not us. |
| **Class loading conflict** | App fails to start | Agent skips conflicting class, logs warning. ByteBuddy has safe fallback. |
| **Thread pool exhaustion** | None | Agent uses 1-2 daemon threads only. Does not use customer thread pools. |

### 15.2 The Golden Rule

> **The agent must NEVER cause the customer application to fail, slow down, or behave differently.**
> If in doubt, DROP data rather than risk affecting the app.

---

## 16. Performance Budget

### 16.1 Per-Method Overhead

| Operation | Budget | How |
|-----------|--------|-----|
| Enter advice (timing + context) | < 100ns | `System.nanoTime()` + ThreadLocal read |
| Exit advice (timing + emit) | < 200ns | nanoTime + ring buffer write |
| nodeId construction | 0ns at call time | Pre-computed at class load time |
| Sampling check | < 20ns | Single boolean read from ThreadLocal |
| **Total per method call** | **< 300ns** | Less than a HashMap.get() |

### 16.2 Throughput Budget

| Metric | Target |
|--------|--------|
| Max events/sec emitted | 50,000 (before sampling) |
| Max batches/sec to FCS | 10 (100 events per batch, ~200ms interval) |
| Max HTTP payload size | 100KB compressed |
| Agent threads | 2 (pipeline + transport) |
| Agent heap usage | < 30MB |
| Class load overhead | < 500ms total at startup |

### 16.3 Measurement

The agent MUST self-monitor:
- Events emitted/sec
- Events dropped/sec (buffer overflow + circuit break)
- Batch send latency (p50, p99)
- Circuit breaker state changes
- Memory usage

Exposed via JMX MBeans and periodic log line (every 60s):
```
[flow-agent] events=12340/s dropped=0 batches=124 avgLatency=15ms circuit=CLOSED heap=22MB
```

---

## 17. Security Considerations

### 17.1 Data Privacy

| Concern | Policy |
|---------|--------|
| Method arguments | **NOT captured** by default. Opt-in per method via config. |
| Return values | **NOT captured** by default. |
| Exception messages | Captured (type + message). Stack trace opt-in. |
| HTTP headers | Only `flow-trace-id`, `flow-span-id` injected. No customer headers read. |
| Kafka message body | **NOT captured**. Only headers used for trace propagation. |
| PII | No PII captured in default mode. If checkpoint SDK used, developer is responsible. |

### 17.2 Transport Security

- **TLS required** in production mode (configurable)
- **API key** header sent with every batch: `Authorization: Bearer <flow-api-key>`
- API key configured via system property or environment variable

### 17.3 Supply Chain

- Minimal dependencies: ByteBuddy (shaded), Jackson (shaded), nothing else
- All dependencies shaded into agent JAR to avoid classpath conflicts
- Agent JAR signed (future)

---

## 18. Packaging & Distribution

### 18.1 Single Fat JAR

```
flow-agent-{version}.jar
  ├── META-INF/MANIFEST.MF
  │     Premain-Class: com.flow.agent.FlowAgent
  │     Agent-Class: com.flow.agent.FlowAgent (for dynamic attach)
  │     Can-Retransform-Classes: true
  ├── com/flow/agent/              (agent code)
  ├── com/flow/agent/shaded/       (shaded dependencies)
  │     ├── net.bytebuddy.*
  │     └── com.fasterxml.jackson.*
  └── META-INF/flow-agent.properties (default config)
```

### 18.2 Usage

```bash
# Static attach (recommended for production)
java -javaagent:/path/to/flow-agent-0.1.0.jar \
     -Dflow.server.url=https://fcs.mycompany.com \
     -Dflow.api.key=sk-xxxx \
     -Dflow.packages=com.greens.order \
     -jar my-application.jar

# Dynamic attach (for debugging / dev — attach to running JVM)
java -jar flow-agent-0.1.0.jar attach --pid 12345
```

### 18.3 Dependency Isolation (Shading)

The agent's internal dependencies MUST NOT conflict with customer app dependencies. If the customer uses ByteBuddy 1.14 and we use 1.15, there must be no conflict.

**Solution:** Maven Shade plugin relocates all agent dependencies:
```
net.bytebuddy → com.flow.agent.shaded.bytebuddy
com.fasterxml.jackson → com.flow.agent.shaded.jackson
```

---

## 19. Configuration Model

### 19.1 Configuration Sources (Priority Order)

```
1. System properties:    -Dflow.packages=com.greens.order
2. Environment variables: FLOW_PACKAGES=com.greens.order
3. Config file:          flow-agent.yml (next to agent JAR or specified via -Dflow.config=)
4. FCS remote config:    GET /agent/config?graphId=... (future — dynamic config)
5. Defaults:             Built into the agent
```

### 19.2 Configuration Properties

```yaml
flow:
  # Connection
  server:
    url: "https://fcs.mycompany.com"         # Required
    api-key: "${FLOW_API_KEY}"               # Required in production
    connect-timeout-ms: 5000
    read-timeout-ms: 5000

  # Identity
  graph-id: "payment-service"                 # Required — maps to static graph
  service-name: "payment-service"            # Optional — display name

  # Instrumentation
  packages:
    include:
      - "com.greens.order"
      - "com.greens.payment"
    exclude:
      - "com.greens.order.config.internal"
  
  filter:
    use-manifest: true                        # Download flow.json as whitelist
    skip-getters-setters: true
    skip-constructors: false
    skip-private-methods: false               # Set true to reduce volume
  
  # Sampling
  sampling:
    mode: "adaptive"                          # ALWAYS | RATE | PROBABILITY | ADAPTIVE
    rate: 100                                 # traces/sec (for RATE mode)
    probability: 0.1                          # 10% (for PROBABILITY mode)
    min-traces-per-endpoint-per-minute: 1     # guarantee for ADAPTIVE mode

  # Pipeline
  pipeline:
    buffer-size: 8192                         # ring buffer capacity
    batch-size: 100                           # events per HTTP batch
    flush-interval-ms: 200                    # max time before batch is shipped
    compress: true                            # GZIP compression

  # Resilience
  resilience:
    circuit-breaker:
      failure-threshold: 3                    # consecutive failures to open
      reset-timeout-ms: 30000                # wait before half-open probe
    retry:
      max-attempts: 1                        # total attempts = 2
      delay-ms: 500

  # Security
  security:
    tls-required: true                       # enforce HTTPS
    mask-arguments: true                     # mask method arg values

  # Observability
  observability:
    jmx-enabled: true                        # expose MBeans
    log-interval-sec: 60                     # periodic stats log line
    log-level: "INFO"                        # agent-specific log level
```

---

## 20. Module Structure

### 20.1 Where Does This Live?

**New module** inside `flow-java-adapter`:

```
flow-java-adapter/                        (existing parent)
  ├── flow-adapter/                       (existing — core scanning framework)
  ├── flow-spring-plugin/                 (existing — Spring annotation scanner)
  ├── flow-kafka-plugin/                  (existing — Kafka annotation scanner)
  ├── flow-runner/                        (existing — CLI runner JAR)
  ├── flow-runtime-agent/                 (NEW — the runtime plugin)
  │     ├── pom.xml
  │     └── src/main/java/com/flow/agent/
  │           ├── FlowAgent.java               (premain entry point)
  │           ├── config/
  │           │     ├── AgentConfig.java
  │           │     └── ConfigLoader.java
  │           ├── instrumentation/
  │           │     ├── FlowTransformer.java       (ByteBuddy AgentBuilder)
  │           │     ├── MethodAdvice.java           (enter/exit/error advice)
  │           │     ├── NodeIdBuilder.java          (runtime → static nodeId)
  │           │     ├── ProxyResolver.java          (CGLIB → real class)
  │           │     └── interceptors/
  │           │           ├── KafkaProducerInterceptor.java
  │           │           ├── KafkaConsumerInterceptor.java
  │           │           ├── RestTemplateInterceptor.java
  │           │           ├── WebClientInterceptor.java
  │           │           └── AsyncInterceptor.java
  │           ├── context/
  │           │     ├── FlowContext.java            (ThreadLocal trace context)
  │           │     ├── SpanInfo.java
  │           │     └── TraceIdGenerator.java
  │           ├── pipeline/
  │           │     ├── EventRingBuffer.java        (lock-free buffer)
  │           │     ├── DurationPairer.java          (pair ENTER/EXIT)
  │           │     ├── BatchAssembler.java          (time/count trigger)
  │           │     └── FlowEventSink.java           (static emit() entry)
  │           ├── sampling/
  │           │     ├── Sampler.java                 (interface)
  │           │     ├── AlwaysSampler.java
  │           │     ├── RateSampler.java
  │           │     ├── ProbabilitySampler.java
  │           │     └── AdaptiveSampler.java
  │           ├── transport/
  │           │     ├── HttpBatchSender.java         (async HTTP client)
  │           │     ├── CircuitBreaker.java
  │           │     └── PayloadSerializer.java       (JSON + GZIP)
  │           ├── filter/
  │           │     ├── FilterChain.java
  │           │     ├── PackageFilter.java
  │           │     ├── ManifestFilter.java          (flow.json whitelist)
  │           │     ├── MethodExcludeFilter.java
  │           │     └── BridgeMethodFilter.java
  │           ├── manifest/
  │           │     ├── StaticManifest.java           (parsed flow.json lookup)
  │           │     └── ManifestSyncService.java      (download from FCS)
  │           └── monitor/
  │                 ├── AgentMetrics.java             (counters)
  │                 └── AgentHealthMBean.java         (JMX)
  └── sample/greens-order/                (existing — test target app)
```

### 20.2 Why Inside flow-java-adapter?

- Shares the same **nodeId format** / `SignatureNormalizer` logic
- Shares the same **GEF model** understanding
- Same team, same release cycle, same versioning
- Can reuse `flow.json` model classes from `flow-adapter`

### 20.3 Maven POM Highlights

```xml
<artifactId>flow-runtime-agent</artifactId>
<packaging>jar</packaging>

<dependencies>
  <!-- Bytecode instrumentation -->
  <dependency>
    <groupId>net.bytebuddy</groupId>
    <artifactId>byte-buddy</artifactId>
  </dependency>
  <dependency>
    <groupId>net.bytebuddy</groupId>
    <artifactId>byte-buddy-agent</artifactId>
    <scope>provided</scope>
  </dependency>

  <!-- JSON (shaded) -->
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
  </dependency>

  <!-- Reuse nodeId building logic from flow-adapter -->
  <dependency>
    <groupId>com.flow</groupId>
    <artifactId>flow-adapter</artifactId>
  </dependency>
</dependencies>

<build>
  <plugins>
    <!-- Shade all deps + set MANIFEST entries for javaagent -->
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId>
      <configuration>
        <transformers>
          <transformer implementation="...ManifestResourceTransformer">
            <manifestEntries>
              <Premain-Class>com.flow.agent.FlowAgent</Premain-Class>
              <Agent-Class>com.flow.agent.FlowAgent</Agent-Class>
              <Can-Retransform-Classes>true</Can-Retransform-Classes>
            </manifestEntries>
          </transformer>
        </transformers>
        <relocations>
          <relocation>
            <pattern>net.bytebuddy</pattern>
            <shadedPattern>com.flow.agent.shaded.bytebuddy</shadedPattern>
          </relocation>
          <relocation>
            <pattern>com.fasterxml.jackson</pattern>
            <shadedPattern>com.flow.agent.shaded.jackson</shadedPattern>
          </relocation>
        </relocations>
      </configuration>
    </plugin>
  </plugins>
</build>
```

---

## 21. Phased Delivery Plan

### Phase 1 — Foundation (MVP)
**Goal:** Trace method calls in a Spring Boot app, ship to FCS, see merged graph.

- [ ] Agent bootstrap (`FlowAgent.premain()`)
- [ ] Config loading (system properties + env vars)
- [ ] ByteBuddy transformer with package filter
- [ ] Method enter/exit advice
- [ ] NodeId builder (with proxy resolution)
- [ ] FlowContext (ThreadLocal-based)
- [ ] Ring buffer + batch assembler
- [ ] HTTP batch sender (async, non-blocking)
- [ ] Circuit breaker (basic)
- [ ] Test with `sample/greens-order` app → verify merge in FCS
- [ ] Basic JMX metrics

**Validation:** Run greens-order with agent → POST /ingest/runtime events appear → GET /trace shows real execution flow merged with static graph.

### Phase 2 — Kafka & Async
**Goal:** Full async hop stitching + cross-thread propagation.

- [ ] KafkaProducerInterceptor (header injection)
- [ ] KafkaConsumerInterceptor (header extraction)
- [ ] @Async / CompletableFuture context propagation
- [ ] PRODUCE_TOPIC / CONSUME_TOPIC event emission
- [ ] Test with greens-order Kafka flow → verify ASYNC_HOP edges

### Phase 3 — Distributed Tracing
**Goal:** Cross-service trace propagation via HTTP.

- [ ] RestTemplate interceptor
- [ ] WebClient interceptor
- [ ] Feign interceptor
- [ ] HTTP header injection/extraction (flow-trace-id, flow-span-id)
- [ ] Multi-service test setup

### Phase 4 — Production Hardening
**Goal:** Ready for customer production JVMs.

- [ ] Manifest-based filtering (download flow.json as whitelist)
- [ ] Adaptive sampling
- [ ] GZIP compression
- [ ] TLS enforcement
- [ ] API key authentication
- [ ] Comprehensive error handling audit
- [ ] Performance benchmarks (< 3% overhead proven)
- [ ] Memory leak testing
- [ ] Dynamic attach support
- [ ] Agent version reporting

### Phase 5 — Advanced Features
**Goal:** Competitive with Datadog/New Relic for Flow-specific use cases.

- [ ] Checkpoint SDK (`Flow.checkpoint("key", value)`)
- [ ] Error enrichment (exception chains, root cause)
- [ ] Remote config from FCS (dynamic sampling, dynamic filters)
- [ ] Hot-reload config without restart
- [ ] Virtual thread support (Java 21 ScopedValue)
- [ ] Reactive (WebFlux) support
- [ ] gRPC interceptor
- [ ] Agent self-update mechanism

---

## 22. Open Questions

| # | Question | Options | Impact |
|---|----------|---------|--------|
| 1 | **Should the agent live in `flow-java-adapter` repo or a separate repo?** | Same repo (shared nodeId logic) vs separate (independent release cycle) | Build & release process |
| 2 | **Should we collapse ENTER+EXIT into single event in agent, or let FCS pair them?** | Agent-side pairing (halves network) vs FCS-side (simpler agent, richer data) | Event volume & agent complexity |
| 3 | **How to handle generics erasure for nodeId matching?** | (a) Manifest lookup, (b) Adapter emits erased form too, (c) Fuzzy match | nodeId correctness |
| 4 | **Should Checkpoint SDK be a separate thin JAR that customers depend on?** | (a) Separate `flow-sdk.jar` (1 class, no deps), (b) Static method via reflection, (c) No SDK, just log markers | Customer DX |
| 5 | **What Java version minimum?** | Java 17 (match adapter) vs Java 11 (wider compatibility) | Customer reach |
| 6 | **Should agent sync flow.json from FCS at startup?** | Yes (accurate filtering) vs No (works offline, simpler) | Accuracy vs simplicity |
| 7 | **Do we need OpenTelemetry interop?** | Bridge OTel spans vs ignore vs export as OTel | Ecosystem compatibility |
| 8 | **gRPC transport option?** | HTTP-only (simpler) vs gRPC option (lower overhead at scale) | Scale & complexity |
| 9 | **Multi-tenancy at agent level?** | Agent sends tenant ID vs FCS infers from API key | SaaS architecture |
| 10 | **Agent metrics — push to FCS or expose locally only?** | Push to FCS (centralized) vs JMX only (simpler) | Observability |

---

> **This document is a living brainstorm. Edit sections, add comments, challenge assumptions.**  
> **Next step:** Decide on Open Questions 1–6, then begin Phase 1 implementation.

