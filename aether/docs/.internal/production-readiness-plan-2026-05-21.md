# Production-Readiness Plan — Integration Tests + CLI (2026-05-21)

**Bar:** RC1 unless explicitly deferrable. Every assertion in the integration test suite must catch a real production regression. No warn-then-pass demotions, no tautologies, no name-vs-check mismatches in the green-checkmark RC1 build.

**Inputs:**
- `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` (this audit) and partials/
- `aether/docs/reference/feature-catalog.md` (capability inventory)
- `aether/docs/specs/test-readiness-contract.md` (model for contracts-as-pinned-properties)

**Decisions taken (this session):**
- RC1 bar: anything that hides a real regression escalates to RC1-block
- Reorg: charter overlay only, keep 00-15 layout
- Coverage gap: feature-catalog driven
- Product API: in scope (principal/identity, TLS-active, scheduled-tasks/inject)

---

## Re-triage with the RC1 bar

Audit's RC1-block count was **18**. Under "RC1 unless deferrable", the following escalate from HIGH to RC1-block:

| # | Item | From | Why escalates |
|---|------|------|---------------|
| 19 | `03-scaling/test-03-scale-down.sh::test_no_data_loss` | HIGH | Name lies grossly; any release reviewer would believe this gives a data-loss guarantee |
| 20 | `08-resources/test-scheduled-tasks.sh::test_pause_task` | HIGH | Pause/resume is a real product capability; broken pause silently passes |
| 21 | `08-resources/test-scheduled-tasks.sh::test_resume_task` | HIGH | Same |
| 22 | `13-edge-cases/test-concurrent-deploys.sh::test_both_blueprints_visible` | HIGH | Empty slices payload after deploys is a real product failure mode |
| 23 | `14-storage/test-storage-cli.sh::test_cli_storage_list` | HIGH | Silent skip on CLI failure — masks regression of a shipped CLI command |
| 24 | `14-storage/test-storage-cli.sh::test_cli_storage_status` | HIGH | Same |
| 25 | `14-storage/test-storage-cli.sh::test_cli_storage_list_json` | HIGH | Same + leading-char regex |
| 26 | `14-storage/test-storage-management.sh::test_storage_snapshot` | HIGH | Empty body → return 0 — snapshot endpoint is product feature |
| 27 | `15-delegation/test-01-task-assignments.sh::test_tasks_distributed` | HIGH | "Across ≥2 nodes" claim with `≥1` check; suite's whole point |
| 28 | `12-network/test-swim-detection.sh::test_swim_detection_time` | HIGH (already #17) | Already RC1-block in §2.2 — unchanged |

And a small number of MEDIUMs:

| # | Item | From | Why escalates |
|---|------|------|---------------|
| 29 | `lib/cluster.sh::drain_node` / `activate_node` duplicate definitions | RC2 | Two different API contracts in collision; dead code is a maintenance trap |
| 30 | `lib/load.sh` 3xx-as-success at 4 sites | RC2 | Affects soak + scale-down + kill-under-load error-rate gates universally |

**Total RC1-block: 30 items.** (Some duplicates collapse; the count may shrink to ~25-28 once siblings consolidate.)

The remaining HIGH (1 left), MEDIUM (~17), LOW (~20), and SOUND (~140) are RC2 work.

---

## Phases & sequencing

### Phase 0 — Setup (Day 1, ~half day)

**Deliverables:**
- `aether/docs/contributors/test-charter-template.md` — shape for per-suite charters
- Test-ID convention: `TC-<SUITE>-<NUMBER>` (e.g. `TC-02-CHAOS-005` = kill-node test 5)
- CI lint draft script: prohibits `log_warn ... log_pass` pairs, `2>/dev/null || true`, `assert_ne ... ""` on raw responses

**Not blocking other phases.** Can start in parallel.

### Phase 1 — Coverage gap analysis (Days 1-2, parallel agents)

**Deliverables:**
- `aether/docs/internal/audits/test-coverage-matrix-2026-05-21.md`
- Per feature-catalog entry, three columns: `COVERED` (≥1 test asserts the contract), `PARTIAL` (smoke / non-strict assertion exists), `NONE` (no test).
- Output drives Phase 4 backlog additions ("tests we need to write").

**Method:** Walk `feature-catalog.md` sections in parallel. Each agent takes 1-2 sections, greps `aether/tests/integration/suites/**` for evidence, classifies.

**Effort:** 1-2 days via 4-6 parallel agents. Read-only.

### Phase 2 — Product API additions (Days 3-7, sequenced REST → CLI → Docs)

These are the product surface changes RC1-blockers depend on. Each follows the `REST routes → CLI → Docs` triad rule in `CLAUDE.md`.

| # | Product change | Drives RC1-blockers | Effort |
|---|----------------|---------------------|--------|
| P1 | `/api/whoami` endpoint (echo authenticated principal) + CLI `aether whoami` | RC1-blockers 5, 6 | 1d |
| P2 | Add `principal` field to `/api/nodes/status` response | RC1-blockers 5, 6 | 0.5d |
| P3 | Add `tlsEnabled` boolean field to `/api/certificates` | RC1-blocker 3 | 0.5d |
| P4 | Auth-required path discoverable; document the "must-be-401-when-unauth" contract | RC1-blocker 7 | 0.5d |
| P5 | `POST /api/scheduled-tasks/inject` (test-only endpoint, gated by `AETHER_INSECURE_DEV_MODE`) + CLI `aether scheduled-tasks inject` | RC1-blocker 16, HIGH 20, 21 | 1.5d |
| P6 | `aether streams read <name> <partition>` (CLI for existing REST) | RC1-blocker 1 | 0.5d |
| P7 | `aether streams create <name>` / `delete` / `consumer-group join/leave/status` | Multiple streaming tests + future coverage | 1.5d |

**Total Phase 2: ~6 days, can compress to ~3-4 days with parallelism on independent items.**

Each item ships as its own PR with REST + CLI + docs in the triad shape.

### Phase 3 — CLI gaps not driving RC1-blockers (Days 5-10, parallel with Phase 2)

Lower priority but in scope. Closes operator-facing gaps + enables coverage matrix to be cleaner.

| # | CLI addition | Notes |
|---|--------------|-------|
| C1 | `aether metrics prometheus/transport/comprehensive/derived/history` | 5 commands; all REST routes exist; CLI is wrapping |
| C2 | `aether slices status` / `aether slices topology` | 2 commands |
| C3 | `aether cluster governors` | 1 command |
| C4 | `aether ttm status` / `training-data` | 2 commands |
| C5 | `aether artifacts get <g:a:v> [--out=<file>]` | byte-stream resolve |
| C6 | `aether blueprints publish <g:a:v>` (orthogonal to `apply`) | dead route otherwise |
| C7 | `aether cluster export --format json` actually emits JSON | currently TOML body regardless of flag |
| C8 | `aether artifacts versions` JSON output | currently XML body |

**Effort:** ~3-5 days via parallel agents (CLI commands wrapping REST is a known pattern; mostly mechanical).

### Phase 4 — RC1-block test fixes (Days 8-14, batched per-suite)

Once Phase 2 product changes land, fix the 30 RC1-block items. Batched per-suite (parallel agents):

| Batch | Suites | Items | Depends on |
|-------|--------|-------|-----------|
| B1 | 03-scaling | 1 (test_no_data_loss) | — |
| B2 | 04-streaming | 2 | P6 (streams read) |
| B3 | 05-security | 6 | P1, P2, P3, P4 (whoami/principal/tlsEnabled) |
| B4 | 06-deployment | 4 | — (assertion logic only) |
| B5 | 07-cluster-mgmt | 2 | — (assertion logic only) |
| B6 | 08-resources | 4 (subscriber + 3 scheduled-tasks) | P5 (scheduled-tasks inject) |
| B7 | 12-network | 1 (SWIM 15s budget) | — |
| B8 | 13-edge-cases | 1 (concurrent deploys empty payload) | — |
| B9 | 14-storage | 5 (CLI silent-skip + snapshot + artifacts instance) | — (migrating to `aether storage *` CLI) |
| B10 | 15-delegation | 1 (tasks_distributed ≥2) | — |
| B11 | lib | 2 (drain_node shadow, load.sh 3xx-as-success) | — |

Each batch is one PR with the test fix(es) + the charter entry (Phase 5) + any new assertions discovered during fix.

**Effort:** 3-5 days via parallel agents, sequenced behind Phase 2 for the dependent batches.

### Phase 5 — Per-suite charters (Days 12-15)

Mechanical follow-on to Phase 4. For each suite, write `aether/tests/integration/suites/<NN-name>/CHARTER.md`:

```markdown
# Suite NN-NAME Charter

## Contracts under test
- C1: <contract from spec / test-readiness-contract>
- C2: ...

## Test-to-contract map
| TC ID | Test function | Contract(s) | Severity |
|---|---|---|---|
| TC-NN-001 | test_X | C1, C2 | core |
| ... |
```

Charters are generated semi-mechanically from the audit catalog (§1 of the audit) + the spec references in each test. Spawn one agent per suite to bootstrap; human pass to verify contracts are real (not invented).

**Effort:** ~2-3 days via parallel agents.

### Phase 6 — CI lint (Day 15)

`aether/tests/integration/lint-tests.sh` — shell pre-commit / CI step that fails on:

1. `log_warn ...` immediately followed by `log_pass ...` in the same control flow (warn-then-pass demotion).
2. `2>/dev/null || true` inside `aether/tests/integration/suites/**`.
3. `assert_ne ... ""` on a variable whose source is a raw HTTP response (heuristic: grep precedes the assert with `curl`/`api_get`).
4. `[ status -ge 200 ] && [ status -lt 400 ]` outside `lib/load.sh` (3xx-as-success).
5. Any `test_*` function defined but not invoked via `run_test` in the same file (catches `test_blue_green_rollback`-class dead code).

Wire into `./build.sh` and `run-tests.sh` pre-flight. Existing offenders get the audit's grace period; new code is gated.

**Effort:** ~1 day.

### Phase 7 — HIGH/MEDIUM iterative (post-RC1, into RC2)

The remaining ~17 MEDIUM items and the leftover HIGH (whichever doesn't escalate after Phase 0's re-triage discussion). Standard backlog, no urgency.

---

## Critical path

```
Phase 0 (setup)              [half day, parallel-safe]
  │
  ├── Phase 1 (coverage)      [1-2d, parallel agents]
  │
  ├── Phase 2 (product API)   [3-4d, sequenced]
  │     │
  │     └── unblocks ──→ Phase 4 (test fixes)  [3-5d, parallel agents]
  │                              │
  │                              └── Phase 5 (charters)  [2-3d, parallel agents]
  │                                       │
  ├── Phase 3 (CLI gaps)      [3-5d, parallel with Phase 2]
  │                                       │
  │                                       └── Phase 6 (CI lint)  [1d]
  │
  └── RC1-ready when all of {Phase 1, 2, 4, 5, 6} green
```

**Total wall time:** ~2.5-3 weeks if focused. **Critical path:** Phase 2 (product API changes) → Phase 4 (dependent test fixes). Everything else can run in parallel around that spine.

---

## Acceptance criteria for RC1 tag

1. **Zero RC1-block items** in a re-run of this audit (i.e., re-spawn the 7 parallel agents and confirm the table shrinks to 0).
2. **Charter exists** for every one of 16 suites at `aether/tests/integration/suites/<NN-name>/CHARTER.md` with assertion-to-contract traceability.
3. **CI lint** active in `./build.sh` and `run-tests.sh`; green run is mandatory.
4. **Coverage matrix** shows zero `NONE` entries for `Complete` features in `feature-catalog.md`. `PARTIAL` is acceptable if documented.
5. **Integration suite green end-to-end** on remote Docker (cluster A non-destructive + cluster B destructive, all 15 suites).
6. **All Phase 2 product changes** updated in CHANGELOG.md and feature-catalog.md.

---

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Product API changes spiral (principal/whoami needs RBAC re-spec) | MEDIUM | Cross-check with RBAC spec at Phase 2 entry; if scope is >1d per item, escalate scope decision before continuing |
| Coverage gap analysis finds NONE entries that need new tests written from scratch | HIGH | Phase 1 surfaces these explicitly; they become Phase 4 additions, not RC1-blockers (unless the feature is `Complete` and uncovered) |
| Charters reveal that the "real contract" for some tests doesn't exist in any spec | MEDIUM | Charter doc has a "contract gap" column; flag for spec-writing follow-up; doesn't block RC1 unless the feature is core |
| CI lint flags hundreds of preexisting offenders (LOW-severity, e.g. cosmetic `2>/dev/null`) | LOW | Lint runs in two modes: `--strict` (RC1+ code) and `--legacy` (pre-existing). Migration is incremental; new code is gated |
| Product change for principal injection (P1+P2) is bigger than estimated and requires structured response refactor | MEDIUM | Time-box at 1.5d total for P1+P2; if it overruns, descope to "minimum principal echo" and follow up in RC2 |
| Some `Complete` features in feature-catalog actually aren't complete (NONE entries that are really product gaps) | MEDIUM | Phase 1 surfaces these. Decision per-item: re-classify the catalog entry (catalog hygiene) or write the test (test gap) |

---

## What the user gets at the end

- A test suite where every test catches a real regression
- Per-suite CHARTER.md documenting what each test verifies and which contract it tests
- Full CLI coverage of the REST surface (no operator-facing gaps; no test needing raw `curl` to reach a known operation)
- CI lint that prevents the next round of warn-then-pass demotions
- Coverage matrix showing what's tested and what isn't
- A clean re-audit at RC1-tag time

---

## Open questions for the next consultation

These don't need to be answered before Phase 0 starts, but should be addressed by Phase 1 completion:

1. **Where does charter-vs-spec live?** Do charters cite spec files (`test-readiness-contract.md §1.1`) or do they introduce their own contract IDs that the specs then reference? Recommend: charters cite specs; if a contract doesn't have a spec home, charter creation triggers a spec follow-up.

2. **How strict is the "no warn-then-pass" lint?** There are legitimate uses (passive observation, intentional load-tolerance). Recommend: the lint flags the pattern; tests opt out with an inline comment `# WARN_PASS_OK: <reason>`. Forces the author to write the why.

3. **Do we need a soak suite re-run before RC1 tag?** The 4-hour soak is currently in 01-stability (opt-in). Recommend: yes, one full run on remote Docker is a Phase 6 step.

4. **What's the lint enforcement timing?** Soft (warnings only) for the first week, hard fail after? Or hard fail from day 1 with a known-offenders allowlist? Recommend the latter — keeps signal honest.

---

## Suggested next action

Confirm this plan (or adjust), then I start **Phase 0 + Phase 1** in parallel — Phase 0 takes a half day and Phase 1 launches 4-6 read-only parallel agents that produce the coverage matrix without conflicting with anything. While those run, Phase 2 can begin spec review for the product changes.

Once Phase 1 lands and we re-triage with the coverage matrix in hand, the rest of the phases unfold against the plan above.
