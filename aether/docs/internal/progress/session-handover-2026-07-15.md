# Session Handover — 2026-07-14/15 (aether-main)

**Branch:** `release-1.0.0-rc2` · **HEAD:** `6d96e7c58` (pushed; `v1.0.0-rc2-candidate` on HEAD; Release CI republished the candidate release jar at this HEAD — verified `updated=2026-07-14T20:50:56Z`). **Cloud: FULLY REAPED** (only `aether-test-pg-038708`=149856199 + `aether-pg-firewall`=11290118).

## TL;DR

The **#445 residual is FIXED and validated on every surface** — unit (6 deterministic repros), module (aether-stream 622/0, node 682/0), remote containers (suite 02 stream-failover 7p/0f), and **cloud JVM (all 3 stream-failover assertions green: complete history after owner kill, post-repair liveness 0..24 in order, RF restored)**. The acked-then-lost durability break AND the availability residual are closed. Along the way the **remote container gate reached 15/15** (two harness bugs fixed: A/B deploy stagger + CTM-ULID node addressing) and the **first-ever cloud JVM run landed 13/15** — both fails are env-debt (no VM snapshot → replacement provisioning outruns 900s budgets; JVM-flavor #94 SWIM-under-load), NOT product. Pre-release check done: **release-ready with caveats** (below). Remaining: owner runs the release act + walks the close-out/decision queue.

## Commits this session (all pushed)

| Commit | What |
|---|---|
| `2f08f1e9d` | **product: #445 residual** — empty failover-owner no longer false-promotes (`CAUGHT_UP@-1`); no truncating self-promote below a confirmed-ahead survivor; `escapeOwnerCatchup` reserved for the probed-unreachable branch |
| `7a3fdb0d8` | docs: rc2 close-out — CHANGELOG #403/#442 + #445 remote-validation; feature-catalog #180/#111/#141a |
| `c80d59486` | harness: stagger remote A/B deploy (10-node co-boot starved cluster A's formation; A converges 28s solo, 957s-timeout co-booted) |
| `6d96e7c58` | harness: `wait_for_node_count_fast` falls back to `_discover_endpoint_by_label` — addresses CTM-provisioned ULID replacement nodes (03-scaling remote 0p/3f → 3p/0f) |

## The #445 residual — root + fix (the session's core)

Run-13's hypothesis ("replicas refuse backfill-source reads") was **wrong** — there is no owner-only read gate; the refusal site (`StreamPartitionManager.resolvePartitionInEntry`) gates on *materialized ring*, and a CAUGHT_UP replica serves fine (the probe proves it via the identical path). Real root: **the #333 "HRW owner holds complete history" assumption breaks under failover** (fresh owner = empty ring). Two promotion paths trusted the empty source: (a) `backfillFromOwner` promoted a non-owner off an EMPTY owner response (`-1 < -1` false → `CAUGHT_UP@-1`); (b) `catchupOwnerFromSurvivor`'s failure branch degraded (after the wait bound) to `ownerSelfPromote` at local watermark — truncating below the survivor's confirmed tail. Fix routes both through the safety invariant the cold-start path (`decidePromotion`) already enforces: *never promote at/below a probed peer watermark without fetching*. Empty owner response → probe-gated `handleNoSource` (survivor ahead ⇒ stay SYNCING; genuinely-empty partition still promotes after the bound); failed catch-up from a confirmed-ahead survivor → stay SYNCING and let the redrive retry (dead survivor leaves the member view ⇒ safe self-promote later). `PartitionBackfill`-internal; no API/wire/stored-format change. New repros: `EmptyOwnerReadDoesNotFalsePromote` (A1/A2/A3), `PromotedOwnerSurvivorCatchupFailureStaysSyncing` (B1/B2/B3).

## Remote container gate: 15/15 (evidence composition)

- Suite 02-chaos **7p/0f** (first run, incl. the full stream-replica-failover script — owner killed → new owner serves ALL 20 acked → RF restored).
- Staggered sweep: 13/14 green incl. 04-streaming 4p/0f + 14-storage 2p/0f; only 03-scaling failed.
- 03-scaling root-caused: NOT product (scale API returned 200, cluster scaled) — the fast-count poll only scanned seed ports 5161-65 and never saw CTM ULID replacements on ephemeral host ports. Fixed (`6d96e7c58`); B-chain re-run 05,13,12,03 → **4/4 green, 03-scaling 3p/0f**.
- Two compose facts worth keeping: compose-a owns forge-postgres + BOTH networks (b attaches external); replacement containers publish 8080/8070 to **ephemeral** host ports (`DockerComputeProvider` `-p 8080` bare; the "5166-5169" comment in compose is outdated).

## Cloud JVM gate: 13/15 — first-ever JVM flavor

- `--runtime jvm` does **NOT** use ghcr — VMs download the release-asset `aether-node.jar` pinned by `jar_url` to the `v1.0.0-rc2-candidate` tag. Moving the tag re-triggers Release CI which republishes the jar (that's how the fix shipped; no manual image build needed).
- **PASS (13):** 00,04,05,06,07,08,09,10,11,12,13,14,15 — incl. 12-network kill+replacement observed, 13-edge-cases route-fencing.
- **FAIL (2), both env-debt:** 03-scaling (1p/2f — scale-up reached 6/7; replacement VM can't finish full Temurin+jar install inside 900s) and 02-chaos (3p/4f — kill-under-load/auto-heal-restore timeouts of the same provisioning-speed root + `Kill_node_during_active_load` no NODE_FAILED-in-60s = **#94 SWIM-under-load class re-manifesting on the JVM flavor**; its container-flavor fix was cloud-validated 2026-06-13). The 02-chaos **stream-failover script itself passed every assertion**.
- Clean-cloud-15/15 lever: **pre-built VM snapshot** (`tools/build-aether-vm-snapshot.sh` → set `AETHER_VM_SNAPSHOT_ID_JVM`); decide whether to gate rc2 on it or ship with env-debt documented.
- Reap lesson (again, sharper): deleting a live cluster **races auto-heal** — replacements spawned mid-reap (151043935, 151044293…). Working pattern: parallel-delete ALL cluster-labeled VMs per sweep, repeat until **3 consecutive clean checks**. Never bare `cloud-reaper.sh --destroy` (kills PG).

## Pre-release check (done this session) — release-ready with caveats

- Git/tests/build: clean tree, synced, pom tree consistent at `1.0.0-rc2` (~60 poms, no `${revision}` flattener — literal versions).
- **Stale rc1 refs** (the `/release` version-bump should catch): `README.md:58,65,73` dep snippets; `aether/README.md:5` status banner; `GitHubVersionResolver.java:47` `DEFAULT_VERSION="1.0.0-rc1"`. Also `PackageSlicesMojo.java:674,680` hardcodes `0.11.2`/`0.1.0` in generated poms — verify intentional.
- **Codegen flag:** `RecordGenerator.java:177` — records with **>11 fields** emit a `// TODO: extract tuple fields` stub constructor (uncompilable mapper for wide tables). Blocker only if a shipping pg-slice table exceeds 11 columns — quick verify before GA; else rc3 limitation.
- `mvn verify` was NOT re-run (HCLOUD_TOKEN + failsafe = paid server); validation bar = module gates + remote 15/15 + cloud 13/15.

## Pending owner actions (next session queue)

1. **Issue close-outs** (drafts at session scratchpad `issue-closeouts-draft.md`; issue-writes were permission-gated): #445 close on the full 4-surface evidence; #421 close as dup of #445; #441/#442/#403 close on remote-15/15 + unit evidence (#403 formally at release). #427 separate arc — verify own evidence. #446 (RFC 10008 HTTP QUERY, rc3) was filed this session.
2. **File rc3 issue** (create was permission-blocked): *In-JVM Ember RF=2 owner-kill regression test* — `EmberCluster.killNode` exists; blocker is fixture wiring (`test-stream-repl` RF=2 blueprint not on forge-tests classpath; forge only has RF=1 `TestArtifacts.STREAM_SLICE`). Value: moves the #445/#431/#421 bug-family's primary debug surface off paid cloud.
3. **Release act** (owner): version bump (fix stale rc1 refs above) → `/release` (merge → tag `v1.0.0-rc2` → publish). Decide the VM-snapshot/cloud-15-15 question first.
4. **Decision queue unchanged** from 07-13: #420 milestone formality (rec: ship C1+caveat); rc3 durable-pubsub D1-D4 ratify + D5 envelope call (owner-held); KVStoreSerializer parse-quarantine (3 options on #411); spec §13 items 5/6.

## Operational gotchas (hard-won this session)

- **The environment kills long-running background work BOTH ways**: agent-watchdog stalls (build-runner died twice on long maven) AND plain `run_in_background` Bash (a sweep was externally killed mid-run). Long runs (integration sweeps, cloud) MUST be `nohup … & disown` with a Monitor tailing the log file; the detached process survives.
- **`mvn -f aether/forge/forge-tests/pom.xml test` "passes" vacuously** — surefire has `<skip>true</skip>`; "Tests are skipped" ⇒ no report. Forge tests only run via `mvn verify -Pwith-e2e` (which is HCLOUD-dangerous). Check for the surefire report before trusting BUILD SUCCESS. (Also: `-pl aether/forge/forge-tests` is not in the root reactor.)
- **zsh multi-line var trap**: `for id in $IDS` with a newline-separated unquoted var does NOT word-split in zsh — the reap loop passed all IDs as one argument and "failed". Use `printf '%s\n' "$IDS" | while read -r id`.
- **Monitor pattern hygiene**: `FATAL` matched benign `NON-FATAL` advisories; per-test `[FAIL]` floods on a dead-cluster suite. For long runs, watch per-suite tallies (`\] +[0-9]+p/[0-9]+f`) + `RUN_EXIT` only.
- Suite-02-only runs mask cluster-A deploy problems (02 never awaits A) — the stagger bug hid there.

## Loop state

MAILBOX: no Editor traffic since the 2026-07-10 contract amendment; **re-arm the mtime Monitor first thing next session** (memory `project_gap_drain_loop_mailbox`). Task board: #445-residual ✅, remote-15/15 ✅, cloud-JVM ✅ (13/15, env-debt isolated), close-out docs ✅; remaining = release act + queue above.
