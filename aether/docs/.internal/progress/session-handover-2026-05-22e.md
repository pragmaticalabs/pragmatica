# Session Handover — 2026-05-22 (e) — Comprehensive

**Branch:** `release-1.0.0-rc1` | **HEAD:** `e15cc408d`
**Predecessors:** [22d](session-handover-2026-05-22d.md) (Phase 1 PR-A scaffolding) → [22c](session-handover-2026-05-22c.md) (Path 2 v2 + root-cause fixes) → [22b](session-handover-2026-05-22b.md) → [22 (no-suffix)](session-handover-2026-05-22.md) (config provisioning refactor + 5 follow-up fixes)
**Scope of this document:** the entire in-flight initiative — Cluster Convergence Reconciler (5 phases, ~5 PRs) + the RC1 release status — *not* just this session segment. Read this first; the chronological predecessors document deltas.

---

## 1. Big picture

### 1.1 Where Aether sits in the release cycle

- **Active branch:** `release-1.0.0-rc1`. Commits go directly to this branch; never a feature branch on the release branch.
- **HEAD:** `e15cc408d` — `fix(ctm): gate leader-failover provisioning on phase!=COLD_BOOT`. Three commits ahead of `origin/release-1.0.0-rc1` (`c8d6f6faa`, `1846a618c`, `e15cc408d`). Not yet pushed.
- **Working tree:** large and uncommitted. ~100 modified Java files in `aether-deployment/`, plus the slice KV records, two new sealed-interface roots, the `audit/` sub-package, the convergence reconciler spec, four handover documents, and the two new RC1 fixes documented below.
- **`./build.sh` state:** RED on Step 2 (format-lint) — pre-existing, blocked by Task #13 (26 JBCT-RET-01 violations across aether-stream/aether-metrics/aether-deployment). Use `mvn -pl <module> install -DskipTests -am` for focused builds until that lands.
- **RC1 release bar:** 15/15 integration suites across both Docker and Hetzner. Currently: ~13/15 on cluster A (this segment's run hit 34p/2f, with both failures pre-existing and not in Phase 1 scope). Cluster B baseline is wedged from this segment's cross-contamination — needs restore before measuring.

### 1.2 What is the Cluster Convergence Reconciler initiative

A multi-PR overhaul that closes the silent-divergence gap between Aether's four parallel "membership" state machines: Rabia generation, SWIM gossip, NodeLifecycleKey FSM, and MembershipView. Pre-fix, propagation failures (lost events, leader-handover during a write window, SWIM probe gaps) leave divergence in place forever and break operator workflows.

**Structural deliverables (in plan order):**

1. **FSM collapse** — `NodeLifecycleState` from 6 values to 4 (`JOINING` / `ON_DUTY` / `DRAINING` / `STOPPED`), with `StopReason` sidecar (`GRACEFUL` / `FORCED` / `DRAIN_FAILED`).
2. **Node-local SYNCING sub-phase** — invisible to KV; signalled by a new `readyCandidate` field on `ClusterSyncPong`. Leader emits a reducer command on receipt. Leader-side `activeSyncHolds` protects busy syncing nodes from force-decommission.
3. **Command primitive** — input alphabet of the FSM reducer extended from Event to Event | Command. Single ingress for state changes (`LifecycleCommand` sealed interface + `LifecycleWriter.applyCommand`). All kind-2 ("external-trigger") call sites migrate.
4. **LifecycleReconciler** — leader-only periodic component during NORMAL phase. Observes lifecycle/SWIM/generation/holds; emits commands when divergence persists past calibrated budgets.
5. **Operator API + CLI for ForceDecommission** + audit channel (re-uses existing aether-stream topic `audit.lifecycle.commands`).

**Source of truth.**

- Spec: `aether/docs/specs/cluster-convergence-reconciler-spec.md` (untracked, written this multi-session arc).
- Plan: `~/.claude/plans/stateless-twirling-glade.md` (probably gone — plan files don't survive sessions cleanly; the plan content is duplicated below in §3).

Pre-GA → no backward compatibility burden. Schema and protocol changes are free.

---

## 2. Phase status — top-level table

| Phase | PR | Scope | Status | Notes |
|---|---|---|---|---|
| 1 | PR-A | FSM collapse + Command primitive + kind-2 migration | **~75% complete, uncommitted** | Sub-steps A/B/C/D/E/F/G/ForceDrain/J landed (22d). H/I/K/L pending. |
| 2 | PR-B | SYNCING sub-phase + readyCandidate field + leader-side hold | not started | Depends on PR-A. ~8 files. |
| 3 | PR-C | Operator API + CLI for ForceDecommission | not started | Depends on PR-A. ~10 files (incl. docs triad). |
| 4 | PR-D | LifecycleReconciler dry-run + 7 rules | not started | Depends on PR-A. ~15 files. |
| 5 | PR-E | Reconciler enforcing mode + cluster B 02-chaos validation | not started | Depends on PR-D. ~3 files (config flip + tests). |

**Side-fixes landed this segment (independent of the reconciler initiative):**

- `Deployment.rolledBack()` + `applyRollbackRouting` chain — RC1 production bug, unblocked `06-deployment` cascade.
- `test-export.sh` scaling-cooldown filter — RC1 test-side fix, unblocked `07-cluster-mgmt/Config_identical_after_re-apply`.

Both validated end-to-end against remote cluster A. Independent of Phase 1 PR-A scope; can be committed as standalone `fix:` commits or folded into the PR-A landing.

---

## 3. Phase 1 PR-A — sub-step inventory

Authoritative table (carried over from 22d, unchanged):

| Step | Description | Status | Key files |
|---|---|---|---|
| A | `StopReason` enum + `NodeLifecycleValue.stopReason` sidecar | ✅ | `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` |
| B | `MembershipFsmInput` sealed root + `MembershipFsmEvent` rebase | ✅ | `MembershipFsmInput.java` (new), `MembershipFsmEvent.java` |
| C | `LifecycleCommand` sealed interface, 5 variants | ✅ | `LifecycleCommand.java` (new) |
| D | Reducer `apply(state, input, gate)` overload + `applyCommand` + 5 per-command handlers | ✅ | `ClusterMembershipReducer.java` |
| E | `LifecycleWriter.applyCommand` default API + `DirectLifecycleWriter` override with `StopReason` sidecar | ✅ | `LifecycleWriter.java` |
| F | 3 production call sites migrated to `applyCommand` | ✅ | `ClusterTopologyManagerRecord.java:1076,1182`, `ConsensusDrainCoordinator.java:156` |
| G | Audit stream wiring (real publisher, not stub) | ✅ | `audit/*` (new), `AetherNode.java`, `LifecycleWriter.java` |
| ForceDrain | 5th `LifecycleCommand` variant + reducer dispatch + writer routing | ✅ | `LifecycleCommand.java`, `ClusterMembershipReducer.java`, `LifecycleWriter.java` |
| J | KV deadline atoms (JoinDeadlineKey/DrainDeadlineKey + values) | ✅ | `AetherKey.java`, `AetherValue.java`, `EphemeralKeys.java`, `KVStoreSerializer.java`, reducer entry/exit helpers |
| **H** | `MembershipFsmState` record collapse (Decommissioned + FailedDrain → Stopped) | ⏸ | Reducer-internal records + helpers; coupled to I |
| **I** | `NodeLifecycleState` enum collapse 6→4 (JOINING/ON_DUTY/DRAINING/STOPPED) | ⏸ | 13 architecturally-significant case-arms + ~1072 secondary refs across the tree |
| **K** | `OperatorDrain`/`OperatorDecommission` event → command migration | ⏸ | 2 production sites in `NodeLifecycleRoutes.java:215,452` + 27 test sites. UNBLOCKED by ForceDrain |
| **L** | Pattern-match consumer updates for collapsed state enum | ⏸ | Cleanup follow-on to I |

### 3.1 H/I/K/L pickup options (carried from 22d)

**Option 1 — H+I as one coordinated sweep (recommended).** The two collapses are tightly coupled: the boundary mapping from FSM state record → KV value enum must update atomically or the reducer's lifecycle-write paths break. Major case-arm sites:
- `MembershipView.java:287` (MemberStatus mapping)
- `MembershipFsm.java:1066, 1124` (KV replay)
- `SnapshotMembershipView.java:70` (snapshot projection)
- `ClusterGenerationProjector.java:196, 220` (guard exclusions)
- `NodeLifecycleRoutes.java:164, 270` (route guards)
- `BootstrapModule.java:298` (pre-allocation filter)
- `ClusterEventAggregator.java:227`, `TopologyObserver.java:709/713/717`

Recommended via `jbct-coder` single-shot agent. Sealed-switch exhaustiveness will catch every miss at compile time.

**Option 2 — K first (smaller, mechanical).** Migrate 2 production producers (`NodeLifecycleRoutes.java:215, 452`) to `applyCommand(new ForceDrain(...))` / `applyCommand(new ForceDecommission(...))`. Delete the 2 event variants + reducer handlers. Rewrite 27 test sites (mostly `new OperatorDrain(peer, reason, at)` → `new ForceDrain(peer, reason, Causes.cause("test"), at)`).

**Option 3 — both in parallel.** Risky: edit collision in the reducer file likely. Don't recommend.

**Suggested next-session ordering:** K first (builds migration mechanics confidence), then H+I as a single jbct-coder sweep, then L as cleanup.

### 3.2 Sub-step detail recall (what to expect when reading the diff)

- **A — `StopReason` enum:** `AetherValue.java` adds `enum StopReason { GRACEFUL, FORCED, DRAIN_FAILED }` and a `stopReason` field on `NodeLifecycleValue` with `withStopReason(Option<StopReason>)`. Backward-compat constructor overloads keep callers working; field defaults to `Option.none()`.
- **B/C — Sealed input hierarchy:** `MembershipFsmInput` permits `MembershipFsmEvent | LifecycleCommand`. Existing event variants unchanged in shape.
- **D — Reducer command dispatch:** new `apply(state, input, gate)` overload pattern-matches `MembershipFsmEvent | LifecycleCommand`. Each command variant has a dedicated `applyForce*` / `applyRecord*` / `applyRequest*` handler that exhaustively switches on all 7 `MembershipFsmState` records. Reused existing transition helpers (`enterJoining`, `onDutyToDecommissioned`, ...) wherever possible.
- **E — Writer:** default `applyCommand` interface method dispatches via `switch (command)` to legacy `request*` methods. `DirectLifecycleWriter` overrides `ForceDecommission` specifically to carry `StopReason` sidecar. Audit publishing inside `applyCommand`.
- **G — Audit publishing:** `applyCommand` calls `publishReceived(command)` before dispatch, then `publishApplied(command, accepted=true|false)` on `.onSuccess`/`.onFailure`. Payload uses surrogate fields (`commandType`, `peerId`, `reasonTag`, `justificationMessage`, `timestampMs`, `accepted`) — path B chosen because `Cause` is not `@Codec`-able. `LifecycleCommand` itself is NOT annotated `@Codec`.
- **G — Real publisher at `AetherNode`:** `streamPartitionManager.createStream(AuditLifecycleStreams.AUDIT_LIFECYCLE_COMMANDS)` provisions the topic (`partitions=4, retention=time/7d, max-event-size=16KB`). `DefaultStreamPublisher.streamPublisher(...)` builds the publisher. Null-safe lambda: `event -> Option.option(ref.get()).fold(Promise::<Unit>unitPromise, p -> p.publish(event))`. `STREAM_ALREADY_EXISTS` is benign and skipped from the warn log.
- **F — Call site migrations:**
  - CTM line 1076 `writeDecommissionedAtom`: `ForceDecommission(nodeId, StopReason.FORCED, Causes.cause("CTM: terminate-success decommission for " + nodeId), HlcTimestamp.ZERO)`
  - CTM line 1182 `tombstoneAssignedNodeOnExpiry`: same shape, justification `"CTM: expired slot owner tombstone for " + assignedId`
  - ConsensusDrainCoordinator line 156 `markDrainComplete`: `ForceDecommission(nodeId, StopReason.GRACEFUL, Causes.cause("Drain: markDrainComplete for " + nodeId), HlcTimestamp.ZERO)`
- **ForceDrain:** `ForceDrain(NodeId peer, DrainReason reason, Cause justification, HlcTimestamp at)`. Reducer handler: `OnDuty → enterDraining`; all other states no-op (idempotent). Writer route: falls through to legacy `requestDrain(cmd.peer())` via default switch.
- **J — KV deadline atoms:** `JoinDeadlineKey(NodeId)` / `DrainDeadlineKey(NodeId)` + `JoinDeadlineValue(long deadlineMs, HlcTimestamp setAt)` / `DrainDeadlineValue(...)`. Registered in `EphemeralKeys` (per-runtime observability) and `KVStoreSerializer` (sealed-switch exhaustiveness mandatory). Reducer emits `KVCommand.Put` on `enterJoining` / `enterDraining`, `KVCommand.Remove` on every terminal exit + `applyRequestReJoin`. Replay-path KV READ deferred — existing `MembershipFsm.resumeJoinDeadline` / `resumeDrain` recompute correctly from `NodeLifecycleValue.updatedAt()`; atoms are observability-only for now.

---

## 4. Phases 2–5 — planned scope

Direct extract from the plan, in implementation order.

### 4.1 Phase 2 — SYNCING sub-phase + readyCandidate + leader-side hold (PR-B)

**Goal:** Cluster syncs new joiners via the existing pong channel; no KV state for SYNCING; busy syncing nodes protected from force-decommission.

- **`readyCandidate` field on `ClusterSyncPong`** — add 10th field `Option<NodeId> readyCandidate` to `ClusterSyncPong` record at `integrations/cluster/src/main/java/org/pragmatica/cluster/metrics/ClusterSyncMessage.java` line 93. Codec auto-generated by `@Codec` annotation processor.
- **`NodeReadinessTracker`** — new file at `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/NodeReadinessTracker.java`. Owned per-node (not leader-only). Listens for the "Rabia sync complete" signal in `RabiaNode.java:448` (response-promise resolution on the receiving side); sets a volatile `Option<NodeId> candidate`. Cleared on own-`ON_DUTY` KV notification. `ClusterSyncCollector` consults this when assembling outgoing pongs.
- **Leader-side `ForceOnDuty` emission** — in `ClusterSyncPongSignalFan.java::fanIfLeader` (line 28–35), after the existing leader check and `SwimHint` emission, add: `pong.readyCandidate().onPresent(candidate -> lifecycleWriter.applyCommand(new ForceOnDuty(candidate, Cause.of("readyCandidate from " + pong.sender()))))`. Idempotent on the reducer side.
- **Leader-side `activeSyncHolds`** — at `RabiaNode.java::handleKVSyncRequest` (line 448): on request arrival, compute `holdMs = clamp(snapshot.length / EXPECTED_SYNC_BPS, MIN_HOLD_MS, MAX_HOLD_MS)` (defaults: MIN=5s, EXPECTED_BPS=10 MB/s, MAX=60s; configurable under `[reconciler.holds]`); `activeSyncHolds.put(request.sender(), nowMs() + holdMs)`. On promise completion: `activeSyncHolds.remove(request.sender())`. Exposed via `Supplier<Set<NodeId>>` injected into the reconciler in Phase 4.

**Estimated touch:** ~8 files.

### 4.2 Phase 3 — Operator API + CLI for ForceDecommission (PR-C)

**Goal:** Operator-facing intent channel; integration tests can clean up stuck states between scenarios.

- **REST endpoint** — `POST /api/nodes/lifecycle/commands` in `aether/node/src/main/java/.../routes/NodeLifecycleRoutes.java`. Body: `{"type": "FORCE_DECOMMISSION", "nodeId": "node-2", "cause": "..."}`. Forwards to leader via existing `LEADER` route target. Synchronous-on-consensus.
- **Audit subscription endpoint** — `GET /api/audit/commands?since=...` (SSE or polling, depends on existing aether-stream HTTP surface).
- **CLI subcommand** — `aether nodes decommission <node-id> --reason "..."` in `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java`. Wraps the POST. Audit subscription: `aether cluster audit --source reconciler --since 1h`.
- **Docs triad (per CLAUDE.md invariant)** — update `aether/docs/reference/management-api.md`, `aether/docs/reference/cli.md`, and register the route in the `ManagementRoute` enum.

**Estimated touch:** ~10 files.

### 4.3 Phase 4 — LifecycleReconciler dry-run mode (PR-D)

**Goal:** Periodic convergence checking on leader; emit audit entries only (no enforcement).

- **`LifecycleReconcilerRecord`** — new file at `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/reconciler/LifecycleReconcilerRecord.java`. Mirror CTM pattern (`ClusterTopologyManagerRecord.java:668-680` is the template): `activate()`/`deactivate()` lifecycle hooks tied to leader lease; `SharedScheduler.scheduleAtFixedRate(this::reconcile, TimeSpan.timeSpan(10).seconds())`. Inject `PeerObservationStore`, `GenerationSnapshotSource`, `Supplier<Set<NodeId>> activeSyncHolds`, `LifecycleWriter`. Read-only snapshots each tick.
- **Phase gate** — `phaseSupplier.get() == ClusterPhase.NORMAL`. Skip during `COLD_BOOT` and all `RECOVERING` sub-branches.
- **7 reconciliation rules**, each as a `ReconciliationRule` SPI in `reconciler/rules/`:

  | Rule | Trigger | Behavior |
  |---|---|---|
  | `JoiningTimeout` | SWIM Faulty/Departed + `JOIN_DEADLINE × 1.5` elapsed | emit `ForceDecommission` |
  | `JoiningStuckAlert` | SWIM Alive past `JOIN_DEADLINE × 3` | audit-only |
  | `OnDutyFaulty` | SWIM `Faulty` for `SWIM_FAULTY_DECLARATION × 3` | emit `ForceDecommission` |
  | `DrainTimeout` | DRAINING + `DRAIN_DEADLINE × 1.5` | emit `ForceDecommission` |
  | `GenerationLifecycleGap` | 30s no-entry | emit `RecordJoining` |
  | `SwimLifecycleGap` | 30s no-entry | audit-log lookback guard |
  | `StoppedZombie` | KV says STOPPED but SWIM still alive | audit-only |

- **Per-rule enable flag in `[reconciler.rules]` of `aether.toml`.** Default: dry-run (all rules on, audit-only).
- **Status endpoint** — `GET /api/nodes/lifecycle/reconciler` → `{ lastTickAt, lastActionAt, rulesEnabled[], recentDecisions[] }`. Recent decisions sourced from the audit event stream.

**Estimated touch:** ~15 files.

### 4.4 Phase 5 — Reconciler enforcing mode flip + cluster B validation (PR-E)

**Goal:** Reconciler emits real commands; cluster self-heals.

- `[reconciler.rules]` defaults flip from `audit_only=true` to `enforce=true` for `JoiningTimeout`, `OnDutyFaulty`, `DrainTimeout`, `GenerationLifecycleGap`, `SwimLifecycleGap`. `JoiningStuckAlert` and `StoppedZombie` stay audit-only by design.
- **Cluster B 02-chaos: 15/15 acceptance** — re-run with `enforce=true`. No ghost-tolerance workarounds, no stuck-state manual cleanup, no false-positive decommissions over 1000s soak.

**Estimated touch:** ~3 files.

### 4.5 End-to-end verification (post-Phase 5)

1. `./build.sh` green.
2. Cluster B 02-chaos: 15/15 with reconciler enforcing.
3. Cluster A non-destructive suites: no regressions.
4. Manual stuck-state recovery: `aether nodes decommission` cleans up a ghost ON_DUTY entry in <30s.
5. Audit stream consumer test: subscribe to `audit.lifecycle.commands`; trigger one ForceDecommission via CLI; verify `CommandReceived` + `CommandApplied` events arrive with correct payload.
6. Soak: 1000s cluster B run; audit stream noise count = 0 false-positives.

---

## 5. This session segment in particular — the two side-fixes

### 5.1 Patch A — `Deployment.rolledBack()` + `applyRollbackRouting` advance to terminal

**Problem.** `Deployment.java` exposed `rollback()` → `ROLLING_BACK` and `complete()` → `COMPLETED`, but no `rolledBack()` → `ROLLED_BACK`. `DeploymentManagerImpl.applyRollbackRouting` persisted at `ROLLING_BACK` and stopped — the FSM's terminal sink for the success path was unreachable. Compare `AbTestManager`, which has the analogous flow correct.

**Symptom.** `06-deployment` cascade:
1. First test's rollback step calls `POST /api/deploy/rollback/{id}` → 200, deployment stuck at `ROLLING_BACK`.
2. Test passes (it accepts `ROLLING_BACK | ROLLED_BACK` as "terminal/transitional").
3. `deploy_cleanup` between tests retries rollback → 500 `Invalid deployment state transition: ROLLING_BACK -> ROLLING_BACK`, then complete → 500 `ROLLING_BACK -> COMPLETED`.
4. Next test's `*_start` (Canary, Rolling, Blue-Green) → 500 `Deployment already in progress for blueprint: ...`.

Pre-fix `06-deployment` = `3p/2f`. Post-fix `5p/0f`.

**Fix.**
- `Deployment.java`: add `rolledBack()` between `rollback()` and `complete()`. One-liner: `return transitionTo(DeploymentState.ROLLED_BACK);`.
- `DeploymentManagerImpl.applyRollbackRouting`: chain `deployment.rolledBack().flatMap(finalized -> ...)` around the existing consensus batch so the same consensus write that flips routes back to v1 also stamps the deployment at `ROLLED_BACK`.

### 5.2 Patch B — `test-export.sh` filters `scaling-cooldown/*` from round-trip equality

**Problem.** `test_config_identical_after_reapply` asserts canonical-form equality between two `config_export` snapshots taken ~3-4s apart. The runtime emits `scaling-cooldown/<slice-coords>` keys whose values are timestamps of the scheduler's last scale-action per slice; they bump on the scheduler's own cadence independent of user-applied config.

**Fix.** Both canonicalization pipelines now `grep -v '"scaling-cooldown/'` before sorting. Comment block above the filter explains rationale.

Pre-fix `07-cluster-mgmt` = `3p/1f`. Post-fix `4p/0f`.

**Follow-up (RC2 candidate).** Structural separation of user-authored vs runtime keys in `/api/config` (e.g. `?include=runtime` or distinct endpoint). Test-side allow-list is the tactical patch.

---

## 6. Validation results this segment

The 06:19AM `run-tests.sh` was killed during prior session, but one child test script — `02-chaos/test-joining-window-kill.sh` (PID 33890) — survived as an orphan reparented to launchd (PPID=1) and continued running against the wedged cluster B until user-instructed SIGKILL at 06:41:02. This produced the cluster B "results" below — all expected fails against a wedged cluster, no diagnostic value.

| Cluster | Suite | Result | Note |
|---|---|---|---|
| A | 00-smoke | 2p/0f | |
| A | 04-streaming | 4p/0f | |
| A | **06-deployment** | **5p/0f** | ✅ Patch A validated end-to-end (Rolling/Blue-Green/Canary) |
| A | **07-cluster-mgmt** | **4p/0f** | ✅ Patch B validated |
| A | 08-resources | 4p/1f | pre-existing inject-500 flake (Task #5/#12 territory) |
| A | 09-artifacts | 2p/1f | pre-existing 1MB/5MB resolve 500 |
| A | 10-database | 3p/0f | |
| A | 11-observability | 6p/0f | |
| A | 14-storage | 2p/0f | |
| A | 15-delegation | 2p/0f | |
| **Cluster A total** | — | **34 passed, 2 failed** | both failures pre-existing, NOT Phase 1 regressions |
| B | 02-chaos/test-joining-window-kill | 0p/6f (no value) | Orphan ran against wedged cluster B; all 6 sub-tests fast-failed (no leader, empty NodeIds, S01 kill never landed). Run terminated by user at 06:41:02 during the orphan's mid-restore-baseline phase of test 6 |
| B | other 02-chaos scripts | not exercised | Single-script orphan only — orchestrator was already dead |
| B | 03-scaling / 05-security / 12-network | not exercised | no orchestrator to schedule them |

**Validation conclusion.** No Phase 1 PR-A regressions. Both side-fixes validated. Cluster B suites yielded zero new data — wedged baseline produced only the expected void. Phase 4 still required to actually validate cluster B chaos paths.

---

## 7. Working tree at handover

**Modified in this segment** (the two side-fixes):

```
M aether/aether-invoke/src/main/java/org/pragmatica/aether/update/Deployment.java
M aether/aether-invoke/src/main/java/org/pragmatica/aether/update/DeploymentManagerImpl.java
M aether/tests/integration/suites/07-cluster-mgmt/test-export.sh
M CHANGELOG.md
```

**Modified in prior segments (22b/22c/22d), unchanged this segment** — full set lives in `git status --short`. Highlights:

- ~100 modified Java files in `aether/aether-deployment/` (large surface from Phase 1 PR-A scaffolding + earlier 22-suffix config provisioning work).
- Slice KV records: `AetherKey.java`, `AetherValue.java`, `EphemeralKeys.java`, `KVStoreSerializer.java`.
- `aether/node/.../AetherNode.java` (audit publisher provisioning).
- Several `integrations/consensus/*` (LifecycleState + TopologyObserver).
- 6 cluster B chaos test scripts (`02-chaos/*.sh`).
- Cluster A test infra (`lib/cluster.sh`, `lib/common.sh`, `lint-baseline.txt`).

**Untracked (new files):**

```
?? aether/aether-deployment/.../audit/                                           AuditLifecycleCommandPublisher.java + AuditLifecycleStreams.java + CommandLifecycleEvent.java
?? aether/aether-deployment/.../membership/fsm/LifecycleCommand.java             sealed interface, 5 variants
?? aether/aether-deployment/.../membership/fsm/MembershipFsmInput.java           sealed root
?? aether/docs/specs/cluster-convergence-reconciler-spec.md                      the reconciler spec (untracked, not yet committed)
?? aether/docs/internal/progress/session-handover-2026-05-22b.md                 from session 22b
?? aether/docs/internal/progress/session-handover-2026-05-22c.md                 from session 22c
?? aether/docs/internal/progress/session-handover-2026-05-22d.md                 from session 22d
?? aether/docs/internal/progress/session-handover-2026-05-22e.md                 this file
```

**Git remote state.** 3 commits ahead of `origin/release-1.0.0-rc1` (`c8d6f6faa`, `1846a618c`, `e15cc408d`). Not yet pushed. Nothing on origin should conflict.

---

## 8. Active processes at handover

**NONE.** Verified locally and remotely.

**Local processes:** zero `run-tests.sh`, zero `test-*.sh`, zero `mvn` / `aether` related processes. Confirmed by `pgrep -lf "run-tests"` + `pgrep -lf "test-joining"` returning empty.

**Remote cluster state — FULLY CLEAN (post-cleanup):**
- All aether containers **removed** (`docker ps -a | grep aether` returns nothing).
- No aether volumes.
- No aether networks.
- Compose teardown via `docker compose -f docker-compose-{a,b}.yml down --remove-orphans` (no resources to remove — clusters were not compose-managed at kill time) + explicit `docker rm -f` of the 2 stray ad-hoc containers (`aether-b-core-node-{0,1}-<random-suffix>`) the killed test infra had spawned via CTM provisioning + `docker network prune`.

**Next session MUST `docker compose up -d` on both clusters before any test run:**
```bash
ssh "$AETHER_SSH_USER@$TARGET_HOST" -i "$AETHER_SSH_KEY" \
    'cd && docker compose -f docker-compose-a.yml up -d && docker compose -f docker-compose-b.yml up -d'
```
Or just invoke `./run-tests.sh --env remote --skip-build` — the harness's `restore_cluster_baseline` will bring both up.

**Chronology of this segment's process management:**
- 06:19AM: `run-tests.sh` started in prior session (PID-tree included PID 33890 — `02-chaos/test-joining-window-kill.sh`).
- 06:24AM (prior session): orchestrator `run-tests.sh` killed. Child PID 33890 reparented to launchd (PPID=1) and continued executing.
- 06:37AM: predecessor handover's `21:21` count of "process tree" missed the orphan because pgrep matched only `run-tests` patterns, not individual `test-*.sh` scripts. Documentation false-claim: "no active processes" — **incorrect**.
- 06:41:02: orphan PID 33890 killed (SIGTERM ignored → SIGKILL succeeded) on explicit user instruction.
- 06:41:03: background waiter dumped final log tail — orphan had reached test 6 (`pick_non_leader_excludes_decommissioned_replacement`) and was mid-`restore_cluster_baseline` docker compose cycle when killed.
- 06:41:55: remote cluster cleanup executed — `docker compose down` on both compose files (no resources to remove) + `docker rm -f` on the 2 stray ad-hoc CTM-provisioned containers + `docker network prune -f`. Final state confirmed "ALL CLEAR: no aether containers".

---

## 9. Open follow-ups carried forward

### From 22d (still open):

1. `MembershipFsm` replay paths should READ `JoinDeadlineKey`/`DrainDeadlineKey` directly instead of recomputing from `NodeLifecycleValue.updatedAt()`. Currently atoms are write-only observability. Required when in-process JOINING/DRAINING entry HLC and `updatedAt()` diverge.
2. `MembershipFsmConfig.drainTimeout()` vs spec name `drainDeadline()` — cosmetic rename.
3. TOML round-trip parser for deadline atoms (RC2 if needed). Atoms are marked ephemeral so `fromToml` skips them; restore would need new parser arms.
4. `LifecycleCommand` records not `@Codec` — blocks any future consumer that wants structured command payloads on the audit stream. Path A would require `Cause` to also be `@Codec`-able (it isn't today).
5. `AuditLifecycleCommandPublisher` qualifier annotation currently unused. Remove if slice-DI migration is confirmed unneeded.
6. `HlcTimestamp.ZERO` placeholders in F migration call sites. When `applyCommand` is rewired to route through the reducer directly (post H+I), real `HlcClock`-derived timestamps must be threaded through these call sites.
7. Audit publisher provisioning failure mode — `provisionAuditLifecycleCommandPublisher` chains `.onFailure(this::logAuditStreamProvisionOutcome)`. Real failures leave the `AtomicReference` empty forever and the lambda silently drops events. Consider fallback in-process buffer or retry-on-leader-takeover.
8. Topic registration consolidation — `AuditLifecycleStreams.AUDIT_LIFECYCLE_COMMANDS` is a code constant. If slice-level `resources.toml` provisioning lands for deployment, move this to TOML.
9. Pre-existing JBCT-RET-01 baseline (Task #13) — 26 violations across aether-stream (14), aether-metrics (3), aether-deployment (9). Empirically verified: nothing added by Phase 1 PR-A work. Spawn a focused session before Phase 4 lands, so that module is clean.

### New from this segment:

10. **`/api/config` structural separation** of user-authored vs runtime keys (RC2 candidate). The `scaling-cooldown/*` test-side filter is tactical; a cleaner long-term separation would let the round-trip identity test be defended structurally instead of by allow-list.
11. **Audit for sister bugs to Patch A** — quick grep across `aether-invoke` and similar for `transitionTo(.*ROLLING_BACK)` and `transitionTo(.*DRAINING)` to confirm no other `*Manager` classes have the "persisted-at-transitional-but-no-terminal-advance" pattern. `DeploymentManagerImpl.applyRollbackRouting` was an isolated outlier; double-check.

---

## 10. Active tasks at handover

```
#5  [in_progress] Make 02-chaos pass end-to-end                                  — Phase 4 target
#8  [in_progress] Investigate test-kill-multiple lifecycle staleness cascade     — Phase 4 subsumes
#12 [in_progress] JOINING-window kill: FSM doesn't demote to DECOMMISSIONED      — root-cause fix from 22c in tree; not validated post-Phase-1 due to cluster B wedged state
#13 [pending]     Pre-existing JBCT-RET-01 violations (26)                       — blocks ./build.sh Step 2 but not focused compiles. Pre-Phase-4 cleanup.
#15 [in_progress] Phase 1 PR-A: FSM collapse + Command primitive + migration    — A-G + ForceDrain + J done; H/I/K/L pending
#16 [pending]     Phase 2 PR-B: SYNCING + readyCandidate + leader-side hold
#17 [pending]     Phase 3 PR-C: Operator API + CLI for ForceDecommission
#18 [pending]     Phase 4 PR-D: LifecycleReconciler dry-run + 7 rules
#19 [pending]     Phase 5 PR-E: Reconciler enforcing mode + cluster B validation
#20 [completed]   Preflight: aether-stream API reachability + JBCT-RET-01 baseline
#21 [in_progress] Phase 1 PR-A: validate via remote integration suite           — cluster A green for 06/07/all-non-pre-existing; cluster B blocked
#22 [completed]   06-deployment: ROLLING_BACK → ROLLED_BACK advance              — landed this segment
#23 [completed]   07-cluster-mgmt: filter scaling-cooldown                       — landed this segment
#24 [in_progress] Write detailed session handover for next start                 — this document
```

After this handover is written, `#24` becomes completed.

---

## 11. Verification recipe (run on session start)

```bash
# Confirm git state
git rev-parse HEAD                       # expect e15cc408d
git status --short | wc -l               # expect ~120 entries (working tree has all 22b/22c/22d/22e work)

# Confirm both side-fix patches are in place
grep -n "rolledBack()" aether/aether-invoke/src/main/java/org/pragmatica/aether/update/Deployment.java
grep -n "deployment.rolledBack().flatMap" aether/aether-invoke/src/main/java/org/pragmatica/aether/update/DeploymentManagerImpl.java
grep -n "scaling-cooldown" aether/tests/integration/suites/07-cluster-mgmt/test-export.sh

# Confirm Phase 1 PR-A scaffolding from 22d is intact
ls aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/audit/
ls aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/LifecycleCommand.java
ls aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsmInput.java
grep -n "applyCommand" aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/LifecycleWriter.java | head -5

# Focused build (avoids the JBCT-RET-01 RED on Step 2)
mvn -pl aether/aether-invoke install -DskipTests -am -q
mvn -pl aether/aether-deployment install -DskipTests -am -q

# Full aether-node shaded JAR (needed before any remote test run)
mvn -pl aether/node install -DskipTests -am -q
ls -lh aether/node/target/aether-node.jar          # expect ~51 MB

# Confirm no stray legacy calls in migrated files
grep -n "requestDecommission" aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java
grep -n "requestDecommission" aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/drain/ConsensusDrainCoordinator.java
# Both should return zero matches
```

If any of these fail, the working tree drifted — re-read the relevant predecessor handover and reconcile.

---

## 12. Suggested first action in the next session

Two candidate moves, pick one or sequence them:

**Move A — commit the two side-fixes as standalone `fix:` commits before continuing Phase 1 PR-A.** Independent of FSM-collapse work; shipping them now de-risks the eventual PR-A landing (which will already be a much larger diff). Suggested commit messages (single-line, no body, no trailers, per project policy):

```
fix(invoke): advance Deployment from ROLLING_BACK to ROLLED_BACK after rollback routing applied
fix(test-infra): 07-cluster-mgmt round-trip equality filters runtime scaling-cooldown timestamps
```

User must explicitly authorize commits — do not commit unprompted.

**Move B — continue Phase 1 PR-A from where 22d stopped.** Pick H+I sweep (recommended) or K-first cleanup per 22d §"Next-step options". Recommended ordering: K first, then H+I together, then L.

**Recommended sequencing.** Move A first (small, validated, ships a real RC1 fix), then Move B. The two are independent — Move A doesn't block Move B and vice versa.

---

## 13. Constraints carry-over (still in effect)

- **Single-line commits only**, no body, no `Co-Authored-By` trailers, no other trailers.
- **NEVER pass `-Djbct.skip=true` for aether builds** — POM hierarchy handles it; only valid use is building `jbct/` itself.
- **NEVER run `mvn verify` with `HCLOUD_TOKEN` set** — Failsafe picks up `HetznerCloudIT` and creates a real paid Hetzner server.
- **NEVER create feature branches on `release-1.0.0-rc1`** — commit directly.
- **`./build.sh` is RED pre-existing (Task #13).** Use focused `mvn -pl <module> install -DskipTests -am` until #13 is cleared.
- **AETHER_INSECURE_DEV_MODE=true** required in cluster A+B compose env (in place).
- **PEERS uses 3-part format** `nodeId:host:port` — never 2-part.
- **Tests must be self-contained** — assume nothing about cluster state from prior runs.
- **NEVER inline `$TARGET_HOST` / `$AETHER_SSH_KEY` / `$AETHER_SSH_USER`** — reference by name only.
- **Aether code is BSL-1.1**, SPDX short header required on new files (markdown docs exempt).
- **`build-runner` agent owns Maven invocations** — main thread direct `mvn` is acceptable for tight loops but should return to the agent-mediated pattern for the next session.
- **`jbct-coder` for non-trivial Java implementation work; `jbct-reviewer` for on-demand audits.**
- **Delegate by default** — main context is the scarce resource.
- **User mode: auto-mode active** — bias toward action; stop when direction is unclear.

---

## 14. One-line summary

Multi-session arc: cluster convergence reconciler initiative is ~Phase-1.6 of 5 (PR-A scaffolding ~75% done, H/I/K/L pending; PR-B through PR-E not started). This segment delivered two independent RC1 fixes (server-side rollback FSM advance + test-side scaling-cooldown filter), both validated on remote cluster A (`06-deployment 5p/0f`, `07-cluster-mgmt 4p/0f`, total cluster A 34p/2f with both failures pre-existing). Cluster B noise from an orphaned `test-joining-window-kill.sh` (PID 33890, reparented to launchd) was discovered and killed; both remote clusters then fully torn down (containers + volumes + networks). Zero new commits. Next session: bring clusters back up (`docker compose -f docker-compose-{a,b}.yml up -d`), commit the two `fix:` patches, then resume Phase 1 PR-A H/I/K/L sweep.
