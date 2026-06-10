# Worker-Tier Topology & Membership — Analysis

**Date:** 2026-06-10 · **Branch:** `release-1.0.0-rc1` @ `d3ebb3c8d` · **Companion to:** `cluster-topology-architecture-audit-2026-06-10.md`
**Known gap ticket:** [#241 — Community topology lifecycle](https://github.com/pragmaticalabs/pragmatica/issues/241) (+ 3 comments). Related: #235, #220, #178, #236, #222, #189.
**Method:** 3 parallel audit agents (worker membership in code; worker-tier constructs/consumers; spec+ticket landscape), synthesized. Full agent reports in appendices A–C.

---

## Verdict

#241 correctly identifies the *desired-state* gap (no community formation, no growth policy, no role/community-aware seeding). But the analysis shows the problem is wider: **the *observed* layer for workers is itself broken or unwired**. Three classes of finding:

1. **Live correctness bugs** — workers inflate every role-unfiltered membership count. The moment a cluster has one worker (reachable today: any join past `coreMax` is assigned WORKER), quorum, auto-heal, and role assignment all miscount. A dead core is never replaced; 1 core + 2 workers can satisfy a quorum of 2 over a 3-core config while Rabia has no voter majority.
2. **Half-wired machinery** — the worker tier is scaffolding with three severed wires: community formation never runs (`GroupMembershipTracker` has no feed), worker slice deployment has no consumer (`WorkerDeploymentManager.onDirectivePut` has zero callers), and Tier-2 observation has an engine but no ignition (`SpokesmanKey` has no assignment producer; the spec'd `HealthReconciler` was never built). Worker slices, communities, and spokesmen are all unreachable end-to-end.
3. **The #241 gap proper** — no desired-state, no stable community identity, no fan-out budget, no role/source-aware seeding or provisioning — with the design direction (source-as-community-key, governor-delegated reconciliation, FSM connectionSet seam) already sketched in the ticket comments and the FSM-unification spec.

The architecture-audit root pattern (multiple competing authorities) is poised to **recur in the worker tier**: #241's comment 2 warns that without the worker `connectionSet` projection, worker transport "silently stays on the legacy authority and re-opens the multi-authority gap." The same applies to counting: today's role-blind `countedMembers()` is a second authority problem in miniature.

---

## 1. Current state — end-to-end worker lifecycle (as implemented)

**Roles: three vocabularies for one concept.** Config `NodeRole{CORE,WORKER,SPOT}` (`aether-config/.../cluster/NodeRole.java:14-17`), transport `NodeRole{ACTIVE,PASSIVE}` (`consensus/.../net/NodeRole.java:23`), and the self-asserted SWIM label `role` from `AETHER_ROLE` (`Main.java:537-538`). Transport `PASSIVE` is **never produced** anywhere (`Main.java:437` always ACTIVE; SWIM rebuilds peer NodeInfo hardcoded ACTIVE, `SwimProtocol.java:969`) — its ~12 filter sites are all no-ops.

**Join.** Workers join exactly like cores: SWIM ANNOUNCE, symmetric probing/gossip, zero role awareness in SWIM. On `NodeJoined`, leader CDM assigns role: core while `activeNodes().size() < effectiveCoreMax`, else WORKER, via consensus `ActivationDirectiveKey` (`ClusterDeploymentState.java:292-296, 619-643`). The worker flips to forwarding mode and constructs `WorkerBootstrap`, `GovernorMesh`, `GroupMembershipTracker`, `WorkerDeploymentManager`, `GovernorAnnouncer` (`AetherNode.java:2918-2992`). A parallel, duplicate vocabulary (`TopologyGrowthMessage.{ActivateConsensus,AssignWorkerRole}`) exists alongside the KV directive path.

**Membership/transport.** `MemberDescriptor(address, role, source)` is landed and survives into DEAD (`MemberDescriptor.java:21-46`; descriptor-into-DEAD test exists). FSM-driven dialing (transport-as-dumb-executor) is landed **core-only**: `desiredConnections()` = core dial-set (`MembershipFsm.java:489-496`), identical on every node — a worker dials all cores. The worker projection ("governor + community peers") does not exist. `broadcastEligibleMembers()` has no role filter — explicitly deferred to #241 in a code comment (`MembershipFsm.java:459-479`) — so consensus protocol traffic goes to workers.

**Community formation (emergent, unstable).** `GroupAssignment.computeGroups` chunks SWIM-alive workers by zone — where "zone" is parsed from the **NodeId prefix before the last dash** (`GroupAssignment.java:67-74`) — splitting zones over `maxGroupSize=100` into round-robin subgroups `groupName-i` (`:23-65`). Churn **renumbers communities and reshuffles members** — community identity is unstable, directly conflicting with `targetCommunity`-scoped slice directives and `DhtPartitionOwnershipValue.ownerCommunityId`. And in practice it never runs: `GroupMembershipTracker.updateMember/removeMember` have **zero callers** (`GroupMembershipTracker.java:35-49`).

**Governor.** Election is incumbent-sticky, else lowest-alive NodeId (`GovernorElection.java:16-47`); `GovernorAnnouncer` writes `GovernorAnnouncementKey(communityId) → (governorId, members, tcpAddress, communityTerm, …)` through consensus every 30s (`GovernorAnnouncer.java:32-96`). Governors relay full Rabia decisions to followers (`DecisionRelay.java:22-87`), aggregate Tier-3 metrics (`WorkerMetricsAggregator`, 500ms `FollowerHeartbeat`), and emit `CommunityScalingRequest` evidence consumed by the leader `ControlLoop` (`AetherNode.java:3533`; `ControlLoopContext.java:303` with staleness+cooldown guards) — today's governor already **is** #241's governor; the sensor-only/leader-decides loop works.

**Spokesman (Tier 2).** `SpokesmanKey(coreNodeId) → (communities, epoch, status)` shards community observation across core nodes. The engine is complete and reactive (`SpokesmanPingLoop.java:44-383`: activates on a key naming itself, pings governors, aggregates `CommunityReport`s) — but **no production writer of `SpokesmanKey` exists**; the spec'd leader-resident `HealthReconciler` (`cluster-generation-spec.md:104-113`) appears only in javadoc. Quiescence permanently reports "N communities awaiting spokesman" (`ClusterQuiescenceEvaluator:126`).

**Deployment.** `PlacementPolicy{CORE_ONLY,WORKERS_PREFERRED,WORKERS_ONLY,ALL}` exists; CDM writes global and per-community `WorkerSliceDirectiveKey`s (`ClusterDeploymentState.java:1785-1833`) — but `WorkerDeploymentManager.onDirectivePut` has **zero callers**; the manager instance is created and dropped (`AetherNode.java:2974-2979`). Worker slice execution is also flagged designed-deferred (AG-3, `architecture-review-issues.md`: workers can't run slices; Phase 2 DHT).

**Heal/scale.** `LeaderReconciler` counts role-blind `countedMembers()` against `configuredCoreCount`; `CTM.provisionReplacement` hardcodes spec role `"core"` (`ClusterTopologyManagerRecord.java:455-478`); provisioning user-data hardcodes docker label `aether-role=core` and inherits `AETHER_ROLE` from the *bootstrapping host's* environment (`UserDataTemplate.java:227, 262-271`). Runtime config scaling ignores role entirely: `ScaleUp(source, WORKER, n)` mutates the **core** desired size (`ClusterConfigApplier.java:48-74`); `AddSource/AddRole/RemoveRole` are log-only.

---

## 2. Findings register (ranked)

### Class 1 — Correctness bugs live today (pre-#241, should not wait for it)

| # | Finding | Evidence |
|---|---------|----------|
| W1 | **Workers inflate quorum**: `healthyOnDutyCount = members.size()`, role-blind; 1 core + 2 workers passes `haveQuorum` for quorum=2 of a 3-core config with no Rabia voter majority. Same for `QuorumLossDetector`'s numerator | `PresenceMembershipView.java:40-52`, `TopologyObserver.java:666-679`, `PresenceSampler` (no role awareness) |
| W2 | **Dead core never replaced once workers exist**: heal deficit compares role-blind `countedMembers()`/`effective()` to `configuredCoreCount` — workers fill the gap arithmetically | `MembershipFsm.java:364-375`, `AetherNode.java:1784-1822` |
| W3 | **Role assignment miscounts**: `currentCoreCount = activeNodes().size()` includes workers → once workers exist, would-be cores below `coreMax` are mis-assigned WORKER | `ClusterDeploymentState.java:619-621` |
| W4 | **Provisioned nodes never stamped with worker role**: `provisionReplacement` hardcodes `"core"`; user-data hardcodes `aether-role=core`, inherits `AETHER_ROLE` from bootstrap host. Since the SWIM `role` label is the ONLY worker classifier, provisioned workers look like cores to every projection (dialed into the core mesh, counted in `coreMembers`) while running in forwarding mode | `ClusterTopologyManagerRecord.java:473`, `UserDataTemplate.java:227, 262-271` |
| W5 | **Runtime worker scale corrupts core size**: `ClusterConfigApplier` ignores `scale.role()` — `ScaleUp(source, WORKER, …)` calls `setDesiredSize` (core) | `ClusterConfigApplier.java:56-68` |
| W6 | **CDM `activeNodes()` includes workers** (its `isPassive` filter is structurally always false) → a READY worker enters `allocatableNodes()` and appears in **both** `AllocationPool.coreNodes` and `mainWorkers`; `CORE_ONLY` placement can land on a worker | `ClusterDeploymentState.java:660-667, 691-696`, `TopologyManager.java:91-94`, `AllocationPool.java:16-72` |

W1–W3 are the worker-tier echo of the architecture audit's "one quorum denominator" finding — counting authority is split between role-aware intent and role-blind implementation. The unification spec already plans the fix (`QuorumLossDetector → role=core, MEMBER-only (strict)`, spec §6) but rc1 code is role-blind.

### Class 2 — Severed wires (machinery exists, never engages)

| # | Finding | Evidence |
|---|---------|----------|
| W7 | Community formation never runs: `GroupMembershipTracker.updateMember/removeMember` zero callers; `myGroup` stays DEFAULT despite "ready for SWIM-based community formation" log | `GroupMembershipTracker.java:35-49`, `AetherNode.java:2991` |
| W8 | Worker slice deployment dead-ends: `WorkerSliceDirectiveKey` written by CDM has no consumer (`onDirectivePut` zero callers; manager instance dropped) | `AetherNode.java:2974-2979`, `ClusterDeploymentState.java:1785-1833` |
| W9 | Tier-2 never ignites: no `SpokesmanKey` assignment producer (engine complete, `HealthReconciler` never built); spokesman death leaves its communities unobserved — no cleanup/rebalance path | `SpokesmanPingLoop.java:120-144`, `cluster-generation-spec.md:104-113` |
| W10 | **Worker state path possibly dead entirely**: no consumer of `WorkerMutation` found outside the worker package — either worker slice-state reporting is dead code or rides an unfound generic route. Must be verified before #241 builds on it | `MutationForwarder.java:62-64` (core path = Broadcast) |
| W11 | Dynamic workers can't report a TCP address (`resolveSelfTcpAddress` returns `""`); workers in forwarding mode may not get KV snapshots (snapshot path presumes the never-produced "passive" classification) | `AetherNode.java:2994-3005`, `NetworkMessage.java:49` |

### Class 3 — Scalability posture (what breaks first at 1K–10K workers)

1. **Worker slice state through consensus** — per-worker `SliceNodeKey` puts; the O(N×S×M×I) blowup is quantified at ~60M entries/~1.5GB for 10K nodes (`kv-store-scalability.md:85-104`). Breaks first (~1K workers under deployment fan). The designed registry split (endpoints/routes out of consensus) is unimplemented.
2. **Full KV replication to every worker** — `WorkerBootstrap` full snapshot + `DecisionRelay` full decision stream: every worker ingests every cluster write.
3. **Mutation Broadcast to all cores** (`MutationForwarder.java:63`) — every worker mutation × core size.
4. **Single SWIM gossip pool at 10K** — at the practical limit; zone-chunking doesn't shard gossip itself; full-visibility membership on every worker.
5. **No fan-out budget anywhere** — `maxGovernorFanout` (the sizing constraint #241's recommended shape hangs on) doesn't exist; spokesman community lists are unbounded append; `GovernorAnnouncementValue.members` is O(community) per consensus write on every wobble.
6. **No worker readiness/drain plane** — leader-cached readiness covers core pongs only; no drain code exists under the worker tree.

### Class 4 — Design drift (docs/tickets)

- v2-spec still says `MembershipFsm` DELETED and NTT authoritative — both reversed by convergence-fsm/unification (same FSM name reused for a different state space; never reconciled).
- v2-spec §12.7 "a dead NodeId NEVER returns" vs convergence-fsm §9.2 incarnation-fenced same-id rejoin (newer, Docker-validated — older doc never updated).
- **Zone vs source**: #222 and `AETHER_ZONE`/cluster-management-spec are zone-centric; #241 comment 1 replaces zone with `source` (zone is a field *of* a source, `SourceProfile.java:19`). #222 needs re-expression in source terms.
- #220 (LB revival) depends on observing `NodeLifecycleKey` — deleted; readiness is heartbeat-only now. `aether/lb` source is gone from the build (stale jar + stale feature-catalog entry). Needs redesign; the natural shape is LB as worker ingress **and** host of the non-consensus endpoint registry the kv-store-scalability split calls for.
- Duplicate mechanisms unresolved: `TopologyGrowthMessage` vs `ActivationDirectiveKey`; `WorkerConfig.groupName/maxGroupSize` (static) vs `CommunityScalingEvaluator` (dynamic) as community-size source.

---

## 3. The #241 gap in context

What #241 adds (`observed → desired → reconcile → place`) is the right layer, and its comments have already made three load-bearing calls:

1. **`source` (or `source+role`) as the community key** — a source is already a homogeneous pool with declared per-role target counts; community desired-state largely falls out of existing `SourceProfile`/`RoleSubTable`/`ClusterBootstrapConfigDiff` config. Relationship: *community ⊆ (source, role)*, partitioned by governor fan-out cap.
2. **The FSM `connectionSet` seam is the integration point** — core = "all cores" (landed); worker = "governor + community peers" (the #241 deliverable). Without it, worker transport stays on the legacy authority — re-opening the multi-authority gap this stack just spent a month closing for cores.
3. **Per-member `(role, source)` descriptors are already in the FSM** (into DEAD) — source-aware replacement provisioning and operational visibility are wiring work, not data work.

**Dependency chain (critical path):**

```
[LANDED] convergence FSM + cutover; descriptor(role,source); FSM-driven core dialing; AETHER_SOURCE/ROLE
  → pre-#241 hygiene: role-filtered counting (W1-W3); role stamping in provisioning (W4-W5);
    role-vocabulary unification (3→1); transitive role/source gossip carrier (versioned MembershipUpdate)
  → #241 prerequisites: worker connectionSet projection; FSM→GroupMembershipTracker feed;
    committed (not derived) community identity
  → #241 proper: community desired-state from (source,role) targets + fan-out cap;
    growth comparator (spread-then-pack default); per-community FSM (FORMING→ACTIVE→DEGRADED→DISSOLVING);
    governor-delegated reconciliation (leader = core + community-set + policy);
    spokesman assigner == the leader-side community reconciler (one component, two jobs);
    seeding = oldest-stable preferential attachment (FSM join-times are free)
  → parallel, for usefulness: AG-3 worker slice execution; worker state path decision (W10 + registry split);
    worker readiness/drain plane; LB ingress (#220 redesign)
```

**Sequencing observations:**

- **W1–W6 should not wait for #241.** They are small, role-filter-shaped fixes to landed code, and they're the kind of necessary-but-insufficient predicates that make later #241 debugging hell if left in (the #94 lesson: multi-mechanism bugs hide behind each other).
- **Community identity must become a committed fact before any consumer keys on it.** `targetCommunity` directives and DHT ownership already key on an identity that today is a round-robin chunk number that renumbers on churn. The membership-placement-split direction ("placement = external facts the core authors") gives the answer: communityId as a consensus-committed atom, governor-announced membership as the observed view against it.
- **The spokesman assigner and the community reconciler are the same component.** The spec's missing `HealthReconciler` (assign communities→cores) and #241's leader-side reconcile (maintain community set + policy) have identical inputs and cadence. Building them as one leader-resident reconciler avoids a fourth topology authority.
- **Decide reconciliation ownership now: the evidence favors governor-delegated.** #235's verdict (core ≤~15; 10K scale lives in worker tiers), #178's shape (sensor-only members, governor decides, spokesman relays), and the existing scaling-evidence loop all point the same way. The leader keeps O(#communities): community set, targets, spokesman assignment. Governors keep O(community): member reconciliation against the target. #241's open decision #2 is effectively already made by #235 — it should be closed, not left open.
- **#178's caution transfers**: Tier-1's term-fencing black-hole (no-pong-on-fence-reject) is dormant only because dispatch is single-pinger; Tier-2's multi-pinger spokesmen re-expose that class. Carry the fix pattern into the spokesman protocol.

---

## 4. Open questions / decisions for the design

1. Should workers leave `countedMembers()` entirely, or do quorum/heal consumers switch to a role-filtered `coreCounted()` — who else depends on the unfiltered set?
2. Can an observation-mode worker report READY today (`clusterNode::isActive` on pong)? Determines whether W6 (CORE_ONLY placement on a worker) is reachable now.
3. Which role vocabulary survives: delete transport `ACTIVE/PASSIVE` (vestigial) and standardize on config `CORE/WORKER/SPOT` carried in descriptors?
4. `TopologyGrowthMessage` vs `ActivationDirectiveKey` — which is canonical for role assignment?
5. Is `WorkerMutation` consumed anywhere core-side (W10)? Blocking for worker state design.
6. `handleCommunityScalingRequest` caps at `effectiveClusterSize()` — core size or worker count? Core size would neuter worker-tier growth.
7. Who cleans up ephemeral `SpokesmanKey`/`GovernorAnnouncementKey` when the writer dies?
8. `maxGovernorFanout` — value and derivation (relay bandwidth? heartbeat budget? decision-stream fan?).
9. Growth strategy ownership: global policy vs per-blueprint (#241 open decision 1) — zone-anti-affinity blueprints force zoned; packing workloads want depth-first.
10. Worker rejoin: does incarnation fencing apply identically, or is worker identity disposable (cheaper: treat workers as cattle, new NodeId per boot)?
11. Worker drain story (#189 overlap) — governor-mediated drain as part of the community FSM's DISSOLVING path?
12. Gossip carrier versioning for transitive role/source propagation (positional `@Codec` blocks additive fields).

---

## Appendix A — Worker membership in code (agent worker-membership-audit)

[Report as delivered]

**End-to-end map.** Two role taxonomies + one label: config `CORE/WORKER/SPOT` (`aether-config/.../cluster/NodeRole.java:14-17`); transport `ACTIVE/PASSIVE` (`consensus/.../net/NodeRole.java:23`); SWIM label `role` from `AETHER_ROLE` (`Main.java:538`). Self NodeInfo always transport-ACTIVE (`Main.java:437`); SWIM rebuilds peers hardcoded ACTIVE (`SwimProtocol.java:969`); no production site constructs PASSIVE.

Join: boots with `--peers`/`CLUSTER_PEERS` (`Main.java:440-466`), SWIM un-gated, ANNOUNCEs (`AetherNode.java:2901-2915`); SWIM fully role-symmetric. Leader CDM assigns role on `NodeJoined`: core if `activeNodes().size() < effectiveCoreMax`, else worker via `ActivationDirectiveKey` (`ClusterDeploymentState.java:292-296, 619-643`); WORKER → `activateWorkerMode` (`AetherNode.java:2918-2992`): `authorizeObservation()`, `ForwardingClusterNode`, worker subsystems. Parallel vocabulary `TopologyGrowthMessage.{ActivateConsensus,AssignWorkerRole}` (`TopologyGrowthMessage.java:8-14`).

Membership/transport: `MemberDescriptor(address, role, source)` (`MemberDescriptor.java:21-46`); `coreMembers()` = MEMBER+SUSPECT minus `role=="worker"` (`MembershipFsm.java:450-457`); `desiredConnections()` = core dial-set (489-496); `broadcastEligibleMembers()` no role filter, deferred to #241 (459-479); `countedMembers()` no role filter (364-371). Transport executor: `setDesiredConnections` (`QuicClusterNetwork.java:397`; `AetherNode.java:1953`); reconcile 1476-1494, defensive PASSIVE skip 1484-1485. Projection identical on every node — workers dial all cores.

Quorum: `haveQuorum` reads `healthyOnDutyCount >= quorumSize()` (`TopologyObserver.java:666-679`); `healthyOnDutyCount = members.size()` (`PresenceMembershipView.java:40-52`; `PresenceGenerationSnapshotSource.java:72-82`); PresenceSampler role-blind. Legacy fallbacks filter `role != PASSIVE` (no-ops, `TopologyObserver.java:579, 589, 793, 815`); `addNode` adds any node to `coreNodeIds` (429-431).

Deployment: `activeNodes()` = countedMembers minus always-false `isPassive` (`ClusterDeploymentState.java:660-667`; `TopologyManager.java:91-94`); `allocatableNodes()` = ∩ READY (691-696); `AllocationPool(coreNodes, mainWorkers, workersByCommunity)` (`AllocationPool.java:16-72`); `mainWorkers` from ActivationDirective puts/restore (508-512, 769-779); communities from `GovernorAnnouncementKey` (684-720); placement routing 1784-1804 → `WorkerSliceDirectiveKey` (1816+). DHT: only static `"core"` partition (`BootstrapModule.java:248-257, 329-332`).

Heal/scale: `LeaderReconciler` role-blind (`AetherNode.java:1784-1785, 1814-1822`; `MembershipFsm.wouldProvision` 373-375); `provisionReplacement` hardcodes `"core"` + unfiltered PEERS (`ClusterTopologyManagerRecord.java:455-478, 485-499`); `drainNode` role-blind (508-517).

Role/source data: `AETHER_ROLE/AETHER_SOURCE` in `IDENTITY_VARS` (`ClusterIdentityEnv.java:27-32`); only consumer of `role` is `MemberDescriptor.isCore()`; `source` stored, never consumed. `DiffAction(sourceName, role, count)`: runtime applier ignores both (`ClusterConfigApplier.java:48-74`). `ActivationDirectiveValue.WORKER` is the one working role mechanism.

Risks: (1) CRITICAL worker count inflation (quorum/heal/QuorumLossDetector); (2) HIGH PASSIVE dead code → workers allocatable; (3) HIGH provisioning never stamps worker role (`UserDataTemplate.java:227, 262-271`); (4) HIGH Tier-2 half-wired (`WorkerDeploymentManager` no callers `AetherNode.java:2974-2979`; `GroupMembershipTracker` no feed; `SpokesmanKey` no producer); (5) MEDIUM `ScaleUp(WORKER)` mutates core size; (6) MEDIUM `assignNodeRole` miscounts; (7) LOW replacement PEERS off workers; `resolveSelfTcpAddress` "" (`AetherNode.java:2994-3005`); broadcast to workers.

\#241 prerequisites present: descriptor+upsert; FSM projections + executor wiring; join-time role assignment; config vocabulary; KV key schema; worker scaffolding incl. scaling evidence loop (`ControlLoop.java:250-267`; `AetherNode.java:3533-3534`); governor announcements written/read. Missing: worker connectionSet; role-filtered counting; community-formation driver; spokesman producer; directive consumer wiring; role-aware seeding/provisioning; per-(source,role) runtime reconciliation; role attestation (`MemberDescriptor.java:43`).

## Appendix B — Worker-tier constructs (agent worker-tier-audit)

[Report as delivered]

Exists: roles/activation (above); community = emergent chunking (`GroupAssignment.java:23-74`: zone = NodeId prefix before last dash; `maxGroupSize=100` `WorkerConfig.java:35`; round-robin subgroups; renumbering on churn); governor election incumbent-sticky lowest-NodeId (`GovernorElection.java:16-47`); `GovernorAnnouncer` 30s consensus reannounce (`GovernorAnnouncer.java:32-96`; `AetherValue.java:454-623`); Spokesman per-core-node sharded Tier 2 (`AetherKey.java:1635-1663`; `SpokesmanPingLoop.java:44-383` reactive only — status echo `:120-144`, governor pings `:307-338`, `CommunityReport` aggregation `:340-365`); tier protocol (`ClusterSyncMessage.java:37-68, 125-136`; `CommunityReport.java:13-27`); Tier 3 (`WorkerMetricsPing/Pong`, `FollowerHeartbeat` 500ms, `WorkerConfig.java:37-38`); worker scaling evidence → leader ControlLoop (`ControlLoopContext.java:303, 569, 581`) — sensor-only/leader-decides; worker deployment keys + `WorkerDeploymentManager` (`:90-102, 147-150, 262-269`); `MutationForwarder` follower→governor→core-Broadcast (`MutationForwarder.java:62-64`); `DecisionRelay` 1000-deep (`DecisionRelay.java:22-87`); `WorkerBootstrap` full-KV pull (`WorkerBootstrap.java:25-90`); ephemeral keys (`EphemeralKeys.java:20,27`); observability (`ClusterGenerationAssembler:281, 245`; `ClusterQuiescenceEvaluator:126`; `ClusterTopologyRoutes.java:61`).

Designed-only: spokesman assignment/rebalance (`cluster-generation-spec.md:104-113, 299, 310, 321-322`; `HealthReconciler` javadoc-only); registry split (`kv-store-scalability.md:108-165`); passive-pool research §15; `maxGovernorFanout` (absent); membership-placement-split (draft 2026-06-01).

Mapping to #241: governor already is #241's governor; spokesman is the core-side observation shard, not a governor. Missing: community as governed entity (no desired size/growth/reconciliation; unstable identity); leader-side assigner/lifecycle; seeding (deferred per `membership-fsm-unification-spec.md:14, 81, 92-94`; community "derived downstream, never a descriptor field").

Scalability (what breaks first): (1) worker slice state into consensus ~1K workers (`kv-store-scalability.md:85-104`); (2) `WorkerMutation` consumer possibly absent — verify; (3) full KV replication to every worker; (4) mutation Broadcast × core; (5) `GovernorAnnouncementValue.members` O(community) per wobble; (6) single SWIM pool at 10K; (7) Tier 1 fine post-#235 but no worker drain/readiness plane.

\#235/#220/#178 implications: core ≤~15, no core-side task assignment, scale-out by community sharding (`session-handover-2026-05-27.md` §2); ASSIGNED/ACTIVE/FAILED spokesman lifecycle is the surviving assignment idiom; Tier-1 term-fencing black-hole class re-exposed by multi-pinger Tier 2; #178 wire protocol fully landed, only assigner missing; `aether/lb` deleted from build (stale jar; `feature-catalog.md:250` stale) — nothing routes external traffic to workers.

## Appendix C — Spec/ticket landscape (agent worker-specs-audit)

[Report as delivered]

Layers: L0 simple scheme (v2-spec §2-3, §10); L1 per-node FSM single authority + transport-as-executor Option A (convergence-fsm §3; unification §2.2-2.3, §4; descriptor into DEAD; connectionSet seam §8 — core landed, worker owned by #241 comment 2); L2 #241 desired-state (observed→desired→reconcile→place; per-community FSM; preferential-attachment seeding; RC2); L3 operator pools (bootstrap-spec source-centric: one zone per source REQ-5.1.3.1; closed role set REQ-5.1.6; `aether cluster scale <source> <role> --count N` REQ-10.2.1; community ⊆ (source, role) per #241 comment 1).

Status matrix: convergence FSM landed (cutover 2026-06-07); `memberDescriptor()` landed (+ DEAD-survival test `MembershipFsmTest.java:955-1010`); transport-as-executor landed core-only (`MembershipFsm.java:489-496, 814`; `AetherNode.java:3162`; `QuicClusterNetwork.java:245, 252, 398, 1429`); worker connectionSet designed-only; `AETHER_SOURCE/ROLE` landed (`ClusterIdentityEnv.java:31-32`); transitive role/source gossip designed-only (needs versioned carrier, unification §5.1); NTT→PresenceSampler demotion landed (no `NodeTopologyTracker` in tree); incarnation fencing landed Docker-validated (convergence-fsm §9.4: `BootEpoch` deleted, `terminalIncarnation` map, 16s rejoin); source/role/diff config landed in aether-config; worker substrate partially landed (`WorkerNode.java` gone; AG-3: workers can't run slices, Phase 2 DHT); community formation/growth/per-community FSM designed-only; source-aware provisioning designed-only; Tier-2 CommunitySync RC2-gated (#178 requires Tier-1 soak retrospective).

Contradictions: v2 "FSM removed"/"NTT authoritative" vs convergence-fsm/unification (name reused, text never reconciled); v2 §12.7 terminal-removal vs convergence-fsm §9.2 fenced rejoin (newer wins); zone-vs-source (#222 vs #241 comment 1; `SourceProfile.java:19`); leader-vs-governor reconciliation (#235 reversed control-plane-delegation-investigation; #178 + #241 shape #4 prescribe delegated; no doc states the resolved policy); #220 depends on deleted `NodeLifecycleKey` (readiness heartbeat-only, v2 I13); two `NodeRole` vocabularies + `AETHER_ROLE=active` string in tests (three total); cluster-management-spec flat `[cluster.workers] count` vs bootstrap-spec source model (no cross-marking); #214 largely obsolete (slot machinery deleted); #189 drain overlaps v2 §8 `DrainProcedure` (app-layer drain = #189's scope, names predate v2).

Critical path: (a) versioned gossip carrier for role/source; (b) worker connectionSet projection registered with FSM; (c) governor election / GroupMembershipTracker swap to FSM projection (unification §6 "deferred to #241") → #241 proper → parallel: AG-3 worker slices. Gates: #178 Tier-1 soak; #236 structurally addressed by `QuorumLossDetector → role=core MEMBER-only` migration (§6) + convergence-fsm I8.

Open decisions: community key (source vs source+role vs fan-out partitions); growth ownership (global vs blueprint); reconciliation ownership; `maxGovernorFanout` derivation; role vocabulary unification; gossip carrier versioning; worker control heartbeat/drain (v2 §7.5 pattern, epoch-fenced CommunitySync); worker rejoin semantics (fencing vs disposable identity); #220 LB redesign post-`NodeLifecycleKey`.
