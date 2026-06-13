# Suite 10-database Charter

**Test-ID convention:** `TC-10-DATABASE-NNN`.

**Scope:** Schema-management contracts on top of declarative PostgreSQL persistence (`pg-persistence-spec`). Covers schema baseline (initial migration), retry semantics for FAILED states, and versioned-migration progression. Per audit §1.12, all 18 functions are SOUND and every prior green-sticker has been remediated via the datasource-discovery pattern.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state before any schema probe runs. | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | A tracked datasource (declared via `@PgSql`) becomes discoverable through `/api/schema/status` GET (filtered for `"datasource":"<name>"`) within a bounded window. | `aether/docs/specs/pg-persistence-spec.md`; `aether/docs/reference/management-api.md §Schema` |
| C3 | `/api/schema/baseline` accepts a discovered datasource and returns 2xx with a non-empty body. | `aether/docs/reference/management-api.md §Schema Baseline` |
| C4 | Post-baseline `/api/schema/status` returns a non-`{UNKNOWN,FAILED,empty}` status for the datasource (baseline call landed in orchestrator state). | `aether/docs/specs/pg-persistence-spec.md §Schema Lifecycle` |
| C5 | Slices backed by the baselined schema reach `instances > 0` (schema baseline does not block slice activation). | `aether/docs/specs/pg-persistence-spec.md`; `aether/docs/specs/unified-deploy-spec.md` |
| C6 | Calling `/api/schema/baseline` twice on the same datasource is idempotent (second call returns 2xx + non-empty). | `aether/docs/specs/pg-persistence-spec.md §Idempotency` |
| C7 | `/api/schema/retry` either succeeds (datasource is in FAILED state and transitions out) OR returns a documented `not in FAILED state` envelope (datasource is not retryable). | `aether/docs/specs/pg-persistence-spec.md §Retry`; `aether/docs/reference/management-api.md §Schema Retry` |
| C8 | Post-retry `/api/schema/status` returns FAILED (orchestrator preserves FAILED for the documented contract) or transitions to a documented healthy state — never empty/UNKNOWN. | `aether/docs/specs/pg-persistence-spec.md §Retry` |
| C9 | `/api/schema/retry` is idempotent (same contract on repeated calls). | `aether/docs/specs/pg-persistence-spec.md §Idempotency` |
| C10 | `/api/schema/status` for a versioned datasource (fixture `test-persistence` ships V900) reports `currentVersion > 0` (versioned migrations applied). | `aether/docs/specs/pg-persistence-spec.md §Versioned Migrations` |
| C11 | `/api/schema/status` includes schema-history entries (non-empty, non-`null`) for migrated datasources. | `aether/docs/specs/pg-persistence-spec.md §Schema History` |
| C12 | `/api/schema/status` (global, no datasource filter) returns a JSON envelope (starts with `[` or `{`). | `aether/docs/reference/management-api.md §Schema` |
| C13 | Cluster remains healthy after each schema workload. | `aether/docs/specs/test-readiness-contract.md §1.1` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-10-DATABASE-001 | `test_cluster_ready` | `test-schema-baseline.sh:29` | C1, C2 | smoke | Pushes + deploys schema-backed slice; `wait_for "tracked datasource discovered"` strict 60s — hard log_fail on miss. This is the datasource-discovery gate that unlocks all subsequent strict assertions. |
| TC-10-DATABASE-002 | `test_schema_baseline_endpoint` | `test-schema-baseline.sh:49` | C3 | core | Strict: empty datasource → fail; `api_post` failure → fail; empty body → fail (`assert_ne`). |
| TC-10-DATABASE-003 | `test_schema_status_after_baseline` | `test-schema-baseline.sh:66` | C4 | core | Case match — empty/UNKNOWN/FAILED → log_fail; anything else → log_pass. Acknowledges orchestrator landed the baseline. |
| TC-10-DATABASE-004 | `test_slices_active_after_baseline` | `test-schema-baseline.sh:82` | C5 | core | `assert_gt instances 0` against real cluster state. |
| TC-10-DATABASE-005 | `test_baseline_idempotent` | `test-schema-baseline.sh:90` | C6 | core | Second POST returns 2xx + non-empty. |
| TC-10-DATABASE-006 | `test_cluster_healthy_after_baseline` | `test-schema-baseline.sh:103` | C13 | core | — |
| TC-10-DATABASE-007 | `test_cluster_ready` | `test-schema-retry.sh:22` | C1, C2 | smoke | Strict datasource-discovery gate. |
| TC-10-DATABASE-008 | `test_schema_status_before_retry` | `test-schema-retry.sh:37` | C4 | smoke | Strict non-empty status precondition for retry test. |
| TC-10-DATABASE-009 | `test_schema_retry_endpoint` | `test-schema-retry.sh:54` | C7 | core | Either `schema_retry` succeeds (2xx) OR response contains documented `not in FAILED state` message → pass; else hard fail with body dump. Inline comment acknowledges fault-injection TODO for the deeper FAILED→HEALTHY transition. |
| TC-10-DATABASE-010 | `test_schema_status_after_retry` | `test-schema-retry.sh:83` | C8 | core | Case match on status field: empty/UNKNOWN → fail; FAILED → pass (expected per orchestrator contract); anything else → pass. |
| TC-10-DATABASE-011 | `test_retry_idempotent` | `test-schema-retry.sh:100` | C9 | core | Same contract as TC-10-DATABASE-009 on repeated call. |
| TC-10-DATABASE-012 | `test_cluster_healthy_after_retry` | `test-schema-retry.sh:123` | C13 | core | — |
| TC-10-DATABASE-013 | `test_cluster_ready` | `test-schema-versioned.sh:20` | C1, C2 | smoke | Strict datasource-discovery gate. |
| TC-10-DATABASE-014 | `test_schema_status_endpoint` | `test-schema-versioned.sh:35` | C4 | core | Strict non-empty after discovery. |
| TC-10-DATABASE-015 | `test_migrations_applied` | `test-schema-versioned.sh:53` | C10 | core | `[ "$current_version" -gt 0 ]` with sentinel `${current_version:--1}` to distinguish missing field from zero. Compared against V900 migration shipped by `test-persistence` fixture. Prior `2>/dev/null` swallow REMEDIATED per audit §1.12. |
| TC-10-DATABASE-016 | `test_schema_history_entries` | `test-schema-versioned.sh:72` | C11 | core | Strict non-empty AND not literal `null`. |
| TC-10-DATABASE-017 | `test_global_schema_status` | `test-schema-versioned.sh:91` | C12 | core | Case match on JSON envelope starting with `[` or `{`. |
| TC-10-DATABASE-018 | `test_cluster_healthy_after_schema_check` | `test-schema-versioned.sh:105` | C13 | core | — |

---

## Suite-level invariants

- **Pre-conditions:** cluster A (non-destructive). PostgreSQL backing store is present (the test-persistence slice provides the schema fixture, including V900 migration).
- **Side effects:** baselines the datasource; calls retry idempotently; observes schema-history entries. **Critical:** these tests are SAFE to re-run because every retry path is idempotent (C6, C9) and the orchestrator preserves status semantics on repeat calls.
- **Cleanup discipline:** no explicit EXIT trap. State persists in PostgreSQL across runs — this is by design (schema history is observable across test re-runs).
- **Datasource-discovery pattern:** every test file starts with `wait_for "tracked datasource discovered"` before issuing per-datasource calls. This is the architectural lever that turned all prior endpoint-responds green-stickers into strict assertions (audit §1.12 calls this out as a model for other rewrites).
- **Fixture coupling:** `test_migrations_applied` (TC-10-DATABASE-015) is fixture-coupled to V900 in `test-persistence`. If the fixture drops V900, this test becomes a false positive (would pass at currentVersion=0 against sentinel=-1). Keep fixture and assertion in sync.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-10-DATABASE-009 | `schema_retry` cannot drive a real FAILED→HEALTHY transition without fault injection. Inline comment acknowledges the gap; the test verifies the orchestrator's documented contract message for the "not in FAILED state" path. | Audit §1.12 SOUND — contract-driven, not a green-sticker. Fault-injection TODO captured in code comment. |
| TC-10-DATABASE-010 | "FAILED is acceptable post-retry" branch relies on orchestrator contract documentation. If the orchestrator contract changes (e.g., post-retry should always transition out of FAILED), this assertion will silently mis-track. | Audit §1.12 SOUND — pinned to current orchestrator contract; revisit if `pg-persistence-spec` adds explicit retry-state-transition rules. |
| TC-10-DATABASE-015 | Coupled to V900 fixture in `test-persistence`. | Fixture-test coupling; document in test header comment. |

### Contract gaps

- None at the contract level for this suite — `pg-persistence-spec.md` and `management-api.md §Schema` cover all assertions. The remaining limitations (fault injection in C7 path, fixture coupling in C10) are test-side, not contract-side.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter authoring agent | Initial charter; TC-10-DATABASE-001 through TC-10-DATABASE-018 catalogued from audit §1.12. All prior green-stickers recorded as REMEDIATED via the datasource-discovery pattern. |
