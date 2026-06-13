# Suite 09-artifacts Charter

**Test-ID convention:** `TC-09-ARTIFACTS-NNN`.

**Scope:** Artifact store contracts — push, resolve, DHT replication, and large-payload boundaries. This is the strongest suite in the audit batch (audit §1.11) because every functional assertion terminates in a cryptographic SHA-256 equality between source and resolved bytes — assertions are non-fakeable.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state before any artifact probe runs. | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | Artifact-push endpoint accepts a binary payload and returns 2xx with a coordinate-bearing response (artifact addressable by name + version). | `aether/docs/reference/management-api.md §Artifacts` |
| C3 | Artifact-resolve endpoint returns the byte-exact payload previously pushed under the same coordinate. | `aether/docs/reference/management-api.md §Artifacts`; `aether/docs/specs/dht-resilience-spec.md` |
| C4 | Resolved bytes hash to the same SHA-256 as the source bytes (cryptographic round-trip integrity). | `aether/docs/reference/management-api.md §Artifacts` |
| C5 | After push to one node and a bounded replication window, the artifact resolves with identical bytes from a different node (DHT replication). | `aether/docs/specs/dht-resilience-spec.md §Replication` |
| C6 | Cross-node resolve returns SHA-256-identical bytes (replication is byte-faithful, not lossy). | `aether/docs/specs/dht-resilience-spec.md §Replication` |
| C7 | Artifacts at size boundaries (64KB, 128KB, 1MB, 5MB) push and resolve with byte equality (no truncation, chunking, or pagination defects). | `aether/docs/reference/management-api.md §Artifacts` (size-class behaviour); `[CONTRACT-GAP]` for explicit boundary policy. |
| C8 | Cluster remains healthy after artifact load (push/resolve does not destabilise membership or leader). | `aether/docs/specs/test-readiness-contract.md §1.1` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-09-ARTIFACTS-001 | `test_cluster_ready` | `test-artifact-push-resolve.sh:24` | C1 | smoke | `wait_for_cluster_ready` strict; `wait_for_all_tasks_active` demoted to log_warn (matches lib pattern, not test-local). |
| TC-09-ARTIFACTS-002 | `test_generate_artifact` | `test-artifact-push-resolve.sh:30` | C2 | smoke | `assert_gt size 0` on locally-generated test artifact. |
| TC-09-ARTIFACTS-003 | `test_push_artifact` | `test-artifact-push-resolve.sh:37` | C2 | core | Strict `200 <= status < 300` on `/api/artifacts` POST. |
| TC-09-ARTIFACTS-004 | `test_resolve_artifact` | `test-artifact-push-resolve.sh:53` | C3 | core | Strict 2xx with acknowledged 2s replication wait at L55. |
| TC-09-ARTIFACTS-005 | `test_checksum_matches` | `test-artifact-push-resolve.sh:68` | C4 | core | Exact SHA-256 equality via `assert_eq` — non-fakeable round-trip integrity. |
| TC-09-ARTIFACTS-006 | `test_cluster_healthy_after` | `test-artifact-push-resolve.sh:75` | C8 | core | Exact `health=="healthy"` via aether CLI. |
| TC-09-ARTIFACTS-007 | `test_cluster_ready` | `test-artifact-replication.sh:26` | C1 | smoke | Strict + `assert_ge nodes 2` (replication test requires ≥2 nodes). |
| TC-09-ARTIFACTS-008 | `test_identify_second_node` | `test-artifact-replication.sh:34` | C5 | regression-net | If endpoint provided → log_pass; else attempt grep on address/host; else fall back to `CLUSTER_ENDPOINT` (gateway round-robin). AMBER per audit §1.11 — fallback-to-same-endpoint is acknowledged; gateway dispatch still exercises DHT replication. |
| TC-09-ARTIFACTS-009 | `test_push_to_primary` | `test-artifact-replication.sh:72` | C2 | core | Strict 2xx. |
| TC-09-ARTIFACTS-010 | `test_wait_for_replication` | `test-artifact-replication.sh:89` | C5 | regression-net | 10s sleep + unconditional `log_pass`. GREEN-STICKER per audit §1.11, LOW — decorative; the subsequent resolve+integrity tests are strict, so the SHA-256 equality catches replication failure. |
| TC-09-ARTIFACTS-011 | `test_resolve_from_second_node` | `test-artifact-replication.sh:96` | C5 | core | Strict 2xx from the secondary endpoint (or gateway-round-robin fallback per TC-09-ARTIFACTS-008). |
| TC-09-ARTIFACTS-012 | `test_integrity_across_nodes` | `test-artifact-replication.sh:109` | C6 | core | Strict SHA-256 equality across primary-push and secondary-resolve. Non-fakeable. |
| TC-09-ARTIFACTS-013 | `test_cluster_ready` | `test-large-artifact.sh:18` | C1 | smoke | Strict + warn on task-active (lib pattern). |
| TC-09-ARTIFACTS-014 | `test_64kb_boundary` | `test-large-artifact.sh:67` | C7, C4 | core | Delegates to `push_and_verify_size` helper — strict 2xx + SHA-256 equality. |
| TC-09-ARTIFACTS-015 | `test_128kb` | `test-large-artifact.sh:71` | C7, C4 | core | Same helper. |
| TC-09-ARTIFACTS-016 | `test_1mb` | `test-large-artifact.sh:75` | C7, C4 | core | Same helper. |
| TC-09-ARTIFACTS-017 | `test_5mb` | `test-large-artifact.sh:79` | C7, C4 | core | Same helper; has `MAX_SIZE_MB < 5` skip branch → `log_warn` + `log_pass "skipped by config"`. AMBER per audit §1.11 — by-design configurable skip. Prior inverted-check finding at L43 REMEDIATED (now positive-with-negation `if ! { status -ge 200 && status -lt 300 }`). |
| TC-09-ARTIFACTS-018 | `test_cluster_healthy_after_large_artifacts` | `test-large-artifact.sh:88` | C8 | core | Exact health check. |

---

## Suite-level invariants

- **Pre-conditions:** cluster A (non-destructive); ≥2 nodes for the replication file (`test-artifact-replication.sh` asserts via `assert_ge`).
- **Side effects:** writes artifacts of varying sizes into the cluster's artifact store. Coordinate names use suite-local prefixes to avoid collision with neighbouring suites.
- **Cleanup discipline:** no explicit EXIT trap. Artifacts persist beyond the test run — this is intentional (`artifact_store` is designed to retain pushed coordinates); collisions are avoided via unique suffixes per test execution.
- **Cryptographic backbone:** every functional outcome reduces to a SHA-256 equality. Outside `test_wait_for_replication` (decorative) and `test_identify_second_node` (acknowledged endpoint-discovery fallback), no test in this suite can pass under a real bug.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-09-ARTIFACTS-008 | Endpoint-discovery falls back to `CLUSTER_ENDPOINT` (gateway). Gateway round-robin probabilistically routes to a different node, so DHT replication is still exercised — but the test does not guarantee a distinct target. | Audit §1.11 AMBER, LOW. |
| TC-09-ARTIFACTS-010 | 10s sleep + unconditional pass. Decorative — the next test's SHA-256 equality would catch unreplicated state. | Audit §1.11 GREEN-STICKER, LOW. Consider renaming to `test_replication_window_elapsed` to align name with semantics. |
| TC-09-ARTIFACTS-017 | `MAX_SIZE_MB < 5` skip branch is by-design configurable. When MAX_SIZE_MB ≥ 5, this is a real test; otherwise it's vacuous. | Audit §1.11 AMBER, LOW. |

### Contract gaps

- **C7** — no canonical spec section for artifact-size boundary policy. The 64KB / 128KB / 1MB / 5MB sizes are empirically chosen to probe chunking/streaming code paths in the artifact store; should be documented in `management-api.md` (or a dedicated artifact-store spec) so the boundaries are intentional and not test-side folklore.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter authoring agent | Initial charter; TC-09-ARTIFACTS-001 through TC-09-ARTIFACTS-018 catalogued from audit §1.11. Prior inverted-check finding at `test-large-artifact.sh:43` recorded as REMEDIATED. |
