# Design-Stream Session Handover — 2026-06-30 (#336 reachability-evidence fix: implemented, validated on real infra, PR #391)

> This stream now IMPLEMENTS (aether-main busy with mid-term planning). This session took #336 from
> an in-JVM red repro all the way to a reconciled, real-infra-validated fix in **PR #391** off
> `release-1.0.0-rc2`. Untracked handover (on disk, like prior ones). Durable facts also in memory:
> `project_336_node_add_eviction_injvm_repro.md` (rewritten), `MEMORY.md` pointer updated.

## ⚡ TL;DR / current state
- **PR #391** (`feat/336-reachability-evidence` → `release-1.0.0-rc2`) is OPEN, clean (3 commits), verified.
  It is the reconciled #336 fix. **This is the deliverable.** https://github.com/pragmaticalabs/pragmatica/pull/391
- **Workspace is currently checked out on `feat/336-reachability-evidence`.** Working tree clean (only the
  untracked prior + this handover doc).
- **#359 (`feat/241-worker-governor`, slice 3) is DEFERRED** — see §"Deferred work". A clarifying note was
  posted: PR #359 #issuecomment-4848091554.
- Remote integration host: test containers cleaned up (`aether-*` removed, `forge-postgres` preserved).

## What #391 is (and why it's separate from #361)
Two synergistic commits (do NOT split):
1. `9156887a8` **option-1**: `ClusterSyncContext.emitPingTimeoutIfExceeded` no longer does the destructive
   `network.disconnect` + `evictionHints.put` on transient SWIM-SUSPECT; it feeds
   `recordTransportHint(PeerUnreachable(PING_TIMEOUT))` into SWIM (same pipeline as QUIC `onPeerLeft`),
   refutable when pongs resume. Removes the eviction-hint broadcast (an S20 self-drain-cascade trigger).
2. `e26c2f809` **SWIM refutation hardening**: at-risk proactive self-refute (`refreshSelfAlive` advances own
   incarnation on inbound-reachability silence); co-confirmation kill-gate (`coConfirmedFaulty`: ≥2 distinct
   accusers OR transport-unreachable hint) before terminally departing an ever-HEALTHY peer on a first-hand
   FAULTY (threaded `boolean firstHand` through `emitFaultyOrUnknown`'s 3 call sites); `log(N)` suspect window.
   **option-1's hint trips this gate's transport-veto arm — mutually dependent.**

**Release already had a #336 fix: #361 (`38dde3c2b`, "OBSERVED birth state").** Reconciliation (this session)
proved #361 and #391 target **disjoint cases**: #361 = freshly-added node at join; #391 = established HEALTHY
peer transiently SUSPECTED under churn. All of #391's changes are **additive** to release (release has no
equivalent of any). Release's `ClusterSyncContext` STILL has the destructive disconnect — the gap is real.

## The full arc (key learnings — don't re-discover these)
1. In-JVM `CommunityFormationProbeTest` is RED but it's an **INVALID gate** — single-JVM CPU starvation (8
   nodes/8 cores) makes SWIM probe-acks late → nodes genuinely reach FAULTY. Proof: **LHM sawtooth**
   (increments==decrements, score capped at 1 → late-not-absent) + no-ack count drifting 15→25→38 on rebuilds
   alone (load jitter). It is now `@Disabled` on `feat/241-worker-governor` (commit `c27614993`); on `release`
   it exists via #361 (not disabled there). **Validate #336 on real infra, never this in-JVM probe.**
2. The operative eviction path was NOT SWIM's internal FAULTY path — it was the cluster-sync eviction-hint
   mechanism (`emitPingTimeoutIfExceeded`), found because Fix-2's log line was absent while a different logger
   fired. build→observe→reframe caught it.
3. Interim attempts (cluster-sync join-grace guard, then a FAULTY-gate) were tried and REVERTED — superseded by
   option-1. Don't resurrect them.

## Validation evidence
- **Units:** 170 green on `feat/336-reachability-evidence` (`integrations/swim` + `aether/aether-metrics`):
  SwimProtocolTest/Wave6/Tombstone/DeathPathCoConfirmation/PhaseAwareSuppression + ClusterSyncFsmTest. Clean
  compile incl. the git-auto-merged `AetherNode`.
- **Real infra (on predecessor branch `feat/241-worker-governor`):** `run-tests.sh --env remote --suites 02`
  (S20 test excluded there) — all 5 kill/membership tests pass; killed node removed from membership ~6s,
  cluster re-elects/fails-over; dropped-broadcast convergence risk did NOT materialize.
- **NOT yet run:** full 02-chaos on `feat/336-reachability-evidence` (recommended pre-merge — release now has
  the S20 `restart_all_nodes` fix so baseline-restore shouldn't cascade). See §"How to run" below.

## ⚠️ S20 caveat (pre-existing, unrelated to #336)
`feat/241-worker-governor` is ~53 commits behind release and forked BEFORE the S20 `restart_all_nodes`
force-recreate fix landed on `release-1.0.0-rc2`. So S20 self-drain recovery fails on THAT branch (container
recreate failure, not a quorum/membership failure — confirmed). `release` HAS the fix. See
[[project-s20-root-cause-harness-quorum]].

## Deferred work (after #391 merges)
**Rebase `feat/241-worker-governor` (#359) onto the updated `release`.** Cleanest sequencing: once #336/#391
is in release, the duplicate #336 commits on #359 drop out automatically, #361's `SwimProtocol`/`AetherNode`
changes are absorbed, and slice-3 (`62464a456`) lands on top. Expect conflicts in `SwimProtocol`/`AetherNode`
(slice-3 vs #361) + a duplicate forge-test (release has it via #361) — resolve, re-verify (SWIM units +
ideally real-infra 02), force-push #359. This is the genuinely messy part; it was deliberately deferred.

## Local environment notes (this machine was NOT set up for integration tests)
- The `aether` CLI was broken: `~/.aether/bin/aether` is a symlink whose `$0`-relative launcher resolved
  `jre`/`lib` to `~/.aether/` (missing). **Fixed** by symlinking `~/.aether/jre` → `aether-cli-1.0.0-rc1/jre`
  and `~/.aether/lib` → `aether-cli-1.0.0-rc1/lib`. (Side effect: `aether-forge`/`aether-node` CLIs would now
  mis-resolve — irrelevant for the integration suites, which only use `aether`.) macOS TCC/Local-Network was
  NOT a blocker once the CLI started.
- Integration env vars present: `$TARGET_HOST`, `$AETHER_SSH_KEY`, `$AETHER_SSH_USER`, `$HCLOUD_TOKEN`.

## How to run the real-infra suite safely (for the pre-merge re-validation or #359 work)
```
# --env remote = fixed host docker-compose, NO paid VMs. ALWAYS prefix HCLOUD_TOKEN= (remote doesn't need it).
HCLOUD_TOKEN= mvn -q install -DskipTests        # safe build (NO verify → no HetznerCloudIT; build.sh also safe but slower)
HCLOUD_TOKEN= ./aether/tests/integration/run-tests.sh --env remote --suites 02 --skip-build --skip-teardown
# cleanup after:  ssh $TARGET_HOST 'docker rm -f $(docker ps -aq --filter name=aether-)'  (forge-postgres is safe — name doesn't match)
```
Footguns (all cloud-only, none bite --env remote): the catch-all `cloud-reaper.sh` nukes the shared
`forge-postgres` VM → never run `--env cloud` without `--skip-teardown` + cluster-scoped reap. NEVER run
`mvn verify` with `HCLOUD_TOKEN` set (spawns `HetznerCloudIT` paid server).

## Follow-ups parked
- Delete the now-inert `evictionHints`/`currentEvictionHints`/`processEvictionHints` plumbing (broadcast's
  sole writer removed by option-1).
- The #359 rebase (above).
