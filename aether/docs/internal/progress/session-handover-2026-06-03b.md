# Session Handover — 2026-06-03b

**Branch:** `release-1.0.0-rc1` · **HEAD:** `8019627db` · **68 unpushed (DO NOT push)** · tree clean.

## TL;DR
Shipped **per-lane QUIC streams** (the original transport design intent), which **fixed the idle consensus-stream receive-wedge** and **named the real residual root**. Then attacked that root: a **backoff attempt was REFUTED** (Docker-regressed, reverted), and a **dedicated SYNC lane** was implemented as the correct fix — **unit-green but NOT yet Docker-validated** (next session's first task).

## Commits this session (all on `release-1.0.0-rc1`, unpushed)
| Commit | What | Status |
|--------|------|--------|
| `5226b3154` | **Phase 1** — polymorphic `Message.Wired.streamType()` replaces reflective stream routing; single serialization site (`writeToStream`); message-typed offline buffer (`Outbound` record removed; user-driven send-path reshape) | Docker-validated ✓ |
| `153ef0f80` | **Phase 2** — per-lane QUIC streams: dialer opens 7 lanes after CONTROL/Hello (open-all-before-attach), 1-byte stream-open preamble, acceptor `ServerStreamHandler` attributes each inbound stream to its lane via `PEER_CONNECTION` attr, shared `QuicLaneDataHandler`, per-lane backpressure logging | Docker-validated ✓ |
| `8019627db` | **#43 SYNC lane** — `SyncRequest`/`SyncResponse` moved off CONSENSUS to a dedicated `SYNC` lane (StreamType index 7, 8th lane) | **unit-green (581/581), NOT Docker-validated** |

## Validated outcome (Docker 02-chaos, 2026-06-03)
- **Formation gate (00-smoke): 13/13 green** — the 7→8-stream-per-peer handshake forms cold-start clusters perfectly. No regression.
- **Consensus-stream wedge FIXED** — READY/restore/re-election all converge in **0–6s** (was the 600s-READY-timeout that eroded the cluster). The per-lane split removed the cross-lane head-of-line blocking that made CONSENSUS backpressure fatal.
- **Self-drain quorum-loss (S19/S20) FIXED** — 7/0 (was BROKEN in prior handover): survivors `halt(2)` in 28s, clean restart-recovery.
- **Diagnostic delivered** — the per-lane backpressure logging pinned the residual precisely: stalled lane = **CONSENSUS only** (every other lane 0); root = a **far-behind replacement** (joins ~400 phases behind) that floods `SyncRequest` on CONSENSUS + has its `SyncResponse` HOL-blocked → catch-up slow → stays **SUSPECTED** → `ClusterGenerationProjector` → **DEGRADED** → `generation did not quiesce`.

## #43 — the replacement catch-up problem (IN PROGRESS)

**Confirmed root chain:** a CTM-auto-heal replacement joins far behind; today `SyncRequest`+`SyncResponse` both ride the **CONSENSUS** lane, so the `SyncResponse` needed to complete catch-up is HOL-blocked behind consensus round traffic + the joiner's own `SyncRequest` flood (one node showed **725× CONSENSUS backpressure** to a single peer). Catch-up stalls → SUSPECTED → DEGRADED → quiesce fails.

**REFUTED — do NOT retry (was reverted, never committed):** geometric backoff on `SyncRequest` *retries* (`BackoffStrategy.exponential`). Docker result: cut peak flood 725→320 **but REGRESSED READY (600s timeout, only 3/5 READY) and 5× generation churn (gen 1:200 → 1:1130)**. Reason: backing off re-requests slows catch-up *exactly when SyncResponses are being lost* — the flood is a **symptom** of sync-not-completing, not the cause. Throttling punishes the struggling case.

**CURRENT FIX (committed `8019627db`, needs validation): dedicated SYNC lane.** Move `SyncRequest`/`SyncResponse` to their own lane so the catch-up handshake is neither HOL-blocked by nor floods the consensus rounds. Request (outbound) and response (inbound) share the lane but use independent QUIC stream directions, so they don't block each other. No rate-throttling → none of the backoff's trap. Additive (8 ≪ 64 `INITIAL_MAX_STREAMS`), protocol unchanged.

### NEXT SESSION — validate the SYNC lane (first task)
1. `mvn -pl aether/node -am install -DskipTests` (rebuild shaded jar with `8019627db`).
2. Clean-slate remote: `cd aether/tests/integration && source lib/common.sh 2>/dev/null && remote_exec "docker ps -aq --filter name=aether-a-node --filter name=aether-b-node | xargs -r docker rm -f"` + `pgrep -fl run-tests.sh` orphan check.
3. `./run-tests.sh --env remote --suites 02 --skip-build --skip-teardown` (image-push does `docker build --no-cache` from the fresh jar).
4. **Compare vs baseline:** per-node CONSENSUS backpressure (was 725× peak) `docker logs <node> | grep -c 'Backpressure on .* lane CONSENSUS'`; **READY convergence must NOT hit 600s**; generation must NOT churn toward 1130.
5. **If it regresses like the backoff → `git revert 8019627db`** (it's unpushed, trivial). **If it improves** → close #43, full-suite RC1 run.
6. **Reserve (idea 2, only if SYNC lane insufficient):** accumulate-and-targeted-top-up — stop clearing `syncResponses` each retry in `doSynchronize` and re-request only from non-responders (NOT rate-backoff, NOT relaxing `syncQuorumSize`).

## Remaining SEPARATE residuals (pre-existing, NOT the consensus wedge)
- **Forwarding error-rate ~40% under kill-under-load** (was 49.75%): in-flight requests to a killed slice-owner error during the re-route window. Needs fast-fail/retry routing.
- **`pick_non_leader 1/2`** (kill-multiple FAIL): **harness-side** — doesn't classify ULID-named replacement nodes as eligible victims + a stale `UNKNOWN` membership-view ghost (killed old-leader) inflates the count. Confirmed not a runtime stall (cluster was quorate, 5/6 READY).
- **CTM generation-churn / 180s-quiesce** on auto-heal-after-kill restores (clean restart quiesces in ~1s — only the churn path misses). Likely the same replacement-catch-up phenomenon the SYNC lane targets.

## Verified facts (don't be misled)
- **Phase 2 has NO lane-attach regression**: acceptor `handshake-first ordering violated` (attr-null) = 0, preamble errors = 0 across all nodes. Lanes attach correctly. The `Attached … lane stream` log is **DEBUG** (DEBUG is OFF in containers) — its absence is NOT evidence of non-attach. `No stream available` WARNs target **just-killed nodes** (normal transient), not a stream-establishment failure. (An earlier investigator over-attributed "streams never come up" — refuted by these counts.)

## Infra notes
- Remote cluster (`$TARGET_HOST`) **torn down** at session end.
- ALWAYS clean-slate (`docker rm -f aether-a/b-node`) + `pgrep run-tests.sh` orphan-check before a run — survivors silently invalidate it.
- `--skip-build` skips `build.sh`; image-push (NOT `--skip-image-push`) copies `aether/node/target/aether-node.jar` to remote + `docker build --no-cache` → containers run the fresh jar.
- Plan file: `~/.claude/plans/sharded-tumbling-forest.md` (per-lane plan; Phase 1+2 done).
