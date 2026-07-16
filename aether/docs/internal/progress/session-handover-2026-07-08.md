# Session Handover — 2026-07-08

**Branch:** `release-1.0.0-rc2` · **HEAD:** `4fb6c5774` + this handover update (pushed, tree clean, `v1.0.0-rc2-candidate` current) · **Cloud: FULLY REAPED** (0 containers on `$TARGET_HOST`; test-PG VM untouched).

## TL;DR

Gate-driven hardening arc, COMPLETE through run 4. Run 3a: 10/15, every red root-caused → fixed or filed. **Run 4 (fixed build): 14/15 suites green — 03-scaling, 04-streaming, 06-deployment, 08-resources, 13-edge-cases ALL green; both streaming fixes + #415 + the fixture/CNFE class are cloud-validated.** Sole red: 02-chaos 4p/3f — forensics verdict: **all three failures HARNESS-side; the product was exonerated everywhere observable** (S01: product removed the node in ~18s; the 21-min reading was a wedged harness poll — #426 rewritten as the harness-fix tracker). One MEDIUM-confidence residual: isolated kill-node re-run to positively exclude a leader-wedge. Cloud reaped. **Release-gating now hinges on the open decisions below, not on unknown failures.**

## Commits this session (oldest first)

| Commit | What |
|---|---|
| `21180fd02` | owner-route mgmt stream publish/read via new `StreamWriteRouter` (run-2 root cause 1) |
| `d200bd533` | CNFE during factory resolution → named rebuild-together cause (run-2 root cause 2) |
| `736da90c3` | examples → rc2 platform, v2 `[click-events]` restored (run-2 root cause 2b) |
| `7ae948084` | **#415 fix**: re-arm cold-boot-suppressed quorum-loss drain intent (closed; S19 cloud proof in run 3a: both survivors exited 36s/45s budget) |
| `afb4a77ea` | formatter chore (post-build.sh) |
| `0438318cf` | **streaming bug A**: recover typed `PARTITION_NOT_LOCAL` local reads into owner-forward (GOVERNOR + NEAREST arms; metadata-only-node mgmt reads returned 500) |
| `0c18d8f6b` | **streaming bug B**: write-forward vs config-apply race on fresh streams — owner-side lazy materialization from committed `StreamConfigKey` + bounded (3×150ms) retry gated on new retryable wire flag (`PublishForwardResponse.retryable`, internal forward protocol, rebuild-together, envelope stays 1007) |
| `71a4aa599` | **harness**: poll-with-settle for all 02-chaos `Initial:` count preconditions + loud abort (no more dead-port "100% error rate") |

All verified before commit: I re-checked every load-bearing claim in code (enum-identity recovery, `everQuorate` latch, DHT mechanisms, baseline line-shifts). Module tests: aether-stream 614/0, node 681/0 (+ earlier full pass 2721/0). `build.sh` green; fixtures Envelope 1007.

## Gate run 3a scoreboard (HEAD `afb4a77ea`, remote `$TARGET_HOST`)

PASS: 00, 03→(see below), 05, 07, 09, 10, 11, 12, 14, 15. **06-deployment flipped GREEN** (CNFE+fixtures fix confirmed). **02-chaos S19 GREEN — #415 cloud-proven.**
FAIL→forensicated: 04 (3p/1f), 08 (4p/1f), 13 (3p/1f) = the two streaming bugs (now fixed); 03-scaling 2p/1f = **#420**; 02-chaos 5p/2f = harness race + harness dead-port (now fixed).

## Open product issues (rc-relevant)

- **#420 artifact-DHT durability (REAL data loss, cloud-proven)** — marker 404 on all 5 nodes after 5→7→5 churn; mechanisms verified: no join-migration (`DHTTopologyListener.onNodeJoined`), survivor-primary-only rebalance (`DHTRebalancer.onNodeRemoved`), anti-entropy exists but current-replica-set-only + can't resurrect 0 copies, no read-repair/hinted-handoff, chunk amplification (one 64KB chunk kills the artifact). **Scope is a CLASS**: artifacts (memory+DHT ONLY), content-blocks (same), stream-segments (memory+disk+DHT — DHT is the node-loss recovery path). FULL-pin is NOT safe (fire-and-forget, anti-entropy disabled, no eviction anywhere). Design fork on the issue: A = durable blob tier (consensus placement + reconciler, pattern exists in stream ownership, nothing pre-built), B = complete DHT machinery, C = layered (C1 departing-push kills the observed mode, C2 resolve-time alternate-target fallback, C3 join backfill). **My standing recommendation: rc2 ships with documented caveat; C1+C2 open rc3; A-vs-B design session gates GA. User chose "design talk first" — the fork analysis was delivered in-session; MILESTONE DECISION STILL OPEN.**
- **#421 ring-vanish anomaly (unverified)** — probe stream's owner stored 24 events then ring gone (~1min, headOffset:-1, no release log); suspect = double-materialization via bug-B's raced first publish; repro protocol on the issue — run it on the FIXED build (next session).

## Run 4 final verdict (fixed build: jar w/ both streaming fixes + harness fixes)

`test-results.json`: 14/15 passed. 00:2/0, 02:**4/3**, 03:3/0, 04:4/0, 05:3/0, 06:5/0, 07:4/0, 08:5/0, 09:3/0, 10:3/0, 11:6/0, 12:4/0, 13:4/0, 14:2/0, 15:1/0. Log: session scratchpad `cloud-gate-run4.log` (session-scoped — durable facts are here + in the issues).

**The three 02-chaos failures — FINAL verdicts (forensics report landed post-reap; container evidence captured pre-recycle; all four cited harness code sites verified verbatim by the lead):**
1. **S01 "removal latency" → HARNESS (#426, rewritten).** The PRODUCT removed the killed-in-SYNCING replacement in **~18s** (SUSPECT +4.7s → first-hand FAULTY accepted, NO transport-veto line → membership purge +18.7s) — well inside the 90s budget. The 1293s was `wait_for_node_removed` wedging inside one blocking read AND `kv_lifecycle_state` treating transport failure as "removed" (verified: `api_get ... || true` → empty → return 0; deadline only checked between iterations). Transport-veto/#415-family hypothesis REFUTED. #426 is now the harness-fix tracker (5-item fix list on the issue).
2. **Auto-heal_restores_to_5 count=0 → HARNESS-leaning, MEDIUM confidence.** Endpoint-resolution failure over the all-ULID replaced cluster (count=0 ≠ "stuck at 4"; recovery landed in 10s on seed ports). Honest residual: a leader-wedge during 14:05–14:11 is NOT positively excluded (window lost to recycling) — **re-run test-kill-node in isolation with the leader window captured before sign-off** (on #426's list).
3. **Kill_node_during_active_load loud-abort → HARNESS re-baseline gap; #420 chain REFUTED for this case.** Zero DEPLOYMENT_FAILED/artifact-not-found lines suite-wide; failure 2's own `restart_all_nodes` recovery left KV empty ("No cluster configuration stored") and nothing redeployed echo. S20's re-upload went ACTIVE in 0s — deploy path healthy. The new loud-abort + diagnostic worked exactly as designed.

**Net: run 4 found ZERO confirmed product defects.** The gate's product verdict is 14/15-green-equivalent with one MEDIUM-confidence residual (the isolated kill-node re-run).

## Cleanup state (this session's end)

- Remote `$TARGET_HOST`: **all containers removed** (clusters A+B, forge-postgres) — `docker ps -aq` = 0. Images retained for fast redeploy. Test-PG VM untouched (cloud discipline).
- Run-4 log monitor stopped. MAILBOX mtime monitor was session-scoped (dies with session) — **re-arm next session**.
- NOT done (needs a live cluster — fold into the next deploy/run): **#421 repro** (protocol on issue) and the **manual 1f fence probe** (`aether cluster ownership` during owner-kill: deposed `fenced=true`, steady `highWater==epoch`).

## ⭐ Open user decisions (do not proceed without)

1. **#420 milestone** (recommendation above). User chose "design talk first"; the fork analysis was delivered in-conversation and is summarized on the issue.
2. **#426 disposition — now a HARNESS-fix work item, not a product decision**: 5-item fix list on the issue (wall-clock ceiling, transport-vs-404, contradictory PASS, endpoint hardening, rebaseline-after-destructive-recovery) + the one residual product check: **isolated test-kill-node re-run with the leader window captured** (positively exclude a leader-wedge). Small, next-session.
3. **Cloud-JVM run 3b** before release cut? (`--env cloud --runtime jvm`, TOMLs exist, first-ever JVM-flavor 15-suite run — treat first failures as probable env-debt.) Also the natural vehicle for the pending #421 repro + 1f fence probe if run on containers first.
4. **Streaming coverage gaps** (assessment delivered): top-3 pre-GA = multi-partition e2e fixture, publish-under-load-during-reshuffle chaos test, hard-kill crash-durability e2e. Awaiting go-ahead to file as rc3/GA issues.
5. Release cut when gate verdict accepted: `/pre-release-check` → `/release` (closes #403). Run-4 posture: 14/15 with the sole red fully triaged (#426 + one recovered budget miss + one working-as-designed abort) — arguably cut-ready pending decisions 1-3.

## ⭐ Next session — exact sequence

1. Re-arm MAILBOX monitor (memory `project_gap_drain_loop_mailbox`).
2. Resolve decisions 1-3 with the user (one at a time).
3. If #426 investigate-first: jbct-coder/investigator on the in-JVM repro (plan on issue) — one code track.
4. If run 3b approved: `cd aether/tests/integration && ./run-tests.sh --env cloud --runtime jvm --skip-teardown` (HCLOUD_TOKEN needed by bootstrap — do NOT strip it there, but NEVER run maven with it set; use --skip-build). Cluster-scoped reap after; preserve test-PG. Fold in #421 repro + 1f fence probe (containerized remote redeploy also works for those two).
5. `/pre-release-check` → `/release`.

## Working notes for the next session

- **Coder/investigator stall pattern**: agents go idle without delivering; a `SendMessage` nudge wakes them (harness coder produced ALL its work only after the nudge — check `git status` for actual product before assuming failure; NEVER start parallel edits until the tree is checked).
- App publish path deliberately has NO forwarder-side retry (gets owner-side materialization only; config commits at slice activation) — follow-up candidate, noted in `0c18d8f6b`.
- One origin rebase mid-session: `bbfbd0259` (docs: resilience principles P1-P7, Editor drop, no overlap). MAILBOX monitor was re-armed this session — **re-arm next session** (memory `project_gap_drain_loop_mailbox`).
- Run-2/3a evidence: this session's scratchpad (`cloud-gate-run3.log`, `cloud-gate-run4.log`); durable diagnoses live in #415/#420/#421 + this file.
