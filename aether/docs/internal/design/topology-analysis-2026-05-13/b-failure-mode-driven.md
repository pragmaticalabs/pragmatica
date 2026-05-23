# Topology / Membership / Leader-Election — Failure-Mode-Driven Design

**Branch:** `release-1.0.0-rc1` · **HEAD:** `84726a848`. Lens: derive minimum mechanism per failure mode, then compose. Givens (not redesigned): Rabia, SWIM, QUIC, Pragmatica functional types, mandatory rolling upgrades. Current H-series (`MembershipView` = SWIM ∪ KV-overrides; revival cell permanently `nop`) is the right destination, but three observed failures (NODE_FAILED skew, per-node alert race, asymmetric partition) are not yet structurally addressed. This document treats those as primary constraints.

---

## 1 · Mechanism derivation table

| # | Scenario | Minimum mechanism | Also covers |
|---|----------|-------------------|-------------|
| S1 | Mass startup | **M1: incarnation epoch** on NodeId; every SWIM/KV/QUIC message carries it; stale-incarnation messages dropped | S5, S10, S12, S13, S20, S22 |
| S2 | Slow incremental startup | **M2: derive-on-read view** (no time-based ON_DUTY write) | S3, S4, S6, S38 |
| S3 | Network flap | M2 + **M3: writer-side dampening** (Rabia commits drive durable transitions; view filters mid-flap noise) | S4 |
| S4 | Quick disconnect/reconnect | M2 + M3 + M6 | S3 |
| S5 | View drift across nodes | **M4: events carry consensus (term, index)** — compare offsets not wall clocks | S20, S21, S37, S40 |
| S6 | Sudden disappearance | SWIM (given) + M2 | — |
| S7 | Asymmetric partition (A↔B mismatch) | **M5: reciprocal-observation gate** — `ON_DUTY→DECOMMISSIONED(SwimFaulty)` requires ≥ ⌈f+1⌉ distinct witnesses inside `witnessWindow` | S8, S11, S17, S29, S44 |
| S8 | Slow link, not partition | M5 + **M6: HEALTHY hysteresis** (debounce ≥ probeInterval × 2) | S3, S4 |
| S9 | Packet reorder/duplication | SWIM/QUIC + M1 | S10 |
| S10 | Stale SWIM observation | M1 — reject `obs.incarnation < lastSeen` | S9 |
| S11 | DNS failure mid-flight | **M7: connection failures aren't membership events** — DNS decays into SWIM SUSPECT first | S4 |
| S12 | Same-NodeId new incarnation | M1 + M12 | S13, S20 |
| S13 | NodeId collision (misconfig) | **M8: NodeId = (uuid, incarnation)**, uuid provisioned-once + persisted | S12 |
| S14 | Lost incarnation counter | **M9: bootstrap-time monotonicity** — read max from KV, +1 | S12 |
| S15 | NTP step backward | **M10: HLC on all transitions**; wall-clock observability-only | S8, S16, S17, S36 |
| S16 | Long GC on leader | M10 + **M11: leader-lease ≡ log advance** (not heartbeat) | S17, S19, S26 |
| S17 | Long GC on victim | M5 + M6 | S8 |
| S18 | Drain + force decommission race | **M12: monotone state lattice** — rank only increases | S20, S21, S38 |
| S19 | Decommission of current leader | M11 + **M13: separate leader-identity from membership** | S16 |
| S20 | Rapid churn (add/remove same id) | M1 + M8 + M12 | S12 |
| S21 | Operator clears DECOMMISSIONED while SWIM faulty | **M14: Remove ≠ revival** — re-join needs fresh JOINING + new incarnation | S18 |
| S22 | Cloud quota/rate-limit | **M15: idempotent provisioning slots** (present, `ProvisioningSlotKey`) | S23, S25 |
| S23 | F.2 rollback partial failure | **M16: Provider Provision → Result; error compensates with Terminate(slot)** | S22, S25 |
| S24 | Provisioned never healthy | **M17: JOINING deadline timer (HLC)** → DECOMMISSIONED + slot release (present) | S35 |
| S25 | Stale slot blocks new provision | **M18: CTM slot GC gated on JOINING terminal** (present) | S22 |
| S26 | Loss of quorum | Rabia + M11 → RECOVERING phase; view stops emitting ON_DUTY | S27, S28 |
| S27 | 2-node split | **M19: bootstrap requires ≥ 3** (2-node is config error) | S28 |
| S28 | Even-sized split | Rabia + M19 (odd) or M5 (asymmetric) | S26 |
| S29 | Quorum recovery post-partition | M5 + M11 → log resumes; view recomputes | S7 |
| S30 | Reconfig increment/decrement | M15 + **M20: reconfig is a single Rabia entry** | S22 |
| S31 | Split brain (stale leader writes) | M11 + **M21: term-tagged writes** rejected by Rabia at apply | S19, S26 |
| S32 | Cold restart of entire cluster | **M22: durable cluster-id + epoch on disk per node**; replay from log | S5, S26 |
| S33 | KV log divergence | Rabia (given) | — |
| S34 | Snapshot install race | **M23: receive fenced by `appliedIndex ≥ snapshot.index`** | — |
| S35 | Stuck state | **M24: per-persistent-state deadline timer (HLC)** — extend to PROVISIONING + DRAINING | S24 |
| S36 | Event ordering inversion | M1 + M10 + M21 | S9, S10 |
| S37 | Effect dropped during leader switch | **M25: idempotent effects + replay-on-takeover** (present, `resumeInFlightProtocolsIfLeader`) | S38 |
| S38 | DecommissionedAtomGc race vs late event | M12 + M14 | S20, S21 |
| S39 | New code joins old cluster | **M26: envelope-version per entry; leader writes new format only when `min(peer.version) ≥ targetVersion`** | S40 |
| S40 | ENVELOPE_FORMAT_VERSION mismatch | M26 | S39 |
| S41 | Per-node alert/trace race | **M27: aggregating read fan-out** (light) — `/api/alerts` calls all `MembershipView.onDutyPeers()` and merges | S42 |
| S42 | NODE_FAILED skew | **M28: cluster-scoped Rabia-replicated event log** — replaces per-node `ClusterEventAggregator` | S41, S43 |
| S43 | Mgmt-gateway mid-failover | M28 + **M29: gateway sticky by `cluster_epoch`**, 503 + Retry-After on mismatch | S31 |
| S44 | Chaos vs real failure | M5 + M6 + M12 — correctness identical | S7, S8 |
| S45 | MembershipView staleness (SWIM+KV non-atomic) | **M30: single-snapshot view computation** stamped `(swimEpoch, kvLogIndex)` | S46 |
| S46 | MembershipDecision vs View mismatch | **M31: MembershipDecision carries `(logIndex, hlc)`**; consumers `waitForView(index)` | S5, S37, S45 |
| S47 | Self-injection in view before SWIM started | **M32: self always-HEALTHY at view construction** (present, H.5) | S1 |

32 distinct mechanisms across 47 scenarios.

---

## 2 · Composition (ranked by leverage)

| Rank | Mechanism | Covers | Status today |
|------|-----------|--------|--------------|
| 1 | **M1** Incarnation epoch on every message | 8 | partial (Swim only; KV inconsistent) |
| 2 | **M10** HLC, no cross-node wall-clock compares | 5 | absent (`nowMs` is wall-clock) |
| 3 | **M11** Leader-lease ≡ log advance | 4 | absent |
| 4 | **M12** Monotone lattice / no revival | 4 | **present** (H.4) |
| 5 | **M2** Derive-on-read view | 4 | **present** (`MembershipView`) |
| 6 | **M5** Reciprocal-observation gate | 5 | absent — single-witness suffices today |
| 7 | **M28** Cluster-scoped replicated event log | 3 | absent (per-node aggregator) |
| 8 | **M31** Decision carries (logIndex, hlc) | 3 | partial — emitted but not stamped |
| 9 | M15/16/17/18 Slot lifecycle | 4 | **present** (G.3, F.2) |
| 10 | M26 Min-peer-version write gating | 2 | partial (constant exists; not enforced) |
| 11 | M25 Idempotent effects, leader replay | 1 | **present** (H `resumeInFlightProtocolsIfLeader`) |
| 12 | M32 Self always-HEALTHY at construction | 1 | **present** (H.5) |
| 13 | M14 No implicit revival on Remove | 1 | **present** (H.4) |
| 14 | M6 HEALTHY hysteresis | 1 (+amplifies M5) | absent |
| 15 | M30 (swimEpoch, kvLogIndex)-stamped snapshot | 1 (+amplifies M5) | partial — per-call build, no offset stamp |
| 16-32 | M3/M4/M7-9/M13/M19-24/M27/M29 | 1 each | mixed |

**Load-bearing eight** (M1, M10, M11, M12, M2, M5, M28, M31) cover 38 of 47 scenarios. The architecture pivots on those.

---

## 3 · Architecture sketch

```
┌──────────────────────── per-node process ─────────────────────────────────┐
│  QUIC transport       SWIM failure-detect        Rabia consensus           │
│  + incarnation tag    (M1, M5 k-of-n,            + Leader Election         │
│  (M1)                  M6 hysteresis)             (term, index)            │
│       │                       │                   M11 lease ≡ log advance  │
│       └───────────┬───────────┴───────────┬──────────────┘                 │
│                   ▼                       ▼                                │
│         MembershipObservationCollector                                     │
│         (stamps every input with HLC (M10) + sourceIncarnation (M1))       │
│                   ▼                                                        │
│         MembershipFsm  — leader-only writer                                │
│         M12 lattice; pure reducer; idempotent effects (M25)                │
│         Writes  → KV NodeLifecycleKey (term-tagged via Rabia, M21)         │
│         Emits   → MembershipDecision (logIndex, hlc) (M31)                 │
│             │                              │                               │
│             ▼                              ▼                               │
│   MembershipView                     ClusterEventLog                       │
│   derive(SWIM, KV)                   (M28 Rabia-replicated;                │
│   snapshot stamped                    leader appends; consumers            │
│   (swimEpoch, kvIdx) (M30)            follow by index)                     │
│             │                              │                               │
│             ▼                              ▼                               │
│   Consumers (CDM, CTM, mgmt API, dashboard) wait for                       │
│   view.appliedIndex ≥ decision.index before acting (M31)                   │
└────────────────────────────────────────────────────────────────────────────┘
```

Three flows; one writer (leader's FSM); one canonical query (`MembershipView`); one cross-node event substrate (`ClusterEventLog`).

---

## 4 · State model

States (monotone-ranked, M12):

```
UNTRACKED(0) ─┐                                ┌─▶ DECOMMISSIONED(6, terminal)
              │ SlotClaimed                    │
PROVISIONING(1) ─▶ JOINING(2) ─┐               │
                       ▲       │ SwimHealthy   │
                       │       ▼               │  OperatorDecommission(force)
                       │   ON_DUTY(3) ─────────┤  SwimFaulty (after M5 quorum)
              OperatorDrain │                  │  SwimDeparted
                       │    ▼                  │  JoinDeadlineExpired
                   DRAINING(4) ────────────────┤  DrainOutcome(success)
                       │    │ DrainOutcome(fail)
                       ▼    ▼
                  FAILED_DRAIN(5) ─────────────┘
```

**Rank-monotonicity (I-Lattice):** rank(S₂) ≥ rank(S₁) for every transition **except** via explicit operator override on a fresh incarnation (KV Remove + new JOINING + incremented epoch — M14). Eliminates revival cells and every "rare resurrection" mode at once.

---

## 5 · Invariants

1. **I-Single-Writer.** Only the leader's `MembershipFsm` writes `NodeLifecycleKey`. *Prevents follower drift; concentrates monotonicity enforcement.*
2. **I-Lattice.** No transition decreases rank. *Eliminates revival storms permanently; makes operator commands monotonic.*
3. **I-Term-Tagged.** Every KV write carries the Rabia term. *Stale-leader writes drop at apply (M21).*
4. **I-Incarnation-Monotone.** `nodeIncarnation(n)` strictly increases per process restart; receivers reject older. *Same-id new-process is unambiguous (M1).*
5. **I-HLC-Linearised.** All transitions carry HLC; cross-node ordering uses (logIndex, hlc). *NTP step-backward cannot corrupt ordering.*
6. **I-Reciprocal-Decommission.** Leader will not commit `ON_DUTY → DECOMMISSIONED(SwimFaulty)` until ≥ ⌈f+1⌉ peers report FAULTY inside `witnessWindow`. *Asymmetric partition + single-node mis-observation cannot decommission a healthy node (M5).*
7. **I-Lease-Equals-Log.** Leader liveness = log advancing at HLC rate ≥ minRate; followers detect failure by stall, not heartbeat absence. *Long GC on leader produces deterministic re-election; GC on victim does not (M11).*
8. **I-View-Snapshot.** `snapshot()` returns a `(swimEpoch, kvLogIndex)`-stamped picture; reads do not interleave with mid-flight writes. *Eliminates SWIM-vs-KV non-atomic race (M30).*
9. **I-Decision-Carries-Index.** `MembershipDecision` events carry `(consensusLogIndex, hlc)`; subscribers synchronise by index. *Effect-dropped-during-leader-switch becomes a no-op (M31, M25).*
10. **I-Versioned-Envelope.** Every log entry + KV value carries `envelopeFormatVersion`; leader writes new format only when `min(peer.version) ≥ targetVersion`. *Rolling upgrade safe by construction (M26).*
11. **I-No-Implicit-Revival.** `KV.Remove` on DECOMMISSIONED does **not** transition to ON_DUTY; rejoin requires fresh JOINING + new incarnation. *Operator clearing while SWIM faulty cannot resurrect a zombie (M14).*
12. **I-Cluster-Event-Log.** `/api/events` is served from a leader-appended, Rabia-replicated log; no per-node aggregator serves API reads. *NODE_FAILED visibility is symmetric across all nodes (M28); eliminates cluster-B 02-chaos flake.*
13. **I-Self-View-Always-Healthy.** `self → HEALTHY` injected from view construction. *Phase computation can bootstrap (M32).*
14. **I-Min-Cluster-3.** Bootstrap rejects 2-node configs. *2-of-3 minimum quorum (M19).*
15. **I-Reconstructible.** Local state is a pure function of (consensus log up to appliedIndex, local SWIM, local incarnation). *Cold-restart, snapshot install, leader takeover all use the same path.*

---

## 6 · Edge-case handling table

| Scenario | Mechanism | Outcome |
|----------|-----------|---------|
| Mass startup | M1, M2 | Peers admitted at SWIM admission; no write-herd |
| Slow incremental startup | M2 | Late joiner visible at SWIM admission |
| Network flap / quick disc-rec | M3, M6, M1 | Hysteresis absorbs; new-incarnation supersedes |
| View drift | M4, M30 | Bounded by replication lag; detectable by index compare |
| Sudden disappearance | SWIM + M5 | M5 prevents single-witness decommission |
| Asymmetric partition | M5 | Decommission requires k witnesses, not 1 |
| Slow link | M5, M6 | Hysteresis + reciprocal gate prevent decommission |
| Packet reorder / stale obs | M1, M10 | Stale incarnation dropped; HLC orders |
| DNS failure mid-flight | M7 | Decays into SUSPECT then FAULTY after k probes |
| Same-NodeId new incarnation | M1, M12 | New incarnation supersedes; old dropped |
| NodeId collision | M8 | Impossible by construction |
| Lost incarnation counter | M9 | Bootstrap reads max from KV |
| NTP step backward | M10 | HLC advances regardless of wall-clock |
| Long GC on leader | M11 | Log stalls → re-election |
| Long GC on victim | M5, M6 | Single-node observation insufficient |
| Drain + force race | M12 | Lattice; DECOMMISSIONED wins |
| Decommission of leader | M11, M13 | Handoff on log stall; new leader writes |
| Rapid churn | M1, M12 | Unique incarnations; no resurrection |
| Operator clears DECOMMISSIONED | M14 | Remove ≠ revival; needs fresh JOINING |
| Cloud quota / rate-limit | M15, M16 | Per-slot idempotency + rollback |
| Provisioned never healthy | M17 | JOINING deadline → DECOMMISSIONED |
| Stale slot blocks provision | M18 | CTM GC on JOINING terminal |
| Loss of quorum | Rabia + M11 | RECOVERING; no ON_DUTY emitted |
| 2-node split | M19 | Bootstrap rejection |
| Even-sized split | M19 / M5 | Quorum side proceeds |
| Reconfig | M15, M20 | Atomic single-entry |
| Split brain | M11, M21 | Old-term writes rejected at apply |
| Cold restart | M22 | Replay from log |
| Snapshot install race | M23 | Fenced by appliedIndex |
| Stuck state | M24 | Per-state deadline timer |
| Effect dropped on leader switch | M25, M31 | Replay; consumers wait for index |
| GC race vs late event | M12, M14 | No revival path |
| New code joins old cluster | M26 | Min-peer-version gating |
| Per-node alert/trace race | M27 / M28 | Aggregating fan-out (light) or replicated log (heavy) |
| NODE_FAILED skew | M28 | All nodes serve `/api/events` from same log |
| Mgmt-gateway mid-failover | M29 | Sticky by cluster_epoch; 503+Retry-After |
| View staleness | M30 | Snapshot internally consistent |
| Decision vs View mismatch | M31 | Consumer waits for `view.appliedIndex ≥ decision.index` |
| Self before SWIM | M32 | Static HEALTHY at construction |

---

## 7 · Migration path from current H-series

Ordered by leverage-per-disruption. Each step independently committable.

**Phase J1 — close observed-failure gap (cheap, RC1).**

1. **M27 (= M28-lite): aggregating fan-out** for `/api/events`, `/api/alerts`, `/api/traces`. Route handler calls all `MembershipView.onDutyPeers()` and merges. Removes cluster-B chaos flake and #219. ~200 LOC, no protocol change.
2. **M5 reciprocal gate.** Leader's reducer adds a witness buffer: SWIM observations from each peer tracked with HLC; `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` requires ≥ ⌈f+1⌉ distinct observers within 10 s. Pure function over (state, event, witnessSet). ~300 LOC.

**Phase J2 — structural correctness (RC2).**

3. **M10 HLC adoption.** Replace `System.currentTimeMillis()` in FSM events with HLC stamps. Wire `HlcClock` at startup; propagate through SwimObservation, MembershipFsmEvent, NodeLifecycleValue.updatedAt. ~500 LOC.
4. **M31 Decision indexing + subscriber migration.** Stamp `MembershipDecision` with `(logIndex, hlc)`; migrate the 5 KV-listener subscribers (CDM, NDM, CDS, GenerationSnapshotPublisher, BootstrapModule) onto `MembershipDecision`. Then SWIM-driven `NodeLifecycleKey` writes can be removed — KV becomes purely operator-override (handover §6 plan). ~600 LOC.
5. **M28 full: replicated `ClusterEventLog`.** New Rabia-replicated atom for cluster-scoped events (NODE_JOINED, NODE_FAILED, LEADER_ELECTED, PHASE_CHANGED). Replaces M27 lite. ~800 LOC.

**Phase J3 — robustness depth (post-RC1).**

6. **M11 lease ≡ log advance.** Replace leader-liveness heartbeat with "leader committed up to index N at HLC T"; failure detection switches to log-stall watchdog. Needs Rabia consultation. ~1200 LOC.
7. **M26 rolling-upgrade gate.** Per-message version field; leader enforces `min(peer.version)`. ~400 LOC.
8. **M6 hysteresis** in view. ~150 LOC.
9. **M1/M8/M9 incarnation hardening.** Audit every NodeId-bearing message + KV value. ~400 LOC (mostly mechanical).

**Phase J4 — tidy-up.**

10. M19 bootstrap rejection of 2-node configs.
11. M14 explicit no-implicit-revival semantics in spec.
12. M24 deadline for PROVISIONING + DRAINING (JOINING already covered).
13. Delete dormant `Decommissioned.swimDriven` field.

**RC1 cut.** J1 is mandatory (closes the two observed failures). J2/J3 are correctness amplifiers, not bug fixes. The current H architecture is correct in the absence of asymmetric-partition and slow-link false-positives, which are not on the chaos test's hot path.

---

## 8 · Self-acknowledged weakness

**The reciprocal-observation gate (M5) adds a witness-window of ~10 s on top of SWIM detection latency (10-15 s).** Common case (one node hard-fails, every peer observes FAULTY within 1-2 probe rounds) is harmless: the gate clears within `probeInterval × 2`. But in *partial network failure* (one peer's SWIM is degraded — high jitter, dropped probes — while underlying node is genuinely dead), the witness window extends decommission to ≥ 20-25 s, during which the leader routes slice work to a corpse and CTM does not yet provision a replacement.

The H-series moves in the opposite direction: zero witness-window, single-node observation suffices, accept the false-positive risk because the revival cell is `nop` (a wrongly decommissioned peer cannot rejoin without operator intent). **The redesign trades higher steady-state decommission latency for elimination of asymmetric-partition false-positive decommissions.** Whether that trade-off is right for Aether's workload (slices are short-lived; provisioning is cheap; CTM auto-heals in seconds) is genuinely arguable. A pragmatic compromise — **2-witness gate for follower observations and a 1-witness path for the leader's own observation** (the leader is by definition a quorum participant) — would cut latency for the common case while retaining asymmetric-partition protection. That compromise is not chaos-validated here and remains the design's most fragile assumption.

---

**Total mechanisms: 32. Load-bearing: 8 (M1/M2/M5/M10/M11/M12/M28/M31).**
**Delta vs H-series:** keep M2/M12/M25/M32 (already landed); add M5/M10/M11/M28/M31 (the missing five); harden M1/M26 (already partial).
