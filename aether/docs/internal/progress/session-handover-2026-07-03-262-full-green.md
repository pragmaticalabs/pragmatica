# Session Handover — 2026-07-03 (#262 stream replication FULL GREEN on real infra)

> Continuation of `session-handover-2026-07-01-two-knob-replication.md`. That doc ended
> with "IMPROVED but NOT lossless" (config-propagation race, replica-failover 6p/3f).
> This session closed the race **and three deeper layers it un-masked**. Final state:
> **suite 02 fully green on real infra — replica-failover 9 PASS / 0 FAIL**, S19+S20
> green in the same run, `rc=0`, 798s. Everything committed on `feat/336-reachability-evidence`.

## ⚡ The four layers (each fix un-masked the next — do not re-discover)

1. **Config-propagation race (layer 3 of the 07-01 handover) — FIXED.**
   `StreamRoutes.ensureStreamExists` + `StreamApiRoutes.ensureStreamExists` (~:413) no longer
   fabricate an RF=1 default when the local `streams`-map read misses: they read the committed
   `StreamConfigValue` from the applied-state kvStore (`getTyped(StreamConfigKey…)`) and
   materialize with THAT config, defaulting only when genuinely absent.
   **Plus the structural root**: replica placement was edge-triggered (boot/membership/quorum)
   with NO stream-config edge — in a stable cluster an app stream's RF=2 set was never placed
   until an unrelated edge fired. Fixed: the `StreamConfigKey` Put handler in `AetherNode`
   (~:2672) now also fires `ReplicaSetController.reconcile()` (registered AFTER
   `onStreamConfigPut` hydration; reconcile is async on the controller executor, idempotent,
   never writes `StreamConfigKey` → no re-entrancy).

2. **Post-failover watermark reporting (owner reseat + backfill ack) — FIXED.**
   The owner's own registry row was written only by backfill self-paths → a promoted owner
   reported `CAUGHT_UP@-1` forever (and the steady-state owner's row lagged its ring).
   Fix A: owner redrive predicate gains `ownerRegistryBelowRingTail` → reseat via
   `ownerSelfPromote` to the ring tail. Fix B: a non-owner that reaches CAUGHT_UP via
   *backfill* now sends the live `ReplicateAck` to the current owner (PartitionBackfill gains
   a `ReplicationTransport`), so replacement replicas show CAUGHT_UP without waiting for the
   next write.

3. **Fresh-HRW-owner catch-up trigger (peer-watermark-blind predicate) — FIXED.**
   A CTM-auto-healed fresh node that wins HRW ran `promoteOwner`, but `aheadSurvivor` read
   survivor tails from its LOCAL registry (peer watermarks never propagated; `WatermarkStore.NOOP`)
   → survivor read as −1 → false-ready empty self-promote `CAUGHT_UP@-1`. Fix: when local says
   "none", probe *blind* peers' real tails via the already-wired `ReplicaWatermarkProbe`
   (`probeThenPromoteOwner`/`blindPeers`/`decideOwnerCatchup`); reachable-ahead →
   `catchupOwnerFromSurvivor` (which now also records the pulled survivor's row CAUGHT_UP@tail —
   truthful, monotonic-guarded since `updateWatermark` is NOT monotonic); unreachable →
   `escapeOwnerCatchup` bounded wait. Local-registry HIT path byte-identical.

4. **Recovery epoch fence (the deepest layer) — FIXED.**
   With layer 3 fixed, the fresh owner PULLED the events but every apply was rejected:
   *"Stale-epoch stream append rejected: presented owner epoch 0:0 is older than the partition
   high-water 1:3"* → infinite escape loop. Root: the recovery seam (`streamPartitionRecovery`,
   AetherNode ~:2760) was bound to the 4-arg `appendRecovered` which stamps `Epoch.ZERO`.
   Fix (~3 lines): stamp `streamOwnerEpochSource.currentOwnerEpoch(s,p)` (the committed
   `StreamPartitionOwnershipValue.ownerEpoch` — the SAME committed value the fence's high-water
   derives from). Epoch **adoption**, not invention: can't forge newer, never strictly older;
   cold-start ZERO-vs-ZERO still passes; the fence itself untouched. Also unblocks any
   replacement-replica backfill into a high-water-advanced partition.

## Test-harness fixes (were masking / mis-reporting reality)
- `wait_for_stream_config_committed` gate: requires non-owner replica **placed** (not CAUGHT_UP —
  an empty stream's replica stays SYNCING until the first write; the barrier is state-agnostic,
  so placement is the right pre-publish proof). Plain poll, not `wait_for` (whose timeout latches FAIL).
- `count_inorder_offsets`: `streams read --format json` is pretty-printed multi-line; the
  line-oriented grep never matched → flatten `tr '\n' ' '` first. (Product had always served
  offsets 0..24 correctly — verified live.)
- `test_replica_set_converged`: convergence predicate is now `no lag AND a CAUGHT_UP non-owner`
  (`converged_with_rf_restored`) + a positive "Replacement replica re-replicated" assertion —
  anti-false-green: "no CAUGHT_UP lags" passes vacuously while the replica is stuck SYNCING.

## Validation evidence (final run, fresh image md5-verified `d2ee3f5e…`)
- **Suite 02 complete: `Total: 1 | Passed: 1 | Failed: 0`, rc=0, 798s.** Every file green:
  5/0, 5/0, 5/0, 5/0, kill-under-load 4/0, self-drain 7/0 (S19 passed — flake did not recur;
  S20 guard force-recreated and recovered), **replica-failover 9/0**:
  gate → `Published all 20` → kill → `Complete history` 20/20 → offsets-in-order →
  `Converged partition owner is CAUGHT_UP` → `Replacement replica re-replicated (RF restored)` —
  convergence in **0s**.
- Unit: aether-stream PartitionBackfill/ReplicaSet/StreamReadRouter/ReplicationReceive/
  StreamPartitionManager suites all green incl. new nests `PostFailoverReplicasViewReseat`,
  `FreshOwnerBlindRegistryProbeFallback`, `RecoveryEpochFence`; node StreamRoutes suites green.
- Historical context: pre-session 6p/3f (0/20 history); mid-session 8p/1f twice (each revealing
  the next layer via live-cluster probes + owner container logs — the observability-first loop).

## Known remaining (documented, NOT blockers)
- **S19 self-drain flake** — pre-existing, orthogonal (flaked 2 of ~6 runs this arc; passed the
  final two). Membership self-drain timing; worth its own ticket.
- **Env-kill of long background runs** — local harness environment intermittently kills runs
  (observed at 4/13 min; two full runs completed at ~12-13 min). Not a product issue.
  Mitigation used: full suite resets per-file baselines; `run-replonly.sh` (scratchpad) for a
  ~5-min single-file cycle against a live cluster — but beware: replonly on a churned cluster
  (mid-auto-heal) invalidates ownership-stability assertions (data assertions stay valid).
- **A2 gate replicas>2 hardening** + `streaming-performance-analysis.md` doc drift — unchanged
  from the 07-01 handover.
- Stale remote leftover: `/home/aether/node/target/aether-node.jar` (Apr 14) on `$TARGET_HOST`
  is NOT what deploys — the harness pushes `~/aether-node.jar` and builds `aether-node:local`
  (verify with container-jar md5 vs local, as done this session).
- Cluster B left running post-test (`--skip-teardown`). Scoped cleanup:
  `docker rm -f $(docker ps -aq --filter name=aether-b-node-)` (leaves A + forge-postgres).

## Where things live
- Product: `PartitionBackfill.java` (layers 2+3), `AetherNode.java` (reconcile edge ~:2672,
  epoch stamp ~:2760, backfill transport wiring), `StreamRoutes.java`/`StreamApiRoutes.java`
  (committed-config materialization), tests in `PartitionBackfillTest.java`,
  `StreamPartitionManagerTest.java` (`RecoveryEpochFence`), `StreamRoutesEnsureStreamExistsTest.java`.
- Integration: `suites/02-chaos/test-stream-replica-failover.sh` (gate + parser + convergence).
- The "broken `StreamConfigReplicationTest`" scare was a stale `~/.m2` slice-api jar — refreshed
  via `mvn -pl aether/slice-api install -am`; zero source changes needed.
