# Topology / Membership / Leader-Election: a first-principles design

Branch: `release-1.0.0-rc1` · HEAD `84726a848` · 2026-05-13.

---

## 1 · The load-bearing insight

> **Membership is not a state. Membership is a folded log.**
>
> The cluster maintains a single, consensus-replicated, append-only stream of typed
> `MembershipDecision` records (the type already exists at
> `integrations/consensus/.../MembershipDecision.java`). Every membership-derived value —
> `MemberStatus`, `ClusterPhase`, `coreCount`, `onDutyPeers`, the SWIM-driven aggregator buffer,
> per-node alert sharding, CTM shortfall, "did this peer just fail?" events — is a **left-fold
> over the prefix the node has seen**. SWIM is an *input* to the leader's producer, not a
> parallel store of truth. KV `NodeLifecycleKey` is a *materialised view* of the fold — never
> queried for correctness.
>
> Every edge case in the brief collapses to the same question: *"does node X's fold trail node Y's?"*
> — and the answer is always *"deliver the missing suffix"*.

Three immediate consequences, each killing a bug class:

1. **One canonical query.** `MembershipView.snapshot()` becomes "fold of the log up to my locally
   committed offset". The SWIM ∪ KV race that strands stale `ON_DUTY` for SWIM-faulty peers is gone.
2. **One event channel.** The chaos failure ("NODE_LEFT not seen on the polling node") and the
   observability failure ("alert injected on node A, queried on node B") are the same bug: per-node
   in-memory buffers existed because there was no globally-ordered stream to subscribe to. Polling
   moves from `/api/events` to `/api/decisions?since=<offset>` and is trivially correct.
3. **Rolling upgrades are a schema problem on one channel.** Records carry a version tag; new
   readers understand old records; old readers skip forward-unknown records. No "FSM version"
   coordination because the FSM is the fold function — it lives in the binary and binaries upgrade
   one at a time.

---

## 2 · Architecture sketch

```
                                 +-----------------------------+
                                 |   Operator (CLI / REST)      |
                                 +--------------+--------------+
                                                | drain / decommission / force
                                                v
+-------------+    SwimObservation    +----------------------+     append    +------------------+
|    SWIM     |---------------------> |    Leader producer   |-------------->|                   |
| (per node)  |                       |   (membership.Mint)  |               |   MembershipLog   |
+-------------+    PeerConnected      |   pure decision fn   |               |  (Rabia replicated|
+-------------+ --------------------> |                      |    snapshot   |   append-only)    |
|    QUIC     |                       +----------+-----------+ <------------ |  offset 0..N     |
| (per node)  |                                  |                           +---------+--------+
+-------------+                                  |                                     |
                                                 | proposes Decision                   | committed
                                                 v                                     v
                                          via Rabia consensus              every node replays/tails
                                                                                       |
                                                                                       v
                                                            +----------+----------+----------+----+
                                                            |          |          |          |    |
                                                       Folder      Folder     Folder    Folder ...
                                                        on n1       on n2      on n3     on n4
                                                          |          |          |          |
                                          MembershipView<==+          +==>routing-table     +==> alertStore-by-key
                                          ClusterPhase<====+                                +==> NODE_FAILED metric
                                          CTM shortfall<==='----- same folder, fed offsets ------'
```

Key points:

- **One mutating actor** — the leader's `MembershipMint`. SWIM and QUIC are *inputs*. Followers
  never write. No self-write, no second writer, no escape hatch.
- **One replicated structure** — `MembershipLog`. Replaces "lifecycle KV + slot KV + phase KV +
  FSM shadow". The KV-Store still exists for unrelated purposes; `NodeLifecycleKey` becomes a
  derived cache materialised from the log.
- **Folders are identical pure functions** on every node. The folder subsumes today's
  `MembershipView` + `ClusterPhaseView` + `ClusterEventAggregator` + `MembershipFsm.fsmStates`.
- **Leader election sits unchanged on Rabia.** Leader identity flows in via `LeaderChange-to-self`;
  Mint starts/stops accordingly. The new leader continues from the previous leader's last
  committed offset because the log is consensus-replicated.

---

## 3 · State model

No FSM state stored anywhere. One decision-stream record type, one fold function. Everything
operator-facing is computed.

### 3.1 Decision record

```
sealed interface MembershipDecision {
    long          offset();              // assigned at append time by Rabia
    long          decidedAtMs();         // leader's clock at append (informational only)
    NodeId        peer();
    Incarnation   incarnation();         // see I-6 below
    int           schemaVersion();       // for rolling upgrades

    record NodeJoined           (..., JoinCause cause) implements MembershipDecision {}
    record NodeOnDuty           (...) implements MembershipDecision {}
    record NodeDrainRequested   (..., DrainReason reason) implements MembershipDecision {}
    record NodeDrained          (...) implements MembershipDecision {}
    record NodeFailed           (..., FailureCause cause) implements MembershipDecision {}
    record NodeDrainFailed      (...) implements MembershipDecision {}
    record NodeDecommissioned   (...) implements MembershipDecision {}
    record NodeRevived          (..., Incarnation newIncarnation) implements MembershipDecision {}
    record SlotProvisioned      (SlotId, ...) implements MembershipDecision {}
    record SlotClaimed          (SlotId, NodeId, ...) implements MembershipDecision {}
    record SlotAbandoned        (SlotId, ...) implements MembershipDecision {}
    record LeaderChanged        (NodeId newLeader, long epoch) implements MembershipDecision {}
}
```

### 3.2 Fold output (per-peer logical state, never stored)

```
record MemberFold(
    NodeId peer,
    Lifecycle lifecycle,        // UNTRACKED | PROVISIONING | JOINING | ON_DUTY | DRAINING |
                                // DECOMMISSIONED | FAILED_DRAIN
    Incarnation incarnation,
    long lastDecisionOffset,
    Option<SlotId> slot)
```

`MembershipView.snapshot()` returns `Map<NodeId, MemberFold>` — same shape as today, produced
entirely by the fold up to the locally-committed offset. No live SWIM at query time. SWIM
influences only the *next* decision the leader produces.

### 3.3 Transitions

Only the leader's Mint transitions; followers fold. Mint is a pure total function
`mint(currentFold, input) → List<Decision>` — same shape as today's `ClusterMembershipReducer`,
but it returns *log entries*, not (writes + effects). Effects (drain invocation, timers,
notifications) are produced by **folders** reacting to entries they just folded. The leader is
itself a folder. So leader and follower side-effects derive from the same code path on the same
input. Collapses today's "writes vs effects" duality.

---

## 4 · Invariants

**I-1 · Single producer, single channel.** All membership facts are records on `MembershipLog`.
Nothing else writes membership state. *Rationale*: every drift bug today traces to a fact existing
in two places (SWIM alive ∪ KV ON_DUTY, per-node aggregator, per-node alert map). One channel
makes drift impossible.

**I-2 · Folds are deterministic.** `fold(prefix)` is a pure function. *Rationale*: every node's
`MembershipView` is provably equal at the same offset. "View drift across nodes" becomes "did node
X finish folding offset N?" — a liveness question, not a correctness one.

**I-3 · Leader is sole proposer; consensus is sole arbiter.** Mint runs only on the leader; Rabia
assigns offsets. *Rationale*: removes the "two leaders during handoff" race; removes refractory
windows and tombstones (the G.1 cure).

**I-4 · Folders never block appends.** Folders are read-only. *Rationale*: a stuck follower (long
GC pause) lags but does not stall the cluster.

**I-5 · KV `NodeLifecycleKey` is a derived materialisation, never a query target.** Written for
legacy consumers; removed from any membership query path. *Rationale*: closes the "ON_DUTY KV
stale after SWIM-faulty" race; 4 truth stores → 1.

**I-6 · Identity = (NodeId, Incarnation).** Every `NodeId` carries a monotonic `Incarnation`
(persisted on the node). *Rationale*: solves container-restart, NodeId collision, and "operator
clears DECOMMISSIONED while SWIM still faulty" — only an incarnation bump can revive.

**I-7 · Time-step backward is never load-bearing.** Decisions carry `offset` (monotonic by Rabia)
and `decidedAtMs` (informational). All orderings use `offset`. *Rationale*: NTP / GC pauses cannot
produce wrong answers, only delayed ones. Wall clocks affect only operator-facing timestamps and
deadline timers (recomputed locally, not in the log).

**I-8 · Mixed-version readers tolerate forward unknown records.** Folders skip
`schemaVersion > localKnownMax`. *Rationale*: rolling upgrades work even if a new record type
appears before all nodes upgrade.

**I-9 · Quorum loss is fold-visible.** When Rabia loses quorum, the log stops; folders see no new
entries; `ClusterPhase=RECOVERING` (computed). *Rationale*: removes "COLD_BOOT stuck state" logic.

**I-10 · Integration tests poll offsets, not events.** `/api/decisions?since=<offset>` returns
committed entries `> N`. *Rationale*: chaos and observability suites become trivial — no per-node
aggregator to miss the event.

---

## 5 · Edge-case handling table

`Mint` = leader producer, `Fold` = per-node folder.

| Scenario | Mechanism | Outcome |
|---|---|---|
| Mass startup | Mint batches `NodeJoined`/`NodeOnDuty` in offset order; Rabia serialises | NORMAL when fold sees `onDuty ≥ quorum + stable window`; no leader storm |
| Slow incremental startup | Per-`SwimHealthy` → `NodeOnDuty(peer)` | Single ordered stream |
| Network flap | Hysteresis (N consecutive observations) before Mint emits | No log churn |
| Quick disconnect/reconnect | Hysteresis dominates | No-op |
| View drift across nodes | I-2; offsets exposed via `/api/decisions/offset` | Drift = offset lag, bounded by commit latency |
| Sudden disappearance (kill -9) | SWIM → Faulty → Mint → `NodeFailed` at offset N; all folds advance | Test polls `?since=N-1`, sees event |
| Asymmetric partition (A↔B) | Only leader's SWIM drives Mint; followers' views ignored | Cluster converges to leader's view; minority cannot propose |
| Slow link, not partition | Hysteresis (suspect-timeout) absorbs jitter | No false `NodeFailed` |
| Packet reorder / dup | Mint dedupes by `(NodeId, Incarnation)` | Idempotent |
| Stale SWIM observation | Mint discards observations with `Incarnation < current` | Ignored |
| DNS failure mid-flight | Behaves as transient flap; hysteresis recovers | No log entry |
| Same NodeId new incarnation | New container boots with `Inc+1`; Mint emits `NodeRevived(peer, newInc)` | Admitted as fresh; old record kept as audit |
| NodeId collision (misconfig) | Mint detects equal-`Inc` + different identity → admission rejected, operator alerted | Fail-loud |
| Lost incarnation counter | Mint sees `Inc < last-known`; admission rejected | Fail-loud |
| NTP step backward | I-7; orderings use offset | No corruption |
| Long GC on leader | Rabia re-elects; new leader resumes Mint from log | Fold-stable across switch |
| Long GC on victim | Mint emits `NodeFailed`; on return, victim revives if within window via `NodeRevived` | Explicit semantics |
| Drain + force decommission race | Rabia totally orders both calls; first wins; second nop | No race window |
| Decommission of current leader | Leader appends `NodeDrainRequested(self)`; hands off; new leader continues drain via fold | Smooth |
| Rapid add+remove same NodeId | Distinct offsets per event | Audit trail intact |
| Operator clears DECOMMISSIONED while SWIM faulty | Revival requires `Inc` bump (I-6); plain "clear" is a no-op | Chaos-revival storm eliminated by construction |
| Cloud quota/rate-limit | `SlotProvisioned(...status=Pending)` then `SlotAbandoned(reason)` on failure | Single log records attempt + outcome |
| F.2 rollback partial failure | `SlotProvisioned` appended only after `confirmProvisioned()`; provider rolls back before any log entry | Log never lies |
| Provisioned node never healthy | Join-deadline timer on leader → `SlotAbandoned`+`NodeFailed` | Slot recycled |
| Stale slot blocks new provision | `SlotAbandoned` clears from fold; CTM uses fold | No special-case GC |
| Loss of quorum (>f failures) | No new appends; fold stalls; `ClusterPhase=RECOVERING` | Operator-visible |
| 2-node split | Rabia requires both; both freeze; reconnect resumes | Standard |
| Even-sized split | Side with leader + quorum continues; other freezes | Standard |
| Quorum recovery | Rabia merges; folders fold suffix; NORMAL after stable window | Standard |
| Cluster size below target | Fold reports `onDuty<target` → CTM provider call → `SlotProvisioned`→…→`NodeJoined` | Unified path |
| Cluster size above target | Fold reports `onDuty>target` → CTM emits `NodeDrainRequested` | Symmetric |
| Reconfig small/large | Operator updates target; CTM diff drives Mint | No discontinuity |
| Split brain | Rabia precludes | Impossible at log layer |
| Cold restart of entire cluster | Each node replays persisted log; folder reconstructs fold | No special boot mode |
| KV log divergence (theoretical) | Rabia state-machine hash detects; abort + alert | Standard |
| Snapshot install race | Snapshots are full-fold serialisations + hash; mismatch → re-request | Standard |
| Stuck state | Impossible — no stored state to be stuck in | By construction |
| Event ordering inversion | Rabia is the order | By construction |
| Effect dropped during leader switch | New leader re-derives from log; `prepareDrain` is idempotent | I-4 |
| `DecommissionedAtomGc` race vs late event | `NodeForgotten(peer)` is itself a Mint emission; folders drop on seeing it | Race-free |
| Mixed-version new node, old cluster | I-8: skip unknown forward records | Rolling upgrade safe |
| `ENVELOPE_FORMAT_VERSION` mismatch | Mint rejects admission outside support window | Hard fail with diagnostic |
| **Per-node alert/trace race (failing today)** | Alerts emitted as records on the log; gateway queries the fold | Fix is "use the log", not "shard the gateway" |
| **NODE_FAILED skew (failing today)** | Test polls `/api/decisions?since=N` instead of `/api/events` | Bug class eliminated |
| mgmt-gateway routes mid-failover | Gateway is stateless; any node folds same log → same answer | Consistent by construction |
| Chaos vs real failure indistinguishable | Both produce `NodeFailed`; `cause` field distinguishes | Audit-visible |
| `MembershipView` staleness | Staleness = offset lag; exposed as `/api/decisions/offset` | Measurable, not hidden |
| `MembershipDecision` event vs query mismatch | Cannot occur: view IS the fold | By construction |
| Self-injection in `MembershipView` before SWIM | Mint emits `NodeJoined(self)`+`NodeOnDuty(self)` on quorum admission | Self treated like any peer |

---

## 6 · Migration path from H-series

Today: `MembershipView` = SWIM ∪ KV with overrides; `MembershipFsm` still writes `NodeLifecycleKey`
for legacy consumers; revival cell permanently nop; `MembershipDecision` exists but only 4 of 9
subscribers use it (5 still on KV-put). Each step below is one commit; build + tests green at every
step.

| Step | Action | What stays | What changes | What's deleted |
|---|---|---|---|---|
| **M1** | Expose `/api/decisions?since=<offset>` reading from the existing `MembershipDecision` event stream + KV-put events bridged into the same channel | Everything | New read-only endpoint added | Nothing |
| **M2** | Move chaos-test polling from `/api/events` to `/api/decisions`. Move observability-test alert injection to log-backed (`AlertRaised` records). | All current subscribers | Tests become offset-based; integration suite green | Per-node alert in-memory map |
| **M3** | Add `Incarnation` field to `NodeId` (persisted in `~/.aether/node-incarnation`); plumb through SWIM, QUIC, Mint. New `NodeRevived` decision. | Current `NodeLifecycleKey` writes | Mint emits `NodeRevived` on incarnation bump | Nothing |
| **M4** | Migrate the remaining 5 KV-put subscribers (`NodeDeploymentManager`, `GenerationSnapshotPublisher`, `BootstrapModule`, dashboard, `ClusterSyncCollector`) to subscribe to `MembershipDecision` instead. | `MembershipFsm` still writes for diag/legacy | Subscribers move | Nothing yet |
| **M5** | Replace `MembershipView` impl: SWIM-input is no longer consulted at query time; the view is now `fold(MembershipLog up to local commit offset)`. Cache invalidation = log advance. | Public interface (`MemberStatus`, `MemberView`) | Internals rewritten as folder | The "SWIM ∪ KV" derivation rule |
| **M6** | Move Mint to be the only writer of `NodeLifecycleKey`, with KV-write strictly as a materialised view of the fold (single applier on leader). Followers stop writing. | The KV atom (legacy consumers) | Writer count drops to 1 | `MembershipFsm.applyExternalLifecyclePut` follower paths |
| **M7** | Delete `MembershipFsm.fsmStates` shadow map; FSM becomes a stateless function `mint(currentFold, input) → List<Decision>` invoked by the leader. State lives in the log. | The pure-function reducer | FSM no longer maintains state | The in-memory FSM state map; the `priorLifecycle` map; the `slotIdToPeer` map (replaced by fold) |
| **M8** | Migrate `ClusterPhaseView` to fold-based: phase is computed from the fold + offsets, not from KV reads. Stable-window check uses log offsets + decidedAtMs informationally. | Phase-state public API | Internals fold-based | The `priorPhaseReader` cache (no longer needed) |
| **M9** | Rolling-upgrade dress rehearsal: bump `MembershipDecision.schemaVersion` to 2; introduce a benign new record type (`NodeAnnotation`); run mixed 1.0/1.1 cluster; confirm folders skip unknown forward records gracefully. | All consumers | Schema-versioning gate gains a test | Nothing |
| **M10** | Retire the dual-purpose KV puts as event channels. `NodeLifecycleKey` becomes purely a materialised-view cache. Consumers must subscribe to `MembershipDecision`. | KV cache for cold-boot reads | Event channel = log; query = fold | The "KV-put-as-event" notifications |

H-series stays in place through M1–M4 (no rip-and-replace). M5 makes the new model dominant; M7
deletes the FSM-as-stateful-actor; M10 retires the dual-purpose KV channel.

**For RC1 ship**, stop at M3: chaos and observability failures are fixed by M1+M2; M3 hardens the
chaos-revival cure permanently. Everything after M3 is architectural cleanup.

---

## 7 · One self-acknowledged weakness

**The model handles slow followers poorly under high decision-rate churn.**

If decisions appear faster than the slowest follower can fold, that follower's view lags. There is
no built-in back-pressure from Mint — Rabia commits at quorum speed; folders catch up at their own
pace. Concretely:

- A follower in long-GC sees no view changes for the pause, then a burst.
- A follower restoring from an old snapshot during incarnation churn may take seconds to converge.
- If chaos tests run at ~10–50 ms between decisions and a folder takes ~5 ms per step (realistic
  for production code that dispatches alert subscribers as a side effect), a queue accumulates.
  This appears as test flake under the chaos workloads we care about — not as a wrong answer, but
  as a *late* answer.

Mitigations not baked into the design above: pre-fold compression of N consecutive decisions about
the same peer, snapshot install when lag exceeds a threshold, decoupled side-effect dispatch
(fold runs at memory speed; effects drain async).

The honest trade: this design optimises for *correctness under adversarial conditions* at the cost
of *worst-case latency on slow followers*. On a healthy cluster invisible; on a cluster with one
degraded VM, that node visibly lags — and test infrastructure must treat offset-lag as
first-class rather than assuming instant convergence.

I'd still take this trade. The current architecture's failure mode is **wrong answers** (stale
`ON_DUTY` for failed peers, missing `NODE_FAILED`, gateway routing inconsistency); the proposed
architecture's failure mode is **delayed correct answers**, bounded by Rabia commit latency plus
fold lag. Wrong-answer bugs are unbounded; delay bugs are bounded.
