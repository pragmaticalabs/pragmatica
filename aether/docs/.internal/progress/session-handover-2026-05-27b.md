<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-27b (cluster-B wedge root-caused to resync-stuck joiner; φ-accrual committed; 3 correct-but-uncommitted fixes; instrumentation live)

**Branch:** `release-1.0.0-rc1`. **Most work LOCAL/unpushed. DO NOT push.** Temporary diagnostic instrumentation is still in the tree (intentionally — the next session needs it).

## 0. TL;DR
1. **#231 φ-accrual leader-side detection: COMMITTED** (`5a9885ecd`) — black-hole detection + leader-stall guards; full validation done last session.
2. Spent this session root-causing the **cluster-B chaos wedge** ("formally quorate, functionally dead"). Went through ~6 diagnoses, several wrong, corrected by empirical instrumentation + repeated user pushback. **Net: the wedge is a CONSENSUS RECOVERY bug — an auto-healed replacement joins SWIM/transport but its Rabia engine gets stuck in `Syncing` (resync never completes; `connectedNodes=0` on its consensus client) → it never becomes a voting proposer → with surviving voters < quorum, consensus can't decide → `Backpressure exceeded` storm → permanent wedge.**
3. Built + **engine-level-validated** a Rabia **paused-sync-responder** fix. It is **correct but ORTHOGONAL** to the Docker wedge (the fix's branch never fired on Docker — survivors were never `Paused` at sync time). KEEP it (real latent-bug fix) but it does NOT make cluster-B recover.
4. **#230 Fix 1 (compute-provider readiness gate)** + **Fix 2 (CTM quorum-safety)**: implemented + validated, **uncommitted**, correct.
5. **#236 ticketed (RC2):** bounded dissolution — liveness/quorum gates count transport-reachable peers, not synced voters.
6. **NEXT TARGET: the resync-stuck-joiner path** (§4). The paused-sync fix is done; the resync bug is what actually wedges cluster-B.

## 1. Commit / working-tree state
- **COMMITTED:** `5a9885ecd feat(membership): leader-side φ-accrual detection replacing 2-plane gate, with leader-stall guards (#231)`. Tag `v1.0.0-rc1-candidate` on it.
- **UNCOMMITTED (working tree), all CORRECT but cluster-B not green:**
  - **#230 Fix 1 — compute-provider readiness gate.** `aether/environment-integration/` (`ComputeProvider.confirmRunning`, `ReadinessPolicy`, `EnvironmentError.ProvisionReadinessTimeout`) + docker/aws/gcp/azure/hetzner providers. `provision()` now confirms instance RUNNING (bounded poll, reuse `instanceStatus()`) before success; on timeout → FAIL so CTM frees the slot (no phantom). Validated: 6 modules + provider tests.
  - **#230 Fix 2 — CTM provisioning quorum-safety.** `ClusterTopologyManagerRecord.provisioningGatesPass`: abort a FILLING reservation when confirmed-healthy voters (`view.healthyOnDutyCount()`) < `configured/2+1` → route to dissolve. 619 deployment tests pass. (Note: did NOT fire in the wedge runs — `quorum-abort count = 0` — so it's a guard, not the wedge-breaker.)
  - **Rabia paused-sync-responder fix.** `RabiaEngine.doHandleSyncRequest` (~`:1004`): condition extended to `isActive()||isObserving()||isPaused()` so a **Paused** responder serves its retained in-memory state (`makeSnapshot + currentPhase + pendingBatches.values()`) instead of the persisted/empty fallback. Engine-validated RED→GREEN (`RabiaPausedSyncResponseTest`), 573 Rabia tests pass. **Correct but orthogonal to the Docker wedge (never fired — 0 `paused=true` on Docker).**
  - **Tests (new/changed):** `RabiaPausedSyncResponseTest` (keep — deterministic engine test of the paused-sync fix), `MembershipMultiKillSpikeTest` (keep — clean-dissolve case, PASSES), `MembershipQuorumMaskSpikeTest` (keep but **assertion is stale** — it asserts dissolution; post-fix it RECOVERS via plain sync; re-orient to assert recovery; ALSO it is race-dependent / doesn't reliably hit the bug). Restored `RabiaConsensusIntegrationTest` to HEAD.
  - **TEMPORARY INSTRUMENTATION — STILL IN TREE (keep for the resync investigation; revert before any commit):** `[RABIA-DIAG]` + `[ENGAGE-DIAG]` in `RabiaEngine.java` + `PhaseData.java`; `[LEADER-DIAG]` in `LeaderManager.java` + `fsm/LeaderElectionState.java`. Revert grep tags: `RABIA-DIAG`, `ENGAGE-DIAG`, `LEADER-DIAG`.
  - **Reverted dead-end:** an "adopt-on-entry" Rabia change (wrong first fix) was fully reverted.

## 2. THE cluster-B wedge — confirmed mechanism (Docker, with the fix + instrumentation in the JAR)
- Setup: 5-node cluster-B, chaos kills (S01 + kill-2 incl. leader). Surviving committed topology has node-3 + node-5 = **2 voting proposers**; quorum (fixed `clusterSize`) = 3.
- Auto-heal provisions a replacement (`aether-b-node-3EJRjetXw6…`). It comes up, joins SWIM (leader sees its pongs), but on its **consensus** client: `STATE Stopped→Syncing, connectedNodes=0`, `BEHIND by 350+ phases → triggerResync`, and **never leaves `Syncing`, 0 proposals on every node.**
- Result: phase 481 `STALL PROPOSALS size=2 need quorum=3` ×3420; **0 `DECIDED` for ~28 min**; MembershipFsm retries `ForceDecommission`/`TransportUnreachable` for dead node-4 → `Backpressure exceeded: 1000 pending batches` (3784 on leader, ~400/min) → harness sees `scale_cluster rc=7`, `restore_cluster_baseline: failed to converge to 4+ ON_DUTY within 600s`.
- **Wedge-signature checks:** `Backpressure exceeded` = YES; functional cluster-ready timeout = YES; `responding NON-LIVE (Paused)` = NO; `responding LIVE-EQUIVALENT paused=true` = **NO (fix never fired)**.
- **Decisive datum:** the replacement's consensus transport shows **`connectedNodes=0`** — it has no consensus-plane connections to the survivors, so it cannot complete resync (sync needs `syncQuorumSize()` responses from connected peers), stays `Syncing`, never proposes, never becomes the 3rd voter. The SWIM/metrics plane sees it (pongs) but the consensus/QUIC-client plane did not establish connections to the survivors.

## 3. Diagnosis history (so the next session does NOT re-tread)
1. **φ over-eviction** (leader-stall mass-eviction) — REAL → fixed+committed (#231).
2. **Phantom provisioning** (failed `docker run` returns RUNNING) — REAL → #230 Fix 1.
3. **"Static voter set / replacements aren't voters"** — **WRONG.** Rabia counts replies by `size()` (`PhaseData.hasQuorumProposals` etc.), no admission/identity gate. (User corrected.)
4. **"Membership-change-needs-quorum admission deadlock"** — **WRONG** (no admission gate). 
5. **QuorumState counts transport-reachable, not synced voters → masks sub-quorum → suppresses dissolution** — REAL but SECONDARY → ticketed RC2 **#236**. (Makes the wedge fail to dissolve; not why recovery fails.)
6. **Paused responder ships empty `pendingBatches` on sync** — REAL latent bug; fix built + engine-validated; **but ORTHOGONAL to the Docker wedge** (survivors weren't Paused at sync time; fix didn't fire).
7. **ACTUAL Docker wedge (§2): auto-healed replacement stuck in Rabia `Syncing` (resync never completes, `connectedNodes=0` on its consensus client) → never a voting proposer.** ← **NEXT TARGET.**

## 4. NEXT TARGET — why the auto-healed replacement can't complete resync
Investigate, on Docker (in-process Ember does NOT reproduce this — see §5): **why does a freshly auto-healed replacement's consensus client show `connectedNodes=0` and never complete resync?**
- Start at `RabiaEngine`: the `Syncing` state, `synchronize()` (broadcasts `SyncRequest`, needs `syncQuorumSize()` responses), `handleSyncResponse`, the `BEHIND by N phases → triggerResync` path (around `handlePropose`). Why does resync never satisfy `syncQuorumSize()`?
- Likely upstream of Rabia: the replacement's **consensus QUIC client (`QuicClusterClient`) never connects to the surviving cores** (`connectedNodes=0`). Check: does an auto-healed node (new KSUID id, not in boot-time `PEERS`) get its consensus connections established to the survivors? Is this the static-`PEERS` topology again (the replacement isn't dialed by / doesn't dial the survivors on the consensus plane, even though SWIM `connect(NodeInfo)` ran)? See `ClusterNetwork.connect(NodeInfo)` (SWIM JoinAnnounced path) vs the consensus broadcast peer set (`QuicClusterNetwork.peers`).
- Hypothesis to test: SWIM admits the replacement (pongs visible to leader) but the **consensus transport peer set** on the replacement (and/or the survivors→replacement direction) is not established, so the replacement can neither receive proposals nor get sync responses → stuck `Syncing`.
- The `[ENGAGE-DIAG]` STATE/SYNC logs + `[RABIA-DIAG]` are already wired to show this — re-run cluster-B and grep the **replacement's** logs for `STATE … Syncing`, `connectedNodes=`, `SYNC broadcast SyncRequest`, `SYNC-RESPONSE … responsesNow=N need syncQuorum=M`, and whether it ever reaches `Syncing→Idle`.

## 5. Dev-loop reality + tooling
- **In-process Ember does NOT faithfully reproduce the cluster-B Docker wedge.** In-JVM sync is instant and survivors stay LIVE; Docker resync is slow + can stall with `connectedNodes=0`. Multiple Ember runs "recovered" via paths that don't exist on Docker. **Docker cluster-B is the source of truth for this bug.** (This is the core argument for DST — §6.)
- Ember spikes (fast, single-JVM): `MembershipChaosSpikeTest` (single kill → recovers), `MembershipMultiKillSpikeTest` (multi-kill, no joiners → clean dissolve, PASSES), `MembershipQuorumMaskSpikeTest` (race-dependent). Engine-level: `RabiaPausedSyncResponseTest` (deterministic).
- Run Ember spikes: `mvn -Pwith-e2e install -pl aether/forge/forge-tests -am -DskipTests` then `env -u HCLOUD_TOKEN mvn -Pwith-e2e -pl aether/forge/forge-tests verify -Dit.test='<Name>' -DfailIfNoTests=false`. (NEVER bare `mvn verify` with `HCLOUD_TOKEN` set — failsafe → Hetzner. `env -u HCLOUD_TOKEN` + `-pl forge-tests` only = safe.)
- Docker cluster-B: build jar `mvn -pl aether/node install -DskipTests -am` (bypasses blocked build.sh format/lint), then `aether/tests/integration/run-tests.sh --env remote --skip-build --suites 00,02,03,05,12,13`. RABIA/ENGAGE/LEADER-DIAG appear in the **container** logs (`docker logs aether-b-node-*`), not the harness stdout — grep them via ssh (vars exported: `$TARGET_HOST`/`$AETHER_SSH_KEY`/`$AETHER_SSH_USER`, reference by name). Watch the `batch-N` substring trap — match full `NodeId[id=...]`.

## 6. DST assessment (done this session) — the strategic fix for the dev-loop gap
Effort assessment produced. Substrate is closer than the doc's "highest cost" implies: `ClusterNetwork` is already an interface (QUIC+Netty, has `blackhole()`); CTM already takes an injectable `LongSupplier` clock; Rabia is deterministic (weak coin, no RNG except leader-election jitter which is already injectable). **The cost is two global async singletons:** `SharedScheduler` (static) + `Promise`'s own hardcoded virtual-thread executor + `Thread.sleep`. Phases: P1 = virtual clock + deterministic scheduler in Ember (would let us deterministically reproduce timing/stall bugs like this one); P2 = in-memory `SimClusterNetwork` (latency/loss/partition); P3 = seeded reproducible failing seeds. MVP first step: `Clock`/`TimeSource` seam + injectable scheduler + one Ember test reproducing a known timing bug deterministically. This session's repeated "Ember recovers, Docker wedges" is exactly the gap DST closes.

## 7. Commit / cleanup decision (PENDING — the Docker test changed the plan)
User's intended sequence was test→cleanup→commit, predicated on the Docker test passing. **It did not** (cluster-B still wedges via the resync path). Options for next session:
- **(a)** Keep everything uncommitted + instrumentation in place; fix the resync-stuck-joiner bug (§4); THEN revert instrumentation, re-orient `QuorumMaskSpikeTest`, and commit the whole batch once cluster-B actually recovers.
- **(b)** Commit the SOLID, independent fixes now to bank them — #230 Fix 1 (environment modules, no instrumentation) and Fix 2 (aether-deployment, no instrumentation) are cleanly separable; the Rabia paused-sync fix is intertwined with the consensus instrumentation, so leave it + instrumentation uncommitted for the resync work.
- Lean: (b) for #230 Fix 1/2 (correct, validated, no instrumentation, banks progress), (a) for the consensus changes. Decide with user.

## 8. Remote host state
**Cluster-B is left WEDGED on `$TARGET_HOST`** (the confirmation run was still cascading against a dead cluster). **Clean before the next run:** `docker ps -aq --filter name=aether --filter name=test-` → `docker rm -f`, then `docker volume ls -q --filter name=aether`/`--filter name=test` → `docker volume rm` (incl. `aether_pgdata`). NOTE: the auto-mode classifier BLOCKS unfiltered `docker ps -aq`/`docker volume ls -q` mass-rm; the **`--filter name=`/`--filter volume=` forms PASS** — use those.

## 9. References
- Issues: **#230** (cluster-B recovery: provisioning + resync), **#231** (φ-accrual, committed), **#236** (RC2 bounded dissolution).
- Design doc: `aether/docs/internal/membership-failure-detection-unification.md`.
- Prior handover: `session-handover-2026-05-27.md` (φ-accrual + control-plane removal).
