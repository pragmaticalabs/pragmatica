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

## UPDATE — SYNC lane DOCKER-VALIDATED (same session)
Ran `02-chaos --skip-build --skip-teardown` with `8019627db`. **The SYNC lane works — keep it.**
- **Flood ELIMINATED (not relocated):** per-node CONSENSUS backpressure peak **725 → 36**; SYNC lane = **0** everywhere. Mechanism confirmed: dedicated SYNC lane removes the HOL → `SyncResponse` arrives → catch-up completes → joiner stops re-broadcasting `SyncRequest` → flood evaporates.
- **02-chaos 4p/2f → 5p/1f**: fixed **kill-under-load error rate 39.95% → 0.00%**. No 600s READY, no 5× churn (the backoff's regression). Lone FAIL = harness `pick_non_leader` (kill-multiple).
- **Still open (separate, present in baseline too — NOT the consensus flood):** (1) `pick_non_leader 1/2` harness classification (ULID replacements + stale UNKNOWN ghost); (2) `generation did not quiesce within 180s` on auto-heal-after-kill restores — now isolated to **SWIM-readiness/CTM-churn** (consensus catch-up is fixed), the next #43-adjacent target.

## UPDATE 2026-06-03c — both residuals investigated; SHARED ROOT found; ULID/UNKNOWN framing REFUTED; harness tuned (`301108bdb`)

Two parallel `aether-investigator` passes (read-only, code-evidence). **Both residuals share ONE root: a fresh-ULID auto-heal replacement is slow to reach READY/HEALTHY, and both assertions were tighter than that legitimate latency.** Neither is the bug this doc described above.

**`pick_non_leader 1/2` — the ULID-classification + stale-UNKNOWN-ghost framing (above) is REFUTED with code evidence (it had already been corrected in the 06-02 handovers; 06-03b regressed to the wrong story):**
- ULID-classification FALSE: the picker does ZERO name-pattern matching — it iterates raw `nodeId` strings from `nodes lifecycle --state READY` and filters only `==leader` (`cluster.sh:349`), `==pinned` (`:350`, empty in docker), and a docker-ps liveness guard (`:357`). A `aether-<cluster>-node-<ulid>` name passes identically to a seed.
- Stale-UNKNOWN-ghost FALSE: `NodeReportedState` has no `UNKNOWN` member; `UNKNOWN` is a display-only string on `/api/nodes/status` (`StatusRoutes.java:185`) — a DIFFERENT endpoint than the lifecycle one the picker uses, and the `--state READY` server filter drops it anyway. Killed nodes are evicted from the readiness map on `PeerDisconnected` (`AetherNode.java:1651`) + time-swept, so they cannot inflate the count.
- REAL cause: a **readiness-latency undercount**. `pick_non_leader 2` (kill-multiple:33) needs TWO simultaneously-`READY` non-leaders; the readiness source `reportedStates()` is **leader-only-gated** (`ClusterSyncPongSignalFan.java:143` `if (!leaderManager.isLeader()) return`), so a re-election empties the new leader's map and it rebuilds via pongs while a fresh replacement is still `SYNCING→READY` → 1 candidate < 2 past the 120s budget. Single-kill (count=1) is unaffected.

**#44 generation-not-quiesce — confirmed SWIM-readiness LATENCY, not starvation, not consensus.** The 180s clock gates on the replacement reaching SWIM HEALTHY: provision-fresh-ULID → boot → QUIC connect → SWIM SUSPECT-on-arrival→ALIVE probe-ack (`SwimProtocol.java:274`) → `HealthyObserved` → ×2 `upHysteresis` NTT ticks (`NodeTopologyTracker.java:282,328`) → admitted to `stableMembers` → counted by `cluster_member_count` (`cluster.sh:43`, snapshot member set incl. JOINING). After-kill ≠ cold-restart because cold-restart has **no provisioning step** (~1s warm-seed parallel ack) — so "1s clean vs >180s after-kill" was never a fair comparator. Edge-only SWIM emission (`emitIfEdge`) proves a stable SUSPECT does NOT perpetually re-arm → no steady-state starvation; the only starvation path is the residual #231 SUSPECT↔FAULTY flap, which `tombstoneOnFaultyEdge` (`:540`) already closes for the common case. Secondary gate: `ClusterGenerationProjector.java:334` DEGRADED-on-SUSPECTED — while admitted-but-SUSPECTED, the live SUSPECTED hint pins quiescence to DEGRADED.

**FIX APPLIED (chosen path = harness tuning, NOT a runtime change), commit `301108bdb`:**
1. `test_auto_heal` in `test-kill-node.sh` + `test-kill-under-load.sh`: count barrier 180→**240s**, then a SECOND **semantic** gate `await_generation_quiesced … current 120` — because count alone is satisfied by an admitted-but-SUSPECTED replacement; QUIESCED requires every member actually HEALTHY. (kill-multiple already rode 240s, untouched.)
2. `pick_non_leader` budget now **scales with `count`**: default `120 * count` (count=1 keeps 120s; count=2 → 240s, matching the suite quiesce budget); explicit `PICK_NON_LEADER_TIMEOUT` override still wins; deadline-passed log reports the actual `budget`.

**Deferred (optional, NOT done — real but non-fatal):** runtime latency reduction — explicit SUSPECT→ALIVE probe on fresh QUIC connect (vs waiting for round-robin), and/or lower `upHysteresis` for a node that arrives already SWIM-HEALTHY (`NodeTopologyTracker` comment :54 notes the asymmetry is intentionally conservative). NOT a never-converges bug → not blocking.

**NOT YET DOCKER-VALIDATED.** The tuning is syntax-checked (`bash -n` clean) and committed; the user chose "bump now" over a confirming `--skip-teardown` repro. Next run should confirm 02-chaos `5p/1f → 6p/0f` and that the QUIESCED gate doesn't expose an admitted-but-never-HEALTHY tail (if it does, that flips to the deferred runtime fix).

> **SUPERSEDED 2026-06-03d — the tuning above was REVERTED (`607804ff6`).** Docker 02-chaos proved it wrong on BOTH counts: `pick_non_leader` failed *identically* at 240s as at 120s (so NOT latency), and the QUIESCED gate *regressed* kill-node/kill-under-load via false positives (`await_generation_quiesced` returns 1 on `rc=7` endpoint-unreachable, indistinguishable from real non-quiescence) — taking 02-chaos `5p/1f → 3p/3f`. **Real root (live-confirmed): lifecycle/readiness is leader-only-served (`ClusterSyncPongSignalFan:143` `fanIfLeader`); a non-leader returns `200 []` on `/api/nodes/lifecycle`.** The picker fetches via `aether_failover`, lands on a non-leader, undercounts → empty-victim kill cascade → churn → `rc=7` on `CLUSTER_ENDPOINT`. All 3 fails are ONE cascade headed by the `200 []` bug. The generation snapshot canNOT carry readiness (`GenerationSnapshotPublisher:257`). FIX (task #45): `503`+leader-hint on non-authoritative read endpoints (necessary core, fixes failover) + leader-broadcast readiness view cached on followers (capability layer). See the next session handover for the implementation.

