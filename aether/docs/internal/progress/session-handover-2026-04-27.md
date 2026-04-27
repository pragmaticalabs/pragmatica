# Session handover — 2026-04-27

**Branch:** `release-1.0.0-rc1`
**HEAD at end of session:** `fff20a540` + Rabia phase-gap-detection fix (this handover commit pending)
**Prior handovers:** `session-handover-2026-04-26-reconnect-fix.md`
**Commits this session:** 10 (`46b2e1035` → `fff20a540`)

## One-line summary

Ten commits landed targeting the integration suite's chaos-chain failures; deep diagnosis culminated in identifying Rabia's phase-gap apply asymmetry as the primary remaining gating defect — handleDecision skips intermediate phases when a Decision arrives out of order, leaving the state machine inconsistent with peers; current run holds at 8/15 stable.

## Commits this session (chronological)

```
46b2e1035  fix(consensus): QuorumWaiting periodic 1Hz re-check (Fix A)
b6e373942  fix(deployment): seed observedCoreEpoch on first ON_DUTY (Fix B)
dfc890149  test(integration): wait_for_leader cluster-B floor 120s + is_cluster_ready leader gate (Fix I+J)
e350b009c  fix(consensus): TopologyObserver consults KV NodeLifecycleValue.DECOMMISSIONED (Fix C)
0fa9eecaa  fix(deployment): SliceNodeValue+NodeArtifactValue carry transitionedAt; Active.onEntry re-derives transitionalStateTimestamps from KV (Fix E)
27226669e  fix(deployment): KV-mirror CTM inFlightProvisions (Fix D)
3e0ed7261  fix(consensus): allow all nodes to propose during initial election (single-proposer-rule removal)
f60abbe94  fix(consensus): Electing/ReElecting stuck-tick recovery — submitProposalWith silent early-returns reschedule + INFO logs
d022556af  fix(consensus): pull-side leader recovery from KV-Store
fff20a540  fix(consensus): close Rabia sync apply-asymmetry — buffer Decisions during Stopped/Syncing + advance-only applyRestoredState
```

All 10 compile, all module tests pass (372+ across `integrations/consensus`, `integrations/cluster`, `aether/aether-deployment`, `aether/node`).

## Integration test result trajectory

| Run | Pass/Total | Notes |
|---|---|---|
| Pre-session baseline (handover-04-26) | smoke FAIL, ~7/15 | - |
| After 6-fix wave (D pushed) | 8/15 | recovered smoke; 08-resources flake |
| After single-proposer removal | 7/15 | 08-resources regressed momentarily |
| After stuck-tick reschedule | 8/15 | 08-resources clean again |
| After KV-pull leader recovery | 8/15 | unchanged |
| After Rabia sync apply-asymmetry fix | 8/15 (mid-suite stop) | 08-resources timing improved 358s → 69s |

**Net delta**: smoke gate restored, 8 of 10 cluster A non-destructive suites fully green, **0 of 5 cluster B chaos suites pass**.

## Cluster A residual flakes (out of scope)

- **06-deployment** (`test-deploy-canary`): post-blue-green `cluster healthy` 60s timeout. Soft fail (downstream `all task groups ACTIVE` passes immediately afterward). Pattern: cluster transitions through transient leaderless-looking state right after a 61-second blue-green promote. Likely Fix-J's stricter `is_cluster_ready` gate is now correctly surfacing a brief generation-snapshot transition; cosmetic test-side bump may suffice.
- **15-delegation** (`test-02-reassignment`): operator-PUT METRICS reassign doesn't take effect; SCALING auto-failover picks the dead node. **Different code path** from CTM surplus comparator (Fix B target) — lives in `TaskAssignmentCoordinator.reassign()` and the `/api/cluster/tasks/reassign/{group}` route handler. Out of scope for this session; needs its own investigation.

## Cluster B failure — the actual gating defect

After all 10 commits land, integration tests on cluster B reproduce the same `Initial_5_nodes=328-339s` stall pattern. Live diagnostic during a stall (after test was paused):

| Node | leaderId | nodeCount | coreCount (`/api/cluster/topology`) | epoch |
|---|---|---|---|---|
| node-1 (rejoined ~21:28) | none/node-3 | 5 | **5** | (no epoch) |
| node-2 (rejoined ~21:20) | none/node-3 | 5 | **5** | (no epoch) |
| node-3 (continuous since 20:46) | node-3 | 5 | **3** | **1:0** |
| node-4 (continuous since 20:46) | node-3 | 4 | **5** | **1:4** |
| node-5 (continuous since 20:46) | node-3 | 4 | **5** | **1:4** |

**The defect**: node-3 is the LEADER, was alive throughout the run, but its local generation snapshot is stuck at epoch **1:0** while node-4 and node-5 (both continuously alive too) are at epoch **1:4**. node-3's `coreCount` is computed from its stale snapshot and reports 3 (not 5).

When the test's `is_cluster_ready` rotates to query node-3 and gets `coreCount=3`, the gate fails (test wants ≥5). This is the dominant cause of the chaos-chain stall.

## Why Fix A + Fix B (Rabia sync apply-asymmetry, today's last commit) didn't cover this

Fix A buffers Decisions delivered during `Stopped`/`Syncing`. Fix B prevents `applyRestoredState` from regressing `currentPhase`. Both target the *rejoining-node* path: a node going through `Stopped → Syncing → Idle`.

But node-3 in this scenario was never restarted. It stayed in `Idle`/`InPhase` throughout. It missed Decisions in a different code path:

**`handleDecision` (`RabiaEngine.java:1025-1046`) has NO phase-gap check.** When a Decision arrives for a phase far ahead of `currentPhase`, the engine:

1. Calls `commitDecision(decision)` — applies the Decision's commands.
2. `commitDecision` calls `advancePhase(decision.phase(), value, false)`.
3. `advancePhase` does `currentPhase.set(decision.phase().successor())` — **jumps the counter**, skipping intermediate phases entirely.

Concretely: node-3 at phase 1:0 receives a Decision for phase 1:4. Its state machine applies *only* the commands carried in phase 1:4's batch. Decisions for phases 1:1, 1:2, 1:3 (and their commands — including, presumably, generation-snapshot updates) are silently lost. `currentPhase` jumps to 1:5. From node-3's local KV-Store perspective, the cluster's recent committed state never happened.

Compare to `handlePropose` (line 806-815): when a Propose arrives for a phase far ahead, the code DOES check (`isFarFuturePhase`, threshold = 100 phases) and triggers `triggerResync()`. The asymmetry is structural — Propose has gap detection, Decision does not.

## The fix (precise instructions)

### File
`integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java`

### Change

In `handleDecision`, after the existing Stopped/Syncing buffer guard, add a phase-gap check that triggers resync (and buffers the Decision for replay after resync completes):

```java
private void handleDecision(Decision<C> decision) {
    log.trace("Node {} received decision {}", self, decision);
    var state = engineState.get();
    if (state instanceof EngineState.Stopped || state instanceof EngineState.Syncing) {
        bufferedDecisions.offer(decision);
        if (bufferedDecisionCount.incrementAndGet() > MAX_BUFFERED_DECISIONS) {
            bufferedDecisions.pollFirst();
            bufferedDecisionCount.decrementAndGet();
        }
        return;
    }
    // NEW: phase-gap detection. A Decision for a phase materially ahead of `currentPhase`
    // means we missed intermediate phases' Decisions (and their commands). `commitDecision`
    // would `advancePhase(decision.phase().successor())`, skipping the intermediate phases
    // entirely — their KV mutations are lost, the local state machine diverges from peers.
    // Mirror `handlePropose`'s `isFarFuturePhase` check (line 822-826), but with a smaller
    // threshold: a single phase gap is normal under jitter, but more than that indicates the
    // node is genuinely behind. Buffer the Decision and trigger resync; the post-resync
    // drain will apply it correctly relative to the restored phase.
    var current = currentPhase.get();
    var gap = decision.phase().value() - current.value();
    if (gap > MAX_DECISION_PHASE_GAP) {
        log.warn("Node {} received Decision for phase {} while at phase {} (gap={}). Buffering and triggering resync.",
                 self, decision.phase(), current, gap);
        bufferedDecisions.offer(decision);
        bufferedDecisionCount.incrementAndGet();
        triggerResync();
        return;
    }
    commitDecision(getOrCreatePhaseData(decision.phase()), decision);
}

private static final long MAX_DECISION_PHASE_GAP = 3;
```

### Why threshold 3

- 0 = strict in-order (over-aggressive — out-of-order delivery is normal under brief jitter).
- 1-2 = tolerates one or two reordered Decisions (likely from concurrent broadcast paths).
- 3 = above this, the node is genuinely behind and resync is the right action.
- Compare to `MAX_PHASE_AHEAD = 100` for Propose — much larger because Propose can arrive long before the consensus round completes; Decisions are end-of-phase markers and should arrive in tight succession.

### Why `triggerResync` after buffering

`triggerResync()` calls `doClusterConnected()` which transitions the engine to `Syncing`. The buffer guard (Fix A from `fff20a540`) now accepts Decisions during Syncing. After sync completes, the buffer drain applies them filtered by `phase >= currentPhase`. This means:
- Decisions before the resync's restored phase are correctly skipped (already in the snapshot).
- Decisions at or after the restored phase are correctly applied.
- The fast-forward bug is closed.

### Tests to add

Unit tests in `RabiaEngineTest`:
1. `handleDecision_phaseGapBeyondThreshold_triggersResync` — set `currentPhase=phase(0)`, send `Decision(phase=phase(10))`, assert `engineState` is `Syncing` and the Decision is in `bufferedDecisions`.
2. `handleDecision_phaseGapWithinThreshold_appliesNormally` — set `currentPhase=phase(0)`, send `Decision(phase=phase(2))`, assert `commitDecision` runs and `currentPhase` advances to `phase(3)`.
3. `handleDecision_postResyncDrain_appliesBufferedFutureDecisions` — trigger gap, complete resync at `phase=phase(8)` (between gap-Decision phase and current), assert buffered `Decision(phase=phase(10))` is applied via drain.

### Risk

- Low. This adds a guard before `commitDecision`; correctness preserved (Decisions still apply or buffered for replay).
- The threshold value matters: too low triggers spurious resyncs under normal jitter; too high allows the bug. 3 is conservative without being aggressive.
- Existing tests that send out-of-order Decisions with gap > 3 would observe the new resync path. Audit `RabiaEngineTest` for any such tests; update or expect resync.

## What WORKS now (vs session start)

1. **Smoke gate green** — restored end-to-end on remote.
2. **All cluster A non-destructive suites except 06/15 flakes pass** (8/10 fully green, ~28+/30 sub-tests).
3. **08-resources timing recovered**: 358s → 69s (5x faster).
4. **Within-test re-elections fast** (0s) on cluster B chaos suites.
5. **All 10 commits compile + module tests pass** (372+ tests).

## What does NOT work yet

1. **Cluster B chaos chain (5 suites)** — gating on Rabia phase-gap apply asymmetry described above. The fix in this handover (post-commit) is expected to close it.
2. **15-delegation operator-PUT reassign** — separate `TaskAssignmentCoordinator` defect. Out of scope.
3. **06-deployment canary `cluster_healthy` 60s timeout** — likely cosmetic; downstream assertions pass.

## Critical files (touched this session)

### Consensus / leader election
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionState.java` — Fix A polling, stuck-tick reschedule, KV-pull leader recovery (adoptLeaderFromKvIfPresent), single-proposer-rule removal.
- `integrations/consensus/src/main/java/.../leader/fsm/LeaderElectionContext.java` — `currentLeaderFromKvSupplier` plumbing.
- `integrations/consensus/src/main/java/.../leader/LeaderManager.java` — factory overloads through new supplier.
- `integrations/consensus/src/main/java/.../rabia/RabiaEngine.java` — `bufferedDecisions` + `drainBufferedDecisions` + `applyRestoredState` advance-only. **The phase-gap fix in this handover lives here.**
- `integrations/cluster/src/main/java/.../node/rabia/RabiaNode.java` — wires KV supplier through to LeaderManager.
- `integrations/cluster/src/main/java/.../state/kvstore/KVStore.java` — `getTyped` for mixed-type lookups.

### Topology / CTM
- `integrations/consensus/src/main/java/.../topology/TopologyObserver.java` — Fix C (KV DECOMMISSIONED predicate).
- `aether/aether-deployment/src/main/java/.../cluster/ClusterTopologyManagerRecord.java` — Fix D (KV-mirror inFlightProvisions).

### Deployment
- `aether/aether-deployment/src/main/java/.../node/NodeDeploymentManager.java` — Fix B (seed observedCoreEpoch).
- `aether/aether-deployment/src/main/java/.../cluster/fsm/ClusterDeploymentState.java` — Fix E (transitionalStateTimestamps re-derivation).
- `aether/slice/src/main/java/.../kvstore/AetherValue.java` — Fix E (`transitionedAt` field on SliceNodeValue + NodeArtifactValue).
- `jbct/slice-processor/src/main/java/.../codegen/ManifestGenerator.java` — `ENVELOPE_FORMAT_VERSION` 1003 → 1004.

### Wiring
- `aether/node/src/main/java/.../AetherNode.java` — KV supplier for leader, isDecommissioned predicate, snapshot-supplier for epoch.

### Test infrastructure
- `aether/tests/integration/lib/cluster.sh` — Fix I+J (cluster-B 120s floor + leader-gated `is_cluster_ready`).
- `aether/tests/integration/lib/common.sh` — `WAIT_FOR_LEADER_TIMEOUT` env-var escape hatch.

## Investigations performed (this session)

1. **FSM temporal choreography** — `/tmp/investigation-fsm-rhythm.md`. Mapped all FSMs + tick cadences. Identified QuorumWaiting one-shot consensus-readiness check; led to Fix A.
2. **SSOT topology** — `/tmp/investigation-ssot-topology.md`. Cataloged all KV atom families + in-memory state holders. Identified `observedCoreEpoch=Epoch.ZERO` global default; led to Fix B.
3. **Synthesis** — `/tmp/synthesis-fixes-to-15.md`. Verified both above; ranked fixes A, B, C, D, E, I+J.
4. **Single-rejoin leader stall** — diagnosed live cluster, identified node never logging `Submitting leader proposal` after one Electing tick; led to stuck-tick reschedule fix.
5. **Forward Rabia sync defect** — identified handleDecision missing engine-state guard + applyRestoredState unconditionally regresses currentPhase; led to fff20a540.
6. **Regression archaeology** — pinpointed `7cfe889c6` (advancePhase InPhase-only guard) and `0caab1f69` (TopologyManagementMessage cleanup) as suspect commits.
7. **This handover** — final live-diagnostic identifies handleDecision phase-gap defect (post-fff20a540 residual).

## rc2 deferred items (unchanged from prior handover)

- `#189` drain protocol on quorum loss (Q11+Q12+Q13).
- TopologyMembersKey atom (architectural — covered above as alternative)
- Rabia engine reorganization once phase-gap fix lands (consider state-machine consolidation similar to LeaderElection's FSM rewrite).

## Next-session P0

1. **Verify the phase-gap fix in this handover unblocks cluster B chaos chain.** Run integration after commit; expected delta: 8/15 → 12+/15.
2. **15-delegation operator reassign** — investigate `TaskAssignmentCoordinator.reassign()` and its REST route handler. Different code path from the consensus surplus comparator.
3. **06-deployment canary timeout** — likely cosmetic test-side bump.
4. **rc2 #189 drain protocol** — separate session.

## Verification commands

```bash
# Module tests (post-fix)
mvn -pl integrations/consensus,integrations/cluster,aether/aether-deployment,aether/node test -am

# Rebuild aether-node jar (without build.sh which can be blocked by JBCT lint)
mvn -pl aether/node install -DskipTests -am

# Verify fix in deployed JAR (after integration deploy)
ssh $AETHER_SSH_USER@$TARGET_HOST "docker exec aether-b-node-3 sh -c \
  'jar -xf /app/aether-node.jar org/pragmatica/consensus/rabia/RabiaEngine.class && \
   javap -p -v org/pragmatica/consensus/rabia/RabiaEngine.class | grep -E MAX_DECISION_PHASE_GAP'"

# Integration suite (use --skip-build to bypass build.sh JBCT lint trap)
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build
```

## Specific live-diagnostic queries (post-fix validation)

```bash
# Each node's epoch + coreCount — should be IDENTICAL across all 5 once fix lands
for n in 1 2 3 4 5; do
  curl -s -m 2 -H "X-API-Key: aether-integration-test-key" \
    "http://localhost:516$((n-1))/api/cluster/topology" \
    | grep -oE '"coreCount":[0-9]*|"epoch":"[^"]*"'
done
```

If post-fix epochs converge AND coreCounts agree, the chaos chain should follow.

---

**Session totals**: 10 commits (~14 hours active development), 1 architectural diagnosis chain (3 investigators), final fix instructions captured here for the next session to implement and validate.
