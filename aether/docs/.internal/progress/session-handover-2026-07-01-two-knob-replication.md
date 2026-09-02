# Session Handover — 2026-07-01 (S20 harness fix DONE; #262 two-knob stream replication WIP)

> Long session. Two deliverables: (1) **S20 restart_all_nodes fix — DONE, committed `cc948e19f`, validated 4×** on real infra. (2) **#262 two-knob stream replication — substantially implemented, unit-green, real-infra-improved, but full-history owner-kill failover NOT yet lossless** due to a config-propagation race. Banked as a documented WIP commit. This doc is the resume point.

## ⚡ TL;DR / current state
- Branch: `feat/336-reachability-evidence` (off `release-1.0.0-rc2`).
- **S20 fix `cc948e19f`** (in `aether/tests/integration/lib/cluster.sh`): DONE + validated. `restart_all_nodes` now name-prefix-precleans `aether-b-node-*` + a container-count guard force-recreates when `docker compose up -d` flakily returns 0 with <5 containers running. Validated 4 runs this session (guard caught 2/5, 0/5, 1/5, 2/5 → force-recreated to 5/5 each time). **This is the banked, done win.**
- **#262 two-knob replication (WIP commit):** config decoupling + off-by-one fix + sync barrier + promotion gate + config-adoption. Compiles, ~540 aether-stream unit tests green. On real infra it **improves** behavior (replication + convergence + post-failover writes now work; the old silent RF-collapse + infinite read-hang are gone) but the **initial pre-kill history is still lost on owner-kill** (integration test `test-stream-replica-failover.sh` = 6 pass / 3 fail).
- Remote cluster B currently deployed with the fixed two-knob image (from the last validation run, `--skip-teardown`). Cluster A + `forge-postgres` healthy. **Not cleaned up** — safe to `docker rm -f $(docker ps -aq --filter name=aether-b-node-)` (leaves A + postgres).

## What #262 is + the semantics we chose (Kafka-style, two knobs)
Root problem found this session: `min-sync-replicas` was **overloaded** as BOTH the replica count (RF, via `ReplicaPlacement.replicationFactor(APP, minSyncReplicas, N)=clamp`) AND the write-ack requirement (`awaitReplication(minAcks=minSyncReplicas)` needs `minSyncReplicas` DISTINCT NON-SELF acks). Since non-self replicas = RF−1 = minSync−1 < minSync, **every synchronous publish was rejected** → the sync barrier had never worked end-to-end (no test exercised it — the only failover test published via the async management API).

Decision (user-approved): **decouple into two knobs, Kafka `min.insync.replicas` semantics**:
- **`replicas`** (int, default 1) = replication factor / total copies incl owner.
- **`min-sync-replicas`** (int, default 0) = min in-sync replicas INCLUDING owner that must ack a write. `≤1` = no peer-ack wait; `≥2` = await `min-sync − 1` distinct non-self acks. Invariant `0 ≤ min-sync ≤ replicas`.
- `consistency-mode` (EVENTUAL/STRONG) stays the READ knob, untouched.

## The debugging arc — three layers of the SAME bug (don't re-discover)
1. **Off-by-one (fixed).** RF derived from `minSync` while the barrier needed `minSync` non-self acks → unsatisfiable. Fix: `replicas` field for RF; `minAcks = minSync − 1`; barrier triggers at `minSync > 1`.
2. **REST publish fabricated a default config (fixed, but see #3).** `StreamRoutes.ensureStreamExists` hardcoded `StreamConfig.streamConfig(name, DEFAULT_PARTITIONS, …)` → `replicas=1/min-sync=0`, committed over the app stream. This disarmed the barrier (min-sync=0 → publish returns success awaiting nothing) AND collapsed the replica set to RF=1 (→ `replicationTargets` empty → nothing sent). First real-infra run: **0/20 replicated, promoted owner empty, read hangs forever** (the infinite hang was reaping the long jobs). Fix: primary — `ensureStreamExists` does a local `streamInfo` read and leaves an existing stream's config intact; secondary (load-bearing) — `StreamPartitionManager.onStreamConfigPut` switched `computeIfAbsent`→`compute`+`reconcileCommittedConfig` (monotonic-up adoption of the committed app config onto the SAME rings/WALs).
3. **⚠️ REMAINING: config-propagation RACE.** After fix #2, the run completes (no hang), post-failover writes replicate, and `Replica set converged (no lag)` PASSES — proving replication now works. BUT `Complete history after failover` = **0/20**: the owner published the initial 20 markers using the DEFAULT config (RF=1/min-sync=0) BEFORE the app config `replicas=2/min-sync=2` propagated/adopted onto the owner's live entry, so those 20 never replicated. Writes AFTER adoption (the 5 liveness markers) DO replicate + serve. The adoption fix made config *eventually* correct, not *immediately* correct at first-write.

## Where to resume — closing layer #3
Two paths (pick one; ~1 more real-infra cycle each, ~20 min now that the hang is gone):
- **Product (cleaner, harder):** ensure `ensureStreamExists` (and the parallel `StreamApiRoutes.ensureStreamExists` ~line 413, same defect, left unfixed) NEVER fabricates a default for a stream that may be app-declared under a not-yet-propagated local read — forward/await the committed config instead of defaulting. Closes the race at the source.
- **Test-side (pragmatic):** gate the first publish in `test_deploy_repl_stream_blueprint` on the owner's config actually showing `min-sync=2` / RF=2 authoritative (a CORRECT version of the best-effort wait that was removed — but NOT via `wait_for`, which counts its timeout as a `[FAIL]`; use a non-counting poll). Papers over the product race but makes the test prove the feature.

## Files touched (all in the WIP commit unless noted)
- **slice-api** `StreamConfig.java`: `replicas` field (order: `…consistencyMode, replicas, minSyncReplicas, compression, encryptionKeyId`).
- **slice** `KVStoreSerializer.java` (13-field wire, `replicas` @ index 4), `StreamConfigParser.java` (parse `replicas`, validate `min-sync ≤ replicas`).
- **aether-stream** `StreamCatalog.java` (StreamSpec 4-arg), `ReplicaSetController.java` (RF from `replicas`), `StreamPartitionManager.java` (replicaCatalog carries replicas, `minSyncReplicasFor`, `onStreamConfigPut` adoption), `PartitionedStreamAccess.java` + `DefaultStreamPublisher.java` (barrier `>1`, `minSync−1`), `PartitionBackfill.java` (catch-up-before-serve promotion gate).
- **node** `StreamRoutes.java` (wire `awaitReplication` on publish + config-preserve fix; return type `Result`→`Promise`).
- **aether-deployment** `AuditLifecycleStreams.java` (`replicas=1`, audit stays eventual).
- **Tests:** unit tests across slice/aether-stream/node (round-trip, validation, barrier arithmetic, RF-from-replicas, adoption, promotion-gate, ensureStreamExists-preserve).
- **Integration:** `tests/blueprints/test-stream-repl/` (NEW module — RF=2 stream `repl-failover-events`, `replicas=2, min-sync=2`), `build.sh` (blueprint list), `suites/02-chaos/test-stream-replica-failover.sh` (rewrite: deploy RF=2 blueprint instead of RF=1 `POST /api/streams`), `suites/02-chaos/CHARTER.md` (TC-02-034), `docs/specs/streaming-spec.md` (§10.5 Stream Replication Durability).

## Known limitations / follow-ups (documented, deliberately deferred)
- **Config-propagation race (layer #3)** — the primary remaining gap; see "Where to resume".
- **A2 promotion gate is `replicas=2`-correct only.** For `replicas > min-sync` it (a) computes the catch-up target from the LOCAL registry, which may understate a survivor's true tail unless peer watermarks propagate — the robust path is the wired-but-unused `ReplicaWatermarkProbe`; (b) has a bounded transient serving window (gate engages at redrive, not `onBecameOwner`); (c) has no write-side fence during catch-up. All fine for `replicas=2` (single sync replica is always the caught-up survivor). Real headroom (`replicas=3/min-sync=2`) needs these hardened AND a `replicas=3` integration scenario (timing-sensitive) to validate.
- **`StreamApiRoutes.ensureStreamExists`** (~line 413) — parallel copy of the fabricate-default defect, left unfixed (absent-race healed at manager level; present-case benign).
- **Cosmetic:** if the default config `Put` is ordered after the app `Put` in the consensus log, the stored KV *value* ends as the default though live entries converge correctly (runtime `minSyncReplicasFor`/placement are right).
- **Doc drift:** `aether/docs/reference/streaming-performance-analysis.md` still states old `minSyncReplicas > 0` barrier wording — needs a pass.
- **S19 self-drain flake (SEPARATE, pre-existing, not #262):** `Survivors_self-drain_and_exit_within_45s_S19` flaked in 2 of ~4 runs (a survivor doesn't `Runtime.halt(2)` within the 45s budget; exits 0 instead). Orthogonal to all stream work (membership self-drain timing). Likely too-tight budget or a real self-drain-detection lag — worth its own investigation. Memory: [[project-s20-root-cause-harness-quorum]].

## How to validate (now that the hang is gone, a full run completes ~20 min)
```
HCLOUD_TOKEN= mvn -q -pl aether/node -am install -DskipTests            # rebuild node jar with any fix
HCLOUD_TOKEN= mvn -q -f aether/tests/blueprints/test-stream-repl/pom.xml install -DskipTests
HCLOUD_TOKEN= ./aether/tests/integration/run-tests.sh --env remote --suites 02 --skip-build --skip-teardown   # (NO --skip-image-push → rebuilds remote image from fresh jar)
# replica-failover is the LAST 02-chaos file; watch Complete_history_after_failover (currently 0/20).
# cleanup: ssh $TARGET_HOST 'docker rm -f $(docker ps -aq --filter name=aether-b-node-)'
```
Faster iteration once the image is deployed: the standalone `scratchpad/run-replonly.sh` runs JUST the replica-failover test against the live cluster (~5 min) with the exact cluster-B env `run_suite` exports.

## Memory updated
- [[project-s20-root-cause-harness-quorum]] — S20 FIXED + validated (name-prefix preclean + count guard).
- [[project-336-node-add-eviction-injvm-repro]] — #336/#391 validated incl. full 02-chaos.
- (this session) #262 two-knob replication WIP — see this handover + the WIP commit(s).
