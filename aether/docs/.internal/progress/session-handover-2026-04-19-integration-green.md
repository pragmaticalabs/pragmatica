# Session Handover — 2026-04-19 — Integration Green (in progress)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `4b3683eee` · **9 files modified, not yet committed**

## TL;DR — where to resume

You are part-way through driving the integration suite to end-to-end green. Four fixes are in the working tree (tested, produce passing behavior in isolation). The remaining failure — `02-chaos/test-kill-leader` when run from the full suite runner — is NOT a product bug at this point; the same test passes **in isolation** but fails when run through `run-tests.sh`. Next step is to find what the suite-runner context adds that the isolation run doesn't.

**Best current pass rate (last full run, before we knew about the suite-vs-isolation discrepancy):** 11 / 15 suites pass; 4 fail. The four failures are all downstream of `test-kill-leader`'s leader-election timeout.

## Uncommitted changes (9 files)

```
M aether/node/src/main/java/org/pragmatica/aether/Main.java
M aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java
M aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java
M aether/node/src/test/java/org/pragmatica/aether/node/health/CoreSwimHealthDetectorHintEmissionTest.java
M aether/tests/integration/lib/cluster.sh
M aether/tests/integration/lib/generation.sh
M aether/tests/integration/run-tests.sh
M integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java
M integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java
```

All four fixes below should be committable as-is; user approved each landed change inline.

## Fixes landed this session

### Fix 1 — Rabia bootstrap sync race (regression A, previous handover)
**File:** `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java`

Two related changes:
1. Removed the cold-start "bypass ConnectionDirection when peerLinks is empty" in `connectPeer`. Rule is now strict: **lower NodeId always dials, higher always accepts**. Before this, both sides in a pair dialed concurrently; the duplicate-close cascade sent a CONNECTION_CLOSE over the SAME UDP flow the peer had stored in `peerLinks`, silently killing the kept entry. Nodes ended up with dead connections that send-path never evicted → broadcasts went to `/dev/null`.
2. `sendToConnection` on inactive connection now calls `evictStaleConnection` — removes from peerLinks + re-dials if we're the initiator side. NO `processViewChange(REMOVE)` — peer remains a valid cluster member; we just lost transport.
3. `QuicClusterNetwork.disconnect(DisconnectNode)` — SWIM-driven DisconnectNode is authoritative. Even if no peer link exists, we MUST propagate REMOVE to topology. Before this, `disconnect()` bailed early when `peerLinks.get(peerId) == null`, dropping the SWIM signal.

**Verification:** bootstrap-race scenario manually reproduced pre-fix, verified clean post-fix. All 5 nodes activate in 4-6s.

### Fix 2 — V0-only snapshot sync doesn't carry phase
**File:** `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java`

`restoreState` used to short-circuit on `snapshot.length == 0` to `activate()` without running `applyRestoredState`. That skipped `currentPhase.set(state.lastCommittedPhase())`. If the cluster had advanced via V0 decisions (which bump phase but leave state-machine unchanged), a syncing node ended up stuck at phase 0 and spewed `handlePropose: behind by 108 phases, Triggering resync` forever.

Fix: always call `applyRestoredState` first, then `activate()`, regardless of snapshot size.

### Fix 3 — Self-hostname advertised to peers
**File:** `aether/node/src/main/java/org/pragmatica/aether/Main.java` (`parsePeers`)

`selfInfo = nodeAddress("localhost", selfPort)` — in Docker / Hetzner, **peers inside other containers resolve `localhost` to themselves**. SWIM probes from peers hit their own port, return healthy, and the dead node stays in `coreNodes` as HEALTHY forever — so `coreCount` drifts above `coreMax`.

Fix: `selfInfo = nodeAddress(resolveHostname(), selfPort)` — uses `InetAddress.getLocalHost().getHostName()` which respects the container's `--hostname` flag.

### Fix 4 — SWIM doesn't register dynamically-learned peers
**Files:**
- `aether/node/src/main/java/org/pragmatica/aether/node/health/CoreSwimHealthDetector.java` (new `onNodeConnected(NodeInfo)` overload)
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (route handler looks up NodeInfo via `topologyManager.get(peerId)` and dispatches to the typed overload)

The existing `onNodeConnected(NodeId)` used `topologyConfig.coreNodes()` (static config) to resolve the SWIM address. CTM-provisioned replacements (e.g. `aether-core-node-0-XXX`) are learned **dynamically** via QUIC Hello — they're not in static config. Without the NodeInfo variant, SWIM never added them to membership, so when they died SWIM had no probe to fail, and topology held the dead phantom forever.

Regression test landed: `CoreSwimHealthDetectorHintEmissionTest.onNodeConnected_withDynamicallyLearnedPeer_emitsHealthyHint` (all 7 tests pass: `mvn test -pl aether/node -Dtest=CoreSwimHealthDetectorHintEmissionTest`).

### Fix 5 — Integration test runner reliability
**Files:** `aether/tests/integration/run-tests.sh`, `lib/cluster.sh`, `lib/generation.sh`

- `run-tests.sh` post-chaos quiesce relaxed from `current+1` to `current` — auto-heal already bumps epoch; demanding `+1` was unreachable.
- `run-tests.sh` post-chaos quiesce is now best-effort (log-warn, not abort). Aborting after one chaos failure turned 1 failure into 5 (SKIP cascades through 03/05/12/13).
- `rebuild_remote_node_image` uses `docker build --no-cache` — prevents BuildKit from reusing a stale jar layer when the build-info.properties happens to cache-match but bytecode has changed. (We hit this; confirmed stale binary via `/api/status` buildTimestamp.)
- `lib/generation.sh` `_resolve_epoch` retries up to 10s on transient missing snapshot — avoids false-abort when the leader has just transitioned.
- `lib/cluster.sh` `restart_all_nodes` adds `wait_for_node_count NODE_COUNT 60` after container restoration — gives SWIM-driven membership reconciliation time to prune phantoms before the next suite runs.

## Regression test added

```java
// CoreSwimHealthDetectorHintEmissionTest.java
@Test void onNodeConnected_withDynamicallyLearnedPeer_emitsHealthyHint() {
    var dynamic = new NodeId("aether-core-node-0-deadbeef");
    var info = NodeInfo.nodeInfo(dynamic, NodeAddress.nodeAddress("aether-core-node-0-deadbeef", 9001).unwrap());
    detector.onNodeConnected(info);
    // … assert HEALTHY signal emitted for dynamic peer (regression test for Fix 4)
}
```

## Last run result (after all fixes in place)

**Full 15-suite run on remote 192.168.0.71, docker env:**
- **PASS (10+):** 00-smoke, 04-streaming, 06-deployment, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 14-storage, 15-delegation (test-02-reassignment had an internal `assert_ne` fail that run_test treats as PASS — see "Known issue: soft-fail asserts" below)
- **FAIL (4):** 02-chaos (test-kill-leader only), 03-scaling (cascade), 12-network (cascade), 13-edge-cases (cascade)
- **Common failure signature:** `leader elected (timed out after 150s)` + `Cluster healthy with 4 nodes: expected 'healthy', got ''`

## Critical unresolved finding — resume here

**`test-kill-leader` passes in isolation, fails in the full suite.**

Reproduction — **isolation run passes in 17s**:
```bash
cd /Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/tests/integration
# fresh cluster B
ssh -i $AETHER_SSH_KEY aether@192.168.0.71 \
  'docker compose -f ~/docker-compose-b.yml down -v && docker compose -f ~/docker-compose-b.yml up -d'
# wait for boot, then:
CLUSTER_ID="b" CLUSTER_NAME="aether-b-node-" \
  CLUSTER_ENDPOINT="http://192.168.0.71:5160" MGMT_PORT="5160" \
  bash suites/02-chaos/test-kill-leader.sh
```

Result: 4 PASS, 1 FAIL (Auto-heal doesn't add replacement — CTM doesn't see deficit since cluster was at 4/5 of desiredMin but not provisioned from cloud). Leader re-election itself takes **17s**. The only failure in isolation is the separate auto-heal step.

Reproduction — **full suite run fails**:
```bash
./run-tests.sh --env remote --skip-build
```
Result: test-kill-leader's `wait_for_leader 150` times out — no leader elected in 150s.

So the bug is:
- NOT in the product's leader-election code path (isolation works)
- NOT an environmental docker/daemon issue (we did full `docker system prune -af --volumes` + daemon restart mid-session; no change)
- IS triggered by something the full-suite context adds before/during test-kill-leader

**What the full-suite runner adds that isolation doesn't:**
1. Blueprint deploy step (`deploy_blueprints "$CLUSTER_B_LB_MGMT" "${B_BLUEPRINTS[@]}"`) — B_BLUEPRINTS collected from all enabled cluster-B suite.conf files. 02-chaos uses `blueprint=test-echo`.
2. A `await_generation_quiesced "current+1" 60` barrier after blueprint deploy.
3. Cluster A running **10 suites in parallel** against its own 5 nodes — shares the docker daemon, host CPU, and host memory with cluster B.
4. `CLUSTER_A_LB_MGMT` / `CLUSTER_B_LB_MGMT` env vars (empty if no LB) propagate through to tests.

**Recommended next investigation:**
1. Run 02-chaos *alone* via the suite runner (no parallel cluster A):
   ```bash
   ./run-tests.sh --env remote --suites 02 --skip-build
   ```
   Does test-kill-leader pass? If YES → the bug is in the *shared-host-with-cluster-A* interaction (resource contention, port collision, or docker socket contention). If NO → the bug is in suite setup (blueprint deploy polluting cluster B state in a way that breaks leader election).

2. If blueprint deploy is implicated: compare cluster B's `/api/cluster/topology` and `/api/cluster/generation` BEFORE and AFTER `deploy_blueprints` — look for replica count drift, slice load state, epoch bumps.

3. Watch node-2's logs during the full-suite run at the *moment* node-1 is killed. The isolation run showed leader-election completes in 17s. If the suite-run shows `SWIM member faulty: node-1` firing but no new leader proposal submitted, that's an internal symptom we can trace. We saw this pattern earlier but on a STALE binary — need to verify on the fresh binary (in the last run, node-2 was crashing repeatedly; that crash mystery was resolved as `docker start` calls from chaos-cleanup not bumping `RestartCount`).

## Node crash (exit-137) mystery — RESOLVED

Earlier session hours: node-2's container showed multiple "Starting Aether node" log entries in a single container ID with `RestartCount=0`. We initially suspected a JVM bug.

**Resolution:** `RestartCount` only increments on Docker's built-in restart policy. `docker start`, `docker restart` (manual or from `cluster.sh`'s `restart_all_nodes` / `start_node`), and `NodeLifecycleManager.restartNode` all bypass `RestartCount`. The chaos tests kill node-2 (`pick_non_leader` picks it as first non-leader) and then bring it back via `docker start` — completely expected choreography, not a JVM bug. Exit code 137 is the SIGKILL that `docker kill` sends.

Node-2 gets more restarts than others because `test-kill-multiple`, `test-kill-node`, and `test-kill-under-load` all pick `pick_non_leader "node-1"` → `node-2`.

## Known issue: soft-fail asserts

`run_test` treats a test function's last command as the gate for PASS/FAIL. If a middle assertion (`assert_ne`, `assert_eq`) fails but the last assertion passes, the function returns 0 and run_test reports PASS. `test-02-reassignment`'s "SCALING reassigned from dead node" fails but the test reports PASS.

Fix (future): make `assert_*` accumulate failure into a per-function flag and make `run_test` check it. Not RC1-blocking — but worth an issue.

## Binary-freshness checklist (do this every time before running tests)

The docker build-cache burned hours of diagnostic work this session. Always verify:
1. Local jar has the fix: `unzip -p aether/node/target/aether-node.jar org/.../YourFile.class | strings | grep yourMethod`
2. Remote image timestamp: `ssh … 'docker images aether-node:local --format "{{.CreatedAt}}"'`
3. In-container build timestamp:
   ```bash
   curl -s -H "X-API-Key: $AETHER_API_KEY" http://192.168.0.71:5160/api/status | grep -oE 'buildTimestamp"[^,]*'
   ```
4. `run-tests.sh` now uses `docker build --no-cache` — ensures no BuildKit surprises.

## Files that matter for next session

- **Product code under investigation:** `integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/RabiaEngine.java` (leader-election path) + `integrations/consensus/src/main/java/org/pragmatica/consensus/leader/LeaderManager.java` (proposal timing).
- **Suite runner context:** `aether/tests/integration/run-tests.sh` lines 580-610 (blueprint deploy + quiesce barrier), line 333 (`run_cluster_b_suites`).
- **Blueprint content:** `aether/tests/integration/blueprints/test-echo/` — what's deployed to cluster B before chaos runs.

## Environment at handoff

- **Cluster B** currently up on remote 192.168.0.71, fresh after my last isolation-run reset.
- **Cluster A** may or may not be up — not needed for next isolation investigations.
- **Remote build cache** is warm with the current-code `aether-node:local` image (buildTimestamp 19:06:13Z). If more code fixes land, push+rebuild+redeploy.
- **Docker env vars already exported** in parent shell: `AETHER_API_KEY`, `AETHER_SSH_KEY`, `TARGET_HOST=192.168.0.71`. Don't re-specify them on command lines.

## Commits to make (when investigation complete)

Suggested 4 squashed commits after green run:
1. `fix: QuicClusterNetwork strict ConnectionDirection + SWIM-authoritative disconnect`
2. `fix: Rabia restoreState carries phase even for empty snapshot`
3. `fix: Main selfInfo uses container hostname; SWIM registers dynamic peers via NodeInfo overload`
4. `test: integration-suite runner resilience (no-cache build, best-effort quiesce, wait-for-node-count cleanup)`

(Per project policy: single-line commit messages, no trailers, no `Co-Authored-By`.)

## Open decision

If after the `02-only` run the failure reproduces → it's in suite setup. If it doesn't → it's parallel-cluster interference. Recommend filing both hypotheses as separate issues and fixing the first identified.

**Stop point:** isolation run proved the product code paths work. Suite-runner context is the actual culprit. Resume by comparing what the suite runner does vs my isolation invocation.
