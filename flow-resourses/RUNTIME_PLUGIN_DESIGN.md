# Flow Runtime Plugin — Architecture & Design Document

**Version:** 0.5 (Draft — Brainstorming)  
**Date:** March 7, 2026  
**Status:** Planning  
**Authors:** Flow Team

---

## Table of Contents

0. [Product Scope & North Star — What Flow Is and Is Not](#0-product-scope--north-star--what-flow-is-and-is-not)
1. [The Problem Statement](#1-the-problem-statement)
2. [What We Have Today](#2-what-we-have-today)
3. [What Is Missing](#3-what-is-missing)
4. [Design Goals](#4-design-goals)
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
22-A. [Decision Rationale — Closed Questions](#22-a-decision-rationale--closed-questions)
23. [Deployment Mode Tiers — Individual vs Enterprise](#23-deployment-mode-tiers--individual-vs-enterprise)
24. [External System Integrations — Beyond Kafka](#24-external-system-integrations--beyond-kafka)
25. [Data Sensitivity & Deployment Topology](#25-data-sensitivity--deployment-topology)
26. [Checkpoint SDK — Visual Debugging on the Graph](#26-checkpoint-sdk--visual-debugging-on-the-graph)
27. [OTel and Flow — Deep Analysis, Problems, and Hybrid Architecture](#27-otel-and-flow--deep-analysis-problems-and-hybrid-architecture)

---

## 0. Product Scope & North Star — What Flow Is and Is Not

> **This section is a hard boundary. Every feature, every integration, every design decision must be evaluated against it before it enters the roadmap.**

---

### 0.1 The Two Objectives — Nothing More

Flow has exactly **two objectives**:

```
OBJECTIVE 1 — REAL-TIME SYSTEM VISIBILITY
  Show how a running system actually behaves — live, on the architecture graph.
  Which paths are executing. Which nodes are being hit. In real time.
  Not dashboards. Not charts. The CODE GRAPH, animated with live execution.

OBJECTIVE 2 — BUSINESS MEANING OF CODE  (to be designed separately)
  Show what the code means in business terms.
  Map technical execution paths to business processes / user journeys.
  Bridge the gap between engineering and product/business teams.
```

That is the entire product.

---

### 0.2 What Flow Is NOT

These are explicit **out-of-scope** items. If a discussion or feature starts pulling Flow into these territories, it must be rejected.

| Out of Scope | Why We Are NOT Building This |
|---|---|
| **Performance monitoring / APM** | Datadog, New Relic, Elastic APM already do this. We are not competing. `durationMs` is captured only because it's a **side effect** of knowing when a node was entered/exited — not as a product feature. |
| **Alerting & anomaly detection** | Not our problem. If a customer wants alerts on latency, they use Datadog. |
| **Metrics dashboards** | No charts, no timeseries graphs, no p50/p95/p99 displays as a product goal. |
| **Log aggregation** | Not our concern. |
| **Error tracking / crash reporting** | Sentry, Rollbar do this. We capture errors only to **annotate the graph node** (show which node threw), not to build an error tracking product. |
| **Distributed tracing as a standalone feature** | We propagate trace context only so we can **stitch the graph across service boundaries**. We are not building a Jaeger / Zipkin replacement. |
| **Infrastructure monitoring** | CPU, memory, disk — not our concern. |
| **Security / threat detection** | Not our concern. |
| **Cost optimization** | Not our concern. |

---

### 0.3 The Litmus Test for Every Feature

Before adding anything to the roadmap, ask:

```
Does this feature make the ARCHITECTURE GRAPH more accurate,
more complete, or more alive at runtime?

  YES → Consider it.
  NO  → Drop it, regardless of how useful it seems in isolation.
```

**Examples:**

| Feature | Litmus Test Result |
|---|---|
| Capture `durationMs` per node | ✅ YES — makes the graph show how long each node ran |
| Show which graph nodes were hit in this request | ✅ YES — core objective 1 |
| Stitch Kafka producer → consumer as graph edge | ✅ YES — makes the graph complete across async boundaries |
| Show Redis GET/SET as a node in the graph | ✅ YES — Redis IS part of the architecture |
| p95 latency trend for `OrderService#placeOrder` over 7 days | ❌ NO — this is APM, not graph visualization |
| Alert when error rate on a node exceeds 5% | ❌ NO — this is monitoring, not graph visualization |
| Show method argument values in traces | ❌ NO — this is debugging / observability, not graph visualization |
| Map `OrderService#placeOrder` to "Place Order" business process | ✅ YES — this is Objective 2 |

---

### 0.4 Why This Scope Discipline Matters

A broader scope means:
- More surface area to build and maintain
- More chances to build the wrong thing
- Longer time to a working, shippable product
- Confusion about what the product actually is
- Direct competition with well-funded incumbents (Datadog, New Relic) where we have no advantage

A tight scope means:
- Ship faster
- Be **the best in the world at one specific thing** — showing your code architecture live
- Clear positioning: *"Flow shows you your system as it actually runs — not dashboards, not logs, your actual code graph, live."*
- No existing tool does exactly this. That is the moat.

---

### 0.5 What the Runtime Agent's Job Actually Is

Given the scope above, the runtime agent has **one job**:

```
Observe method executions and external system calls in the customer's JVM.
Emit the minimum data needed to animate the architecture graph.
Ship it to FCS reliably without affecting the customer app.
Nothing more.
```

The agent is **not** an APM agent. It does not build flame graphs. It does not track percentiles. It does not sample for debugging. It emits **graph animation events** — enter, exit, which node, which trace, in what order. That is all.

---

## 1. The Problem Statement

Today the Flow platform can:
- ✅ **Scan** Java source code → produce `flow.json` (static graph) via `flow-java-adapter`
- ✅ **Receive** runtime events via `POST /ingest/runtime` on `flow-core-service`
- ✅ **Merge** static + runtime graphs via `MergeEngine` in `flow-engine`
- ✅ **Serve** enriched graphs via REST API with zoom-level filtering

But **nobody is sending runtime events**. The receiver exists. The emitter does not.

The static graph shows what **could** execute. Without runtime events, Flow cannot show what **is** executing right now. The graph is frozen — a blueprint, not a live system.

The runtime plugin exists to **bring the graph to life**:
- Which nodes are being hit in this request, right now
- Which paths are actually taken vs which are dead code
- How Kafka, SQS, RabbitMQ, DB, Redis calls appear as real nodes and edges in the graph
- How execution flows across service boundaries as one connected graph

The runtime plugin is the **last critical piece** that makes the architecture graph live.

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

## 4. Design Goals

This is NOT a POC. It will run inside **customer production JVMs**. The bar is high. But the goal is specific — we are building a **graph animation agent**, not an APM agent.

### Non-Negotiable Requirements

| # | Requirement | Why |
|---|-------------|-----|
| 1 | **Zero code changes** in customer app | Adoption friction kills SaaS products. `-javaagent` attachment only. |
| 2 | **< 3% latency overhead** | Customers won't tolerate visible performance degradation in production. |
| 3 | **< 50MB memory overhead** | Must not steal heap from the application. |
| 4 | **Graceful degradation** | If FCS is down, the customer app MUST NOT be affected. Events are dropped silently. |
| 5 | **Thread-safe, async-safe** | Must work with `@Async`, `CompletableFuture`, virtual threads (Java 21), reactive. |
| 6 | **Exact nodeId matching** | Runtime nodeIds must precisely match static graph nodeIds from `flow.json`. Without this, the graph cannot be animated. |
| 7 | **Framework-aware** | Must understand Spring proxies (CGLIB), Kafka, SQS, JDBC, Redis client libraries. |
| 8 | **Configurable filtering** | Customers control what gets traced — by package, annotation, or class. Limits noise. |
| 9 | **Sampling** | 100% in dev/staging. Rate-limited in production. We only need enough events to animate the graph — not every single call. |
| 10 | **Secure — no data capture** | No method arguments, no query values, no message bodies, no PII. We capture structure (which node ran), never content. |
| 11 | **Backward compatible** | Must work with Java 17 and Java 21+. |

### What We Are NOT Optimising For

| ❌ Not a Goal | Reason |
|---|---|
| Sub-millisecond trace latency reporting | We don't build latency dashboards |
| 100% trace completeness | Dropping events under load is acceptable — the graph still animates |
| Full flame graph / call tree capture | That's a debugger / profiler job |
| Storing every event forever | We need enough to show the graph live — not a permanent audit trail |

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

### 14.1 Why We Sample

We do not need every single method call to animate the graph. We need **enough representative traces** to show which paths are alive and which are not. Sampling is not a compromise — it is correct behaviour for our use case.

In production: 1–10% sampling is enough to keep the graph animated. Every path that is regularly used will appear. Dead code paths will remain dim. That is the goal.

### 14.2 Head-Based Sampling

Sampling decision is made at the **trace entry point** (the first event in a trace). All subsequent events in the same trace follow the same decision — the whole trace is either captured or skipped. No partial traces.

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

These numbers are not APM targets. They are **safety ceilings** — the maximum the agent will consume from the customer's resources.

| Metric | Ceiling | Why |
|--------|---------|-----|
| Max events/sec emitted (pre-sampling) | 50,000 | Ring buffer size acts as natural cap |
| Max batches/sec to FCS | 10 (100 events per batch, ~200ms interval) | Non-blocking — never more than needed to keep the graph live |
| Max HTTP payload size | 100KB compressed | Enough for a full trace batch |
| Agent threads | 2 daemon threads | Pipeline thread + transport thread. Never touches customer thread pools. |
| Agent heap usage | < 30MB | Bounded structures only |
| Class load overhead | < 500ms total at startup | One-time cost |

### 16.3 Self-Monitoring

The agent logs a single status line every 60 seconds. This is for the customer to verify the agent is working — not a metrics product.

```
[flow-agent] active=true events=1240/s dropped=0 circuit=CLOSED heap=18MB integrations=[spring,kafka,jdbc]
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
| SQS / RabbitMQ / Pub/Sub message body | **NOT captured**. Only message attributes used for trace propagation. |
| SQL query text | **NOT captured**. Only table name / entity name extracted. Bind parameters never captured. |
| Redis key values | **NOT captured as-is**. Key is normalized to a pattern (`order:*`). Actual value never captured. |
| Elasticsearch query body | **NOT captured**. Only index name and operation type. |
| MongoDB document content | **NOT captured**. Only collection name and operation type. |
| S3 object key | **NOT captured** by default (may contain sensitive path). Only bucket name + operation. |
| gRPC message payload | **NOT captured**. Only service name, method name, and duration. |
| PII | No PII captured in default mode. If Checkpoint SDK used, developer is responsible. |

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
  ├── flow-jdbc-plugin/                   (NEW — JDBC / JPA / Hibernate scanner)
  ├── flow-redis-plugin/                  (NEW — Redis / Spring Cache scanner)
  ├── flow-elasticsearch-plugin/          (NEW — Elasticsearch scanner)
  ├── flow-mongo-plugin/                  (NEW — MongoDB scanner)
  ├── flow-aws-plugin/                    (NEW — S3 / SQS / SNS scanner)
  ├── flow-grpc-plugin/                   (NEW — gRPC proto stub scanner)
  ├── flow-rabbit-plugin/                 (NEW — RabbitMQ scanner)
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
  │           │           ├── RestTemplateInterceptor.java
  │           │           ├── WebClientInterceptor.java
  │           │           └── AsyncInterceptor.java
  │           ├── integrations/
  │           │     ├── IntegrationPlugin.java      (interface — the contract)
  │           │     ├── IntegrationRegistry.java    (auto-detects active plugins)
  │           │     ├── messaging/
  │           │     │     ├── KafkaIntegration.java
  │           │     │     ├── SqsIntegration.java
  │           │     │     ├── SnsIntegration.java
  │           │     │     ├── RabbitMqIntegration.java
  │           │     │     └── PubSubIntegration.java
  │           │     ├── db/
  │           │     │     ├── JdbcIntegration.java
  │           │     │     ├── HibernateIntegration.java
  │           │     │     └── SpringDataJpaIntegration.java
  │           │     ├── cache/
  │           │     │     ├── LettuceRedisIntegration.java
  │           │     │     ├── JedisIntegration.java
  │           │     │     └── HazelcastIntegration.java
  │           │     ├── search/
  │           │     │     ├── ElasticsearchIntegration.java
  │           │     │     └── OpenSearchIntegration.java
  │           │     ├── document/
  │           │     │     └── MongoIntegration.java
  │           │     ├── objectstore/
  │           │     │     ├── AwsS3Integration.java
  │           │     │     └── GcsIntegration.java
  │           │     └── rpc/
  │           │           └── GrpcIntegration.java
  │           ├── context/
  │           │     ├── FlowContext.java            (ThreadLocal trace context)
  │           │     ├── SpanInfo.java
  │           │     └── TraceIdGenerator.java
  │           ├── pipeline/
  │           │     ├── EventRingBuffer.java        (lock-free buffer)
  │           │     ├── DurationPairer.java         (pair ENTER/EXIT)
  │           │     ├── BatchAssembler.java         (time/count trigger)
  │           │     └── FlowEventSink.java          (static emit() entry)
  │           ├── sampling/
  │           │     ├── Sampler.java                (interface)
  │           │     ├── AlwaysSampler.java
  │           │     ├── RateSampler.java
  │           │     ├── ProbabilitySampler.java
  │           │     └── AdaptiveSampler.java
  │           ├── transport/
  │           │     ├── HttpBatchSender.java        (async HTTP client)
  │           │     ├── CircuitBreaker.java
  │           │     └── PayloadSerializer.java      (JSON + GZIP)
  │           ├── filter/
  │           │     ├── FilterChain.java
  │           │     ├── PackageFilter.java
  │           │     ├── ManifestFilter.java         (flow.json whitelist)
  │           │     ├── MethodExcludeFilter.java
  │           │     └── BridgeMethodFilter.java
  │           ├── manifest/
  │           │     ├── StaticManifest.java          (parsed flow.json lookup)
  │           │     └── ManifestSyncService.java     (download from FCS)
  │           └── monitor/
  │                 ├── AgentMetrics.java            (counters)
  │                 └── AgentHealthMBean.java        (JMX)
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

> **Architecture: Hybrid (v0.5).** Flow owns method-level instrumentation. OTel provides external system spans.
> See Section 27 for the full analysis and rationale.

---

### Phase 1 — Flow Method Engine (MVP)
**Goal:** Trace every method in `flow.json` inside a Spring Boot app, ship to FCS, see merged graph with method nodes lit up.

**Flow Agent:**
- [ ] `FlowAgent.premain()` — agent bootstrap
- [ ] Config loading (system properties + env vars + `flow.server.url` + `flow.graphId`)
- [ ] ByteBuddy transformer with package filter (customer packages only)
- [ ] Method enter/exit/error advice — emits `METHOD_ENTER` / `METHOD_EXIT` / `ERROR`
- [ ] Full nodeId builder (FQCN + params + return type via `getGenericReturnType()`)
- [ ] CGLIB proxy resolution (resolve `$$SpringCGLIB$$` → real class)
- [ ] Bridge method / lambda filtering
- [ ] `FlowContext` (ThreadLocal-based: `traceId`, `spanStack`, `currentNodeId()`)
- [ ] Ring buffer + batch assembler (count threshold OR time threshold flush)
- [ ] HTTP batch sender to FCS (`POST /ingest/runtime`) — async, non-blocking
- [ ] Circuit breaker (CLOSED → OPEN after 3 failures → HALF-OPEN after 30s)

**Static Scanner:**
- [ ] No new scanner needed — `flow-spring-plugin` already produces method nodes in `flow.json`

**Validation:**
```
Run sample/greens-order with -javaagent:flow-agent.jar
→ METHOD_ENTER / METHOD_EXIT events arrive at FCS
→ MergeEngine merges runtime events with static graph
→ UI shows method nodes lighting up on the architecture graph
```

---

### Phase 2 — OTel Bridge for External Systems
**Goal:** DB, Redis, Kafka, HTTP, gRPC calls appear as graph nodes alongside method nodes.

**Flow Agent — OTel Integration:**
- [ ] Embed OTel SDK inside Flow agent (for customers who don't have OTel)
- [ ] OTel presence detection: if customer already has OTel agent running, don't bootstrap OTel — just register `FlowSpanProcessor`
- [ ] `FlowSpanProcessor` — registers as OTel `SpanProcessor`, reads completed boundary spans
- [ ] OTel semantic attribute → Flow nodeId mapper:
  - `db.system` + `db.sql.table` → `db:{dataSource}:query:{table}`
  - `db.system=redis` + `db.operation` → `cache:redis:{host}:{op}:{keyPattern}`
  - `messaging.system` + `messaging.destination` → `topic:{name}` / `queue:{name}`
  - `rpc.system=grpc` + `rpc.service` + `rpc.method` → `grpc:{service}/{method}`
  - `http.method` + `http.target` → `endpoint:{method} {path}`
  - `db.system=elasticsearch` → `search:es:{cluster}:{index}:{op}`
  - `db.system=mongodb` → `mongo:{db}:{collection}:{op}`
- [ ] Converts OTel spans → Flow `RuntimeEvent` → feeds into same ring buffer as method events
- [ ] Unified pipeline: method events + bridged boundary spans → FCS in single batch

**Trace Context Bridge:**
- [ ] `FlowContext.traceId` reads from OTel `Span.current().getSpanContext().getTraceId()`
- [ ] If no incoming `traceparent` header: Flow generates traceId, sets it on OTel Context
- [ ] One traceId shared by both layers — method events and boundary events linked in same trace

**Static Scanner additions:**
- [ ] `flow-jdbc-plugin`, `flow-redis-plugin` — produce `db:*`, `cache:redis:*` nodes in `flow.json`
- [ ] Extend `flow-kafka-plugin` — already exists, verify `topic:*` nodeId format matches mapper

**Validation:**
```
greens-order with flow-agent.jar
→ method nodes + DB nodes + Redis nodes + Kafka topic nodes all appear
→ edges from method → DB, method → Redis, method → Kafka visible
→ full architecture graph live
```

---

### Phase 3 — Checkpoint SDK
**Goal:** Developer-placed checkpoints appear on exact method nodes in the graph.

**flow-sdk.jar:**
- [ ] `Flow.checkpoint("key", value)` — single class, zero dependencies
- [ ] Agent intercepts via ByteBuddy advice on `com.flow.sdk.Flow#checkpoint(String, Object)`
- [ ] Uses `FlowContext.currentNodeId()` (NOT `Span.current()` — see Section 27.12)
- [ ] Emits `CHECKPOINT` event with `{ nodeId, traceId, spanId, data: { key: value } }`

**FCS:**
- [ ] `MergeEngine` already handles `CHECKPOINT` events → `node.data.checkpoints` — no changes needed ✅

**Validation:**
```
Developer adds: Flow.checkpoint("cart_total", cart.getTotal());
→ value "cart_total: 250.00" appears on OrderService#placeOrder node in UI
→ NOT on the HTTP endpoint node — on the EXACT method node
```

---

### Phase 4 — Cross-Service + Async Method Edges
**Goal:** Traces span across services. @Async and CompletableFuture show method-to-method edges.

**Cross-Service:**
- [ ] W3C `traceparent` header read/write — delegate to OTel layer for HTTP/Kafka propagation
- [ ] Flow agent reads OTel's traceId from incoming requests to continue traces
- [ ] `ASYNC_HOP` edge stitching in FCS for Kafka/SQS/RabbitMQ (already implemented in MergeEngine ✅)

**Async Method-to-Method Edges:**
- [ ] `@Async` executor wrapping: intercept `AsyncTaskExecutor.submit()` → wrap `Runnable` with `FlowContext` snapshot
- [ ] `CompletableFuture.supplyAsync()` wrapping → wrap supplier with context
- [ ] `ExecutorService.submit()` wrapping → wrap all submitted tasks
- [ ] Each wrapped task restores `FlowContext` on the new thread → `METHOD_ENTER` on the async method carries correct `parentSpanId` → FCS sees the method-to-method edge

**Validation:**
```
Multi-service test: Service A → HTTP → Service B → Kafka → Service C
→ single traceId across all three services
→ unified graph shows full execution path
→ @Async calls show correct method-to-method edges (not thread-to-thread)
```

---

### Phase 5 — Production Hardening
**Goal:** Ready for customer production JVMs at scale.

- [ ] Manifest sync — async background pull of `flow.json` from FCS (enrichment, not dependency)
- [ ] Adaptive sampling — head-based decision at trace entry; within sampled traces: 100% method coverage
- [ ] GZIP compression on event batches
- [ ] TLS enforcement + API key authentication (`Authorization: Bearer`)
- [ ] `buildMeta` handshake — agent sends `graphId`, `version`, `env`, `commitSha` at startup
- [ ] Re-scan nudge — FCS detects stale `flow.json` from unresolved event rate
- [ ] Performance benchmarks: < 3% latency overhead, < 50MB memory overhead (proven)
- [ ] Memory leak testing under sustained load
- [ ] Comprehensive error handling audit across all integrations
- [ ] OTel Collector support — FCS accepts OTLP directly for external spans from existing OTel Collectors

---

### Phase 6 — Advanced Features
**Goal:** Feature-complete, every customer scenario covered.

- [ ] Virtual thread support (Java 21 `ScopedValue`) — detect at runtime, enable conditionally
- [ ] Reactive (WebFlux / Project Reactor) — FlowContext propagation via Reactor `Context`
- [ ] Dynamic attach (attach Flow agent to running JVM without restart)
- [ ] Remote config from FCS (dynamic sampling rate, dynamic package filters — no restart)
- [ ] Error enrichment (exception chains, root cause, stack trace opt-in)
- [ ] Agent self-update mechanism
- [ ] gRPC transport option (OTLP/gRPC for high-volume enterprise — OTel SDK supports natively)

---

## 22. Open Questions

| # | Question | Options | Impact | Status |
|---|----------|---------|--------|--------|
| 1 | **Agent repo — same or separate?** | Same repo (shared nodeId logic) vs separate (independent release cycle) | Build & release | ✅ Decided: separate repo |
| 2 | **Collapse ENTER+EXIT in agent or let FCS pair?** | Agent-side pairing (halves network) vs FCS-side (simpler agent, richer data) | Event volume & agent complexity | ✅ Decided: Option B — FCS pairs. See rationale below. |
| 3 | **Generics erasure nodeId matching?** | (a) Manifest lookup, (b) Adapter emits erased form too, (c) Fuzzy match | nodeId correctness | ✅ Decided: Option D (primary) + B (enrichment). See rationale below. |
| 4 | **Checkpoint SDK — separate JAR?** | (a) Separate `flow-sdk.jar`, (b) Reflection, (c) Log markers | Customer DX | ✅ Decided: separate `flow-sdk.jar` — single class, zero deps. See Section 26. |
| 5 | **Java version minimum?** | Java 17 vs Java 11 | Customer reach | ✅ Decided: Java 11 minimum. All agent dependencies available since Java 11. Java 21 detected at runtime. |
| 6 | **Agent sync flow.json from FCS at startup?** | Yes (accurate filtering) vs No (works offline) | Accuracy vs simplicity | ✅ Decided: Optional enrichment — async background pull, never blocks. See Q3 rationale. |
| 7 | **OpenTelemetry interop?** | Bridge OTel spans vs ignore vs export as OTel | Ecosystem compatibility | ✅ Decided: Hybrid — Flow owns method-level, OTel provides external systems. See Section 27. |
| 8 | **gRPC transport option?** | HTTP-only vs gRPC (lower overhead at scale) | Scale & complexity | ✅ Decided by Q7: OTLP/HTTP default (works everywhere). OTLP/gRPC supported natively by OTel SDK — FCS exposes gRPC endpoint in Phase 6 (Advanced). No custom work needed. |
| 9 | **Multi-tenancy at agent level?** | Agent sends tenant ID vs FCS infers from API key | SaaS architecture | ✅ Decided: FCS infers tenant from API key. Agent sends only `graphId` + `buildMeta`. API key = tenant identity. Simple, secure, no agent complexity. |
| 10 | **Agent metrics — push to FCS or local only?** | Push to FCS (centralized) vs JMX only | Observability | ✅ Decided: Local only — single log line every 60s (`[flow-agent] active=true circuit=CLOSED`). Not a product feature per scope rules. JMX bean for tooling access. Push to FCS is out of scope. |
| 11 | **Redis key pattern normalization — where does it live?** | In the agent (strips at emit time) vs in FCS (strips at ingest time) | nodeId consistency | ✅ Decided by Q7: FCS side — `OtelSpanMapper` normalises `db.redis.key` attribute at ingest time. OTel already captures the key — we strip variable parts (numeric IDs, UUIDs) server-side. Agent sends raw OTel span. |
| 12 | **SQL table name extraction — how deep?** | Simple prefix parse (`FROM orders`) vs full SQL parser | Accuracy vs complexity | ✅ Decided by Q7: Not needed — OTel captures `db.sql.table` as a standard semantic attribute. No SQL parsing required in agent or FCS. `OtelSpanMapper` reads `db.sql.table` directly. |
| 13 | **Should external system nodes exist in flow.json if not explicitly used?** | Only scanned nodes vs auto-discovered at runtime | Graph completeness | ✅ Decided: YES — static scanners MUST produce external system nodes (DB, Redis, Kafka, ES etc.) in flow.json. Without them MergeEngine cannot link runtime OTel spans to static graph nodes. New scanner plugins required per Section 24.6. |
| 14 | **Integration classpath detection — at startup or lazy?** | Detect all at startup (faster, simpler) vs lazy per-class-load (more accurate) | Startup time | ✅ Decided by Q3+Q7: For OTel customers — OTel auto-instrumentation handles this. For Flow Lightweight Agent — lazy at class-load time (decided in Q3). No eager startup scan. |
| 15 | **How to handle AWS SDK v1 vs v2 coexistence?** | Two separate integration classes vs single with version detection | Compatibility | ✅ Decided by Q7: OTel already maintains separate instrumentation for AWS SDK v1 and v2. Flow inherits this automatically via OTel auto-instrumentation. `OtelSpanMapper` handles both — same semantic attributes emitted by OTel for both versions. |

---

> **This document is a living brainstorm. Edit sections, add comments, challenge assumptions.**  
> **ALL 15 questions decided ✅**  
> **Architecture:** Hybrid — Flow owns method-level instrumentation, OTel provides external system spans. See Section 27.  
> **Next step:** Begin Phase 1 — Flow Method Engine (ByteBuddy + FlowContext + batch sender).

---

## 22-A. Decision Rationale — Closed Questions

### Q1 — Agent lives in a separate repo ✅

Separate repo gives independent release cycles. The nodeId contract (the shared schema) is the only coupling point — documented, not code-coupled.

---

### Q2 — ENTER + EXIT sent separately, FCS does the pairing ✅

**The options:**

```
Option A — Agent pairs ENTER+EXIT → sends one METHOD_EXECUTION event with durationMs
Option B — Agent sends ENTER and EXIT separately → FCS pairs them by spanId
```

**Why Option B is correct for graph animation specifically:**

| Concern | Option A (Agent pairs) | Option B (FCS pairs) |
|---------|----------------------|---------------------|
| **Real-time animation** | ❌ Node only lights up AFTER method exits — you see the past | ✅ Node lights up ON ENTER — you see execution as it happens |
| **Orphaned ENTER (crash / exception)** | ❌ Sits in agent buffer forever → memory leak risk | ✅ FCS sees the ENTER, marks node as "in-flight", can show incomplete traces |
| **Recursive calls** (`method → same method`) | ❌ Agent must maintain a stack per thread — complex, error-prone | ✅ Each call has its own `spanId` — trivially handled |
| **Async / `@Async` / virtual threads** | ❌ ENTER on thread A, EXIT on thread B — pairing by descriptor is ambiguous | ✅ `spanId` is the unique call identifier — works across threads |
| **Agent complexity** | ❌ Agent holds state in memory per open call — bounded but real | ✅ Agent is stateless per event — emit and forget |
| **Event volume** | ✅ Half the events | ❌ 2× events — but established not a real problem at our scale+sampling rate |

**The decisive insight:**

> For a **real-time graph animation** product, ENTER is the signal that drives the UI.
> The node should light up the moment execution enters it — not after it exits.
> Agent-side pairing would delay every node appearance by the full method duration.
> That is the wrong behaviour for our core Objective 1.

**Decision:** Send ENTER and EXIT as separate events. FCS pairs by `spanId` to compute `durationMs`. The graph animates on ENTER. EXIT closes the node. This is correct, not just simpler.

---

### Q4 — Checkpoint SDK is a separate `flow-sdk.jar` ✅

**The options considered:**
- (a) Separate `flow-sdk.jar` — single class, zero dependencies
- (b) Developer uses reflection to call agent internals
- (c) Developer writes special log markers that the agent detects

**Why Option A is the only correct answer:**

| Concern | Option A (`flow-sdk.jar`) | Option B (Reflection) | Option C (Log markers) |
|---------|--------------------------|----------------------|----------------------|
| **Developer experience** | ✅ Clean API: `Flow.checkpoint("key", value)` | ❌ Ugly, fragile reflection boilerplate | ❌ Magic strings in logs — no IDE support, typo-prone |
| **Compile-time safety** | ✅ Compiler checks the method call | ❌ Reflection fails at runtime silently | ❌ No type safety at all |
| **Zero agent dependency** | ✅ SDK is a no-op without the agent — ships to prod safely | ❌ Reflection requires knowing agent internals | ❌ Log framework required |
| **Classpath impact** | ✅ Single class, `< 5KB`, zero transitive deps | ❌ No JAR but brittle coupling to agent | ❌ Depends on logging framework version |
| **IDE support** | ✅ Autocomplete, Javadoc, refactoring | ❌ None | ❌ None |

**Key design properties of `flow-sdk.jar`:**
- **Single class**: `com.flow.sdk.Flow` — one public static method `checkpoint(String, Object)`
- **Zero dependencies**: no Jackson, no HTTP, nothing
- **No-op without agent**: if `-javaagent` is not attached, `Flow.checkpoint()` returns immediately — costs one empty method call, essentially free
- **Agent intercepts via ByteBuddy**: when agent IS attached, ByteBuddy installs advice on `Flow#checkpoint()` at class-load time — associates the checkpoint with the currently-executing graph node automatically via `FlowContext.currentNodeId()`
- **Developer owns the data**: they choose the key, they choose the value — no automatic PII capture

**Decision:** `flow-sdk.jar` — separate module, published to Maven Central, single class, zero dependencies. See Section 26 for full design.

---

### Q3 + Q6 — Generics erasure AND agent startup sync — decided together ✅

These two questions are the same decision. The answer to Q3 determines the answer to Q6.

---

**The generics erasure problem:**

```
Static scanner reads source code:
  public KafkaTemplate<String, String> kafkaTemplate()
  public List<Order> findAll()
  public Optional<Order> findById(Long id)

flow.json nodeIds (with generics):
  ...KafkaProducerConfig#kafkaTemplate():KafkaTemplate<String, String>
  ...OrderRepository#findAll():List<Order>
  ...OrderRepository#findById(Long):Optional<Order>

Agent at runtime — JVM erases generics:
  returnType = KafkaTemplate   ← <String,String> is gone
  returnType = List            ← <Order> is gone
  returnType = Optional        ← <Order> is gone

Result: nodeIds don't match → graph nodes go dark even though they executed
```

---

**Why the three original options were rejected:**

| Option | Problem |
|--------|---------|
| **B — Manifest lookup only** | Hard dependency on FCS at startup. If FCS unreachable, agent cannot instrument anything. Violates graceful degradation principle. |
| **A — Adapter emits erased form** | Erased forms **collide** — two overloads differing only in generic params (`save(List<Order>)` vs `save(List<User>)`) both erase to `save(List)`. Ambiguous. Unreliable. |
| **C — FCS fuzzy match** | Silent wrong matches produce **misleading graphs**. A node lighting up that didn't actually run is worse than a node not lighting up. |

---

**The key insight — Java reflection preserves generics:**

The JVM erases generics at the **bytecode execution level** — but generic type information survives in **reflection metadata**:

```java
// What ByteBuddy sees at bytecode level (erased):
method.getReturnType()             → KafkaTemplate.class   ❌ erased

// What reflection gives us (preserved):
method.getGenericReturnType()      → KafkaTemplate<String, String>  ✅ full
method.getGenericParameterTypes()  → [List<Order>]                  ✅ full
```

This is Java's `java.lang.reflect.ParameterizedType` — always available, no external library needed.

---

**The Decision — Option D (primary) + Option B (optional enrichment):**

```
PRIMARY — Option D: Lazy reflection at class-load time

  As each customer class loads into the JVM:
    → ByteBuddy ClassFileTransformer fires (already happening for instrumentation)
    → Agent calls method.getGenericReturnType() and method.getGenericParameterTypes()
    → Builds nodeId with full generics → matches flow.json exactly
    → Adds to in-memory lookup table
    → Installs advice on matching methods

  Properties:
    ✅ Preserves generics — KafkaTemplate<String,String>, List<Order> — exact match
    ✅ Zero network dependency — works completely offline
    ✅ Zero startup latency — lazy, happens at class-load time (piggyback on instrumentation)
    ✅ Always matches exactly what is running — cannot be stale
    ✅ Works for ALL tiers — solo dev to enterprise — same behaviour


SECONDARY — Option B: flow.json manifest as optional enrichment

  After JVM starts, agent pulls manifest async in background:
    GET /manifest from FCS  (always client → FCS, never the reverse)
    Non-blocking — app starts immediately, manifest arrives later
    If FCS unreachable → agent continues with reflection-only index
    If FCS responds → enriches index with what reflection cannot see:

      Reflection CAN build:
        ✅ com.greens.order.core.OrderService#placeOrder(String):String
        ✅ com.greens.order.config.KafkaProducerConfig#kafkaTemplate():KafkaTemplate<String,String>

      Reflection CANNOT build (no Java method signature):
        ❌ topic:orders.v1
        ❌ endpoint:POST /api/orders/{id}
        ❌ db:primary:repository:OrderRepository
        ❌ cache:redis:localhost:GET:order:*

      These come from flow.json manifest only.
```

---

**What this means for Q6 (agent sync flow.json at startup):**

> Q6 is answered by Q3. The manifest is **optional enrichment**, not a hard dependency.
> The agent always starts. The agent always instruments. The manifest improves
> external system node coverage when available. The agent pulls it — FCS never pushes.

```
Startup sequence:
  1. premain() fires → install ClassFileTransformer → return (< 1ms)
  2. App starts normally
  3. Classes load → agent reflects lazily → method index builds incrementally
  4. Background thread: GET /manifest from FCS (async, non-blocking)
  5. If manifest arrives → external system nodes enriched
  6. If manifest never arrives → method nodes still work perfectly
```

**The rule for all communication:**
> Always **client → FCS**. The agent pulls. FCS never pushes into the customer's JVM.
> This is non-negotiable — customer JVMs sit behind firewalls and private networks.

---

### Q7 — Hybrid Architecture: Flow for internals, OTel for boundaries ✅

**The question was:** Should Flow interact with OpenTelemetry — bridge OTel spans, ignore it, or export as OTel?

**Initial thinking (v0.4):** OTel-first — delegate everything to OTel, build a thin adapter.

**After deep analysis (v0.5):** The OTel-first approach **breaks Flow's core requirements**. OTel was designed for distributed tracing at service boundaries — not for method-level graph animation inside services. Five specific problems were identified (see Section 27.2–27.6):

1. **OTel does NOT create spans for internal methods** — 0 out of 6 method nodes in a typical call chain get OTel spans. The graph is dark.
2. **Checkpoints land on the wrong node** — `Span.current()` returns a boundary span (HTTP), not the method span. `Flow.checkpoint("cart_total", 250)` inside `placeOrder` attaches to the HTTP endpoint node instead.
3. **Overloaded methods are indistinguishable** — OTel captures `code.function=placeOrder` without parameter types. Two overloads become the same span. Wrong graph node lights up.
4. **Async method-to-method edges are lost** — OTel propagates traceId across threads but has no method-level spans to connect. Edges go from boundary to boundary, not method to method.
5. **100% graph coverage is impossible** — Even at 0% sampling loss, internal methods have no OTel spans.

**However, OTel excels at exactly what Flow doesn't need to build:**
- External system instrumentation (DB, Redis, Kafka, HTTP, gRPC, S3, SQS, ES, Mongo)
- W3C traceparent cross-service propagation
- Reactive / virtual thread context propagation for boundary calls

**Decision: Precise Hybrid.**

```
Flow owns method-level:
  → ByteBuddy instrumentation for every method in flow.json
  → FlowContext with currentNodeId() for checkpoint accuracy
  → spanStack for method-to-method parent-child edges
  → Full nodeId (FQCN + params + return type + generics)

OTel provides external-system-level:
  → JDBC, Redis, Kafka, ES, Mongo, gRPC, HTTP, S3, SQS spans
  → W3C traceparent cross-service propagation
  → Flow bridges OTel spans into its pipeline via FlowSpanProcessor

Both layers coexist in the same JVM — no conflict:
  → Flow's ByteBuddy instruments customer packages only
  → OTel's ByteBuddy instruments library/framework code only
  → Zero overlap
```

See **Section 27** for the full analysis, architecture diagram, and phased delivery plan.

---

### Q8 — OTLP/HTTP default, OTLP/gRPC Phase 8 ✅

**Decided by Q7.** OTel SDK natively supports both `OTLP/HTTP` and `OTLP/gRPC`. No custom transport work required.

- **OTLP/HTTP** — default. Works through every proxy, firewall, load balancer. Simple to implement on FCS. Handles 99% of customer scenarios.
- **OTLP/gRPC** — Phase 8. FCS exposes port 4317. Useful for very high volume enterprise deployments (100k+ spans/sec). OTel SDK switches with one config line — no agent code change.

No original custom transport design needed. Question answered by the OTel standard.

---

### Q9 — FCS infers tenant from API key ✅

The agent sends `graphId` + `buildMeta` only. It does NOT send a tenant ID.

**Why:** API key = tenant identity. Every request from the agent carries `Authorization: Bearer <api-key>`. FCS resolves the tenant from the API key at ingestion time — same as how every SaaS API works (Stripe, GitHub, Datadog etc.)

**What this means:**
- One API key per organisation (or per environment if the customer wants isolation)
- `graphId` scopes the graph within the tenant — `payment-service`, `order-service` etc.
- Agent configuration is minimal — just `graphId` + `api-key` + FCS URL
- No multi-tenancy complexity in the agent

---

### Q10 — Agent metrics: local log line only ✅

**Scope rule applies:** metrics dashboards are out of scope (Section 0.2).

Agent self-monitoring is limited to:
```
[flow-agent] active=true circuit=CLOSED spans=1240/s dropped=0 heap=18MB
```
One log line every 60 seconds. JMX MBean exposed for tooling access.

**Not pushed to FCS.** The agent's health is the customer's concern — they can monitor it via their existing log aggregation. Flow does not build a second observability product on top of itself.

---

### Q11 — Redis key normalisation lives in FCS (`OtelSpanMapper`) ✅

**Decided by Q7.** OTel captures the Redis key in `db.redis.key` attribute. The agent sends it as-is (raw OTel span). `OtelSpanMapper` in FCS strips variable parts at ingest time:

```
OTel span: db.redis.key = "order:12345"
OtelSpanMapper: strip numeric suffix → "order:*"
nodeId: cache:redis:localhost:GET:order:*
```

**Why FCS-side, not agent-side:**
- Agent stays dumb — emits standard OTel, normalisation is a Flow concern
- Normalisation rules can be updated in FCS without redeploying agents
- All customers get consistent normalisation from one place

---

### Q12 — SQL table extraction: not needed ✅

**Decided by Q7.** OTel captures `db.sql.table` as a standard semantic attribute — already extracted by OTel's JDBC instrumentation. No SQL parsing needed anywhere.

```
OTel span: db.system=postgresql, db.sql.table=orders, db.operation=SELECT
OtelSpanMapper: → db:primary:query:orders
```

The original concern (parse SQL to find table name) is entirely eliminated by OTel's semantic conventions.

---

### Q13 — External system nodes MUST exist in flow.json ✅

**Decision:** YES — static scanners must produce external system nodes in `flow.json`.

Without them, `OtelSpanMapper` maps the OTel span to a correct nodeId — but `MergeEngine` finds no matching static node to merge with. The runtime event has nowhere to land on the graph.

**Rule:** For every external system the runtime agent can emit events for, there must be a corresponding static scanner that produces the matching nodeId in `flow.json`:
- `flow-jdbc-plugin` → `db:*` nodes
- `flow-redis-plugin` → `cache:redis:*` nodes
- `flow-kafka-plugin` (existing) → `topic:*` nodes
- `flow-elasticsearch-plugin` → `search:es:*` nodes
- `flow-mongo-plugin` → `mongo:*` nodes
- `flow-aws-plugin` → `s3:*`, `queue:sqs:*`, `topic:sns:*` nodes
- `flow-grpc-plugin` → `grpc:*` nodes
- `flow-rabbit-plugin` → `queue:rabbit:*` nodes

---

### Q14 — Integration classpath detection: lazy at class-load ✅

**Decided by Q3 + Q7.**
- For OTel customers: OTel auto-instrumentation detects classpath at startup automatically — Flow inherits this.
- For Flow Lightweight Agent (non-OTel): lazy detection at class-load time as decided in Q3 — `IntegrationRegistry` checks `requiredClasses()` when each class loads, activates plugins on-demand.

No eager startup classpath scan. Zero added startup latency.

---

### Q15 — AWS SDK v1 vs v2: inherited from OTel ✅

**Decided by Q7.** OTel maintains separate, battle-tested instrumentation for AWS SDK v1 (`com.amazonaws`) and v2 (`software.amazon.awssdk`). Both emit spans with identical semantic attributes. `OtelSpanMapper` handles both through the same mapping rules — no version detection needed in Flow code.



**Decision:** Java 11.

**Why not Java 17:**
- Java 17 is still not universal in production — many enterprise teams are on Java 11 LTS
- Setting Java 17 as the minimum would exclude a significant portion of real-world customer JVMs
- As a SaaS product, maximising customer reach at no technical cost is the right call

**Why Java 11 works perfectly for the agent:**

| Agent requirement | Available since |
|---|---|
| `java.net.http.HttpClient` (async HTTP transport) | Java 11 ✅ |
| `Method.getGenericReturnType()` (generics preservation) | Java 1.5 ✅ |
| ByteBuddy instrumentation | Java 8+ ✅ |
| `ThreadLocal` based trace context | Java 1.2 ✅ |
| `CompletableFuture` async propagation | Java 8 ✅ |
| GZIP compression (`GZIPOutputStream`) | Java 1.1 ✅ |

**Java 21 virtual thread support:**
Java 21 `ScopedValue` and virtual thread aware context propagation will be enabled **conditionally at runtime**:
```java
// At agent startup — detect Java version
if (Runtime.version().feature() >= 21) {
    // enable ScopedValue-based context propagation
} else {
    // use ThreadLocal-based context propagation
}
```
No minimum version change needed — the same agent JAR runs on Java 11 through Java 21+.









---

## 23. Deployment Mode Tiers — Individual vs Enterprise

### 23.1 The Reality: Not Every User Has a Jenkins Problem

The build/runtime drift problem described earlier (where a different build is deployed vs what was scanned) is **real for enterprises** but does **not exist at all in simpler scenarios**. Flow must accommodate the full spectrum:

```
──────────────────────────────────────────────────────────────────────
TIER 1: Individual Developer / Solo Project
  └─ Build on laptop → run on laptop / local Docker
  └─ flow.json is ALWAYS from the same build that is running
  └─ NO drift. NO Jenkins. NO separate build pipeline.
  └─ This is the easiest case. Maximum value, zero complexity.

TIER 2: Small Team / Startup
  └─ GitHub Actions / simple CI pipeline
  └─ One environment (staging + prod are the same app)
  └─ flow.json is pushed to FCS as part of the build step
  └─ Drift is rare but possible (hotfixes, manual deploys)
  └─ Needs: lightweight reconciliation, not a full merge engine

TIER 3: Enterprise / Multi-Team
  └─ Jenkins, ArgoCD, multiple environments
  └─ Multiple microservices, different versions per environment
  └─ Production rollbacks common
  └─ flow.json per service per build per environment
  └─ Drift is the NORM, not the exception
  └─ Needs: full build-identity system, merge-safe design
──────────────────────────────────────────────────────────────────────
```

### 23.2 Design Principle: Tiers Should Work With the Same Agent

The agent binary should be **identical** across all tiers. Only the **configuration and behavior of FCS** changes based on the tier. The customer should not need to pick a "tier" — it should degrade gracefully.

```
Same agent JAR → same -javaagent flag → different behavior based on what FCS knows
```

### 23.3 How Each Tier Behaves at Runtime

#### Tier 1 — Individual Developer

```
Dev machine:
  $ mvn flow:scan         → produces flow.json, pushes to FCS
  $ java -javaagent:flow-agent.jar -jar myapp.jar

FCS has exactly one flow.json for this graphId.
Agent starts → downloads manifest → full nodeId whitelist → instrument.

Runtime merge: 100% accurate. No drift possible.
No special handling needed. Just works.
```

#### Tier 2 — Small Team

```
GitHub Actions CI:
  - Build JAR
  - Run flow scan → push flow.json to FCS (tagged with commitSha)
  - Deploy JAR

Agent starts → sends commitSha as part of startup handshake:
  { "graphId": "payment-service", "buildId": "abc123" }

FCS:
  - Has flow.json for commitSha abc123 → serve it as manifest.
  - If commitSha not found → fall back to latest known flow.json.
  - Log warning: "runtime build abc123 not found, using latest"

Merge accuracy: High. Fallback to latest handles most cases.
```

#### Tier 3 — Enterprise (Jenkins / Multi-Env)

```
Jenkins pipeline:
  - Build JAR → artifact: payment-service-2.4.1.jar
  - Run flow scan → push flow.json tagged:
      { graphId: "payment-service", version: "2.4.1", env: "prod" }
  - Deploy 2.4.1 to prod

Agent starts → identifies itself:
  { "graphId": "payment-service", "version": "2.4.1", "env": "prod" }

FCS:
  - Looks up exact flow.json for version 2.4.1
  - If not found (e.g., rollback to 2.3.0) →
      return flow.json for 2.3.0 if available, else latest
  - MergeEngine only merges events whose nodeIds exist in the matched flow.json
  - Unmatched events → stored raw, surfaced as "unresolved events" in UI

Production rollback scenario:
  v2.4.1 is running → new deploy of v2.4.0 (rollback)
  Agent of v2.4.0 sends buildId=2.4.0
  FCS serves flow.json from v2.4.0
  MergeEngine uses v2.4.0 graph → merge is accurate for the deployed code
  Methods from v2.4.1 (that no longer exist) are NOT in the graph → no ghost nodes
```

### 23.4 Build Identity: What the Agent Must Send

To support all tiers, the agent should send a **build identity** envelope at startup and with each batch:

```json
{
  "graphId": "payment-service",
  "buildMeta": {
    "buildId": "abc123",           // git commit SHA, build number, or null
    "version": "2.4.1",            // semantic version or null
    "env": "production",           // environment tag or null
    "scannedAt": 1735412000000     // when flow.json was generated (from flow.json header)
  }
}
```

**Rules for FCS manifest resolution:**

| buildMeta sent | FCS behavior |
|----------------|-------------|
| `null` / nothing | Use the latest flow.json for this graphId. Works perfectly for Tier 1. |
| `buildId` only | Look up flow.json by buildId. Fall back to latest if not found. |
| `version` + `env` | Look up flow.json by version+env. Fall back to version-only. |
| All fields | Exact match first. Cascade through fallback chain. |

### 23.5 Graceful Degradation for Unmatched NodeIds

Regardless of tier, some runtime events **will not match** static graph nodes:
- Framework internals slipping through filters
- Lambda-generated classes
- Edge case proxy resolution failures
- Enterprise drift scenarios

**What should FCS do with unmatched events?**

```
Option A: DROP silently
  ✅ Clean graph, no noise
  ❌ Lose potentially useful data (e.g., a new method added post-scan)

Option B: STORE as raw events, surface in UI as "unresolved"
  ✅ Nothing is lost
  ✅ UI can show: "X events from runtime had no matching static node"
  ✅ Alert developer: "Your flow.json may be stale — re-scan"
  ❌ Slightly more storage / complexity

Option C: FUZZY MATCH — attempt to match to closest nodeId
  ✅ Auto-heals minor drift (e.g., parameter type formatting differences)
  ❌ Risk of wrong matches producing misleading graphs
  ❌ Complex to implement correctly

RECOMMENDATION: Option B as default + Option C for minor formatting differences only
  - Exact match → merge as normal
  - Fuzzy match (formatting only, e.g., "java.lang.String" vs "String") → merge with flag
  - No match → store as raw unresolved event
  - UI shows unresolved event count → prompts developer to re-scan
```

### 23.6 The "Re-Scan Nudge" — Closing the Loop for All Tiers

For all tiers, Flow should detect when runtime events consistently fail to match static graph nodes and **proactively alert**:

```
FCS detects:
  - >10% of runtime events are unresolved for graphId "payment-service"

FCS action:
  - Flags the graphId as "stale"
  - Returns a warning in GET /graph/:id response:
      { "staleness": "HIGH", "unmatchedEventPercent": 23 }
  - UI shows: "⚠️ Runtime events are not fully matching this graph.
               Consider re-scanning your codebase."
```

This works for ALL tiers:
- **Tier 1 dev:** Gets the nudge if they forget to re-scan after code changes
- **Tier 2 team:** Gets the nudge if CI scan wasn't run after a hotfix
- **Tier 3 enterprise:** Gets the nudge after a rollback or partial deploy

### 23.7 Summary Table

| Concern | Tier 1 (Solo Dev) | Tier 2 (Small Team) | Tier 3 (Enterprise) |
|---------|-------------------|---------------------|---------------------|
| Build/runtime drift | Never | Rare | Common |
| Agent config needed | `graphId` only | `graphId` + `buildId` | `graphId` + `version` + `env` |
| FCS manifest lookup | Latest always | By buildId, fallback | By version+env, cascade |
| Unmatched event handling | Negligible | Occasional warning | Full unresolved tracking |
| Re-scan nudge | Nice to have | Useful | Critical |
| Production rollback handling | N/A | N/A | Full flow.json versioning per deploy |
| Complexity of setup | Minimal | Low | Medium |

> **Key design insight:** The same agent, the same FCS, the same merge engine handles all tiers.
> Complexity is proportional to what the user sends in `buildMeta`.
> A solo dev who sends nothing gets a great experience automatically.
> An enterprise team that sends full build metadata gets precise per-build graph merges.

---

## 24. External System Integrations — Beyond Kafka

### 24.1 The Bigger Picture

Kafka was the first "async hop" we designed for, but a real-world Java service talks to many external systems. Each of these is an **execution boundary** — a point where the call leaves the JVM. Flow must represent ALL of them as first-class nodes in the graph, not just Kafka topics.

```
Your Java Service
  │
  ├──▶ PostgreSQL / MySQL      (JDBC, Hibernate, Spring Data JPA)
  ├──▶ Redis                   (Lettuce, Jedis, Spring Data Redis)
  ├──▶ Elasticsearch           (High-level REST client, Spring Data ES)
  ├──▶ MongoDB                 (Mongo driver, Spring Data Mongo)
  ├──▶ AWS S3                  (AWS SDK v2)
  ├──▶ AWS SQS / SNS           (async messaging — like Kafka)
  ├──▶ Google Pub/Sub          (async messaging)
  ├──▶ RabbitMQ / AMQP         (async messaging)
  ├──▶ gRPC services           (blocking + async stubs)
  ├──▶ REST (outbound HTTP)    (RestTemplate, WebClient, Feign, OkHttp)
  ├──▶ Hazelcast / Ehcache     (distributed cache)
  └──▶ Kafka                   (already designed ✅)
```

Every one of these must become a **node in flow.json** and a **runtime event** when called.

---

### 24.2 The Integration Contract

Every external system integration in the agent must implement the same contract. This is what makes the system **extensible** — adding a new integration is adding a new implementation of this contract, nothing more.

```
Integration Contract (3 mandatory parts):

1. INTERCEPT POINT
   Where in the client library do we hook?
   Must be deterministic — same call path every time.

2. NODE ID FORMAT
   What does this call look like as a node in flow.json?
   Must be stable — same query/operation always produces same nodeId.

3. EVENT TYPE
   What RuntimeEvent type does this emit?
   (METHOD_EXIT covers internal calls. External systems get their own types.)
```

**New event types needed** (additions to the existing set):

```
Existing:
  METHOD_ENTER, METHOD_EXIT, PRODUCE_TOPIC, CONSUME_TOPIC, CHECKPOINT, ERROR

New:
  DB_QUERY          ← any relational DB call (JDBC / JPA / Hibernate)
  CACHE_GET         ← Redis, Hazelcast, Ehcache read
  CACHE_SET         ← Redis, Hazelcast, Ehcache write
  SEARCH_QUERY      ← Elasticsearch, OpenSearch
  DOCUMENT_READ     ← MongoDB, DynamoDB read
  DOCUMENT_WRITE    ← MongoDB, DynamoDB write
  OBJECT_STORE_GET  ← S3, GCS read
  OBJECT_STORE_PUT  ← S3, GCS write
  QUEUE_PRODUCE     ← SQS, SNS, RabbitMQ, Pub/Sub publish
  QUEUE_CONSUME     ← SQS, RabbitMQ, Pub/Sub receive
  RPC_CALL          ← gRPC outbound
  RPC_SERVE         ← gRPC inbound
  HTTP_OUT          ← outbound REST (RestTemplate, WebClient, Feign)
```

---

### 24.3 nodeId Format Per Integration

The static scanner (`flow-java-adapter`) must produce these same nodeId formats when it scans the codebase. The agent at runtime must produce the **exact same format**. This is the contract that MergeEngine depends on.

```
┌─────────────────────┬────────────────────────────────────────────────────┐
│ System              │ nodeId Format                                       │
├─────────────────────┼────────────────────────────────────────────────────┤
│ JDBC (raw)          │ db:{dataSourceName}:query:{tableName}               │
│                     │ e.g. db:primary:query:orders                        │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Spring Data JPA     │ db:{dataSourceName}:repository:{RepositoryName}     │
│                     │ e.g. db:primary:repository:OrderRepository          │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Hibernate (HQL)     │ db:{dataSourceName}:entity:{EntityName}             │
│                     │ e.g. db:primary:entity:Order                        │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Redis (Lettuce)     │ cache:redis:{host}:{operation}:{keyPattern}         │
│                     │ e.g. cache:redis:localhost:GET:order:*              │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Spring Data Redis   │ cache:redis:{repositoryName}                        │
│                     │ e.g. cache:redis:OrderCacheRepository               │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Elasticsearch       │ search:es:{clusterName}:{indexName}:{operation}     │
│                     │ e.g. search:es:prod-cluster:orders:SEARCH           │
├─────────────────────┼────────────────────────────────────────────────────┤
│ MongoDB             │ mongo:{dbName}:{collectionName}:{operation}         │
│                     │ e.g. mongo:ecommerce:orders:FIND                    │
├─────────────────────┼────────────────────────────────────────────────────┤
│ AWS S3              │ s3:{bucketName}:{operation}                         │
│                     │ e.g. s3:orders-bucket:PUT                           │
├─────────────────────┼────────────────────────────────────────────────────┤
│ AWS SQS             │ queue:sqs:{queueName}                               │
│                     │ e.g. queue:sqs:orders-queue                         │
├─────────────────────┼────────────────────────────────────────────────────┤
│ AWS SNS             │ topic:sns:{topicName}                               │
│                     │ e.g. topic:sns:order-events                         │
├─────────────────────┼────────────────────────────────────────────────────┤
│ RabbitMQ            │ queue:rabbit:{exchangeName}:{routingKey}            │
│                     │ e.g. queue:rabbit:orders.exchange:order.created     │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Google Pub/Sub      │ topic:pubsub:{projectId}:{topicName}                │
│                     │ e.g. topic:pubsub:my-project:orders                 │
├─────────────────────┼────────────────────────────────────────────────────┤
│ gRPC (outbound)     │ grpc:{serviceName}/{methodName}                     │
│                     │ e.g. grpc:PaymentService/Charge                     │
├─────────────────────┼────────────────────────────────────────────────────┤
│ gRPC (inbound)      │ grpc:serve:{serviceName}/{methodName}               │
│                     │ e.g. grpc:serve:OrderService/PlaceOrder             │
├─────────────────────┼────────────────────────────────────────────────────┤
│ Hazelcast           │ cache:hazelcast:{mapName}:{operation}               │
│                     │ e.g. cache:hazelcast:sessions:GET                   │
└─────────────────────┴────────────────────────────────────────────────────┘
```

> **Rule:** The prefix (`db:`, `cache:`, `search:`, `mongo:`, `s3:`, `queue:`, `topic:`, `grpc:`) is the **system type discriminator**. The MergeEngine uses this to decide what kind of node to render in the UI (cylinder for DB, lightning bolt for cache, etc.)

---

### 24.4 Intercept Points Per Integration

For each system, exactly where does ByteBuddy hook in?

#### Database — JDBC / Hibernate / Spring Data JPA

```
Target:  java.sql.Connection#prepareStatement(String sql)
         java.sql.Statement#executeQuery(String sql)
         org.hibernate.Session#createQuery(String hql)
         Spring Data: Repository method calls (already in method advice)

What we capture:
  - Operation type (SELECT / INSERT / UPDATE / DELETE) from SQL prefix
  - Table name (parsed from SQL — best effort, not full SQL parser)
  - DataSource name (from DataSource bean name in Spring context)
  - Duration (ENTER/EXIT timing)

What we DO NOT capture:
  - Full SQL text (PII / sensitive data risk)
  - Bind parameters (PII)
  - Result set data

nodeId construction:
  "SELECT * FROM orders WHERE id=?" → db:primary:query:orders
  HQL "FROM Order o WHERE o.id=:id" → db:primary:entity:Order
```

#### Redis — Lettuce / Jedis / Spring Data Redis

```
Target:  io.lettuce.core.RedisAsyncCommands (all command methods)
         redis.clients.jedis.Jedis (all command methods)
         Spring Data: RedisTemplate.opsForValue().get(key)

What we capture:
  - Command type (GET, SET, DEL, HGET, ZADD, etc.)
  - Key pattern (strip variable parts — "order:123" → "order:*")
  - Duration

Key pattern normalization:
  "session:abc123def456" → "session:*"   (UUID/hash stripped)
  "order:12345"          → "order:*"     (numeric ID stripped)
  "user:profile:42"      → "user:profile:*"

This normalization is CRITICAL — without it every key becomes
a separate node in the graph, making it unreadable.
```

#### Elasticsearch

```
Target:  org.elasticsearch.client.RestHighLevelClient#search()
         org.elasticsearch.client.RestHighLevelClient#index()
         co.elastic.clients.elasticsearch.ElasticsearchClient (v8+)
         Spring Data: ElasticsearchRepository method calls

What we capture:
  - Index name
  - Operation type (SEARCH, INDEX, DELETE, UPDATE, GET)
  - Duration

What we DO NOT capture:
  - Query body (may contain PII)
  - Response documents
```

#### MongoDB

```
Target:  com.mongodb.client.MongoCollection#find()
         com.mongodb.client.MongoCollection#insertOne/Many()
         com.mongodb.client.MongoCollection#updateOne/Many()
         com.mongodb.reactivestreams.client (reactive variant)
         Spring Data: MongoRepository method calls

What we capture:
  - Database name
  - Collection name
  - Operation type (FIND, INSERT, UPDATE, DELETE, AGGREGATE)
  - Duration
```

#### AWS S3

```
Target:  software.amazon.awssdk.services.s3.S3Client#getObject()
         software.amazon.awssdk.services.s3.S3Client#putObject()
         software.amazon.awssdk.services.s3.S3AsyncClient (async variant)

What we capture:
  - Bucket name
  - Operation type (GET, PUT, DELETE, LIST)
  - Duration

What we DO NOT capture:
  - Object key (may contain PII or sensitive path)
  - Object content
```

#### AWS SQS / SNS, RabbitMQ, Google Pub/Sub

```
Same pattern as Kafka — these are all async messaging systems:

PRODUCE side:
  Intercept the send/publish call
  → Inject flow-trace-id into message attributes/headers
  → Emit QUEUE_PRODUCE or TOPIC_PRODUCE event

CONSUME side:
  Intercept the receive/listener entry point
  → Extract flow-trace-id from message attributes/headers
  → Emit QUEUE_CONSUME or TOPIC_CONSUME event
  → Continue trace with same traceId

This gives us the same ASYNC_HOP stitching as Kafka.
```

#### gRPC

```
Producer (client stub):
  Target: io.grpc.ClientInterceptor
  → Intercept outbound call metadata
  → Inject flow-trace-id into gRPC metadata headers
  → Emit RPC_CALL event

Consumer (server stub):
  Target: io.grpc.ServerInterceptor
  → Extract flow-trace-id from incoming metadata
  → Emit RPC_SERVE event
  → Continue trace
```

---

### 24.5 The Plugin Architecture — Making Integrations Extensible

Since we will build many integrations over time, and they follow the same contract, we model them as **plugins inside the agent**:

```
flow-runtime-agent/
  └── src/main/java/com/flow/agent/
        └── integrations/
              ├── IntegrationPlugin.java          ← interface
              ├── IntegrationRegistry.java        ← loads active plugins
              ├── db/
              │     ├── JdbcIntegration.java
              │     ├── HibernateIntegration.java
              │     └── SpringDataJpaIntegration.java
              ├── cache/
              │     ├── LettuceRedisIntegration.java
              │     ├── JedisIntegration.java
              │     └── HazelcastIntegration.java
              ├── search/
              │     ├── ElasticsearchIntegration.java
              │     └── OpenSearchIntegration.java
              ├── document/
              │     └── MongoIntegration.java
              ├── objectstore/
              │     ├── AwsS3Integration.java
              │     └── GcsIntegration.java
              ├── messaging/
              │     ├── KafkaIntegration.java     ← already designed ✅
              │     ├── SqsIntegration.java
              │     ├── SnsIntegration.java
              │     ├── RabbitMqIntegration.java
              │     └── PubSubIntegration.java
              └── rpc/
                    └── GrpcIntegration.java
```

**The `IntegrationPlugin` interface:**

```java
interface IntegrationPlugin {

    // Which libraries does this plugin require to be present on the classpath?
    // If none are present, the plugin is skipped silently.
    List<String> requiredClasses();

    // Install ByteBuddy interceptors via the AgentBuilder.
    // Called once at agent startup.
    AgentBuilder install(AgentBuilder builder, AgentConfig config);

    // Human-readable name for logging / JMX metrics.
    String name();
}
```

**The `IntegrationRegistry`:**

```java
// At agent startup:
List<IntegrationPlugin> allPlugins = List.of(
    new JdbcIntegration(),
    new LettuceRedisIntegration(),
    new ElasticsearchIntegration(),
    new MongoIntegration(),
    new AwsS3Integration(),
    new KafkaIntegration(),
    new SqsIntegration(),
    new RabbitMqIntegration(),
    new GrpcIntegration()
    // ... more added over time
);

for (IntegrationPlugin plugin : allPlugins) {
    if (plugin.requiredClasses().stream().allMatch(ClassUtils::isPresent)) {
        agentBuilder = plugin.install(agentBuilder, config);
        log.info("[flow-agent] Activated integration: {}", plugin.name());
    } else {
        log.debug("[flow-agent] Skipped integration (not on classpath): {}", plugin.name());
    }
}
```

**Key properties of this design:**
- **Zero configuration** — integrations activate automatically if the library is on the classpath
- **Zero overhead** if a library isn't present — the plugin is never loaded
- **Extensible** — new integration = new class implementing `IntegrationPlugin`
- **Testable in isolation** — each plugin can be unit-tested independently

---

### 24.6 What the Static Scanner Must Also Produce

The agent emitting `db:primary:query:orders` at runtime is useless if `flow.json` doesn't also contain a node with that exact nodeId. This means the **static scanner** (`flow-adapter` + its plugins) must also be extended:

```
Current scanners:
  flow-spring-plugin  → scans @RestController, @Service, @Component etc.
  flow-kafka-plugin   → scans @KafkaListener, KafkaTemplate usage

New scanners needed (each a new plugin module):
  flow-jdbc-plugin        → scans @Query, Repository interfaces, DataSource beans
  flow-redis-plugin       → scans RedisTemplate, @Cacheable, @CacheEvict
  flow-elasticsearch-plugin → scans ElasticsearchRepository, RestHighLevelClient usage
  flow-mongo-plugin       → scans MongoRepository, MongoTemplate usage
  flow-aws-plugin         → scans S3Client, SqsClient, SnsClient usage
  flow-grpc-plugin        → scans .proto-generated service stubs
  flow-rabbit-plugin      → scans @RabbitListener, RabbitTemplate usage
```

Each scanner adds nodes to `flow.json` using the **same nodeId format** the runtime agent uses. The MergeEngine sees them as the same node.

---

### 24.7 What the Graph Looks Like With All Integrations

This is the real value — a **complete, live picture** of your architecture as it actually executes:

```
POST /api/orders/{id}
  │
  ├──▶ OrderService#placeOrder(String)
  │       │
  │       ├──▶ db:primary:repository:OrderRepository      ← reads order
  │       │
  │       ├──▶ cache:redis:localhost:GET:order:*           ← checks cache
  │       │
  │       ├──▶ OrderService#validateCart(String)
  │       │       │
  │       │       └──▶ db:primary:entity:CartItem          ← reads cart items
  │       │
  │       ├──▶ PaymentService#charge(String)
  │       │       │
  │       │       └──▶ grpc:PaymentGateway/Charge          ← external gRPC
  │       │
  │       ├──▶ search:es:prod-cluster:orders:INDEX         ← indexes in ES
  │       │
  │       ├──▶ topic:orders.v1                             ← publishes to Kafka
  │       │       │
  │       │       └──▶ OrderConsumer#onMessage(String)     ← (different service)
  │       │               │
  │       │               └──▶ s3:orders-bucket:PUT        ← stores receipt
  │       │
  │       └──▶ cache:redis:localhost:SET:order:*           ← updates cache
  │
  └── RESPONSE: 200 OK
```

What the graph shows at runtime (the UI concern, not the agent concern):
- 🟢 **Lit nodes** = executed in this trace — the path that actually ran
- ⚪ **Dim nodes** = in the static graph but NOT executed in this trace — potential dead code or uncovered paths
- 🔴 **Error nodes** = executed and threw an exception — annotated on the graph node itself
- 🔵 **Async edges** = hops across Kafka, SQS, RabbitMQ, Pub/Sub — stitched into the graph

This is **not a performance dashboard**. This is your **architecture, alive**.
No existing tool produces this view — not Datadog, not New Relic, not any APM.
They show services. Flow shows what is **inside** a service and how it connects — live.

---

### 24.8 Phased Delivery for Integrations

| Phase | Integrations | Rationale |
|-------|-------------|-----------|
| **Phase 1** | Method tracing only | Foundation — get the pipe working |
| **Phase 2** | Kafka, RabbitMQ, SQS, SNS, Pub/Sub | All async messaging — highest graph differentiation value |
| **Phase 3** | JDBC, Spring Data JPA, Hibernate | Databases — in almost every Java service |
| **Phase 4** | Redis / Lettuce / Spring Cache | Cache — extremely common, adds real nodes to the graph |
| **Phase 5** | Elasticsearch, MongoDB | Document stores — common in modern services |
| **Phase 6** | gRPC, AWS S3, GCS | RPC + object storage |
| **Phase 7** | Hazelcast, DynamoDB, Cassandra | Specialised — enterprise use cases |


> **Principle:** Each phase delivers **immediate visible value** in the graph UI.
> A user with only JDBC integration sees their service's DB calls as nodes — already more than any APM shows.

---

### 24.9 Hard Problems Specific to External Integrations

| Problem | Detail | Solution |
|---------|--------|---------|
| **Key pattern normalization (Redis)** | Every unique key = separate node → graph explosion | Strip numeric/UUID suffixes → `order:*` pattern |
| **Dynamic table names (JDBC)** | SQL built at runtime → table name unknown at scan time | Best-effort SQL parsing (just the FROM clause) |
| **Connection pool interception** | JDBC pools (HikariCP) wrap `Connection` — real class is a proxy | Instrument `java.sql.Connection` interface, not the impl |
| **Reactive clients (WebFlux + Redis)** | Lettuce reactive commands run on different thread | Same async context propagation as `@Async` |
| **AWS SDK v1 vs v2** | Different package, different method signatures | Two separate integration classes |
| **Spring `@Cacheable` abstraction** | Hides whether Redis or Hazelcast is underneath | Instrument both the abstraction AND the provider |
| **SQL injection risk in nodeId** | User data in SQL might leak into nodeId | Only capture table name, never column values or WHERE clause data |
| **Batch DB operations** | `executeBatch()` — many tables at once | Emit one event per statement in the batch |

---

## 25. Data Sensitivity & Deployment Topology

### 25.1 What Actually Leaves the Customer's JVM

It is critical to be precise about this. The agent sends **structural metadata** — not business data.

```
✅ SENT TO FCS (structural metadata)
  - nodeId strings        e.g. "com.acme.payments.fraud.FraudDetectionEngine#scoreTransaction(Transaction):RiskScore"
  - traceId / spanId      UUIDs — no business meaning
  - timestamps            when each node was entered/exited
  - event type            METHOD_ENTER, METHOD_EXIT, PRODUCE_TOPIC, etc.
  - graphId               the service identifier
  - buildMeta             version, env, commitSha

❌ NEVER SENT (business / sensitive data)
  - Source code
  - Method arguments or return values
  - Database query text or results
  - Message/event payloads (Kafka, SQS, RabbitMQ)
  - HTTP request/response bodies
  - Redis key values
  - Any user data or PII
```

The nodeId IS derived from source code (class name + method name) — but it is **structural metadata**, equivalent to a stack trace. Every APM tool in existence already sends this.

---

### 25.2 How Datadog Does It — Flow Is No Different

When a team uses Datadog, they send to Datadog's servers:

```
- Class names and method names (in stack traces and spans)
- Service names and endpoint paths
- Database query structures (table names, operation types)
- Kafka topic names
- Environment names, hostnames, PIDs
- Error messages and exception types
```

This is **exactly the same set of structural metadata** that Flow sends. Not more.

```
Datadog takes structural metadata → shows LATENCY GRAPHS and FLAME GRAPHS
Flow takes structural metadata    → shows ARCHITECTURE GRAPH, live

Same data leaving the JVM. Different visualisation on the other side.
```

Datadog customers accept this trade-off every day without hesitation. Flow is no more invasive. It is arguably **less** invasive — we explicitly do NOT capture arguments, payloads, or query values that many Datadog configurations do capture.

---

### 25.3 The One Real Difference — Perception vs Reality

In Datadog, a method name in a stack trace is **incidental** — buried in a flame graph, looked at briefly during an incident.

In Flow, the method name is the **primary UI element** — it sits front and centre on the architecture graph permanently, readable by anyone with access to the dashboard.

```
TECHNICAL REALITY:   Same data leaving the JVM as any APM tool
CUSTOMER PERCEPTION: "My architecture is explicitly visible and readable"
```

The sensitivity is **psychological, not technical**. But perception matters commercially. We must address it head-on.

---

### 25.4 Deployment Topology — Three Tiers

The answer to data sensitivity concerns is **deployment flexibility**, not data reduction.

```
┌──────────────────────────────────────────────────────────────────────┐
│ TIER 1 — SaaS (hosted by Flow)                                        │
│                                                                        │
│  Customer JVM ──▶ flow-agent ──▶ Flow's FCS (cloud) ──▶ Flow UI      │
│                                                                        │
│  Target:    Solo devs, startups, low IP-sensitivity teams             │
│  Data:      Structural metadata sent to Flow's cloud                  │
│  Trust:     Same model as using Datadog, GitHub, or Sentry            │
│  Setup:     Zero infrastructure. Just -javaagent flag.                │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│ TIER 2 — Self-Hosted (on-premise / customer VPC)                      │
│                                                                        │
│  Customer JVM ──▶ flow-agent ──▶ Customer's FCS ──▶ Flow UI          │
│                   (same JAR)     (Docker/Helm)    (browser → internal)│
│                                                                        │
│  Target:    Enterprise, regulated industries (finance, health, govt)  │
│  Data:      Nothing ever leaves the customer's network                │
│  Trust:     Flow (the company) never sees any customer data           │
│  Setup:     Customer runs FCS as a Docker container / Helm chart      │
│  Revenue:   Flow earns via license fee, not data hosting              │
└──────────────────────────────────────────────────────────────────────┘
```

The **agent JAR is identical** in both tiers. Only the `flow.server.url` config changes:

```bash
# SaaS
-Dflow.server.url=https://app.flowplatform.io

# Self-hosted
-Dflow.server.url=https://flow.internal.acme.com
```

---

### 25.5 Direction of All Communication — Always Client → FCS

This is a hard architectural rule, not a preference:

```
✅ ALWAYS (client initiates)
  Customer JVM  →  POST /ingest/runtime  →  FCS   (send events)
  Customer JVM  →  GET  /manifest        →  FCS   (pull flow.json)
  Browser       →  GET  /graph/:id       →  FCS   (fetch graph for UI)

❌ NEVER (FCS initiates)
  FCS  →  Customer JVM   (impossible — JVM is behind firewall/VPC)
  FCS  →  Browser        (browser opens WebSocket to FCS if needed — still client-initiated)
```

Why this is non-negotiable:
- Customer JVMs sit behind **firewalls and private networks** — FCS cannot reach them
- Inbound connections require **security approvals** that would kill enterprise adoption
- If FCS pushed to clients, a FCS outage would directly affect customer apps — violates the golden rule

This is how every successful agent-based SaaS works. Datadog, New Relic, Elastic APM — all outbound only from the agent.

---

### 25.6 What Flow Must Communicate to Customers

For the SaaS tier, Flow's Terms of Service and documentation must clearly state:

| Point | Statement |
|-------|-----------|
| **What is collected** | Structural metadata only — class names, method names, trace IDs, timestamps |
| **What is never collected** | Arguments, return values, payloads, query text, user data, PII |
| **Equivalence to APM** | The same class of data collected by Datadog, New Relic, Elastic APM |
| **Data ownership** | Customer owns their data. Flow does not sell or share it. |
| **Data residency** | EU and US hosting regions available |
| **Retention** | Configurable retention period. Data deleted on account closure. |
| **Self-hosted option** | Available for customers who cannot send any data outside their network |

The key sales message:

> *"Flow collects the same structural metadata as any APM tool you already use.
> The difference is what we do with it — instead of latency dashboards,
> we show you your architecture, live. If you use Datadog today, you are
> already comfortable with this data leaving your network."*

---

## 26. Checkpoint SDK — Visual Debugging on the Graph

### 26.1 What Already Exists in the Core Engine

The core engine (`flow-engine`) already has full checkpoint support — it is **already implemented**:

```java
// RuntimeEvent model — already in FCS
class RuntimeEvent {
    String type;                    // "CHECKPOINT"
    String nodeId;                  // which graph node this checkpoint is on
    String traceId;
    Map<String, Object> data;       // { "cart_total": 250, "discount": "APPLIED20" }
}
```

```java
// MergeEngine — already handles CHECKPOINT events
// Step 4 — Apply Checkpoints:
node.data.checkpoints.{key} = value
```

```java
// RuntimeFlow — UI already receives this
{
  "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
  "checkpoints": {
    "cart_total": 250,
    "discount": "APPLIED20",
    "user_tier": "GOLD"
  }
}
```

The **full pipeline** from CHECKPOINT event → MergeEngine → UI data is already there.
**The only missing piece is the emitter on the agent side.**

---

### 26.2 The Two Scenarios for Checkpoint Emission

#### Scenario 1 — Developer Places an Explicit Checkpoint (Recommended)

The developer adds a single call anywhere in their code:

```java
import com.flow.sdk.Flow;

public String placeOrder(String orderId) {
    Cart cart = cartService.getCart(orderId);

    Flow.checkpoint("cart_total", cart.getTotal());       // ← developer places this
    Flow.checkpoint("item_count", cart.getItems().size()); // ← and this
    Flow.checkpoint("user_tier", user.getTier());          // ← and this

    // rest of method...
}
```

The agent intercepts `Flow.checkpoint()` via ByteBuddy and emits:

```json
{
  "type": "CHECKPOINT",
  "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
  "traceId": "req-abc-123",
  "spanId": "span-7",
  "data": {
    "cart_total": 250.00,
    "item_count": 3,
    "user_tier": "GOLD"
  }
}
```

**Key properties:**
- Developer has **full control** — they choose exactly what to surface
- Developer owns the **key name** (human-readable, business-meaningful)
- Developer owns the **value** — they can compute, format, mask before passing to `Flow.checkpoint()`
- Zero risk of accidental PII — developer makes a conscious choice per value
- Works with any Java framework, any Java version

#### Scenario 2 — Auto-Capture of Method Arguments (Explicit Opt-In Per Method)

Without the developer writing any `Flow.checkpoint()` calls, the agent can optionally auto-capture method arguments — but **only for methods the developer explicitly whitelists in config**:

```yaml
flow:
  capture:
    method-args:
      - "com.greens.order.core.OrderService#placeOrder"      # capture args for this method
      - "com.greens.order.core.PaymentService#charge"        # and this one
      # ← everything else: no arg capture
```

At runtime, the agent captures the argument values for whitelisted methods and emits them as checkpoint data on `METHOD_ENTER`:

```json
{
  "type": "CHECKPOINT",
  "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
  "data": {
    "arg0_orderId": "order-456"    ← auto-named by param position
  }
}
```

**Key properties:**
- Never automatic — requires explicit developer opt-in per method
- Never global — cannot accidentally capture all arguments everywhere
- Developer sees exactly which methods are opted in (in config, in code review)

---

### 26.3 Scope Boundary — What Checkpoints Are For

```
✅ IN SCOPE
  Developer-placed checkpoints via Flow.checkpoint("key", value)
  → Developer decides what to surface, what to name it, what value to pass
  → The value can be a simple scalar, a formatted string, a masked value

✅ IN SCOPE (explicit opt-in only)
  Auto-capture of method arguments for whitelisted methods
  → Developer explicitly opts in per method in config
  → Visible in code review, in config, auditable

❌ OUT OF SCOPE
  Automatic capture of all method arguments everywhere
  → That is a debugger / profiler feature, not graph animation
  → Violates data privacy principles
  → Not our product objective

❌ OUT OF SCOPE
  Capture of return values automatically
  → Same reasoning — debugger territory

❌ OUT OF SCOPE
  Capture of local variable values
  → Requires bytecode-level instrumentation of variable table
  → Complex, fragile, debugger territory
```

---

### 26.4 The flow-sdk.jar — Design

The SDK is intentionally **minimal**. It is a single class with no dependencies:

```java
package com.flow.sdk;

/**
 * Flow Checkpoint SDK.
 * One class. No dependencies. Zero transitive impact on customer classpath.
 *
 * Usage: Flow.checkpoint("key", value)
 *
 * When the flow-agent is NOT attached: this method is a no-op (returns immediately).
 * When the flow-agent IS attached: ByteBuddy intercepts this call and emits a CHECKPOINT event.
 */
public final class Flow {

    private Flow() {}

    /**
     * Place a checkpoint at this point in execution.
     * The key and value will appear on the graph node in the Flow UI
     * during live trace animation.
     *
     * @param key   Human-readable label shown in the UI (e.g. "cart_total", "user_tier")
     * @param value The value to display. Primitives, Strings, and numbers recommended.
     *              Complex objects will be toString()'d. PII is the developer's responsibility.
     */
    public static void checkpoint(String key, Object value) {
        // No-op when agent is not attached.
        // The agent intercepts this method via ByteBuddy and emits the CHECKPOINT event.
        // This body intentionally empty.
    }
}
```

**Why this works:**
- When the agent is **not attached**: `Flow.checkpoint()` is a no-op — costs one empty method call, essentially free
- When the agent **is attached**: ByteBuddy intercepts `Flow.checkpoint()` at class-load time, installs advice that emits the CHECKPOINT event to the ring buffer
- The SDK JAR is `< 5KB` — no Jackson, no HTTP client, nothing
- Customer adds it as a `compile` dependency — no agent required to compile or run without Flow

**Maven dependency (what the customer adds):**

```xml
<dependency>
    <groupId>com.flow</groupId>
    <artifactId>flow-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

---

### 26.5 What the Graph Looks Like With Checkpoints

This is the visual debugging story — the entire value of checkpoints expressed in one picture:

```
POST /api/orders/{id}   [traceId: req-abc-123]
  │
  ├──▶ 🟢 OrderService#placeOrder(String)         [12ms]
  │         📌 cart_total   = 250.00
  │         📌 item_count   = 3
  │         📌 user_tier    = "GOLD"
  │         │
  │         ├──▶ 🟢 validateCart(String)           [3ms]
  │         │         📌 coupon_applied = true
  │         │         📌 discount_pct   = 20
  │         │
  │         ├──▶ 🟢 PaymentService#charge(String)  [8ms]
  │         │         📌 amount   = 200.00          ← after discount
  │         │         📌 currency = "USD"
  │         │         📌 gateway  = "stripe"
  │         │
  │         └──▶ 🟢 topic:orders.v1               [async]
  │                   📌 messageKey = "order-456"
  │
  └── 200 OK
```

**No log file. No grep. No Kibana query.**
You see the values **on the node** — exactly where the code was executing — in real time.

This is the difference between Flow and every other tool. Datadog shows you a latency spike at `PaymentService#charge`. Flow shows you: `amount=200.00, currency=USD, gateway=stripe` — right there, on the architecture node, live.

---

### 26.6 Data Privacy — Developer's Responsibility

When a developer calls `Flow.checkpoint("key", value)`, they are making a **conscious, explicit decision** to surface that value in the Flow UI.

Flow's responsibility:
- Transport the value securely (TLS)
- Never capture values the developer didn't explicitly pass
- Never log or inspect the values beyond storing them in the trace

Developer's responsibility:
- Do not pass PII (names, emails, SSNs, card numbers) unless intentional
- If passing sensitive data: mask or hash before passing
  ```java
  Flow.checkpoint("card_last4", card.getNumber().substring(12)); // ← developer masks
  Flow.checkpoint("user_id_hash", sha256(userId));               // ← developer hashes
  ```
- In self-hosted deployments: the data stays inside their network anyway

---

### 26.7 Module Location

```
flow-java-adapter/  (separate repo — already decided Q1)
  └── (agent repo)
        ├── flow-runtime-agent/     ← the javaagent JAR
        └── flow-sdk/               ← the tiny SDK JAR customers add as a dependency
              ├── pom.xml
              └── src/main/java/com/flow/sdk/
                    └── Flow.java   ← single class, no dependencies
```

The `flow-sdk` module is published to Maven Central independently. It has:
- Zero dependencies
- No agent code — just the empty `checkpoint()` method
- Stable API — once published, the method signature never changes
- Versioned independently from the agent (but same repo for convenience)

---

### 26.8 Agent-Side Interception of Flow.checkpoint()

Inside the agent, `Flow.checkpoint()` is treated as a special integration — similar to how Kafka or Redis are intercepted:

```java
// Inside IntegrationRegistry — treated as a built-in integration
new CheckpointInterceptor()

// CheckpointInterceptor installs advice on:
// com.flow.sdk.Flow#checkpoint(String, Object)

// Advice (conceptual):
@Advice.OnMethodEnter
static void onCheckpoint(
    @Advice.Argument(0) String key,
    @Advice.Argument(1) Object value) {

    String nodeId = FlowContext.currentNodeId();  // what method is currently executing
    String traceId = FlowContext.currentTraceId();
    String spanId = FlowContext.currentSpanId();

    FlowEventSink.emitCheckpoint(nodeId, traceId, spanId, key, value);
}
```

`FlowContext.currentNodeId()` returns the nodeId of the **currently executing method** — set on `METHOD_ENTER` by the main method advice. So the checkpoint is automatically associated with the right graph node without the developer needing to specify it.

---

## 27. OTel and Flow — Deep Analysis, Problems, and Hybrid Architecture

> **Version 0.5 — Revised March 7, 2026**  
> This section replaces the earlier naive "OTel-first" framing.
> After deep analysis of what Flow actually needs vs what OTel provides,
> the architecture is a **precise Hybrid** — not OTel-first, not ignore-OTel.

---

### 27.1 What OTel Was Built For vs What Flow Needs

To understand the boundary, we must first understand OTel's design philosophy:

```
OTel was designed for:
  → Distributed tracing across SERVICES (not inside services)
  → Observing BOUNDARY CALLS — HTTP in/out, DB, cache, messaging
  → Measuring latency, error rates, throughput
  → Sampling: capture a representative % of traces
  → Standard format (OTLP) for interop between APM tools

Flow needs:
  → Tracing INSIDE services — every method in the static graph
  → 100% coverage of graph nodes within sampled traces
  → Exact nodeId matching (FQCN + params + return type)
  → Checkpoint values attached to EXACT method nodes
  → Accurate async method-to-method edges (@Async, CompletableFuture)
  → Graph animation — the architecture, live, not latency dashboards
```

The goals are **fundamentally different**. OTel traces the boundaries. Flow traces the internals. Let's go through each requirement and stress-test OTel against it.

---

### 27.2 Problem 1 — Method-Level Granularity

**What Flow needs:**

`flow.json` has 200 method nodes for a typical Spring Boot service. When a request executes, maybe 40 of those methods are hit. Flow must light up **all 40** — every method in the call chain.

```
POST /api/orders/{id}
  └─▶ OrderController#createOrder         ← must light up
       └─▶ OrderService#placeOrder         ← must light up
            ├─▶ OrderService#validateCart   ← must light up
            │    └─▶ CartService#getItems   ← must light up
            ├─▶ PaymentService#charge       ← must light up
            └─▶ NotificationService#send    ← must light up
```

**What OTel does:**

OTel creates spans for **boundaries** — HTTP entry, DB call, Kafka produce, HTTP out. It does NOT create spans for every internal method call:

```
OTel's spans for the same request:
  span: HTTP POST /api/orders/{id}        ← yes
  span: SELECT * FROM orders              ← yes (JDBC auto-instrumentation)
  span: Redis GET order:123               ← yes (Lettuce auto-instrumentation)
  span: Kafka PRODUCE orders.v1           ← yes (Kafka auto-instrumentation)

  OrderController#createOrder             ← NO SPAN
  OrderService#placeOrder                 ← NO SPAN
  OrderService#validateCart               ← NO SPAN
  CartService#getItems                    ← NO SPAN
  PaymentService#charge                   ← NO SPAN
  NotificationService#send                ← NO SPAN
```

**6 out of 6 method nodes in the graph have NO OTel span.** The graph is dark.

OTel's `@WithSpan` annotation can manually create spans for individual methods — but that requires the **customer to annotate every method** they want traced. That violates our core requirement: **zero code changes.**

**Verdict: OTel CANNOT provide method-level graph animation. Flow MUST build its own method instrumentation.**

---

### 27.3 Problem 2 — Checkpoint Variable Accuracy

**What Flow needs:**

When a developer calls `Flow.checkpoint("cart_total", 250)` inside `OrderService#placeOrder`, the checkpoint value must attach to the `placeOrder` graph node — not to the HTTP endpoint, not to the service, but to the **exact method**.

```java
// Inside OrderService#placeOrder
Cart cart = cartService.getCart(orderId);
Flow.checkpoint("cart_total", cart.getTotal());  // ← must attach to placeOrder node
```

**What OTel does:**

`Span.current()` returns the "current active span" — which in OTel's model is the **innermost boundary span**, not the method span (because method spans don't exist):

```
Execution stack:
  HTTP span: POST /api/orders/{id}         ← THIS is Span.current()
    └─▶ OrderService#placeOrder()          ← no OTel span
         └─▶ Flow.checkpoint("cart_total", 250)
              └─▶ Span.current().setAttribute(...)
                   └─▶ attaches to HTTP span ❌ NOT to placeOrder

Result in UI:
  endpoint:POST /api/orders/{id}  📌 cart_total = 250   ← WRONG NODE
  OrderService#placeOrder         (no checkpoint)        ← WHERE IT SHOULD BE
```

If a DB call happens inside `placeOrder`, there IS an OTel span for the DB call. So after the DB call, `Span.current()` briefly points to the DB span, then returns to the HTTP span. Checkpoints would land on random boundary spans depending on timing.

**Verdict: OTel CANNOT reliably associate checkpoints with the correct method node. Flow MUST maintain its own FlowContext with currentNodeId().**

---

### 27.4 Problem 3 — nodeId Accuracy (Overloaded Methods)

**What flow.json contains:**

```
com.greens.order.core.OrderService#placeOrder(String):String
com.greens.order.core.OrderService#placeOrder(String, User):String
```

Two overloads — different parameter signatures.

**What OTel captures:**

```
code.namespace = "com.greens.order.core.OrderService"
code.function  = "placeOrder"
```

No parameter types. No return type. Both overloads map to the same OTel span name. **Indistinguishable.** The wrong graph node lights up 50% of the time.

Even with manifest lookup — if you search for `OrderService#placeOrder` in the manifest and find 2 matches, you cannot determine which overload was called from the OTel span data alone.

**Verdict: OTel span names are insufficient for exact nodeId matching. Flow MUST build its own nodeId from ByteBuddy's Method descriptor (which has full param types and return types).**

---

### 27.5 Problem 4 — Async Method-to-Method Edges

**What Flow needs:**

When `OrderService#placeOrder` calls an `@Async` method `EmailService#sendConfirmation`, the graph must show:

```
OrderService#placeOrder ──async──▶ EmailService#sendConfirmation
```

A precise method-to-method edge, not a thread-to-thread or executor-to-executor edge.

**What OTel does:**

OTel propagates trace context across threads via executor instrumentation. It creates a new span for the async task — but the span name is determined by what the async method does (e.g., if it makes a DB call, the DB span is created, not a method span). There is **no span for `sendConfirmation` itself** unless it's manually annotated with `@WithSpan`.

Even with context propagation, the parent-child relationship in OTel is:
```
HTTP span (parent) → DB span in async thread (child)
```
Not:
```
placeOrder (parent) → sendConfirmation (child)
```

**Verdict: OTel's async propagation preserves trace identity but loses method-to-method edges. Flow MUST track its own FlowContext.spanStack with method-level granularity.**

---

### 27.6 Problem 5 — 100% Coverage of Sampled Traces

**What Flow needs:**

For every trace that is selected for sampling, **every method node in flow.json** that executes within that trace must emit an event. Dropping even one method makes the graph path incomplete — a gap in the animation.

```
Sampled trace must produce:
  ENTER OrderController#createOrder
  ENTER OrderService#placeOrder
  ENTER OrderService#validateCart       ← if THIS is missing, the graph
  EXIT  OrderService#validateCart          has a broken path
  ENTER PaymentService#charge
  EXIT  PaymentService#charge
  EXIT  OrderService#placeOrder
  EXIT  OrderController#createOrder
```

**What OTel does:**

OTel's default sampling applies to **spans it creates** — boundary spans. Even at 100% sampling rate, OTel only creates boundary spans. Internal methods don't exist in OTel's world, so they are "100% missing" regardless of sampling config.

**Verdict: Sampling is not the issue — OTel's span granularity is. Even with zero sampling (capture everything), internal methods are invisible.**

---

### 27.7 Problem Summary — Where OTel Breaks for Flow

| Flow Requirement | Does OTel Handle It? | Why |
|---|---|---|
| Light up every method node in the graph | ❌ **No** | OTel only creates boundary spans (HTTP, DB, Kafka), not method spans |
| Full nodeId with parameter + return types | ❌ **No** | OTel captures `code.namespace` + `code.function` only — no params, no return type |
| Checkpoint attaches to exact method node | ❌ **No** | `Span.current()` returns boundary span, not method span |
| Async method-to-method edges | ❌ **No** | OTel propagates trace context but has no method-level spans to connect |
| 100% coverage of nodes within a trace | ❌ **No** | Internal methods are never instrumented by OTel |
| DB call as a node in the graph | ✅ **Yes** | OTel creates JDBC spans with `db.system`, `db.sql.table` |
| Redis call as a node in the graph | ✅ **Yes** | OTel creates Redis spans with `db.operation` |
| Kafka produce/consume as graph edge | ✅ **Yes** | OTel creates messaging spans with `messaging.destination` |
| HTTP outbound as graph edge | ✅ **Yes** | OTel creates client spans with `http.url` |
| gRPC call as graph node | ✅ **Yes** | OTel creates RPC spans with `rpc.service`, `rpc.method` |
| Cross-service trace stitching | ✅ **Yes** | W3C `traceparent` header propagation |
| Elasticsearch / MongoDB calls | ✅ **Yes** | OTel creates spans with `db.system`, `db.operation` |

**The pattern is clear:**

```
Internal code (method calls, checkpoints, async edges)  → Flow MUST own this
External systems (DB, cache, queue, HTTP, gRPC)          → OTel already solved this
Cross-service propagation                                → W3C traceparent standard
```

---

### 27.8 The Hybrid Architecture — Precise Separation

```
┌─────────────────────────────────────────────────────────────────────┐
│                      CUSTOMER'S JVM                                  │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  LAYER A — Flow's Own ByteBuddy  (method-level)              │  │
│  │                                                               │  │
│  │  What it instruments:                                        │  │
│  │    → Every method in flow.json (customer code only)          │  │
│  │    → METHOD_ENTER / METHOD_EXIT events                       │  │
│  │    → Full nodeId: FQCN + params + return type                │  │
│  │    → FlowContext: currentNodeId() for exact checkpoint       │  │
│  │    → spanStack: method-to-method parent-child edges          │  │
│  │    → @Async / CompletableFuture: wraps executors for         │  │
│  │      method-level context propagation                        │  │
│  │                                                               │  │
│  │  What it does NOT do:                                        │  │
│  │    ✗ DB calls, Redis, Kafka, HTTP, gRPC instrumentation     │  │
│  │    ✗ Library/framework bytecode transformation              │  │
│  │    ✗ Cross-service header propagation                       │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
│                              │ Flow method events                    │
│                              │                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  LAYER B — OTel SDK / OTel Agent  (external systems)         │  │
│  │                                                               │  │
│  │  What it instruments:                                        │  │
│  │    → JDBC / Hibernate / Spring Data JPA                     │  │
│  │    → Redis (Lettuce, Jedis)                                  │  │
│  │    → Kafka, RabbitMQ, SQS, SNS                              │  │
│  │    → HTTP outbound (RestTemplate, WebClient, Feign, OkHttp) │  │
│  │    → gRPC client/server                                      │  │
│  │    → Elasticsearch, MongoDB                                  │  │
│  │    → S3, DynamoDB                                            │  │
│  │    → W3C traceparent cross-service propagation              │  │
│  │                                                               │  │
│  │  What it does NOT do:                                        │  │
│  │    ✗ Customer method instrumentation                        │  │
│  │    ✗ Flow nodeId construction                               │  │
│  │    ✗ Checkpoint handling                                    │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
│                              │ OTel spans (boundary events)          │
│                              │                                       │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  UNIFIED EVENT PIPELINE                                       │  │
│  │                                                               │  │
│  │  Merges:                                                     │  │
│  │    Flow method events (from Layer A)                         │  │
│  │    + OTel boundary spans (from Layer B, bridged)             │  │
│  │    + Checkpoint data (from Flow.checkpoint())                │  │
│  │    → single batched stream → FCS                            │  │
│  │                                                               │  │
│  │  OTel span bridge:                                           │  │
│  │    FlowSpanProcessor reads OTel spans                       │  │
│  │    → maps OTel semantic attributes to Flow nodeId format    │  │
│  │    → converts to Flow RuntimeEvent                          │  │
│  │    → feeds into same pipeline as method events              │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
│                              │                                       │
│                              ▼ Flow-format batch (POST /ingest)      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 27.9 What Flow Builds vs What OTel Provides — Final

```
FLOW BUILDS (cannot delegate — Flow's core IP):

  1. Flow ByteBuddy Method Instrumentation
     → Instruments every method in flow.json (customer packages only)
     → Full nodeId builder: FQCN + params + return type + generics
     → CGLIB proxy resolution
     → Bridge method / lambda filtering
     → Narrow scope: only flow.json whitelist (or package filter)

  2. FlowContext
     → ThreadLocal trace context with method-level span stack
     → currentNodeId() — always tracks which method is executing
     → @Async / executor wrapping for method-to-method edge preservation
     → Checkpoint association: Flow.checkpoint() → currentNodeId()

  3. Checkpoint SDK (flow-sdk.jar)
     → Flow.checkpoint("key", value) → attaches to FlowContext.currentNodeId()
     → NOT using Span.current() (which is wrong in OTel's model)
     → Uses FlowContext directly — always accurate

  4. Flow Event Pipeline
     → Ring buffer / batch assembler
     → HTTP sender to FCS with circuit breaker
     → Merges Flow method events + bridged OTel boundary spans

  5. OTel Span Bridge (thin — inside the agent)
     → Registers as OTel SpanProcessor (or reads via SpanExporter)
     → For each OTel boundary span: reads semantic attributes
     → Maps to Flow nodeId format (db:*, cache:*, topic:*, grpc:*)
     → Converts to Flow RuntimeEvent → feeds into unified pipeline

OTEL PROVIDES (leverage — don't rebuild):

  6. All external system instrumentation
     → JDBC, Redis, Kafka, RabbitMQ, SQS, Elasticsearch, MongoDB,
       S3, DynamoDB, gRPC, HTTP clients
     → OTel auto-instrumentation handles all of these
     → Battle-tested, maintained by OTel community
     → Produces standard spans with semantic attributes

  7. W3C traceparent propagation
     → Cross-service trace ID stitching via HTTP/Kafka headers
     → Flow agent reads/writes the same W3C headers
     → Zero conflict, zero duplication

  8. Reactive / Virtual Thread support
     → OTel handles context propagation for WebFlux, Reactor, virtual threads
     → For the external system layer, this is already solved
     → For Flow's method-level layer, FlowContext handles its own propagation
```

---

### 27.10 How the Two Layers Coexist Without Conflict

**The key question:** Can Flow's ByteBuddy and OTel's ByteBuddy run on the same JVM without conflicting?

**Answer: Yes — because they instrument DIFFERENT classes.**

```
Flow's ByteBuddy transforms:
  com.greens.order.core.OrderService             ← customer code
  com.greens.order.core.PaymentService           ← customer code
  com.greens.order.core.CartService              ← customer code
  (narrow: only classes in flow.json / package filter)

OTel's ByteBuddy transforms:
  org.apache.kafka.clients.producer.KafkaProducer  ← library code
  io.lettuce.core.RedisAsyncCommandsImpl           ← library code
  java.sql.Connection                              ← JDK code
  org.springframework.web.client.RestTemplate       ← framework code
  (broad: all known libraries and frameworks)
```

**Zero overlap.** Flow's transformer only touches customer package classes. OTel's transformer only touches library/framework classes. ByteBuddy supports multiple transformers running simultaneously — this is its designed use case.

**For customers who already have OTel agent:**
```
-javaagent:opentelemetry-javaagent.jar     ← handles external systems
-javaagent:flow-agent.jar                  ← handles method-level

Both run side by side. No conflict.
Flow agent detects OTel presence → disables its own OTel bootstrap
                                → uses existing OTel context for traceId
                                → bridges OTel spans via SpanProcessor
```

**For customers who have NO OTel:**
```
-javaagent:flow-agent.jar
  → bootstraps OTel SDK internally (embedded)
  → activates OTel auto-instrumentation for external systems
  → runs Flow method instrumentation for customer code
  → single agent JAR does everything
```

---

### 27.11 The Trace Context Bridge — How Flow Reads OTel's traceId

Flow's `FlowContext` and OTel's `Context` must share the same `traceId` so that method events and boundary events belong to the same trace.

```
Request arrives: HTTP header traceparent: 00-abc123-span456-01

OTel extracts:
  OTel Context.traceId = "abc123"

Flow reads from OTel:
  FlowContext.traceId = Span.current().getSpanContext().getTraceId()
  → "abc123" — same traceId

Flow method event:
  { traceId: "abc123", nodeId: "OrderService#placeOrder(String):String", type: METHOD_ENTER }

OTel DB span (bridged):
  { traceId: "abc123", nodeId: "db:primary:query:orders", type: DB_QUERY }

Both arrive at FCS with the same traceId → MergeEngine links them in the same trace.
```

If NO incoming `traceparent` header: Flow generates a traceId, sets it on OTel's Context so OTel spans also carry the same ID. One trace, two instrumentation layers, unified.

---

### 27.12 Checkpoint — Why Flow's Own Context Is Mandatory

The checkpoint problem is the clearest proof that OTel-only cannot work for Flow:

```
Call stack during execution:

  [OTel span]  HTTP POST /api/orders/{id}          ← Span.current()
    └─▶ [Flow]  OrderService#placeOrder             ← FlowContext.currentNodeId()
         └─▶ [Flow]  validateCart
              └─▶ Flow.checkpoint("cart_total", 250)

If checkpoint uses OTel Span.current():
  → attaches to HTTP span
  → UI shows checkpoint on endpoint:POST /api/orders/{id}  ❌

If checkpoint uses FlowContext.currentNodeId():
  → attaches to "OrderService#validateCart(String):void"
  → UI shows checkpoint on the exact method  ✅
```

This is why Section 26.8 (agent-side interception of `Flow.checkpoint()`) uses `FlowContext.currentNodeId()` — not `Span.current()`. The entire Flow checkpoint feature **requires Flow's own context tracking**. It cannot be delegated to OTel.

---

### 27.13 Revised Phased Delivery Plan (Hybrid)

```
Phase 1 — Flow Method Engine (MVP)
────────────────────────────────────
Build:
  - Flow ByteBuddy transformer (customer packages only)
  - Method ENTER/EXIT advice with full nodeId
  - FlowContext (ThreadLocal + spanStack)
  - Ring buffer + batch assembler
  - HTTP sender to FCS with circuit breaker
  - Lazy nodeId builder (getGenericReturnType)

Skip for now:
  - OTel integration
  - External system spans
  - Checkpoint SDK

Validate: greens-order → method nodes light up on graph


Phase 2 — OTel Bridge for External Systems
────────────────────────────────────────────
Build:
  - OTel SDK embedded in Flow agent (for non-OTel customers)
  - FlowSpanProcessor — reads OTel boundary spans
  - OTel semantic attributes → Flow nodeId mapper
  - Unified pipeline: method events + bridged spans → FCS
  - OTel presence detection (if customer already has OTel agent)

Validate: greens-order → DB, Redis, Kafka nodes appear alongside method nodes


Phase 3 — Checkpoint SDK
─────────────────────────
Build:
  - flow-sdk.jar (Flow.checkpoint("key", value))
  - Checkpoint interceptor using FlowContext.currentNodeId()
  - FCS checkpoint-to-node association

Validate: developer adds Flow.checkpoint() → value appears on exact method node


Phase 4 — Cross-Service + Async
─────────────────────────────────
Build:
  - W3C traceparent read/write (or delegate to OTel layer)
  - @Async executor wrapping for method-to-method edge
  - CompletableFuture context propagation
  - ASYNC_HOP edge stitching in FCS (Kafka, SQS, RabbitMQ)

Validate: multi-service test → unified graph across services


Phase 5 — Production Hardening
────────────────────────────────
  - Manifest sync (async background pull)
  - Adaptive sampling (within sampled traces: 100% method coverage)
  - GZIP compression, TLS, API key auth
  - Performance benchmarks (< 3% overhead proven)
  - Memory leak testing
  - OTel Collector compatibility (FCS accepts OTLP for external spans directly)


Phase 6 — Advanced
───────────────────
  - Virtual thread support (Java 21 ScopedValue)
  - Reactive WebFlux support
  - Dynamic attach
  - Remote config from FCS
```

---

### 27.14 What We Now Build vs Original Plan — Impact

| Component | Original Plan (Full Custom) | Hybrid Plan | Savings |
|---|---|---|---|
| **Method instrumentation** | Build from scratch | Build from scratch | Same |
| **FlowContext** | Build from scratch | Build from scratch | Same |
| **Checkpoint SDK** | Build from scratch | Build from scratch | Same |
| **JDBC instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **Redis instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **Kafka instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **RabbitMQ instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **Elasticsearch instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **MongoDB instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **gRPC instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **HTTP client instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **S3/SQS/SNS instrumentation** | Build from scratch | OTel provides → bridge | **Saved** |
| **Cross-service propagation** | Build W3C from scratch | OTel provides (W3C standard) | **Saved** |
| **OTel span bridge** | N/A | Build (thin mapper) | **New — but small** |
| **Async context propagation** | Build from scratch | Flow method-level: build. External: OTel provides. | **Partially saved** |

**Net savings: ~60% of the original custom agent work is eliminated.** The remaining 40% (method engine, FlowContext, checkpoint) is Flow's core IP that cannot be delegated and must be built regardless.

---

### 27.15 Summary — The Precise Hybrid Rule

```
IF it's about methods inside customer code:
  → Flow owns it. Flow's ByteBuddy. FlowContext. Full nodeId.
  → This is Flow's entire value proposition.
  → Cannot be delegated to OTel.

IF it's about external system calls (DB, cache, queue, HTTP, gRPC):
  → OTel owns it. OTel's auto-instrumentation. OTel semantic conventions.
  → Flow bridges OTel spans into its pipeline via FlowSpanProcessor.
  → Flow does NOT rebuild these interceptors.

IF it's about cross-service trace identity:
  → W3C traceparent standard. OTel propagates it.
  → Flow reads/writes the same header format.
  → One traceId across both layers.
```

> **Flow is not OTel-first. Flow is not OTel-free.**
> **Flow is OTel-for-boundaries, Flow-for-internals.**
> **Each tool does exactly what it was designed for.**

---

## 28. Build Readiness Assessment — Are We Ready?

> **Verdict: Almost. The design is strong. 10 specific gaps must be closed before Phase 1 ships.**

---

### 28.1 What IS Solid — Ready to Build

| Area | Status | Evidence |
|---|---|---|
| **Product scope** | ✅ Rock solid | Section 0 — two objectives, litmus test, explicit out-of-scope list |
| **Architecture** | ✅ Decided | Section 27 — Hybrid model stress-tested against 5 real problems |
| **All 15 design questions** | ✅ Resolved | Section 22 + 22-A — every question has decision + rationale |
| **FCS Runtime Ingestor** | ✅ Already built | `POST /ingest/runtime` — accepts events, buffers by traceId |
| **MergeEngine** | ✅ Already built | Merges static + runtime graphs, computes durations, stitches async hops |
| **RuntimeFlowExtractor** | ✅ Already built | Generates ordered execution steps for UI animation |
| **Checkpoint handling (FCS)** | ✅ Already built | CHECKPOINT events → `node.data.checkpoints` → UI shows values on nodes |
| **Static scanner** | ✅ Already built | `flow-java-adapter` → `flow.json` with full nodeId format |
| **nodeId format** | ✅ Defined | `{FQCN}#{method}({params}):{returnType}` — clear, documented |
| **Data sensitivity model** | ✅ Documented | Section 25 — structural metadata only, deployment tiers |
| **Golden rule** | ✅ Documented | Agent NEVER affects customer app — drop, never block |
| **Checkpoint SDK design** | ✅ Documented | Section 26 — `flow-sdk.jar`, single class, zero deps, `FlowContext.currentNodeId()` |
| **Phase plan** | ✅ Documented | Section 21 + 27.13 — 6 phases, clear scope per phase |

---

### 28.2 What Is NOT Yet Resolved — 10 Gaps

---

#### Gap 1 — FCS Batch Ingestion Endpoint
**Problem:** The agent sends batches of events across **multiple traces** in a single HTTP request (Section 9.2 payload format). The current FCS `POST /ingest/runtime` accepts events for a **single trace** (requires `traceId` at top level).

```
Agent sends:
  { "graphId": "payment-service", "batch": [
      { traceId: "req-1", ... },
      { traceId: "req-2", ... },    ← different trace
      { traceId: "req-1", ... },
  ]}

FCS current API:
  { "graphId": "...", "traceId": "req-1", "events": [...] }   ← single trace only
```

**Resolution needed:** Either FCS adds a `POST /ingest/batch` endpoint that accepts mixed-trace arrays, or the agent groups events by traceId before sending (adds complexity + latency to the agent).

**Recommendation:** FCS adds batch endpoint — simpler, keeps the agent dumb.

---

#### Gap 2 — FCS API Authentication
**Problem:** Agent sends `Authorization: Bearer <api-key>`. FCS resolves tenant from API key (decided in Q9). But the FCS auth mechanism — key format, validation, tenant resolution, rate limiting per key — is not designed.

```
Agent config:
  flow.api-key=fk_live_abc123

Agent sends:
  POST /ingest/batch
  Authorization: Bearer fk_live_abc123

FCS must:
  api_key → tenant_id → validate graphId belongs to tenant → accept or reject
```

**Recommendation:** Design a simple API key table in FCS. `api_keys(key, tenant_id, created_at, active)`. MVP: no rate limiting, just key validation.

---

#### Gap 3 — FCS Backpressure Under Load
**Problem:** 100 agents × 1000 events/sec = 100,000 events/sec hitting FCS. The RuntimeTraceBuffer is in-memory with a HashMap. Under burst load this can OOM FCS.

**What's not designed:**
- FCS ingestion queue (in-memory bounded queue? Redis? Kafka?)
- What happens when the queue is full — drop events? backpressure HTTP 429?
- How MergeEngine processes events — synchronous per request? async worker pool?

**Recommendation for MVP:** Bounded in-memory queue (e.g., `LinkedBlockingQueue(50_000)`). Drop oldest events if full. Return HTTP 200 regardless (agent doesn't need to know). Log warning on drop. Revisit with Kafka/Redis for production hardening (Phase 5).

---

#### Gap 4 — Event Storage / Trace Replay
**Problem:** RuntimeTraceBuffer has 5-minute TTL. After that, events are gone. If a user clicks on a trace in the UI 10 minutes later — nothing to show.

**What's not designed:**
- Persist merged traces? Where? PostgreSQL? Elasticsearch?
- What retention? 1 hour? 1 day? 7 days?
- How does the UI distinguish "live" traces vs "historical" traces?

**Recommendation for MVP:** Don't persist. 5-minute in-memory buffer is fine for Phase 1 "live" animation. Document the limitation. Design persistence as part of Phase 5 (production hardening).

---

#### Gap 5 — nodeId Contract Integration Test
**Problem:** The scanner and agent MUST produce **identical** nodeId strings for the same method. A single difference — `java.lang.String` vs `String`, missing space after comma in params, different generics formatting — breaks the merge completely. The graph node stays dark.

**What's not built:**
- No test that verifies scanner output matches what the agent would produce at runtime
- The nodeId builder exists in the scanner (`flow-adapter`) but the agent's nodeId builder doesn't exist yet

**Recommendation:** Write this test FIRST — before any agent code. It defines the contract both sides must honor:

```java
@Test
void scannerAndAgentProduceIdenticalNodeIds() {
    // 1. Scan greens-order → flow.json → extract all nodeIds
    Set<String> scannerNodeIds = scanFlowJson("greens-order");

    // 2. Load greens-order classes via reflection
    // 3. For each class/method, build nodeId using the same algorithm
    //    the agent will use (getGenericReturnType, simplify types, etc.)
    Set<String> agentNodeIds = buildRuntimeNodeIds("com.greens.order");

    // 4. Must match exactly
    assertEquals(scannerNodeIds, agentNodeIds);
}
```

This test is the **single most important test in the entire project**. If it fails, nothing works.

---

#### Gap 6 — Sampling Decision Point
**Problem:** "100% within sampled traces" is stated but the decision mechanism is not specified.

**Questions:**
- Where is the sampling decision made? First METHOD_ENTER? HTTP filter? Kafka consumer entry?
- Who decides? Flow agent only? Or does it respect OTel's sampling decision?
- How does the decision propagate? Set `FlowContext.sampled = true` once at trace start?
- If `sampled = false`, does the agent skip all instrumentation for this trace? Or still instrument but don't emit events?

**Recommendation for Phase 1 MVP:** 100% sampling. Every trace is captured. No sampling logic. The sampling config exists (`flow.sampling.rate=1.0`) but is always 1.0 in Phase 1. Implement adaptive sampling in Phase 5.

---

#### Gap 7 — Live UI Push Mechanism
**Problem:** The UI needs to animate the graph **in real time** — nodes lighting up as methods execute. But FCS currently only has REST endpoints. REST is pull-based. The UI would need to poll `GET /trace/{traceId}` every 100ms, which is terrible.

**What's not designed:**
- WebSocket connection from browser to FCS?
- Server-Sent Events (SSE) for one-way push?
- How does FCS know which traces the UI is interested in? (a user is viewing a specific graphId)

**Recommendation for Phase 1 MVP:** Simple polling with 1-second interval. Not ideal but works for demo. Design WebSocket/SSE push for Phase 2 alongside the OTel bridge work. The "live" part can tolerate 1-second latency for MVP.

---

#### Gap 8 — OTel Agent Coexistence Proof
**Problem:** Section 27.10 asserts "zero overlap" between Flow's ByteBuddy and OTel's ByteBuddy because they instrument different classes. This is architecturally correct but **has not been tested**. ByteBuddy class file transformer ordering, class loader isolation, and JVM agent precedence can cause subtle issues.

**Recommendation:** Build a minimal proof-of-concept before Phase 2:
```
greens-order
  + OTel Java Agent (-javaagent:otel.jar)
  + Flow Agent (-javaagent:flow-agent.jar)
  → verify both agents load
  → verify OTel captures DB/Kafka spans
  → verify Flow captures method ENTER/EXIT
  → verify no ClassFormatError, no VerifyError, no duplicate transforms
```
Not needed for Phase 1 (which is Flow-only). Required before Phase 2.

---

#### Gap 9 — OTel Span Bridge API Contract
**Problem:** `FlowSpanProcessor` is described conceptually but the exact OTel interface to implement is not decided.

**Options:**
- `SpanProcessor` — receives span start + end callbacks in-process. Lower latency. Tighter coupling to OTel SDK version.
- `SpanExporter` — receives completed spans in batches. Simpler. Decoupled from OTel internals. But only sees completed spans (no ENTER event on start).

**Impact on Q2 decision:** We decided ENTER + EXIT separately (Q2). With `SpanExporter`, we only see completed spans = EXIT only. With `SpanProcessor`, we see both `onStart` (ENTER) and `onEnd` (EXIT).

**Recommendation:** `SpanProcessor` — because we need ENTER events for real-time graph animation (the decisive insight from Q2). Document this decision.

---

#### Gap 10 — Trace Context Bridge Specification
**Problem:** Section 27.11 describes Flow and OTel sharing a traceId but the exact mechanism is not specified.

**Two scenarios:**

```
Scenario A — Customer has OTel agent (OTel generates traceId):
  HTTP request arrives → OTel extracts traceparent → sets OTel Context
  Flow agent: FlowContext.traceId = Span.current().getSpanContext().getTraceId()
  → Flow READS from OTel

Scenario B — Customer has NO OTel (Flow generates traceId):
  HTTP request arrives → Flow detects no OTel context → generates traceId
  Flow sets on OTel Context: Context.current().with(Span.wrap(flowSpanContext))
  → Flow WRITES to OTel (so when OTel bridge activates in Phase 2, spans carry same ID)

Scenario C — No traceparent header at all:
  Flow generates traceId → sets FlowContext.traceId
  OTel separately generates its own traceId
  → TWO DIFFERENT traceIds for the same request ❌ MERGE BREAKS
```

**Recommendation:** For Phase 1 (no OTel): Flow generates traceId, stores in FlowContext. Simple.
For Phase 2 (with OTel): Flow READS from OTel Context if present, generates if not. Specify exact API call. Add integration test to verify both layers carry identical traceId.

---

### 28.3 Additional Gaps Found — Final Review

---

#### Gap 11 — ThreadLocal Cleanup on Thread Pool Reuse ⚠️ WILL BURN US
**Severity: HIGH — silent data corruption in production**

**Problem:** FlowContext uses ThreadLocal. Application servers (Tomcat, Jetty) and `@Async` executors **reuse threads**. If FlowContext is not cleaned up after a request completes, the NEXT request on the same thread inherits the old traceId, spanStack, and currentNodeId.

```
Thread-1: Request A → traceId="req-A" → FlowContext set
Thread-1: Request A completes → FlowContext NOT cleared ❌
Thread-1: Request B arrives → FlowContext still has traceId="req-A"
→ Request B's events are attributed to Request A's trace
→ Graph shows Request B's methods inside Request A's trace
→ Completely wrong animation
```

**This happens in every production server.** Tomcat has a thread pool of 200 threads. Every thread will eventually carry a stale FlowContext.

**Solution (straightforward — implement in Phase 1):**

```java
// Entry point advice (HTTP filter / @KafkaListener / @Scheduled):
@Advice.OnMethodEnter
static void onRequestEnter() {
    FlowContext.initNewTrace();  // generates new traceId, empty spanStack
}

@Advice.OnMethodExit(onThrowable = Throwable.class)
static void onRequestExit() {
    FlowContext.clear();  // removes ThreadLocal entirely — clean for next request
}
```

The `FlowContext.clear()` call is **mandatory** in every entry point exit advice. This is not optional. Without it, every production deployment will produce corrupted traces within minutes.

**Status: ✅ Solvable — add to Phase 1 as mandatory.**

---

#### Gap 12 — Recursive / Re-entrant Method Calls
**Severity: MEDIUM — incorrect durations and broken spanStack**

**Problem:** If a method in flow.json calls itself recursively, or if method A calls B calls A (mutual recursion), the spanStack logic must handle re-entrant calls correctly.

```
OrderService#processOrder(String)
  └─▶ OrderService#processOrder(String)     ← recursion
       └─▶ OrderService#processOrder(String) ← deeper
```

If the spanStack tracks by nodeId only, EXIT pops the wrong ENTER. Duration computation breaks.

**Solution (straightforward — each call gets a unique spanId):**

```
ENTER: push new spanId="span-1" for processOrder
  ENTER: push new spanId="span-2" for processOrder (same nodeId, different spanId)
    ENTER: push new spanId="span-3" for processOrder
    EXIT: pop span-3 → duration = exit3 - enter3 ✅
  EXIT: pop span-2 → duration = exit2 - enter2 ✅
EXIT: pop span-1 → duration = exit1 - enter1 ✅
```

The spanStack is a `Deque<SpanInfo>` (stack). ENTER pushes, EXIT pops. Each has a unique `spanId`. Pairing is by stack order, not by nodeId. This is already implied in Section 11.1 (`spanStack: Deque<SpanInfo>`) but not explicitly addressed for recursion.

**Status: ✅ Solvable — already handled by the stack design. Add explicit test case for recursion.**

---

#### Gap 13 — Agent Class Loading Interference ⚠️ WILL BURN US
**Severity: HIGH — can crash customer app at startup**

**Problem:** ByteBuddy transforms classes at load time. If the agent's transformer throws an exception or produces invalid bytecode for ANY class, that class fails to load. If that class is critical (e.g., a Spring `@Configuration` class), the entire application fails to start.

```
Customer deploys flow-agent.jar to production.
Agent's ByteBuddy advice has a bug in nodeId construction for a specific method signature.
Spring @Configuration class fails to load.
Application crashes at startup.
Customer is down. In production.
```

This is the **#1 risk of any javaagent**. Datadog, Elastic APM, New Relic have all had this happen.

**Solution (multi-layered — implement in Phase 1):**

```
Layer 1 — ByteBuddy safe mode:
  AgentBuilder.Default()
    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
    .with(new AgentBuilder.Listener.Adapter() {
        @Override
        public void onError(String name, ClassLoader loader, 
                           JavaModule module, boolean loaded, Throwable throwable) {
            // Log warning, DO NOT rethrow
            log.warn("[flow-agent] Failed to transform {}: {}", name, throwable.getMessage());
        }
    })
    .disableClassFormatChanges()  // safer transformations

Layer 2 — Individual class try-catch:
  Each class transformation wrapped in try-catch.
  If transformation fails → skip that class, log warning, continue.
  Customer app loads normally with that one class uninstrumented.

Layer 3 — kill switch:
  -Dflow.agent.enabled=false → agent premain() returns immediately
  -Dflow.agent.safe-mode=true → only instrument classes that are in the manifest
  If ANY class fails to transform → auto-disable agent for remaining classes
```

**Status: ✅ Solvable — implement all three layers in Phase 1. Non-negotiable.**

---

#### Gap 14 — High-Cardinality nodeIds from Spring Data / Repository Proxies
**Severity: MEDIUM — graph noise, FCS memory bloat**

**Problem:** Spring Data JPA creates dynamic proxy implementations for every `Repository` interface. At runtime the agent might see:

```
com.sun.proxy.$Proxy123#findById(Long):Optional
com.sun.proxy.$Proxy124#save(Object):Object
org.springframework.data.jpa.repository.support.SimpleJpaRepository#findById(Long):Optional
```

None of these match the flow.json nodeId `com.greens.order.repository.OrderRepository#findById(Long):Optional`. The proxy class names are generated at runtime and vary across JVM restarts.

**Solution:**

```
Spring Data proxy resolution (similar to CGLIB resolution):
  $Proxy123 → inspect interfaces
  → finds: OrderRepository extends JpaRepository
  → use "com.greens.order.repository.OrderRepository" as the nodeId prefix

Implementation:
  if (className.startsWith("com.sun.proxy.$Proxy") || className.contains("$$")) {
      // Check interfaces for customer-package match
      for (Class<?> iface : clazz.getInterfaces()) {
          if (matchesPackageFilter(iface.getName())) {
              return iface.getName();  // use the interface as the real class
          }
      }
  }
```

**Status: ✅ Solvable — add JDK proxy resolution alongside CGLIB resolution in Phase 1.**

---

#### Gap 15 — Agent Startup Ordering with Spring Boot ⚠️ NEEDS BRAINSTORM
**Severity: MEDIUM — missed events at app startup**

**Problem:** The javaagent `premain()` runs BEFORE `main()`. But the agent needs to send events to FCS. At `premain()` time:
- Network may not be ready
- DNS may not be resolved
- TLS certificates may not be loaded
- Spring hasn't started — no HTTP client configured

More critically: classes loaded DURING Spring startup (before the app is "ready") will be instrumented and will emit events. But the HTTP sender thread may not have successfully connected to FCS yet.

```
Timeline:
  premain()     → install ByteBuddy transformer ✅
  main()        → Spring starts loading classes
  class load    → agent transforms classes, installs advice ✅
  Spring init   → beans created, methods called
  events emitted → ring buffer fills ✅
  HTTP sender   → tries to connect to FCS → may fail (network not ready)
  → events in buffer dropped by circuit breaker
  → startup execution path is INVISIBLE on the graph
```

**Is this a real problem?**
- For steady-state production traffic: NO — by the time real requests arrive, the HTTP sender is connected.
- For startup visualization: YES — if someone wants to see which beans/methods execute during Spring startup, they won't see it.

**Recommendation for Phase 1:** Accept this limitation. The agent buffers events in the ring buffer. If FCS is reachable by the time the buffer flushes (200ms), startup events are captured. If not, they're dropped. This is the same trade-off every APM agent makes. Document it.

**Status: ✅ Acceptable for MVP — document the limitation.**

---

#### Gap 16 — Multiple Instances of Same Service (graphId collision)
**Severity: LOW for Phase 1, HIGH for production**

**Problem:** In production, a service runs multiple instances (e.g., 3 pods of `order-service`). All three send events with `graphId: "order-service"`. FCS receives events from all three simultaneously.

**Questions:**
- Does MergeEngine handle interleaved events from multiple instances for the same graphId?
- If pod-1 sends trace "req-A" and pod-2 sends trace "req-B", are they kept separate? (Yes — different traceIds.)
- If pod-1 is running version 1.2 and pod-2 is running version 1.3 (rolling deploy), the flow.json might be different. Which flow.json does MergeEngine use?

**The rolling deploy problem is the real issue:**
```
Pod 1: version 1.2 → flow.json v1.2 (has OrderService#placeOrder)
Pod 2: version 1.3 → flow.json v1.3 (has OrderService#placeOrder + OrderService#validateCoupon ← new method)

Pod 2 sends events for validateCoupon → MergeEngine uses flow.json v1.2 → nodeId not found → event dropped
```

**Solution:** `buildMeta` — each agent sends `{ version: "1.3", commitSha: "abc123" }` with every batch. FCS uses the matching flow.json version for merge. This is already in the design (Section 9.2 mentions `agentVersion`) but the FCS-side version-aware manifest lookup is not specified.

**Status: ⚠️ Needs FCS design — version-aware flow.json storage. Add to Phase 5.**

---

### 28.4 Updated Gap Summary

```
PHASE 1 — MUST resolve before writing agent code:
  □ Gap 5  — nodeId contract integration test (THE most important test)
  □ Gap 11 — ThreadLocal cleanup (WILL corrupt traces without it)
  □ Gap 13 — Class loading safety (WILL crash customer apps without it)

PHASE 1 — Build in parallel:
  □ Gap 1  — FCS batch ingestion endpoint
  □ Gap 2  — FCS API key authentication
  □ Gap 6  — Sampling: 100% for MVP
  □ Gap 7  — UI polling (1s interval for MVP)
  □ Gap 12 — Recursive method test case
  □ Gap 14 — JDK proxy resolution (alongside CGLIB)
  □ Gap 15 — Document startup event limitation

BEFORE Phase 2:
  □ Gap 3  — FCS backpressure (bounded queue)
  □ Gap 4  — Event storage (in-memory 5min TTL for MVP)
  □ Gap 8  — OTel agent coexistence proof
  □ Gap 9  — OTel SpanProcessor vs SpanExporter decision → SpanProcessor
  □ Gap 10 — Trace context bridge specification

PHASE 5 (production hardening):
  □ Gap 3  — Redis/Kafka-backed ingestion queue
  □ Gap 4  — Persistent trace storage + retention
  □ Gap 16 — Version-aware flow.json storage in FCS
```

---

### 28.5 Final Verdict

> **The design is ready. The architecture is sound. All hard questions are answered.**
>
> There are **3 non-negotiable items** before writing Phase 1 code:
>
> 1. **Gap 5 — nodeId contract test.** If scanner and agent nodeIds don't match, the entire product doesn't work. Write this first.
> 2. **Gap 11 — ThreadLocal cleanup.** Without it, every production deployment produces corrupted traces within minutes. Build into every entry point advice from day one.
> 3. **Gap 13 — Class loading safety.** Without it, the agent can crash customer apps. Multi-layered safety net must be in the agent from the first commit.
>
> **Everything else is solvable in parallel or deferrable.**
>
> Once these 3 are addressed → **Phase 1 is go.**














