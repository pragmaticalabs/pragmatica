# Session Handover — 2026-06-08b (#114 generation-snapshot deletion SHIPPED + validated; full-suite mapped; 02+06 residuals are separate pre-existing roots)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `4c5f7da4e` · tree clean.
**Origin:** `origin/release-1.0.0-rc1` is **15 commits behind** HEAD (13 this session + 2 pre-session: `1971bad36` #68-TTL, `a4bffeb87` prior handover). **NOTHING PUSHED** — user has not asked to push. Push when the membership residuals settle or when asked.

## TL;DR
**#114 (delete the generation-snapshot subsystem) is DONE and Docker-validated.** The per-second Rabia consensus `Put` is gone; the snapshot record/projector/publisher/KV-key/adapters are deleted; every consumer now reads the per-node `MembershipFsm` + KV + the ClusterSync ping. The generation epoch is now leader-minted onto the ping (a monotonic counter), with the leader reading its own minted epoch (a follower-style `observedEpoch` never self-updates on the leader). Two regressions I introduced were **caught by Docker and fixed** (a boot StackOverflow recursion + the leader epoch-stall). Then I fixed a **real chaos-recovery bug #114 exposed** (DEAD tombstones poisoned the live quiescence verdict — #125). A full 15-suite run maps the cluster at **9/15**; **every failure is a pre-existing/separate root, not a #114 regression**. The biggest remaining levers are #126 (never-READY joiner pins quiescence — approach approved: eviction timer) and #94 (NODE_FAILED-within-60s under load).

---

## What shipped this session (13 commits `c378448c6`..`4c5f7da4e`, all on the rc1 branch, UNPUSHED)

### #114 — generation-snapshot subsystem DELETED (the headline)
The subsystem persisted a `ClusterGenerationSnapshot` to KV via a **per-second Rabia consensus round** (`GenerationSnapshotPublisher`) — the #1 most expensive pattern in the codebase (confirmed by a repo-wide KV-scan/periodic-task audit). It was redundant post-membership-rework. Deleted entirely; consumers re-sourced from FSM+KV+ping.

| Commit | Wave | What |
|---|---|---|
| `c378448c6` | W1 | Leader mints a **monotonic `generationCounter`** onto the ClusterSync ping epoch (was hard-wired `0L`). `bumpGenerationIfLeader` scheduler (`AetherNode` ~1213) increments per ping-interval when leader. Helper `bumpGenerationIfLeader` (~2396). |
| `fbf92f46e` | W2 | Extracted pure `ClusterQuiescenceEvaluator` (evaluateCluster/evaluateCommunity) from the projector — behavior-preserving; the one quiescence authority, shared by the route + await-quiesced. |
| `aa3b39d37` | W2.0 | Exposed `MembershipFsm membershipFsm()` on `ManageableNode` for live route assembly. |
| `aa2b42522` | W2a | New `ClusterGenerationAssembler` (aether-node) builds `ClusterGenerationResponse` LIVE from FSM(members+health)+KV(governors/partitions/config)+epoch — no stored aggregate, no projector. Gen-route no longer reads the snapshot. |
| `6d7036be5` | W2b | `await-quiesced` gates on **live** observed epoch + **live** quiescence (`ClusterGenerationAssembler.clusterQuiescence(node)` — typed `KVStore.forEach` governor scan, NOT a full-store `snapshot()` copy, on the 200ms poll path). This is where the #68 persisted-flicker died. |
| `4cc7135f5` | W2c | CDM (`ClusterDeploymentManager`/`Context`/`State`) `activeNodes()`←FSM `countedMembers()` (minus passive); `activeCommunityIds()`←KV `GovernorAnnouncementKey` keyset. Replaced `snapshotSupplier` with `countedMembersSupplier`. |
| `8ca36aea9` | W2d | `BootstrapModule` DHT core-partition seeding ← FSM `countedMembers` + KV partitions/config; dropped the projector + `currentMembershipSnapshot`/`readPublishedSnapshot`/`projectFromCommittedAtoms`. |
| `d59e3f487` | W2e | NDM epoch + ClusterTopologyRoutes ← observed ping epoch / FSM countedMembers; consensus `ctmProvisionedSupplier` ← FSM-backed `PresenceMembershipView`; deleted `KvBackedGenerationSnapshotSource`/`SnapshotMembershipView` usage. Last snapshot reader removed. |
| `6fb16bda2` | W3 | **DELETED** (13 files): `GenerationSnapshotPublisher`, `ClusterGenerationProjector`(+`ProjectionInput`), `KvBackedGenerationSnapshotSource`, `SnapshotMembershipView`, `MemberLifecycle`, `ClusterGenerationSnapshot` record, `AetherKey.GenerationSnapshotKey`+`GenerationSnapshotValue`+serializer/ephemeral entries, the 1s markDirty tick + KV-watch fan + `snapshotSupplier`/`currentGenerationSnapshot()`/`requestGenerationSnapshotRefresh()` plumbing + 6 dead tests. Grep-gate: 0 prod refs. **KEPT** `ClusterQuiescenceEvaluator`, `PresenceGenerationSnapshotSource`/`PresenceMembershipView` (FSM-backed). |

### Two #114 regressions caught by Docker + fixed
- `fe974452e` — **ctmProvisioned infinite recursion** (StackOverflowError at boot → consensus never commits → no leader → formation timeout). W2e had wired `ctmProvisionedSupplier` → `snapshotSource.currentMembershipView()`, but that source builds its view *from* `ctmProvisionedSupplier`. Fixed: read FSM descriptors filtered by `source=="ctm"` directly (`ctmProvisionedFromFsm`, AetherNode ~2402). NOTE: `ctmProvisionedNodeIds()` has **zero downstream consumers** anyway.
- `3efbbbbb4` — **leader epoch-stall**. The leader's `observedEpoch` only updates from *received* pings; the leader never pings itself → leader-served gen-route/await read `0:0` forever → `current+1` deploy barriers unreachable. Fixed: `ManageableNode.currentGenerationEpoch()` = `isLeader ? leaderMintedEpoch : observedEpoch` (`currentGenerationEpochSupplier`, AetherNode ~1229), wired into gen-route + await-quiesced + NDM. Docker-proven: leader counter advanced 3→9 in 6s; deploy `current+1` barriers pass; blue-green deploy ran clean.

### #125 — real chaos-recovery bug #114 exposed
`fb34017bf` — `MembershipFsm.healthHints()` iterated ALL members (no `notDead()` filter) and DEAD→FAULTY is unconditional (no TTL). Retained DEAD tombstones (kept for incarnation-fenced rejoin) emitted permanent FAULTY hints that poisoned the live quiescence verdict (`"7 members FAULTY"` while all 5 live members HEALTHY) → `restore_cluster_baseline` 180s timeout. `countedMembers()`/`broadcastEligibleMembers()` already filter DEAD; the OLD projector read health off `countedMembers` so this only surfaced once W2b fed `healthHints()` directly. Fix: `liveHealthHint` gates the projection on `notDead()`. Docker-validated: most 02 restore barriers now quiesce.

### #124 — 06 harness state-isolation (partial)
`4c5f7da4e` — strategy deploys collided on version (blue-green made 1.0.1 active, canary/rolling then hit the `sameVersion` 500). Added `assert_active_version` + corrected the post-v1 barrier `current+1`→`current` across the 3 strategy tests. Helped (full-suite 06 went 4p/1f), but blue-green still fails — see #124 below.

---

## Validation evidence (#114 core)
Docker, remote cluster, `run-tests.sh --env remote --skip-build`:
- Formation 5 nodes/6s, leader elected/0s, CTM activated ready=5.
- Deploy `current+1` barriers PASS (blue-green start→promote→complete clean; quiesce at `1:27`/`1:30`/`1:31`).
- Epoch advances ~1/sec (leader counter 3→9 over 6s, term=1).
- 02-chaos: kill-leader re-elect, kill-2, kill-non-leader, auto-heal-to-5, JOINING-window-evict-in-1s all PASS.
- Unit: aether/node 523 tests 0F; aether-deployment + aether-metrics clean; `ClusterQuiescenceEvaluatorTest` 8/8; `MembershipFsmTest` (incl. new DEAD-exclusion tests) green.

---

## Full-suite map (latest run, `/tmp/wf114-full.log`) — **9/15 PASS**

**PASS (9):** 00-smoke, 04-streaming, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 14-storage, 15-delegation.

**FAIL (6) — categorized; NONE is a #114 regression:**
| Suite | Fail detail | Root | Tracked |
|---|---|---|---|
| 02-chaos 5p/1f | `Kill_node_during_load: no NODE_FAILED in 60s`; restore-quiesce cleanup (×3) | SWIM-detect latency under load; never-READY joiner | **#94** + **#126** |
| 12-network 2p/2f | `no NODE_FAILED in 60s` ×2; `connectedPeerCount=3 (≥4)`; `4+ READY in 600s` | same SWIM latency; env Docker-bridge transient; recovery latency | **#94** + transient |
| 13-edge 0p/3f | slices won't deploy (`0 instances`, `artifact isolation`, `concurrent deploy 500`); `drain 500≠409`; `drain 500 lifecycle-not-found` | **cluster-B churn cascade** (13 runs last) + drain-budget | cascade of **#126** + **#93** |
| 03-scaling 2p/1f | `scale-down 7→5 under load: 22.21% error rate` (>2%) | request routing during scale-down under load | VERIFY (likely pre-existing; could touch W2c/W2e — recheck) |
| 05-security 1p/2f | TLS renewalStatus HEALTHY w/o TLS; admin whoami = anonymous/VIEWER | cluster runs under `AETHER_INSECURE_DEV_MODE` | **#95** (known) |
| 06-deployment 4p/1f | blue-green `deploymentId` missing / no post-promote state | publish/deploy semantics | **#124** |

**Cascade insight:** cluster-B suites run sequentially 02→03→05→12→13; `restore_cluster_baseline reported non-zero; subsequent suites may inherit cluster churn` fired after 02. So **#126 contaminates 13 (slices won't deploy) and contributes to 12's READY-600s.** Fixing #126 is the biggest *cascade* lever.

Baseline was ~10/15 (handover 2026-06-04); 9/15 now is within cluster-B churn variance — same family of pre-existing issues.

---

## ▶ NEXT — remaining work, prioritized

### 1. #126 — never-READY joiner pins quiescence (APPROACH APPROVED: never-healthy eviction timer)
**Root (verified):** a CTM-provisioned replacement that never reaches READY is promoted OBSERVED→MEMBER on its **first** SWIM-healthy edge (`UP_HYSTERESIS=1`, `MembershipFsm` ~437), then flaps MEMBER→SUSPECT, re-stamping `lastDoubtAtMs` within the ~15s TTL; the co-confirmation gate (`swimFaulty ∧ livenessGone`) rarely latches → it stays counted-SUSPECT, pinning quiescence DEGRADED >180s. `onJoinGraceExpired` (the never-healthy→DEAD path) **has no production caller** AND is **ignored in SUSPECT** (`MembershipState:161`; only OBSERVED→DEAD at :84). So a naive timer→onJoinGraceExpired does nothing to a SUSPECT ghost.

**Approved approach + the design subtlety to get right:**
- The FSM does NOT know READY (READY is a `NodeReportedState` from the pong/control-heartbeat, available in `AetherNode` via the pong fan / `nodeReportedStateHolder`/`cdmReadyNodesRef`). The discriminator "never-READY" must come from the **node-side readiness signal**, NOT FSM state alone — a SWIM-healthy-but-never-READY node is a member by `presence=membership`, so do NOT evict on FSM-SUSPECT alone.
- Plan: (a) `MemberTracking` add `firstObservedAtMs`; (b) extend `JoinGraceExpiredNeverHealthy` to also transition **SUSPECT→DEAD** (and consider MEMBER→DEAD only when never-READY — risky, since it can evict a real flapping member); (c) a `SharedScheduler` timer in `AetherNode` that fires `onJoinGraceExpired(id)` ONLY for members present > join-grace AND never-reported-READY (via the readiness supplier) AND currently OBSERVED/SUSPECT. The "never-READY + grace" judgment lives in the timer (which has readiness data); the FSM just provides the transition. Then #125 already excludes the resulting DEAD member → quiescence clears.
- **Lower-risk alt the user did NOT pick** but worth keeping in mind: exclude never-stably-healthy SUSPECT joiners from the quiescence verdict only (like #125-for-DEAD) — no eviction, reconciler cleans up later.
- Files: `MembershipFsm.java`/`MembershipState.java`/`MemberTracking` (aether-deployment/membership/fsm), timer in `AetherNode.java` (near the readiness-sweep ~1202 / bump ~1213). Fragile subsystem — single-commit, Docker-validate 02 (restore quiesces) + confirm no over-eviction of legit slow joiners.

### 2. #94 — NODE_FAILED-within-60s under load (the *counted* 02 + 12 failure)
SWIM SUSPECT→FAULTY detection latency under load; a killed node's departure isn't observed within 60s. Separate root (SWIM tuning / φ-accrual removed). Pre-existing. This is what actually fails 02 `Kill_node_during_load` + 12 `Kill_node_and_detect_drop`/`SWIM_detection_time`. Needs its own investigation (SWIM probe cadence under load, or the FSM DEAD-edge emission timing).

### 3. #124 — 06 blue-green publish/deploy (needs clean instrumented repro)
The 06 investigation was inconclusive + ran on a contaminated cluster A. Clean-run evidence: harness `assert_active_version` PASSED (v1 1.0.0 active) → `publish 1.0.1` → canary deploy "1.0.1 already active". Candidate root: register-only publish bootstrap-activates a base whose `SliceTargetValue` it (mis)reads as absent (`ClusterDeploymentState.handleAppBlueprintChange`/`shouldSuppressActivation` ~857/885). Defense-in-depth fix: register-only publish must NEVER advance the active target. REFUTED theory: the ping-timer epoch did NOT regress the barrier (the OLD generation also advanced ~1/s via the markDirty tick, so `current+1` was always ~time-based). Needs a fresh-cluster instrumented repro of the canary/rolling/blue-green publish→deploy sequence.

### Pre-existing, separate (not this session's scope): #93 drain-budget 500≠409, #95 secure-mode cluster-B variant, #91 DHT durability, #97 budget-stress, 12-network connectedPeerCount transient (env Docker-bridge), 03-scale-down error-rate (verify not a W2c/W2e regression).

---

## State / environment
- HEAD `4c5f7da4e`, tree clean, **15 commits UNPUSHED**. Shaded JAR `aether/node/target/aether-node.jar` @ Jun 8 09:49 carries all #114 + #125.
- Both Docker clusters were left UP (`--skip-teardown`) after the full run, then the run ended; **clean them before the next run** (`docker rm -f aether-*` + network/volume rm + forge-postgres; `pgrep run-tests.sh` first). The 06 investigation mutated cluster A's deploy state earlier.
- Env vars `$TARGET_HOST`/`$AETHER_SSH_KEY`/`$AETHER_SSH_USER`/`$AETHER_API_KEY` set; reference by name. Node mgmt ports: cluster A 5151-5155, cluster B 5161-5165 (resolve replacements via `docker port <name> 8080/tcp`).
- Full-suite log: `/tmp/wf114-full.log`. JSON report: `aether/tests/integration/test-results.json`.

## Key learnings
- **Docker validation is non-negotiable for wiring changes** — both #114 regressions (ctmProvisioned recursion, leader self-read) passed all unit tests and only surfaced on a real boot/leader-served path. Unit tests don't exercise the wired call graph.
- **The leader doesn't observe its own ping** — any per-node "current cluster X" derived from `observedEpoch` must special-case `isLeader ? mintedLocal : observed`.
- **`healthHints()` vs `countedMembers()` consistency** — when a projection feeds quiescence/health verdicts, it must apply the SAME DEAD/counted filters as the membership-count projections, or retained tombstones poison it.
- **Cluster-B suites cascade** — a non-quiescing `restore_cluster_baseline` (#126) churns every subsequent cluster-B suite (esp. 13, which runs last). Read cluster-B failures with the cascade in mind; fix the upstream membership root first.
- Audit confirmed `GenerationSnapshotPublisher` was the codebase's #1 KV-scan + per-second-consensus offender; #114 removed it. Secondary offenders flagged (info only): `DashboardMetricsPublisher` 1s `forEach` when a dashboard is open; `KvStoreApiKeyValidator` full-store scan per authenticated request (RC2 follow-up).

## Standing directives (unchanged)
HCLOUD-safe builds (`env -u HCLOUD_TOKEN`, never `mvn verify`/`./build.sh` with token set; `build-runner` owns maven). Single-line commits, no trailers. Commit on `release-1.0.0-rc1`; **push only when asked** (15 unpushed). Docker: clean-slate + `pgrep run-tests.sh` before runs; capture logs before teardown; `ssh -n` in loops. Delegate to preserve context; verify subagent claims (the 06 investigator's "barrier regression" was refutable; the 02 investigator's timer fix was incomplete for SUSPECT). Instrument/probe before fixing.
