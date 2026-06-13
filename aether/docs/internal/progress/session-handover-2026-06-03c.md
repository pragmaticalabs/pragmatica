# Session Handover — 2026-06-03c

**Branch:** `release-1.0.0-rc1` · **HEAD:** `725444a15` (+ this handover/memory commit) · **pushed to origin this session.**

## TL;DR
The lone persistent **02-chaos `pick_non_leader 1/2`** failure (kill-multiple) is **FIXED** — root cause was a **one-line harness bug** (ssh consuming a `while read` loop's stdin), not the runtime. **02-chaos: 6p/0f** (standalone *and* in the full suite). Full-suite regression: **10/15 suites pass**; the 5 failures are all in subsystems **outside this session's changed code** (artifact-store, CTM scale-up, TLS/security, ReachabilityGate/QUIC-partition, drain endpoint) — characterized below, no evidence any is a regression.

Along the way: a **real SWIM bug** (qd9-class premature FAULTY-evict of a healthy replacement) was found + fixed (committed, efficacy-unvalidated as the wedge didn't recur), and a **readiness any-node-queryability** improvement shipped. Both are orthogonal to the pick fix.

## The actual root cause (after THREE mis-targeted fixes)
`pick_non_leader` (`lib/cluster.sh`) enumerated READY candidates with:
```bash
while IFS= read -r candidate; do
    ... remote_exec "docker ps ... ${candidate}" ...   # ssh
done <<< "$current_members"
```
**`remote_exec` (ssh) inside the loop consumed the loop's stdin (the here-string)** — after the *first* candidate's liveness check, ssh had eaten the rest, `read` hit EOF, loop exited with `attempt_found=1`. So the picker **always evaluated 1 of N candidates**, regardless of cluster health → count=1 picks (kill-node) passed; **count=2 (kill-multiple) always returned `1/2`**.

**Proof:** added a DIAG line to the picker's failure path; it showed `rawReady=4` (four READY non-leaders existed) but only **one** candidate disposition logged. Unambiguous.

**Fix (commit `725444a15`):** read on **FD 3** so ssh's stdin can't reach the list:
```bash
while IFS= read -r candidate <&3; do ... done 3<<< "$current_members"
```
Same fix applied to **`cleanup_cluster_zombies`** (it had the identical bug — removed only 1 zombie per call; this is very likely the long-standing "cleanup_cluster_zombies leaves survivors that invalidate runs" issue in memory). DIAG logging kept (proved its worth; harmless additive logging).

**Why three prior fixes failed *identically* (`1/2` every time):** none touched this loop.
1. Timeout bump 120→240s (REVERTED `607804ff6`) — not latency; also its QUIESCED gate false-positived on `rc=7`.
2. Readiness-cache + 503 (`f630bf407`) — real improvement, orthogonal.
3. SWIM join-grace (`98b156cee`) — real bug fixed, orthogonal.

## Commits this session (all on `release-1.0.0-rc1`)
| Commit | What | Status |
|--------|------|--------|
| `607804ff6` | **revert** of the bad harness tuning (240s bump + QUIESCED gate that regressed 02-chaos 5p/1f→3p/3f via `rc=7` false-positives) | done |
| `f630bf407` | **readiness any-node-queryability**: leader broadcasts its readiness view on `ClusterSyncPing`; followers cache (leader+term-gated, TTL=3 ping intervals) + serve it; `NodeLifecycleRoutes` returns 503+leader-hint when not authoritative & cache cold | real improvement, orthogonal to pick |
| `98b156cee` | **SWIM NORMAL-phase join-grace (5s)**: suppress FAULTY/tombstone for a just-joined never-HEALTHY member until its first-probe window elapses; prevents qd9-class premature eviction. 108 SWIM tests pass; wired (5s) to the node via `SwimConfig.fromTimeouts` | **efficacy UNVALIDATED** — the wedge didn't recur in validation runs |
| `725444a15` | **THE pick fix**: FD-3 read in `pick_non_leader` + `cleanup_cluster_zombies`; + picker DIAG | **Docker-validated: 02-chaos 6p/0f** |

## The qd9-class SWIM bug (real, fixed, efficacy-pending)
A CTM auto-heal replacement that doesn't land its first SWIM probe-ack within ~3.7s of joining was FAULTY-evicted by the leader (`SwimProtocol.emitFaultyOrUnknown` "never-HEALTHY peer … cold-boot suppression bypassed" — cold-boot protection only covered COLD_BOOT phase, not a NORMAL-phase join) → REMOVE → QUIC churn → perpetual SUSPECT↔refute, never HEALTHY, never in the leader's readiness view, never READY. Observed live: `qd9` (a member, docker-healthy 26+ min) absent from the readiness view entirely; 20 refutes, 0 probe-acks. **NOT** #43/SYNC-lane (qd9's Rabia sync completed, phase 459). Family: 06-02 `tombstoneOnFaultyEdge` / never-HEALTHY-victim, now on a *live joining* replacement. Fix = join-grace (`98b156cee`). **It did not recur in the validation runs, so the fix's efficacy is unproven — confirm opportunistically when a churned replacement next stalls.** Evidence preserved: `/tmp/aether-qd9-evidence/` (qd9 + leader + sibling container logs).

## Full-suite regression — 10/15 (the 5 failures are pre-existing / separate)
| Suite | Result | Cause | In my changed paths? |
|---|---|---|---|
| 02-chaos | **6p/0f** ✓ | picker fixed; SWIM/readiness active throughout | YES — validated clean |
| 00/04/06/07/08/10/11/14/15 | all green ✓ | cluster-A core | — |
| 09-artifacts | 2p/1f | ≥1MB artifact **resolve → 500** (artifact-store size limit; 64KB/128KB pass) | No |
| 03-scaling | 1p/2f | scale-up 5→7 **stuck at 5** (CTM provisioning never brings up the 2 new nodes) → scale-down/no-data-loss cascade | No |
| 05-security | 1p/2f | TLS `renewalStatus=HEALTHY` (expected NOT_CONFIGURED) + admin `whoami` returns anonymous/VIEWER (dev-mode auth bypass, `AETHER_INSECURE_DEV_MODE`) | No |
| 12-network | 2p/2f | **S05** partition-gate didn't hold (node-3 — a HEALTHY seed — removed in 2s; `ReachabilityGate.isConfirmedUnreachable`); after reconnect, **QUIC didn't fully reconnect** (`connectedPeerCount=3`, 4+ READY timed out 600s) | No — no 503 signatures; HEALTHY-node path (grace never applies); transport/gate untouched |
| 13-edge-cases | 1p/2f | **drain** endpoint 500 `"Node lifecycle not found"` + `kill_node` on an already-replaced container (harness stale name) | No — drain route, not the LIST route edited |

**Caveat:** no clean pre-session baseline was captured this session, so attribution of the 5 is by **code-path analysis** (strong: none in changed files; no 503s; symptoms in artifact-store/CTM-scaling/TLS/ReachabilityGate/QUIC-reconnect/drain) + consistency with the historical ~150P/83F baseline. A baseline run on pre-session HEAD would make it definitive.

## Follow-up work items (separate, pre-existing — candidates for RC1/RC2 triage)
1. **03-scaling**: CTM scale-up 5→7 doesn't provision/admit the 2 new nodes (stuck at 5, 300s). Highest-value (scaling is core).
2. **12-network**: (a) `ReachabilityGate` partition-gate doesn't hold the 5s window (S05 false-decommission); (b) QUIC reconnection after a docker-network partition doesn't restore full connectivity (`connectedPeer=3`). Transport/gate area — same family as the prior dual-dial/single-dialer work.
3. **09-artifacts**: artifact resolve returns 500 for ≥1MB artifacts (size threshold in the artifact store).
4. **13-edge-cases**: drain endpoint 500s with `"Node lifecycle not found"` for decommissioned/replaced nodes; harness kills stale container names.
5. **05-security**: dev-mode auth bypass makes admin-identity + TLS-status assertions fail (test-expectation vs `AETHER_INSECURE_DEV_MODE`).
6. **#44 quiesce-on-restore**: `restore_cluster_baseline: generation did not quiesce within 180s` recurs as inter-suite *cleanup* noise (non-counted) — CTM-churn/replacement-readiness on the restore path, amplified by full-suite sequencing. Not a test FAIL but worth closing.
7. **readiness-cache 503-on-cold**: keep an eye on it for no-failover consumers (it showed no harm this session — zero 503 signatures in the failing suites — but it's a behavior change).

## Key learnings (for next session)
- **Instrument before fixing.** Three blind fixes failed identically; one DIAG line (`rawReady` + per-candidate disposition) nailed the root in one run. When a number won't move across orthogonal fixes, stop guessing and log the inputs.
- **In-shell harness reproduction is contaminated.** `_refresh_mgmt_entry_point`/`api_get` cross-resolve to cluster A (`:515x`) or return empty outside the full test driver — don't trust manual picker repro; use the real run + instrumentation.
- **Correlation ≠ causation.** The qd9 SWIM wedge was real but *not* the pick failure (pick failed even when the wedge didn't occur). Verify the suspected cause is actually causal.
- **`ssh` in a `while read … <<<` loop eats the loop's stdin** — use FD 3 (`<&3` / `3<<<`) or `ssh -n`. The class still lurks in any harness loop with `remote_exec` inside (scan added).

## Infra
- Remote cluster (`$TARGET_HOST`) **torn down** at session end. Evidence: `/tmp/aether-qd9-evidence/`.
- ALWAYS `docker rm -f aether-a/b-node` + `pgrep run-tests.sh` orphan-check before runs. The `cleanup_cluster_zombies` 1-per-call bug (now fixed) was a survivor source.
- Harness changes are shell-only (no jar rebuild); use `--skip-build --skip-image-push` to reuse the remote image, or drop `--skip-image-push` to rebuild from the local jar for a definitive run.
