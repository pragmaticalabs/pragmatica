# Known Limitations & Current Scope

> **This is the single source of truth for Aether's current scope.** Where any other document (security docs, trust-model, feature-catalog, READMEs) needs to state a boundary, it **references this page** rather than restating it. This is a living scope statement — updated as each rc closes a boundary.

Each entry below is a **deliberate boundary with a rationale**, not an apology. A runtime that states its limits plainly is more credible, not less — the boundaries are where an evaluator should aim their diligence, and where the roadmap is pointed. Consistency and partition guarantees are documented separately and precisely in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md) and [`guarantees.md`](guarantees.md); this page states *scope* (what the system is and is not for today), those state *guarantees* (what each operation provides).

## Scope at a glance

| Boundary | Current state | Rationale | Tracking |
|----------|---------------|-----------|----------|
| Release maturity | rc series toward GA — **not production-ready** | GA is gated on the scale-validation epic | #365 |
| Trust model | Single trust domain (self-signed mTLS, shared cluster secret) | No federated identity yet; one operator, one cluster secret | — |
| Geography | Single-region only | No cross-region data-plane DR designed | #303-class |
| Overload / backpressure | Not yet characterized; system-level rate limiting deferred | Deferred **pending performance numbers** — see below | #365, #200 |
| Hierarchical scale | Multi-community barrier designed, **under validation** | The one barrier Aether has not yet crossed | #367 |
| Performance numbers | Single-machine / simulated | Multi-node benchmark not yet run | #365 |

## Release maturity — rc series toward GA

Aether is in its **release-candidate series moving toward GA**, and makes **no claim to be production-ready**. GA is not a calendar milestone here — it is gated on the performance-and-scale validation epic (#365), whose headline artifact is the multi-community barrier run (#367). Treat the rc series as suitable for evaluation, development, and controlled pilots, not unattended production traffic. This page shrinks as each rc closes a boundary below.

## Single trust domain

Aether runs within **one trust domain**:

- Node-to-node mTLS uses a CA **derived from a shared cluster secret** via HKDF — every node derives the same CA, so mutual trust is established without external PKI (see [`../architecture/10-security.md`](../architecture/10-security.md)). Certificates are **self-signed**; there is no external-CA integration.
- Slice-to-slice invocation is within the same trust domain (no additional auth between slices).
- **Federated identity is not built** — no cross-organization identity, no per-tenant trust separation at the transport layer.

**Rationale:** a single cluster secret is the smallest correct trust primitive for a single-operator cluster; federated identity is a distinct problem deferred until the single-domain story is fully hardened. Cloud-CA adapters (AWS ACM, GCP Certificate Manager, Azure Key Vault) exist for *certificate material* but do not change the single-trust-domain boundary.

## Single region

Aether is **single-region**. There is **no cross-region data-plane disaster recovery** — no cross-region replication of the KV plane, streams, or DHT, and no cross-region failover. The core consensus tier assumes intra-region latencies (Rabia is all-to-all per round). Multi-region is not yet designed; run one cluster per region and handle cross-region concerns above Aether.

## Overload and backpressure not yet characterized

Aether's behavior **under sustained overload is not yet characterized**, and **system-level rate limiting is intentionally deferred**. This is a sequencing decision, not an oversight: a rate limiter tuned before the system's real throughput/latency envelope is measured would encode guessed numbers. The limiter is deferred **pending the performance numbers** the scale-validation epic (#365) produces.

**Recommended meanwhile: apply application-level rate limiting** in front of or inside your slices. Per-HTTP-route rate limiting is a planned platform feature (#200, catalog row 200), not a current one.

## Multi-community / hierarchical scaling barrier — under validation

Aether's two-layer topology (a 5–9-node Rabia core plus SWIM-gossip worker communities that scale horizontally) is **designed and wired**, but the **multi-community barrier has not yet been crossed under load and chaos**. The first crossing — a 3-node core with 3×3 worker communities, chaos aimed at the core — is **under validation** (#367) and is the headline GA gate.

We neither claim it works nor hide that it is the open frontier. Until #367 produces its three outputs, the validated topology is a **single-community** deployment on a core of 5–9 nodes. Specifically pending:

- **Worker-community dissolve-on-core-isolation** — the partition contract in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md) is proven today only at the single-tier core; its hierarchical proof is gated on #367.
- **The dissolve-timeout (`split_timeout`) tuning curve** for hierarchical topologies — the recommended default for multi-community clusters comes out of this run.
- **Core coordination-load slope** at 1→2→3 communities — the real "how far does it scale" answer, and whether the hierarchy has a ceiling to know about before GA.

### The scaling numbers (single source)

Two distinct scale dimensions must not be conflated, and each has its own validation gate:

| Dimension | Number | Status |
|-----------|--------|--------|
| **Single community at scale** — one worker community at its node cap | **~100 nodes** (design target) | Designed; **pending validation** by the single-community node-cap sweep (#365 / #366) — not yet a proven ceiling |
| **Multi-community / hierarchical seam** — total reach across communities | An **output of #367**, not pre-committed | **Pending validation** by the 3×3 barrier sweep (#367) |
| **Core tier** — Rabia consensus members | **5–9 nodes**; tolerates ⌊(N−1)/2⌋ simultaneous losses | Bounded by Rabia's all-to-all O(N²) cost (established, not a target) |

The **~100 per-community figure is a design target under validation, stated here once**: it is the number the single-community sweep is built to confirm, not a measured cap, and the consistency-lens rule forbids presenting it as proven until the sweep lands. Other docs (e.g. [`../architecture/08-scaling.md`](../architecture/08-scaling.md)) **reference this row rather than restating the number**, so it can never drift. When the sweeps produce measured ceilings, they replace the target here — in one place.

## Performance numbers are single-machine / simulated

Every performance figure Aether currently publishes — the cold-start, re-election, failover, throughput, and latency numbers in [`../architecture/00-overview.md`](../architecture/00-overview.md#performance-characteristics-v0200) and the demos — was measured on a **single machine with a simulated (Forge) cluster**, not a multi-node deployment. They are labeled as such at the source and are useful for relative comparison, not as production capacity planning. The multi-node benchmark that replaces them is part of #365; when it lands, these numbers are superseded, not appended.

## Consistency and durability boundaries

The precise per-operation consistency, durability, and delivery guarantees — including where the honest answer is *eventual*, *best-effort*, or *not crash-durable* — are documented in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md) and, authoritatively with `file:line` mechanisms, in [`guarantees.md`](guarantees.md). The scope-relevant boundaries an evaluator should know up front:

- **Default persistence is in-memory / snapshot-only.** A simultaneous full-cluster crash loses KV + DHT state since the last lifecycle snapshot. Deliberate (in-memory replicated), tracked under the storage-durability epic (#349 / #383).
- **DHT-backed routing/endpoint state is eventual and not crash-durable.** Slice-node, HTTP-route, and endpoint keys were migrated from consensus (CP, quorum-durable) to the DHT `ReplicatedMap` (eventual, W=R=1, in-memory) — reads may be stale across nodes and are lost on full restart (#384).
- **Default streams are RF=1 and one-disk-deep.** Crash-durable via per-partition WAL, but not resilient to disk loss or owner failover unless `min-sync-replicas ≥ 2` is configured (#262).
- **The production streaming substrate is all-RAM.** Retention, cold-tier reads, and STRONG-stream creation are gated on the streaming-persistence plan (catalog rows 179/180/181/185/190/191); DEFAULT streams are what a stock node creates today. Their delivery has **three regimes**: (1) **while the consumer runs** — at-least-once: the cursor advances only after a successful callback and retries failures; (2) **across a restart with the log intact** — the per-partition WAL replays the log (A6, `StreamCrashDurabilityTest`), but there is **no automatic cursor resume**: an app `StreamAccess` consumer that explicitly commits and re-seeks (`committedOffset()`) gets **bounded-window** redelivery (disk-durable cursor, writable data dir), while a non-committing app, every **system consumer** (`SystemStreamFactories` → `none()`), and Forge / in-memory nodes **replay from offset 0** (duplicate-heavy, still at-least-once, not loss); (3) **only where the log itself is lost** — RF=1 owner failover or node replacement onto a fresh disk (the one-disk-deep caveat above) — a published event is delivered **zero times: at-most-once end-to-end**, avoidable with `min-sync-replicas ≥ 2`. Mechanism detail in [`guarantees.md`](guarantees.md) §4; per-operation row in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md).
- **Pub/sub is at-most-once, unordered, best-effort** — never persisted; a subscriber down at publish time misses the message permanently.
- **Durable-entity fenced/durable guarantees are planned, not wired** into a deployed slice.

## Related Documents

- [../architecture/14-consistency-and-partitions.md](../architecture/14-consistency-and-partitions.md) — the consistency & partition-behavior contract (shares terminology with this page)
- [failure-almanac.md](failure-almanac.md) — operator catalog of failure modes, surfaces, and recovery budgets (companion to this page)
- [guarantees.md](guarantees.md) — authoritative per-operation guarantees, traced to `file:line`
- [feature-catalog.md](feature-catalog.md) — capability inventory with Complete/Partial/Planned status
- [../architecture/10-security.md](../architecture/10-security.md) — trust domain and mTLS mechanism
- [../architecture/05-worker-pools.md](../architecture/05-worker-pools.md) — two-layer topology and worker scaling
