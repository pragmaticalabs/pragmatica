# Known Limitations & Current Scope

> **This is the single source of truth for Aether's current scope.** Where any other document (security docs, trust-model, feature-catalog, READMEs) needs to state a boundary, it **references this page** rather than restating it. This is a living scope statement — updated as each rc closes a boundary.

Each entry below is a **deliberate boundary with a rationale**, not an apology. A runtime that states its limits plainly is more credible, not less — the boundaries are where an evaluator should aim their diligence, and where the roadmap is pointed. Consistency and partition guarantees are documented separately and precisely in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md) and [`guarantees.md`](guarantees.md); this page states *scope* (what the system is and is not for today), those state *guarantees* (what each operation provides).

## Scope at a glance

| Boundary | Current state | Rationale | Tracking |
|----------|---------------|-----------|----------|
| Release maturity | rc series toward GA — **not production-ready** | GA is gated on the scale-validation epic | #365 |
| Node-binary version skew | No version field on the join/handshake `Hello`; no mismatch policy; no rolling node-binary-upgrade design | Pre-GA gap — the window to add the field closes at GA (an old node can't parse an extended `Hello`) | #666 |
| Trust model | Single trust domain (self-signed mTLS, shared cluster secret) | No federated identity yet; one operator, one cluster secret | — |
| Geography | Single-region only | No cross-region data-plane DR designed | #303-class |
| In-JVM slice isolation | Dependency versions only — no per-slice resource limits, no slice-to-node pinning | The fault boundary is the node; hard isolation is tier-level placement by construction | #614 |
| Overload / backpressure | Not yet characterized; system-level rate limiting deferred | Deferred **pending performance numbers** — see below | #365, #200 |
| Hierarchical scale | Multi-community barrier designed, **under validation** | The one barrier Aether has not yet crossed | #367 |
| Performance numbers | Single-machine / simulated | Multi-node benchmark not yet run | #365 |

## Release maturity — rc series toward GA

Aether is in its **release-candidate series moving toward GA**, and makes **no claim to be production-ready**. GA is not a calendar milestone here — it is gated on the performance-and-scale validation epic (#365), whose headline artifact is the multi-community barrier run (#367). Treat the rc series as suitable for evaluation, development, and controlled pilots, not unattended production traffic. This page shrinks as each rc closes a boundary below.

## Node-binary version skew — no join-time version check yet

The node-to-node membership handshake (`Hello`) carries no version field today, and there are no
documented codec-evolution rules for the gossip/consensus wire format — a real gap an internal
design-completeness review flagged by name on 2026-06-11, and which stayed untracked until now.
**Tracked as #666**, deliberately scoped minimal and pre-GA: add a version field to `Hello` and a
join-time mismatch policy (refuse or warn-and-degrade — the decision itself is part of the
ticket; refuse is the conservative default candidate). Version *negotiation*, codec-evolution
rules, and mixed-node-binary-version rolling-upgrade support are explicit non-goals of #666 and
remain unscheduled. Timing is deliberate, not arbitrary: a version field cannot be retrofitted
cleanly after GA, because an old node cannot parse an extended `Hello` — the window to add the
field at all closes at GA, even if the policy stays detection-only. This is a
**tracked-not-designed boundary**: #666 closes the detection gap, not the cross-version
compatibility story. See
[`versioning-and-compatibility.md`](versioning-and-compatibility.md#rolling-upgrades-and-node-version-skew)
for the fuller technical writeup and the operator-facing fallback (canary-wait rolling upgrade,
no rc-skipping) that applies until it lands.

**Worked example of why this bites — route security values (rc4, #763/#866).** The persisted
`NodeRoutesValue.RouteEntry.security` field is a plain `String`, and rc4 added a new value to its
domain: `"UNSPECIFIED"`, meaning "this route declared no security stance, so apply the node's
global `security_mode`". A node running an older build has no case for that value, falls through
its unrecognized-value path, and resolves it to `api_key`. The direction is fail-closed — the route
demands a credential rather than becoming public — but the *behaviour differs between nodes in the
same cluster*: the old node enforces `api_key` on a route whose owner declared nothing, while a new
node applies whatever `security_mode` says. **This is not a claim that the change is safe to roll
through a live cluster.** It is an illustration of the gap above: a string-typed wire field can gain
a value with no version check anywhere to notice, and the only reason this instance is not a
security regression is the direction its fallback happens to point. The supported upgrade path
remains full-cluster stop, upgrade, start.

## Single trust domain

Aether runs within **one trust domain**:

- Node-to-node mTLS uses a CA **derived from a shared cluster secret** via HKDF — every node derives the same CA, so mutual trust is established without external PKI (see [`../architecture/10-security.md`](../architecture/10-security.md)). Certificates are **self-signed**; there is no external-CA integration.
- Slice-to-slice invocation is within the same trust domain (no additional auth between slices).
- **Federated identity is not built** — no cross-organization identity, no per-tenant trust separation at the transport layer.

**Rationale:** a single cluster secret is the smallest correct trust primitive for a single-operator cluster; federated identity is a distinct problem deferred until the single-domain story is fully hardened. Cloud-CA adapters (AWS ACM, GCP Certificate Manager, Azure Key Vault) exist for *certificate material* but do not change the single-trust-domain boundary.

## Single region

Aether is **single-region**. There is **no cross-region data-plane disaster recovery** — no cross-region replication of the KV plane, streams, or DHT, and no cross-region failover. The core consensus tier assumes intra-region latencies (Rabia is all-to-all per round). Multi-region is not yet designed; run one cluster per region and handle cross-region concerns above Aether.

## In-JVM slice isolation is version isolation, not a fault boundary

Per-slice classloaders isolate **dependency versions**; the **cluster** isolates failures. Co-located slices share one JVM — heap, GC, native memory — so Aether does not enforce per-slice resource limits in-process, and **pinning a slice to a specific node is not supported**: the blueprint has no placement key, and `PlacementPolicy` (`CORE_ONLY`, `WORKERS_PREFERRED`, `WORKERS_ONLY`, `ALL`) is the entire slice-placement vocabulary. A slice needing hard resource isolation gets it **by construction** — a placement tier whose node pool contains exactly the nodes meant for it, with every other slice kept out of that tier. Mechanics and rationale: [`../architecture/15-resource-and-isolation-model.md`](../architecture/15-resource-and-isolation-model.md).

**Rationale:** the node is already the unit the runtime replicates, retries, and rebalances around; duplicating a fault boundary inside the JVM buys little and costs a scheduler. A per-slice node pin is a possible future feature; #614 records it as explicitly out of scope today.

## Overload and backpressure not yet characterized

Aether's behavior **under sustained overload is not yet characterized**, and **system-level rate limiting is intentionally deferred**. This is a sequencing decision, not an oversight: a rate limiter tuned before the system's real throughput/latency envelope is measured would encode guessed numbers. The limiter is deferred **pending the performance numbers** the scale-validation epic (#365) produces.

**Recommended meanwhile: apply application-level rate limiting** in front of or inside your slices. Per-HTTP-route rate limiting is a planned platform feature (#200, catalog row 200), not a current one.

## Multi-community / hierarchical scaling barrier — under validation

Aether's two-layer topology (a 5–9-node Rabia core plus SWIM-gossip worker communities that scale horizontally) is **designed and wired for the steady state** — communities form, elect governors, and receive placed work — but its **partition response only landed in #590**, and the **multi-community barrier has not yet been crossed under load and chaos**. The first crossing — a 3-node core with 3×3 worker communities, chaos aimed at the core — is **under validation** (#367) and is the headline GA gate.

> **Correction, 2026-08-15 (#590).** This section previously described dissolve-on-core-isolation as
> awaiting *proof*. That was wrong in a way worth recording: there was no mechanism to prove. Neither
> side of the detection existed. A community could not notice it had lost the core (`writeDissolved()`
> fired only when a community shrank to zero members, never on partition), and the core could not
> notice it had lost a community — its "observed live membership" read
> `GovernorAnnouncementValue.memberCount`, a field the community writes about ITSELF, which under
> partition freezes at its last healthy value instead of expiring. Both sides were blind, and the
> wording implied a built mechanism waiting on a test run. The mechanism now exists (see below); the
> end-to-end proof genuinely is still pending.

We neither claim it works nor hide that it is the open frontier. Until #367 produces its three outputs, the validated topology is a **single-community** deployment on a core of 5–9 nodes. Specifically pending:

- **Worker-community dissolve-on-core-isolation** — the mechanism landed in #590 and is **unit- and
  mutation-verified, not integration-verified**. The core broadcasts `ClusterSyncPing` cluster-wide and
  every live node answers; that one exchange now carries liveness both ways. A worker that has seen no
  term-accepted ping for `timeouts.cluster.core_absence` (default 10s) dissolves LOCALLY — it never has
  to write to the core, which is the point, since announcing dissolve normally means a consensus write
  the isolated community cannot complete. The core independently stops counting a member it has had no
  pong from for `timeouts.cluster.community_absence` (default 20s) and re-places the community's slices.
  `core_absence < community_absence` is refused at config load, and that inequality is the whole
  no-double-active guarantee: the community stops serving before the core hands its work to anyone else.
  `[verified: CoreAbsenceDetectorTest 16/16, ClusterDeploymentStateCommunityFsmTest$CoreObservedAbsence
  6/6, ClusterTimeoutsAbsenceOrderingTest 7/7 — each mutation-checked: removing the cold-start latch or
  the observed-absence read turns exactly the guarding tests red]`
  `[design intent — unverified: the ORDERING under a real partition. No in-JVM harness can produce one —
  Forge is single-JVM and cannot sever the cluster network — so "the community stopped serving before
  the core re-placed its work" is believed, not demonstrated, until a docker/cloud partition run.
  Gated on #367.]`
- **The dissolve-timeout tuning curve** for hierarchical topologies — the recommended
  `core_absence`/`community_absence` pair for multi-community clusters comes out of this run. The
  defaults are multiples of the 1s ping cadence, chosen to clear a leader-election gap (pings originate
  from the leader, so an election is a legitimate silence), not measured against a real partition.
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
- **Default streams are RF=1 and one-disk-deep.** Crash-durable via per-partition WAL, but not resilient to disk loss or owner failover unless `min-sync-replicas ≥ 2` is configured (#262). **Caveat on that remedy, measured 2026-08-16:** until then `min-sync-replicas` was enforced only when the publishing node happened to hold the partition ring locally — a publish forwarded to the partition owner was acked on the owner's local fsync alone, so a stream configured for 2 effectively ran at 1. `02y-stream-crash` on a 5-node cluster ACKED 80/80 events and then lost two whole partition logs (41 acked events) to a single SIGKILL. The barrier now sits on the owner in `StreamForwardHandler.onPublishForward`, covering both write paths `[verified: unit + mutation]` — **not yet integration-verified**. Independently, a replica starved by `reshuffle_concurrency` pacing may never reach in-sync, in which case `min-sync-replicas ≥ 2` is unsatisfiable and publishes now FAIL rather than silently acking at a weaker guarantee `[design intent — unverified]`. Slot tenure was previously UNBOUNDED — a stalled backfill held its slot forever, measured at 4m55s with zero releases while the partitions behind it were lost — and is now bounded: a slot held past a tenure bound while partitions are queued is preempted, the backfill continuing unslotted `[verified: unit + mutation — StreamReshuffleLifecycleTest$StalledSlotPreemption]`. The bound itself is now operator-tunable via `[streaming] reshuffle_concurrency`, which until 2026-08-16 was a hard-coded constant that the error message nonetheless named as if it were a knob.
- **The production streaming substrate is all-RAM.** Retention, cold-tier reads, and STRONG-stream creation are gated on the streaming-persistence plan (catalog rows 179/180/181/185/190/191); DEFAULT streams are what a stock node creates today. Their delivery has **three regimes**: (1) **while the consumer runs** — at-least-once: the cursor advances only after a successful callback and retries failures; (2) **across a restart with the log intact** — the per-partition WAL replays the log (A6, `StreamCrashDurabilityTest`). Cursor resume differs by consumer kind: an app `StreamAccess` consumer that explicitly commits and re-seeks (`committedOffset()`) gets **bounded-window** redelivery (disk-durable cursor, writable data dir); a **declarative `[streams.X]` consumer** gets consensus-KV-backed checkpointing (`ClusterCursorStore`, resume = `max(local, cluster)`) that also survives an ownership change, degrading to local-only on a swallowed consensus-write failure; a non-committing app, every **system consumer** (`SystemStreamFactories` → `none()`), and Forge / in-memory / unwritable-mount nodes **replay from offset 0** (duplicate-heavy, still at-least-once, not loss); (3) **only where the log itself is lost** — RF=1 owner failover or node replacement onto a fresh disk (the one-disk-deep caveat above) — a published event is delivered **zero times: at-most-once end-to-end**, avoidable with `min-sync-replicas ≥ 2`. Mechanism detail in [`guarantees.md`](guarantees.md) §4; per-operation row in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md).
- **Pub/sub is at-most-once, unordered, best-effort** — never persisted; a subscriber down at publish time misses the message permanently.
- **Durable-entity CRUD + reads are wired**, not planned: `PartitionFencedDurableEntity` has been node-wired since #352 shipped, with fsync-before-ack (`durablyLog` → `PartitionWal` group-commit), RF=3 replication by default, and non-owner calls forwarded to the committed owner (#596) rather than failing. Both read consistencies are live — `BOUNDED_STALE` (offset-bounded, replica-forwarding) and `LINEARIZABLE` (owner-routed, no-op-round epoch fence; a given call can still return `EntityError.LinearizableUnavailable` if no barrier is registered for that partition — a freshness-vs-safety asymmetry, not dormancy). **Still genuinely planned:** `entity.timer`, `workflow.*`, and `saga.*`. Mechanism detail in [`guarantees.md`](guarantees.md) §6.
- **Storage at-rest encryption (`[storage.encryption]`, #253) has no plaintext-to-encrypted migration path.** Enabling `encrypted = true` (or `streams_encrypted = true`) is new-instance/fresh-data only in rc4: a local-disk directory already holding unmarked plaintext blocks refuses to boot as encrypted rather than mixing plaintext and ciphertext; a DHT tier (no directory to scan) instead fails closed per block on read. Re-encrypting existing blocks under a rotated key (including on tier demote/promote) is also not shipped — old keys stay resolvable for reads instead. **The `content` storage instance cannot be encrypted at all**, regardless of configuration: `AetherNode` always provisions it via a separate, keyring-less factory path that bypasses the config/keyring wiring every other instance goes through — the same structural gap that already excludes `content` from demotion/GC (#783). The auto-synthesized default `artifacts` instance (used only when no explicit `[storage.artifacts]` section is configured) tracks node-wide keyring presence instead: it is encrypted whenever `[storage.encryption]` is configured with a resolvable keyring, plaintext otherwise. The `streams` instance's write-ahead log is excluded from `streams_encrypted` coverage regardless — it is a separate directory from the segment-block tiers encryption gates. See [`configuration.md`](configuration.md#storage-encryption-configuration-253) for the full coverage/exclusion list, including why this is unrelated to the already-dead `[streams.X].encryption-key-id` blueprint key (#576).
- **A previously-encrypted storage tier that boots with encryption disabled (or `[storage.encryption]` removed) is a new boot-failure mode, not a silent downgrade to plaintext access.** Both the local-disk and DHT tiers write a `.encryption-enabled` marker the first time encryption is enabled over them; if that marker is present and no keyring resolves for the instance, boot refuses with `EncryptedTierRequiresKeyring` rather than handing back framed `AEC1...` bytes as if they were the block's real content. This covers both directions on the per-instance disk/DHT path (`StorageFactory.createOne`), and the disk side of the built-in `streams` segment tiers (`defaultStreamStorage`'s no-keyring branch already refused on the disk marker before this round). Recovery: restore `[storage.encryption]` with the same key id still resolvable in `[storage.encryption.keys]`, or migrate to a fresh, unmarked directory or DHT namespace (tracked separately — #831). **Known gap:** the `streams` DHT namespace (`stream-segments`, used when a stream's segment tiers fall back to memory+DHT) has neither the marker write nor the reverse-direction check — only the per-instance DHT path (`<name>-blocks`) does; tracked separately as #849.

## Related Documents

- [../architecture/14-consistency-and-partitions.md](../architecture/14-consistency-and-partitions.md) — the consistency & partition-behavior contract (shares terminology with this page)
- [failure-almanac.md](failure-almanac.md) — operator catalog of failure modes, surfaces, and recovery budgets (companion to this page)
- [guarantees.md](guarantees.md) — authoritative per-operation guarantees, traced to `file:line`
- [feature-catalog.md](feature-catalog.md) — capability inventory with Complete/Partial/Planned status
- [../architecture/10-security.md](../architecture/10-security.md) — trust domain and mTLS mechanism
- [../architecture/05-worker-pools.md](../architecture/05-worker-pools.md) — two-layer topology and worker scaling
