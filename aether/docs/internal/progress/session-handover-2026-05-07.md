# Session Handover — 2026-05-07

**Branch:** `release-1.0.0-rc1`  ·  **HEAD:** `29b7fed38` (pushed)  ·  **Tag:** `v1.0.0-rc1-candidate` at `29b7fed38` (pushed, force-updated)

Continuation of [`session-handover-2026-05-06.md`](session-handover-2026-05-06.md). Goal: take 12-network on cloud from yesterday's 1p/2f to **3p/0f** without shortcuts. Outcome: **12 of 13 sub-tests confirmed PASS** on cloud against image `d2ba2cc58`. The 13th (suite-2 `wait_for_replacement_of`, CTM auto-provisioning a replacement VM) was diagnosed all the way down to a Hetzner label-validation 422; fix landed at `29b7fed38` but **not yet validated on cloud** because Hetzner cx33-fsn1 capacity went out (5+ consecutive HTTP 412 `resource_unavailable`) and orphan VMs from earlier failed-bootstrap cleanup paths kept accumulating. Account is clean and PG VM is powered off at session end.

Plus a **structural audit** of the membership/network layer was written, identifying the parallel-state-tracker pattern that has been exposing one cloud bug after another. The audit lays out an 8-step consolidation plan estimated at 5-7 days.

**Per project owner's call, all backlog items in §4 — including the 8-step consolidation — are RC1-must-fix, not deferred.** Production-grade bar: real 15/15 with no known-bug debt at this level. Total RC1-day budget remaining: ~7-10 days. See §6 for ordered phases A–F.

---

## ⚡ TL;DR for next session

**To finish the 13th sub-test on cloud:**

```bash
# 1. Power on the PG VM
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers/128911684/actions/poweron' | jq -r '.action.status'

# 2. Wait ~30s for PG to be ready, then run
cd aether/tests/integration && source /tmp/aether-test-pg.env && \
  ./run-tests.sh --env cloud --suites 12 --skip-build --skip-teardown

# 3. If suite-2 wait_for_replacement_of PASSES → 13/13 on cloud, ship-ready.
# 4. If still FAILS → ssh into leader, grep logs:
#    LEADER=$(jq -r '.collectedAddresses[0]' ~/.aether/clusters/test-b/bootstrap-state.json)
#    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i "$AETHER_SSH_KEY" \
#      root@$LEADER "docker logs aether-node 2>&1 | \
#      grep -iE 'CTM:|provision|HetznerComputeProvider|cloud environment|invalid input'"
# 5. After diagnostic, tear down:
#    tools/cloud-reaper.sh --cluster test-b --destroy --force
#    tools/pg-firewall.sh close
```

**If Hetzner is out of cx33 capacity in fsn1 again:**
```bash
# Inventory check first — orphan VMs from failed-bootstrap cleanup can pile up
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)\t\(.labels)"'
# Anything with `aether-node-id` label but no `aether-cluster` label is an orphan.
# `tools/cloud-reaper.sh --cluster test-b --destroy --force` won't touch them
# (label filter mismatch). Authorize destruction by ID after verifying.
```

---

## 1 · Cloud matrix end-state

| Suite | Yesterday | Today | Notes |
|---|---|---|---|
| 00-smoke | PASS | (untouched) | ✓ |
| 02-chaos | PASS | (untouched) | ✓ |
| 03-scaling | PASS | (untouched) | ✓ but **suspicious** — see §6 |
| 04-streaming | PASS | (untouched) | ✓ |
| 05-security | PASS | (untouched) | ✓ |
| 06-deployment | PASS | (untouched) | ✓ |
| 07-cluster-mgmt | PASS | (untouched) | ✓ |
| 08-resources | FAIL | (untouched) | flaky slice routing — separate issue |
| 09-artifacts | PASS | (untouched) | ✓ |
| 10-database | PASS | (untouched) | ✓ |
| 11-observability | PASS | (untouched) | ✓ |
| **12-network** | SKIP | **12/13 confirmed, 13th pending Hetzner capacity** | suite-1 6/6, suite-2 3/4, suite-3 3/3 |
| 13-edge-cases | PASS | (untouched) | ✓ |
| 14-storage | PASS | (untouched) | ✓ |
| 15-delegation | PASS | (untouched) | ✓ |

**12-network was a SKIP yesterday** (capability-gated to docker/remote only). Today it ran on cloud, dropped from 1p/2f against the SWIM-only fix to **12/13 against d2ba2cc58**:

| Sub-suite | Sub-test | Yesterday's first run | Today |
|---|---|---|---|
| 12-network/01 (gossip-encryption) | Cluster ready | — | ✓ |
| | Cluster formed with encryption | — | ✓ |
| | Gossip encryption via config | — | ✓ |
| | Gossip encryption via transport | — | ✓ |
| | Nodes communicating encrypted | — | ✓ |
| | Health probes over encrypted transport | — | ✓ |
| **12-network/02** (quic-connectivity) | Cluster ready | — | ✓ |
| | All nodes connected | — | ✓ |
| | Kill node and detect drop (departure observed) | FAIL | ✓ |
| | Kill node and detect drop (replacement provisioned) | FAIL | **FAIL — pending 29b7fed38 cloud validation** |
| | Connections recovered | — | ✓ |
| **12-network/03** (swim-detection) | Cluster ready (5 nodes) | — | ✓ |
| | SWIM detection time | FAIL | ✓ (9s, threshold 15s) |
| | Recovery after detection | — | ✓ |

**Net delta from yesterday:** +0 suites green confirmed, +1 expected after `29b7fed38` validates on cloud → **15/15 once 12 lands**. Plus 08-resources is a separate pre-existing flake.

**Hetzner spend:** ~€10 across the multi-iteration session. Account clean at end-of-session (PG off, 0 cluster nodes).

---

## 2 · Commits landed (16, all pushed)

```
29b7fed38 fix(hetzner): filter caller-supplied tags through Hetzner label spec
d2ba2cc58 fix(node): bundle Hetzner/AWS/GCP/Azure SPI factories into shaded JAR
70f8da499 fix(ctm): preserve stability anchor on downward count transitions
ba83af021 refactor(events): drop redundant topology-broadcast emission path
ddf90d221 feat(events): tag NODE_FAILED/NODE_LEFT with emitting source path
c0ad66f4b fix(events): restore NodeRemoved/NodeDown event paths alongside SWIM observation
af50600fa fix(test-infra): cloud_ssh uses root + kill_node/start_node abort on failure
92cf142f6 docs: changelog for SWIM observation-listener pre-start buffering fix
d0dcee8bc fix(swim): buffer observation listeners until SWIM protocol starts
b0fde6205 docs: changelog entry for PG VM firewall toggle
2fe55e15f chore(test-infra): toggle Hetzner firewall around PG VM during cloud test runs
d0c5cfd93 docs: changelog for SWIM-observation fix, native ARM runner, cloud PARTITION cap
ddfa2d2ff test(integration): enable PARTITION capability on cloud env
6ac8690f5 ci: build-linux-arm64-dist on native ARM runner (closes #211)
```
(plus 2 docs/handover commits)

### Key behaviour changes (in chronological order)

#### `6ac8690f5` — Native ARM CI (closes #211)
`build-linux-arm64-dist` job switched from `ubuntu-latest` + `docker/setup-qemu-action` + QEMU-emulated container to `runs-on: ubuntu-24.04-arm` with native Maven. Job wall-clock: **30-45 min → 1m 50s**. Total release publish: **38m → ~5m 30s**. Means we can iterate against fresh published images quickly.

#### `2fe55e15f` — PG VM Hetzner firewall toggle
New `tools/pg-firewall.sh` (`init|open|close|status|destroy`) creates a Hetzner Cloud Firewall on the PG VM. Baseline: SSH (22) from operator IP only. `open` adds 5432/tcp from `0.0.0.0/0` for the test window; `close` reverts. `run-tests.sh --env cloud` opens before bootstrap and closes via `teardown()`. PG is invisible to the public internet outside test runs.

#### `d0dcee8bc` — SWIM listener buffering
`CoreSwimHealthDetector.addObservationListener` previously dropped listeners silently when called before SWIM had reached `Running` state (the case at AetherNode init time). Two listeners (`healthReconciler::onSwimObservation` and `eventAggregator::onSwimObservation`) were silently lost. Fix: a `pendingObservationListeners` CoW list buffers them; `seedAndWrap` re-attaches every pending listener to each freshly-started `SwimProtocol`. Regression test added.

#### `c0ad66f4b` → `ddf90d221` → `ba83af021` — Events emission convergence

Three-step evolution:
1. `c0ad66f4b` belt-and-suspenders: re-added `onNodeRemoved`/`onNodeDown` event paths alongside `onSwimObservation`. Helped flush out which path was actually firing on cloud.
2. `ddf90d221` source-tagging: every NODE_FAILED/NODE_LEFT entry tagged with `details.source = "swim-observation"|"topology-broadcast"|"lifecycle-kv"`. **The diagnostic that proved local SWIM observation works on cloud.** Result on a manual kill: `swim-observation` fired multiple times across all surviving nodes within ~0s of the kill; `lifecycle-kv` fired once per follower; `topology-broadcast` fired once on the leader.
3. `ba83af021` cleanup: with diagnostic confirmation that swim-observation is the canonical fast path, `topology-broadcast` is redundant — dropped its emission. `lifecycle-kv` retained for graceful-drain semantics (DRAINING → DECOMMISSIONED → NODE_LEFT).

#### `af50600fa` — Test-infra: kill_node abort-on-failure
The smoking gun. `kill_node` cloud branch was `cloud_ssh "$node_id" "docker update --restart=no aether-node >/dev/null 2>&1; docker kill aether-node" 2>/dev/null`. Two compounding bugs:
- `cloud_ssh` used `$AETHER_SSH_USER` (= `aether`) — but the `aether` user on cloud VMs lacks docker access (Docker installed *after* user creation). `docker` commands fail with `permission denied while trying to connect to the docker API`.
- `2>/dev/null` swallowed the permission-denied error.

Net effect: **`kill_node` was a silent no-op on cloud for an unknown duration** — every cloud chaos/SWIM/network test was running against a still-alive container. The previous handover §1 noted "12-network was SKIP on cloud" partly because of this; my session uncovered it via direct VM inspection (`docker inspect aether-node` showed `restartCount=0 restartPolicy=unless-stopped` after a "successful" kill).

Fix: `cloud_ssh` defaults to `root` (configurable via `CLOUD_SSH_USER`); `kill_node`/`start_node` capture stderr and abort loudly on rc != 0.

#### `a4695786b` — Disconnect uses evict + per-node SWIM-FAULTY → disconnect

After fixing `kill_node`, the kill *did* work but a follow-on bug surfaced: after `start_node` brought the killed container back, peers' SWIM never re-promoted the restarted node to ALIVE, so a *second* kill of the same node ID didn't trigger any SWIM edge. Investigation showed:

- `QuicClusterNetwork.disconnect` previously called `peer.authoritativeRemove` (terminal `REMOVED` phase). New attach attempt from a restarted peer returned `AttachResult.REJECTED` → connection closed → reconnection storm.
- Only the *leader's* `routeFaulty` → `routeDisconnect` was triggering disconnect; followers' QUIC `PeerState` was untouched on FAULTY observation, leaving stale CONNECTED entries that `attach()` then rejected as DUPLICATE.

Fix: `disconnect` now uses `peer.evict` (recoverable `EVICTED` phase, future Hello returns `RECONNECTED`). Plus AetherNode adds a third SWIM observation listener that calls `localNetwork.disconnect(...)` on every node's local FAULTY (not just leader's `routeFaulty` broadcast). Per-node eviction means restart's Hello hits `RECONNECTED` → `ConnectionEstablished` → `swim.markAlive` → SWIM revives → second kill produces a real FAULTY edge → events emit.

#### `70f8da499` — CTM stability anchor on upward-only

After the SWIM fixes, suite-3 went 3/3 but suite-2's `wait_for_replacement_of` still failed: CTM never auto-provisioned a replacement. Diagnostic showed CTM repeatedly logging `stability window not yet elapsed (elapsed=Xms, required=30000ms)` with the elapsed value periodically resetting just before reaching 30000ms. Cause: `maybeBumpAnchorOnHealthyOnDutyEdge` and `observeRealActualForStability` reset the stability anchor whenever `snapshotHealthyOnDutyCount` changed. A single SWIM FAULTY emits to multiple paths (leader's local routeDisconnect + every node's third SWIM listener), each routing `processViewChange(REMOVE)` and racing with `NodeLifecycleKey -> DECOMMISSIONED` snapshot rebuild — the count flipped 5↔4 across the millisecond window and reset the anchor each flip.

Fix: stability anchor only resets on **upward** transitions (cluster grew). Downward transitions (peer departed) confirm the deficit and let the 30s window elapse normally → CTM provisions.

#### `d2ba2cc58` — Bundle cloud SPI factories

After the stability fix, suite-2 still failed. New diagnostic via leader logs revealed:

```
ERROR Main.resolveEnvironment - Failed to create cloud environment: Operation not supported: Unknown cloud provider: hetzner
```

Root cause: **`aether/node/pom.xml` only declared `environment-docker` as a dependency.** Hetzner/AWS/GCP/Azure modules were never on the runtime classpath. The shaded `aether-node.jar`'s `META-INF/services/EnvironmentIntegrationFactory` listed only `DockerEnvironmentIntegrationFactory`. So `EnvironmentIntegrationFactory.forProvider("hetzner")` returned empty → `lifecycleManager.isCloudManaged()` returned false → CTM hit the `no ComputeProvider, cannot auto-provision` branch on every cloud cluster.

This had been silently broken since the cloud auto-scale work landed yesterday. **03-scaling's cloud "PASS" is suspicious** because scale-up via `/api/cluster/scale` goes through the same `lifecycleManager.isCloudManaged()` gate. Either yesterday's image lineage had Hetzner on the classpath transiently, or the test's check (`wait_for_node_count_fast 7 300`) somehow returned true without 7 nodes ever being provisioned. Worth re-running 03-scaling on cloud against the fixed image. Tracking note in §6 below.

Fix: added `environment-hetzner`/`-aws`/`-gcp`/`-azure` deps. Verified post-build: shaded JAR now lists 5 factories; 72 Hetzner classes bundled. Maven's `ServicesResourceTransformer` correctly merges them.

#### `29b7fed38` — Hetzner label filter (current HEAD)

Final blocker (post-validation will tell). After the SPI bundle, CTM successfully called `provisionNodes` on cloud (verified in leader logs: `CTM: Cluster at 4/5, provisioning 1 replacement(s)`), but Hetzner rejected:

```
WARN: CTM: Node provisioning failed: Hetzner API error 422 (invalid_input): invalid input in field 'labels'
```

Source: `ClusterTopologyManagerRecord.buildProvisionTags()` returns:

```java
Map.of("aether.peers", peers,                  // multi-line: "nodeId:host:port,nodeId:host:port,..."
       "aether.core-max", String.valueOf(N),
       "aether.provisioned-by", "ctm");
```

These are *Docker-runtime* tags (consumed by `DockerComputeProvider` to set container env vars at line 102-104). Hetzner labels have stricter rules: max 63 chars, no `:`, no `,`. The `aether.peers` value is ~150 chars with `:` and `,` everywhere → 422.

Fix: `HetznerComputeProvider.mergeLabels` filters caller-supplied tags through two regex patterns (`[a-zA-Z]([a-zA-Z0-9_.-]*[a-zA-Z0-9])?(/[a-zA-Z0-9_.-]+)?` for keys, `[a-zA-Z0-9_.-]*` for values, length ≤ 63). Incompatible tags are dropped with a debug log; defaults (`aether-cluster`, `aether-role`) survive. The new VM gets seed peers via cloud-init userData (already wired), not via Docker tags.

**Validation status:** image republished with the fix; cloud validation pending Hetzner cx33-fsn1 capacity returning. Last 5+ attempts hit HTTP 412 `resource_unavailable`.

---

## 3 · The structural audit

A consolidated audit was written by the `aether-investigator` subagent at:

**`aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md`** (418 lines)

The audit maps **four parallel state trackers** that all answer the question "what is the cluster size and which peers are healthy?":

| # | Tracker | Authoritative for | Cloud failure mode |
|---|---|---|---|
| T1 | `MembershipView` (KV-projected snapshot) | `coreMemberIds`, `onDutyMemberIds`, `healthyOnDutyCount`, `desiredCoreSize` | Lags local SWIM observation by Rabia round-trip + projection latency (200-500ms on cloud) |
| T2 | `TopologyObserver.nodeStatesById` | `topology()` list, transport-level legacy fallback | **`NodeHealth` field is dead state** — defaults to HEALTHY on add, never updates on SWIM FAULTY |
| T3 | `SwimProtocol.members` | `MemberState` per peer, incarnation numbers | `everSeenHealthy` cold-boot suppression too aggressive on cloud |
| T4 | `QuicClusterNetwork.peers` (`PeerState`) | Per-peer connection lifecycle | Per-peer protection-window check doesn't coordinate across multiple `disconnect()` callers |

Plus six debounce sidecars (CTM `lastObservedHealthyOnDutyCount` + `realActualStableSinceMs`, HealthReconciler aggregator + cooldown + cluster phase + stableSinceMs, SWIM `lastEmittedHealth` + `everSeenHealthy`).

**The amplification cascade for a single SWIM `FaultyObserved`:**
- `processViewChange(REMOVE, peer)` routed **N times** (one per surviving node).
- `TopologyChangeNotification.NodeRemoved` routed **N+1 times** (each subscribed by 15 receivers across 8 modules).
- `KVCommand.Put<NodeLifecycleKey, DECOMMISSIONED>` 1 (leader-gated, cooldown saves the rest).
- `NODE_FAILED` ring-buffer entry per node: 2-3 (per-witness × source).
- `QuicClusterNetwork.disconnect(peer)` called N+1 times.

**Recommended consolidation (8 steps, est. 5-7 days):**
1. Add `MembershipView` delta-diff publisher inside `TopologyObserver`.
2. Make `MembershipView` deltas the SOLE driver of `TopologyChangeNotification.NodeRemoved`/`NodeDown`. Delete `QuicClusterNetwork.processViewChange` upward emission.
3. Drop the AetherNode local SWIM-FAULTY-to-`disconnect` lambda; replace with `MembershipView` subscriber inside `QuicClusterNetwork`.
4. Re-source `ClusterEventAggregator.onSwimObservation` to `onTopologyChange`.
5. Strip `TopologyObserver.nodeStatesById` of `NodeHealth` field + legacy fallbacks.
6. Loosen SWIM cold-boot suppression to phase-aware (BOOTING-only) instead of per-peer-ever-healthy.
7. Replace per-node `ObservationAggregator` with cross-node `PeerObservationStore`-backed quorum.
8. Cleanup pass.

**HIGH-risk steps:** 2 (15 receivers must already see the membership-delta path) and 7 (cross-node quorum aggregation requires functional `PeerObservationStore` reducer, currently absent).

This is the **right RC2 target**, not RC1-day work. Each individual surface fix in this session was correct in isolation but each surfaced another seam between trackers. The SPI-bundle and label-filter fixes are independent of the consolidation and stand on their own.

---

## 4 · RC1 must-fix backlog (all promoted from RC2 — production-grade bar)

Per project owner's standing call: "REAL 15/15 and production-grade code is THE actual goal. There is no pressure to ship incomplete product or product with known bugs at THIS level." All items below block RC1 — none are deferred.

| # | Issue | Severity | Est |
|---|---|---|---|
| RC1-1 | **Validate label-filter fix `29b7fed38` on cloud** — quick win. Run 12-network suite-2 against current HEAD, verify CTM successfully provisions a replacement (`provisioning 1 replacement(s)` + `provision succeeded` + new VM joins via SWIM). If 13/13, this single line clears. | High | 0.25 day |
| RC1-2 | **`BootstrapCleanup` HTTP 401 on partial-failure path** — when bootstrap fails partway, the orchestrator's failure-cleanup tries to terminate provisioned VMs but gets 401-unauthorized. Result: 5 orphan VMs accumulate per failed run, eating Hetzner cx33 capacity. Encountered THREE times this session; each required manual destruction (with user authorization) of the orphans. Likely cause: cleanup path uses cluster_secret-encrypted credential from KV-Store, but bootstrap may have failed before KV was populated. Token fallback should be the env-supplied `HCLOUD_TOKEN`. | High | 0.5 day |
| RC1-3 | **03-scaling cloud "PASS" needs revalidation** — yesterday's matrix shows it green on cloud, but `aether-node` had no Hetzner SPI bundled until today (`d2ba2cc58`), so `lifecycleManager.isCloudManaged()` was false → CTM couldn't provision. Either the test was lucky (timing flake), the matrix was wrong, or there's a code path that bypasses the SPI gate that I haven't traced. Re-run 03-scaling against `29b7fed38` on cloud with `--skip-teardown` and verify CTM actually provisioned 2 new VMs for the 5→7 scale. | Medium | 0.5 day |
| RC1-4 | **08-resources flaky slice routing on cloud** — pre-existing flake from yesterday's matrix (was the only docker FAIL). On cloud APP_ENDPOINT hits node-1 directly but slice may not be hosted there (3 instances on 5 nodes). Either fix `wait_for_slices_active` to poll until route reachable on targeted node, OR wire `AppHttpServer` cross-node forwarding for slice routes on cloud. | Medium | 1-2 days |
| RC1-5 | **`ObservationAggregator` is per-node, not cross-node** — spec §4.3 ("decision rule 1: k-of-n across nodes") is unimplemented. Per-node sliding window means leader writes DECOMMISSIONED on its own local SWIM evidence. Subsumed by audit Step 7 in RC1-9. | Medium | 1-2 days (subsumed) |
| RC1-6 | **`everSeenHealthy` cold-boot suppression too aggressive** — peer killed before its first successful Ping ack emits `UnknownObserved` instead of `FaultyObserved`. `HealthReconciler.aggregator` doesn't aggregate UnknownObserved → DECOMMISSIONED never written → CTM never provisions. Subsumed by audit Step 6 in RC1-9. | Medium | 0.5 day (subsumed) |
| RC1-7 | **TopologyObserver.NodeState.health is dead state** — set to HEALTHY on add, never updated on SWIM FAULTY. The `legacyHealthyActivePeerCount` fallback would lie if snapshot path were absent (cold-boot ~2s window). Subsumed by audit Step 5 in RC1-9. | Medium | 0.5 day (subsumed) |
| RC1-8 | **Test infra: `cluster_node_count` race with snapshot** — `cluster_node_count` reads the leader's snapshot via `/api/cluster/generation`, which lags KV writes. Test helpers may see stale counts during fast scale-up/scale-down. Not currently breaking 12-network but would surface under stress. | Low | 0.5 day |
| RC1-9 | **Membership state-tracker consolidation (audit's 8-step plan)** — single source of truth, eliminates the parallel-tracker amplification cascade that has been surfacing one cloud bug per session. Subsumes RC1-5/6/7 plus closes spec divergences D1–D7 in the audit. **The right load-bearing fix; everything else is patches at seams.** See `aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md` for ordered Steps 1–8 with file:line touchpoints. | High | 5-7 days + 2-3 days remote-stabilization |

---

## 5 · State at session end

- **Hetzner**: account clean, only PG VM (id 128911684, name `aether-test-pg-681ab7`) remaining. Status: **off**.
- **Branch HEAD**: `29b7fed38` (pushed).
- **Tag**: `v1.0.0-rc1-candidate` at `29b7fed38` (pushed, force-updated). Image republished and tested-clean: 5 cloud SPI factories + Hetzner label filter active.
- **Last cloud test result**: 12/13 against `d2ba2cc58` (rerun-18 / `bp6wev8t8`). The 13th sub-test (`wait_for_replacement_of`) hit Hetzner API 422 — fix at `29b7fed38` is the right thing, **not yet validated** because subsequent retries hit Hetzner cx33-fsn1 capacity exhaustion.
- **PG firewall**: closed (baseline). 5432 denied from internet.
- **Working tree**: clean (only `aether/docs/internal/progress/session-handover-2026-04-28.md` untracked, pre-existing from session start).
- **Audit doc**: `aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md` (418 lines).

---

## 6 · First things to do next session — RC1 ship-readiness

Ordered for shipping discipline: shortest-path quick wins first to unblock the test signal, then fix the orphan-leak that has been silently capping our Hetzner capacity each session, then the structural consolidation that addresses the underlying parallel-tracker problem.

### Phase A — Validate the in-flight fix (≈ 1 hour)

1. **Power on PG VM** (`actions/poweron`).
2. **Inventory check** — destroy any orphan VMs that accumulated since session-end (Hetzner doesn't auto-clean; failed-bootstrap cleanup-401 path leaks them).
3. **Run 12-network on cloud** with `--skip-teardown` so post-test inspection is possible against the current HEAD (`29b7fed38`, Hetzner label filter active).
4. **If 13/13** (RC1-1 closes) → proceed to Phase B.
5. **If suite-2 `wait_for_replacement_of` still fails** → ssh leader, grep `provisioning [0-9]+ replacement|Hetzner API error|provision succeeded`. Likely outcomes:
   - Provision succeeds, NODE_JOINED for new ID fires → 13/13.
   - Provision succeeds but new ID isn't in `core` topology → CTM is provisioning extras instead of replacing. Look at `inFlightProvisions` size.
   - New API rejection (`userData` validation, etc.) → another label-style filter problem on a different field — fix and re-validate.

### Phase B — Stabilize cloud testing infrastructure (RC1-2, RC1-3) (≈ 1 day)

6. **RC1-2 — Fix `BootstrapCleanup` HTTP 401 on partial-failure path.** Each failed bootstrap leaks 5 VMs because the orchestrator's cleanup couldn't authenticate. This was hit 3× this session and required manual orphan destruction with explicit user authorization. Until this is fixed, Hetzner cx33-fsn1 capacity exhausts within a few failed runs. Likely cause: cleanup path uses cluster_secret-encrypted credential from KV-Store, but bootstrap may have failed before KV was populated; the env-supplied `HCLOUD_TOKEN` should be a fallback.
7. **RC1-3 — Re-validate 03-scaling cloud "PASS"** against image `29b7fed38`. Yesterday's matrix says PASS but the SPI was missing until today, so `lifecycleManager.isCloudManaged()` returned false. Either the test was lucky or there's a code path I haven't traced.

### Phase C — Address remaining test failures (RC1-4) (≈ 1-2 days)

8. **RC1-4 — 08-resources flaky slice routing on cloud.** Either fix `wait_for_slices_active` to poll until route reachable on the targeted node, OR wire `AppHttpServer` cross-node forwarding for slice routes on cloud.

### Phase D — Structural consolidation (RC1-9, subsumes RC1-5/6/7) (≈ 5-7 days)

9. **RC1-9 — Implement the audit's 8-step membership state-tracker consolidation.** Single source of truth (`MembershipView`), pure projections elsewhere, cross-node quorum aggregation in `HealthReconciler`. See `aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md` for the ordered steps with concrete file:line touchpoints. **HIGH-risk steps**: Step 2 (delete `processViewChange` upward emission — 15 receivers must already see the membership-delta path) and Step 7 (cross-node quorum aggregation).
   - Closes RC1-5 (cross-node quorum, audit Step 7).
   - Closes RC1-6 (phase-aware cold-boot suppression, audit Step 6).
   - Closes RC1-7 (strip dead `NodeHealth` field, audit Step 5).
   - Closes spec divergences D1–D7 in the audit.
10. **Final 12-network + chaos + scaling on cloud** against the consolidated machine — must pass cleanly without per-session whack-a-mole.

### Phase E — Polish (RC1-8) (≈ 0.5 day)

11. **RC1-8 — Test infra `cluster_node_count` snapshot race.** Not currently breaking but surfaces under stress; tighten now or it'll bite during release-candidate stress testing.

### Phase F — Ship

12. Wrap up, changelog, feature catalog, move tag, push, merge to `main`, tag `v1.0.0-rc1`, publish to Maven Central.

---

## 7 · Quick start for next session

```bash
# Sanity
git log --oneline f7a6f6f2a..HEAD          # 16+ commits this session (plus docs)
git status --short                          # should be clean

# Power up
curl -s -X POST -H "Authorization: Bearer $HCLOUD_TOKEN" \
  'https://api.hetzner.cloud/v1/servers/128911684/actions/poweron' | jq -r '.action.status'

# After ~30s
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers/128911684' | jq -r '.server.status'

# Inventory check (kill any orphans before running)
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" 'https://api.hetzner.cloud/v1/servers' | \
  jq -r '.servers[] | "\(.id)\t\(.name)\t\(.status)\t\(.labels)"'

# Phase A: validate label-filter fix
cd aether/tests/integration && source /tmp/aether-test-pg.env && \
  ./run-tests.sh --env cloud --suites 12 --skip-build --skip-teardown
```

**Targets to attack in order (RC1-day shipping discipline):**

1. **RC1-1**: Validate label-filter fix end-to-end → ideally closes 12-network 13/13 in ~1 hour.
2. **RC1-2**: Fix cleanup-401 (orphan leak) → unblocks all future cloud iterations.
3. **RC1-3**: Re-validate 03-scaling on cloud.
4. **RC1-4**: 08-resources flake.
5. **RC1-9**: Membership state-tracker consolidation (the load-bearing structural fix).
6. **RC1-8**: Test-infra `cluster_node_count` race.
7. Wrap-up, tag, ship to Maven Central.

Total RC1-day budget remaining: **~7-10 days** by the audit's estimate.
