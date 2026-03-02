# What is Pragmatica Aether?

Pragmatica Aether is a distributed application platform for Java. It takes your business logic — packaged as **slices** — and runs it across a cluster of nodes with automatic scaling, zero-downtime updates, built-in resilience, and full operational visibility. No service mesh. No sidecar proxies. No YAML mountains. Just your code and the runtime.

Aether occupies the space between a monolith and microservices. You write code as if it's a monolith — simple interfaces, direct method calls, no network boilerplate. The runtime handles distribution, replication, routing, and failure recovery transparently. When a node goes down, requests are retried on healthy nodes. When load increases, new instances are spun up automatically. When you deploy a new version, traffic shifts gradually with health-based rollback.

The platform is built on Pragmatica Lite, a functional Java core library that enforces predictable, testable code through the JBCT (Java Backend Coding Technology) methodology. Every slice method returns a `Promise<T>` — the runtime handles the rest.

---

## For Developers

### The Programming Model

A **slice** is a unit of deployment — a Java interface annotated with `@Slice`:

```java
@Slice
public interface OrderService {
    Promise<OrderResult> placeOrder(PlaceOrderRequest request);

    static OrderService orderService(InventoryService inventory, PricingEngine pricing) {
        return request -> inventory.check(request.items())
                                   .flatMap(available -> pricing.calculate(available))
                                   .map(priced -> OrderResult.placed(priced));
    }
}
```

That's it. No Spring annotations, no dependency injection framework, no HTTP client configuration. The factory method declares dependencies (other slices), and the runtime materializes them at activation time. `InventoryService` might be on the same node or across the cluster — the invocation path is identical.

### What You Don't Write

- **No HTTP clients** — inter-slice calls are direct method invocations via generated proxies
- **No service discovery** — the runtime tracks where every slice instance lives
- **No retry logic** — built-in retry with exponential backoff and node failover
- **No circuit breakers** — the reliability fabric handles failure automatically
- **No serialization code** — request/response types are serialized transparently (Fury binary serialization for inter-node, JSON for HTTP)

### Slice Lifecycle

Every slice follows a deterministic state machine:

```
LOAD → LOADING → LOADED → ACTIVATE → ACTIVATING → ACTIVE
                                                      ↓
                              UNLOADING ← DEACTIVATING
```

Two optional hooks: `start()` (called during ACTIVATING, before accepting requests) and `stop()` (called during DEACTIVATING, for graceful cleanup). Both have configurable timeouts.

Dependencies are validated eagerly during activation — if a required slice isn't available, activation fails immediately with a clear error, not at first request time.

### ClassLoader Isolation

Each slice runs in its own `SliceClassLoader` with child-first delegation. Two slices can use different versions of the same library without conflict. Shared dependencies (like Pragmatica Lite core) are loaded once in a parent classloader and shared across all slices.

### Local Development: Forge

Forge is a single-JVM multi-node simulator. It runs a full 5-node cluster on your laptop with a web dashboard for:

- **Topology visualization** — see nodes, leader, connections (D3.js)
- **Real-time metrics** — CPU, heap, throughput, success rate (Chart.js, WebSocket push)
- **Chaos operations** — kill nodes, crash leader, rolling restart
- **Load generation** — configurable request rates with ramp-up

Start Forge, deploy a blueprint, and see your slices running across a cluster — all locally, all observable.

### Examples

- **URL Shortener** — demonstrates HTTP routing, inter-slice calls, and CacheService integration
- **Order Domain** — 5-slice e-commerce example (OrderProcessor, Inventory, Pricing, Payment, Notification)

---

## For Operations

### Deployment

Slices are deployed via **blueprints** — TOML files declaring what to run and how many instances:

```toml
id = "org.example:commerce:1.0.0"

[[slices]]
artifact = "org.example:inventory-service:1.0.0"
instances = 3

[[slices]]
artifact = "org.example:order-processor:1.0.0"
instances = 5
```

Apply with one command:

```bash
aether blueprint apply commerce.toml
```

The runtime resolves artifacts, loads slices, distributes instances across nodes, registers routes, and starts serving traffic. To update, change the blueprint and re-apply.

### Rolling Updates

Zero-downtime deployments with traffic control:

```bash
aether update start org.example:order-processor 2.0.0 -n 3
aether update routing <id> -r 1:3    # 25% to v2, 75% to v1
aether update routing <id> -r 1:1    # 50/50
aether update complete <id>          # 100% to v2, drain v1
```

Health-based guardrails: if the new version's error rate exceeds thresholds, rollback is one command away (`aether update rollback <id>`).

### Scaling

**Two-tier control system:**

| Tier | Interval | Function |
|------|----------|----------|
| Decision Tree (reactive) | 1 second | Responds to current conditions — CPU, latency, queue depth, error rate |
| TTM Predictor (proactive) | 60 seconds | ONNX-based ML model forecasts load from 2-hour historical window, pre-scales before spikes |

The reactive tier handles immediate demand. The predictive tier (when enabled with a trained model) anticipates patterns — daily peaks, scheduled events, gradual ramps — and scales preemptively.

Configuration is per-blueprint:

```bash
aether scale org.example:order-processor --min 2 --max 10 --target-cpu 60 --target-latency 100ms
```

### Monitoring

**Metrics collection:** Push-based, 1-second intervals. Leader aggregates from all nodes. Zero consensus I/O overhead for metrics — they bypass the consensus protocol entirely.

**What's collected:**
- JVM: CPU, heap usage, GC time (per node)
- Per-method: call count, success/failure, P50/P95/P99 latency
- Slow invocation tracking with configurable threshold strategies (fixed, adaptive, per-method, composite)

**Prometheus endpoint:** `GET /metrics/prometheus` — standard scrape-compatible format.

**WebSocket dashboard:** Real-time push at `/ws/dashboard` — no polling. Nodes, metrics, alerts, all streamed.

**Alert thresholds:** Configurable per metric, persisted via consensus. Set warning and critical levels, get notified when crossed.

**Dynamic aspects:** Toggle per-method LOG/METRICS/LOG_AND_METRICS modes at runtime via REST API (`/api/aspects`) without redeployment or restart.

### CLI

37 commands organized by function — deployment, scaling, updates, artifacts, observability, control, alerts. Both single-command and interactive REPL modes.

### Management API

30+ REST endpoints covering cluster status, slice management, metrics, controller configuration, alert thresholds, rolling updates, artifact repository, and dashboard. Full programmatic control of everything the CLI can do.

### Artifact Repository

Built-in cluster artifact cache backed by the distributed hash table. Artifacts are chunked (64KB), replicated across nodes with quorum consistency, and integrity-verified (MD5 + SHA-1). In development, slices resolve from your local Maven repository. In production, the cluster is self-contained.

### Recovery Characteristics

When a node rejoins after failure, it:
1. Reconnects to peers and restores consensus state from cluster snapshot
2. Receives current deployment state from KV-Store
3. Re-resolves artifacts from DHT (cluster-internal, no external dependency)
4. Re-activates assigned slices

During recovery, requests to slices on the failed node are automatically retried on other nodes that host the same slice. No operator intervention required for single-node failures within quorum.

**Measured Times (URL Shortener demo, 5-node Forge on a single laptop):**

Even in this constrained single-machine environment (all 5 nodes sharing one CPU and one JVM), the numbers are:

| Metric | Time |
|--------|------|
| Cold start to quorum | 3.7 seconds |
| Quorum to leader elected | 0.26 seconds |
| Leader to blueprint deployed (2 slices, 5 nodes) | 4.1 seconds (includes JIT codec compilation) |
| **Total: process start to serving 5,000 req/s** | **~9.7 seconds** |

| Recovery Metric | Result |
|-----------------|--------|
| Leader kill to new leader elected | ~2ms (consensus-based, near-instant) |
| Replacement node join to routes available | ~150ms |
| Request failures during leader kill + node replacement | 124 / 314,758 (**0.039%**) |
| Failure window | ~150ms of 404s on replacement node during route sync |
| Steady-state latency at 5,000 req/s | 0.5ms |

Production deployments on dedicated hardware with multi-node clusters will have faster codec compilation (one-time cost) and lower absolute latencies.

### Cluster Formation

Nodes discover each other via a configured peer list. No external service registry needed. On startup:
1. Node connects to known peers
2. Requests state snapshot from the cluster
3. Restores local state from the most recent snapshot
4. Joins consensus and begins accepting work

Quorum requires `(N/2) + 1` nodes. A 5-node cluster tolerates 2 simultaneous failures.

---

## For Decision Makers

### What Problem Does Aether Solve?

Microservices solved the monolith scaling problem but created an operational one. A typical microservices deployment requires: container orchestration (Kubernetes), service mesh (Istio/Linkerd), API gateway, service discovery (Consul/etcd), distributed tracing (Jaeger), circuit breakers (Resilience4j), configuration management, and CI/CD pipelines for each service. The operational surface area grows with every new service.

Aether collapses this stack. The runtime provides service discovery, load balancing, retry logic, health checking, traffic routing, rolling updates, metrics, and scaling — all built in. Your team manages one artifact (the Aether cluster) instead of dozens of infrastructure components.

### Competitive Position

| Capability | Aether | Kubernetes + Microservices | Orleans | Akka Cluster |
|------------|--------|---------------------------|---------|--------------|
| Deployment unit | Slice (interface) | Container (full service) | Grain (actor) | Actor |
| Service discovery | Built-in | External (etcd/Consul) | Built-in | Built-in |
| Rolling updates | Built-in with traffic control | Manual or Argo/Flux | Manual | Manual |
| Scaling | Reactive + predictive | HPA (reactive only) | Manual | Manual |
| Retry/resilience | Built-in fabric | Service mesh required | Built-in | Built-in |
| Version compatibility | Built-in | Contract discipline | No built-in | No built-in |
| Operational tooling | CLI + API + Dashboard | kubectl + ecosystem | Limited | Limited |
| Observability | Built-in metrics + dashboard | Grafana/Prometheus stack | Limited | Lightbend telemetry |

**Where Aether is stronger:** deployment safety, version evolution, operational simplicity, latency (no sidecar proxy), scaling intelligence.

**Where Kubernetes is stronger:** ecosystem breadth, market trust, resource isolation via cgroups.

**Where Orleans/Akka are stronger:** ecosystem maturity, broader language support.

### Architecture, Not Infrastructure

Aether is not a Kubernetes replacement. Kubernetes manages containers — arbitrary, isolated workloads that can belong to any system. Aether manages **slices** — components of a single coherent system. This is a fundamental distinction:

- Slices trust each other (same system, same team)
- Slices share a JVM (efficient, low-latency invocation)
- The runtime understands the application topology (not just container health)
- Scaling decisions are application-aware (per-method latency, not just CPU)

This is closer to how a well-designed monolith works internally, except the runtime can distribute it across machines.

### Total Cost of Ownership

**Infrastructure reduction:** No service mesh, no API gateway, no service discovery system, no separate configuration management, no sidecar proxies. Each of these is a system to deploy, monitor, upgrade, and debug.

**Developer productivity:** Write business logic as plain Java interfaces. No HTTP client code, no retry policies, no circuit breaker configuration, no Kubernetes manifests. The Aether annotation processor generates everything from the slice interface.

**Operational simplicity:** One CLI, one management API, one dashboard. Rolling updates, scaling, and health monitoring are built in — not bolted on from five different vendors.

### License

Aether uses the **Business Source License 1.1 (BSL)**. In practice, this is nearly as permissive as open source:

**Permitted without any license:**
- Internal use within your organization at any scale
- Integration into applications you develop and operate
- Modification and creation of derivative works for internal use
- Evaluation, testing, and development purposes

**Single restriction:** You cannot offer Aether itself as a managed service (i.e., selling hosted Aether access to third parties).

**Automatic conversion:** On **January 1, 2030**, the license automatically converts to **Apache License 2.0** — fully open source, no restrictions.

This is the same licensing model used by MariaDB, CockroachDB, and other production-grade infrastructure. You can build your entire business on Aether today with zero licensing cost.

---

## Architecture Overview

### Core Components

```
                    ┌─────────────────────────────┐
                    │         AetherNode           │
                    │                              │
  HTTP ──────────► │  AppHttpServer                │
                    │    ↓                          │
                    │  HttpRouteRegistry            │
                    │    ↓                          │
                    │  SliceInvoker ──► SliceStore  │
                    │    ↓         (local path)     │
                    │  ClusterNetwork ──► Remote    │
                    │                    Node       │
                    │                              │
  Management ────► │  ManagementServer             │
                    │                              │
  Cluster ◄──────► │  RabiaEngine (Consensus)      │
                    │  KVStore (State Machine)      │
                    │  DHTNode (Artifact Storage)   │
                    │  TopologyManager              │
                    └─────────────────────────────┘
```

### Consensus: Rabia Protocol

Aether uses the **Rabia** crash-fault-tolerant consensus algorithm. Unlike Raft, Rabia has no designated leader for consensus — any node can propose, and agreement is reached through a two-round voting protocol with a fast path (super-majority in round 1 skips round 2).

**What's stored in consensus (KV-Store):**
- `SliceTargetKey` — desired deployment state (artifact, instance count, blueprint)
- `SliceNodeKey` — actual per-node slice state (LOADING, ACTIVE, etc.)
- `EndpointKey` — service discovery (which node hosts which method)
- `HttpRouteKey` — HTTP route-to-node mapping
- `AppBlueprintKey` — application blueprints
- Alert thresholds, controller configuration, dynamic aspect settings

**What bypasses consensus:** Metrics. The 1-second metrics pipeline uses direct leader-to-node messaging (MetricsPing/MetricsPong) with zero consensus overhead.

### DHT: Distributed Artifact Storage

Artifacts are stored in a consistent hash ring with configurable replication:
- **Production:** 3 replicas, quorum reads/writes (W=2, R=2)
- **Development (Forge):** Full replication across all nodes
- **1024 partitions**, 150 virtual nodes per physical node, MurmurHash3

### Request Routing

1. HTTP request arrives at any node
2. `HttpRouteRegistry` matches the longest prefix from consensus-replicated routes
3. If the target slice is local: direct invocation (no network hop)
4. If remote: forward to a node hosting the slice, selected by round-robin with failover
5. On failure: automatic retry with the failed node excluded from selection

### Event-Driven State Management

Everything is reactive. KV-Store changes emit notifications:
- `SliceTargetKey` put → `ClusterDeploymentManager` schedules deployment
- `EndpointKey` put → `EndpointRegistry` updates local routing cache
- `HttpRouteKey` put → `HttpRouteRegistry` updates local route table
- Node removed → `SliceInvoker` immediately retries in-flight requests on surviving nodes

No polling. No periodic reconciliation loops for core state.

---

## Current Capabilities (v0.19.0)

### Implemented

| Category | Feature |
|----------|---------|
| **Runtime** | Slice loading with ClassLoader isolation, lifecycle management (11 states), dependency materialization, route self-registration |
| **Invocation** | Local-first routing, remote invocation with KSUID correlation, retry with exponential backoff, node failover on departure |
| **Consensus** | Rabia CFT protocol, KV-Store state machine, leader election (local + consensus modes), state sync/snapshot |
| **Storage** | DHT with consistent hashing, quorum reads/writes, 3 replication modes, 64KB chunked artifact storage with integrity verification |
| **Deployment** | Blueprint-based (TOML), rolling updates with weighted routing, health-based guardrails |
| **Scaling** | Decision tree controller (1s reactive), TTM ONNX predictor (60s proactive), configurable per-blueprint thresholds and cooldowns |
| **Observability** | Push-based metrics (1s), per-method invocation tracking with P50/P95/P99, Prometheus export, WebSocket real-time dashboard, dynamic aspects, EMA latency smoothing |
| **Alerts** | Configurable thresholds per metric, persistent via consensus, warning + critical levels |
| **Management** | 37 CLI commands (single + REPL), 30+ REST endpoints, web dashboard |
| **Networking** | Netty-based cluster transport, topology management with quorum detection, reconnection with backoff |
| **Local Dev** | Forge (5-node single-JVM simulator), chaos operations, load generation, web dashboard |
| **Testing** | 81 E2E tests against 5-node Podman clusters, artifact repository tests, deployment lifecycle tests, chaos/recovery tests |

### E2E Test Coverage

The test suite validates real cluster behavior in Podman containers:
- Cluster formation and quorum establishment
- Slice deployment, scaling, and undeployment
- Blueprint apply with multi-slice topological ordering
- Multi-instance distribution across nodes
- Artifact upload, cross-node resolution, and integrity verification
- Leader failure and recovery
- Node restart with state restoration
- Orphaned state cleanup after leader changes

---

## Roadmap

### Near-Term: Resource Provisioning Framework

The current infrastructure services (cache, pubsub, lock, scheduler, secrets, config) provide in-memory implementations. These are transitioning to a **resource provisioning model**: Aether manages connections to external infrastructure (PostgreSQL, Redis, Kafka) as cluster resources, following the pattern already established with the database connector.

This enables a clean mix: Aether-native resources (like cluster-wide pubsub where low-latency matters) alongside external systems (like Kafka for durable streaming). The annotation processor foundation for this is already in place.

### Medium-Term

- **OpenTelemetry Integration** — span export for distributed tracing across slice invocations (request ID propagation foundation exists)
- **Dynamic Configuration via KV-Store** — expose runtime configuration in consensus, no restart required for changes
- **Dependency Lifecycle Management** — block unload while dependents are active, graceful degradation
- **Cloud Provider Adapters** — execute scaling decisions on real infrastructure (AWS, GCP, Azure)

### Longer-Term

- Formal delivery semantics specification (at-least-once/effectively-once contracts)
- Backpressure and admission control for overload protection
- Canary and blue-green deployment strategies
- LLM-based cluster management (Layer 3 controller)

See [development-priorities.md](internal/progress/development-priorities.md) for the full prioritized backlog.

---

## Getting Started

### Prerequisites

- Java 25+
- Maven 3.9.12+

### Build

```bash
git clone https://github.com/pragmaticalabs/pragmatica.git
cd pragmatica
mvn install -DskipTests -Djbct.skip=true
```

### Run Forge (Local Development)

```bash
cd aether/forge
mvn exec:java
```

Open `http://localhost:8888` for the dashboard. Deploy the URL Shortener example, generate load, kill nodes, and watch the cluster recover.

### Deploy to a Cluster

```bash
# Start nodes (each on a separate machine or container)
java -jar aether-node.jar --peers node1:6000,node2:6000,node3:6000

# Deploy your application
aether blueprint apply my-app.toml

# Monitor
aether status
aether metrics
```

---

*Pragmatica Aether is developed by Pragmatica Labs. Licensed under BSL 1.1 — free for all use except managed service offerings. Converts to Apache 2.0 on January 1, 2030.*
