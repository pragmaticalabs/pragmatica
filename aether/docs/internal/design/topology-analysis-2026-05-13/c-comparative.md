# Aether Topology / Membership / Leader-Election — Comparative Analysis

**Date:** 2026-05-13 · **Branch:** `release-1.0.0-rc1` · **HEAD:** `84726a848`
**Lens:** External-pattern synthesis · **Author:** spec-writer (autonomous)

This document audits how seven battle-tested distributed systems handle the scenario list captured in the brief, distils the patterns that are most broadly applicable to Aether's givens (Rabia leaderless consensus + SWIM + QUIC + Pragmatica functional types + rolling upgrades), and synthesises a design that closes the open chaos failures while preserving the H-series structural win (`MembershipView`).

The lens here is deliberately wide. Edge-case rows trace back to specific code locations in the current implementation only where the comparison hinges on it.

---

## 1 · Comparative Scenario-Handling Matrix

Columns: **HC-Serf** (HashiCorp Consul / Serf SWIM + Lifeguard), **etcd/K8s** (etcd raft + `coordination.k8s.io/v1 Lease`), **ZK** (ZooKeeper ephemeral nodes + watches), **Akka** (Akka Cluster CRDT membership + SBR), **Cass** (Cassandra gossip generation/version + hinted handoff), **Cock** (CockroachDB epoch-based leases + liveness range), **Nomad** (Nomad raft + Autopilot dead-server cleanup), **Aether-now** (current H-series, this repo).

Citations use `[N]` referencing the §7 References. Where a row says "—" the system does not directly address the scenario in primary docs.

| # | Scenario | HC-Serf | etcd/K8s | ZK | Akka | Cass | Cock | Nomad | Aether-now |
|---|---|---|---|---|---|---|---|---|---|
| N1 | Mass startup (cold boot) | Seeds list + push/pull on join [4] | Static initial-cluster discovery [13] | All clients race watches; leader election via sequential znodes | Seed nodes converge via gossip; "joining" until leader sets "up" [6] | Seeds in `cluster.yaml`; generation distinguishes restarts [9] | Liveness range bootstrapped via meta-range leases [11] | Raft bootstrap on N=3 servers; Autopilot waits for stability [15] | TOML peers + QUIC-bridged `PeerConnected→SwimHealthy` (F.4, `MembershipFsm.onPeerConnected`) |
| N2 | Slow incremental startup | Suspicion timer dynamically extended under load [1][3] | Learners (3.4+) join non-voting, promoted after catch-up [12] | Sessions stretched by tickTime × min/maxSessionTimeout | Leader withholds "up" transition until convergence [6] | Generation-counter per restart; old version ignored [9] | Liveness-record acceptance does not require gossip [11] | Autopilot `min_quorum` + `server_stabilization_time` [15] | KV `JOINING` + `JOIN_DEADLINE` timer in reducer |
| N3 | Network flap (transient) | Lifeguard refute path; suspect→alive retraction via incarnation bump [1][2] | Lease renewal threshold preserves liveness [13] | Session-timeout grace; ephemeral znodes survive flaps within ticks | Reachability re-derived per observer; flapping → unreachable, then reachable [7] | Generation unchanged; only version advances → state merges [9] | Epoch lease unaffected if liveness heartbeat resumes before expiry [11] | Autopilot keeps server if heartbeat resumes | `(OnDuty, SwimHealthy) → nop` (idempotent confirm) |
| N4 | Quick disconnect/reconnect (<5 s) | NACK-piggyback indirect probe absorbs jitter [4] | kubelet 10 s heartbeat / 40 s NotReady threshold [10] | Session keep-alive within tickTime survives | Phi-accrual detector adapts threshold [7] | Schema gossip carries application_state versions [9] | Lease epoch unchanged | Heartbeat interval << dead-server window [15] | SWIM probe interval; QUIC bridge re-fires `PeerConnected` |
| N5 | View drift across nodes | Anti-entropy push/pull every 30 s [4] | Single source of truth (etcd) — no drift | All clients read via leader; watches are linearisable | Gossip seen-set tracks convergence; leader blocks on convergence [6][7] | (gen,ver) tuple merge → eventual convergence [9] | Liveness range gossiped only by leader; readers tolerate staleness [11] | Raft FSM replicated state | **GAP**: SWIM per-node + KV replicated → `MembershipView` reconciles but emitted events asymmetric (e.g., `NODE_FAILED` skew) |
| N6 | Sudden disappearance (hard crash) | SWIM probe-fail + indirect probes → suspect → confirm [3][4] | Lease TTL expiry → API server marks NotReady → eviction taint [10] | Session expiry deletes ephemeral znodes; watchers notified [8] | Failure detector trips → unreachable; SBR decides downing [7] | Detected as "DN" (down) after PHI threshold; hinted handoff buffers writes [9] | Liveness epoch unchanged → next ack fails → new leaseholder bumps epoch [11] | Autopilot purges dead server within `last_contact_threshold` [15] | Reducer `(OnDuty, SwimFaulty) → DECOMMISSIONED` write |
| N7 | Asymmetric partition (A sees B, B does not see A) | Indirect probe via K random members validates [1][3] | API server is partition-of-one; clients on minority lose lease | Quorum-based: minority sees session expired | Phi-accrual is local per observer — both halves may mark each other unreachable [7] | Gossip resolves via 3rd-party witness | Liveness range leaseholder authoritative [11] | Raft quorum loss on minority side | **GAP**: SWIM "indirect ping" via 3 random peers exists in `org.pragmatica.swim`, but `MembershipView` is local-only — no cross-node arbitration |
| N8 | Slow link (high RTT) but not partition | Lifeguard NACK + dogpile dampener [1] | Lease renewal misses → false NotReady (known TODO upstream) | Tunable tickTime + sessionTimeout | Phi-accrual adapts to RTT distribution [7] | Phi-style FD on top of gossip [9] | Liveness uses fixed-window heartbeat (less RTT-adaptive) | Configurable heartbeat timeouts | SWIM `suspectTimeout` not adaptive (Lifeguard not implemented) |
| N9 | Packet reorder / duplication | Incarnation number monotonic — stale "suspect" refuted by newer "alive" [2] | TCP transport for raft + watches | TCP + ZXID order | Vector-style version in gossip; merge is monotonic [6][7] | (gen, ver) — older versions discarded [9] | Raft log index + lease epoch | Raft log index | QUIC streams provide ordering; SWIM observation `incarnation` field present |
| N10 | Stale SWIM observation arrival | Refute via incarnation bump [2] | n/a | n/a | Outdated reachability merged but newer wins by version [7] | (gen, ver) ignores old [9] | Liveness epoch monotonic [11] | n/a | **GAP**: reducer ignores `incarnation` field in `SwimHealthy`/`SwimFaulty` events; relies on event ordering |
| N11 | DNS resolution failure mid-flight | Retry on next probe; member stays in suspect [3] | kubelet retries; node stays Ready until lease expires | Client retries with backoff | Akka uses static seed list + ARTERY; retries reachability | Cassandra retries seed contacts | Retries via gossip; new leader if liveness expires | Autopilot tolerates short outages | QUIC client retries; `DockerComputeProvider` has rollback (F.2) |
| I1 | Same NodeId, new incarnation (container restart) | Incarnation counter incremented on rejoin [2] | New kubelet sees existing Node; reuses Lease | Session resumes if SID match, else new session | Member with same address must rejoin under new UID [6] | Generation bumped on restart [9] | Liveness epoch incremented [11] | Same node-id replays raft log | **GAP**: Revival cell deleted (chaos cure) → if KV `DECOMMISSIONED`, operator must clear; no automatic re-admission |
| I2 | NodeId collision (misconfig) | Memberlist rejects duplicate name with conflict resolution [4] | Lease objects keyed by Holder; duplicate causes RV conflict | Sequential znodes prevent collision | UID separates address from identity [6] | Token collision is fatal — operator intervention | NodeId is unique per registered node | Bootstrap config + raft membership add | No collision detection (assumed unique by configuration) |
| I3 | Lost incarnation counter (disk wipe) | New incarnation = wall clock seconds [2] | Lease.spec.holderIdentity comparison | Session expires → ephemerals removed | Akka requires explicit `Cluster.leave` before rejoin | Generation = startup timestamp [9] | New liveness record with fresh epoch [11] | New raft snapshot reconstructs | KV replay reconstructs state; `incarnation` field unused |
| T1 | NTP step backward | Lifeguard uses monotonic ticks, not wall-clock [1] | etcd uses monotonic time for elections [13] | ZK leader uses logical clocks (ZXID) | Akka Phi-accrual relies on local monotonic time | Generation is wall-clock-based — vulnerable [9] | Hybrid Logical Clocks (HLC) [11] | Raft randomised election timeouts | **GAP**: `nowMs = System.currentTimeMillis()` in `MembershipFsm.translate()` — wall-clock |
| T2 | Long GC pause on leader | Lifeguard NMS / Dogpile dampens [1] | Lease renewal misses → step-down | ZK session expires; new election | Local awareness penalty during pause [1] | Heartbeat resumes; gen unchanged | Liveness lease epoch may expire → loses range leases [11] | Raft heartbeat misses → election | Rabia: leaderless, but leader-election sits on top — election re-runs |
| T3 | Long GC pause on victim (false faulty) | Lifeguard refute window held open longer for degraded members [1][2] | NotReady but pods stay until eviction grace | Session expiry then notify on reconnect | Refutation only succeeds if alive ping processed in time [1] | Suspect→refute via gossip exchange [9] | Liveness lease check rejects on epoch mismatch | Suspect window | **GAP**: refute path absent (no Lifeguard, no incarnation refute) |
| O1 | Graceful drain + force-decommission race | n/a (Serf has no drain concept) | PDB + drain controller + force-delete | n/a | Coordinated shutdown via Cluster Leave + leader transition [6] | `nodetool decommission` is sequential | Drain via API + force after timeout | Force flag sets `force-leave` | `(DRAINING, OperatorDecommission(force=true)) → DECOMMISSIONED` (reducer cell) |
| O2 | Decommission of current leader | n/a | Leader election re-runs on lease loss | New leader elected from remaining followers | Leader transfer before leave [6] | Token move + sequential decommission | Lease transferred to new replica [11] | Raft leadership transferred before leave [16] | Drain triggers Rabia leader change; CTM/MembershipFsm follow on takeover (G.4) |
| O3 | Add+remove same NodeId in seconds | New incarnation distinguishes [2] | Lease objects recreated with new RV | Session create/expire/create | Akka requires `Cluster.leave` to complete first [6] | Generation-counter distinguishes | New epoch on rejoin [11] | Autopilot waits for stabilisation | Pre-H caused revival storm; post-H: no revival → manual KV clear needed |
| O4 | Operator clears DECOMMISSIONED while SWIM still faulty | n/a | Delete Lease → kubelet recreates | n/a | n/a | n/a | n/a | n/a | KV removed → falls back to `MembershipView` rule "no KV + SWIM faulty → absent" |
| P1 | Cloud quota / rate-limit during mass provision | n/a | Cluster Autoscaler retries with backoff | n/a | n/a | n/a | n/a | Nomad scheduler retries placement | CTM provisions one-at-a-time; `DockerComputeProvider.rollback` (F.2) |
| P2 | Rollback partial failure | n/a | Pre-stop hooks + finaliser | n/a | n/a | n/a | n/a | Raft membership rollback | CTM provider rollback (F.2/F.3, this branch) |
| P3 | Provisioned node never reaches healthy (slot leak) | n/a | NotReady → taint → eviction | n/a | n/a | n/a | n/a | Autopilot dead-server cleanup [15] | `JOIN_DEADLINE` timer → `(JOINING, JoinDeadlineExpired) → DECOMMISSIONED` |
| P4 | Stale slot blocks new provision | n/a | Generation/ResourceVersion conflict | n/a | n/a | n/a | n/a | Autopilot purges [15] | `removeSlot(slotId)` on terminal transitions in reducer |
| Q1 | Loss of quorum (>f failures) | Serf has no quorum (gossip-only) | etcd refuses writes, becomes read-only | ZK halts on quorum loss | SBR rules: down side without majority [5] | Cassandra: read/write CL controls | Liveness range unavailable → no new leases [11] | Raft stops accepting writes | Rabia leaderless requires quorum; `ClusterPhaseView` → RECOVERING |
| Q2 | 2-node cluster split | n/a | etcd: 2-node cluster cannot tolerate any failure | n/a | SBR: both sides may down (lowest-address tiebreak) [5] | n/a (Cassandra needs ≥ RF replicas) | n/a | Autopilot requires 3+ servers | Min 5-node policy (MEMORY rule); 2-node not supported |
| Q3 | Even-sized split | n/a | Quorum impossible if exactly half | n/a | SBR keep-majority falls back to lowest-address [5] | n/a | n/a | Static-quorum forbidden | Rabia quorum = ⌊n/2⌋+1; even-split → no progress |
| Q4 | Quorum recovery after partition heals | Serf merges via push/pull anti-entropy [4] | Raft heals automatically; learners catch up [12] | Recovering replicas sync from leader | Reachability auto-recovers; Akka downs the losing side per SBR [5] | Gossip exchanges merge | Liveness epoch resumes [11] | Autopilot promotes healthy servers [15] | Rabia handles automatically; `ClusterPhaseView` → NORMAL after stable window |
| TR1 | Cluster size below target | n/a | HPA / Cluster Autoscaler | n/a | n/a | n/a | n/a | Nomad scheduler scales clients | CTM auto-provisions on shortfall |
| TR2 | Cluster size above target | n/a | Scale-down via CA + PDB | n/a | n/a | n/a | n/a | Nomad scale-down | CTM terminates excess (oldest-first heuristic) |
| TR3 | Split brain | Lifeguard dampens but does not prevent | etcd quorum prevents writes on minority | ZK quorum prevents | SBR keep-majority / lease / static-quorum [5] | Inconsistent until reconciled (hinted handoff replays) [9] | Liveness lease single-writer [11] | Raft quorum | Rabia leaderless quorum prevents committed divergence |
| TR4 | Cold restart of entire cluster from disk | Memberlist resumes from peers list | etcd WAL replay + raft join | ZK txn log replay | Akka requires explicit reformation | Generation bumped per node restart [9] | Liveness range rebootstrapped | Raft peers re-elect | KV snapshot replay; `MembershipFsm.replayFromKv` |
| TR5 | KV log divergence (theoretical) | n/a | Raft prevents by design | Atomic broadcast prevents | n/a (data is CRDT) | Read repair + AE [9] | Raft prevents | Raft prevents | Rabia prevents (1-year chaos-tested) |
| TR6 | Snapshot install race | n/a | Raft InstallSnapshot RPC [13] | ZK snapshot transfer | n/a | n/a | Raft snapshot | Raft snapshot | Rabia snapshot — out of scope here |
| SM1 | Stuck state | Watchdog + retry on next probe | Controller reconcile loops | Watches re-fire on session resume | Local recovery + manual `Cluster.down` | Repair via `nodetool` | Liveness epoch refresh forces resolution | Autopilot heals | Reducer is total function; "stuck" only by stuck KV — `DecommissionedAtomGc` clears |
| SM2 | Event-ordering inversion | Incarnation orders [2] | Raft log index orders | ZXID orders | Gossip merge is commutative [6] | (gen,ver) orders [9] | Raft log + HLC | Raft log | KV writes are consensus-serialised; SWIM events are wall-clock ordered (T1 hazard) |
| SM3 | Effect dropped during leader switch | n/a | Watch resume from last revision [13] | Watch re-fire | Leader actions retried on next leader [6] | n/a | New leaseholder retries [11] | Autopilot continuity | **G.4** rewires `LeaderChange → onPeerConnected` synthesis; drain/join resume (`resumeInFlightProtocolsIfLeader`) |
| SM4 | `DecommissionedAtomGc` race vs late event | n/a | Finaliser pattern | n/a | n/a | Tombstone TTL > gc_grace_seconds | Liveness TTL > gossip | n/a | **GAP**: revival eliminated; tombstone removed (H.4); if late event arrives after GC, peer re-enters as `UNTRACKED` |
| MV1 | New code joining old cluster | Memberlist protocol-version field [4] | etcd `--initial-cluster-state existing` + version negotiation | Backward compat per major | Akka rolling-upgrade docs require step versions | Schema-mismatch tolerant [9] | Cluster version negotiation | Raft protocol version negotiation | Slice envelope `ENVELOPE_FORMAT_VERSION` (jbct/slice-processor) bumped on codegen changes |
| MV2 | Envelope-version mismatch during rolling deploy | Protocol downgrade negotiation [4] | etcd v2/v3 dual API during upgrade | Per-message version field | Akka serialization-bindings versioning | (gen,ver) tolerates schema [9] | Mixed-version raft messages | Raft min/max protocol versions | Envelope-version check on slice load; **no** equivalent for membership wire-format |
| OB1 | Per-node alert/trace race (current failure) | n/a (gossip carries no app state) | etcd is single source | Persistent znodes survive across nodes | Cluster Sharding pins state | n/a | Cross-range queries via gateway | n/a | **GAP**: `injectedAlerts` / `traceStore` are per-node `Map`s; needs consensus or aggregating-gateway |
| OB2 | NODE_FAILED event delivery skew (current failure) | Gossip carries events to all nodes [4] | Watch on Event resource is cluster-wide | Watches cluster-wide [8] | Cluster events broadcast | Gossip [9] | Range-keyed event log | Raft-replicated event log | **GAP**: `ClusterEventAggregator` buffer is per-node; SWIM observation on node A → polled on node B sees nothing |
| OB3 | Mgmt-gateway routes mid-failover | Health-checked load-balancer | Service endpoints updated via EndpointSlice | Watch on `/services` | Cluster Sharding rerouting | Coord. node failure switches | KV gateway re-routes on lease epoch change [11] | Service-discovery via Consul | nginx mgmt-gateway DNS evaluated at config-load (`project_nginx_gateway_pitfalls`) |
| OB4 | Chaos vs real failure indistinguishable | n/a | Drain annotation distinguishes intent | n/a | Cluster Leave intent | n/a | n/a | Drain intent | **GAP**: `OperatorDrain` event distinguishes intent, but post-drain failure is not labelled |
| OB5 | `MembershipView` staleness | n/a | Linearisable reads via raft | Linearisable watches | Local view per observer (eventually consistent) | Eventually consistent | Reads tolerate liveness staleness [11] | Raft reads | View recomputed at each call; SWIM + KV not snapshot-atomic |
| OB6 | `MembershipDecision` event vs `MembershipView` query mismatch | n/a | Watch + Get linearised on same revision | Watch + Get on same zxid | Gossip + query both local | Gossip is eventually consistent | Lease-epoch matched | Raft FSM single source | Two separate streams in current code (consensus event + view query) |
| OB7 | Self-injection in `MembershipView` before SWIM started | n/a | Self-lease created at start | Self-ephemeral on session | Self always reachable | Local node is always alive [9] | Self heartbeat record [11] | Self-record on bootstrap | H.5 explicitly injects `self→HEALTHY` in view |

---

## 2 · Pattern Distillation

### 2.1 Patterns that survive Aether's constraints

**P-1 · Incarnation / generation counter as monotonic refute key.** SWIM, Cassandra, CockroachDB, and Memberlist all key state on a per-node monotonic counter. Stale observations and false suspicion fall out of contention without explicit refute logic at the consumer. *Aether already has the field* (`SwimObservation.incarnation`) but the reducer does not consult it (N10, SM2). **Adoptable as-is** — does not conflict with any given.

**P-2 · Anti-entropy push/pull (Serf) / seen-set convergence tracking (Akka).** Independent of the gossip event channel, every N seconds (Serf: 30 s) two members reconcile their full state. Closes N5/OB6: SWIM events delivered on one node propagate to others by structural pull, not just event push. Aether's KV-replicated `NodeLifecycleKey` is the consensus analogue — but per-node *derived* state (alert/trace buffers, event aggregator ring) is not. **Adoptable** — only the channel changes (consensus replication instead of gossip).

**P-3 · Lease-with-epoch (Cockroach) / Lease object (K8s).** A monotonically increasing per-holder counter. Aether's Rabia leader-election already provides leader-epoch semantics; CTM and the MembershipFsm tag writes implicitly by leader identity. Where this pattern adds value over the current design: **typed lease objects per-peer** (analogous to `coordination.k8s.io/v1 Lease`) instead of opaque `ON_DUTY` lifecycle writes — every renewal is a typed heartbeat carrying `holderIdentity` + `leaseDurationSeconds`. **Partially adoptable** — the Rabia leader writes the lease; followers read it.

**P-4 · Reachability is per-observer, convergence requires N observers agreeing (Akka).** Akka explicitly does *not* collapse failure detection to a single "is X alive" boolean. Each observer maintains a Reachability vector; the cluster reaches convergence only when all observers' vectors agree. This avoids the N5 drift problem by *defining drift as the steady state* and requiring an explicit convergence handshake before mutating membership. **Partially adoptable** — Aether has SWIM-per-node + Rabia-replicated KV; we can promote `MembershipDecision` to be the convergence point and treat per-node `MembershipView` as the local Reachability vector.

**P-5 · Learners / non-voting members (etcd).** Rolling upgrades benefit from a joining peer that takes log but not vote. Aether could classify `JOINING` peers as non-voting in Rabia-quorum accounting. **Partially adoptable** — Rabia is leaderless and consensus protocol must support phase-aware quorums; out-of-scope for v1 but worth a flag.

**P-6 · Autopilot dead-server cleanup with stabilisation window (Nomad).** Don't promote a new server into the voting set until `server_stabilization_time` has elapsed AND don't reap a dead server until `last_contact_threshold`. Aether's `MembershipFsm.JOIN_DEADLINE` is the join-side analogue. The reap-side analogue is the `DecommissionedAtomGc`. **Already adopted, just under-documented** — should be promoted to a spec invariant.

**P-7 · Single-writer for lifecycle, multi-reader for liveness (CockroachDB).** Cockroach's liveness range is single-leader-written but every node can read and act on the cached value, accepting staleness in exchange for availability. Aether's H.5 separation maps to this: KV is single-writer (leader); SWIM is multi-writer (each node observes locally) but is only **consumed as input** to the read-side view. **Already adopted** — formalise in spec.

**P-8 · Down rather than partition (Akka SBR).** When the cluster cannot converge, an explicit policy decides which side stays. Aether under Rabia *cannot commit* on either side of a quorum split, so split-brain is impossible — but during a partition both sides may *believe* they are healthy for the SWIM-derived view. **Adoptable** — `MembershipView` must consult `quorate` flag (`ClusterPhaseView.haveLeader`) before reporting peers as `ON_DUTY` on a non-quorate node.

### 2.2 Patterns rejected as incompatible with Aether's givens

**R-1 · Raft learner state machine (etcd).** Rabia is leaderless and does not have a separate log-replication phase a learner could catch up on. The "non-voting member" concept doesn't directly translate; Aether's analogue is `JOINING` (no quorum contribution until promoted) and it already exists. **Reject** the raft-specific implementation; **keep** the *intent*.

**R-2 · Session-based ephemeral nodes (ZooKeeper).** ZK's ephemeral-on-session model is tightly coupled to a single ZK session per client. Aether peers maintain multiple long-lived QUIC connections + a SWIM ping cycle — there is no single "session" to terminate. **Reject** as architectural style; the *event semantics* (delete-on-disconnect) are weaker than what Aether already gets from SWIM-departed.

**R-3 · CRDT-based membership state (Akka ddata).** CRDTs require commutative merge functions. Aether's `NodeLifecycleValue` already passes through Rabia consensus, which gives strictly-linearised writes; layering a CRDT on top is redundant and would weaken ordering guarantees we paid for. **Reject** — keep consensus-serialised writes.

**R-4 · Hinted handoff (Cassandra).** Tied to Cassandra's eventually-consistent write model. Aether's KV is linearisable; if a peer is unreachable, writes simply don't commit. **Reject.**

**R-5 · Phi-accrual failure detector (Akka).** Adapts to RTT distribution per peer. SWIM with Lifeguard already gives most of the benefit (dynamic suspicion); replacing the SWIM failure detector is out of scope. **Reject** — extend SWIM with Lifeguard instead (T2/T3 gap).

---

## 3 · Synthesised Design for Aether

The core insight: the H-series correctly identified **derived view > replicated truth** for queries, but did not extend that to **derived events > replicated puts**. The remaining failures (OB1, OB2, OB6) all stem from per-node materialisations of state that should be either (a) computed from inputs at read time or (b) replicated through the consensus channel that already exists.

### 3.1 Architecture sketch

```
                       ┌──────────────────────────────┐
                       │  Rabia consensus (given)     │
                       │  - leader election (top-of)  │
                       │  - linearisable KV writes    │
                       │  - MembershipDecision stream │
                       └────────────┬─────────────────┘
                                    │
                  ┌─────────────────┼─────────────────┐
                  ▼                 ▼                 ▼
        ┌─────────────────┐ ┌───────────────┐ ┌────────────────┐
        │ NodeLifecycle   │ │ NodeLease     │ │ ClusterEvent   │
        │ Key (operator  │ │ Key (new)     │ │ Log (new,      │
        │ intent only:   │ │ - holder      │ │ replicated     │
        │ JOINING /      │ │ - epoch       │ │ ring buffer)   │
        │ DRAINING /     │ │ - renewedAt   │ │                │
        │ DECOMMISSION / │ │ - swimHealth  │ │                │
        │ FAILED_DRAIN)  │ │ snapshot      │ │                │
        └────────┬───────┘ └───────┬───────┘ └────────┬───────┘
                 │                 │                  │
                 │                 │ (leader-only     │ (replicated;
                 │                 │  writes, every   │  every node
                 │                 │  ~3 s)           │  reads same
                 │                 │                  │  buffer)
                 ▼                 ▼                  ▼
        ┌──────────────────────────────────────────────────────┐
        │  MembershipView v2 (read-time pure function)         │
        │    inputs:                                           │
        │      - local SWIM HealthSnapshot                     │
        │      - NodeLifecycleKey (intent overrides)           │
        │      - NodeLeaseKey (consensus-stamped liveness)     │
        │      - quorate flag (from ClusterPhaseView)          │
        │    output:                                           │
        │      - Map<NodeId, MemberView>                       │
        │      - effective status with reason ("swim-faulty",  │
        │        "no-recent-lease", "operator-drain", …)       │
        └──────────────────────────────────────────────────────┘
```

The view becomes **3 inputs** instead of 2; the third input (`NodeLeaseKey`) is the consensus-stamped analogue of CockroachDB's liveness record and Kubernetes' Lease — written **only by the leader**, **once per renewal period (~3 s)**, carrying both the holder's identity (proves we're talking about the right incarnation) and a snapshot of the leader's SWIM view of that holder.

### 3.2 State model (per peer)

The reducer FSM (7 states × 8 events) is **kept**. What changes is the source of two events:

| Event | Pre-design (H-series) | Synthesised design |
|---|---|---|
| `SwimHealthy` | from local SWIM observation | from local SWIM observation (unchanged) |
| `SwimFaulty` | from local SWIM observation | from local SWIM observation (unchanged) |
| `SwimDeparted` | from local SWIM observation | from local SWIM observation (unchanged) |
| `LeaseExpired` (new) | — | from `NodeLeaseKey` TTL watcher (consensus-derived) |
| `LeaseRenewed` (new) | — | from `NodeLeaseKey` KV notification |

`LeaseExpired` is the **cluster-wide consistent** "node is dead" signal. SWIM events remain the **local fast-path** liveness signal. Both feed the FSM; the reducer's existing cells absorb both.

**Why two channels?** This is the key insight from comparing Cockroach (liveness lease) with Serf (SWIM gossip): SWIM is fast (sub-second detection on small clusters) but per-node; a consensus-stamped lease is slow (3 s renewal × 2-3 renewals to declare dead = ~10 s) but globally consistent. Aether currently has only the fast channel; downstream consumers reading `/api/events` on a node that didn't observe the failure see nothing (OB2). Adding the slow channel closes this without slowing the fast path.

### 3.3 Replicated event log (`ClusterEventLog`)

Replace the per-node `ClusterEventAggregator.buffer` with a **single Rabia-replicated ring buffer** keyed by monotonic event-id. Every node sees identical contents. Implementation: a new KV key `ClusterEventLogKey(eventId)` with a TTL-bounded sweeper; the existing `ClusterEventAggregator` becomes a *projection* over this KV range.

**Closes OB2** structurally. **Closes OB1** for the same reason if `injectedAlerts` and `traceStore` are migrated to the same key family (operator-injected diagnostics are *transient* events, replicated identically).

**Cost.** Writing every cluster event to consensus is bounded by max event rate; ring-buffer cap keeps storage flat. SWIM-derived events fire on state change, not on every probe — order of 10s/min in steady state, spikes to ~100/min during chaos.

### 3.4 Invariants (numbered for traceability)

- **V1 · Single derived view.** `MembershipView` is the only "is peer X currently ON_DUTY?" answer. All routing / CTM / dashboard / status-route readers MUST consult it. KV `NodeLifecycleKey.state == ON_DUTY` is **not** a queryable status — it is a back-compat event signal.
- **V2 · Single-writer for intent.** Only the Rabia leader writes `NodeLifecycleKey` (intent) and `NodeLeaseKey` (liveness lease). Followers refuse with a WARN log; clients are redirected to the new leader on the next request.
- **V3 · Two-channel liveness.** A peer is treated as `ON_DUTY` iff (a) SWIM-HEALTHY locally AND (b) `NodeLeaseKey` for that peer has been renewed within `2 × leaseDuration`. (a) alone tolerates leader-write delays during failover; (b) alone tolerates local SWIM-probe-window jitter; both together survive both.
- **V4 · No automatic revival.** `(DECOMMISSIONED, *) → DECOMMISSIONED` (existing H-series cure). Operator must clear KV explicitly. Re-joins as a new NodeId (or post-GC) re-enter through `(UNTRACKED, SwimHealthy)`.
- **V5 · Monotonic incarnation.** Reducer consults `SwimObservation.incarnation` and rejects events with `incarnation < last_seen_for_peer`. Closes N10/SM2.
- **V6 · Replicated event log.** Cluster events are a Rabia-replicated KV range; per-node aggregators are projections only. Closes OB2.
- **V7 · Quorum-aware view.** `MembershipView.onDutyPeers()` returns `List.of()` on a non-quorate node (consult `haveLeader`). Closes the partition scenario where minority side claims peers `ON_DUTY` based on local SWIM.
- **V8 · Mixed-version safety.** Membership wire-format (SwimObservation + KV value records) carries a `protocolVersion` byte. Reducer rejects unknown future versions with `nop`; serialisers tolerate missing future fields. Closes MV2.
- **V9 · Monotonic time.** All FSM timestamps come from `System.nanoTime()` for ordering; wall-clock only for human-readable audit. Closes T1.

### 3.5 Leader-election overlay (already on top of Rabia)

The current leader-election overlay is correct — Rabia's leaderless commit is *consensus*; "who is the leader for KV-write coordination" is a derived election among Rabia members. Three reinforcements:

1. **Lease-epoch on leader changes** (Cockroach pattern). When a new leader takes over, it increments `LeaderEpochKey` and prefixes all lease writes with the new epoch. Followers reject lease entries with a stale epoch — closes leader-handoff write races.
2. **In-flight protocol resume is leader-only** (already implemented, formalise). `resumeInFlightProtocolsIfLeader` (line 832-840 of `MembershipFsm.java`) is correct; **add** a check that the leader-epoch on resume is the current epoch.
3. **`MembershipDecision` is the cluster-decision event stream** (already exists in `org.pragmatica.consensus.topology`). The five `NodeLifecycleKey`-listening subscribers from §6 of the handover should migrate to `MembershipDecision`. This is the H-series follow-through.

---

## 4 · Edge-Case Handling Table (Synthesised Design)

| Scenario | Mechanism | Outcome |
|---|---|---|
| N1 mass startup | QUIC-bridge (existing) + lease-renewal handshake | All peers `ON_DUTY` within max(SWIM probe, lease renewal) |
| N2 slow incremental | `JOINING` + JOIN_DEADLINE; learner-equivalent | Peer not in view until both SWIM and lease confirm |
| N3 network flap | Lifeguard refute + lease 2× renewal grace | No state change unless flap > 2 × renewal |
| N4 quick disconnect/reconnect | SWIM probe re-confirm; lease unchanged | `nop` cell |
| N5 view drift | `MembershipView` recomputed; cluster-wide event log via V6 | All readers see identical answer |
| N6 sudden disappearance | Reducer `(OnDuty, SwimFaulty) → DECOMMISSIONED` | Within SWIM detection (~10 s) |
| N7 asymmetric partition | SWIM indirect probe + lease consensus-required | Minority side: V7 returns empty `onDutyPeers` |
| N8 slow link | Lifeguard adaptive suspicion | Suspicion held during high RTT |
| N9 reorder/dup | V5 monotonic incarnation | Stale event discarded |
| N10 stale SWIM | V5 + lease-epoch | Discarded |
| N11 DNS fail mid-flight | Provider rollback (F.2) | Slot freed, retried |
| I1 same NodeId restart | V4 + operator clears KV | Required operator action (chaos-cure preserved) |
| I2 collision | Config-validated NodeIds; reducer no-op on duplicate | Reject duplicate |
| I3 lost incarnation | Wall-clock startup time bootstrapped; lease epoch monotonic | New incarnation accepted |
| T1 NTP backward | V9 monotonic time | Reducer ordering preserved |
| T2 GC pause leader | Lifeguard + leader-epoch | New leader takes over; old lease epoch rejected |
| T3 GC pause victim | Lifeguard refute window held open | Reduced false positive |
| O1 drain + force race | Existing reducer cell; force wins | DECOMMISSIONED via force |
| O2 decommission leader | Drain triggers leader-change first | Drain completes via new leader |
| O3 rapid churn | V4 + new NodeId required | Single-pass without revival storm |
| O4 operator clears DECOMMISSIONED | Fall back to V-view rules | If SWIM still faulty → absent until SWIM heals |
| P1 quota | CTM serialises provisions | Retries with backoff |
| P2 partial fail | Provider rollback (F.2) | Slot freed |
| P3 never healthy | JOIN_DEADLINE → DECOMMISSIONED | Bounded slot lifetime |
| P4 stale slot | `removeSlot` on reducer terminal cells | No leak |
| Q1 quorum loss | Rabia refuses; V7 empty `onDutyPeers` on minority | RECOVERING phase |
| Q2 2-node split | Policy: minimum 5 (MEMORY); 2 unsupported | n/a |
| Q3 even split | Rabia ⌊n/2⌋+1; no progress on tie | RECOVERING until partition heals |
| Q4 partition heal | Rabia + V-view recomputes | NORMAL after stable window |
| TR1/2 scaling | CTM driven by `MembershipView.onDutyPeers()` count | Auto-converge to target |
| TR3 split brain | Rabia + V7 + V8 | Committed state never diverges |
| TR4 cold restart | KV replay (existing) | FSM reconstructed from KV |
| TR5 log divergence | Rabia prevents | n/a |
| TR6 snapshot install | Rabia (out of scope) | n/a |
| SM1 stuck | Reducer is total function; sweeper for `DECOMMISSIONED` KV | Bounded |
| SM2 reorder | V5 monotonic incarnation | Discarded |
| SM3 leader switch | G.4 + V6 replicated event log | No effect lost |
| SM4 GC vs late event | V4 + V5 | Late event arrives as new UNTRACKED, takes normal path |
| MV1 mixed version | V8 protocol byte | Old code tolerates new fields; new code tolerates missing |
| MV2 envelope mismatch | V8 + existing `ENVELOPE_FORMAT_VERSION` | Reject incompatible; warn otherwise |
| OB1 alert/trace race | Migrate to V6 replicated log | POST and GET land on same content |
| OB2 NODE_FAILED skew | V6 replicated event log | All nodes see same event |
| OB3 mgmt-gateway mid-failover | nginx upstream resolver fix (separate ticket) | Out of scope here |
| OB4 chaos vs real | `OperatorDrain` + drain-reason field on lifecycle value | Audit-distinguishable |
| OB5 view staleness | V3 two-channel; SWIM + lease both required for `ON_DUTY` | Bounded by max(SWIM, 2 × lease) |
| OB6 decision vs view | `MembershipDecision` subscribers + view both projected from same KV | Eventually identical |
| OB7 self-injection | H.5 self→HEALTHY (existing) | Self always present |

---

## 5 · Migration Path from H-Series

Five steps, each shippable independently. Numbered for issue-tracker scaffolding.

**M1 · Promote `MembershipDecision` to canonical event channel.** Migrate the 5 subscribers (`ClusterDeploymentManager`, `NodeDeploymentManager`, `ClusterDeploymentState`, `GenerationSnapshotPublisher`, `BootstrapModule`) from `onNodeLifecyclePut` to `onMembershipDecision`. After migration, SWIM-driven `NodeLifecycleKey` writes (the H.5 retention) can be deleted. **Closes the H.3 conflation cleanly.** Estimate: 1-2 days. Already roadmapped in handover §6 item 4.

**M2 · Add `NodeLeaseKey` and lease renewal loop.** New key family, leader-only writer, 3-second renewal period. Add `LeaseExpired` / `LeaseRenewed` events to `MembershipFsmEvent`. Extend reducer with 2 new event columns (14 new cells, mostly `nop`). View v2 consults lease as third input. **Closes V3 / OB5 / N6 cross-node delivery.** Estimate: 3-5 days.

**M3 · Replicated `ClusterEventLog`.** New KV range `ClusterEventLogKey`. `ClusterEventAggregator` becomes a projection. Migrate `injectedAlerts` and `traceStore` to same family. **Closes OB1 / OB2.** Estimate: 2-3 days.

**M4 · Lifeguard suspicion-timer extension + monotonic incarnation consultation in reducer.** SWIM module extension (separate from membership). Reducer reads `incarnation` field and gates against `last_seen_for_peer`. **Closes T2 / T3 / N10 / SM2.** Estimate: 2-3 days.

**M5 · V7 quorum-aware view + V8 protocol-version byte.** `MembershipView.onDutyPeers()` returns empty on non-quorate node. SWIM observation + KV value records carry leading version byte. **Closes TR3 (operationally) / MV1 / MV2.** Estimate: 1-2 days.

Total: ~10-15 engineering days, fully sequenceable; M1+M2 are the load-bearing structural change.

---

## 6 · Self-Acknowledged Weakness

**The lease-renewal channel (M2 / V3) reintroduces a centralised throughput dependency on the Rabia leader.** Every ~3 seconds the leader must successfully commit a `NodeLeaseKey` write per peer. On a 50-node cluster that's ~17 writes/second of pure liveness traffic; under load or during a leader-handoff this can starve operator writes or amplify the duration of "no fresh lease → peers marked stale" windows.

Cockroach mitigates this by **batching all liveness records in a single range** (one write covers many nodes) [11]. Aether's KV does not currently support batched single-write updates across keys; a naive implementation writes N keys per renewal cycle. Two follow-ups: (a) batch into a single `ClusterLeaseSnapshotKey` value (loses per-peer reasoning) or (b) extend `KVCommand` with a multi-put atom (best, but adds consensus surface area). Without this batching, M2 is correct but expensive at scale; the design assumes ≤50-node clusters during v1, which the project's published roadmap matches.

A subtler concern: the view's "ON_DUTY iff SWIM-HEALTHY ∧ lease fresh" rule means that a brief leader-handoff stall (Rabia re-elects fast, but lease-renewal-loop owner must restart) can cause the *whole cluster's view* to flicker peers to `UNTRACKED` for one renewal cycle. Mitigation: 2-cycle grace (`2 × leaseDuration`) in V3 — but this softens the lease's "globally consistent" property by exactly one cycle. The design accepts this softening; an alternative is for the renewal loop to be re-resumable mid-cycle by the new leader (carry `LeaseEpochKey` in the renewal payload), which is the strict-correctness escape hatch if production traffic surfaces the flicker.

---

## 7 · References

### Primary sources (cited inline as `[N]`)

1. Dadgar, J., Phillips, J. & Currey, J. "Lifeguard: Local Health Awareness for More Accurate Failure Detection." arXiv:1707.00788, 2017. <https://arxiv.org/pdf/1707.00788>
2. HashiCorp. "Making Gossip More Robust with Lifeguard." HashiCorp Blog. <https://www.hashicorp.com/en/blog/making-gossip-more-robust-with-lifeguard>
3. HashiCorp. "Failure detection in the era of gray failures." <https://www.hashicorp.com/en/resources/failure-detection-in-the-era-of-gray-failures>
4. HashiCorp. "memberlist — Golang package for gossip-based membership and failure detection." GitHub. <https://github.com/hashicorp/memberlist>
5. Lightbend. "Split Brain Resolver" (Akka core). <https://doc.akka.io/libraries/akka-core/current/split-brain-resolver.html>
6. Lightbend. "Cluster Membership Service" (Akka core). <https://doc.akka.io/libraries/akka-core/current/typed/cluster-membership.html>
7. Lightbend. "Cluster Specification" (Akka core 2.5). <https://doc.akka.io/libraries/akka-core/2.5/common/cluster.html>
8. Apache Software Foundation. "ZooKeeper Programmer's Guide" (current). <https://zookeeper.apache.org/doc/current/zookeeperProgrammers.html>
9. Apache Software Foundation. "Cassandra Architecture / Dynamo." Cassandra 3.11 docs. <https://cassandra.apache.org/doc/3.11/cassandra/architecture/dynamo.html>
10. Kubernetes Authors. "Nodes." Kubernetes docs. <https://kubernetes.io/docs/concepts/architecture/nodes/> · "Leases." <https://kubernetes.io/docs/concepts/architecture/leases/>
11. CockroachDB. "RFC: Range Leases" (`docs/RFCS/20160210_range_leases.md`). <https://github.com/cockroachdb/cockroach/blob/master/docs/RFCS/20160210_range_leases.md> · `pkg/kv/kvserver/liveness/liveness.go`. <https://github.com/cockroachdb/cockroach/blob/master/pkg/kv/kvserver/liveness/liveness.go>
12. etcd Authors. "Learner." <https://etcd.io/docs/v3.3/learning/learner/> · "etcd learner design." <https://etcd.io/docs/v3.4/learning/design-learner/>
13. etcd Authors. "Runtime reconfiguration." <https://etcd.io/docs/v3.4/op-guide/runtime-configuration/>
14. Das, A., Gupta, I. & Motivala, A. "SWIM: Scalable Weakly-consistent Infection-style Process Group Membership Protocol." Proceedings of the 2002 International Conference on Dependable Systems and Networks. <https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf>
15. HashiCorp. "Nomad Autopilot." <https://developer.hashicorp.com/nomad/tutorials/archive/autopilot> · "autopilot Block." <https://developer.hashicorp.com/nomad/docs/configuration/autopilot>
16. Kubernetes Authors. "kubernetes/pkg/controller/nodelifecycle/node_lifecycle_controller.go." <https://github.com/kubernetes/kubernetes/blob/master/pkg/controller/nodelifecycle/node_lifecycle_controller.go>

### Internal references (Aether)

- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/view/MembershipView.java` — H.1 derived view.
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/ClusterMembershipReducer.java` — 7 × 8 reducer cells; revival cell deleted at line 169-186.
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java` — wiring layer; `onPeerConnected` line 385, `resumeInFlightProtocolsIfLeader` line 832.
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/phase/ClusterPhaseView.java` — derived `ClusterPhase` view.
- `aether/node/src/main/java/org/pragmatica/aether/api/ClusterEventAggregator.java` — per-node ring buffer (OB2 site).
- `aether/docs/internal/progress/session-handover-2026-05-13.md` — full H-series narrative.
- `aether/docs/specs/cluster-membership-fsm-spec.md` — current FSM spec.
- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/...` — `MembershipDecision` event stream.
