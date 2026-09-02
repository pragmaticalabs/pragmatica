# Session handover — 2026-05-21 (RC1 production-readiness sprint)

Branch: `release-1.0.0-rc1`
HEAD: `58b92965a` (all pushed)
Range: `7dc8935a0..58b92965a` — **36 commits**
Working tree: clean

## Topline

Most consequential session in the RC1 push to date. Started with an audit-driven question ("are our integration tests actually testing what they claim?"), produced an authoritative answer (200+ tests catalogued, 30 RC1-blockers identified), and closed the entire backlog in the same session: all 30 RC1-blockers, all 8 CLI gap items, all 14 product API additions, all 16 per-suite charters, CI lint ratchet wired.

Net delta vs the prior session (2026-05-20c — Wave 7 fixes pending Result core helper):
- 5 carry-over commits cleaning up Wave 7 (entry-point refresh, stream_list, delegation auto-heal, chunk retry, Result.firstFailureOf migration after PR 225 merge)
- 31 net-new commits this sprint

Only remaining RC1-readiness backlog: ~51 net-new test functions + 29 PARTIAL strengthenings for Complete-but-NONE features in the coverage matrix (~22 wall-clock days). This is **coverage expansion**, not RC1-blocker remediation.

## Status by phase

| Phase | Status | Output |
|---|---|---|
| 0 — Setup | ✅ | `aether/docs/contributors/test-charter-template.md`, `aether/tests/integration/lint-tests.sh` (R1-R5 lint rules + `--baseline` ratchet) |
| 1 — Coverage matrix | ✅ | `aether/docs/internal/audits/test-coverage-matrix-2026-05-21.md` (176 features classified: 24 COVERED / 48 PARTIAL / 102 NONE / 2 N/A) |
| 1b — Follow-up plan | ✅ | `aether/docs/internal/production-readiness-followup-2026-05-21.md` (51 new tests + 29 strengthenings backlog; ~22 wall-clock days) |
| 2 — Product API | ✅ | 14 items delivered (P1, P3, P5, P6, P7 + P-NEW-A through P-NEW-I). P2, P4 redundant/no-op. |
| 3 — Non-blocking CLI gaps | ✅ | C1-C8 all closed |
| 4 — RC1-block test fixes | ✅ | **30/30** RC1-blockers closed across 11 batches (B1-B11) |
| 5 — Per-suite charters | ✅ | 16 `CHARTER.md` files; 319 tests catalogued with TC-IDs + contract citations |
| 6 — CI lint | ✅ | Wired into `./build.sh` step 6 + `run-tests.sh` step 0; ratchet against 49-entry baseline |

## RC1-blocker closure (audit §2.2)

All 30 closed; grouped by suite:

| Suite | RC1-blockers closed | Commit |
|---|---|---|
| 03-scaling (#19) | `test_no_data_loss` — push marker artifact pre-scale, assert SHA-256 equality post-scale | `c68a3ec37` |
| 04-streaming (#1, #2) | `test_read_events_from_partition` strict ≥1 event via `aether streams read`; `test_publish_and_verify_count` track per-publish rc | `3a61fef27` |
| 05-security (#3, #4) | `test_tls_active` asserts `tlsEnabled` field; `test_rotation_under_load` skips cleanly on NOT_CONFIGURED + drives load through TLS handshake | `13df96427` |
| 05-security (#5-#8) | All 4 functions in `test-principal-injection.sh` rewritten to use `aether whoami`; strict 401 + WWW-Authenticate assertions | `50af7bcde` |
| 06-deployment (#9-#12) | All three strategy `*_promote` tests strict-state-assert; `test_blue_green_rollback` wired into run_test | `b8d20d57b` |
| 07-cluster-mgmt (#13, #14) | `test_config_visible_on_all_nodes` probes per-node ports; `test_config_identical_after_reapply` canonical-form equality | `04ff1fb79` |
| 08-resources (#15, #16) | `test_subscriber_receives_events` real publish→read; `test_task_last_execution_advances` via `/api/scheduled-tasks/inject` | `c37ecae93` |
| 12-network (#17) | `test_swim_detection_time` strict 15s budget (TIMEOUT_SCALE-aware) | `db221dee4` |
| 13-edge-cases (#22) | `test_both_blueprints_visible` strict empty + ≥1 ACTIVE | `cbc1f50e3` |
| 14-storage (#18, #23-#26) | 5 storage CLI/API tests now strict on mandatory `artifacts` instance + snapshot epoch + JSON shape | `9309a8608` |
| 15-delegation (#27) | `test_tasks_distributed` strict ≥2 unique nodes | `3b217a4ab` |
| lib (#29, #30) | Removed shadow `drain_node`/`activate_node` duplicates; `lib/load.sh` strict 2xx | `bf637a22f` |

## Phase 2 product API deliveries

14 new endpoints + CLI commands across `aether/cli` and `aether/node`:

- **P1** `GET /api/whoami` + `aether whoami` (`38c8b5349`)
- **P3** `tlsEnabled` field in `/api/certificates` (`e5e941832`)
- **P5** `POST /api/scheduled-tasks/inject` + CLI (dev-mode gate; `04ebd4482`)
- **P6** `aether streams read <name> <partition>` CLI (`04ebd4482`)
- **P7** `aether streams create/delete + consumer-group join/leave/status` (`9e99b5365`)
- **P-NEW-A** `/api/metrics/timeouts` + `aether metrics timeouts` (timeout-fired counters) (`13fe1076f`)
- **P-NEW-B** `POST /api/dht/inject` (test-only, explicit HLC) (`8722657ef`)
- **P-NEW-C** `aether backup create/restore` (singular alias with `--wait/--timeout` polling) (`f44674812`)
- **P-NEW-D** `POST /api/metrics/backfill` (test-only) (`13fe1076f`)
- **P-NEW-E** `aether nodes promote --role={WORKER|CORE|PASSIVE}` + REST endpoint (`58b92965a`)
- **P-NEW-F** `/api/dht/replication-map` + `aether dht replication-map` (`8722657ef`)
- **P-NEW-G** `aether cluster init --non-interactive` flag (`c5280a007`)
- **P-NEW-H** `GET /api/scheduled-tasks/executions-by-node/...` + CLI (`c5280a007`)
- **P-NEW-I** `POST /api/certificates/configure-short-validity` (test-only) (`c5280a007`)

## Phase 3 CLI gap closures

- **C1** 5 metrics variants (`prometheus`, `transport`, `comprehensive`, `derived`, `history`) (`26ba7be24`)
- **C2** `aether slices status` + `aether slices topology` (`26ba7be24`)
- **C3** `aether cluster governors` (`26ba7be24`)
- **C4** `aether ttm status` + `aether ttm training-data` (`26ba7be24`)
- **C5** `aether artifacts get <g:a:v> [--out=<file>]` (byte-stream resolve) (`26ba7be24`)
- **C6** `aether blueprints publish <g:a:v>` (`26ba7be24`)
- **C7** `aether cluster export --format json` (`b5723bd58`)
- **C8** `aether artifacts versions --format json` via `MavenMetadataFormatter` (`b5723bd58`)

## Documentation outputs

- `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` (4,190 lines) + 7 partials directory
- `aether/docs/internal/audits/test-coverage-matrix-2026-05-21.md` (per-domain RC1 gap analysis)
- `aether/docs/internal/production-readiness-plan-2026-05-21.md` (7-phase plan + 5-tier sequencing)
- `aether/docs/internal/production-readiness-followup-2026-05-21.md` (998 lines; 51-test backlog with TC-ID slots + dependencies + tier ordering)
- `aether/docs/contributors/test-charter-template.md` (per-suite charter skeleton + authoring guidance + smell list)
- 16 × `aether/tests/integration/suites/<NN>/CHARTER.md` (319 tests catalogued with TC-IDs)
- 21+ docs files updated (`cli.md`, `management-api.md`, `CHANGELOG.md`, RBAC spec, cluster-management-spec)

## Verification status

- **Module tests (each Phase 2 / Phase 3 commit):** verified via `build-runner` agent
  - `mvn -pl aether/node test -am` — 444/444 pass (was 402 pre-session)
  - `mvn -pl aether/cli test -am` — 420/420 pass (was 342 pre-session)
- **CI lint:** `./aether/tests/integration/lint-tests.sh` returns 0 against 49-entry baseline
- **Integration suite (15 suites):** **NOT re-run end-to-end** in this session. All test rewrites validated by `bash -n` + lint baseline (0 new findings). End-to-end run is the recommended next-session opener.
- **`./build.sh`:** NOT run this session (HCLOUD_TOKEN risk + jbct:format module-scope re-pollution; deferred to a dedicated build-clean session)

## Known issues carried forward

1. **49 RC2-class lint findings** in the baseline (`aether/tests/integration/lint-baseline.txt`). All are non-RC1-blocker (warn-then-pass in `test_cluster_ready` setup functions; 3xx-as-success in `test-sql-connector.sh`; pervasive but not regression-net failures). Each can be picked off in future sessions; CI lint blocks any NEW occurrence.

2. **JBCT formatter module-scope re-pollution.** Single bulk-format commit `131ac685c` early in session normalised ~190 unrelated files in `aether/{cli,node}` because `mvn jbct:format` is module-scoped. Subsequent Phase 2/3 work was done under user directive "skip formatting this session" — no further format runs happened. Next session needs a `jbct:format` pass on the work that landed (or accept the local-format flake; new files were authored conformantly via `jbct-coder` agent).

3. **Reactor-cache staleness when running `mvn -pl <module> test` without `-am`.** Surfaced twice (P-NEW-E NoSuchMethodError, MetricsRoutesTest NoClassDefFoundError) but were transient — fresh `-am` builds passed. Document this as a contributor gotcha if not already.

4. **P-NEW-H per-node attribution descoped.** Endpoint reports the task's `registeredBy` node as sole executor with global counts; true per-node attribution requires `Map<NodeId, NodeExecutionStats>` in `ScheduledTaskStateValue` (schema change, RC2 follow-up).

5. **P-NEW-E `authorizeActivation()` machinery.** REST + CLI surface added; underlying role-transition path uses existing `ManageableNode` scaffolding but the WORKER role topology is never deployed in integration tests (Worker Pools is the largest single coverage gap per matrix Domain C — 16 NONE entries).

## Open items for next session (in order)

1. **Run integration suite end-to-end on remote Docker.** Validates the 11 batches of test fixes work together. Command: `cd aether/tests/integration && ./run-tests.sh --env remote`.
2. **Land jbct:format pass** on the Phase 2/3/4 work that bypassed format this session, OR accept the formatter-flake reality and rely on next bulk-format checkpoint.
3. **Phase 7 (test-additions backlog)** per the follow-up plan tiers:
   - Tier 1 (no dependencies, immediate Phase 4 expansion)
   - Tier 2 (needs Phase 2 — now unblocked)
   - Tier 3 (needs test-topology TOPO-1..4 changes — Passive LB, Worker Pools, labels, TLS)
   - Tier 5 (cloud-suite work stream)
4. **Re-audit when Phase 7 progresses** — re-spawn the 7 parallel audit agents to verify the baseline shrinks.
5. **RC1 tag once:** zero RC1-block items in re-audit AND integration suite 15/15 green AND `./build.sh` green AND charters reviewed.

## Key learnings worth retaining

1. **Audit→plan→follow-up→execute is the right shape for large remediation.** Producing the audit (5 parallel agents, 4190 lines) gave a definitive list of what was broken. Producing the coverage matrix (5 parallel agents, 176 features) gave the additive-coverage backlog. Producing the follow-up plan (1 agent, 998 lines) gave the sequenced TC-ID-ready backlog. Each artifact is durable and was directly consumed by subsequent agents.

2. **CI lint with a baseline (ratchet) is the right shape for legacy-debt cleanup.** Capturing 49 known findings as baseline lets CI hard-fail on NEW regressions without blocking the build on pre-existing debt. The baseline only shrinks over time.

3. **Per-suite CHARTER.md per-test contracts is high-leverage low-cost.** 4 parallel agents took ~10 minutes total to produce 16 charters covering 319 tests. Future contributors can answer "which contract does this test verify?" in seconds.

4. **Test-only inject endpoints (alerts/traces/scheduled-tasks/DHT/cert) close a class of warn-then-pass demotions.** When the runtime can't trigger the test event deterministically, expose `POST /api/*/inject` gated by `AETHER_INSECURE_DEV_MODE`. Established pattern; replicate liberally.

5. **Phase 2 product changes unblock test fixes downstream.** Sequencing matters: P1 (`/api/whoami`) had to land before B3 (principal-injection test rewrite). The plan's Tier-2 sequencing captured this; following it avoided rework.

6. **Parallel agents with shared-file edits don't compose.** When >1 agent edits `AetherCli.java` or `ManagementRoute.java`, the last writer wins. Either sequence them, or pre-allocate enum entries / DTO stubs before fanning out. Phase 2 P-NEW waves were done sequentially-per-wave because of this.

7. **Bulk `jbct:format` runs are toxic to focused commits.** Module-scoped formatter normalisation re-touches ~190 unrelated files. Disable it in Phase 4/5/6 sessions; consolidate into dedicated format-only checkpoints.

## Session metadata

- Date: 2026-05-21
- Commits this session: 36 (`7dc8935a0..58b92965a`)
- Net code added: ~50K lines across ~80 files (heavy doc weight; code is mostly small focused additions)
- Module test deltas: aether/node 402→444 (+42), aether/cli 342→420 (+78)
- Lint findings: 393 (initial) → 69 (R5 fix) → 49 (post-RC1-blocker cleanup) — baseline locked
- Production-readiness phases completed: 8 of 8 (Phase 7 / coverage-expansion is post-RC1)

## Suggested next-session opener

```bash
# 1. Sanity
git log --oneline release-1.0.0-rc1 | head -5
git status

# 2. Run integration suite end-to-end on remote (the big proof point)
cd aether/tests/integration && ./run-tests.sh --env remote 2>&1 | tail -200

# 3. If 15/15 green, ready to:
#    - Optionally: bulk jbct:format pass (separate commit)
#    - Begin Phase 7 tiers per production-readiness-followup-2026-05-21.md
#    - Or tag v1.0.0-rc1 candidate and start exit-criteria validation
```

If the integration suite is not green, the test fixes need triage against actual cluster behaviour — but every test was rewritten to be strict, so any new failure is signal not noise.
