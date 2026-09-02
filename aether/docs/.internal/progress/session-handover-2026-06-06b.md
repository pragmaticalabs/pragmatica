# Session Handover — 2026-06-06b (PR #239 merged to rc1 + tag-gate fixes)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `e6952646d` (pushed) · tree clean (untracked `aether/tests/integration/suites/02z-killonly/` is local scaffolding — see Learnings).
**Candidate tag:** `v1.0.0-rc1-candidate` at `69baa86d2` — **a few commits behind HEAD; move it when wrapping.**

## ▶ NEXT SESSION — priority order
1. **Remaining RC1 tag-gate items (MED/LOW)** — in progress this session's tail; see the scorecard. Issue 2 (cross-node event delivery) → B5 (readiness-latency) → A3 (drain-budget 409) → 05 (secure-mode variant).
2. **RC2/future:** physical-node-drain DHT durability (the reverted barrier, task #91, patch preserved — see below).

## TL;DR
**PR #239 (stream namespaces, epic #165) is MERGED into rc1** via fast-forward (`401381432`), GitHub auto-marked it merged. The RC-blocker that gated it (the "consensus re-election wedge", Issue 7) was root-caused as a **replacement-resync wedge** (an uncaught `No codec registered for ReplicationMessage` on the Rabia apply path killing the single apply worker) and fixed via **7a** (codec, stream-lane) + **7c** (apply-executor exception containment, consensus-lane, mine) + **7b** (KVStore replay signal + stream-lane gate). Also shipped this session: a **scale-up provisioning-stickiness** fix (over-provision-on-failover), the **11/system:cluster-events** observability-registration fix, and the **A1 scale-down data-loss** fix. Both RC1-blocking tag-gate items (11, A1) are **closed**.

## Commits on rc1 this session (all pushed)
| Commit | What |
|--------|------|
| `754d82b0a` | fix(consensus): **7c** — `safeExecute` contains apply-executor handler exceptions so a throwing apply/restore handler can't kill the single worker (+ `RabiaEngineApplyContainmentTest`, proven to fail without the guard via worker-thread identity) |
| `f50f7b3fb` | feat(cluster): **7b** — `KVStore.isReplaying()` sticky replay signal so subscribers suppress side-effects during snapshot/resync restore (+ test) |
| `0563169a0` | fix(provisioning): **scale-up dispatched-IDs survive leader change** — leader broadcasts in-flight provisioning set on `ClusterSyncPing` (`dispatchedNodes`), followers retain it sticky+term-fenced (NOT TTL), new leader seeds `inFlightProvisioning` from it instead of re-dispatching → kills the 12-network over-provision-to-6. Gossip+term-fence (incarnation analog), no consensus. |
| `401381432` | **#239 merge** (FF) — carries 7a codec + 7b gate + blueprint dotted→kebab addressing migration + stream-error-masking fix + **#47 (app publish routes to HRW owner, not consensus leader)** + the stream-namespaces feature |
| `69baa86d2` | docs(changelog) for 7c/7b/scale-up |
| `b5de60993` | fix(stream): **11** — register `system:cluster-events` on **leader-gain** (`registerSystemStreamsOnLeaderChange`: createStream + catalog) so observability reads stop 500ing. Boot-time `[main]` registration ran ~seconds before consensus activation, failed "Node is inactive", was never retried → `/api/events|alerts|traces` 500 "Stream not found". |
| `e6952646d` | fix(repository): **A1** — persist ALL artifact PUTs, not just `.jar`. `MavenProtocolHandler` discarded non-jar content (returned 201, never stored) → GET 404 silent data loss. `handlePutJar`→`handlePutArtifact`, route all `ArtifactPath` through `store.deploy`. + test (seed marker expects 200) + lint-baseline bump. |

## Full-suite validation (combined merge image `401381432`)
9/15 green pre-fix (was 7/15 on the pre-#239 baseline). Improvements: 06-deployment 2/3→5/0 (blueprint migration), 04/08 →green (#47 relieved app-publish), 12-network 1/3→2/2 (**scale-up fix: S06 partition-heal returns to 5, no over-provision-to-6 — validated in the wild**). One regression at the time — 11-observability 6/0→3/3 — was the masking fix surfacing the system-stream-registration bug honestly; **now fixed (`b5de60993`).** Post-fix isolated re-runs: **11 → 5/1** (residual = Issue 2), **03 → 3/0** (A1 + A2 green).

## RC1 tag-gate scorecard
- ✅ **11 system:cluster-events registration** (`b5de60993`)
- ✅ **A1 scale-down data-loss** (`e6952646d`) — real bug = PUT-discard, NOT a drain-durability gap
- ✅ **A2 scale-down forwarding** — passes (0%; the prior 74% was variance, not a code issue)
- ⬜ **Issue 2 — cross-node replicated cluster-event delivery** (#89, MED): `11/All_nodes_agree_on_order` — a published marker reaches the owner but doesn't converge to a non-owner node's `/api/events` within 30s. Replicated-delivery / read-forward gap. Observability-only.
- ⬜ **B5 — recovery latency** (MED): `12-network 4+ cores READY (target=5)` 600s timeout + `Kill_node…NODE_FAILED within 60s` (02/12). Readiness-convergence + detection-latency family (#68). Cluster heals but exceeds thresholds.
- ⬜ **A3 — drain budget** (`13-edge`, LOW): 3rd drain returns 500, should be 409 (budget exhausted); `First_drain` 500 "Node lifecycle not found". Has a `TODO: investigate` in the test.
- ⬜ **05-security** (config/test-infra): TLS + admin-auth fail under `AETHER_INSECURE_DEV_MODE`; needs a secure-mode cluster-B variant.
- ⬜ **physical-node-drain DHT durability** (#91, RC2/future): see below.

## The A1 correction (a learning worth keeping)
The first (theoretical) investigation pointed at a missing drain-time DHT handoff barrier; I implemented it (`DHTRebalancer.handoffOwnedData` + `DrainProcedure.preExitHandoff` + bounded settle). **Docker validation refuted it**: `No_data_loss` still 404. An **instrumented** repro (survivor-side `MigrationData` receipt logs + continuous log capture before CTM removes drained containers + `docker events`) proved: (a) the test's scale 5→7→5 is **config-churn only — no physical node is provisioned/drained in this env**, so the handoff never ran; (b) the marker was **never stored** — `MavenProtocolHandler` discarded non-`.jar` PUTs. The real fix was 5 lines. **The barrier was reverted** (speculative, unvalidated, with a real hash-space concern: ownership probe `nodesFor("partition:"+p,RF)` ≠ entry bucketing `partitionFor(key)`); preserved as a patch at `/tmp/aether-issue7/a1-drain-handoff-barrier.patch` for the future physical-drain item (#91), which needs a real-physical-drain repro env + per-key targeting.

## Resume / how to run
- **rc1:** `e6952646d` pushed, clean. `git checkout release-1.0.0-rc1`.
- **Validate a suite:** rebuild jar `env -u HCLOUD_TOKEN mvn -pl aether/node -am install -DskipTests`, then `cd aether/tests/integration && ./run-tests.sh --env remote --skip-build [--skip-image-push] --suites N [--skip-teardown]`. **Always** strip `HCLOUD_TOKEN` for mvn; **never** `mvn verify`/`build.sh`. **Always** `docker rm -f aether-*` + `docker network rm aether-{a,b}-network` before runs (CTM leaves no zombies here, but be safe).
- Evidence/artifacts: `/tmp/aether-issue7/` (run logs, the `7bc-DONE-handshake.md`, `ISSUE-blueprint-addressing-migration.md`, the barrier patch).

## Learnings
- **Instrument before fixing a runtime anomaly.** The A1 theoretical analysis sent me down a structural-barrier path for a non-problem; one instrumented Docker run found the real 5-line bug and saved an unvalidated speculative commit.
- **This test env scales via config-churn, not physical provisioning** — `scale_cluster N` advances configVersion but `docker events` showed zero container create/destroy; "7 members" was the generation snapshot, not live nodes. So drain/handoff code paths are NOT exercised by 03/12 here — needs a real-provisioning env to test.
- **Harness `.sh` edits drift the lint-baseline line numbers** (bit me on `test-03-scale-down.sh:99→101`); update `lint-baseline.txt` when adding/removing lines in a linted test.
- **`system:cluster-events` (and any consensus-committed registration) must run on leader-gain, not `[main]`** — boot-time consensus.apply fails "Node is inactive" and isn't retried.
- Departure propagates via **SWIM gossip + incarnation fencing (NOT Rabia consensus)**; scale-up state now rides the same class (ClusterSyncPing gossip + term-fence), per the design discussion.
