# Session Handover — 2026-07-08

**Branch:** `release-1.0.0-rc2` · **HEAD:** `71a4aa599` (pushed, tree clean, `v1.0.0-rc2-candidate` on HEAD) · **⚠ Gate run 4 IN FLIGHT at handover** — see "Live state" below; its outcome is the next session's first input.

## TL;DR

Gate-driven hardening arc. Run 3a (remote containerized, 15 suites): 10/15 green, **every red root-caused by paired forensics agents and either fixed or filed**. Fixed this session: #415 (cloud-proven), mgmt stream publish owner-routing + CNFE mapping + rc2 fixtures (the run-2 debt), **two new product streaming bugs** (read-recovery + write-race), and two harness defects. Filed: **#420** (real artifact-DHT data loss under churn — design fork prepared, **milestone decision OPEN**) and **#421** (unreproduced ring-vanish anomaly). **Run 4 is executing on the fully-fixed build right now.** After its verdict: #421 repro + 1f fence probe + reap, then the open user decisions, then release cut.

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

## Live state at handover

- **Run 4 in flight**: `env -u HCLOUD_TOKEN ./run-tests.sh --env remote --skip-build --skip-teardown`, launched after remote reap (0 containers) + fresh jar (Jul 8 14:39, contains both streaming fixes). Log: session scratchpad `cloud-gate-run4.log`; background task `bzrdxbbs2`, monitor `bumgn2c67`. Deploy step had just started. **Expected**: 04/08/13 flip green; 02-chaos preconditions settle; kill-under-load either measures a real endpoint or fails loudly WITH the `/api/slices` diagnostic (answering the open "why was it empty" question); 03-scaling No_data_loss is probabilistic (#420 — a red there is NOT new information). If the session died mid-run: `aether/tests/integration/test-results.json` + the log are authoritative; clusters left up (`--skip-teardown`).
- **Next after run 4** (in order): (1) #421 repro on the live fixed cluster (protocol on issue); (2) **manual 1f fence probe** — `aether cluster ownership` during an owner-kill: deposed node `fenced=true`, steady `highWater==epoch`; (3) reap remote (`docker rm -f $(docker ps -aq)` on `$TARGET_HOST`).

## ⭐ Open user decisions (do not proceed without)

1. **#420 milestone** (recommendation above).
2. **Cloud-JVM run 3b** before release cut? Plan agreed earlier: `--env cloud --runtime jvm` (TOMLs exist: `env/cloud-hetzner-jvm*.toml`, selector `--runtime`), fresh Hetzner VMs, `--skip-teardown` + cluster-scoped reap + preserve test-PG. First-ever JVM-flavor 15-suite run — treat first failures as probable env-debt, not product.
3. **Streaming coverage gaps** (user asked for assessment; delivered): top-3 pre-GA = multi-partition e2e fixture (everything runs partitions=1), publish-under-load-during-reshuffle chaos test, hard-kill (`kill -9`) crash-durability e2e (current proof is graceful-restart only). Offered to file as rc3/GA issues — awaiting go-ahead.
4. Release cut when gate verdict accepted: `/pre-release-check` → `/release` (closes #403).

## Working notes for the next session

- **Coder/investigator stall pattern**: agents go idle without delivering; a `SendMessage` nudge wakes them (harness coder produced ALL its work only after the nudge — check `git status` for actual product before assuming failure; NEVER start parallel edits until the tree is checked).
- App publish path deliberately has NO forwarder-side retry (gets owner-side materialization only; config commits at slice activation) — follow-up candidate, noted in `0c18d8f6b`.
- One origin rebase mid-session: `bbfbd0259` (docs: resilience principles P1-P7, Editor drop, no overlap). MAILBOX monitor was re-armed this session — **re-arm next session** (memory `project_gap_drain_loop_mailbox`).
- Run-2/3a evidence: this session's scratchpad (`cloud-gate-run3.log`, `cloud-gate-run4.log`); durable diagnoses live in #415/#420/#421 + this file.
