# Resource and Isolation Model

An Aether node is a **single JVM**. Slices co-located on a node share one heap, one garbage collector, and one scheduler / event loop. The consequence is sharp and deliberate: **hard per-slice resource isolation is impossible in-process** — a heavy-allocating slice's GC pauses land on *every* slice sharing that node. This page states the isolation model as an operator contract so that consequence is read up front, not discovered under load.

The neighboring [11-slice-container.md](11-slice-container.md) covers isolation of *code* (per-slice ClassLoaders, dependency materialization). This page covers isolation of *runtime resources* (heap, GC, threads) — a different axis with a different, weaker guarantee.

## The single-JVM consequence

Per-slice ClassLoaders keep slices' **classes and dependencies** separate, so two slices can load conflicting library versions without collision. They do **not** partition the JVM's runtime resources:

- **Heap and GC are shared.** A slice that allocates heavily drives GC for the whole node; its pause times are paid by every co-located slice. There is no per-slice heap quota.
- **The scheduler / event loop is shared.** A slice that saturates worker threads or blocks the event loop starves its neighbors of execution time.

Code isolation is real; in-process resource isolation is not. Everything below follows from that.

## Two-tier isolation model

Aether offers isolation at two tiers, with honestly different strengths.

### Tier 1 — soft isolation, in-JVM

Soft isolation makes a resource hog **survivable and visible**, not impossible.

- **Visibility is wired.** The runtime collects **per-slice metrics** — per-artifact CPU, latency, invocation counts, error rate — (`PerSliceMetrics`, `InvocationMetricsCollector.collectPerSliceMetrics()`), broadcast per node (`WorkerMetricsAggregator`, #423) and surfaced through the management API and the per-slice scaling snapshot (#425). A misbehaving slice shows up in the data; an operator can see the hog.
- **Bounded per-slice work is a recommended pattern, not a runtime-enforced limit.** Keeping one slice from monopolizing threads or the event loop — bounded concurrency, capped executor share, bounded work-queues — is applied by the slice author, not enforced by the node. This is the **same per-slice-bounded-work idea** that a future system-level backpressure layer would enforce centrally; that layer is deliberately deferred pending performance numbers (see [`../reference/known-limitations.md`](../reference/known-limitations.md#overload-and-backpressure-not-yet-characterized)). Until then, bounded work is a slice-authoring discipline.

> **Aether does not cap a slice's heap or thread share in-process.** Tier 1 is visibility plus author-applied bounded work — do not read it as a scheduler-level guarantee the runtime enforces.

### Tier 2 — hard isolation, by placement

When a slice needs **guaranteed resources** or **must not be GC-coupled** to a noisy neighbor, give it **its own node / JVM**. A slice placed alone on a node has that node's entire heap, GC, and scheduler to itself — genuine isolation, because the isolation boundary is the JVM process, which Aether already controls through placement:

- **`PlacementPolicy`** — `CORE_ONLY`, `WORKERS_PREFERRED`, `WORKERS_ONLY`, `ALL` — steers where a slice's instances land.
- **Placement hints** — `ZoneHint`, `HostGroupHint`, `AffinityHint`, `AntiAffinityHint` (feature-catalog row 187) — express co-location and anti-co-location constraints.
- **Per-slice `min`/`max` instances** and **dedicated worker communities** let an operator carve out capacity a noisy slice cannot touch.

Hard isolation is a **placement decision the operator already has the tools to make** — not a feature that must be built.

## This is a recommendation, not a runtime feature

To be unambiguous: **Aether does not enforce per-slice resource limits inside a JVM.** It does not sandbox a slice's heap, cap its CPU, or throttle its allocation rate in-process. The isolation model is (1) *see* contention via per-slice metrics, and (2) *avoid* it by placement. Anything stronger than that is placement, full stop.

## Operator decision rule

| Situation | Isolation strategy |
|-----------|--------------------|
| Slices are cooperative; GC coupling is tolerable; you want density | **Co-locate** — rely on soft isolation (per-slice metrics to watch for a hog + author-side bounded work) |
| A slice needs guaranteed resources, or must not share GC with a noisy neighbor, or is latency-critical | **Isolate by placement** — give it its own node / JVM (or a dedicated community) via `PlacementPolicy` + placement hints |

Start co-located and let per-slice metrics tell you when a slice has earned its own node; promote to placement isolation when the data (or the SLA) says the shared heap is the problem.

## Related Documents

- [11-slice-container.md](11-slice-container.md) - Per-slice ClassLoader isolation (code, not runtime resources)
- [05-worker-pools.md](05-worker-pools.md) - Communities and placement — the substrate for hard isolation
- [08-scaling.md](08-scaling.md) - Per-slice instance scaling (`min`/`max`) and cluster scaling limits
- [07-observability.md](07-observability.md) - The metrics pipeline behind per-slice visibility
- [../reference/known-limitations.md](../reference/known-limitations.md) - Overload / backpressure deferral (why bounded work is a pattern, not yet a runtime layer)
