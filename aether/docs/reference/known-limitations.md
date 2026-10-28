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
| Transport certificate rotation | Renewal restarts the node's QUIC server on the same port: connections the node accepted drop and their dialers reconnect; connections the node dialed keep the old certificate; a restart that cannot rebind the port leaves the node not accepting inbound connections | Rotation replaces the TLS material by rebuilding the server, not by swapping the TLS context in place | — |
| State transfer size | A whole-snapshot state transfer must fit one transport frame: 32 MiB less 64 KiB. Above it adding a voter is refused with `STATE_TRANSFER_TOO_LARGE` and a catch-up response is answered with `SyncRejected` after an ERROR log | The cluster transport frames messages with a 32 MiB length-field cap; state transfer is one message | — |
| Transport offline buffer | A peer that is not connected has its frames held in a memory-only buffer of at most 10,000 frames, each for at most the offline-buffer cap (30 s by default); beyond either bound frames are dropped, oldest first | Bounded memory; the layer above owns end-to-end delivery (see [Guarantees](guarantees.md), row 32) | — |
| Stream reference counts | One count per stream, no per-holder record; a recovery re-drive can leave one reference too many | Per-holder reference entries are the structural fix, scheduled for rc5 | #2160 |
| Streams and durable entities | Seven stated limits: a lagging consumer stalls on `CursorExpired`; sealed-tier retention is node-wide 24 h; sealing writes one segment per evicted event; default memory budget fits ~50 partition rings per node; replication is unbatched; a retried entity `update` that missed its barrier applies twice; the transactional cursor path is unwired | Found by a source audit at `acd3357ccc`; fixed by one spec-first overhaul, not patches — see below | #2201 |
| No-majority network cut | A cut that leaves no group of cores with a majority halted every core in cuts of 20, 25 and 30 s halted every core (five cores, every core isolated, Ember in-process; longer cuts were not run); consensus state is memory-only and is lost | Terminal removal of a fenced node plus memory-only consensus; recovery is the backup restore | [runbook](../operators/runbooks/incident-response.md#no-majority-partition--every-core-halted) |
| Re-running `bootstrap` with a live cluster's name | A fresh bootstrap refuses when the registry, a local bootstrap state or (cloud sources only) a labelled provider instance shows the name; SSH and Docker hosts, a cluster known only to another workstation, and two concurrent bootstraps are outside the check | The check reads three sources before the first phase and takes no lock; there is no override flag | #2213 |

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

## Transport certificate rotation restarts the QUIC server

Certificate renewal (at 40% of remaining validity, about day 2.8 of a 7-day certificate) does not swap the TLS context inside a running server. It stops the node's QUIC server, shuts down the server's event loop group, builds a new server and client with the new certificate and binds the same UDP port again. Consequences an operator can see:

- Every connection the node **accepted** is closed with the server; the dialing peers reconnect through the normal reconnect path (each is a new handshake).
- Connections the node **dialed** are not drained: the old client is not closed, so those links keep the old certificate until they end.
- If the new server cannot bind the port, the rotation fails and the node is left without a listening server, so it accepts no inbound connections until it is restarted.
- Because the cadence is about every 2.8 days per node, the reconnect is routine, not exceptional.

See [TLS Certificate Management](../operators/tls-certificates.md) for the renewal schedule.

## State transfer is bounded by the transport frame (32 MiB)

A whole-snapshot `SyncResponse` must fit in one transport frame, which is capped at 32 MiB; after a reserve of 64 KiB the usable size is 32 MiB less 64 KiB. Above it, adding a voter is refused up front with `STATE_TRANSFER_TOO_LARGE`, and a catch-up response is retried without pending state and, failing that, answered with `SyncRejected` after an ERROR log. The refusal is typed and visible. A KV state that grows past the bound needs the bound raised or the state split before a joiner can catch up.

## Transport offline buffer is bounded and memory-only

While a peer's link is down, frames addressed to it wait in a per-peer buffer in memory. It holds at most 10,000 frames; each frame is held at most the offline-buffer cap (`offline_buffer_cap`, 30 s by default, taken from the cluster timeout configuration), and a request frame with a shorter caller deadline expires with it. When the buffer is full the oldest frame is dropped. Frames marked `NoOfflineBuffering` (entity forwards, Rabia `NewBatch`) are never buffered. A node restart loses the buffer. See [Guarantees](guarantees.md), summary row 32.

## A retired NodeId is remembered on each core's disk, not by the cluster

When a different process presents a boot token for a NodeId a core already knows, the core retires that NodeId for good (terminal removal: recover with a fresh NodeId). Each core also writes the retirement to its **identity ledger** before it answers the refusal (over QUIC and over SWIM), and loads the ledger before it admits anyone after a restart. A retirement therefore survives the restart of every core that held it, which the in-memory registry alone did not (#2429).

What the ledger does and does not give you:

- **Location.** `identity/<node-id>/ledger`, next to the artifacts `disk_path` (the same convention as the stream data directory). It must sit on the durable `${data.dir}` volume. A `disk_path` you write in the `[storage.artifacts]` section (also when it equals the default) must be usable on every boot: if the ledger directory cannot be created, written or forced to disk, with or without a ledger file already there, the node does not start, and says why. With the built-in default path the node starts without the protection instead (WARN log, `identity-ledger-not-durable` event, `identity_ledger_durable` gauge at 0).
- **Only retirements are kept**, not the boot tokens of live peers. A restarted node carries a new token, so remembering old ones would make a cold restart of the whole cluster refuse itself.
- **Losing every core's disk** with its memory is a new cluster: nothing is left to remember the retirement, which is also how a cluster behaved before the ledger. Losing one core's **ledger file** (the directory still exists) leaves that core *wiped*: it cannot tell what it retired, so it admits no new peer (`identity-ledger-not-durable`) until it has heard from a **majority of the other configured core seats**. The merge rides the QUIC Hello in both directions: a core whose ledger is loaded and not wiped offers its retirements (an empty offer from a legitimate peer counts like any other), the wiped core keeps each offer of a distinct seat durably beside its ledger (`ledger.offers`; the ledger file itself stays missing, so a restart resumes the count and does not forget it is wiped), and when the seats that offered number a majority of the other seats AND at least one of them is not the peer whose Hello just arrived, it writes the union to its own ledger, forces it to disk, and only then judges peers. The peer whose Hello ended the state is not admitted on that Hello (it is redialed and judged against the merged ledger), so a process for a retired NodeId with an empty fresh ledger can neither end the state alone nor be admitted by it. Consequence: in a three-core cluster the wiped core needs BOTH other seats to have been heard, so with only one other core reachable it stays isolated (fails closed; the safe remedy is to rejoin that node under a **new node id**), and in a two-core cluster it never leaves the state. Deleting the whole `identity/<node-id>` directory starts the node as a new node and lets it join, but turns the protection off for that node. The offers are taken from the configured core seats (`topology.coreNodes`, the roster `cluster.genesis_voters` names) on the strength of the sender's claimed identity inside the shared-secret trust domain.
- **Workers hold no ledger.** They keep the per-observer registry, so a restarted worker has the old amnesia.
- A local writer who can edit the file is inside the trust domain (see below).

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

## Stream reference counts are a bare counter

A slice instance acquires its stream references in the same consensus batch that writes its ACTIVE state; the stream registry holds one `refCount` per stream, written as an absolute value (applied mirror + 1), with no record of who holds it. When a node's activation write is never answered and the node's stuck-slice timer re-drives it (#1117), the re-drive rebuilds the count from the current applied mirror. If the lost original write also lands late, the instance holds **one reference too many**: the stream outlives its holders (kept alive, never released under a holder). Concurrent acquirers writing absolute values can likewise overwrite each other's count; that race predates the re-drive. **Recovery:** delete the stream explicitly (`aether stream delete`) once no slice uses it. **Tracked as #2160** (per-holder reference entries, rc5). `[mechanism: absolute Put of mirror + 1 in KvBackedStreamRegistry.acquireCommand]`

## Streams and durable entities

A source-level audit of streams and durable entities at `release-1.0.0-rc4` (`acd3357ccc`) found a cluster of defects in a few mechanisms. They are fixed together in one spec-first overhaul, tracked as **#2201** (target rc5), rather than by individual patches. Until it lands, these are the limits. Each limit below was confirmed by two independent reads of the code; none is yet reproduced by a test (the integration tests in #2201's plan will do that).

- **A lagging consumer stalls silently.** A declarative consumer reads only the in-memory ring. When its position falls behind the ring, the read fails with `CursorExpired`; the consumer's failure handler recognizes only `EpochDiverged` and does not fall back to sealed segments, so it retries on its poll backoff indefinitely and logs the failure at DEBUG only. With the default memory budget, a lag of a few hundred events is enough. `[mechanism: ConsumerRuntimeState.pollCycle reads through StreamPartitionManager.readServing -> readLocal, which reads the ring buffer only; ConsumerRuntimeState.pollFailed handles EpochDiverged and returns every other cause as a failed pass; logPollFailure logs at DEBUG]` The imperative `StreamAccess` path is a different reader and does fall through to the sealed tier (`guarantees.md` §5).
- **Sealed-tier retention is node-wide 24 h.** The sealed tier is reclaimed by one age policy, `DEFAULT_STREAM_RETENTION_MS` (24 h), passed to the node's single `RetentionEnforcer`. A stream or durable topic configured for longer retention loses events older than 24 h once they have left the ring, and no deploy-time check catches the mismatch. `[mechanism: AetherNode.DEFAULT_STREAM_RETENTION_MS, the retentionEnforcer construction]`
- **Sealing writes one segment per evicted event once a ring is full.** Each eviction hand-over becomes one segment (`SegmentSealer.onEviction`), and a full ring evicts per append. Each segment costs two fsyncs and a DHT put, which bounds the sustained per-partition rate and multiplies the number of files on disk.
- **The default stream memory budget fits about 50 partition rings per node.** The budget is 128 MiB (`StreamAssembly.resolveStreamMaxMemoryBytes`). Raise it with the `STREAM_MAX_MEMORY_BYTES` environment variable, or scale out.
- **Replication is not batched.** The production node builds its replication manager with the non-batching factory (`AetherNode.java:5564`, `ReplicationManager.replicationManager`); `batchingReplicationManager` has no production caller. Replication, forwards and catch-up share one transport stream, which drops messages when it backs up, and a publish forwarded from another node runs its WAL fsync on the transport event loop. The ordered append section has no abort, so a WAL refusal leaves the ring offset already assigned, and the "acked by N replicas" rule is assembled at several call sites rather than owned by the log.
- **A retried durable-entity `update` that missed its replication barrier applies twice.** The record is in the owner's log and is applied to its fold even when the barrier fails, and the caller is told the write failed (`EntityLogError.ReplicationBarrierUnmet`; `EntityFold` applies it on purpose, because a recovering node replays it). Retrying the same `update` appends and applies it a second time. Entity checkpoints can also hold state above their claimed offset, and superseded checkpoint blocks are never released.
- **The transactional (exactly-once) cursor path has no production wiring.** `PgTransactionalCursorCommit` is exercised only by its own test; the node builds its consumer runtime without a `TransactionalCursorCommit`, and nothing calls `transactionalCursorCommit()`. Consumers are at-least-once; "exactly-once" is not a property of any stream path today. See `guarantees.md` row 20 and `streaming-performance-analysis.md`.

Two related corrections made with this section: a topic `idempotencyKey` is the event's message ID and nothing more (the publisher does not deduplicate; see `resource-reference.md`), and consumer-group `join`/`leave` records membership and assignment entries that no stream read consults (see `management-api.md`).

## No-majority network cut halts every core

**Limit.** If a network event leaves no group of cores holding a majority (`cores/2 + 1`) for long enough, every core exits with code 2 and is not restarted (`Restart=no`). A heal after a core has begun draining does not cancel its halt. Consensus state lives in memory only, so blueprints, slice targets, API keys, cluster configuration, stream configurations and entity state are lost on all cores. Nothing restarts the cluster for you: recovery is an operator action.

**Measured.** Five cores with every core isolated from every other, five repetitions per cut length (25 core-runs each): a 10 s cut halted 0 of 25, a 15 s cut halted 2 of 25 (one core in each of two repetitions), and cuts of 20 s, 25 s and 30 s halted 25 of 25 each. The drain began 18 to 19 s after the cut started in every case that fired (the quorum-loss window opens 3 to 4 s after the cut, then the 15 s split timeout runs). 77 of 77 cores that fired exited with code 2 and none returned, including the 2 that fired after the heal. `[measured: five-node Ember probe on the rc4 tip, loopback on one host]` Not measured: a 2/2/1 split (the shape tested is "a cut where no group holds a majority, measured with every core isolated"), cut lengths between 15 s and 20 s, other values of `split_timeout`, and the production halt (the probe ends the node through Ember's exit hook; a production halt falls between the drain start and the end of the 30 s drain grace). `[unverified: production halt timing, 2/2/1]` The probe is not part of the repository's test suite.

**What it does not break.** Consensus never commits on two sides: with no majority, every side refuses writes (`QuorumPaused`) rather than diverging. Reads are served from local applied state, possibly stale, until the node halts. Availability is lost, and so is the in-memory state; committed state does not fork `[mechanism: Rabia quorum commit; DrainProcedure.java:186-199]`.

**Mitigation.** Configure `[backup]` with a `remote` on every multi-core production cluster ([production prerequisite](../operators/runbooks/deployment.md#production-prerequisite-backup-with-a-remote)). The backup preserves declared state only, up to the last successful push; entity state and acknowledged stream records are not recovered. **Recovery:** stop every node, start fresh cores with new NodeIds, the same configuration and the same `[backup]` remote, `restore = "auto"`. Without a backup, bootstrap a new cluster and re-apply declared state from your own source of truth. Full procedure: [No-majority partition / every core halted](../operators/runbooks/incident-response.md#no-majority-partition--every-core-halted).

**Why it is a boundary and not yet fixed.** A fired drain is single-shot with no cancel path, a halted node's NodeId is not reused (terminal removal is the identity model), and consensus state is not persisted; making a halt survivable would change one of those, which is a design change outside rc4. The rc4 decision is documentation, a runbook and the backup prerequisite, with no code change.

## Re-running bootstrap with a live cluster's name

**What is checked (1.0.0-rc4).** A fresh `aether cluster bootstrap` (without `--resume`) refuses, before it mints a secret or writes any state, when any of three checks matches, and the error names the one that did: the cluster registry (`~/.aether/clusters.toml`) already holds the name; a local `~/.aether/clusters/<name>/bootstrap-state.json` exists for it (an unreadable one counts as a match); or, for each `cloud` source, the provider lists an instance labelled `aether-cluster=<name>`. A check that cannot be made refuses too: an unreadable registry, or a provider call that fails or does not answer within 30 s. There is no override flag. `--resume` is not subject to the check. The way forward is `--resume` for an interrupted bootstrap, or `aether cluster destroy --cluster <name>` first to re-create. `[mechanism: BootstrapExistingClusterGuard.refuseExisting, BootstrapExistingClusterGuard.java:73-79, called on the fresh path at ClusterBootstrapOrchestrator.java:106; registry :81-95, local state :99-110, labelled instances :112-146, error ClusterConfigError.ClusterAlreadyExists, ClusterConfigError.java:189; verified: BootstrapExistingClusterGuardTest]`

**What the check does not cover.** It is a read before the run, so its reach is what those three reads can see.
- **Only `cloud` sources are asked what machines exist.** Sources of other types (`ssh`, `docker`) have no provider listing, so for them only the registry and the local state apply. A cluster on SSH or Docker hosts that this workstation has no registry entry or state file for is not recognised by name; the host-level launch refuses a host that already runs `aether-node`, which is a separate, later refusal. `[mechanism: BootstrapExistingClusterGuard.cloudSources, BootstrapExistingClusterGuard.java:121-127]`
- **The cloud check is by label.** It asks the source's own provider for instances labelled `aether-cluster=<name>` and looks at the first page of the answer; a cluster whose machines do not carry that label is not seen. `[mechanism: BootstrapExistingClusterGuard.java:173-176]`
- **The registry and the state file are local to the operator's machine.** A cluster bootstrapped from another workstation is visible here only through a cloud source's labels.
- **No lock is taken.** The guard reads, then the phases run; two fresh bootstraps of the same new name started at the same time are not serialised by it. `[mechanism: no lock or reservation in BootstrapExistingClusterGuard.java]`
- **`destroy` is unchanged.** `aether cluster destroy --cluster <name>` on a Hetzner source removes every machine labelled `aether-cluster=<name>`, whichever bootstrap created them. So a name that two clusters share by any route the check does not see still has both clusters' machines swept together. `[mechanism: BootstrapCleanup.sweepClusterVms selects by label and deletes every match, BootstrapCleanup.java:414-450]`

**What to do.** Continue an interrupted bootstrap with `--resume`. To re-create a cluster, `destroy` it first. Give every cluster you keep a name nothing else uses, including on SSH and Docker sources, where the check has the least to look at.

**History.** Before 1.0.0-rc4 a fresh bootstrap did not check any of this and ran over the live cluster's local state and registry entry; the check shipped in 1.0.0-rc4 (#2213).

## Consistency and durability boundaries

The precise per-operation consistency, durability, and delivery guarantees — including where the honest answer is *eventual*, *best-effort*, or *not crash-durable* — are documented in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md) and, authoritatively with `file:line` mechanisms, in [`guarantees.md`](guarantees.md). The scope-relevant boundaries an evaluator should know up front:

- **Default persistence is in-memory only.** A simultaneous full-cluster crash loses consensus (KV) and DHT state; only the declared cluster state in a pushed `[backup]` survives ([backup-recovery](../operators/runbooks/backup-recovery.md)). Deliberate (in-memory replicated), tracked under the storage-durability epic (#349 / #383).
- **~~DHT-backed routing/endpoint state is eventual and not crash-durable.~~ WITHDRAWN (#943, 2026-09-08) — this limitation never existed.** The entry claimed slice-node, HTTP-route and endpoint keys had migrated from consensus to the DHT `ReplicatedMap` (eventual, W=R=1, in-memory), making reads stale and restart-lossy (#384). **The read path never moved**, and the maps had no production writer, so they held nothing; routing state stayed on the consensus KV throughout and keeps its quorum-durable guarantee. The `ReplicatedMap` plane has been removed. This entry therefore overstated the system's weakness rather than its strength — a reader who avoided the product for this reason was misled.
- **Default streams replicate to 3 copies and acknowledge on 2 (#1564).** Streams, durable topics and durable entities share one policy (`guarantees.md` §4a): `replication_factor` (RF) and `confirmation_factor` (CF), defaulting to the committed cluster `[replication]` section (built-in RF 3, CF 2). The publish acks after the owner's WAL fsync and `CF − 1` distinct peer acks, so an acknowledged event survives `CF − 1` simultaneous replica losses `[design intent — unverified]`. **Breaking change:** before #1564 a stream that declared no factor acked on the owner alone (the old `min-sync-replicas` default 0); a publish now needs one registered peer, so a stream on a cluster with no peer fails `NOT_ENOUGH_REPLICAS` unless it declares smaller factors. `confirmation_factor = 1` restores owner-only acks, with a warning: an event acked inside the replication window is lost if the owner is terminally removed before it reaches a replica. An RF below 3 is accepted only when declared (LOUD warning), never from a default. *History (superseded by #1564):* the knobs were named `replicas` and `min-sync-replicas`; `2 ≤ min-sync-replicas < replicas` was not a guaranteed lossless promotion until #411/#1555. **Until #1549, a blueprint's then write-ack floor (`min-sync-replicas`) never reached the runtime.** The provisioning binder read `min_sync_replicas` and resolved an absent key from `StreamConfig.DEFAULT` (0), so every blueprint stream acked on the owner's local write whatever it declared. The runtime now binds `[streams.X]` through the deploy-time parser `[verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamAckedRecordsOwnerKillTest.java — the committed config of a 5-node cluster reads the declared confirmation_factor = 3]`. **Caveat on that remedy, measured 2026-08-16:** until then the write-ack floor was enforced only when the publishing node happened to hold the partition ring locally — a publish forwarded to the partition owner was acked on the owner's local fsync alone, so a stream configured for 2 effectively ran at 1. `02y-stream-crash` on a 5-node cluster ACKED 80/80 events and then lost two whole partition logs (41 acked events) to a single SIGKILL. The barrier now sits on the owner in `StreamForwardHandler.onPublishForward`, covering both write paths `[verified: unit + mutation]` — **not yet integration-verified**. Independently, a replica starved by `reshuffle_concurrency` pacing may never reach in-sync, in which case `confirmation_factor ≥ 2` is unsatisfiable and publishes now FAIL rather than silently acking at a weaker guarantee `[design intent — unverified]`. Slot tenure was previously UNBOUNDED — a stalled backfill held its slot forever, measured at 4m55s with zero releases while the partitions behind it were lost — and is now bounded: a slot held past a tenure bound while partitions are queued is preempted, the backfill continuing unslotted `[verified: unit + mutation — StreamReshuffleLifecycleTest$StalledSlotPreemption]`. The bound itself is now operator-tunable via `[streaming] reshuffle_concurrency`, which until 2026-08-16 was a hard-coded constant that the error message nonetheless named as if it were a knob.
- **The production streaming substrate is all-RAM.** Retention, cold-tier reads, and STRONG-stream creation are gated on the streaming-persistence plan (catalog rows 179/180/181/185/190/191); DEFAULT streams are what a stock node creates today. Their delivery has **three regimes**: (1) **while the consumer runs** — at-least-once: the cursor advances only after a successful callback and retries failures; (2) **across a restart with the log intact** — the per-partition WAL replays the log (A6, `StreamCrashDurabilityTest`). Cursor resume differs by consumer kind: an app `StreamAccess` consumer that explicitly commits and re-seeks (`committedOffset()`) gets **bounded-window** redelivery (disk-durable cursor, writable data dir); a **declarative `[streams.X]` consumer** gets consensus-KV-backed checkpointing (`ClusterCursorStore`, resume = the later of local and cluster by `(rewind epoch, offset)` — `max(local, cluster)` for a group never rewound, #1333) that also survives an ownership change, degrading to local-only on a swallowed consensus-write failure; a non-committing app, every **system consumer** (`SystemStreamFactories` → `none()`), and Forge / in-memory / unwritable-mount nodes **replay from offset 0** (duplicate-heavy, still at-least-once, not loss); (3) **only where the log itself is lost** — every replica of the partition lost, or the owner terminally removed inside the replication window (the caveat above) — a published event is delivered **zero times: at-most-once end-to-end**. An acked event is exposed to this only at `confirmation_factor = 1`, or when all CF copies are lost; with `confirmation_factor = replication_factor` every replica holds it at the ack. Mechanism detail in [`guarantees.md`](guarantees.md) §4; per-operation row in [`../architecture/14-consistency-and-partitions.md`](../architecture/14-consistency-and-partitions.md).
- **`[streams.X]` compression, `encryption_key_id` and the per-consumer tuning keys are not supported in 1.0 (#677).** They are parsed and then REFUSED by #576's `StreamResourceValidator.guardInertConfig`: segments are written uncompressed, no stream encryptor is wired (#253 owns the key source), and every declarative consumer runs with the hardcoded `ConsumerConfig` defaults. `auto_offset_reset` accepts only `earliest` (permanent, #478). **That refusal does not currently block a deploy, which is the sharp edge:** `BlueprintService.streamBindings` is the validator's only production caller and it discards the failure (`.or(List.of())`), publishing an EMPTY alias→address binding set. A blueprint carrying one of these keys therefore PUBLISHES; a slice consuming the aliased stream then fails with `StreamAddressError.UnboundStreamAlias`, and if nothing consumes that alias the key is silently ignored after all. The keys remain in the schema so the refusal exists to be acted on once a deploy-time gate is wired.
- **Pub/sub is at-most-once, unordered, best-effort** — never persisted; a subscriber down at publish time misses the message permanently.
- **Durable-entity CRUD + reads are wired**, not planned: `PartitionFencedDurableEntity` has been node-wired since #352 shipped, with fsync-before-ack (`logAndReplicate` writes the `PartitionWal` frame, `awaitDurable` awaits its group commit), RF=3 replication by default, and non-owner calls forwarded to the committed owner (#596) rather than failing. Both read consistencies are live — `BOUNDED_STALE` (offset-bounded, replica-forwarding) and `LINEARIZABLE` (owner-routed, no-op-round epoch fence; a given call can still return `EntityError.LinearizableUnavailable` if no barrier is registered for that partition — a freshness-vs-safety asymmetry, not dormancy). Durable timers (`scheduleTimer`/`cancelTimer`) are wired too (#351). **Still genuinely planned:** `workflow.*` and `saga.*` — zero code (#353/#354), with the saga contracts specified under #1827 (spec §7) but not implemented. Mechanism detail in [`guarantees.md`](guarantees.md) §6.
- **Storage at-rest encryption (`[storage.encryption]`, #253) has no plaintext-to-encrypted migration path.** Enabling `encrypted = true` (or `streams_encrypted = true`) is new-instance/fresh-data only in rc4: a local-disk directory already holding unmarked plaintext blocks refuses to boot as encrypted rather than mixing plaintext and ciphertext; a DHT tier (no directory to scan) instead fails closed per block on read. Re-encrypting existing blocks under a rotated key (including on tier demote/promote) is also not shipped — old keys stay resolvable for reads instead. The auto-synthesized default `artifacts` and `content` instances (used only when no explicit `[storage.artifacts]`/`[storage.content]` section is configured) track node-wide keyring presence instead: encrypted whenever `[storage.encryption]` is configured with a resolvable keyring, plaintext otherwise — `content` joined this coverage in #783, having previously bypassed the config/keyring wiring (and demotion/GC) via a separate factory path entirely. The `streams` instance's write-ahead log is excluded from `streams_encrypted` coverage regardless — it is a separate directory from the segment-block tiers encryption gates. See [`configuration.md`](configuration.md#storage-encryption-configuration) for the full coverage/exclusion list, including why this is unrelated to the already-dead `[streams.X].encryption_key_id` blueprint key (#576).
- **Upgrading a node that already has `[storage.encryption]` configured turns on `content` encryption by itself, and pre-existing `content` blocks become unreadable with no migration path (#783).** `content` encryption is **not opt-in**: the auto-synthesized `content` instance takes `encrypted = <a keyring is configured>`, so an operator who enabled `[storage.encryption]` for `artifacts` gets `content` encrypted by the upgrade alone — no `[storage.content]` section, no config change. Content blocks written before the upgrade are plaintext and are read afterwards through an encrypting tier, so they fail closed **per read** with `LegacyPlaintextBlock`. All of `content`'s pre-upgrade durable data is in the DHT tier — the pre-#783 bypass path had no disk tier, so the new disk directory is fresh and the disk-side boot refusal (`EnablingOverExistingPlaintext`) does not apply to `content`'s pre-upgrade data; the DHT tier has no directory to scan and therefore no forward-direction boot guard. Loud and typed, never a silent pass-through of unauthenticated bytes; but #253 ships detection, not migration, so there is no supported way to re-encrypt that data (tracked as #831). **To keep pre-existing content readable across the upgrade, set `[storage.content] encrypted = false` explicitly before upgrading.** Note also that Aether does not support rolling upgrades (#666/#434) — the supported path is a full-cluster stop, upgrade, start — so this is an illustration of that envelope rather than a separate defect: a mixed-version cluster would have an upgraded node writing ciphertext into the shared `content-blocks` namespace while an old node read it through the bare tier.
- **A previously-encrypted storage tier that boots with encryption disabled (or `[storage.encryption]` removed) is a new boot-failure mode, not a silent downgrade to plaintext access.** Both the local-disk and DHT tiers write a `.encryption-enabled` marker the first time encryption is enabled over them; if that marker is present and no keyring resolves for the instance, boot refuses with `EncryptedTierRequiresKeyring` rather than handing back framed `AEC1...` bytes as if they were the block's real content. This covers both directions on the per-instance disk/DHT path (`StorageFactory.createOne`) and both sides of the built-in `streams` segment tiers — the disk side (`defaultStreamStorage`'s no-keyring branch already refused on the disk marker before this round) and, since #849, the `stream-segments` DHT namespace, which is stamped and checked on the same post-formation admission as every `<name>-blocks` namespace. Recovery: restore `[storage.encryption]` with the same key id still resolvable in `[storage.encryption.keys]`, or migrate to a fresh, unmarked directory or DHT namespace (tracked separately — #831).

## Related Documents

- [../architecture/14-consistency-and-partitions.md](../architecture/14-consistency-and-partitions.md) — the consistency & partition-behavior contract (shares terminology with this page)
- [failure-almanac.md](failure-almanac.md) — operator catalog of failure modes, surfaces, and recovery budgets (companion to this page)
- [guarantees.md](guarantees.md) — authoritative per-operation guarantees, traced to `file:line`
- [feature-catalog.md](feature-catalog.md) — capability inventory with Complete/Partial/Planned status
- [../architecture/10-security.md](../architecture/10-security.md) — trust domain and mTLS mechanism
- [../architecture/05-worker-pools.md](../architecture/05-worker-pools.md) — two-layer topology and worker scaling
