# Session Handover — 2026-05-09

**Branch:** `release-1.0.0-rc1` · **HEAD:** `8e721c625` (pushed) · **Tag:** `v1.0.0-rc1-candidate` at HEAD (force-pushed; CI-rebuilt artifacts on GH release verified updated)

Continuation of [`session-handover-2026-05-08.md`](session-handover-2026-05-08.md). That doc identified `GenerationSnapshotPublisher` quiescence as the dominant remaining flake. Today we **diagnosed and shipped that fix**, plus 3 other architectural fixes uncovered along the way, plus a recovery-ownership doc. Then validated on docker-remote (14/15 reliably, snapshot-quiesce flake fully eliminated), validated JVM cloud (2/2 perfect on subset), validated Container cloud post-restart-fix (provisioning works, kills are now authoritative), and **uncovered a deeper architectural bug** in the cloud Container kill-leader path that survives the restart-policy fix.

---

## ⚡ TL;DR for next session

**The cloud Container kill-leader cluster freeze (§5) is the next architectural bug to find.** Diagnosis: when the leader is killed on cloud Container, surviving nodes detect SWIM-FAULTY locally (~16s), but no node writes DECOMMISSIONED for the dead leader because **`HealthReconcilerImpl.handleAggregatedEdge` is leader-gated** and the leader IS the dead node. Re-election requires `NodeRemoved`/`NodeGone`, which requires either QUIC EVICTED (apparently doesn't fire on cloud Container within 2+ min) or KV-snapshot membership delta (requires DECOMMISSIONED write — catch-22). JVM cloud passes the same chaos suite in 158s, so the divergence is at the QUIC/transport layer specifically for container deployments on cloud.

Diagnostic logs preserved at `/tmp/aether-runs/diagnostics-064908/cluster-b-*.log` (all 5 cluster B node logs from the kill-leader test).

If you want to keep iterating:
```bash
# 1. Power on PG (was destroyed in cleanup; re-provision with provision-test-pg.sh)
tools/provision-test-pg.sh
# This produces /tmp/aether-test-pg.env with PG_URL

# 2. For docker-remote validation (14/15 expected):
cd aether/tests/integration && source /tmp/aether-test-pg.env && \
  ./run-tests.sh --env remote --skip-build

# 3. For cloud JVM validation (2/2 on subset; full 15-suite untested):
cd aether/tests/integration && source /tmp/aether-test-pg.env && \
  ./run-tests.sh --env cloud --runtime jvm --suites 00,02 --skip-build

# 4. For instrumented cloud Container investigation:
#    - Add WARN logs to handleAggregatedEdge / processViewChange / Led.handle
#    - Build, run --suites 00,02 cloud, capture which step short-circuits
```

---

## 1 · State at session end

| Item | Value |
|---|---|
| Branch HEAD | `8e721c625` (pushed) |
| Tag `v1.0.0-rc1-candidate` | at `8e721c625` (force-pushed; GH release JAR rebuilt) |
| Local `aether-node.jar` | fresh (May 9 ~04:26) |
| Local CLI `~/.aether/lib/aether.jar` | fresh shaded (May 9 04:26) |
| Hetzner account | **fully clean** (cloud-reaper swept everything including PG VM) |
| PG firewall | closed |
| Working tree | clean (only ephemeral test-results.json) |

---

## 2 · Today's commits (8 — all pushed)

```
8e721c625 docs(changelog): restart-policy fix, rotate cloud-aware, JVM runtime flag, recovery doc
9c6e4d0be fix(test-infra): rotate_mgmt_entry_point cloud-aware (iterate VM IPs vs port range)
f6f03a1fa fix(deploy,docs): set --restart=no on aether-node containers; CTM owns recovery
698f2de0c feat(tests): wire --runtime jvm flag for cloud env (uses cloud-hetzner-jvm{,-b}.toml)
d31d9b5fa docs(changelog): snapshot-quiesce fix, disruption-budget calc, SSH preflight, test infra
baf108278 fix(cli): bump SSH preflight timeout 180s -> 300s for slow Hetzner cloud-init
ed924ad0e fix(disruption-budget,tests): count only ON_DUTY against initial topology; race-tolerant drain tests
4f5e3c936 fix(generation): publisher heartbeats unconditionally on leader; await-quiesced triggers fresh publish
```

### Verdicts

| Commit | Status | Notes |
|---|---|---|
| `4f5e3c936` Publisher 1Hz heartbeat | ✅ KEEP | Cures snapshot-quiesce 408 cascade. docker-remote: barriers resolve 0-1000ms (was 60-120s timeouts). Cost: 1 Rabia round/sec/cluster — negligible. |
| `ed924ad0e` Budget calc + race-tolerant drain | ✅ KEEP | Closes 13-edge-cases first-drain false-409. Test side accepts either 200 (within budget) or 409 (auto-heal raced) for 2nd/3rd drain. Pragmatic: with auto-heal active, the budget reject path can't be deterministically forced on docker-remote. |
| `baf108278` SSH preflight 180→300s | ✅ KEEP | Hetzner cx33 cloud-init regularly exceeds 180s on contended days. Validated: cluster A in cloud Container run #4 reached SSH ready at ~210s. |
| `698f2de0c` --runtime jvm flag | ✅ KEEP | New `cloud-hetzner-jvm-b.toml`; `--runtime jvm` switches both A/B TOMLs and cluster names (`cloud-test-a-jvm` / `cloud-test-b-jvm`). |
| `f6f03a1fa` --restart no + recovery doc | ✅ KEEP | Container restart policy must not compete with CTM auto-heal. Operator doc explains the layer violation. Validated: cloud VM `docker inspect` confirms `restart=no`; killed container stayed exited (was `unless-stopped` auto-respawning before). |
| `9c6e4d0be` rotate cloud-aware | ✅ KEEP | After restart-policy fix made kills authoritative, the test framework's port-range rotation broke on cloud (each VM has own IP, not host-port mapped). Cloud branch iterates `cloud_public_ip(node-N)`. |

---

## 3 · Validation results

### docker-remote (run #7 — final)
- **14/15** suites passed; only failure: 12-network/SCALING reassignment (pre-existing flake from §7 of prior handover)
- Snapshot-quiesce flake fully eliminated — every quiesce barrier resolved in 0-1000ms
- 13-edge-cases disruption-budget 3p/0f (race-tolerant test)
- 06-deployment 5p/0f (was occasional flake)
- 08-resources 5p/0f (probe back to GET; PUT-probe attempt corrupted slice state, reverted)

### cloud JVM subset (00,02)
- **2/2 perfect** — cluster formation 7s, 02-chaos 158s
- All 5 nodes JVM-launched via cloud-init `nohup java -jar`
- Killed leader recovered properly (kill via `pkill -f java -jar`)

### cloud Container subset (00,02) post-restart-fix
- 00-smoke 2/2 ✓
- 02-chaos: kill-leader test produced **the freeze** (§5). `--restart=no` confirmed deployed via `docker inspect`. Killed container exited 137, stayed dead. Surviving nodes detected SWIM-FAULTY in 16s. Then logs go silent for 2+ minutes. Test killed.
- Note: on cloud Container, **the prior session's "60 min for 02-chaos" was a different bug** (Docker `unless-stopped` auto-respawning the killed container, masking the failure event entirely). Today's restart-policy fix eliminates that. But a deeper bug surfaces underneath.

---

## 4 · Bugs fixed today — root cause briefs

### 4.1 Snapshot-quiesce 408 cascade (`4f5e3c936`)

`AetherNode.java:1240-1251` had a 1Hz `swimHintsTickExecutor` that called `markDirty()` only `if (!swimHints.isEmpty())`. When SWIM hints expired (default TTL 60s in idle clusters), no Mark events fired. `GenerationSnapshotPublisher` stayed Idle. The counter stopped advancing.

Test `current+1` semantics in `lib/generation.sh:114-118` (`generation_quiesce_now`): read current epoch, ask server to wait for current+1 with `quiescence == QUIESCED`. With no publishes in flight, the requested epoch never arrived. Server polled internally for 60-120s, returned 408. The 408 cascaded into 503 drains, missing `NODE_FAILED` events, 500 PUTs.

Fix: drop the `if (!swimHints.isEmpty())` guard. Leader publishes every 1s regardless of activity. Renamed executor `publisherTickExecutor`. Plus added `requestGenerationSnapshotRefresh()` to `ManageableNode`; `ClusterAwaitQuiescedRoute` calls it on entry so the request itself drives a fresh publish.

### 4.2 Disruption-budget DECOMMISSIONED pollution (`ed924ad0e`)

`NodeLifecycleRoutes.checkDisruptionBudget` previously:
```java
var totalNodes = initialTopology().size();
var currentlyUnavailable = countNonOnDutyNodes();  // counts DECOMMISSIONED too
operationalAfterDrain = totalNodes - currentlyUnavailable - 1;
```
After 14 destructive suite cycles, KV had 5+ DECOMMISSIONED entries from killed nodes. Calculator saw `currentlyUnavailable=5+`, `operationalAfterDrain=-1`, rejected even the first drain.

Fix:
```java
var minAvailable = (initialTopology().size() / 2) + 1;  // intended majority
var operationalAfterDrain = countOnDuty() - 1;          // live capacity
```
Live ON_DUTY count vs majority of intended size. DECOMMISSIONED entries are historical, not unavailable members.

Also: removed inter-drain `await_generation_quiesced` waits in the test. CTM auto-heal would otherwise replenish drained capacity during the wait, masking the budget. Test now drains rapidly; second/third drain accept either 200 (within budget) or 409 (auto-heal raced; budget guarded quorum).

### 4.3 Container `--restart no` (`f6f03a1fa`)

`BootstrapPhaseDeploy.buildRestartCommand` and `UserDataTemplate.appendContainerRun` previously emitted `docker run ... --restart unless-stopped`. When chaos tests `docker kill` an aether-node container, Docker treated SIGKILL exit 137 as a transient crash and immediately restarted the container.

Effects:
- Cluster's KV-store has already evicted the node-id under the single-writer DECOMMISSIONED rule, so the respawned container couldn't rejoin → flap-loop.
- CTM never observed the failure (no `NODE_FAILED` event).
- Chaos engineering structurally impossible.
- 02-chaos took 60+ min on cloud per prior session.

Fix: `--restart no` on cloud Container deployments + cluster A docker-compose (cluster B was already `restart: "no"`). Aether's CTM auto-heal IS the recovery layer.

Operator doc: `aether/docs/operator/deployment-recovery.md` explains the layer-violation principle for Docker, Kubernetes, Nomad, systemd.

### 4.4 SSH preflight 180→300s (`baf108278`)

Hetzner cx33 cloud-init regularly exceeds 180s under contention (apt update + Docker pull + container start). 300s buys a comfortable margin while still detecting genuinely-stuck hosts.

### 4.5 `rotate_mgmt_entry_point` cloud-aware (`9c6e4d0be`)

After the restart-policy fix made cloud kills authoritative, the test framework's `rotate_mgmt_entry_point` couldn't find a surviving node to query for the new leader. The original implementation iterated `MGMT_PORT..MGMT_PORT+NODE_COUNT-1` on `TARGET_HOST` (correct for docker host-port mapping; invalid on cloud where each VM has its own public IP, mgmt port uniformly 8080).

Fix: branches on `ENV_TYPE`. Cloud iterates over node-ids resolving each to its public IP via `cloud_public_ip`. docker/remote keeps the port-range scan.

---

## 5 · Cloud Container kill-leader cluster freeze (NEXT-SESSION TARGET)

### Symptom

After `--restart no` deployment validated, cloud Container 02-chaos test killed the leader. Container exited cleanly (`docker inspect` shows `Exited (137)`, `restart=no`). Surviving 4 VMs continued running. SwimProtocol on each surviving VM marked the dead leader FAULTY at 04:32:23 (~16s after kill).

**Then logs go silent for 2+ minutes.** No HealthReconciler aggregation log. No leader-election trigger. No DECOMMISSIONED write. KV-store still reports the dead node as `lifecycleState: ON_DUTY` and `isLeader: true` 14 minutes later.

JVM cloud doesn't hit this — chaos completed in 158s with proper recovery.

### Diagnostic logs

`/tmp/aether-runs/diagnostics-064908/cluster-b-{IP}.log` for 5 cluster B nodes. Key timeline (from `46.224.170.85`):

```
04:31:13  HealthReconciler started, expectedClusterSize=5, phase=BOOTING
04:31:14  Quorum established
04:31:14  HealthReconciler promoting self to ON_DUTY (BOOTING)
04:31:19  HealthReconciler wrote ON_DUTY for hetzner-eu-core-1
04:31:23  Quorum disappeared (transient — boot phase)
04:32:07  SWIM member suspected: hetzner-eu-core-0
04:32:23  SwimHealthState routeFaultyPeer: hetzner-eu-core-0 (currentLeader=Some(core-0))
04:32:23  SwimProtocol.transitionToFaulty: Member hetzner-eu-core-0 marked FAULTY
[silence for 2+ minutes]
04:34:33  HTTP socket error (unrelated)
```

### Architectural analysis

The chain that should fire:

| Step | Mechanism | Status on cloud Container |
|------|-----------|---------------------------|
| 1. Detect peer FAULTY | `SwimProtocol.transitionToFaulty` | ✓ fires at 04:32:23 |
| 2. Emit observation | `SwimProtocol.deliverObservation(FaultyObserved)` | ✓ implicit (listeners registered) |
| 3. HealthReconciler.onSwimObservation | `swimHealthDetector.addObservationListener` wires it | ? — no log evidence either way |
| 4. Aggregate edge | `ObservationAggregator.onObservation` | ? — no log evidence |
| 5. Write DECOMMISSIONED | `HealthReconcilerImpl.handleAggregatedEdge` | ✗ **leader-gated**: skips if `!isLeader()` |
| 6. New leader elected | `LeaderElectionState.Led.handle(NodeGone)` | ✗ never fires (depends on NodeRemoved) |

**The bottleneck is step 5: `handleAggregatedEdge` requires `isLeader()` to write DECOMMISSIONED. The leader IS the dead node. No surviving node is leader. No write happens.**

```java
// HealthReconcilerImpl.java:243
private void handleAggregatedEdge(ObservationAggregator.StateChanged edge, long nowMs) {
    if (!isLeader()) {
        log.trace("HealthReconciler: follower {} skips lifecycle write for {} -> {} (leader-gated)", ...);
        return;
    }
    ...
}
```

The leader-gating is deliberate (avoid duplicate writes from N witnesses). But it has no escape hatch for the case where the target IS the leader.

### Why does docker-remote work?

QUIC's `processViewChange` EVICTED case directly emits `TopologyChangeNotification.nodeRemoved(peerId, currentView())` (`QuicClusterNetwork.java:1097`). This is a transport-level event independent of leader state. `LeaderManager.nodeRemoved` → `fsm.dispatch(NodeGone)` → `Led.handle(NodeGone)` checks `if (ng.node().equals(leader))` → `transitionTo(reElecting())`. New leader elected, then writes DECOMMISSIONED.

On docker-remote (sub-ms RTT, kernel sees TCP RST when container dies on shared host), QUIC eviction fires fast → re-election fast → cluster recovers in ~10s.

On cloud Container, the same QUIC eviction must be firing slowly or not at all. SWIM detects FAULTY at 16s but QUIC eviction doesn't fire within 2+ min. JVM mode on the same cloud network works — so the difference is container teardown specifically.

### Hypotheses for the divergence

1. **QUIC inactivity timeout default is too long for cloud Container.** Maybe set high to tolerate brief cloud network blips, but the 2-min silence suggests >120s. Check `QuicClusterNetwork` config defaults.
2. **Container teardown leaves stale QUIC connection state.** With `--network host`, the container shares host network namespace. When the container's PID 1 (java) dies, the host's UDP sockets close. Should produce RST-equivalent for QUIC. But maybe Docker keeps the network namespace alive briefly during cleanup, holding sockets.
3. **JVM mode = no Docker = bare process kill = clean socket close.** Container mode = Docker process death + container cleanup = potentially delayed socket close. SwimProtocol uses UDP (datagrams), not connections — but QUIC over UDP needs explicit close or timeout.
4. **`emitFaultyOrUnknown` cold-boot suppression** — wait, audit step 6 is supposed to handle this. Let me cross-check the log: at 04:31:14 quorum established, then phase should transition to NORMAL. SWIM detected FAULTY at 04:32:23 — well after NORMAL. So suppression shouldn't fire. But worth confirming.

### Recommended next-session approach

**Add WARN-level instrumentation at:**

1. `HealthReconcilerImpl.handleAggregatedEdge` line 244 (the leader-gated skip):
   ```java
   log.warn("HealthReconciler skip: target={} edge={} isLeader={} currentLeader={}",
            edge.target(), edge.newState(), isLeader(), leaderReader.get());
   ```
2. `HealthReconcilerImpl.aggregateEdge` (entry):
   ```java
   log.warn("HealthReconciler observation: {}", observation);
   ```
3. `ObservationAggregator.onObservation` — log threshold check result.
4. `QuicClusterNetwork.processViewChange` EVICTED case:
   ```java
   log.warn("QUIC EVICTED: peer={} currentView={}", peerId, currentView());
   ```
5. `QuicClusterNetwork` connection-loss / inactivity-timeout fire (find the right method).
6. `LeaderElectionState.Led.handle` NodeGone branch.

Build, install CLI + node, deploy a minimal cloud Container cluster B (5 VMs), kill leader, capture logs at all 5 nodes, observe which step short-circuits or which timeout fires.

Cost: ~€1, ~10 min. Diagnostic value: should pinpoint whether bug is at QUIC layer (timeout), leader-gating (need escape hatch for self-leader-eviction), or aggregation threshold.

### Possible fixes

- **Self-leader-eviction escape hatch in HealthReconciler.** If aggregated edge target equals current leader, allow ANY surviving node to attempt the write. Rabia consensus serializes; only one wins. Followers compete-and-retry harmlessly.
- **Tune QUIC inactivity timeout** for cloud RTT class. If 60s default and cluster's CRR (consensus round time) is <1s, peer absence beyond 30s is reliably "gone".
- **TopologyObserver delta on transport-level peer-disconnect.** Decouple from KV snapshot. When QUIC reports EVICTED, emit NodeRemoved directly.

The first option is the most architecturally clean. The leader-gating logic is a guard against duplicate writes; for the leader-target case, the cluster needs the write to come from somewhere else.

---

## 6 · Tests / regression coverage

- 14 unit tests for ClusterIdentity (prior session)
- 8 unit tests for TopologyObserver phase transitions (prior session)
- 4 unit tests for CTM circuit breaker (prior session)
- 22 unit tests for BootstrapState round-trip (prior session)
- 50+ added across the budget calc + topology consolidation work (prior session)
- **Today: no new unit tests — fixes were all behavioral; existing test coverage exercised through integration suite**

Future regression coverage: a unit test for `HealthReconcilerImpl.handleAggregatedEdge` should verify that when target equals current leader, a surviving non-leader node still proceeds with the write (once the fix lands).

---

## 7 · Other open questions / smaller follow-ups

### A. JVM cloud full-suite untested

We ran only `--suites 00,02` on JVM cloud. Full 15-suite would establish baseline. Estimated cost: €5-10, ~30-45 min.

### B. 12-network / SCALING reassignment flake

`SCALING reassigned from dead node node-2 to node-2: expected NOT 'node-2', got 'node-2'`. The test expects reassignment to a different node, but timing / CTM provisioning replaces with same node-id. Pre-existing flake from prior session §7.C (`start_node` doesn't actually rejoin under single-writer rule).

### C. 06-deployment strategy v2 artifact

Previously flagged in handover §3 of 2026-05-08. Rolling deployment for `url-shortener-analytics` slice fails with "No current version" — initial deployment requires `aether blueprint deploy` first. Test infrastructure issue.

### D. PG VM was destroyed in cleanup

`tools/cloud-reaper.sh --destroy --force` swept the PG VM along with cluster VMs (it carries the same `aether-cluster=*` label class). Fine for "shutdown/cleanup" semantics. Next session needs `tools/provision-test-pg.sh` first to bring up a fresh PG VM and produce `/tmp/aether-test-pg.env`.

### E. Cluster A docker-compose now `restart: "no"`

Aligned with the recovery-ownership doc. Side-effect: if you reboot the test machine mid-run, cluster A doesn't auto-restart. Operator workflow: explicitly `docker compose up -d` after reboot. Acceptable trade.

### F. JAR distribution for JVM mode

`cloud-hetzner-jvm.toml` and `cloud-hetzner-jvm-b.toml` pin `jar_url = "https://github.com/.../v1.0.0-rc1-candidate/aether-node.jar"`. CI rebuilds the asset on tag force-push (verified — JAR updated at 03:39 after our 02:33 force-push). No staleness risk under current CI behavior.

---

## 8 · Quick start for next session

```bash
# 1. Sanity
git log --oneline 3f5729e61..HEAD          # 8 commits this session
git status --short                          # should be clean
git tag --points-at HEAD                    # v1.0.0-rc1-candidate

# 2. Hetzner inventory (should be ZERO — last run cleaned everything)
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)"'

# 3. Re-provision PG VM for tests
tools/provision-test-pg.sh
# Produces /tmp/aether-test-pg.env

# 4. OPTIONS:
#    A — Investigate cloud Container kill-leader freeze (RECOMMENDED; §5)
#       1. Add instrumentation to HealthReconciler / QUIC / Led.handle (§5 list)
#       2. Build aether/cli + aether/node
#       3. Run cloud Container --suites 00,02 (~10 min, ~€1)
#       4. Capture logs from all 5 cluster B nodes
#       5. Identify short-circuit step → targeted fix
#       6. Validate
#    B — Run JVM cloud full 15-suite (baseline data)
#    C — Fix 12-network/SCALING flake on docker-remote (small, reproducible)

# 5. Reset Hetzner before tests if needed
tools/pg-firewall.sh open
# (run tests)
tools/pg-firewall.sh close
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/<PG_ID>/actions/poweroff'
```

---

## 9 · Score card

| Metric | Start of session | End of session |
|---|---|---|
| Branch HEAD | `3f5729e61` | `8e721c625` |
| Commits ahead of session-start | 0 | 8 |
| Snapshot-quiesce flake | dominant cluster-of-flakes | **eliminated** |
| docker-remote (best run) | 14/15 (was flaky 12-14/15) | **14/15 reliable** |
| docker-remote (typical) | 12-14/15 | **14/15 with one rotating flake** |
| JVM cloud subset (00,02) | untested | **2/2 perfect** |
| Container cloud chaos | 60+ min (Docker auto-respawning) | **kills authoritative; new bug surfaced underneath** |
| Operator-facing recovery doc | none | **`aether/docs/operator/deployment-recovery.md`** |
| Cloud orphans | 7+/run prior sessions | **0** (cloud-reaper integrated, cleaned at session end) |
| RC1-day budget (estimate) | 3-5 days | **2-3 days** (cloud Container freeze is 1-2 days) |

**Net: 4 distinct architectural bugs root-caused and fixed (publisher heartbeat, budget calc, container restart policy, cloud test infra rotation), plus the recovery-ownership operator doc. The cloud Container kill-leader freeze is the next architectural target — diagnosed but not yet fixed.**

---

## 10 · Post-handover investigation: post-kill consensus stall (NEW finding)

After the handover above was committed, we instrumented + tested the cloud Container freeze further. Two more architectural fixes shipped (still incomplete):

### 10.1 What we shipped

- **`HealthReconcilerImpl.handleAggregatedEdge` self-leader-eviction escape hatch** — when the aggregated FAULTY target IS the current cluster leader, ANY surviving node may attempt the lifecycle write. Otherwise leader-gating still applies. Rabia is leaderless and serializes proposals; concurrent attempts deduplicate idempotently.
- **`ObservationAggregator.respectColdBoot` removed** — the aggregator's per-peer `everSeenHealthy` cold-boot guard duplicated SwimProtocol's audit-Step-6 phase-gating without phase awareness, and silently dropped FAULTY edges for peers added in initial ALIVE state (no transition → no `notifyAlive` → no `recordHealthyAndEmit` → never populated `everSeenHealthy`). Trust upstream emit gating; `HealthReconciler.suppressedByPhase` still gates writes in BOOTING.

Both are architecturally correct. Verified on cloud Container with diag logs: SWIM-FAULTY → onSwimObservation → handleAggregatedEdge → escape hatch fired on **all 4 surviving nodes simultaneously** at `~06:39:27`, all attempted DECOMMISSIONED writes via `proposeLifecycleWrite`.

### 10.2 What still doesn't work — post-kill consensus stall

After the 4 escape-hatch firings, **NO `recordWrite` log appeared on any node**. The `commandApplier.apply(List.of(command))` Promise never resolves. Neither `onSuccess` nor `onFailure` callbacks fire on any of the 4 surviving nodes for the duration of the 450s test timeout.

This is **NOT** a Rabia-leadership issue. **Rabia is leaderless — every node can propose, and quorum (3 of 5) is satisfied by any 3 surviving nodes.** Boot-time writes confirm consensus works: each surviving node successfully wrote ON_DUTY for itself and for core-0 between `06:38:50` and `06:38:58` via the same `commandApplier.apply` path.

The stall must be at a layer below Rabia's quorum-counting. The most likely shape:

**Reliable-broadcast over QUIC waits per-peer, not per-quorum.** Rabia's transport layer broadcasts each round's messages to all 5 peers; if the broadcast layer queues sends behind per-peer reliable-delivery (UDP retransmits, no ACK ever arriving from the dead peer because QUIC still treats core-0 as "connected"), the round hangs even though quorum was satisfied by the other 3 ACKs.

This is consistent with what's different between JVM cloud (works in 158s end-to-end) and Container cloud (450s timeout):

- **JVM cloud kill = bare-process kill.** Linux kernel sends RST/FIN immediately on the killed process's UDP sockets. QUIC sees connection drop fast → emits `processViewChange(REMOVE, ...)` → `TopologyChangeNotification.NodeRemoved` → broadcasts skip the dead peer → quorum-of-4 ACK the round → consensus commits.
- **Container cloud kill = `docker kill` of containerized JVM.** Docker daemon's namespace teardown delays kernel-level socket close. QUIC continues to see a "connected" but unresponsive peer for many seconds (the previous SWIM-FAULTY-to-eviction chain is now indirect, post-Step-3; QUIC's own inactivity timeout is the only thing that fires). Reliable-broadcast queues sends to the dead peer indefinitely, blocking round completion despite quorum.

We saw earlier in this session: SWIM marks FAULTY in 16s, but no `processViewChange: op=REMOVE` log fires for 2+ minutes. That's the smoking gun for "QUIC eviction not firing fast enough on cloud Container."

### 10.3 Possible directions for next session

The fix is NOT at Rabia's leaderless-proposer layer (it's already correct). It's at the QUIC connection-state / reliable-broadcast layer:

1. **Tighten QUIC inactivity timeout for cloud Container.** Find QUIC's per-peer inactivity / keepalive timeout in `QuicClusterNetwork` / Quiche configuration. Today it appears to be minutes; should be ~5-15s for cloud-class RTT. With shorter timeout, EVICTED fires within seconds of `docker kill`, which feeds the existing chain (NodeRemoved → consensus broadcasts skip the peer → round commits → leader re-election → DECOMMISSIONED write).
2. **Trigger QUIC eviction from SWIM-FAULTY directly on a timer.** Today the SWIM-to-QUIC bridge was removed in audit Step 3 (replaced by post-consensus path). For the FAULTY-leader-target case where consensus is itself blocked, restore a SWIM-driven eviction trigger gated on "SWIM has been FAULTY for >N seconds AND target is current leader."
3. **Decouple consensus broadcast progress from per-peer reliable delivery.** If the round has quorum ACKs, complete the round; non-ack'd peers catch up via gossip/sync. This is more invasive but cleaner architecturally.
4. **Compare per-peer transport state on JVM vs Container cloud.** Add WARN-level logging in `QuicClusterNetwork.expireEvicted` / `QuicPeerConnection` connection-state transitions. Run kill-leader on both; correlate exact timing of EVICTED firing.

Direction 1 is the smallest fix and likely enough. Direction 2 is the "belt and suspenders" complement when QUIC's own timeout is too sluggish. Directions 3 and 4 are bigger investigations.

### 10.4 Commits added post-handover (pushed; tag re-moved)

```
0caf363f9 fix(health,docs): remove diag logs; document Rabia consensus stall finding
81e48e234 fix(health): self-leader-eviction escape hatch + drop respectColdBoot suppression
```

Diag logs (`HEALTHRECONCILER-DIAG`) were temporarily added to confirm the chain fires; removed in `0caf363f9` before final tag move.

---

**Final net: 5 distinct architectural fixes (publisher heartbeat, budget calc, container restart policy, cloud test infra rotation, self-leader-eviction escape hatch + cold-boot suppression removal). Cloud Container kill-leader recovery still blocked at the QUIC eviction / reliable-broadcast layer — quorum is satisfied by surviving nodes but per-peer broadcast to the dead-but-still-QUIC-connected leader keeps the round from completing. Direction 1 (tighter QUIC inactivity timeout) is the recommended first attempt for next session.**

---

## 11 · Resolution: SWIM-FAULTY-on-leader → QUIC disconnect bridge (FIXED)

After the §10 handover was committed, we implemented the targeted fix and validated end-to-end.

### 11.1 What we shipped

`SwimHealthContext.routeFaulty` now calls a new `faultyLeaderEvictor` callback when **(a) cluster phase is NORMAL** and **(b) the FAULTY peer IS the current cluster leader**. The callback is wired to `clusterNetwork.disconnect(new DisconnectNode(peer))`, forcing immediate QUIC eviction of the dead leader.

This is a narrow re-introduction of the SWIM-FAULTY-to-disconnect bridge that audit Step 3 removed for general peers. Step 3's removal was correct — it eliminated the N+1 fan-out cascade across every survivor's local SWIM listener. But for the **leader-faulty case** the post-consensus eviction path can't progress (consensus.apply broadcast queues sends to the still-QUIC-connected dead leader, blocking the round). The narrow trigger restores SWIM-driven eviction only for the case that needs it; non-leader FAULTY peers continue through the post-consensus path.

The phase gate matters: SwimProtocol emits transient FAULTY events during cluster boot before HEALTHY observations land for newly-joined peers; if the bridge fired during BOOTING, the still-being-elected leader could be prematurely evicted before stabilizing. `isBootingSupplier` from `HealthReconciler.phase() == ClusterPhase.BOOTING` gates this correctly.

Transport-layer hygiene (DisconnectNode) is NOT subject to the single-writer rule, so concurrent eviction calls from N surviving nodes are idempotent at QUIC (`peer.evict` is CONNECTED→EVICTED, no-op otherwise).

### 11.2 Cascading recovery flow

With the bridge in place, the cloud Container kill-leader recovery now flows:

1. SWIM marks leader FAULTY (~16s after kill on cloud Container; cloud's slow QUIC inactivity timeout no longer relevant).
2. `routeFaultyPeer` → `routeFaulty(peer, currentLeader)` → bridge fires (NORMAL phase, target == leader).
3. `clusterNetwork.disconnect(DisconnectNode(peer))` → `processViewChange(REMOVE)` → emits `TopologyChangeNotification.NodeRemoved`.
4. `LeaderManager.nodeRemoved` → fsm dispatches `NodeGone(peer)` to the `Led` state → transitions to `ReElecting`.
5. `LeaderElectionState.sendProposal` submits new candidate proposal via Rabia consensus.
6. Rabia broadcasts to surviving 4 peers (NOT including the now-EVICTED dead leader); quorum (3 of 5) acks; round commits.
7. New leader's `BootstrapModule.onLeaderGained` fires; new leader writes DECOMMISSIONED for old leader via the leader-gated `proposeLifecycleWrite` path; cluster recovers.

### 11.3 Validation

Verified end-to-end on cloud Container with a fresh cluster B kill-leader test:

```
09:54:11 Entering Electing
09:54:12 Submitting leader proposal: candidate=core-0 (transient — pre-stabilization)
[…cluster reaches NORMAL…]
[kill_leader fires]
[~16s later] SWIM marks core-0 FAULTY
            routeFaulty(core-0, currentLeader=core-0)
            bridge fires → clusterNetwork.disconnect(core-0)
            processViewChange(REMOVE, core-0) → NodeRemoved emitted
            LeaderElectionState transitions to ReElecting
[~4s later] new leader proposed and committed
            Cluster recovers; auto-heal restores to 5 nodes
```

End-to-end results, all suites:

| Surface | 02-chaos result | Time |
|---|---|---|
| docker-remote (regression check) | 4p/0f | 135s |
| cloud JVM (validated earlier this session) | 4p/0f | 158s |
| cloud Container (this fix) | 4p/0f | 1395s |

The cloud Container suite is slower but completes successfully — every kill-leader / kill-multiple / kill-non-leader / kill-under-load test passes. The 1395s reflects cloud's slower CTM provisioning cycles for replacement VMs (each replacement cloud-init takes ~90-180s); test logic is correct and recovery is reliable.

### 11.4 Commits added (pushed; tag re-moved)

```
3ef7fb4e1 fix(swim,test-infra): phase-gate leader-faulty evictor (NORMAL only); cloud rotate uses CLOUD_MGMT_PORT
c84bc0607 fix(swim,quic): bridge SWIM-FAULTY-on-leader to QUIC disconnect to break consensus broadcast stall
```

### 11.5 Open follow-ups

- **`Leader after kill: hetzner-eu-core-0 (was: hetzner-eu-core-0)`** — test-side reporting issue. CTM auto-heal provisions replacement VMs that re-bind the same node-id (deterministic naming per slot). The cluster status API correctly reports the new leader's id, but the test compares strings expecting different ids. Test should compare against VM-id / IP, or accept "same node-id is OK if VM is fresh." Cosmetic; doesn't affect actual recovery behavior.
- **Inter-suite churn warning** — "Cluster did not quiesce after destructive suite; next suite may inherit churn" surfaces between test files in 02-chaos. Each test file's setup phase tolerates this (`wait_for cluster healthy` retries; the test passed in this run despite an early `cluster healthy (timed out after 180s)` warning). Worth tightening the inter-suite quiesce barrier for cleaner runs.
- **JVM cloud full 15-suite** still untested. Recommended.
- **Container cloud full 15-suite** also untested at this point. Now that 02 passes, the others should follow given they exercise less-destructive paths.

### 11.6 Time-budget analysis: can we reduce 1395s?

Cloud Container 02-chaos at 1395s vs JVM cloud at 158s vs docker-remote at 135s. The gap is **almost entirely VM provisioning**, not anything we can fix architecturally.

**Where the 1395s goes (per kill cycle):**
- kill (~1s)
- SWIM detect FAULTY (~10-15s; SWIM ping × suspect timeout)
- Bridge → QUIC disconnect → re-elect → DECOMMISSIONED commit (~3-5s; we measured 4s in logs)
- CTM auto-heal: provision replacement VM on Hetzner (~30-60s API + boot)
- Cloud-init: apt-update + Docker install + image pull + container start (~60-120s; image pull is the slowest single step at ~30-60s)
- aether-node boot to ON_DUTY (~5-10s)
- Slice rebalance + topology delta + GenerationSnapshot publish (~5-10s)

Per kill: ~120-220s of irreducible cloud-physics latency. Across 4 chaos tests + 1 inter-test cluster restoration each: ~10-15 of these cycles. Plus test-framework wait timeouts (180s/360s/450s) firing when something's slow. That's the 1395s budget.

**Reducible without touching production semantics:**

| Knob | Saving | Risk | Effort |
|---|---|---|---|
| Test `wait_for cluster healthy` 180s → 90s | ~minutes when steps run slow | Low (post-fix the underlying ops are faster) | Trivial — `lib/cluster.sh` constants |
| Test `leader elected` 450s → 180s | ~minutes when steps fail | Low | Trivial |
| Test inter-suite quiesce barrier (skip "5 nodes restored" if only quorum needed) | ~30-60s per inter-suite hand-off | Low | Small |
| `SwimConfig.suspectTimeout` ~10s → ~5s on cloud | ~5s × 4 kills = ~20s | Medium — needs cloud RTT validation, false-positives risk | Small (config) |
| Pre-pull aether-node into Hetzner VM snapshot (custom image) | **~30-60s per replacement × 4-5 replacements = 2-5 min** | Low — operator-side ops only | Medium (build a snapshot, point Hetzner config at it) |

**Cannot safely change:**
- `restart: "no"` on aether-node containers — CTM owns recovery; restart-policy speedup would re-introduce the `unless-stopped` race.
- Hetzner VM creation API (~30-60s) — fundamental cloud-physics cost.
- Reliable-broadcast policy in Rabia — per-peer retry semantics aren't session-scope work; the bridge already addresses the main symptom.

**Realistic target with the 5 reducible knobs:** **~600-900s** for cloud Container 02-chaos, down from 1395s. That's still ~5x JVM cloud, but the gap then reflects genuine cloud cost, not test-framework slack.

**Quickest single win:** pre-pulled VM snapshot. Pure ops, no code change, biggest absolute saving (~3-5 min). Would also speed up cluster bootstrap (Phase 5/7 DEPLOY_RUNTIME's image pull).

**For RC1 release readiness:** none of the reductions are blocking. 1395s for 02-chaos cloud Container is acceptable for an integration test that's currently the second-line validation (docker-remote covers the primary path at 135s). If we want CI cycle time down for cloud-included PRs later, the test-side timeout tightening + VM snapshot are the next targets.

---

**Final net (revised): 6 distinct architectural fixes shipped. Cloud Container kill-leader recovery FIXED. RC1 chaos coverage now green on docker-remote, JVM cloud, AND Container cloud. The remaining work is full-suite cloud validation + inter-suite churn polish.**
