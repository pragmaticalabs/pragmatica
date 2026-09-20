<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Worker Membership & Community Topology — Design Specification

**Status:** DRAFT for review (2026-06-10) · **Branch:** `release-1.0.0-rc1` (written against `dd5a2187f`) · **Implements:** GitHub **#241** (this document is the #241 spec of record) · **Builds on:** `cluster-topology-overhaul-spec.md` (the keystone; this spec extends its invariants A1–A8 with A9–A14), `membership-fsm-unification-spec.md` (the `connectionSet` seam), `membership-placement-split-spec.md` (the KV governing principle) · **Foundation:** `aether/docs/.internal/audits/worker-topology-membership-analysis-2026-06-10.md` (Class 2/3 findings; Class 1 = overhaul Wave 2) · **Release target:** RC2 (gated on overhaul Waves 2, 4, 5, 7 — §10)

## 1. Why

The worker tier has a complete *observed* substrate (SWIM cluster-wide visibility, per-member `MembershipFsm` with `MemberDescriptor(address, role, source)`, FSM-driven transport for the core mesh) and a half-built *actuation* layer whose wires were never connected: community formation never runs (`GroupMembershipTracker` has no feed), worker KV consumption is dormant (`WorkerBootstrap`/`DecisionRelay` created and dropped), Tier-2 observation has an engine and no ignition (no `SpokesmanKey` producer), and community identity is an unstable round-robin chunk number that renumbers on churn while `targetCommunity` directives and DHT partition ownership key on it.

This spec defines the missing layer: **desired-state worker topology** — stable community identity, community formation and growth, governor-delegated reconciliation, the worker connection set, a scoped KV access model, drain/rejoin semantics, and the DHT interaction — sized against measured constraints (§8).

It deliberately does NOT redefine what the overhaul already settled: role-correct counting (A8), one role vocabulary (`CORE/WORKER/SPOT`), `ActivationDirectiveKey` as the canonical role-assignment path, leader-canonical membership decisions (A7), and SWIM as the sole alive-authority (A1).

## 2. Settled design decisions

1. **D1 — Committed community identity.** `CommunityId` is a leader-minted, stable, committed KV fact. Community *assignment* is placement ("external facts the core authors" — placement-split §2); community *liveness* is presence (derived, never KV). The emergent `GroupAssignment` zone-chunking (renumbering on churn, `GroupAssignment.java:23-65`) is retired.
2. **D2 — Community key: `community ⊆ (source, role)`.** A source is a homogeneous placement domain with declared per-role target counts (`SourceProfile.roles`); communities partition a `(source, WORKER)` pool under the fan-out cap. Zone is a property of source. Clusters with no declared sources use the implicit source `"default"`.
3. **D3 — Governor-delegated reconciliation (closes #241 open decision 2).** The leader owns the community *set*, per-community *targets*, and global growth policy — O(#communities). Each governor reconciles its own community's membership against its target — O(community). Per #235's verdict (core ≤ ~15; scale lives in the worker tier) and #178's shape (sensor-only members, governor decides). Control-plane task groups remain core-only (`control-plane-delegation-spec`); governors are not delegation targets — they are the worker-tier's own tier.
4. **D4 — Spokesman tier retired.** Community liveness is detected by **governor-announcement staleness (TTL)** on the leader; telemetry/evidence rides the existing governor→leader `ControlLoop` route (`CommunityScalingRequest` path, generalized). The `SpokesmanKey`/`SpokesmanPingLoop` machinery (engine without ignition) is deleted. Rationale: `GovernorAnnouncementKey` already rides consensus with a 30 s unconditional reannounce (`GovernorAnnouncer.java:183-188`) — a TTL on it gives the leader dead-governor/dead-community detection at O(#communities) cost with zero new protocol; the spokesman's only unique value was sharding a ping plane the announcement stream makes redundant. This supersedes the spokesman half of #178 and `cluster-generation-spec` §Tier-2; the *rest* of #178 (governor as single decision-maker and single writer of community atoms, epoch fencing at community level, sensor-only members) is adopted (A11, A12).
5. **D5 — Worker KV access: scoped watch + pull; no full synchronization.** Workers (and governors) never hold a full KV replica. A worker subscribes to key *prefixes* on the **committed-decision stream** (post-apply KV changes carrying the commit sequence) from its join point, and **pulls on demand** with `(value, commitSeq)` reads. Gap in the stream → re-pull the affected prefix (self-healing). Cores remain the only full replicas. The `WorkerBootstrap` unbounded full-snapshot and the unfiltered `DecisionRelay` contract are retired; `DecisionRelay` is reshaped into the **scoped watch relay** (§6). (Adopts `passive-worker-pool-research.md` §15's watch-model recommendation; both retired mechanisms are dormant in production today, so this is first wiring, not rewiring.)
6. **D6 — Worker identity is disposable.** Same-NodeId rejoin is **unsupported** for workers: a wiped or replaced worker is provisioned with a fresh NodeId. Incarnation fencing remains as a safety net (the mechanics are shared with cores), not a supported operational flow. Kills the worker half of the rejoin edge-case matrix at zero operational cost.
7. **D7 — SWIM topology is versioned.** **V1:** one cluster-wide gossip pool (today's model), explicitly ceilinged at ~1K workers (§8). **V2 (designed, deferred):** community-scoped gossip with governor uplink. Binding rule now: *nothing outside a community may depend on directly gossip-observing a worker in another community* — cross-community knowledge flows through committed KV (announcements, ownership) or governor relay, so the V1→V2 cutover is a transport change, not a model change.
8. **D8 — Sizing is a budget, not a constant.** `maxCommunitySize` defaults to 100 with a floor of **3 (= DHT replication factor)**; the normative constraint is the governor fan-out budget over the *filtered* relay rate (§8). Growth policy is a global cluster policy with the hybrid **spread-then-pack** default (width-first to min-coverage, then depth-first); per-blueprint placement constraints are a later, separate concern.
9. **D9 — Role/source for transitively-learned peers: lazy pull, not codec surgery.** The positional `@Codec` on `MembershipUpdate` cannot carry additive fields (unification spec §5.1). Rather than a versioned/length-prefixed carrier, a node lacking a peer's labels issues a **`NodeInfoRequest`/`NodeInfoResponse`** (new SWIM message pair — additive message *types*, no change to existing wire formats) to the peer or any node that knows it; result cached on the descriptor. Consistent with D5's pull philosophy. The authoritative role of a *member* is in any case the leader's `ActivationDirectiveKey` (leader-authored); the SWIM label is the bootstrap hint — which also narrows the role-attestation trust gap (overhaul §11): a lying label can at most mis-hint, not mis-assign.
10. **D10 — Scope boundary.** This spec covers membership/topology only. Worker slice *execution*, the `WorkerMutation` data path, and the endpoint-registry split are deployment concerns, out of scope; §11 states the interface contract they consume. LB revival (#220) is out of scope; its PassiveNode constraints (zero votes, zero quorum, zero leader-eligibility) are restated here as invariant A9.

## 3. Target architecture

### 3.1 The fractal authority model

The community is to the governor what the core is to the leader — the same single-authority pattern at a second scope:

```
  CORE (5–9 nodes, Rabia voters)
  ┌──────────────────────────────────────────────────────────────────┐
  │ Leader: core reconciliation (overhaul) + COMMUNITY-SET reconciler │
  │  - owns: community set, per-community targets, growth policy,     │
  │    community assignment of joining workers, partition→community   │
  │    ownership, announcement-TTL liveness verdicts (D4)             │
  │  - O(#communities), never O(workers)                              │
  └───────────────┬──────────────────────────────────────────────────┘
                  │ committed KV: CommunityKey, ActivationDirective(WORKER,
                  │ communityId), DhtPartitionOwnership(ownerCommunityId)
                  │ + scoped watch stream (governor uplink, D5)
                  ▼
  COMMUNITY (3..100 workers, one per (source,role) partition slot)
  ┌──────────────────────────────────────────────────────────────────┐
  │ Governor (elected member): single decision-maker + single writer  │
  │ of its community's atoms (A11); reconciles members vs target;     │
  │ relays scoped watch stream to followers; aggregates telemetry →   │
  │ leader ControlLoop; serves community sample to joiners; drives    │
  │ DHT re-replication on member death (Option E)                     │
  ├──────────────────────────────────────────────────────────────────┤
  │ Followers: sensor-only (observe, heartbeat to governor, never act │
  │ autonomously, #178); connectionSet = {governor} ∪ community peers │
  └──────────────────────────────────────────────────────────────────┘
```

Membership signals flow exactly as in the core (A1/A2): SWIM observes, the per-node `MembershipFsm` is the local authority, descriptors carry `(role, source)`. What this spec adds is the **desired-state layer** above the observed substrate, and the worker-scoped projections of it.

### 3.2 Invariants (extending overhaul A1–A8)

- **A9 — A worker is never a voter.** Zero Rabia votes, zero leader-eligibility, zero quorum counting, never a `CORE_ONLY` placement target, never a broadcast target for consensus protocol traffic. (A8 + #220 PassiveNode semantics + #236: workers inflate neither the reachable count nor the voter count.)
- **A10 — Community identity is committed and immutable.** A `CommunityId` is minted once by the leader, never renumbered, never reused. Everything that keys on a community (directives, DHT ownership, announcements) keys on the committed id. Death of a community is an explicit DISSOLVED terminal fact, not a disappearance.
- **A11 — One writer per community.** The governor (fenced by `communityTerm`/`communityEpoch`) is the sole writer of its community's observed-membership atoms. The leader is the sole writer of community desired-state (targets, assignment, ownership). A demoted governor's late writes are fenced exactly as a demoted leader's are (#178 epoch semantics, adopted).
- **A12 — Sensor-only followers.** Community members observe and report (heartbeat, metrics); only the governor decides; only the leader changes desired state. No follower acts autonomously on membership.
- **A13 — Bounded connectivity, bounded knowledge.** A worker's QUIC connection set is `{governor} ∪ community peers` (+ governor's extra links per §3.5); a worker's KV knowledge is its subscribed prefixes (D5). No structure on any node may be O(total workers) except on cores (and the leader's community-set state is O(#communities)).
- **A14 — Cross-community opacity (V2-readiness).** Nothing outside a community depends on directly gossip-observing its members (D7). Cross-community facts travel via committed KV or governor relay only.

### 3.3 Community model

**Identity & desired state (leader-owned, committed).** New KV atoms:

- `CommunityKey(communityId)` → `CommunityValue{sourceName, role, targetSize, state, createdAt, dissolvedAt?}` — the desired-state record. `state ∈ {FORMING, ACTIVE, DEGRADED, DISSOLVING, DISSOLVED}` (per-community FSM, leader-evaluated).
- `ActivationDirectiveValue.WORKER` extended with `communityId` + governor address hint — community assignment happens at the same moment as role assignment, through the already-canonical directive path.
- `DhtPartitionOwnershipValue.ownerCommunityId` (exists, `AetherValue.java:1272`) keys on the committed id.

**Observed state (governor-owned, committed via announcement).** `GovernorAnnouncementKey(communityId)` → `GovernorAnnouncementValue` (exists, `AetherValue.java:454-463`) remains the governor's single observed-membership statement: governor id, member list, term/epoch, dissolved flag. The 30 s unconditional reannounce is **load-bearing as the liveness signal** (D4) and stays unconditional; payload optimization (member-list delta) is permitted but not required at S ≤ 100 (~4 KB).

**Per-community FSM (leader-evaluated, edge-driven per A4):**

| Transition | Trigger |
|---|---|
| (minted) → FORMING | leader creates community (growth policy demands a new slot) |
| FORMING → ACTIVE | first governor announcement with `members ≥ RF` (3) |
| ACTIVE → DEGRADED | announcement shows `liveMembers < RF`, or announcement TTL missed once (grace) |
| DEGRADED → ACTIVE | announcement shows `liveMembers ≥ RF` |
| DEGRADED → DISSOLVING | leader decision: unrecoverable (source drained, scale-down, TTL expired beyond grace) |
| ACTIVE → DISSOLVING | leader decision: scale-down / rebalance |
| DISSOLVING → DISSOLVED | partition ownership migrated (§7) AND members drained/reassigned; terminal |

DEGRADED has a concrete, measurable trigger (`< RF`) because RF is the floor of community viability (§7). DISSOLVING sequences DHT ownership migration *before* member drain — drain monotonicity per #189 applies at community scope: a member rejoining mid-dissolution does not stop the dissolution.

**Growth policy (leader, pluggable comparator).** `nextPlacement(topology, joiningWorker) → communityId`: width-first until each `(source, role)` pool has min-coverage (≥1 ACTIVE community), then depth-first (fill toward `targetSize`), spawning a new community when all are at target (or at the fan-out budget, §8). The comparator is one pure function; width/depth/zoned are orderings of it, not mechanisms (#241 recommended-shape 2).

### 3.4 Governor

**Election: unchanged mechanics, new read source.** Incumbent-sticky, lowest-alive-NodeId among community members (`GovernorElection.java:16-47`), reading the **FSM projection scoped to the community** (the unification-spec read-source swap deferred to #241 — delivered here) instead of raw SWIM state. Re-election bumps `communityTerm`; the new governor re-announces and reconciles community membership against the DHT (Option E governor-death rule).

**Duties:** (a) announce (30 s, the liveness signal); (b) reconcile members vs `CommunityValue.targetSize` — report deficit/surplus to the leader as evidence (the leader provisions/drains; the governor never provisions); (c) relay the scoped watch stream to followers (§6); (d) aggregate Tier-3 telemetry (heartbeat 500 ms, metrics 5 s — `WorkerConfig.java:36-39`, finally wired) and push compact evidence to the leader `ControlLoop` (existing route, `AetherNode.java:3533`); (e) curate the community sample for joiner seeding (§5); (f) drive intra-community DHT re-replication on member death (§7); (g) participate in the governor mesh for cross-community DHT relay (§7).

**Governor uplink.** Each governor holds a watch-stream subscription to **one core node selected by HRW(communityId → live cores)** with failover to the next-ranked core. This is the only standing worker-tier→core connection; everything else (announcements, evidence) rides it or consensus.

### 3.5 Worker connection set (the unification-spec seam, filled)

`connectionSet(self, fullMembership)`:

- **Follower:** `{governor} ∪ {community peers}` — ≤ S links.
- **Governor:** `{community members}` ∪ `{other governors}` (mesh, §7) ∪ `{HRW uplink core}` — ≤ S + G + 1 links.
- **Core:** unchanged (`{role=core members}`); cores additionally accept governor uplinks and joiner bootstrap dials.

Registered with the per-node `MembershipFsm` as the worker-case projection; the transport executor reconciles it level-triggered, exactly as the core mesh (A2/A3). A worker's SWIM visibility remains cluster-wide in V1 (gossip ≠ QUIC; the bounded set governs connections, not observation) — in V2 visibility also scopes to the community + governor uplink (A14 makes this a non-event for consumers).

## 4. Join, seeding, and lifecycle flows

### 4.1 Worker join (hierarchical seeding, preferential attachment)

1. Provisioning mints the node with a **fresh NodeId** (D6), `AETHER_ROLE=worker`, `AETHER_SOURCE=<source>` stamped explicitly (overhaul Wave-2 W4 — never inherited), and PEERS = a subset of the **oldest healthy cores** (stability ranking is free: the FSM records OBSERVED→MEMBER times).
2. The node ANNOUNCEs (existing SWIM join), reaches OBSERVED on the leader's FSM with `(role=worker, source)` from labels.
3. The leader's community-set reconciler runs the growth comparator → `communityId` (existing community with headroom, or mint a new FORMING community) → writes `ActivationDirective(WORKER, communityId, governorHint)`.
4. The worker receives the directive (via its bootstrap watch subscription on its own directive prefix, served by the dialed core), dials the governor, requests the **community sample** (governor-curated: itself + oldest community members), extends its connection set, and drops the bootstrap core dial.
5. The worker subscribes to its prefixes (§6) from the current commit sequence, pulls its directives/config, and reports READY through the Tier-3 heartbeat. The governor's next announcement includes it; the leader's FSM sees it MEMBER. If the directive assigns a FORMING community with no governor yet, the worker self-elects per §3.4 (lowest NodeId of one) and announces — FORMING → ACTIVE happens when membership reaches RF.

**JOIN_TIMEOUT:** a worker that cannot complete 1–5 within the join window surfaces a programmatic failure to the leader reconciler (the swim-driven-topology gap — log-only today), which retries assignment or replaces the node.

### 4.2 Worker departure / death

Per-node FSM mechanics are unchanged (SWIM-FAULTY ∧ liveness-gone → DEAD). Scoped consequences: the governor observes the death (community SWIM/heartbeat), removes the member from its next announcement, triggers DHT re-replication (§7), and reports the deficit; the **leader** provisions a replacement targeted at the **same `(source, role)` pool** (the FSM descriptor of the dead node carries `source` into DEAD — source-aware replacement, #241 comment 3), assigned by the growth comparator (usually the same community). A dead governor → re-election (§3.4) → announcement with bumped term; the leader sees continuity, not death of the community.

### 4.3 Worker drain & scale-down

Operator/leader-initiated: `ScaleDown(source, WORKER, n)` → the leader picks victim communities/members (surplus first, then load-ranked via governor evidence — not reverse-NodeId), writes drain directives. The worker's NDM drains per #189 (monotonic, `drain.timeout`-bounded), governor confirms departure, announcement shrinks. Community-level: DISSOLVING per §3.3. `ClusterConfigApplier` **role-routes** scale ops (un-rejecting overhaul Wave-2 W5): CORE-scale → core path; WORKER-scale → this reconciler.

### 4.4 Rejoin

A worker that vanishes and returns with the same NodeId within the FSM's recovery window behaves as a transient (SUSPECT→MEMBER, fine). A worker that was terminally evicted does **not** rejoin (D6): the fence rejects it; ops guidance is reprovision (fresh id). No worker-side analogue of the core's incarnation-bump rejoin flow is built or supported.

## 5. What replaces `GroupAssignment`/`GroupMembershipTracker`

Community membership stops being derived chunking and becomes: **desired** = leader's `CommunityKey` targets + assignment directives; **observed** = governor announcements. `GroupAssignment.computeGroups`/zone-prefix parsing/round-robin renumbering are deleted; `GroupMembershipTracker` either becomes the thin governor-side view of "my community members per the FSM projection + my announcements" or is absorbed into the governor component. `WorkerConfig.groupName`/`maxGroupSize` static config is replaced by `CommunityValue.targetSize` (committed) under the global `maxCommunitySize` cap; the `05-worker-pools.md` "10–50 per group" vs code "100" conflict resolves as: **100 = cap, 10–50 = expected operating point** (§8).

## 6. Worker KV access model (D5, normative)

**Subscription.** A scoped subscription is `(Set<KeyPrefix>, fromCommitSeq)`. Worker default prefixes: own `ActivationDirectiveKey`, `WorkerSliceDirectiveKey` (global + own community), own `CommunityKey` + `GovernorAnnouncementKey(own)`, `ClusterConfigKey`, slice-config prefixes for assigned slices. Governor adds: `DhtPartitionOwnershipKey/*`, `GovernorAnnouncementKey/*`, `CommunityKey/*` (own source at minimum). Nothing else flows down.

**The stream.** The committed-decision stream (post-apply KV change events with commit sequence), **never raw Rabia traffic** (A9). Filtering is server-side at each hop: core filters per the governor's subscription; the governor splits/filters per follower subscriptions. `DecisionRelay` is reshaped into this **scoped watch relay**: same transport position (core → governor → followers, CONSENSUS lane), new contract — prefix-filtered, seq-numbered, gap-detectable. The 1000-deep buffer (`DecisionRelay.java:24`) becomes a latency optimization: a detected gap triggers re-pull of the affected prefixes, not silent loss. The uncalled `decisionAt` gap-fill API is retired in favor of re-pull.

**Pull.** `Read(keyOrPrefix) → (value, commitSeq)` served by the governor's uplink core (followers pull via governor relay or, fallback, direct to a core). Composition rule: subscribe first, then pull, discard buffered updates with `seq ≤ pulledSeq`. Per-prefix convergence; no global snapshot, no join thundering herd (a joining community of 100 pulls a few keys each, vs 100 full-KV snapshots of unbounded `byte[]` — `WorkerBootstrap.java:53-65` retired).

**Boot convergence (worker analogue of overhaul §5.9):** on (re)start a worker converges to *the directives addressed to it* — pull-at-activation, idempotent. Cross-key causality (a directive referencing an artifact/config not yet local) resolves by lazy pull with explicit bounded failure (dht-resilience philosophy); no implicit ordering guarantees across prefixes.

## 7. DHT interaction

1. **Ownership is community-keyed placement.** `DhtPartitionOwnershipKey(partitionId)` → `ownerCommunityId` + `ownershipTerm` fence (exists; today only the static `"core"` partition, leader-written, `BootstrapModule.java:329-336`). This spec generalizes: the leader assigns worker-tier partitions to ACTIVE communities; transfers are `ownershipTerm`-fenced. Stable ownership requires A10 — the second consumer that makes committed identity mandatory.
2. **Intra-community placement: HRW over live community members.** Reuse `ReplicaPlacement` (FNV-1a rendezvous, pure, `ReplicaPlacement.java:38-80`) keyed `(partition, key → community members)`. RF/W/R from `DHTConfig` (3/2/2 defaults, `DHTConfig.java:101`).
3. **Liveness → DHT (Option E, adopted):** the governor reacts to member death with re-replication within the community + periodic anti-entropy; on governor re-election the new governor reconciles membership against the DHT. The implemented resilience layer (ownership ∩ reachability pre-filter `DistributedDHTClient.java:207-209`; explicit `WriteOutcome` fail-fast) applies unchanged: community churn surfaces as bounded `InsufficientReplicas`, never silent loss.
4. **Cross-community routing (Option D, adopted): 2 hops max, no core involvement.** worker → own governor → owner community's governor → target worker, on the DHT lane. Governors discover each other via `GovernorAnnouncementKey` (subscribed prefix). `WorkerDHTNetwork`/`DHTRelayMessage` implement the outbound half (`WorkerDHTNetwork.java:74-134`); the **inbound `DHTRelayMessage` handler is a named deliverable** (does not exist today).
5. **Community viability floor = RF.** A community below RF live members cannot hold write quorum (W=2 of RF=3) durably → DEGRADED (§3.3); `maxGroupSize`'s floor of 2 (`WorkerConfig.java:50-52`) is raised to RF. DISSOLVING migrates every owned partition (fenced) before member drain completes.
6. **Mesh shape.** V1: full governor mesh (O(G) links per governor — fine to G ≈ 300, §8). Designed evolution if G must exceed that: dial-on-demand to owner governors only (sparse mesh); permitted by A13/A14 without model change.

## 8. Sizing model & budgets (normative defaults, informative analysis)

**Measured/stated inputs:** Rabia ~8K commits/s bench, **~800–1,000 commits/s practical**; commits are multi-key batches (e.g., one deployment = 1 commit). Steady-state commit rates are far below the ceiling. Constants: heartbeat 500 ms, metrics 5 s, announce 30 s, spokesman-free, QUIC 8-lane persistent connections, per-node practical connection budget assumed ~500 (assumption, to be validated).

**The binding constraint is filtered relay rate × S at the governor.** With D5 scoping, workers receive only worker-relevant changes: W_relevant ≈ 1–10 commits/s steady, ~100/s during deployment bursts — *decoupled from the consensus ceiling*. Governor egress = W_relevant × S ≤ 10K msgs/s at S=100 burst: comfortable, order-of-magnitude headroom. (Unfiltered, the old contract would have been correctness-fragile: a follower pause > buffer/W seconds silently gapped — at 800 commits/s, a 1.25 s window. The watch model removes the cliff regardless of rate.)

**Secondary budgets at the reference configuration G=100, S=100 (N=10K):**

| Path | Cost model | At reference | Verdict |
|---|---|---|---|
| Announcements (1 Put/30 s per community, O(S) ≈ 4 KB payload) | G/30 commits/s | 3.3/s, ~400 KB resident | trivial; scoped relay keeps them out of follower streams |
| Announcement-TTL liveness (leader) | O(G) timestamps | 100 | trivial |
| Tier-3 heartbeat + metrics ingest (governor) | 2S + S/5 msgs/s | ~220/s | trivial |
| Governor mesh | O(G) links/governor; total conns ≈ S+G+1 | ~200 | OK; ceiling where S+G → conn budget (~500) |
| Evidence governor→leader | O(G) low-cadence msgs | ~G/5 s | trivial |
| SWIM V1 single pool | dissemination at N | 10K — **over** | V1 ceiling ~1K workers (8-entry piggyback, 1 probe/s); V2 required beyond |

**Normative limits:**

- **Community size S:** floor **3** (= RF; DEGRADED below), default cap **100**, hard ceiling ~**200**. Formulated as a budget: `S_max ≈ relayBudget / W_relevant` (≈ 20K msgs/s ÷ 200/s ≈ 100). The fan-out cap (#241's `maxGovernorFanout`) **is** this budget.
- **Governor count G:** **V1: N ≲ 1K binds first** → G ≈ 10–20 at S = 50–100. **V2: G ≈ 100 reaches the 10K target comfortably**; structural ceiling ≈ **300** (full mesh + connection budget); sparse mesh lifts it further.
- **V1→V2 gate:** measured, not guessed — SWIM dissemination latency / probe-budget metrics from the overhaul Wave-1 journal, evaluated as N approaches ~500.
- **Empirical check (required before GA of the worker tier):** a relay micro-benchmark (governor egress under burst) and a join-storm test (community of 100 joining) convert these planning numbers into enforced budgets.

## 9. What is retired / deleted (A6 accounting)

| Mechanism | Disposition |
|---|---|
| `GroupAssignment` zone-chunking + renumbering | DELETE (replaced by committed identity + assignment directives, §5) |
| `SpokesmanKey`, `SpokesmanPingLoop`, ClusterSync spokesman fields, `collectSpokesmen`, `countPendingSpokesmanRebalance`, quiescence "awaiting spokesman" | DELETE (D4); quiescence evaluator updated to announcement-TTL community health |
| `WorkerBootstrap` full-KV snapshot (`SnapshotRequest/Response` unbounded `byte[]`) | DELETE (D5; replaced by scoped watch + pull) |
| `DecisionRelay` unfiltered contract + uncalled `decisionAt` gap-fill | RESHAPE into the scoped watch relay (§6) |
| `WorkerConfig.groupName`/static `maxGroupSize` as community sizing | REPLACE by `CommunityValue.targetSize` + global cap |
| Worker-tier reliance on `TopologyGrowthMessage` | none (already retired by overhaul Wave 9 in favor of `ActivationDirectiveKey`) |
| Same-NodeId worker rejoin support | UNSUPPORTED by policy (D6) |

New structural additions (bounded): `CommunityKey/Value`, the per-community FSM (leader-side, small), the watch-relay contract, `NodeInfoRequest/Response`, the inbound DHT relay handler. Everything else is wiring of existing dormant machinery.

## 10. Phasing & gates

Implementation starts only after overhaul **Waves 2 (role hygiene), 4 (delta spine), 5 (transport emission), 7 (FSM completeness)** are green — this spec builds on role-correct counting, the delta stream, and the FSM-as-sole-authority inversion. #178's soak gate (Tier-1 stability before Tier-2 work) is honored by the same ordering. Each phase Docker-gated; each independently revertable.

- **Phase A — Identity & membership substrate.** `CommunityKey/Value` + per-community FSM (leader); `ActivationDirective(WORKER, communityId)`; announcement-TTL liveness + spokesman retirement (D4); worker `connectionSet` projection registered with the FSM; governor election read-source swap to FSM projection; `NodeInfoRequest/Response` lazy label pull (D9); `GroupAssignment` deletion. *Proves:* worker joins are assigned to stable communities; governor death → re-election with term bump, leader sees continuity; community FSM transitions on announcements/TTL. Extend `13-edge-cases` (the overhaul Wave-2 worker scenario grows community assertions).
- **Phase B — Worker KV access (the nervous system).** Scoped watch relay (core→governor→follower), pull API, gap→re-pull, worker boot convergence, governor uplink (HRW core). *Proves:* a worker receives its directives with zero full-KV transfer; induced stream gap self-heals via re-pull; join storm of N workers produces O(N × few keys) pull traffic.
- **Phase C — Desired state, growth, scale, drain.** Growth comparator + community targets from `(source, role)` config; governor member-reconciliation + evidence; source-aware replacement provisioning; `ClusterConfigApplier` role-routing (un-reject W5); worker drain + DISSOLVING flow (#189 monotonicity at community scope); JOIN_TIMEOUT surfacing. *Proves:* `aether cluster scale <source> worker --count N` provisions into communities per policy on a stable cluster; scale-down drains monotonically; a killed worker is replaced from the same source.
- **Phase D — DHT wiring.** Multi-partition community ownership; inbound `DHTRelayMessage` handler + governor mesh; HRW intra-community placement; Option-E re-replication/anti-entropy; DISSOLVING ownership migration. *Proves:* cross-community put/get at 2 hops; member kill → re-replication within RF window; community dissolve migrates partitions with zero loss (fenced).
- **Phase E (deferred, designed) — V2 community-scoped gossip** behind the measured gate (§8).

**Validation strategy:** per-community FSM and the projector get deterministic simulation tests (overhaul §5.9b pattern — scripted edge sequences, no Docker); Docker scenarios extend `13-edge-cases` per phase; a dedicated worker suite (join storm, governor kill, community dissolve, cross-community DHT) lands with Phase D.

## 11. Interface contract toward deployment (out-of-scope consumers)

Deployment (CDM/NDM/`WorkerDeploymentManager`) consumes from this layer: (a) `AllocationPool.workersByCommunity` built from announcements (existing path, now on stable ids); (b) directive delivery via the watch stream (fixing W8's severed consumer wire is deployment work, but the transport it needs is Phase B); (c) per-community readiness aggregates via governor evidence; (d) the guarantee that `CORE_ONLY` never targets a worker (A9) and that community membership in announcements lags reality by ≤ one announce interval + detection window. The `WorkerMutation` path (W10 — possibly dead end-to-end) must be verified by deployment work before anything builds on it; this spec deliberately does not depend on it.

## 12. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Spokesman retirement loses an observation plane we later need | The announcement stream + TTL gives equivalent dead-community detection; telemetry rides the evidence route. If per-community observation needs *sharding* at G ≫ 300, reintroduce assignment as a leader-side concern then — the retirement deletes no wire format that would block it. |
| Leader community-set reconciler becomes a second "CTM dead-state" accretion | It is specified edge-driven (announcement edges, TTL edges, config commits) per A4 — no periodic tick; deterministic simulation tests pin its transitions. |
| Watch relay re-introduces the multi-authority gap (governor's view vs core's) | The relay is a *projection* of the committed stream with seq numbers; gap → re-pull from the core (single source of truth). The governor caches, never decides KV content (A11 covers only its own atoms). |
| Governor churn under load (election flaps) thrashes announcements/relay | Incumbent-sticky election + communityTerm fencing (existing); follower subscriptions survive governor change (re-subscribe from last seq to the new governor — gap protocol covers the seam). |
| Join-storm on a fresh source (100 workers at once) | No snapshot herd (D5); assignment is O(1) per join at the leader; FORMING communities absorb in parallel (width-first). Validate in Phase B/C gates. |
| D6 (disposable identity) surprises operators who restart a worker in place | Restart-in-place within the recovery window is fine (SUSPECT→MEMBER). Only terminal eviction requires reprovision; document in ops guide + clear error on fenced rejoin. |
| Lazy label pull (D9) leaves a window where a transitively-learned peer has unknown role | Unknown-role peers are counted as nothing (not core, not worker) and never enter any connection set until resolved — fail-closed; resolution is one round-trip, cached. |

## 13. Open questions

| # | Question | Status |
|---|---|---|
| 1 | Governor-side read-through cache for hot pulls — permitted optimization; needed at S=100? | DEFER to Phase B measurement |
| 2 | Per-blueprint placement constraints (zone-anti-affinity → zoned growth) | DEFER (post-GA; comparator is pluggable by design) |
| 3 | `SPOT` role semantics (preemptible workers: drain-on-notice, never DHT owners?) | DEFER — spec treats SPOT = WORKER until defined; flagged so DHT ownership assignment can exclude SPOT communities cheaply later |
| 4 | Worker readiness surfacing to operators (`/api/cluster/topology` per-community detail) | Phase C deliverable, shape with dashboard work |
| 5 | Announcement payload delta-encoding at S > 100 | DEFER (permitted by §3.3, not required) |

## 14. Disposition of related documents & tickets

| Item | Disposition |
|---|---|
| **#241** | This spec is its spec of record; the ticket's open decisions are closed: community key = `(source, role)`-partitioned (D2), reconciliation = governor-delegated (D3), growth = global policy w/ spread-then-pack default (D8), fan-out = filtered-rate budget (§8). |
| **#178** (Tier-2 CommunitySync) | PARTIALLY SUPERSEDED: governor-as-single-writer, epoch fencing, sensor-only members **adopted** (A11/A12); the spokesman relay tier and the `CommunitySync` rename **retired** (D4). Update ticket on ratification. |
| **#189** (coordinated drain) | Adopted at community scope (drain monotonicity, timeout-bounded); worker drain rides NDM's drain per that ticket. |
| **#236** (bounded dissolution) | Reinforced: A9 keeps workers out of both reachable and voter counts. |
| **#220** (LB revival) | Out of scope; A9 restates its PassiveNode constraints; the LB's lifecycle-observation redesign should use the §6 watch model (note on ticket). |
| `cluster-generation-spec.md` | Spokesman/Tier-2 choreography content superseded by D4; banner on ratification. |
| `passive-worker-pool-research.md` | Watch-model recommendation (§15) adopted (D5); full-replica lite-member model rejected. |
| `05-worker-pools.md` | Update numbers: groups 10–50 → "operating point 10–50, cap 100" (§8); spokesman references removed. |
| `membership-fsm-unification-spec.md` | Its #241-deferred items delivered here: worker `connectionSet` projection (§3.5), governor/group read-source swap (§3.4), transitive role/source resolution (D9 — by lazy pull instead of the versioned carrier it anticipated). |
| `kv-store-scalability.md` (registry split) | Orthogonal but eased: workers never subscribe to endpoint-churn prefixes, so worker scaling no longer waits on the split; the split remains right for core-side KV volume. |

## 15. References

- `cluster-topology-overhaul-spec.md` — keystone; invariants A1–A8; Waves 2/4/5/7 prerequisites; §5.2 names this spec.
- `aether/docs/.internal/audits/worker-topology-membership-analysis-2026-06-10.md` — foundation audit (Class 2 severed wires, Class 3 scalability, dependency chain).
- `aether/docs/.internal/audits/cluster-topology-architecture-audit-2026-06-10.md` (companion copy in `pragmatica-store/`) — core audit.
- `membership-fsm-unification-spec.md`, `membership-convergence-fsm.md`, `membership-placement-split-spec.md` — adopted model + KV governing principle.
- `aether/docs/.internal/phase2-open-issues.md` — DHT Options D/E (adopted §7).
- `dht-resilience-spec.md` — implemented resilience layer this spec inherits.
- GitHub #241 (+3 comments), #178, #189, #236, #235, #220, #222.
- HashiCorp Lifeguard / memberlist scaling practice — SWIM pool sizing input (§8, D7).
- User-provided measurement: Rabia ~8K commits/s bench / ~800–1,000 commits/s practical, batched commits (§8).
