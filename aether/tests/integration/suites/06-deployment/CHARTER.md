# Suite 06-deployment Charter

**Test-ID convention:** `TC-06-DEPLOYMENT-NNN` — zero-padded 3-digit index, stable across reorganisations, allocated in `run_test` order.

**Charter purpose:** Anchor every test to the unified-deploy contract: each strategy (immediate / rolling / canary / blue-green) must start, reach a strategy-specific intermediate state, promote, and complete; blue-green must also rollback. Schema migrations are exercised under the same suite because slice deployment depends on per-datasource migration convergence.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | A successful immediate deploy yields ≥ 1 ACTIVE slice instance and leaves the cluster healthy. | `aether/docs/specs/unified-deploy-spec.md §2` (Immediate strategy) |
| C2 | Rolling deploy: v1 deployed, v2 published, both versions tracked under a single deploymentId; `promote` transitions deploymentId to a strategy-appropriate terminal state (`PROMOTED`); `complete` produces `COMPLETED`. | `unified-deploy-spec.md §3` (Rolling) |
| C3 | Canary deploy: v1 deployed, canary v2 published with traffic-split; `promote` succeeds; `complete` produces `COMPLETED`. | `unified-deploy-spec.md §4` (Canary) |
| C4 | Blue-green deploy: blue v1 + green v2 deployed, `promote` switches active version; **`rollback` switches back to the prior version**; `complete` produces `COMPLETED`. | `unified-deploy-spec.md §5` (Blue-green) |
| C5 | `aether deploy …` CLI subcommands route correctly through `/api/deployments` and return structured output suitable for `deployment list`. | `aether/docs/reference/cli.md` (deploy); management-api.md |
| C6 | Per-datasource schema status is queryable; a schema migration converges (`currentVersion ≥ 900` for V900 fixture) under the documented retry contract; failed-state migrations honour the "not in FAILED state" `409 Conflict` body. | `aether/docs/specs/schema-spec.md` (or `[CONTRACT-GAP]` if no canonical spec); slice-lifecycle.md |
| C7 | Cluster remains healthy with all 5 nodes after every deploy strategy and after schema migrations. | `test-readiness-contract.md §1.1` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-06-DEPLOYMENT-001 | `test_cluster_ready` | `test-deploy-immediate.sh:11` | C7 | smoke | `wait_for_cluster_ready` + `push_blueprint`; push success unasserted. Audit §1.8 GREEN-STICKER (RC2). |
| TC-06-DEPLOYMENT-002 | `test_immediate_deploy` | `test-deploy-immediate.sh:21` | C1, C5 | core | `aether_failover deploy …` result non-empty. Audit §1.8 TAUTOLOGY (RC2). |
| TC-06-DEPLOYMENT-003 | `test_cluster_healthy_after_deploy` | `test-deploy-immediate.sh:28` | C7 | core | Warn-demoted quiesce; strict `assert_cluster_healthy`. |
| TC-06-DEPLOYMENT-004 | `test_slices_active` | `test-deploy-immediate.sh:34` | C1 | core | `wait_for_slices_active 1 60`; `assert_gt slices_total_instances 0`. |
| TC-06-DEPLOYMENT-005 | `test_cluster_ready` | `test-deploy-rolling.sh:12` | C7 | smoke | Warn-demoted task readiness. |
| TC-06-DEPLOYMENT-006 | `test_rolling_start` | `test-deploy-rolling.sh:18` | C2, C5 | core | Two quiesce-demotions; `assert_contains "$result" "deploymentId"`. Audit §1.8 NARROW (RC2). |
| TC-06-DEPLOYMENT-007 | `test_rolling_promote` | `test-deploy-rolling.sh:31` | C2 | core | RC1-blocker #9 CLOSED in b8d20d57b — `deploy_status` now strict-asserted to reach `PROMOTED` (previously logged but never asserted). |
| TC-06-DEPLOYMENT-008 | `test_rolling_complete` | `test-deploy-rolling.sh:61` | C2 | core | `assert_contains "COMPLETED"`. |
| TC-06-DEPLOYMENT-009 | `test_cluster_ready` | `test-deploy-canary.sh:17` | C7 | smoke | Warn-demoted readiness. |
| TC-06-DEPLOYMENT-010 | `test_canary_start` | `test-deploy-canary.sh:23` | C3, C5 | core | `deployment list` contains substring `deploymentId`. Audit §1.8 NARROW (RC2). |
| TC-06-DEPLOYMENT-011 | `test_canary_list` | `test-deploy-canary.sh:40` | C3, C5 | regression-net | `deploy_list` contains substring `CANARY`. Audit §1.8 NARROW (RC2). |
| TC-06-DEPLOYMENT-012 | `test_canary_promote` | `test-deploy-canary.sh:46` | C3 | core | RC1-blocker #10 CLOSED in b8d20d57b — promote strict-asserts terminal state. |
| TC-06-DEPLOYMENT-013 | `test_canary_complete` | `test-deploy-canary.sh:72` | C3 | core | Branched: idempotent COMPLETED path OR strict `assert_contains "COMPLETED"`. Audit §1.8 NARROW (RC2). |
| TC-06-DEPLOYMENT-014 | `test_cluster_ready` | `test-deploy-blue-green.sh:12` | C7 | smoke | Warn-demoted readiness. |
| TC-06-DEPLOYMENT-015 | `test_blue_green_start` | `test-deploy-blue-green.sh:18` | C4, C5 | core | Substring deploymentId, double quiesce-demotion. Audit §1.8 NARROW (RC2). |
| TC-06-DEPLOYMENT-016 | `test_blue_green_promote` | `test-deploy-blue-green.sh:32` | C4 | core | RC1-blocker #11 CLOSED in b8d20d57b — promote strict-asserts switch to new active version. |
| TC-06-DEPLOYMENT-017 | `test_blue_green_complete` | `test-deploy-blue-green.sh:126` | C4 | core | `assert_contains "COMPLETED"`. |
| TC-06-DEPLOYMENT-018 | `test_blue_green_rollback` | `test-deploy-blue-green.sh:60` | C4 | core | RC1-blocker #12 CLOSED in b8d20d57b — now wired in run-list (L147) and asserts return to prior version. |
| TC-06-DEPLOYMENT-019 | `test_cluster_ready` | `test-schema-migration.sh:20` | C7 | smoke | Discovers tracked datasource via `wait_for` predicate; explicit log_fail. |
| TC-06-DEPLOYMENT-020 | `test_schema_status` | `test-schema-migration.sh:34` | C6 | regression-net | Per-datasource `schema_status` non-empty. Audit §1.8 TAUTOLOGY (RC2). |
| TC-06-DEPLOYMENT-021 | `test_schema_status_all` | `test-schema-migration.sh:49` | C6 | regression-net | Asserts JSON-shape leading char only. Audit §1.8 NARROW (RC2). |
| TC-06-DEPLOYMENT-022 | `test_trigger_migration` | `test-schema-migration.sh:79` | C6 | core | `wait_for` polls `currentVersion ≥ 900` within 60s; strict log_fail with final-version diagnostic. |
| TC-06-DEPLOYMENT-023 | `test_schema_retry` | `test-schema-migration.sh:96` | C6 | core | Accepts 2xx OR non-2xx with documented body `'not in FAILED state'` (409 Conflict). Contract-aware. |
| TC-06-DEPLOYMENT-024 | `test_cluster_healthy_after_migration` | `test-schema-migration.sh:128` | C7 | core | `assert_cluster_healthy`. |

**Total tests:** 24.

---

## Suite-level invariants

- **Pre-conditions:** Cluster A non-destructive, 5 nodes, NODE_COUNT=5; blueprint `test-persistence` pre-pushed; requires `CAP_PERSISTENCE` (per `suite.conf`). Slice artifacts v1 and v2 reachable in the integration artifact-repo.
- **Side effects:** Deploys slices (immediate, rolling, canary, blue-green) under separate deployment ids; promotes and completes them. Schema migrations write to per-datasource schema-version tables. Each strategy file has its own `cleanup()` trap.
- **Cleanup discipline:** `cleanup()` traps registered in `test-deploy-rolling.sh:71`, `test-deploy-canary.sh:89`, `test-deploy-blue-green.sh:136` — invoke `deploy_cleanup` to abort/cancel any non-terminal deployment. Schema migration test re-discovers datasource on each run.
- **State assumptions:** `aether_failover deploy` is the only mutation path; `await_generation_quiesced` is warn-demoted in all strategy files (replication may run against pre-quiesced state — RC2).

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-06-DEPLOYMENT-001 / 005 / 009 / 014 | `test_cluster_ready` warn-demotes task readiness | Audit §1.8 / §2.1 warn-then-pass census (RC2) |
| TC-06-DEPLOYMENT-002 | `test_immediate_deploy` accepts non-empty body as success | Audit §1.8 TAUTOLOGY (RC2) |
| TC-06-DEPLOYMENT-006 / 010 / 011 / 013 / 015 | Substring matches on deploymentId / strategy label / `COMPLETED` | Audit §1.8 NARROW (RC2) |
| TC-06-DEPLOYMENT-020 | `test_schema_status` accepts non-empty body | Audit §1.8 TAUTOLOGY (RC2) |
| TC-06-DEPLOYMENT-021 | Asserts JSON leading-char only, no field content | Audit §1.8 NARROW (RC2) |
| C6 | No canonical `aether/docs/specs/schema-spec.md` found — schema retry/migration contract documented inline in test code | `[CONTRACT-GAP]` — RC2 follow-up |

No RC1-open findings remain in this suite.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter-author | Initial charter from audit 2026-05-21; reflects RC1-blockers #9–#12 closed in commit b8d20d57b (promote tests strict + blue-green rollback wired into run-list) |
