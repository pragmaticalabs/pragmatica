# Suite 14-storage Charter

**Test-ID convention:** `TC-14-STORAGE-NNN` — zero-padded 3-digit, stable across reorgs.

**Scope:** Hierarchical-storage management surfaces — the per-node `/api/storage*` REST routes, the per-cluster `/api/cluster/storage*` routes, and the `aether storage *` CLI subcommand. This suite covers the management API only; the actual storage data-path (read/write/tier-waterfall) is exercised elsewhere.

**Suite health note (audit §1.16):** This is the **weakest suite in the audit**. 4 of 9 tests silently pass on absent functionality (`skip_test` on CLI failure; `log_warn → return 0` on missing artifacts instance and on snapshot empty response). The "artifacts" instance is mandatory for the artifact-repo service used by every deploy; its absence is a real product regression that this suite does NOT catch as written.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches canonical "ready" state before any storage probe runs | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | `aether storage list` CLI returns a non-empty, well-formed listing of configured storage instances; CLI exit code is 0 on success | `aether/docs/specs/storage-management-api-spec.md §4` (CLI Commands); `aether/docs/reference/cli.md` |
| C3 | `aether storage status <name>` CLI returns instance-detail output (tiers + readiness) for an instance discovered via the REST surface; exit code 0 | `aether/docs/specs/storage-management-api-spec.md §4` |
| C4 | `aether storage list --format json` emits parseable JSON conforming to the StorageInstanceList shape | `aether/docs/specs/storage-management-api-spec.md §3` (Response Records); `§4` (CLI Output Formats) |
| C5 | GET `/api/storage` returns a JSON object with an `instances` array — non-`{}` payload required for sound coverage | `aether/docs/specs/storage-management-api-spec.md §1` (GET /api/storage) |
| C6 | The default `artifacts` storage instance is ALWAYS present in `/api/storage` — it backs the artifact-repo service used by every blueprint deploy | `aether/docs/specs/storage-management-api-spec.md §1`; `aether/docs/specs/hierarchical-storage-spec.md §2.2` (Storage Instance) |
| C7 | GET `/api/storage/{name}` returns per-instance detail including `tiers` array and `readiness` status object | `aether/docs/specs/storage-management-api-spec.md §1` (GET /api/storage/{name}) |
| C8 | POST `/api/storage/{name}/snapshot` triggers a snapshot operation and returns a non-empty body containing an `epoch` field | `aether/docs/specs/storage-management-api-spec.md §1` (POST /api/storage/{name}/snapshot) |
| C9 | GET `/api/cluster/storage` returns cluster-wide storage view including the `instances` set | `aether/docs/specs/storage-management-api-spec.md §2` (GET /api/cluster/storage) |
| C10 | GET `/api/cluster/storage/{name}` returns cluster-wide instance detail including `nodeCount` and `nodes` array | `aether/docs/specs/storage-management-api-spec.md §2` (GET /api/cluster/storage/{name}) |

**Contract gaps surfaced by this audit:**
- `[CONTRACT-GAP-14.A]` — **CLI exit-code contract is not pinned by spec.** `storage-management-api-spec.md §4` enumerates the CLI commands but does not say "CLI MUST exit 0 on success and non-zero on failure". The three CLI tests (TC-14-STORAGE-002, -003, -004) use `2>/dev/null || true` to mask CLI failure as `skip_test` — a contract that distinguishes "no instances configured" (legitimate empty) from "CLI crashed" (real regression) is needed. This is the only suite where the silent-stderr antipattern survives per audit §2.1.
- `[CONTRACT-GAP-14.B]` — **JSON shape contract is not assertable from current spec.** §3 defines response records but the test (`test_cli_storage_list_json`) only checks the first non-whitespace char is `{` or `[`. A spec sentence pinning required top-level fields (e.g. "MUST be a JSON object with an `instances` array of StorageInstanceSummary records") would let the test use a real parser.
- `[CONTRACT-GAP-14.C]` — **Mandatory-instance contract is missing.** The audit calls "artifacts" mandatory because the artifact-repo service depends on it, but `storage-management-api-spec.md` does not enumerate "the storage instances that MUST exist on every node". Without this, TC-14-STORAGE-007's `log_warn → return 0` on missing-artifacts is defensible. Add a spec section listing baseline instances.
- `[CONTRACT-GAP-14.D]` — **Snapshot empty-body semantics undefined.** §1 says the endpoint "triggers a snapshot" but does not pin the response shape contract — TC-14-STORAGE-009 reduces to a stub because "empty response" might mean "endpoint not wired", "snapshot async + not-ready", or "tier doesn't support snapshots". Needs a deterministic response contract (e.g. `{"epoch": N, "status": "TRIGGERED|PENDING|UNSUPPORTED"}`).
- `[CONTRACT-GAP-14.E]` — **Passive LB / worker-pool storage management is not covered by this suite at all.** The hierarchical-storage spec describes tier abstraction and lifecycle but no test in this suite verifies tier-waterfall correctness, block promotion, or per-tier readiness — it stops at endpoint smoke. RC1-blocker resolution in 9309a8608 (per task brief) closed 5 issues but the suite-as-written still cannot distinguish "storage subsystem is functional" from "storage subsystem is mocked into an empty list".

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-14-STORAGE-001 | `test_cluster_ready` | `test-storage-cli.sh:9` | C1 | smoke | wait_for_cluster_ready + unconditional `log_pass`. Acceptable for setup gate. |
| TC-14-STORAGE-002 | `test_cli_storage_list` | `test-storage-cli.sh:20` | C2 | core | **GREEN-STICKER (audit §1.16 HIGH; §2.2 row 4): `aether_failover storage list 2>/dev/null \|\| true` masks CLI errors as "no instances configured" → `skip_test`.** A real CLI regression (binary crash, missing subcommand) silently skips. Violates `feedback_silent_stderr_is_a_trap`. RC1-blocker reportedly CLOSED in 9309a8608 (per task brief) — verify CLI failure now produces hard fail, not skip. |
| TC-14-STORAGE-003 | `test_cli_storage_status` | `test-storage-cli.sh:46` | C3 | core | Same GREEN-STICKER as TC-14-STORAGE-002. Discovers an instance name via REST, then silent-skip on empty. RC1-blocker reportedly CLOSED in 9309a8608. |
| TC-14-STORAGE-004 | `test_cli_storage_list_json` | `test-storage-cli.sh:71` | C2, C4 | core | **WEAK + GREEN-STICKER (audit §1.16 MEDIUM/HIGH): `^\s*[\{\[]` only verifies the leading character.** Truncated/malformed `{"instances":...` (no closing brace) PASSES. Plus same silent-skip on empty. Tracked as `[CONTRACT-GAP-14.B]`. |
| TC-14-STORAGE-005 | `test_cluster_ready` | `test-storage-management.sh:9` | C1 | smoke | Same as TC-14-STORAGE-001. |
| TC-14-STORAGE-006 | `test_storage_list` | `test-storage-management.sh:15` | C5 | core | Audit §1.16 MEDIUM — accepts `{}` (no instances) as PASS. Title says "returns instance list" but `{}` proves only that the endpoint exists. Plus skip-on-empty. |
| TC-14-STORAGE-007 | `test_storage_list_contains_artifacts` | `test-storage-management.sh:37` | C6 | core | **GREEN-STICKER (audit §1.16 HIGH; §2.2 row 18): missing "artifacts" instance demoted to `log_warn → return 0`.** The "artifacts" instance is mandatory for artifact-repo (used by every deploy); its absence is a real product regression. RC1-blocker reportedly CLOSED in 9309a8608 — verify the missing-artifacts branch now `log_fail`s. Tracked as `[CONTRACT-GAP-14.C]`. |
| TC-14-STORAGE-008 | `test_storage_instance_detail` | `test-storage-management.sh:58` | C7 | core | Discovers a name; `assert_contains` for "tiers" and "readiness"; skip on empty. SOUND once we have a name. `assert_contains` is substring-grep — vulnerable if the substring appears in an error payload, but API surface makes that unlikely. |
| TC-14-STORAGE-009 | `test_storage_snapshot` | `test-storage-management.sh:74` | C8 | core | **GREEN-STICKER (audit §1.16 HIGH; §2.3 row 5): empty body → `log_warn → return 0`.** The whole test IS the trigger; empty body means "not wired yet" — a real gap. `assert_contains "$snapshot" "epoch"` is unreachable on empty path. RC1-blocker reportedly CLOSED in 9309a8608 — verify. Tracked as `[CONTRACT-GAP-14.D]`. |
| TC-14-STORAGE-010 | `test_cluster_storage_view` | `test-storage-management.sh:87` | C9 | regression-net | Audit §1.16 LOW (WEAK) — `assert_contains result "instances"` substring-grep on JSON; adequate for endpoint smoke. |
| TC-14-STORAGE-011 | `test_cluster_storage_detail` | `test-storage-management.sh:98` | C10 | core | Discovers name; `assert_contains` for "nodeCount" and "nodes"; skip on empty. SOUND. |

---

## Suite-level invariants

- **Pre-conditions:** Cluster A (non-destructive). Default "artifacts" storage instance must exist (provisioned at bootstrap by the artifact-repo service). NODE_COUNT=5 assumed for cluster-wide views; tests use `nodeCount`/`nodes` field presence (not a specific value) to stay portable.
- **Side effects:** This suite is **non-destructive by design** — only the `test_storage_snapshot` POST mutates state, and snapshots are append-only (no rollback needed). Suite leaves cluster in same state as it found it.
- **Cleanup discipline:** No EXIT trap needed — no destructive operations. Storage state is observed-only across all 11 tests.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-14-STORAGE-002 | CLI failure silently skipped (`2>/dev/null \|\| true`) | audit §1.16 HIGH / §2.2 row 4; CLOSED in 9309a8608 (verify) |
| TC-14-STORAGE-003 | Same silent-skip on CLI failure | audit §1.16 HIGH; CLOSED in 9309a8608 (verify) |
| TC-14-STORAGE-004 | Leading-char regex accepts truncated/malformed JSON; silent-skip | audit §1.16 HIGH+MEDIUM; CLOSED in 9309a8608 (verify); `[CONTRACT-GAP-14.B]` |
| TC-14-STORAGE-006 | `{}` accepted as "list returns instances" | audit §1.16 MEDIUM |
| TC-14-STORAGE-007 | Missing "artifacts" instance demoted to warn → return 0 | audit §1.16 HIGH / §2.2 row 18; CLOSED in 9309a8608 (verify); `[CONTRACT-GAP-14.C]` |
| TC-14-STORAGE-009 | Snapshot empty body → warn → return 0 | audit §1.16 HIGH / §2.3 row 5; CLOSED in 9309a8608 (verify); `[CONTRACT-GAP-14.D]` |
| TC-14-STORAGE-010 | Substring-grep "instances" on JSON instead of structural parse | audit §1.16 LOW |
| (suite) | No tier-waterfall, block-promotion, or per-tier readiness coverage | `[CONTRACT-GAP-14.E]` — RC2 |
| (suite) | No passive LB / worker-pool storage management coverage | `[CONTRACT-GAP-14.E]` — RC2 |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter pass | Initial charter — 11 tests catalogued from audit §1.16 |
