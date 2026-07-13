# Session Handover — 2026-07-13 (aether-main)

**Branch:** `release-1.0.0-rc2` · **HEAD:** `bdd828334` (pushed; `v1.0.0-rc2-candidate` on HEAD) · **Cloud: RUN 13 IN FLIGHT (detached)** — 5 cluster VMs + test-PG will need reap after it ends.

## TL;DR

Goal locked with owner: **fix all issues → 15/15 on `--env remote` (container) AND `--env cloud --runtime jvm` → release rc2**; rc3 scope drains AFTER the cut via a small-model packet loop (Phase A → checkpoint → Phase B, approved). This session executed the Phase-A fix arc across cloud runs 7–13: **six product commits + seven harness commits**, every fix cloud-witnessed. Product repeatedly exonerated (S19 `exit=2` proven 4×); the harness stopped lying (wall-clock deadlines, honest-read contracts, race arbitration), and the first REAL product bug of the arc surfaced and was fixed same-day: **#445 stream acked-then-lost under churn** (divergent live-vs-reconciled placement views). Run 13 (image `5576061e`, all fixes) was at **32/0 through six scripts** with the stream script mid-flight showing the previously-IMPOSSIBLE states green (`servedByOwner` authoritative, **CAUGHT_UP promotable replica exists**) when this handover was written — on track for the **first fully green 02-chaos on cloud, ever**.

## Commits this session (oldest first)

| Commit | What |
|---|---|
| `499dd153c` | harness: #441 wall-clock deadlines (wait_for + 3 siblings) + PAM-tolerant S19 corroboration |
| `5fe0312bc` | product: #442 v1 — server_type spec/config resolution (cx33 default deleted), 3B ssh-key lookup |
| `855ddf598` | product: #442 v2 — ssh-key ids rendered into node overlay + replacement user-data (self-sustaining chain) |
| `b9898108d` | harness: #441 S19 quorum-race arbitration (bounded newcomer kills) + empty-enumeration-is-UNKNOWN + ssh stderr routing |
| `b992909e1` | harness: `_resolve_live_endpoint` reuses canonical enumeration (last broken-selector carrier) |
| `bf6e84f08` | product: #442 v2b — WaveExecutor cluster tags + Hetzner label-value sanitization |
| `5a268864b` | product: #442 create-time label diagnostic (ground-truth INFO per provision) |
| `867b4a3f3` | product: #442 post-join label WIPE fix — read-modify-write merge at both Hetzner label-update sites |
| `c51e47092` | jbct: #403(b) slice-processor staleness guard (mtime-vs-build-stamp, silent degrade outside monorepo) |
| `1b072b6bb` / `bdd828334` | formatter drift chores |
| `b2fd7dc4d` | harness: cloud TOMLs cx33 → cpx32 (cx + cpx31 lines capacity-dead in ALL zones; checked live via `hcloud datacenter describe`) |
| `866488d8b` | harness: #441 read-fragility class — bounded hcloud enumeration, sticky endpoint cache, honest-read count contract, sustained kill-under-load wait |
| `fd7038c30` | **product: #445 single-sourced stream placement view** (backfill ranks over the reconciled snapshot) |

## The #445 story (the session's centerpiece)

Run 12 was 6/7 scripts green; the stream-replica-failover script exposed: fresh RF=2 stream on churned cluster → owner never serves, replicas `SYNCING@-1` forever, **20 ACKED events lost on owner deletion at `min-sync-replicas=2`**. Live-forensicated (investigator on the wedged cluster): two HRW computations over DIFFERENT member views (backfill: live `coreNodes()`; role-gate/registry: last-reconciled snapshot) → placed replica self-classifies NONE → every apply bounces `PARTITION_NOT_LOCAL`. **This root-causes #431's blocker AND #421** (both cross-linked; close #421 as dup when #445 closes). Fix `fd7038c30`: `ReplicaSetController.reconciledMembers()` + `AetherNode.streamPlacementMembers` — divergence closed by construction; deterministic unit repro (`PartitionBackfillTest$SingleSourcedPlacementView`); aether-stream 616/0, node 682/0. Note: `StreamCrashDurabilityTest` was ALREADY re-enabled (1f64cd023) as RF=1-only — it does NOT isolate this fix; the unit pair + the cloud stream script do.

## Run-13 pickup (FIRST THING next session)

- Run 13 is **detached** (`nohup`+`disown`, pid in `run13.pid`) — it SURVIVES session end. Log: `/private/tmp/claude-501/-Users-sergiyyevtushenko-IdeaProjects-pragmatica/1de8aec8-2a1d-4b51-a712-e05f0198b4f7/scratchpad/cloud-gate-run13.log` (absolute path, persists until tmp cleanup). Machine-readable result: `aether/tests/integration/test-results.json` on completion.
- If the stream script went green → **suite 02 fully green**: close #445/#441/#442/#427 on evidence (comment + close), close #421 as dup of #445, then reap the fleet (explicit IDs; PRESERVE `aether-test-pg-038708` = 149856199 + `aether-pg-firewall`) and proceed to the gates.
- If it failed → the log + live cluster are the forensic surface (SSH-exec works on ALL nodes now — #442). Evidence-first, the run-8→13 pattern.
- **Detached execution is now the standard**: runs 10 & 11 were EXTERNALLY killed mid-S19 (~60-90 min in; not me, not the user — environment kills long harness-owned background tasks). Always `nohup`+`disown` + Monitor on the log.

## Remaining rc2 queue (tasks 4–6)

1. **Remote container 15/15 sweep** (`--env remote`) + fold-ins: #421 already resolved via #445; the 1f fence probe (`aether cluster ownership` during owner-kill: deposed `fenced=true`, steady `highWater==epoch`) still pending; #426's residual (isolated kill-node leader-window) was organically answered by runs 8–13 (leader survived every S01 kill, window observable) — note that on #426 and close.
2. **Cloud JVM 15/15** (`--env cloud --runtime jvm`, TOMLs exist at cpx32 now, first-ever JVM-flavor run — treat first failures as env-debt).
3. **Close-outs:** #420 caveat docs (C1 shipped via #427; owner accepted ship-with-caveat direction), #403 closes at release (guard landed), **CHANGELOG + feature-catalog for the whole arc (invariant #2 — NOT yet done)**, then `/pre-release-check` → `/release` (owner runs the release act).

## Operational state & gotchas

- **Image pipeline**: local amd64 build with gates (`created-today` + `arch=amd64`) → push `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc2-candidate` (owner approves each push). Current digest `5576061e` = HEAD `bdd828334`-equivalent (built at fd7038c30+chore). ALWAYS rebuild+push after node/stream/env changes; the run-8 near-miss (pipe swallowed a failed build, old image pushed) is why the gates exist.
- **CLI on PATH is an installed jar** (`~/.aether/aether-cli-1.0.0-rc1/lib/aether-cli.jar`) — refresh it (`cp aether/cli/target/aether.jar …`) after every build; verify `aether --version` timestamp. This was found a MONTH stale (#440 has the evidence).
- **Cloud runs need** `source /tmp/aether-test-pg.env` (PG_* secrets for the TOMLs) + `HCLOUD_TOKEN` present (bootstrap) — but NEVER maven with it set.
- **cpx32** is the instance type now (cx33/cpx31 capacity-dead across fsn1/nbg1/hel1 as of 2026-07-13; re-check live via `hcloud datacenter describe <dc>` if bootstrap hits capacity walls).
- Sticky-endpoint cache file `${TMPDIR:-/tmp}/aether-live-endpoint-<cluster>` survives between separate runs (mitigated by re-probe; hardening idea: verify nodeId pattern in the probe — noted, not urgent for B-only runs).
- Stale worktree `.claude/worktrees/agent-a7adb6930f63a6047/` caused a transient `~/.m2` race during a build (retry succeeded) — consider deleting it.
- **Coder stall pattern persists**: agents go idle without delivering; check `git status` for actual product, then nudge. Messages CROSS — reconcile timelines before re-tasking (three separate crossings this session).
- rc3 follow-ups filed/parked this session: #444 (3A provisioning profile + labels policy + deregister clearLabels nit + aether-source=default), tools/cloud-reaper.sh selector (noted on #426/#441 thread).

## Owner decision queue (parked, walk one-at-a-time when asked)

1. #420 milestone formality (recommendation unchanged: rc2 ships w/ C1+caveat; #428 rc3).
2. rc3 spec rulings: durable-pubsub D1–D4 ratify + **D5 envelope call (owner-held)**; KVStoreSerializer parse-quarantine (3 options on #411); spec §13 items 5/6.

## Loop state

MAILBOX: no unread Editor traffic (last entry = my 2026-07-10 contract amendment); **re-arm the mtime Monitor next session** (memory `project_gap_drain_loop_mailbox`). Editor's probe queue (#432/#433) triaged rc3. Task board: 1,2,7–13 completed; 3 in flight (run 13); 4–6 pending.
