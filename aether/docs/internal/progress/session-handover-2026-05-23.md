# Session Handover — 2026-05-23

**Branch:** `release-1.0.0-rc1` | **HEAD:** `3557b05bd`
**Predecessor:** [session-handover-2026-05-22e.md](session-handover-2026-05-22e.md)
**Tag (rollback safety net before NodeId migration):** `v1.0.0-rc1-candidate` → `ebebd2a6b`

## 1. One-line summary

Landed Phases 1-5 of the cluster-convergence-reconciler initiative + reconciler warmup fix + NodeId-as-container-name architectural migration + a swarm of RC1 hygiene fixes (publish/deploy decoupling, HLC threading, DHT timeout bump, scheduled-task locality routing, marker-event ordering race, and 8 other test-infra fixes). 30 commits this session. Cluster A converges cleanly at ~10/10 minus 2 known pre-existing 09-artifacts flakes. Cluster B 02-chaos + 03/05/12/13 still cascade — root cause now well-characterised and a clean architectural fix path identified (operator-API-driven test cleanup), discussion deferred per user.

## 2. State at handover

- **Local working tree:** clean.
- **`./build.sh`:** still RED on Step 2 (pre-existing Task #13 JBCT-RET-01 baseline — not session-introduced). Focused `mvn -pl <module> install -DskipTests -am` builds clean across all touched modules.
- **Test totals across the initiative:**
  - `aether-config`: 294 (was 290 at session start; +2 reconciler config tests, +2 DHT timeout regression tests)
  - `aether-deployment`: 572 (was 515; +57 across reconciler scaffolding/rules/warmup + SwimLifecycleGap demote test + HlcStamping regression)
  - `aether-invoke`: 183 (unchanged baseline)
  - `aether-metrics`: 199 (was 183; +16 for Phase 2 PR-B SYNCING tests)
  - `aether/node`: 490 (was 453; +37 for Phase 3 PR-C audit + lifecycle routes commands + Fix A blueprint tests)
  - `aether/resource/http`: 7 (CI flake eliminated by switching to `Promise.isResolved()` assertion)
  - `integrations/cluster`: 46 (was 29; +17 for Phase 2 PR-B SyncHold tests)
  - `integrations/consensus`: includes HLC tests merged in via #228
  - `integrations/dht`: 102 (was 98; +4 regression tests for 30s default)
  - `jbct/slice-processor`: SliceProcessorTest now green after #229 revert
- **Remote cluster state:** both clusters torn down; rebuild via `./run-tests.sh --env remote --skip-build` (orchestrator's `restore_cluster_baseline` handles it).

## 3. The cluster-convergence-reconciler initiative — final phase status

| Phase | PR (squash) | Status | Commit |
|---|---|---|---|
| 1 PR-A | scaffolding (FSM collapse 6→4 + Command primitive + audit + KV deadline atoms) | ✅ committed | `1124a9a56` |
| 2 PR-B | SYNCING sub-phase + readyCandidate + sync-hold registry | ✅ committed | `8184a8d4a` |
| 3 PR-C | operator REST + CLI + audit-stream tail + source attribution | ✅ committed | `1636bbd73` |
| 4 PR-D | LifecycleReconciler dry-run + 7 rules + status endpoint | ✅ committed | `4b412cfa2` |
| 5 PR-E | flipped 5 rules to enforcing | ✅ committed | `e848b12e3`, then reverted by `a0bb269ea` (premature firing during cluster formation), re-applied with warmup fix `8c2a21972` |

**Phase 5 post-revert improvements (rolled in via warmup):**

- 60s NORMAL-phase warmup grace period (default in `ReconcilerConfig.normalPhaseWarmup`) — absorbs SWIM gossip flap during cluster formation/recovery.
- SWIM-since tracker resets on every NORMAL-phase entry so stale Faulty timestamps don't carry over.
- `enforcingDefaults()` activates: `JoiningTimeout`, `OnDutyFaulty`, `DrainTimeout`, `GenerationLifecycleGap` (4 rules; 3 stay audit-only forever: `JoiningStuckAlert`, `SwimLifecycleGap`, `StoppedZombie`).
- `SwimLifecycleGap` demotion (commit `7cc9a77eb`): keeping it enforcing would create a phantom-recovery loop — when DecommissionedAtomGc removes a STOPPED entry while SWIM still sees the peer, the rule would emit RecordJoining → re-register → JoiningTimeout fires → ForceDecommission → loop.
- HLC threading (commit `53735f48e`): commands carry per-tick HLC stamp instead of `HlcTimestamp.ZERO` placeholder.

**Evidence the enforcing reconciler works:** Validation #4 audit stream showed `OnDutyFaulty` firing legitimately against `node-2` after 30s sustained SWIM-Faulty, CTM provisioned ad-hoc replacement, cluster A all suites continued passing.

## 4. The NodeId == container name migration

**Motivation (from analysis):** Two parallel allocation paths (compose vs CTM) assigned incompatible naming conventions. Test infra assumed compose names; CTM-provisioned replacements had random suffixes. This was the root cause of the cluster B silent-divergence cascade (`pick_non_leader 0/2`, "lifecycle reports node-2 as ON_DUTY but no live container").

**Architectural choice:** `NodeId == container_name` everywhere.

**Implementation (3 commits):**

| Commit | Scope |
|---|---|
| `cc531643b` | DockerComputeProvider slot allocator: `max(existing aether-${cluster}-node-N) + 1` from `docker ps -a --filter label=aether.cluster=<cluster>`; AtomicLong floor handles back-to-back provisions before previous container is observable. `Main.parseNodeId` reads `AETHER_NODE_ID` → `NODE_ID` → `HOSTNAME` → UUID. |
| `ce6d63810` | Compose: full-form NodeIds (`aether-{a,b}-node-N`) on `AETHER_NODE_ID`, PEERS env, `aether.node-id` label. Port-base shifted −6 to absorb new 1-based slot math. |
| `e85343f79` | Test infra: `kill_node`/`start_node` use NodeId directly as docker container name; `pick_non_leader` liveness via `docker ps --filter name=^${node_id}$`; `to_node_id` translates `node-N` → `aether-${cluster}-node-N` on docker (cloud unchanged); 3 cluster B test scripts updated; README updated. |

**Verified post-migration:**

- Cluster A: 5 nodes ON_DUTY as `aether-a-node-{1,2,3,4,5}`.
- CTM allocation working: validation #7 produced `aether-b-node-6` (clean sequential extension of compose's 1-5).
- Direct curl deploy on settled cluster: ~50ms.

**Surfaced regression (now fixed):** Under parallel-suite-load + cluster bootstrap, the `/api/blueprints/deploy` path was breaching a 10s timeout in `DHTConfig.DEFAULT_TIMEOUT` (artifact resolution via DHT). Longer NodeIds added enough latency to push past the threshold. Fixed by bumping to 30s (commit `3fcf58745`) — aligned with `RemoteRepository.DEFAULT_HTTP_TIMEOUT` and `ArtifactStoreImpl.DEPLOY_TIMEOUT` conventions.

## 5. Side-fixes landed this session (production)

| Commit | Fix |
|---|---|
| `ceee238ef` | `Deployment.rolledBack()` + `applyRollbackRouting` terminal advance — unblocks 06-deployment Rolling cascade |
| `208be15d8` | 07-cluster-mgmt round-trip equality filters runtime `scaling-cooldown/*` keys |
| `addbec54d` | Fix A — `/api/blueprints/publish` decoupled from `/api/blueprints/deploy` via `AppBlueprintValue.registerOnly` flag; `handleAppBlueprintChange` skips `SliceTargetValue` Put when `registerOnly && existing SliceTargetValue present` |
| `86bcb53d8` | PR #228 — HLC into consensus, `HlcTimestamp.nodeId String → NodeId` |
| `fb2ce8adc` | PR #229 — revert `ENVELOPE_FORMAT_VERSION` 1001→1000 (no envelope bumps until GA) |
| `3a9e51899` | `SliceInvoker.findSenderBridge` returns Option (no unwrap); `ScheduledTaskRoutes` locality gate emits `SliceNotLocal{hostingNodeId}` instead of 500 NPE |
| `d77f9e976` | `HttpClientFactoryEagerTest` flake: replace `<500ms` timing assertion with `Promise.isResolved()` correctness check |

## 6. Test-infrastructure improvements

| Commit | Fix |
|---|---|
| `7dfcadb5f` | 08-resources Pause/Resume polls for KV propagation (10s budget) |
| `cae6e9ef1` | 08-resources Last_execution_advances posts inject to slice-hosting node via `tasks.0.registeredBy` offset |
| `ec8f3ac5b` | 11-observability All_nodes_agree_on_order waits for marker-count convergence before snapshotting (eliminates open-replication-window race) |
| `ebebd2a6b` | 05-security cert helper quoting (`aether_field "certs status" tlsEnabled` — three-arg form silently dropped the field) |
| `3557b05bd` | 08-resources NodeId regex `^(aether-[ab]-)?node-([0-9]+)$` accepts post-migration form |

## 7. Validation results

Six validation runs across the session. Cluster A trajectory clean by the end:

| Run | Cluster A | Cluster B | Notes |
|---|---|---|---|
| #2 | 34p/2f (06+07 unblocked by patches A+B) | 02-chaos cascading | Set the cluster B silent-divergence floor pattern |
| #3a (enforcing reconciler, no warmup) | 4p/3f on 00-smoke | (not reached) | Revealed Phase 5 needs grace period |
| #3b (reverted enforcing) | 10/10 - 2 flakes | 02-chaos cascade | Confirmed revert |
| #4 (warmup + enforcing) | 10/10 - 2 flakes | 02-chaos cascade | Reconciler audit stream shows correct enforcing behavior on cluster A |
| #5 (14 suites, no 02) | 10/10 + 2 new regressions (08 Last_execution + 11 marker race) | 03/05/12/13 cascade | Both new findings → fixed in next round |
| #6 | 10/10 - 2 flakes | 03/05/12/13 cascade | All 3 cluster A regressions resolved |
| #7 (post-NodeId-migration) | 8/10 + 2 new regressions (06 all strategies + 08 NodeId regex) + 2 flakes | 02-chaos showed `aether-b-node-6` (sequential CTM allocation working!) | NodeId migration validated at allocation layer; DHT timeout surfaced |

Validation #8 with the DHT timeout + 08 regex fix is the natural next checkpoint.

## 8. Set-aside item — #5 (cluster B 02-chaos + 03/05/12/13)

**User requested discussion before action.** Mechanism is well-characterised (see commit `cc531643b` rationale + handover §11 of 22e):

1. Destructive tests kill compose-managed containers
2. CTM auto-heal provisions replacements faster than test cleanup can run
3. Test infra's `pick_non_leader` finds inconsistent state (KV count vs live container count diverge)
4. Test cycle cascades

The NodeId migration removed naming-domain mismatch but didn't address the timing race between destructive-test cleanup and CTM auto-heal. The 60s reconciler warmup is too slow for the destructive-test timing budgets.

**Possible directions for #5 discussion:**

- (a) Test-side: each destructive script's cleanup uses the Phase 3 PR-C operator API (`aether nodes decommission`) for synchronous force-removal of stuck ON_DUTY entries — doesn't wait for reconciler.
- (b) Auto-heal-side: tests reliably `disable_auto_heal` BEFORE the first kill; verify the disable took effect; re-enable in cleanup. Audit each destructive script for proper auto-heal disable sequencing.
- (c) Reconciler-side: a "destructive test" mode flag with shorter warmup + more aggressive timeouts.

**Recommendation (per session discussion):** combination of (a)+(b) — operator API as the explicit cleanup mechanism, auto-heal disable as the discipline. (c) is over-engineering for what's fundamentally a test-discipline gap.

## 9. Open follow-ups (post this session)

### From this session (still open):

1. **Validation #8** — re-run full 15 suites with DHT timeout + 08 regex fix. Expected: cluster A 10/10 minus 2 known flakes; cluster B 02/03/05/12/13 will need #5 to fully converge.
2. **#5 discussion** — operator-API-driven destructive-test cleanup architecture. Pending user input.
3. **Task #13** — pre-existing JBCT-RET-01 26 violations across aether-stream/aether-metrics/aether-deployment block `./build.sh` Step 2. Still pre-existing; not session-introduced.
4. **DecommissionedAtomGc retention review** — empirical observation in validation #4 audit stream showed `SwimLifecycleGap` firing for `node-2` repeatedly because GC removed the STOPPED entry while SWIM still saw the peer. SwimLifecycleGap is now audit-only (commit `7cc9a77eb`), so this is observational noise rather than a phantom-recovery loop. But the underlying GC racing-SWIM behavior is worth investigating.
5. **RC2 #226** — consolidate `/api/blueprints/{publish,deploy}` + `/api/deploy` into 3 distinct primitives (filed earlier this session).

### From 22e handover (carried forward):

- Open follow-ups 1-11 from 22e §9. Many addressed this session:
  - HLC threading in reconciler emissions ✅ (commit `53735f48e`)
  - LifecycleCommand @Codec — still open (low priority, no consumer)
  - HlcTimestamp.ZERO in F-migration call sites — still open in CTM/drain coordinator (the reconciler path is fixed)
  - JBCT-RET-01 baseline (Task #13) — still open

## 10. Active tasks at handover

```
#5    [set aside]   Cluster B 02-chaos + 03/05/12/13 unblock — operator-API + auto-heal-disable discipline
#13   [pending]     JBCT-RET-01 26 pre-existing violations — blocks ./build.sh Step 2
#18   [completed]   Validation #7 — surfaced DHT timeout + 08 regex regressions
```

## 11. Verification recipe (run on session start)

```bash
# Confirm git state
git rev-parse HEAD                       # expect 3557b05bd (or further if user committed more)
git status --short                       # expect clean
git log --oneline abded84fa..HEAD | wc -l  # expect 30+

# Confirm key commits are in tree (sample check)
git log --oneline --grep="NodeId == container" -1   # cc531643b
git log --oneline --grep="DEFAULT_TIMEOUT 10s" -1    # 3fcf58745
git log --oneline --grep="Phase 5 PR-E" -1           # e848b12e3 (and 8c2a21972 for warmup)

# Focused builds (avoid build.sh Step 2 RED)
mvn -pl aether/node install -DskipTests -am          # refresh shaded JAR
ls -lh aether/node/target/aether-node.jar            # expect ~51 MB

# Test totals sanity
mvn -pl aether/aether-config test                    # expect 294 / 0F / 0E
mvn -pl aether/aether-deployment test                # expect 572 / 0F / 0E
mvn -pl aether/node test                             # expect 490 / 0F / 0E
mvn -pl integrations/dht test                        # expect 102 / 0F / 0E
```

## 12. Suggested first action in the next session

Three options:

**A. Validation #8** — full 15 suites, prove cluster A holds + measure how far cluster B got with the DHT timeout + NodeId migration. Cheap (~30 min).

**B. Discuss #5** — operator-API-driven destructive-test cleanup architecture. Then implement.

**C. Wrap-up:** if validation #8 is clean on cluster A, this is a reasonable RC1 candidate. Move tag, push to main candidate, draft release notes.

Recommended: A → B → C.

## 13. Constraints carry-over (still in effect)

- **Single-line commit messages only.** No body. No trailers. No `Co-Authored-By`.
- **NEVER `-Djbct.skip=true`** for aether builds (POM hierarchy handles it).
- **NEVER `mvn verify`** with `HCLOUD_TOKEN` set (Failsafe creates real Hetzner servers).
- **NEVER feature branches on `release-1.0.0-rc1`** — commit directly.
- **NEVER inline `$TARGET_HOST` / `$AETHER_SSH_KEY` / `$AETHER_SSH_USER`** — reference by name only.
- **BSL-1.1 SPDX header** on new files under `aether/**`, `jbct/slice-processor/`, `jbct/slice-processor-tests/`.
- **PEERS now `nodeId:host:port` with FULL NodeId** — post-migration, nodeId == container name `aether-{a,b}-node-N`. Test infra `to_node_id` handles translation from short `node-N` form on docker (passthrough on cloud).
- **Container == NodeId** convention: `docker kill ${node_id}` Just Works.
- **`build-runner` agent** owns Maven invocations to keep noise out of main context.
- **`jbct-coder` agent** for non-trivial Java implementation; `jbct-reviewer` for on-demand audits.
- **Delegate by default** — main context is the scarce resource.

## 14. Notable architectural decisions (worth remembering)

1. **NodeId-as-container-name** — `aether-{a,b}-node-N` is the canonical form. Random suffixes are gone. Docker kill works directly. Test infra has no name-mapping logic. This is THE cluster B unblocker at the architectural layer; #5 closes the remaining test-discipline gap.

2. **Reconciler 60s warmup** — required because SWIM gossip can flap during cluster formation and 30s budget for OnDutyFaulty would otherwise fire on still-stabilizing peers. The warmup also resets the SWIM-since tracker on phase transitions so stale stamps don't carry over.

3. **SwimLifecycleGap is audit-only forever** — enforcing creates phantom-recovery loop. Spec §7.1 already mandated this; PR-E mistakenly flipped it. Demoted in commit `7cc9a77eb`.

4. **publish/deploy decoupling via `registerOnly` flag** — `AppBlueprintValue.registerOnly` suppresses `SliceTargetValue` activation when there's an existing entry. First-ever publish always bootstraps. The runtime now correctly distinguishes "register a definition for future strategy upgrade" from "deploy immediately".

5. **DHT default timeout 30s** — aligned with `RemoteRepository.DEFAULT_HTTP_TIMEOUT` + `ArtifactStoreImpl.DEPLOY_TIMEOUT`. The 10s previous default was insufficient for the deploy chain under bootstrap + parallel load conditions.

6. **HLC stamping** — reconciler commands now use per-tick HLC stamps via `ReconciliationSnapshot.at`. `HlcTimestamp.ZERO` placeholders remain in F-migration call sites (CTM + drain coordinator) — those are open follow-ups.
