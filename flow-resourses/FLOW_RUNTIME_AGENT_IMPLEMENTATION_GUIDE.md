# Flow Runtime Agent — Implementation Guide

**Version:** 1.0  
**Date:** March 7, 2026  
**Status:** Ready for Implementation  
**Target Repo:** `flow-runtime-agent` (new, separate repository)  
**Source:** Distilled from [RUNTIME_PLUGIN_DESIGN.md](./RUNTIME_PLUGIN_DESIGN.md) brainstorming sessions.

---

## How to Use This Document

This is the **build blueprint** for the `flow-runtime-agent` project. A developer (or AI agent) should be able to create the entire module from this guide alone. Every class, every interface, every Maven dependency is specified. Follow the sections in order.

**Reference documents (for deep rationale):**
- `RUNTIME_PLUGIN_DESIGN.md` — Full brainstorming, architecture decisions (Sections 0–28)
- `RUNTIME_ENGINE_DESIGN.md` — FCS runtime engine implementation (already built)
- `FLOW_ENGINE_API.md` — FCS REST API contract
- `API_DOCUMENTATION.md` — Full FCS API reference

---

## Table of Contents

1. [Mission & Scope](#1-mission--scope)
2. [Glossary](#2-glossary)
3. [Repository Structure](#3-repository-structure)
4. [Maven / Build Setup](#4-maven--build-setup)
5. [The nodeId Contract](#5-the-nodeid-contract)
6. [Configuration Model](#6-configuration-model)
7. [Phase 1 — Class-by-Class Implementation Guide](#7-phase-1--class-by-class-implementation-guide)
8. [FCS Integration Contract](#8-fcs-integration-contract)
9. [Safety Requirements — Non-Negotiable](#9-safety-requirements--non-negotiable)
10. [Testing Strategy](#10-testing-strategy)
11. [Phase 2–6 Implementation Notes](#11-phase-26-implementation-notes)
12. [Appendices](#12-appendices)

---

## 1. Mission & Scope

### 1.1 What This Repo Is

A **Java agent** (`-javaagent:flow-agent.jar`) that observes method executions inside a customer's JVM and sends lightweight events to Flow Core Service (FCS) so the static architecture graph can be animated in real time.

**One sentence:** *The bridge between a running Java application and the Flow architecture graph.*

### 1.2 What This Repo Is NOT

| ❌ NOT This | Why |
|---|---|
| An APM agent | We don't build latency dashboards, metrics, or alerts. |
| A distributed tracing tool | We propagate trace context only to stitch the graph across services. |
| A log aggregator | We don't capture logs. |
| A profiler | We don't build flame graphs or CPU profiles. |

### 1.3 Litmus Test for Every Feature

Before adding anything:

> *Does this feature make the ARCHITECTURE GRAPH more accurate, more complete, or more alive at runtime?*
> 
> **YES** → Consider it. **NO** → Drop it.

### 1.4 The Agent's One Job

```
Observe method executions and external system calls in the customer's JVM.
Emit the minimum data needed to animate the architecture graph.
Ship it to FCS reliably without affecting the customer app.
Nothing more.
```

### 1.5 The Golden Rule

> **The agent must NEVER cause the customer application to fail, slow down, or behave differently.**  
> If in doubt, DROP data rather than risk affecting the app.

---

## 2. Glossary

| Term | Definition |
|---|---|
| **nodeId** | Unique identifier for a node in the architecture graph. Format: `{FQCN}#{method}({paramTypes}):{returnType}`. The **most critical contract** in the system — scanner and agent must produce identical strings. |
| **graphId** | Identifier for the customer's project/service graph. Example: `"order-service"`. Configured by the customer. |
| **traceId** | UUID identifying a single request/execution. Shared across all events in one request. |
| **spanId** | UUID identifying a single method execution within a trace. Used for parent-child relationships. |
| **parentSpanId** | The `spanId` of the calling method. Creates the call tree. |
| **flow.json** | The static architecture graph produced by `flow-java-adapter`. Contains all nodeIds, edges, metadata. |
| **FCS** | Flow Core Service — the backend that receives events, merges with static graph, serves to UI. |
| **MergeEngine** | Component inside FCS that merges runtime events with the static graph. |
| **RuntimeEvent** | A single event emitted by the agent: `{ traceId, spanId, parentSpanId, nodeId, type, timestamp, durationMs, data }`. |
| **FlowContext** | ThreadLocal-based trace context maintained by the agent. Tracks current traceId, span stack, currentNodeId. |
| **OTel** | OpenTelemetry — used for external system instrumentation (DB, Redis, Kafka etc.). Flow bridges OTel spans into its own pipeline. |

---

## 3. Repository Structure

### 3.1 This Is a Separate Repository

The runtime agent lives in its **own repo** (`flow-runtime-agent`), separate from `flow-java-adapter`. Reasons:
- Independent release cycles — agent versions don't need to match scanner versions
- The only coupling is the **nodeId format** — a documented contract, not a code dependency
- Different deployment model — agent is a `-javaagent` JAR, scanner is a build tool

### 3.2 Module Layout

```
flow-runtime-agent/                          ← new repo root
├── pom.xml                                   ← parent POM
├── README.md
├── flow-agent/                               ← the -javaagent module
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/flow/agent/
│       │   ├── FlowAgent.java                     ← premain() entry point
│       │   ├── config/
│       │   │   ├── AgentConfig.java                ← all configuration properties
│       │   │   └── ConfigLoader.java               ← reads sysprops, env, YAML
│       │   ├── instrumentation/
│       │   │   ├── FlowTransformer.java            ← ByteBuddy AgentBuilder setup
│       │   │   ├── MethodAdvice.java               ← @Advice enter/exit/error
│       │   │   ├── EntryPointAdvice.java           ← HTTP/Kafka entry — init/clear FlowContext
│       │   │   ├── NodeIdBuilder.java              ← runtime class+method → nodeId string
│       │   │   └── ProxyResolver.java              ← CGLIB/JDK proxy → real class
│       │   ├── context/
│       │   │   ├── FlowContext.java                ← ThreadLocal trace context
│       │   │   ├── SpanInfo.java                   ← spanId, parentSpanId, nodeId, startTimeNs
│       │   │   └── TraceIdGenerator.java           ← UUID generator
│       │   ├── pipeline/
│       │   │   ├── FlowEventSink.java              ← static emit() entry point
│       │   │   ├── RuntimeEvent.java               ← event POJO
│       │   │   ├── EventRingBuffer.java            ← bounded lock-free buffer
│       │   │   └── BatchAssembler.java             ← daemon thread: drain + batch + flush
│       │   ├── transport/
│       │   │   ├── HttpBatchSender.java            ← async HTTP client → FCS
│       │   │   ├── CircuitBreaker.java             ← CLOSED/OPEN/HALF_OPEN state machine
│       │   │   └── PayloadSerializer.java          ← Jackson JSON + GZIP
│       │   ├── filter/
│       │   │   ├── FilterChain.java                ← orchestrates all filters
│       │   │   ├── PackageFilter.java              ← include/exclude package patterns
│       │   │   ├── MethodExcludeFilter.java        ← skip toString, hashCode, getters/setters
│       │   │   └── BridgeMethodFilter.java         ← skip JVM-generated bridge methods
│       │   ├── sampling/
│       │   │   ├── Sampler.java                    ← interface
│       │   │   └── AlwaysSampler.java              ← 100% — Phase 1 only
│       │   └── monitor/
│       │       └── AgentMetrics.java               ← AtomicLong counters + periodic log
│       ├── main/resources/
│       │   └── META-INF/
│       │       └── flow-agent-defaults.yml         ← default configuration
│       └── test/java/com/flow/agent/
│           ├── instrumentation/
│           │   ├── NodeIdBuilderTest.java           ← THE critical test
│           │   └── ProxyResolverTest.java
│           ├── context/
│           │   └── FlowContextTest.java
│           ├── pipeline/
│           │   ├── EventRingBufferTest.java
│           │   └── BatchAssemblerTest.java
│           ├── transport/
│           │   ├── CircuitBreakerTest.java
│           │   └── HttpBatchSenderTest.java
│           └── integration/
│               └── AgentIntegrationTest.java        ← end-to-end with sample app
│
├── flow-sdk/                                 ← checkpoint SDK (Phase 3, but module created now)
│   ├── pom.xml
│   └── src/main/java/com/flow/sdk/
│       └── Flow.java                          ← single class, zero dependencies
│
└── sample/                                    ← test target app (copy or reference greens-order)
    └── greens-order/
```

---

## 4. Maven / Build Setup

### 4.1 Parent POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.flow</groupId>
    <artifactId>flow-runtime-agent-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>Flow Runtime Agent</name>
    <description>Java agent for real-time architecture graph animation</description>

    <modules>
        <module>flow-agent</module>
        <module>flow-sdk</module>
    </modules>

    <properties>
        <java.version>11</java.version>
        <maven.compiler.source>${java.version}</maven.compiler.source>
        <maven.compiler.target>${java.version}</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- Dependency versions -->
        <bytebuddy.version>1.14.18</bytebuddy.version>
        <jackson.version>2.17.2</jackson.version>
        <slf4j.version>2.0.13</slf4j.version>
        <junit.version>5.10.3</junit.version>
        <mockito.version>5.12.0</mockito.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>net.bytebuddy</groupId>
                <artifactId>byte-buddy</artifactId>
                <version>${bytebuddy.version}</version>
            </dependency>
            <dependency>
                <groupId>net.bytebuddy</groupId>
                <artifactId>byte-buddy-agent</artifactId>
                <version>${bytebuddy.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
            </dependency>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
            <dependency>
                <groupId>org.mockito</groupId>
                <artifactId>mockito-core</artifactId>
                <version>${mockito.version}</version>
                <scope>test</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

### 4.2 flow-agent Module POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.flow</groupId>
        <artifactId>flow-runtime-agent-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>flow-agent</artifactId>
    <name>Flow Agent</name>
    <description>Java agent JAR — attaches via -javaagent</description>

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

        <!-- JSON serialization (will be shaded) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- Logging -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- Shade all deps into a single fat JAR + set MANIFEST for javaagent -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>true</createDependencyReducedPom>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <manifestEntries>
                                        <Premain-Class>com.flow.agent.FlowAgent</Premain-Class>
                                        <Agent-Class>com.flow.agent.FlowAgent</Agent-Class>
                                        <Can-Retransform-Classes>true</Can-Retransform-Classes>
                                    </manifestEntries>
                                </transformer>
                            </transformers>
                            <relocations>
                                <!-- Shade ByteBuddy to avoid conflicts with customer's ByteBuddy -->
                                <relocation>
                                    <pattern>net.bytebuddy</pattern>
                                    <shadedPattern>com.flow.agent.shaded.bytebuddy</shadedPattern>
                                </relocation>
                                <!-- Shade Jackson to avoid conflicts with customer's Jackson -->
                                <relocation>
                                    <pattern>com.fasterxml.jackson</pattern>
                                    <shadedPattern>com.flow.agent.shaded.jackson</shadedPattern>
                                </relocation>
                            </relocations>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.3 flow-sdk Module POM

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.flow</groupId>
        <artifactId>flow-runtime-agent-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>flow-sdk</artifactId>
    <name>Flow SDK</name>
    <description>Checkpoint SDK — single class, zero dependencies. Used by customer code.</description>

    <!-- ZERO dependencies. This JAR goes into customer's classpath. -->
</project>
```

### 4.4 Build & Run Commands

```bash
# Build
mvn clean package -DskipTests

# Run with agent attached to a Spring Boot app
java -javaagent:flow-agent/target/flow-agent-0.1.0-SNAPSHOT.jar \
     -Dflow.server.url=http://localhost:8080 \
     -Dflow.graph-id=order-service \
     -Dflow.packages.include=com.greens.order \
     -jar ../sample/greens-order/target/greens-order.jar
```

---

## 5. The nodeId Contract

> **This is the single most critical contract in the entire system.**  
> If the agent produces a nodeId that differs by even one character from what the scanner produced in `flow.json`, the MergeEngine cannot link the runtime event to the graph node. That node stays dark.

### 5.1 Format

```
{fully.qualified.ClassName}#{methodName}({paramType1}, {paramType2}):{returnType}
```

### 5.2 Examples (from flow.json)

```
com.greens.order.core.OrderService#placeOrder(String):String
com.greens.order.core.OrderService#validateCart(String):void
com.greens.order.core.PaymentService#charge(String):void
com.greens.order.config.KafkaProducerConfig#kafkaTemplate():KafkaTemplate<String, String>
com.greens.order.messaging.OrderProducer#sendOrderEvent(String):void
com.greens.order.messaging.OrderConsumer#listen(String):void
```

### 5.3 Type Simplification Rules

The nodeId uses simplified type names, NOT fully qualified names for common types:

| JVM Type | Simplified In nodeId |
|---|---|
| `java.lang.String` | `String` |
| `java.lang.Integer` | `Integer` |
| `java.lang.Long` | `Long` |
| `java.lang.Boolean` | `Boolean` |
| `java.lang.Object` | `Object` |
| `java.lang.Double` | `Double` |
| `java.lang.Float` | `Float` |
| `java.lang.Byte` | `Byte` |
| `java.lang.Short` | `Short` |
| `java.lang.Character` | `Character` |
| `java.util.List` | `List` |
| `java.util.Map` | `Map` |
| `java.util.Set` | `Set` |
| `java.util.Optional` | `Optional` |
| `void` | `void` |
| All primitives (`int`, `long`, etc.) | As-is |
| Everything else | Fully qualified |

> **IMPORTANT:** These rules MUST exactly match what `flow-java-adapter`'s scanner produces. Reference the `SignatureNormalizer` class in `flow-adapter` module. The nodeId contract integration test (Section 10.2) validates this.

### 5.4 Generics Preservation

Java's type erasure removes generics at bytecode level. But `Method.getGenericReturnType()` and `Method.getGenericParameterTypes()` preserve them via class metadata.

```java
// For: KafkaTemplate<String, String> kafkaTemplate()
Method method = KafkaProducerConfig.class.getMethod("kafkaTemplate");

method.getReturnType();            // → KafkaTemplate.class   (erased)
method.getGenericReturnType();     // → KafkaTemplate<String, String>  (preserved ✅)
```

**The agent MUST use `getGenericReturnType()` and `getGenericParameterTypes()`** — not `getReturnType()` / `getParameterTypes()`.

### 5.5 Proxy Resolution

Spring creates proxies that wrap customer classes:

| Proxy Pattern | Resolution |
|---|---|
| `OrderService$$SpringCGLIB$$0` | Walk superclass chain → `OrderService` |
| `OrderService$$EnhancerBySpringCGLIB$$abc123` | Walk superclass chain → `OrderService` |
| `com.sun.proxy.$Proxy123` (JDK dynamic proxy) | Inspect interfaces → find customer-package interface |

```java
public static Class<?> resolveRealClass(Class<?> clazz) {
    // CGLIB proxy resolution
    String name = clazz.getName();
    if (name.contains("$$")) {
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            return superClass;
        }
    }
    
    // JDK dynamic proxy resolution (Spring Data repositories)
    if (name.startsWith("com.sun.proxy.$Proxy") || Proxy.isProxyClass(clazz)) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (matchesPackageFilter(iface.getName())) {
                return iface;
            }
        }
    }
    
    return clazz;
}
```

### 5.6 Hard Cases

| Case | Problem | Solution |
|---|---|---|
| **CGLIB proxy** | `OrderService$$SpringCGLIB$$0` | Resolve superclass → `OrderService` |
| **JDK proxy** | `$Proxy123` | Inspect interfaces → customer-package interface |
| **Method overloading** | `placeOrder(String)` vs `placeOrder(String, User)` | Include param types — already in nodeId format |
| **Generics** | Runtime: `KafkaTemplate` vs static: `KafkaTemplate<String, String>` | Use `getGenericReturnType()` |
| **Lambdas** | `OrderService$$Lambda$42` | **Skip** — lambdas are not in the static graph |
| **Bridge methods** | JVM-generated bridge for generics | **Skip** — filter by `method.isBridge()` |
| **Default methods** | Interface default methods | Use `method.getDeclaringClass()` |
| **Inherited methods** | `toString()` from `Object` | Filtered out by package filter |
| **Synthetic methods** | JVM-generated | **Skip** — filter by `method.isSynthetic()` |

### 5.7 External System nodeId Formats

These are NOT method nodeIds. They follow a different format:

| System | nodeId Pattern | Example |
|---|---|---|
| HTTP endpoint | `endpoint:{METHOD} {path}` | `endpoint:POST /api/orders/{id}` |
| Kafka topic | `topic:{topicName}` | `topic:orders.v1` |
| Database | `db:{dataSource}:query:{table}` | `db:primary:query:orders` |
| Redis cache | `cache:redis:{host}:{op}:{keyPattern}` | `cache:redis:localhost:GET:order:*` |
| gRPC | `grpc:{service}/{method}` | `grpc:PaymentService/Charge` |

> **Phase 1 only handles method nodeIds.** External system nodeIds are handled in Phase 2 via OTel bridge.

---

## 6. Configuration Model

### 6.1 Configuration Sources (Priority Order)

```
1. System properties:     -Dflow.server.url=http://fcs:8080      (highest priority)
2. Environment variables:  FLOW_SERVER_URL=http://fcs:8080
3. Config file:           flow-agent.yml (next to agent JAR or -Dflow.config=/path)
4. Defaults:              Built into the agent                     (lowest priority)
```

### 6.2 Full Configuration Schema

```yaml
flow:
  # ── Connection ──────────────────────────────────────────────
  server:
    url: "http://localhost:8080"        # REQUIRED — FCS base URL
    api-key: "${FLOW_API_KEY}"          # REQUIRED in production
    connect-timeout-ms: 5000
    read-timeout-ms: 5000

  # ── Identity ────────────────────────────────────────────────
  graph-id: "order-service"             # REQUIRED — maps to static graph in FCS
  service-name: "order-service"         # Optional — display name

  # ── Instrumentation ────────────────────────────────────────
  packages:
    include:                            # REQUIRED — at least one package
      - "com.greens.order"
    exclude:                            # Optional
      - "com.greens.order.config.internal"
  
  filter:
    skip-getters-setters: true          # Default: true
    skip-constructors: true             # Default: true
    skip-private-methods: false         # Default: false
    skip-synthetic: true                # Default: true

  # ── Sampling ────────────────────────────────────────────────
  sampling:
    rate: 1.0                           # 1.0 = 100% (Phase 1 default)

  # ── Pipeline ────────────────────────────────────────────────
  pipeline:
    buffer-size: 8192                   # Ring buffer capacity
    batch-size: 100                     # Events per HTTP batch
    flush-interval-ms: 200              # Max time before batch flush

  # ── Resilience ──────────────────────────────────────────────
  circuit-breaker:
    failure-threshold: 3                # Consecutive failures → OPEN
    reset-timeout-ms: 30000             # Wait before HALF_OPEN probe

  # ── Agent control ───────────────────────────────────────────
  enabled: true                         # Kill switch — false = premain() returns immediately
```

### 6.3 Minimum Viable Config

To run the agent, only 3 properties are needed:

```bash
java -javaagent:flow-agent.jar \
     -Dflow.server.url=http://localhost:8080 \
     -Dflow.graph-id=order-service \
     -Dflow.packages.include=com.greens.order \
     -jar my-app.jar
```

---

## 7. Phase 1 — Class-by-Class Implementation Guide

> **Phase 1 Goal:** Trace every method call in customer code, ship events to FCS, see method nodes light up on the architecture graph. No external system integration. No OTel. No checkpoint SDK. Just methods.

---

### 7.1 FlowAgent.java — Entry Point

**Location:** `com.flow.agent.FlowAgent`

This is the `premain()` entry point. The JVM calls this before `main()`.

```java
package com.flow.agent;

import java.lang.instrument.Instrumentation;

/**
 * Flow Runtime Agent entry point.
 * Attaches via: -javaagent:flow-agent.jar
 */
public class FlowAgent {

    private static volatile boolean initialized = false;

    /**
     * Called by the JVM before main().
     * MUST be fast (< 500ms). MUST NOT throw.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            if (initialized) return;
            initialized = true;

            // 1. Load configuration
            AgentConfig config = ConfigLoader.load(agentArgs);

            // 2. Kill switch
            if (!config.isEnabled()) {
                log("[flow-agent] Disabled via config. Exiting premain().");
                return;
            }

            // 3. Build filter chain
            FilterChain filterChain = FilterChain.from(config);

            // 4. Initialize FlowContext
            FlowContext.init();

            // 5. Initialize pipeline (ring buffer + batch assembler)
            EventRingBuffer ringBuffer = new EventRingBuffer(config.getPipeline().getBufferSize());
            FlowEventSink.init(ringBuffer);

            // 6. Initialize transport (HTTP sender + circuit breaker)
            CircuitBreaker circuitBreaker = new CircuitBreaker(config.getCircuitBreaker());
            HttpBatchSender sender = new HttpBatchSender(config.getServer(), circuitBreaker);

            // 7. Start batch assembler daemon thread
            BatchAssembler assembler = new BatchAssembler(
                ringBuffer, sender, config.getPipeline(), config.getGraphId()
            );
            assembler.start(); // daemon thread

            // 8. Install ByteBuddy transformer
            FlowTransformer.install(instrumentation, filterChain, config);

            // 9. Start agent metrics logger
            AgentMetrics.startPeriodicLog(60); // every 60 seconds

            log("[flow-agent] Initialized. graphId=" + config.getGraphId()
                + " packages=" + config.getPackages().getInclude());

        } catch (Throwable t) {
            // GOLDEN RULE: never crash the customer's app
            System.err.println("[flow-agent] Failed to initialize: " + t.getMessage());
        }
    }

    /**
     * For dynamic attach (Phase 6).
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}
```

**Key rules:**
- `premain()` MUST NOT throw — everything wrapped in try-catch
- MUST complete in < 500ms — no network calls, no heavy I/O
- Network (HTTP to FCS) starts on daemon threads AFTER premain returns

---

### 7.2 config/AgentConfig.java

```java
package com.flow.agent.config;

import java.util.List;

/**
 * Immutable agent configuration. Built by ConfigLoader.
 */
public class AgentConfig {
    private boolean enabled = true;
    private ServerConfig server;
    private String graphId;
    private PackagesConfig packages;
    private FilterConfig filter;
    private SamplingConfig sampling;
    private PipelineConfig pipeline;
    private CircuitBreakerConfig circuitBreaker;

    // Nested config classes
    public static class ServerConfig {
        private String url;               // REQUIRED
        private String apiKey;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 5000;
        // getters...
    }

    public static class PackagesConfig {
        private List<String> include;     // REQUIRED — at least one
        private List<String> exclude = List.of();
        // getters...
    }

    public static class FilterConfig {
        private boolean skipGettersSetters = true;
        private boolean skipConstructors = true;
        private boolean skipPrivateMethods = false;
        private boolean skipSynthetic = true;
        // getters...
    }

    public static class SamplingConfig {
        private double rate = 1.0;        // 1.0 = 100%
        // getters...
    }

    public static class PipelineConfig {
        private int bufferSize = 8192;
        private int batchSize = 100;
        private int flushIntervalMs = 200;
        // getters...
    }

    public static class CircuitBreakerConfig {
        private int failureThreshold = 3;
        private int resetTimeoutMs = 30000;
        // getters...
    }

    // All getters...
}
```

---

### 7.3 config/ConfigLoader.java

```java
package com.flow.agent.config;

/**
 * Loads configuration from system properties, env vars, YAML file, and defaults.
 * Priority: sysprop > env > file > defaults.
 */
public class ConfigLoader {

    public static AgentConfig load(String agentArgs) {
        AgentConfig config = new AgentConfig();

        // 1. Load defaults (built into AgentConfig field initializers)

        // 2. Load YAML file if present
        //    Look for: -Dflow.config=/path/to/flow-agent.yml
        //    OR: flow-agent.yml next to the agent JAR
        String configPath = System.getProperty("flow.config");
        if (configPath != null) {
            loadYaml(config, configPath);
        }

        // 3. Override with environment variables
        //    FLOW_SERVER_URL → flow.server.url
        //    FLOW_GRAPH_ID → flow.graph-id
        //    FLOW_PACKAGES_INCLUDE → flow.packages.include (comma-separated)
        //    FLOW_API_KEY → flow.server.api-key
        applyEnvironmentVariables(config);

        // 4. Override with system properties (highest priority)
        //    -Dflow.server.url=http://...
        //    -Dflow.graph-id=order-service
        //    -Dflow.packages.include=com.greens.order
        applySystemProperties(config);

        // 5. Validate required fields
        validate(config);

        return config;
    }

    private static void validate(AgentConfig config) {
        if (config.getServer() == null || config.getServer().getUrl() == null) {
            throw new IllegalArgumentException("[flow-agent] flow.server.url is required");
        }
        if (config.getGraphId() == null) {
            throw new IllegalArgumentException("[flow-agent] flow.graph-id is required");
        }
        if (config.getPackages() == null || config.getPackages().getInclude() == null
            || config.getPackages().getInclude().isEmpty()) {
            throw new IllegalArgumentException("[flow-agent] flow.packages.include is required");
        }
    }

    // ... YAML parsing, env var mapping, sysprop mapping
}
```

---

### 7.4 instrumentation/FlowTransformer.java

```java
package com.flow.agent.instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;

/**
 * Installs ByteBuddy class transformations for method-level tracing.
 * Transforms ONLY customer-package classes. Never touches framework code.
 */
public class FlowTransformer {

    public static void install(Instrumentation instrumentation,
                                FilterChain filterChain,
                                AgentConfig config) {

        new AgentBuilder.Default()
            // Safety: if transformation fails, skip the class, don't crash
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new SafeTransformListener())      // logs warning, never throws
            .disableClassFormatChanges()             // safer transformations

            // Match only customer packages
            .type(type -> filterChain.shouldInstrumentClass(type.getName()))

            // Install method advice
            .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                return builder.visit(
                    Advice.to(MethodAdvice.class)
                        .on(method -> filterChain.shouldInstrumentMethod(
                            typeDescription, method))
                );
            })

            .installOn(instrumentation);
    }

    /**
     * Safety listener — logs errors, NEVER throws.
     * If a class fails to transform, the class loads normally (uninstrumented).
     */
    private static class SafeTransformListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onError(String typeName, ClassLoader classLoader,
                           net.bytebuddy.utility.JavaModule module,
                           boolean loaded, Throwable throwable) {
            System.err.println("[flow-agent] WARN: Failed to transform " + typeName
                + ": " + throwable.getMessage());
            // DO NOT rethrow — the class loads normally, uninstrumented
        }
    }
}
```

---

### 7.5 instrumentation/MethodAdvice.java

```java
package com.flow.agent.instrumentation;

import com.flow.agent.context.FlowContext;
import com.flow.agent.context.SpanInfo;
import com.flow.agent.context.TraceIdGenerator;
import com.flow.agent.pipeline.FlowEventSink;
import com.flow.agent.monitor.AgentMetrics;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice inlined into every instrumented method.
 * Runs on the APPLICATION THREAD — must be nanosecond-fast, never throw.
 */
public class MethodAdvice {

    @Advice.OnMethodEnter
    public static long onEnter(
            @Advice.Origin Method method,
            @Advice.Origin Class<?> declaringClass) {
        try {
            // Initialize trace context if this is the first instrumented method
            FlowContext ctx = FlowContext.getOrInit();

            long startTimeNs = System.nanoTime();

            // Build nodeId
            String nodeId = NodeIdBuilder.build(declaringClass, method);

            // Push span onto the stack
            String spanId = TraceIdGenerator.generateSpanId();
            String parentSpanId = ctx.currentSpanId(); // null if this is the root
            ctx.pushSpan(new SpanInfo(spanId, parentSpanId, nodeId, startTimeNs));

            // Emit METHOD_ENTER event
            FlowEventSink.emit(
                ctx.getTraceId(), spanId, parentSpanId,
                nodeId, "METHOD_ENTER", System.currentTimeMillis(),
                0L, null
            );

            AgentMetrics.incrementEventsEmitted();
            return startTimeNs;

        } catch (Throwable t) {
            // GOLDEN RULE: never propagate to customer code
            return 0L;
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter long startTimeNs,
            @Advice.Origin Method method,
            @Advice.Origin Class<?> declaringClass,
            @Advice.Thrown Throwable thrown) {
        try {
            if (startTimeNs == 0L) return; // enter failed — skip exit

            FlowContext ctx = FlowContext.current();
            if (ctx == null) return;

            long durationMs = (System.nanoTime() - startTimeNs) / 1_000_000;

            SpanInfo span = ctx.popSpan();
            if (span == null) return;

            // Emit METHOD_EXIT event
            String type = (thrown != null) ? "ERROR" : "METHOD_EXIT";
            FlowEventSink.emit(
                ctx.getTraceId(), span.getSpanId(), span.getParentSpanId(),
                span.getNodeId(), type, System.currentTimeMillis(),
                durationMs, thrown != null ? thrown.getClass().getName() : null
            );

            AgentMetrics.incrementEventsEmitted();

            // If this is the last span (root exit), clean up FlowContext
            if (ctx.isSpanStackEmpty()) {
                FlowContext.clear();
            }

        } catch (Throwable t) {
            // GOLDEN RULE: never propagate to customer code
        }
    }
}
```

> **CRITICAL: `FlowContext.clear()` on root exit.** Without this, the next request on the same thread pool thread inherits the old traceId. This causes silent trace corruption in production. See Gap 11 in RUNTIME_PLUGIN_DESIGN §28.

---

### 7.6 instrumentation/NodeIdBuilder.java

```java
package com.flow.agent.instrumentation;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds nodeId strings from runtime class + method.
 * Output MUST exactly match what flow-java-adapter's scanner produces.
 * 
 * Format: {FQCN}#{methodName}({paramTypes}):{returnType}
 * Example: com.greens.order.core.OrderService#placeOrder(String):String
 */
public class NodeIdBuilder {

    // Cache: Method → nodeId (computed once per method, reused)
    private static final Map<Method, String> CACHE = new ConcurrentHashMap<>();

    /**
     * Build the nodeId for a given class and method.
     * Uses the CACHE to avoid recomputing on every call.
     */
    public static String build(Class<?> declaringClass, Method method) {
        return CACHE.computeIfAbsent(method, m -> buildInternal(declaringClass, m));
    }

    private static String buildInternal(Class<?> declaringClass, Method method) {
        // 1. Resolve proxy class to real class
        Class<?> realClass = ProxyResolver.resolve(declaringClass);
        String className = realClass.getName();

        // 2. Method name
        String methodName = method.getName();

        // 3. Parameter types (using generics-preserving reflection)
        Type[] genericParamTypes = method.getGenericParameterTypes();
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < genericParamTypes.length; i++) {
            if (i > 0) params.append(", ");
            params.append(simplifyType(genericParamTypes[i].getTypeName()));
        }

        // 4. Return type (using generics-preserving reflection)
        String returnType = simplifyType(method.getGenericReturnType().getTypeName());

        // 5. Assemble
        return className + "#" + methodName + "(" + params + "):" + returnType;
    }

    /**
     * Simplify Java type names to match flow.json conventions.
     * java.lang.String → String
     * java.util.List<java.lang.String> → List<String>
     */
    static String simplifyType(String typeName) {
        // Replace java.lang. common types
        String result = typeName;
        result = result.replace("java.lang.String", "String");
        result = result.replace("java.lang.Integer", "Integer");
        result = result.replace("java.lang.Long", "Long");
        result = result.replace("java.lang.Boolean", "Boolean");
        result = result.replace("java.lang.Double", "Double");
        result = result.replace("java.lang.Float", "Float");
        result = result.replace("java.lang.Object", "Object");
        result = result.replace("java.lang.Byte", "Byte");
        result = result.replace("java.lang.Short", "Short");
        result = result.replace("java.lang.Character", "Character");
        result = result.replace("java.util.List", "List");
        result = result.replace("java.util.Map", "Map");
        result = result.replace("java.util.Set", "Set");
        result = result.replace("java.util.Optional", "Optional");
        return result;
    }
}
```

> **WARNING:** The `simplifyType()` rules MUST exactly match the scanner's rules. This is the #1 source of nodeId mismatch bugs. The integration test in Section 10.2 validates this.

---

### 7.7 instrumentation/ProxyResolver.java

```java
package com.flow.agent.instrumentation;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Resolves Spring CGLIB proxies and JDK dynamic proxies to their real classes.
 */
public class ProxyResolver {

    // Updated at startup from AgentConfig.packages.include
    private static List<String> packagePrefixes = List.of();

    public static void init(List<String> packages) {
        packagePrefixes = packages;
    }

    public static Class<?> resolve(Class<?> clazz) {
        String name = clazz.getName();

        // CGLIB proxy: OrderService$$SpringCGLIB$$0 → OrderService
        if (name.contains("$$")) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return superClass;
            }
        }

        // JDK dynamic proxy: $Proxy123 → customer interface
        if (Proxy.isProxyClass(clazz) || name.startsWith("com.sun.proxy.$Proxy")) {
            for (Class<?> iface : clazz.getInterfaces()) {
                for (String prefix : packagePrefixes) {
                    if (iface.getName().startsWith(prefix)) {
                        return iface;
                    }
                }
            }
        }

        return clazz;
    }
}
```

---

### 7.8 context/FlowContext.java

```java
package com.flow.agent.context;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ThreadLocal-based trace context. One per request thread.
 * 
 * CRITICAL: Must be cleared after every request (FlowContext.clear()).
 * Failure to clear causes trace corruption on thread pool reuse.
 */
public class FlowContext {

    private static final ThreadLocal<FlowContext> HOLDER = new ThreadLocal<>();

    private final String traceId;
    private final Deque<SpanInfo> spanStack = new ArrayDeque<>();

    private FlowContext(String traceId) {
        this.traceId = traceId;
    }

    /** Get existing context or create new one (new trace). */
    public static FlowContext getOrInit() {
        FlowContext ctx = HOLDER.get();
        if (ctx == null) {
            ctx = new FlowContext(TraceIdGenerator.generate());
            HOLDER.set(ctx);
        }
        return ctx;
    }

    /** Get existing context. Returns null if no active trace. */
    public static FlowContext current() {
        return HOLDER.get();
    }

    /** Initialize a new trace context (used by entry point advice). */
    public static void initNewTrace() {
        HOLDER.set(new FlowContext(TraceIdGenerator.generate()));
    }

    /**
     * CRITICAL: Remove ThreadLocal to prevent trace corruption.
     * Must be called at the end of every request / entry point.
     */
    public static void clear() {
        HOLDER.remove();
    }

    public static void init() {
        // No-op — just ensures class is loaded
    }

    public String getTraceId() { return traceId; }

    public void pushSpan(SpanInfo span) { spanStack.push(span); }

    public SpanInfo popSpan() {
        return spanStack.isEmpty() ? null : spanStack.pop();
    }

    public String currentSpanId() {
        SpanInfo top = spanStack.peek();
        return top != null ? top.getSpanId() : null;
    }

    public String currentNodeId() {
        SpanInfo top = spanStack.peek();
        return top != null ? top.getNodeId() : null;
    }

    public boolean isSpanStackEmpty() { return spanStack.isEmpty(); }
}
```

---

### 7.9 context/SpanInfo.java

```java
package com.flow.agent.context;

/**
 * Represents a single method execution in the span stack.
 */
public class SpanInfo {
    private final String spanId;
    private final String parentSpanId;  // null for root span
    private final String nodeId;
    private final long startTimeNs;

    public SpanInfo(String spanId, String parentSpanId, String nodeId, long startTimeNs) {
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.nodeId = nodeId;
        this.startTimeNs = startTimeNs;
    }

    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getNodeId() { return nodeId; }
    public long getStartTimeNs() { return startTimeNs; }
}
```

---

### 7.10 context/TraceIdGenerator.java

```java
package com.flow.agent.context;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class TraceIdGenerator {

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static String generateSpanId() {
        // Shorter ID for spans — 16 hex chars
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }
}
```

---

### 7.11 pipeline/RuntimeEvent.java

```java
package com.flow.agent.pipeline;

/**
 * A single event to be shipped to FCS.
 */
public class RuntimeEvent {
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String nodeId;
    private final String type;          // METHOD_ENTER | METHOD_EXIT | ERROR
    private final long timestamp;       // epoch millis
    private final long durationMs;      // 0 for ENTER events
    private final String errorType;     // null if no error

    public RuntimeEvent(String traceId, String spanId, String parentSpanId,
                        String nodeId, String type, long timestamp,
                        long durationMs, String errorType) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.nodeId = nodeId;
        this.type = type;
        this.timestamp = timestamp;
        this.durationMs = durationMs;
        this.errorType = errorType;
    }

    // All getters...
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public long getDurationMs() { return durationMs; }
    public String getErrorType() { return errorType; }
}
```

---

### 7.12 pipeline/FlowEventSink.java

```java
package com.flow.agent.pipeline;

/**
 * Static entry point for emitting events from advice code.
 * Advice cannot hold instance references — must be static.
 */
public class FlowEventSink {

    private static volatile EventRingBuffer ringBuffer;

    public static void init(EventRingBuffer buffer) {
        ringBuffer = buffer;
    }

    /**
     * Emit an event into the pipeline. Non-blocking. Never throws.
     * If the buffer is full, the event is DROPPED silently.
     */
    public static void emit(String traceId, String spanId, String parentSpanId,
                            String nodeId, String type, long timestamp,
                            long durationMs, String errorType) {
        EventRingBuffer buf = ringBuffer;
        if (buf == null) return; // agent not initialized yet

        RuntimeEvent event = new RuntimeEvent(
            traceId, spanId, parentSpanId, nodeId, type, timestamp, durationMs, errorType
        );
        buf.offer(event);
    }
}
```

---

### 7.13 pipeline/EventRingBuffer.java

```java
package com.flow.agent.pipeline;

import com.flow.agent.monitor.AgentMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Bounded, non-blocking event buffer.
 * offer() never blocks. If full, events are DROPPED.
 */
public class EventRingBuffer {

    private final ArrayBlockingQueue<RuntimeEvent> queue;

    public EventRingBuffer(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Non-blocking put. Returns false (and drops event) if full. */
    public boolean offer(RuntimeEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            AgentMetrics.incrementEventsDropped();
        }
        return accepted;
    }

    /** Drain up to maxCount events into the given list. Returns count drained. */
    public int drainTo(List<RuntimeEvent> target, int maxCount) {
        return queue.drainTo(target, maxCount);
    }

    public int size() { return queue.size(); }
    public boolean isEmpty() { return queue.isEmpty(); }
}
```

---

### 7.14 pipeline/BatchAssembler.java

```java
package com.flow.agent.pipeline;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.transport.HttpBatchSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Daemon thread that drains the ring buffer and flushes batches to FCS.
 * Flush trigger: count >= batchSize OR time >= flushIntervalMs (whichever first).
 */
public class BatchAssembler {

    private final EventRingBuffer ringBuffer;
    private final HttpBatchSender sender;
    private final int batchSize;
    private final int flushIntervalMs;
    private final String graphId;
    private volatile boolean running = true;

    public BatchAssembler(EventRingBuffer ringBuffer, HttpBatchSender sender,
                          AgentConfig.PipelineConfig pipelineConfig, String graphId) {
        this.ringBuffer = ringBuffer;
        this.sender = sender;
        this.batchSize = pipelineConfig.getBatchSize();
        this.flushIntervalMs = pipelineConfig.getFlushIntervalMs();
        this.graphId = graphId;
    }

    public void start() {
        Thread thread = new Thread(this::run, "flow-agent-pipeline");
        thread.setDaemon(true); // dies with the JVM — never keeps the app alive
        thread.start();
    }

    private void run() {
        List<RuntimeEvent> batch = new ArrayList<>(batchSize);
        long lastFlushTime = System.currentTimeMillis();

        while (running) {
            try {
                // Drain events from ring buffer
                ringBuffer.drainTo(batch, batchSize);

                long now = System.currentTimeMillis();
                boolean countTrigger = batch.size() >= batchSize;
                boolean timeTrigger = (now - lastFlushTime) >= flushIntervalMs && !batch.isEmpty();

                if (countTrigger || timeTrigger) {
                    sender.send(graphId, new ArrayList<>(batch));
                    batch.clear();
                    lastFlushTime = now;
                }

                // Sleep briefly to avoid busy-spinning
                if (batch.isEmpty()) {
                    Thread.sleep(10);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // Never crash the pipeline thread
                System.err.println("[flow-agent] Pipeline error: " + t.getMessage());
                batch.clear();
            }
        }
    }

    public void stop() { running = false; }
}
```

---

### 7.15 transport/HttpBatchSender.java

```java
package com.flow.agent.transport;

import com.flow.agent.config.AgentConfig;
import com.flow.agent.monitor.AgentMetrics;
import com.flow.agent.pipeline.RuntimeEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Sends batched events to FCS via async HTTP POST.
 * Uses java.net.http.HttpClient (Java 11+, zero external deps).
 */
public class HttpBatchSender {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final CircuitBreaker circuitBreaker;
    private final PayloadSerializer serializer;

    public HttpBatchSender(AgentConfig.ServerConfig serverConfig, CircuitBreaker circuitBreaker) {
        this.baseUrl = serverConfig.getUrl();
        this.apiKey = serverConfig.getApiKey();
        this.circuitBreaker = circuitBreaker;
        this.serializer = new PayloadSerializer();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(serverConfig.getConnectTimeoutMs()))
            .build();
    }

    /**
     * Send a batch of events to FCS. Non-blocking.
     * If circuit is OPEN, events are DROPPED silently.
     */
    public void send(String graphId, List<RuntimeEvent> events) {
        if (events.isEmpty()) return;

        if (!circuitBreaker.allowRequest()) {
            AgentMetrics.incrementEventsDropped(events.size());
            return; // circuit is OPEN — drop
        }

        try {
            byte[] body = serializer.serialize(graphId, events);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/ingest/runtime"))
                .timeout(Duration.ofMillis(5000))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

            if (apiKey != null && !apiKey.isEmpty()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        circuitBreaker.recordSuccess();
                        AgentMetrics.incrementBatchesSent();
                    } else {
                        circuitBreaker.recordFailure();
                        AgentMetrics.incrementBatchesFailed();
                    }
                })
                .exceptionally(ex -> {
                    circuitBreaker.recordFailure();
                    AgentMetrics.incrementBatchesFailed();
                    return null;
                });

        } catch (Throwable t) {
            circuitBreaker.recordFailure();
            AgentMetrics.incrementBatchesFailed();
        }
    }
}
```

---

### 7.16 transport/CircuitBreaker.java

```java
package com.flow.agent.transport;

import com.flow.agent.config.AgentConfig;

/**
 * Simple circuit breaker: CLOSED → OPEN (after N failures) → HALF_OPEN → CLOSED/OPEN.
 * Thread-safe via synchronized (low contention — only 1 transport thread).
 */
public class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openTimestamp = 0;
    private final int failureThreshold;
    private final long resetTimeoutMs;

    public CircuitBreaker(AgentConfig.CircuitBreakerConfig config) {
        this.failureThreshold = config.getFailureThreshold();
        this.resetTimeoutMs = config.getResetTimeoutMs();
    }

    public synchronized boolean allowRequest() {
        switch (state) {
            case CLOSED: return true;
            case OPEN:
                if (System.currentTimeMillis() - openTimestamp >= resetTimeoutMs) {
                    state = State.HALF_OPEN;
                    return true; // allow one probe
                }
                return false;
            case HALF_OPEN: return false; // only one probe at a time
            default: return true;
        }
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openTimestamp = System.currentTimeMillis();
        }
    }

    public synchronized State getState() { return state; }
}
```

---

### 7.17 transport/PayloadSerializer.java

```java
package com.flow.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flow.agent.pipeline.RuntimeEvent;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes event batch to JSON + GZIP.
 * 
 * Payload format:
 * {
 *   "graphId": "order-service",
 *   "agentVersion": "0.1.0",
 *   "batch": [ { traceId, spanId, ... }, ... ]
 * }
 */
public class PayloadSerializer {

    private static final String AGENT_VERSION = "0.1.0";
    private final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] serialize(String graphId, List<RuntimeEvent> events) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("graphId", graphId);
        payload.put("agentVersion", AGENT_VERSION);
        payload.put("batch", events);

        byte[] json = objectMapper.writeValueAsBytes(payload);

        // GZIP compress
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(json);
        }
        return baos.toByteArray();
    }
}
```

---

### 7.18 filter/FilterChain.java

```java
package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;

/**
 * Evaluates whether a class and method should be instrumented.
 * All filters run at CLASS LOAD TIME only — zero per-call overhead.
 */
public class FilterChain {

    private final PackageFilter packageFilter;
    private final MethodExcludeFilter methodExcludeFilter;
    private final BridgeMethodFilter bridgeMethodFilter;

    private FilterChain(PackageFilter packageFilter, 
                        MethodExcludeFilter methodExcludeFilter,
                        BridgeMethodFilter bridgeMethodFilter) {
        this.packageFilter = packageFilter;
        this.methodExcludeFilter = methodExcludeFilter;
        this.bridgeMethodFilter = bridgeMethodFilter;
    }

    public static FilterChain from(AgentConfig config) {
        return new FilterChain(
            new PackageFilter(config.getPackages()),
            new MethodExcludeFilter(config.getFilter()),
            new BridgeMethodFilter()
        );
    }

    /** Should this class be instrumented at all? */
    public boolean shouldInstrumentClass(String className) {
        return packageFilter.matches(className);
    }

    /** Should this specific method be instrumented? */
    public boolean shouldInstrumentMethod(TypeDescription type, MethodDescription method) {
        if (bridgeMethodFilter.shouldSkip(method)) return false;
        if (methodExcludeFilter.shouldSkip(method)) return false;
        return true;
    }
}
```

**PackageFilter:**

```java
package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;
import java.util.List;

public class PackageFilter {
    private final List<String> includePrefixes;
    private final List<String> excludePrefixes;

    public PackageFilter(AgentConfig.PackagesConfig config) {
        this.includePrefixes = config.getInclude();
        this.excludePrefixes = config.getExclude();
    }

    public boolean matches(String className) {
        // Must match at least one include prefix
        boolean included = false;
        for (String prefix : includePrefixes) {
            if (className.startsWith(prefix)) {
                included = true;
                break;
            }
        }
        if (!included) return false;

        // Must NOT match any exclude prefix
        for (String prefix : excludePrefixes) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
```

**MethodExcludeFilter:**

```java
package com.flow.agent.filter;

import com.flow.agent.config.AgentConfig;
import net.bytebuddy.description.method.MethodDescription;
import java.util.Set;

public class MethodExcludeFilter {
    private static final Set<String> EXCLUDED_NAMES = Set.of(
        "toString", "hashCode", "equals", "clone", "finalize",
        "wait", "notify", "notifyAll", "getClass"
    );

    private final boolean skipGettersSetters;
    private final boolean skipConstructors;
    private final boolean skipSynthetic;

    public MethodExcludeFilter(AgentConfig.FilterConfig config) {
        this.skipGettersSetters = config.isSkipGettersSetters();
        this.skipConstructors = config.isSkipConstructors();
        this.skipSynthetic = config.isSkipSynthetic();
    }

    public boolean shouldSkip(MethodDescription method) {
        String name = method.getName();

        if (EXCLUDED_NAMES.contains(name)) return true;
        if (skipConstructors && method.isConstructor()) return true;
        if (skipSynthetic && method.isSynthetic()) return true;
        if (skipGettersSetters && isGetterOrSetter(name)) return true;

        return false;
    }

    private boolean isGetterOrSetter(String name) {
        return (name.startsWith("get") || name.startsWith("set") || name.startsWith("is"))
               && name.length() > 3;
    }
}
```

**BridgeMethodFilter:**

```java
package com.flow.agent.filter;

import net.bytebuddy.description.method.MethodDescription;

public class BridgeMethodFilter {
    public boolean shouldSkip(MethodDescription method) {
        return method.isBridge();
    }
}
```

---

### 7.19 monitor/AgentMetrics.java

```java
package com.flow.agent.monitor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent self-monitoring counters. Logged every 60s.
 */
public class AgentMetrics {

    private static final AtomicLong eventsEmitted = new AtomicLong();
    private static final AtomicLong eventsDropped = new AtomicLong();
    private static final AtomicLong batchesSent = new AtomicLong();
    private static final AtomicLong batchesFailed = new AtomicLong();

    public static void incrementEventsEmitted() { eventsEmitted.incrementAndGet(); }
    public static void incrementEventsDropped() { eventsDropped.incrementAndGet(); }
    public static void incrementEventsDropped(int count) { eventsDropped.addAndGet(count); }
    public static void incrementBatchesSent() { batchesSent.incrementAndGet(); }
    public static void incrementBatchesFailed() { batchesFailed.incrementAndGet(); }

    public static void startPeriodicLog(int intervalSeconds) {
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                    long emitted = eventsEmitted.getAndSet(0);
                    long dropped = eventsDropped.getAndSet(0);
                    long sent = batchesSent.getAndSet(0);
                    long failed = batchesFailed.getAndSet(0);
                    System.out.println("[flow-agent] events=" + emitted + "/period"
                        + " dropped=" + dropped
                        + " batches_sent=" + sent
                        + " batches_failed=" + failed);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "flow-agent-metrics");
        thread.setDaemon(true);
        thread.start();
    }
}
```

---

## 8. FCS Integration Contract

### 8.1 Endpoint

```
POST {flow.server.url}/ingest/runtime
Content-Type: application/json
Content-Encoding: gzip
Authorization: Bearer {flow.server.api-key}
```

### 8.2 Payload Format

```json
{
  "graphId": "order-service",
  "agentVersion": "0.1.0",
  "batch": [
    {
      "traceId": "550e8400-e29b-41d4-a716-446655440000",
      "spanId": "7a2b3c4d5e6f",
      "parentSpanId": null,
      "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
      "type": "METHOD_ENTER",
      "timestamp": 1735412200100,
      "durationMs": 0,
      "errorType": null
    },
    {
      "traceId": "550e8400-e29b-41d4-a716-446655440000",
      "spanId": "8b3c4d5e6f7a",
      "parentSpanId": "7a2b3c4d5e6f",
      "nodeId": "com.greens.order.core.OrderService#validateCart(String):void",
      "type": "METHOD_ENTER",
      "timestamp": 1735412200105,
      "durationMs": 0,
      "errorType": null
    },
    {
      "traceId": "550e8400-e29b-41d4-a716-446655440000",
      "spanId": "8b3c4d5e6f7a",
      "parentSpanId": "7a2b3c4d5e6f",
      "nodeId": "com.greens.order.core.OrderService#validateCart(String):void",
      "type": "METHOD_EXIT",
      "timestamp": 1735412200110,
      "durationMs": 5,
      "errorType": null
    },
    {
      "traceId": "550e8400-e29b-41d4-a716-446655440000",
      "spanId": "7a2b3c4d5e6f",
      "parentSpanId": null,
      "nodeId": "com.greens.order.core.OrderService#placeOrder(String):String",
      "type": "METHOD_EXIT",
      "timestamp": 1735412200130,
      "durationMs": 30,
      "errorType": null
    }
  ]
}
```

### 8.3 Event Types

| Type | When Emitted | durationMs |
|---|---|---|
| `METHOD_ENTER` | Method begins execution | Always `0` |
| `METHOD_EXIT` | Method completes normally | Actual duration in ms |
| `ERROR` | Method throws exception | Actual duration in ms. `errorType` set to exception class name. |

### 8.4 What FCS Does With Events

```
Agent sends batch → FCS /ingest/runtime
  → RuntimeTraceBuffer groups events by traceId
  → MergeEngine matches nodeId to static graph node
  → If nodeId matches → node is enriched with runtime data
  → If nodeId doesn't match → event is ORPHANED (lost)
  → RuntimeFlowExtractor generates ordered execution path for UI
  → UI animates graph nodes
```

---

## 9. Safety Requirements — Non-Negotiable

Every developer working on this agent MUST internalize these rules:

### 9.1 Checklist

```
□ All @Advice code is wrapped in try-catch — exceptions NEVER propagate to customer code
□ Ring buffer uses offer() (non-blocking) — NEVER put() (blocking)
□ HTTP sends are async (sendAsync) — NEVER synchronous
□ Circuit breaker drops events when OPEN — NEVER queues them
□ FlowContext.clear() is called on every entry point exit — NEVER left stale
□ Only daemon threads (2 max) — pipeline + metrics. NEVER user threads.
□ No unbounded collections — all data structures have a size cap
□ No data capture — no args, no return values, no payloads, no PII
□ Class transformation failure → log warning + skip class — NEVER crash the app
□ ByteBuddy SafeTransformListener registered — NEVER throw from onError()
□ Per-method overhead < 300ns — NEVER do I/O on the application thread
□ Total agent heap < 30MB — NEVER grow beyond bounded structures
```

### 9.2 The Kill Switch

If something goes wrong in production, the customer MUST be able to disable the agent instantly:

```bash
# Option 1: Remove the -javaagent flag and restart
# Option 2: Set the kill switch (requires restart)
java -javaagent:flow-agent.jar -Dflow.enabled=false -jar my-app.jar
```

---

## 10. Testing Strategy

### 10.1 Unit Tests

| Test Class | What It Validates |
|---|---|
| `NodeIdBuilderTest` | Every hard case: CGLIB proxy, JDK proxy, generics, bridge methods, overloads, primitives, void return |
| `ProxyResolverTest` | CGLIB resolution, JDK proxy resolution, non-proxy passthrough |
| `FlowContextTest` | init, pushSpan, popSpan, clear, thread isolation, recursive spans |
| `EventRingBufferTest` | offer succeeds, overflow drops, drainTo works correctly |
| `BatchAssemblerTest` | Count trigger fires, time trigger fires, empty buffer sleeps |
| `CircuitBreakerTest` | CLOSED→OPEN transition, OPEN→HALF_OPEN after timeout, success→CLOSED |
| `FilterChainTest` | Package include/exclude, method exclude (toString etc.), bridge filter, getter filter |
| `PayloadSerializerTest` | Correct JSON structure, GZIP decompresses to valid JSON |

### 10.2 THE Critical Test — nodeId Contract

```java
/**
 * THE MOST IMPORTANT TEST IN THE ENTIRE PROJECT.
 * 
 * Validates that NodeIdBuilder (runtime) produces IDENTICAL nodeId strings
 * as flow-java-adapter's scanner (static).
 * 
 * If this test fails, NOTHING works — events will never match graph nodes.
 */
@Test
void agentNodeIdsMustMatchScannerNodeIds() throws Exception {
    // 1. Load the flow.json produced by scanning greens-order
    Set<String> scannerNodeIds = loadNodeIdsFromFlowJson("sample/greens-order/flow.json");

    // 2. Load the same greens-order classes via reflection
    // 3. For each class/method, build nodeId using NodeIdBuilder
    Set<String> agentNodeIds = new HashSet<>();
    for (Class<?> clazz : loadClasses("com.greens.order")) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic()) continue;
            agentNodeIds.add(NodeIdBuilder.build(clazz, method));
        }
    }

    // 4. Every scanner nodeId (that's a method) must be in the agent set
    Set<String> methodNodeIds = scannerNodeIds.stream()
        .filter(id -> id.contains("#"))  // only method nodes, not topic: or endpoint:
        .collect(Collectors.toSet());

    assertEquals(methodNodeIds, agentNodeIds,
        "Agent nodeIds must exactly match scanner nodeIds");
}
```

### 10.3 Integration Test

```java
/**
 * End-to-end: launch sample app with agent → verify events arrive at mock FCS.
 */
@Test
void agentSendsEventsToFcs() {
    // 1. Start a mock HTTP server on localhost:9999
    // 2. Launch greens-order with -javaagent:flow-agent.jar -Dflow.server.url=http://localhost:9999
    // 3. Send an HTTP request to greens-order (trigger placeOrder flow)
    // 4. Assert mock server received POST /ingest/runtime
    // 5. Assert payload contains METHOD_ENTER + METHOD_EXIT for OrderService#placeOrder
    // 6. Assert nodeIds in payload match flow.json
}
```

### 10.4 Safety Tests

```java
@Test
void adviceExceptionDoesNotPropagateToCustomerCode() {
    // Force NodeIdBuilder to throw → verify customer method still completes normally
}

@Test
void ringBufferOverflowDoesNotBlockApplicationThread() {
    // Fill buffer to capacity → offer() returns false → no blocking
}

@Test
void circuitBreakerDropsEventsWhenOpen() {
    // Simulate 3 failures → circuit opens → next send is dropped silently
}

@Test
void flowContextClearedAfterRequest() {
    // Simulate request A → FlowContext populated → clear()
    // Simulate request B → FlowContext has NEW traceId, not A's
}
```

---

## 11. Phase 2–6 Implementation Notes

These phases build on Phase 1. Implementation details here — full design rationale in RUNTIME_PLUGIN_DESIGN.md §27.

### 11.1 Phase 2 — OTel Bridge for External Systems

**New classes:**
- `otel/FlowSpanProcessor.java` — implements OTel `SpanProcessor`. Receives `onStart()` (ENTER) and `onEnd()` (EXIT) from OTel.
- `otel/OtelNodeIdMapper.java` — maps OTel semantic attributes to Flow nodeId format.
- `otel/OtelBootstrap.java` — programmatically starts OTel SDK if not already present.

**Key design:**
- Flow's ByteBuddy handles **customer code** (method-level). OTel handles **library code** (DB, Redis, Kafka).
- Zero overlap — they instrument different classes.
- `FlowSpanProcessor` uses `SpanProcessor` interface (not `SpanExporter`) because we need `onStart()` for METHOD_ENTER events (Q2 decision).
- Trace context bridge: `FlowContext.traceId` reads from `Span.current().getSpanContext().getTraceId()`.

### 11.2 Phase 3 — Checkpoint SDK

**flow-sdk module** (already in repo structure):

```java
package com.flow.sdk;

/**
 * Flow Checkpoint SDK. Single class. Zero dependencies.
 * Without agent: no-op. With agent: emits CHECKPOINT event.
 */
public final class Flow {
    private Flow() {}

    /**
     * Attach a key-value checkpoint to the currently executing graph node.
     * The agent intercepts this method via ByteBuddy.
     */
    public static void checkpoint(String key, Object value) {
        // No-op — agent installs advice that intercepts this call
    }
}
```

**Agent-side interception** uses `FlowContext.currentNodeId()` to attach the checkpoint to the **exact method node** — NOT `Span.current()` (which would be wrong; see RUNTIME_PLUGIN_DESIGN §27.12).

### 11.3 Phase 4 — Cross-Service + Async

- W3C `traceparent` header propagation via OTel (Phase 2 dependency).
- `@Async` executor wrapping: intercept `TaskExecutor.submit()` → wrap `Runnable` with `FlowContext` snapshot.
- `CompletableFuture.supplyAsync()` wrapping → wrap supplier.
- Each wrapped task restores `FlowContext` on the new thread.

### 11.4 Phase 5 — Production Hardening

- `ManifestSyncService` — async background pull of `flow.json` from FCS.
- `AdaptiveSampler` — adjusts rate based on throughput.
- GZIP compression (already in Phase 1 via `PayloadSerializer`).
- TLS enforcement + API key auth (already in Phase 1 via `HttpBatchSender`).
- Performance benchmarks: JMH harness, target < 3% overhead.

### 11.5 Phase 6 — Advanced

- Java 21 virtual thread support: detect `Runtime.version().feature() >= 21` → use `ScopedValue` instead of `ThreadLocal` for `FlowContext`.
- Dynamic attach: `agentmain()` already stubbed in `FlowAgent.java`.
- Remote config: `GET /agent/config?graphId=...` from FCS → reload `AgentConfig` dynamically.

---

## 12. Appendices

### 12.1 FCS Event Types Reference

| Event Type | Description | Required Fields |
|---|---|---|
| `METHOD_ENTER` | Method execution started | traceId, spanId, parentSpanId, nodeId, timestamp |
| `METHOD_EXIT` | Method execution completed | traceId, spanId, parentSpanId, nodeId, timestamp, durationMs |
| `ERROR` | Method threw exception | traceId, spanId, parentSpanId, nodeId, timestamp, durationMs, errorType |
| `PRODUCE_TOPIC` | Kafka/MQ message produced (Phase 2) | traceId, spanId, nodeId (topic:xxx), timestamp |
| `CONSUME_TOPIC` | Kafka/MQ message consumed (Phase 2) | traceId, spanId, parentSpanId, nodeId, timestamp |
| `CHECKPOINT` | Developer checkpoint (Phase 3) | traceId, spanId, nodeId, data: { key: value } |

### 12.2 Agent Startup Log Example

```
[flow-agent] Initialized. graphId=order-service packages=[com.greens.order]
[flow-agent] events=0/period dropped=0 batches_sent=0 batches_failed=0
[flow-agent] events=1240/period dropped=0 batches_sent=12 batches_failed=0
[flow-agent] events=1180/period dropped=0 batches_sent=12 batches_failed=0
```

### 12.3 Decision Summary (All 15 Questions)

| Q | Decision |
|---|---|
| Q1 | Separate repo |
| Q2 | FCS pairs ENTER+EXIT — graph animates on ENTER |
| Q3 | Lazy reflection (getGenericReturnType) primary + manifest enrichment |
| Q4 | flow-sdk.jar — single class, zero deps |
| Q5 | Java 11 minimum |
| Q6 | Async background pull — optional enrichment |
| Q7 | Hybrid: Flow owns method-level, OTel provides external systems |
| Q8 | OTLP/HTTP default, gRPC Phase 6 |
| Q9 | FCS infers tenant from API key |
| Q10 | Local log line only |
| Q11 | FCS-side normalisation |
| Q12 | Not needed — OTel provides db.sql.table |
| Q13 | External nodes MUST be in flow.json |
| Q14 | Lazy at class-load |
| Q15 | OTel handles both AWS SDK versions |

### 12.4 Performance Budget

| Metric | Ceiling |
|---|---|
| Per-method enter advice | < 100ns |
| Per-method exit advice | < 200ns |
| Total per method call | < 300ns |
| Max events/sec (pre-sampling) | 50,000 |
| Max batches/sec to FCS | 10 |
| Max HTTP payload size | 100KB compressed |
| Agent daemon threads | 2 |
| Agent heap usage | < 30MB |

---

*End of implementation guide. For deep architecture rationale, see RUNTIME_PLUGIN_DESIGN.md.*

