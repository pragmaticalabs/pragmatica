# Session handover — 2026-04-24

**Branch:** `release-1.0.0-rc1`
**HEAD:** `c9eb6e42d`
**Prior handover:** `aether/docs/internal/progress/session-handover-2026-04-23.md`

## One-line summary

Full FSM wave: new `integrations/statemachine` runtime (`Fsm` + `FsmState` + `TransitionRequest` + `FsmObserver` + `FsmTestHarness`), shared `ClusterFsmEvent` vocab, rewrote `LeaderManager` onto caller-thread CAS, then retrofit or added lifecycle Fsm to 10 more components. JBCT review pass applied to the refactor surface. Integration not yet re-run on remote.

## Commits landed this session (16 total)

```
61daf926d refactor: rewrite LeaderManager as explicit state machine with stale-commit rejection   (prior session, context)
1d75788df feat(statemachine): add Fsm + FsmState + TransitionRequest for CAS-based GoF state machines
91801e754 feat(statemachine): add FsmTestHarness test-jar + FsmTest covering CAS contention
ed6299ed0 feat(cluster): shared ClusterFsmEvent vocab + ClusterFsmRouter + MicrometerFsmObserver
c2d2829d5 refactor(leader): retrofit LeaderElectionFsm onto caller-thread CAS dispatch + shared ClusterFsmEvent vocab
6806d305f refactor(delegation): migrate TaskAssignmentCoordinator to Fsm + ClusterFsmEvent
6b7444f2c refactor(invoke): rewrite ScheduledTaskManager as explicit FSM
856b2dfa1 refactor(tls): rewrite CertificateRenewalScheduler as explicit FSM (Idle/Healthy/Renewing/RetryBackoff/Stopped)
c706c0436 refactor(control): add lifecycle Fsm to ControlLoop for consistent state tracking
2aed9441a refactor(health): add lifecycle Fsm to CoreSwimHealthDetector (Stopped/Starting/Running)
d862cba2b refactor(metrics): add lifecycle Fsm to ClusterSyncScheduler (Inactive/Pinging)
3b88a678e refactor(http): add lifecycle Fsm to AppHttpServer (Stopped/Starting/Running/CertRotating)
75996f968 refactor(deployment): add lifecycle Fsm to HealthReconciler (Stopped/Active)
ee92bbb48 refactor(deployment): add lifecycle Fsm mirror to NodeDeploymentManager
8d62a1534 refactor(deployment): add lifecycle Fsm mirror to ClusterDeploymentManager
c9eb6e42d fix: JBCT cleanup from review — per-context Fsm refs, Option/Result.lift null+exception hygiene, ThreadLocalRandom, drop record unused()
```

All pushed. `./build.sh` and `mvn -pl aether/node install -am -DskipTests` green at HEAD.

## Design highlights (architecture in one page)

### Concurrency model
- **Caller-thread CAS dispatch.** No executor, no queue. `Fsm.dispatch(event)` runs on the caller thread; state advance guarded by `AtomicReference.compareAndSet`. Winner runs `onExit → transitionAction → onEntry → observer`. Loser: `transitionTo` forwards via `fsm.dispatch(event)` (tail call); `transitionToOrDrop` silently abandons (for idempotent timer events).
- **Zero dispatcher threads.** Cross-FSM cascades run synchronously on the originating thread. Deadlock-free.
- **State objects own their data thread safety.** Data-free states are per-FSM singletons (e.g. `Dormant.INSTANCE` or per-context `ctx.dormant()`); data-carrying states are fresh immutable records per entry (e.g. `Led(ctx, leader)`). Reference equality drives CAS — singletons required for data-free; fresh records required for data-carrying.

### Shared event vocabulary
- `integrations/consensus/.../fsm/ClusterFsmEvent.java` (non-sealed marker): `QuorumEstablished`, `QuorumDisappeared`, `LeaderChange`, `NodeAdded`, `NodeGone`, `Shutdown`.
- Domain events implement `ClusterFsmEvent` directly (so `Fsm<?, ClusterFsmEvent>`): `LeaderElectionEvents.{ConsensusReady, LeaderCommitted, ElectionTick, ProposalSettled}`.
- `ClusterFsmRouter.wire(router, fsm, quorumSequence)` adapts `MessageRouter` notifications (incl. `NodeRemoved`+`NodeDown` normalization to `NodeGone`, `advanceSequence` dedup) — single-call subscription.

### Instrumentation
- `FsmObserver` hook on every transition / CAS-loss / event-ignored.
- `MicrometerFsmObserver` in `aether-metrics` emits `fsm_transitions_total{fsm,from,to}`, `fsm_cas_lost_total`, `fsm_events_ignored_total`.

### Test harness
- `FsmTestHarness.harness(name, initialState)` — single-threaded + `dispatchConcurrently(List<Event>)`. Published as test-jar from `integrations/statemachine`. 34 tests covering CAS contention, forward-on-loss, entry/exit ordering.

### Per-component FSM shapes (summary)

| Component | States | Notes |
|---|---|---|
| `LeaderElectionFsm` | Dormant, QuorumWaiting, Electing, Led(leader), ReElecting, QuorumLost, Stopped | Full deep refactor; fixes stale-commit replay (commits for L not in topology rejected at WARN). |
| `TaskAssignmentCoordinator` | Dormant, Active(maps+timer) | Sealed-interface migration to `FsmState` with `Active` carrying assignment/failed maps + reconcile timer. |
| `ScheduledTaskManager` | Dormant, Following, Leading, Stopped | Full FSM replaces `isLeader`+`hasQuorum` atomics. |
| `CertificateRenewalScheduler` | Idle, Healthy, Renewing, RetryBackoff(retryCount), Stopped | Full FSM with exponential-backoff data state. |
| `ControlLoop` | Dormant, Active, Stopped | Minimal lifecycle Fsm alongside existing evaluationTask. |
| `CoreSwimHealthDetector` | Stopped, Starting, Running | Minimal lifecycle Fsm parallel to `swimProtocol`/`swimTransport` atomics. |
| `ClusterSyncScheduler` | Inactive, Pinging | Minimal. |
| `AppHttpServer` | Stopped, Starting, Running, CertRotating | Minimal flatten of multi-dim state. |
| `HealthReconciler` | Stopped, Active | Minimal mirror; internal atomics unchanged. |
| `NodeDeploymentManager` | Dormant, Active | Minimal mirror alongside existing sealed `NodeDeploymentState`. |
| `ClusterDeploymentManager` | Dormant, Active | Minimal mirror alongside existing sealed `ClusterDeploymentState`. |

"Minimal" = lifecycle Fsm added in parallel to existing correct state tracking, for observability + consistency. "Full" = existing atomic-flag soup replaced by state records.

## JBCT review + cleanup

Ran `/jbct-review` (10 parallel reviewers across focus areas) after commit `8d62a1534`. One round of fixes applied in commit `c9eb6e42d`:

**Fixed:**
- Static `FSM_REF` / `FsmHolder` globals → per-context `AtomicReference<Option<Fsm>>` + `ctx.bindFsm(fsm)`. Unblocks Forge single-JVM multi-node mode.
- `@SuppressWarnings("JBCT-RET-01")` in `TaskAssignmentCoordinator` → `@Contract` on each `@MessageReceiver void`.
- `HealthReconciler`: raw `null` checks on `reprojectionExecutorRef` / `fresh` / `reprojectionSupplier` → `Option.option(...).onPresent/onEmpty`.
- `HealthReconciler.runOneReprojection` try/catch(RuntimeException) → `Result.lift(supplier::get).onFailure(...)`.
- `HealthReconciler.submitReprojectionDrain` try/catch(RejectedExecutionException) → `Result.lift`.
- `HealthReconciler.awaitReprojectionExecutorTermination` try/catch(InterruptedException) → `Result.lift`.
- `TaskAssignmentCoordinator.identifyGroupsNeedingAssignment` rewritten with `Option.fold`, no `== null`.
- `TaskAssignmentCoordinator.isRecentlyFailed` JDK Optional leakage → `Option.from(stream.findFirst())`.
- `ScheduledTaskManager.executeTask` `Result.lift(() -> Promise)` double-wrapping removed.
- `ScheduledTaskManager.IntervalParser.parseNumber` `.fold()` abuse → `.mapError()`.
- `ScheduledTaskManager.IntervalParser` sealed + `record unused()` hack → plain interface.
- `LeaderElectionState.scheduleElectionTick` `Math.random()` → `ThreadLocalRandom.current().nextDouble(0.5)`.
- `CertificateRenewalScheduler.FsmHolder` static holder removed; dispatch via `ctx.dispatch(event)`.
- Context's per-FSM state-instance fields marked `volatile` (safe publication across dispatcher threads).

**Still open (known debt, rc2 candidates):**
- **Naming:** records `fsmBackedLeaderManager` / `scheduledTaskManagerImpl` / `taskAssignmentCoordinator` are lowercase/Impl-suffixed. Rename to PascalCase per JBCT (e.g. `FsmBackedLeaderManager`). Mechanical, ~3 files.
- **Deferred state-singleton binding** in `LeaderElectionContext.initStates()`, `CertificateRenewalScheduler.Context.bindSingletons()`, `ScheduledTaskManager.Context.bindStates()`, `TaskAssignmentCoordinator.Context.dormantHolder` — context is transiently invalid between construction and state wiring. Requires constructor-driven state building (cyclic reference via `Supplier<Context>` or two-phase builder returning fully-wired pair). Not a correctness bug in current code paths (all state refs set before first dispatch).
- **Defensive copy** missing on `ClusterFsmEvent.NodeAdded(topology)` / `NodeGone(topology)` — callers could pass mutable `ArrayList`. Add compact constructor `public NodeAdded { topology = List.copyOf(topology); }`.
- **FsmObserver not wired** in `CertificateRenewalScheduler`, `ScheduledTaskManager`, `LeaderElectionFsm`. Factories use `FsmObserver.noop()`. Thread observer parameter through factories; default noop preserves current behavior. Micrometer observer then feeds `fsm_*_total` metrics.
- **`@Contract`** missing on `Fsm.dispatch/recordIgnored/tryAdvance` and `FsmState.onEntry/onExit` library-surface methods. JBCT-lint may flag if run. Mechanical.
- **Multi-statement switch arms** in `LeaderElectionState.handle(...)` (6 arms with 2-3 statements each). Consider extracting to named helpers (`handleNodeGoneInLed`, `handleQuorumEstablishedInDormant`, etc.). Also `LeaderElectionState.trySubmitProposal` is a 30-line `onPresent` lambda; extract to `submitProposalWithHandler(ctx, handler)`.
- **Metric cardinality** in `MicrometerFsmObserver` — FSM name embeds NodeId, which unbounds label cardinality across node churn. Split into `fsm=<kind>` + `node_id=<id>` tags.
- **`cluster.apply(...)` abandoned** in `TaskAssignmentCoordinator.writeAssignment` — returns `Result.unitResult()` unconditionally regardless of consensus outcome. Should return `Promise<Unit>` from `reassign(...)`.
- **RenewalStatus** collapses `Stopped → FAILED` and `Idle → HEALTHY`; misleading for monitoring dashboards. Add `STOPPED` and `RETRYING` variants.
- Second `/fix-all` pass not executed this session — first pass landed as `c9eb6e42d`, remaining items above.

## Integration test state

- Remote integration run executed **once** during this session, against commit `61daf926d` (first-pass LeaderManager FSM only, single-thread executor). Result: **9/15 suites pass**.
  - Cluster A: 9/10 (06-deployment regressed by 1 test — was previously 5/5).
  - Cluster B: 1/5 (02-chaos, 03-scaling, 05-security, 12-network, 13-edge-cases failing — pre-existing destructive-chain blocker was not resolved by first-pass FSM).
- **Not re-run** on current HEAD `c9eb6e42d`. User explicitly requested: "run integration tests with fresh binaries on remote host" after refactor — this is the next action for the following session.
- Command: `cd aether/tests/integration && ./run-tests.sh --env remote`. `build.sh` is invoked automatically; fresh JAR built.
- Test JSON output lands at `aether/tests/integration/test-results.json` (currently untracked, pre-existing).

## Next-session P0

1. **Run `cd aether/tests/integration && ./run-tests.sh --env remote`** (fresh build against `c9eb6e42d`). Compare cluster A/B pass counts to the 9/15 baseline. If B-suite regression traceable to FSM refactor, bisect via the 16-commit range above.
2. **If any integration suite regressed**, diagnose with:
   - `fsm.transitions` / `fsm.cas_lost` / `fsm.events_ignored` logs at WARN+ — observable via `MicrometerFsmObserver` (once wired in; currently no-op).
   - `LeaderElectionState` INFO logs for stale-commit WARN lines, proposal submissions, and state transitions.
3. **Optional: second /jbct-review pass** to confirm the remaining debt items are all captured and not grown.
4. **Optional: wire `MicrometerFsmObserver`** to `LeaderElectionFsm` / `CertificateRenewalScheduler` / `ScheduledTaskManager` factories. 5-10 lines per site, default to noop.

## Files touched (aggregate, this session)

**New:**
- `integrations/statemachine/src/main/java/.../Fsm.java` + `FsmState.java` + `FsmObserver.java` + `TransitionRequest.java`
- `integrations/statemachine/src/test/java/.../FsmTest.java` + `FsmTestHarness.java`
- `integrations/consensus/src/main/java/.../fsm/ClusterFsmEvent.java` + `ClusterFsmRouter.java`
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionEvents.java`
- `aether/aether-metrics/src/main/java/.../fsm/MicrometerFsmObserver.java`

**Rewritten:**
- `integrations/consensus/src/main/java/.../leader/LeaderManager.java`
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionFsm.java` / `LeaderElectionState.java` / `LeaderElectionContext.java`
- `aether/aether-invoke/src/main/java/.../ScheduledTaskManager.java`
- `integrations/net/tcp/src/main/java/.../security/CertificateRenewalScheduler.java`
- `aether/aether-deployment/src/main/java/.../delegation/TaskAssignmentCoordinator.java`

**Modified (lifecycle Fsm added):**
- `aether/aether-control/.../controller/ControlLoop.java`
- `aether/node/.../health/CoreSwimHealthDetector.java`
- `aether/aether-metrics/.../ClusterSyncScheduler.java`
- `aether/node/.../http/AppHttpServer.java`
- `aether/aether-deployment/.../generation/HealthReconciler.java`
- `aether/aether-deployment/.../node/NodeDeploymentManager.java`
- `aether/aether-deployment/.../cluster/ClusterDeploymentManager.java`

**POM changes:** `integrations/consensus/pom.xml`, `integrations/statemachine/pom.xml`, `aether/aether-metrics/pom.xml`, `aether/aether-invoke/pom.xml` (transitive), `aether/aether-control/pom.xml`, `aether/node/pom.xml`, `integrations/net/tcp/pom.xml`, `integrations/cluster/pom.xml` (transitive) — all added `statemachine` dep.

## Issues filed

- `#188` — RC2: full collapse of `NodeRemoved` + `NodeDown` notifications into `NodeGone` at the notification layer. Label: `rc2`.

## References

- Plan: `/Users/sergiyyevtushenko/.claude/plans/i-want-you-to-quiet-volcano.md`
- RC1 session-handover chain: `2026-04-18` → `2026-04-21` → `2026-04-22` → `2026-04-23` → (this) `2026-04-24`.
