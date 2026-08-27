<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-24 (aether-main, continues 2026-07-21..24 arc)

**Branch:** `release-1.0.0-rc3`. **HEAD:** `bae687e17` + this handover commit; candidate tag `v1.0.0-rc3-candidate` last re-pointed at `22e1b57c5` (Release+CI green) — **re-point after this handover batch**. Working tree clean apart from this doc. **PULL FIRST** (design-stream PRs merge to this branch; four merged this arc).

## TL;DR
1. **Two full delivery days.** 07-23: #499 closed (zombie-scheduler + fire-once-ack, pinned gate ACTIVE), #492/#490/#433 closed, PRs #500/#502 merged, README fixed. 07-24: parallel 3-lane batch closed #485/#506/#429/#430/#431 (shared `StreamForwardRetry`, multi-partition fixture+suites, crash-durability single+multi-partition), PRs #504/#505 merged, #509 deficit-fill found+filed (pulled to rc3).
2. **Getting-started dry-run executed** (legs 1–3 complete, leg 4 staged): the MACHINERY is genuinely simple and impressive; the COLD PATH is broken at every leg by *silent-wrong-state* defects → **#510–#515 filed, all rc3**. Verdict: write the tutorial AFTER those land, from the dry-run transcript.
3. **Next session's mandate (owner-stated):** fix #510–#513 (+#514/#515), then a **reconciliation pass hunting more silent-wrong-state issues**; consider the aether-book teammate or a new getting-started-writer teammate as adversarial gap-finders (§4).

## 1. The dry-run — what was proven and what broke (2026-07-24)

Method: cold-user simulation, sandboxed `HOME` (scratchpad — **EPHEMERAL, dies with the session**; everything needed is inlined here), real download path pinned to the candidate release, every friction logged. Full transcript context: issues #510–#515.

| Leg | Machinery verdict | Cold-path verdict |
|---|---|---|
| 1. Install | ✅ dist matrix + bundled JRE + SHA256 + `--version` (aether) | ❌ **#510**: unpinned = silently installs `1.0.0-alpha` (broken prerelease sort, BOTH tools); jbct ignores `--version` (env `VERSION` only); root installer no passthrough |
| 2. Create | ✅ `jbct init` scaffold rich (pom/TOMLs/scripts/.claude); `mvn package` green first-try vs local rc3 | ❌ **#511**: template fails its own `format-check` at `run-forge.sh` (`mvn install` binds the gate; `package` false-greens) |
| 3. Forge | ✅ init→serving `{"greeting":"Hello, World!"}` in ~30s once correct; dashboard 200 | ❌ **#513**: `run-forge.sh` passes `--blueprint FILE`; forge wants `groupId:artifactId:version:blueprint` coords → HTTP 500 in log, forge keeps running LOOKING healthy (empty cluster, `No route found`) |
| 4. Hetzner | ✅ `aether cluster bootstrap` real (7 phases, auto-cleanup, candidate-aware jar derivation) | ❌ **#514**: NO user doc shows a working config TOML (only internal test files, whose comments encode required tribal knowledge: `security_mode="NONE"` else 401/403; `jar_url` pinning). **#515**: scaffold has no cloud story (`deploy-prod.sh` calls nonexistent `aether artifact push --env`; `/data/aether` absolute default → WARN wall + silent WAL-off on laptops) |
| — | **#512**: real-machine find — mixed-generation `~/.aether` leaves forge silently broken (launcher expects a jar no generation placed; the `.aether` on THIS dev machine is in that state right now — fixing it = re-run the aether installer) |

**Leg-4 live run: STAGED, NOT EXECUTED.** The auto-mode classifier blocked `aether cluster bootstrap` (real spend); surfaced to owner; options were (a) owner runs via `!`, (b) permission rule, (c) skip. **Undecided at handover.** The validated minimal TOML (derived from `aether/tests/integration/env/cloud-hetzner-jvm.toml`, DB blocks dropped) is preserved verbatim in **#514's** dry-run reference and below-referenced test file; 5×cpx32, `zones=["fsn1","nbg1","hel1"]`, `[source.hetzner-eu.node_config.app-http] enabled=true security_mode="NONE"`, jvm runtime with candidate `jar_url`. Cost ≈ €0.07/h; standing grant applies (ALWAYS scoped cleanup, 2h cap, never touch test-pg).

**Positives worth keeping in the tutorial's voice:** bundled-JRE self-contained dists; `jbct init`'s next-steps output is literally the tutorial outline; `aether -c localhost:5150 status` clean JSON; forge formation fast.

## 2. Next tasks (owner-stated) — execution notes

### 2a. Fix #510–#513 (+#514/#515) — all rc3, all small
- **#510** installers: fix prerelease ordering (NOTE: candidate releases are PUBLIC — "newest in API order" would serve the candidate; `releases/latest` 404s pre-GA. Need explicit rank: GA > rc-N > alpha, exclude `*-candidate`), add arg parsing to jbct/install.sh, root passthrough with PER-TOOL version flags. Acceptance in the issue.
- **#511/#513/#515** are ONE structural fix + mechanical repairs: re-format templates, coords-form `--blueprint`, regenerate `deploy-prod.sh` against the real CLI, then the **structural gate: a jbct-cli test that runs `jbct init` into a temp dir and drives it through format-check→lint→run-forge→hello-response**. That test kills the whole template-drift class (#511's acceptance names it; #513/#515 reference it).
- **#512** upgrade hygiene: installer must replace ALL launcher generations; launcher self-diagnoses missing jar. The dev machine's own `~/.aether` is the repro.
- **#514** docs: bootstrap config reference + minimal Hetzner example (validated template exists). Consider folding into the tutorial itself.

### 2b. Reconciliation pass — hunt the rest of the *silent-wrong-state* class
Definition: states where a step **succeeds or looks healthy while being wrong** — the class that burns users silently. Known members found across this arc (the pattern to sweep for):
- Install: alpha-as-latest (#510), mixed-generation broken forge that `command -v` finds (#512).
- Scaffold: `package` false-greens then `install` gates (#511); forge up + empty cluster + healthy banner (#513); deploy-prod invoking a nonexistent command (#515).
- Runtime: `/data` read-only → WAL silently off, "non-crash-durable" only in log scroll (#515); deficit-fill converting slow-formation into phantom VMs + split ownership (#509); registry SYNCING@-1 over fully-replicated data (#499, fixed); dead codec registry shadowing the live one (#503/WorkerCodecs → #492's cause).
- Surfaces: `@PartitionKey`/`withKeyExtractor` dangling (#507); `[streams.X]` consumer registration dangling KV write (#488); mgmt publish hardwires partition 0 (#507 comment).
- Test/CI: `jbct:check` vacuous on test sources; forge-tests `mvn test` vacuous-skip (surefire skip=true); docs' "re-query hrwOwner" remedy that never worked (#490, fixed).
**Sweep method suggestion:** grep-driven candidates (fail-soft fallbacks, `.or(` defaults on config/lookup paths, `log.warn` + continue, empty-collection returns on lookup failure, unconsumed registrations) + the #497 DX-audit lens; each candidate gets the same disposition as #488/#507: wire it, remove it, or document it loudly.

### 2c. Teammate consideration (owner-suggested) — gap-finder lanes
Two options, can run in parallel with the fixes; both follow the **audit-then-write pattern that worked** for the book lane:
- **aether-book teammate** (restart mandate: 2026-07-21 handover §3 — still accurate; lane-safety STEP 0 first). Its source-verification discipline found #432/#433-class gaps before; hand it the dry-run transcript (#510–#515) as the product→book delta AND ask it to audit the book's install/deploy claims against the dry-run reality. NOTE: coding-technology commit `7199276` (manuscript first-tracking) is LOCAL/UNPUSHED — owner holds push; don't lose it.
- **NEW getting-started-writer teammate**: writes the tutorial FROM the dry-run transcript while adversarially re-walking every step post-fix — each step must be executed, not asserted (the #496 discipline). Its natural outputs: the tutorial, a re-validated friction list (regression check on #510–#515 fixes), and NEW gap discoveries (feeds 2b). Suggested constraints from this session's teammate experience: strict file-ownership lanes, no installs/forge without coordination, report-only on product bugs, evidence-first reports, stay-resumable between phases.
Recommendation: run BOTH — writer produces the tutorial + walks the path; book teammate cross-audits claims. The two lanes' outputs cross-check each other.

## 3. State of the queues
- **rc3 milestone:** ~46 open after this arc (42 pre-dry-run + #510/#511/#512/#513/#514/#515 − nothing closed today... recount at session start). Real distance = triage decision: blocking core ≈ #463 (headline, long pole) + #420 + #509 + #510–#515 (tutorial/front-door) + small closables; ~30 riders (durable-entity epic #345+pieces, storage chain #248-#264, autoscaler #435-437, #386) look rc4/backlog-shaped per the pipeline doctrine — OWNER triage recommended.
- **Filed this arc:** #501 (scheduler-leak audit), #503 (WorkerCodecs orphan), #506 (fixed same-day), #507 (partition-key dangling +mgmt partition-0), #508 (cloud docker-kill test w/ full spec), #509 (deficit-fill, rc3), #510–#515 (dry-run).
- **#498** back at rc4 (disproven as #499's driver; own gate-run-1 evidence stands).

## 4. Gotchas ledger (new this arc — memory files exist for most)
- **AetherNode impl is a LOCAL RECORD** (implicitly static — NO capture of method locals; wiring state must be record components, 2 construction sites). Memory: `project_aethernode_local_record_no_capture`.
- **Forge console has NO node identity** — never attribute a log line without a `self=` tag; killed-node zombies (pre-#499-fix builds) polluted 3 diagnosis rounds.
- **`isActive()` is NOT liveness** (no per-link heartbeat; sticky-true) — owner ruling; memory `feedback_aether_isactive_not_liveness`.
- **Forge harness limits:** no in-JVM hard-kill (stop() always runs `streamPartitionManager.close()`, AetherNode.java:1253); **≤2 cluster formations per JVM** (3rd churns via #509 deficit-fill, NOT auto-heal-gated); `jbct:check` scans src/main only. Memory: `project_forge_harness_limits_and_deficit_fill`.
- **Maven/JVM `user.home` ignores the `HOME` env** — sandboxed-HOME runs still resolve the real `~/.m2` (this made the dry-run work; also means HOME-sandboxing does NOT isolate maven).
- **forge-tests module needs `-Pwith-e2e` to join the reactor** for `-pl`; single-suite runs use `mvn -f aether/forge/forge-tests/pom.xml verify -Dit.test=X -Dfailsafe.excludedGroups=`.
- **`tail --pid` is GNU-only** (macOS tail silently follows forever) — Monitor scripts must use `ps` polling; budget monitor loops UNDER the harness timeout.
- **Backticks inside double-quoted `gh` strings execute as command substitution** — one actually ran `aether cluster bootstrap` (harmless usage error). Single-quote or escape.
- **One candidate-tag re-point per batch** — two force-pushes minutes apart raced concurrent Release runs into asset-upload failures (both build legs were green; reruns fixed).
- **Auto-mode classifier blocks `mvn verify` and `aether cluster bootstrap`** — route maven through build-runner (launch-detached pattern for >600s runs: nohup+disown, agent reports PID, lead Monitors); real-spend commands need owner action.
- **`gh pr merge` on two PRs in one command: tail -2 swallows which failed** — check per-PR state after every merge; a "conflict" error may belong to the OTHER PR.
- **Killed forge drains gracefully ~20s** — wait for port release before relaunch (instant-relaunch dies cleanly on bind).
- **jbct init under a candidate-built CLI pins `-candidate` versions** (nonexistent in Maven) — verify version-derivation at REAL rc3 release; workaround: explicit `--aether-version/--jbct-version/--pragmatica-version`.

## 5. Standing state (unchanged unless noted)
- **Book lane:** manuscript committed LOCAL-ONLY in coding-technology @ `7199276` (push HELD by owner); prose synced to the honest streaming story; aether-book teammate resumable per 07-21 §3.
- **Hetzner leg of dry-run:** staged, blocked on owner (see §1). Sandbox is GONE next session — recreate from #514's template + `jbct init` (5 min).
- **#432** (durable-entity spec↔code) remains design-stream's authority decision — not ours.
- Owner rulings this arc: #509→rc3; Option A (cloud tier) for crash-arm-2; book commit=Option A full-manuscript, push held; #499-mechanism stays OUT of book prose.
