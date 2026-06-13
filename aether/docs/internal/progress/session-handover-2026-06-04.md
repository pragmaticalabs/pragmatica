# Session Handover — 2026-06-04

**Branch:** `release-1.0.0-rc1` · **HEAD:** `a6fcf5feb` (+ this handover) · **10 functional commits this session, NOT pushed** (push pending user OK).

## TL;DR
The lone RC1 09-artifacts failure (≥1MB / cross-node artifact resolve) is **FIXED** and Docker-validated. Root was a 3-defect compound: storage silently dropped retried chunk writes, unbounded fan-out saturated the DHT QUIC lane, and the DHT lane **fast-fail-dropped responses to live-but-backpressured reader nodes**. Then a 6-way parallel investigation of every other failing suite found **1 real product bug** (drain disruption-budget over-drain → quorum collapse) + **4 harness/test-expectation issues** (all fixed) + **1 deep deferred bug** (consensus-stream wedge). Full-suite validation confirms every targeted fix landed; the stubborn remainder collapses to the single **deferred consensus-stream wedge** (drives 02-chaos + 12-network `No NODE_FAILED`), a pending **05-secure-mode** change, and a **03 scale-down** residual.

## Commits this session (on `release-1.0.0-rc1`, unpushed)
| Commit | What | Validated |
|--------|------|-----------|
| `5837b1c22` | **B** fix(storage): release block claim on failed write-through so retried put re-writes | ✅ 09 SHA-clean + cross-node probe |
| `2da76e927` | **C** fix(artifact): bound chunk fan-out (8) under DHT-lane backpressure watermark | ✅ (with B/A) |
| `baa0e5112` | **A** fix(quic): retry DHT-lane backpressure to live peer; fast-fail only on dead channel | ✅ cross-node all nodes/sizes <1s (was 30s) |
| `567bb8a1c` | docs: changelog + feature-catalog | — |
| `6fe17200d` | fix(lifecycle): drain budget counts in-flight drains + stable intended size — reject over-drain | unit ✅; Docker = ISOLATED RUN (see below) |
| `300d738a8` | fix(scheduled-tasks): stable response ordering (positional access deterministic) | ✅ 08-resources 4p/1f→5p/0f |
| `7684db709` | fix(harness): resolve slice-owner + container ports via `docker port` (ULID-safe) | ✅ 02-chaos retarget false-100% gone |
| `753a9455a` | fix(harness): seed_cluster_config reconciles coreMax from TOML | ✅ 03-scaling scale-up→7 works (1p/2f→2p/1f) |
| `f6cfc61cb` | test(12-network): S05 expects prompt eviction on dual-signal co-confirmation | ✅ S05 passes |
| `a6fcf5feb` | chore(lint): rebaseline harness R2 entries for S05 line shifts | ✅ lint gate green |

## The cross-node resolve root cause (B+C+A) — proven via instrumentation
Evidence (instrumented run, since reverted): reader sent 20 GetRequests, responders SERVED all 20, but **13 of 20 GetResponses were fast-fail-dropped** (`refuseBackpressured`) because the reader's DHT QUIC lane was momentarily non-writable — reader is alive (serving its own requests), just transiently backpressured. Blocks never reached read-quorum → 30s `operationTimeout`. **A** distinguishes live-but-backpressured (retry, like CONSENSUS) from dead (`!isActive` → fast-fail, preserving quorum fast-fail-on-unreachable). **B** (claim-release) was the deploy-side root: `StorageInstance.handlePut` claimed the block before write and never released on failure, so `ArtifactStore`'s retried chunk put short-circuited to `deduplicateBlock` (success-without-write) → block missing → resolve `Corrupted`. **C** caps fan-out at 8 (8×64KB < 1MB watermark). All three Docker-validated.

## Parallel investigation outcome (6 suites)
- **03-scaling** → harness/config: stale stored `coreMax=5` → scale POST 400 `InvalidCoreMax`; `seed_cluster_config` no-op'd the `max=15` TOML. **Fixed** (`753a9455a`).
- **02-chaos** kill-load → harness: `retarget_app_endpoint` parsed node index via `grep [0-9]+$`, broke on ULID replacement → dead endpoint → false 100%. **Fixed** (`7684db709`).
- **08-resources** → test + runtime: positional `tasks.0` over nondeterministic `ConcurrentHashMap` order. **Fixed** via stable sort (`300d738a8`).
- **05-security** → test-vs-env: suite asserts secure-mode (ADMIN auth + app-TLS) against a dev-mode cluster. **DECISION: provision secure-mode on cluster B** (NOT yet done — see Pending).
- **13-edge-cases** → **REAL product bug**: `NodeLifecycleRoutes.checkDisruptionBudget` counts DRAINING nodes as operational + recomputes intended size from the same shrinking set → admits over-drain → quorum-loss self-drain collapse. **Fixed** (`6fe17200d`): counts in-flight drains (leader `DrainCommandRegistry.drainTargets()`) + stable intended size.
- **12-network** → two real bugs: (a) **S05** gate-semantics — `LeaderReconciler` dual-signal co-confirmation evicts a confirmed node in ~3s, no TTL; **DECISION: fast co-confirmation is correct → fixed the test** (`f6cfc61cb`). (b) **SWIM-detect** = the deep consensus-stream wedge — DEFERRED.

## Validation #1 full status (B+C+A+13+08+harness, NO 05-secure-mode)
10/15 suite-pass; composition materially improved vs session start:
- Green: 00, 07, **09** (goal), **08** (fixed), 04, 10, 06, 14, 15, 11.
- 02-chaos 5p/1f — retarget fixed; residual = deferred consensus-wedge (`No NODE_FAILED` for ULID replacement).
- 03-scaling 2p/1f — coreMax fixed (scale-up→7); residual = scale-DOWN (74% error rate + marker 404): forwarding/durability under scale-down (NEW residual, not yet investigated).
- 05-security 1p/2f — unchanged (secure-mode pending).
- 12-network 2p/2f — S05 fixed; residual = deferred consensus-wedge + known QUIC-reconnect (`connectedPeerCount=2`, no recovery to 5).
- 13-edge-cases 0p/3f — **CONTAMINATED**: inherited 12-network's degraded cluster B (consensus-wedge left it at 4 cores / connectedPeer=2); 13 ran 69 min of stacked timeouts. NOT a valid test of the drain-budget fix → isolated re-run (below).

### Isolated 13 re-run result
### Isolated 13 re-run result — drain-budget fix VALIDATED ✅
`./run-tests.sh --env remote --suites 13 --skip-build` on a fresh cluster B: **2p/1f in 93s** (vs 0p/3f / 69min contaminated). The drain-budget fix works exactly as intended:
- First drain → 200 (allowed); Second drain → 200 (allowed within budget).
- **Third drain → HTTP 409** `"Disruption budget exceeded: draining aether-b-node-3 would leave 2 operational nodes, minimum is 3"` — over-drain REJECTED → **no quorum-loss cascade** (cluster stayed healthy; 93s vs 69min proves no collapse).
- The runtime bug `6fe17200d` is fixed and Docker-proven.

The remaining 1f (`Third_drain_rejected_budget`) is a **TEST-CAPTURE bug, not the runtime**: the `api_post` harness helper logs a `[WARN]` line on any non-2xx, and that WARN text pollutes the test's captured `status` variable, so the assertion compares `"[WARN] http 409 …"` against `"409"` and false-fails on a *correct* 409. **Follow-up (small):** in `suites/13-edge-cases/test-disruption-budget.sh`, capture the bare HTTP status for the expected-409 path (don't let the helper's non-2xx WARN leak into the status var). Product behavior is correct.

## THE key remaining root: consensus-stream wedge (DEFERRED — its own session)
A CTM-provisioned replacement peer's **CONSENSUS QUIC stream never establishes** (storms of `CONSENSUS stream backpressured or inactive` / `No stream available` — 840/336 lines observed) → the peer never becomes consensus-confirmed → SWIM FAULTY never converts to `MembershipDecision.NodeRemoved` → **`NODE_FAILED` is never emitted**. This is the shared root behind 02-chaos `No NODE_FAILED`, 12-network `No NODE_FAILED` + failed recovery, and likely the CTM gen-churn residuals. Same family as the per-lane QUIC work in prior sessions (06-03b) but about stream **establishment** for fresh peers, not just backpressure. High blast radius (touches `QuicClusterNetwork` — A's file). `NODE_FAILED` is published only via consensus-written `MembershipDecision` (`AetherNode.java:2974-2977`), NOT off the SWIM FAULTY edge — a candidate narrower fix is to emit an observable departure event when a never-confirmed peer is evicted from NTT.

## Pending work (next session)
1. **05-secure-mode on cluster B** (DECISION made, not implemented): provision API keys + app-HTTP TLS on `docker-compose-b.yml`, drop the dev-mode auth bypass for the destructive cluster. **Broad** — affects ALL cluster-B suites (02/03/12/13); needs its own full validation. Harness already passes `X-API-Key` + has `cli_tls_flag`. The 2 latent reporting bugs are NOT on the passing path once secure-mode is on, but worth fixing: `renewalStatus`(transport-cert)/`tlsEnabled`(app-TLS) can contradict (`StatusRoutes`), and test/enum string drift `RENEWAL_FAILED` vs `FAILED`.
2. **Consensus-stream wedge** — the deep one; own session with instrumentation (per-stream establishment/flow-control logging on fresh peers).
3. **03 scale-down residual** — 74% error rate + marker 404 after scale-down under load; not yet investigated (forwarding/durability during membership shrink).
4. **Push** the 10 commits when ready (currently unpushed per session default).

## Infra / learnings
- **Worktree stale-base gotcha (cost time this session):** `Agent isolation:worktree` branches from `origin/<default-branch>` = `origin/main` = stale `1.0.0-alpha`, far behind `release-1.0.0-rc1`. 3/3 worktree agents got the alpha base. ALWAYS instruct worktree agents to reset to `release-1.0.0-rc1` HEAD, or run in the main tree on rc1 HEAD when file sets don't collide. Memory updated (`feedback_worktree_isolation_pattern`).
- **Shared cluster-B contamination:** destructive suites (02/03/12/13) share cluster B sequentially; a suite that leaves B degraded (the consensus-wedge in 12) cascades into the next (13). Validate suspect suites in isolation.
- **Harness lint gate:** editing a linted harness `.sh` shifts line numbers → `lint-baseline.txt` (R2 silent-stderr pattern) must be rebaselined or `run-tests.sh` aborts pre-suite.
- ALWAYS `docker rm -f aether-a/b-node` + `pgrep run-tests.sh` before runs. Remote cluster torn down at session end.
- Cross-node investigation evidence preserved: `/tmp/aether-evidence-bca-085320/`.
