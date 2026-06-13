# Suite 07-cluster-mgmt Charter

**Test-ID convention:** `TC-07-CLUSTER-MGMT-NNN` — zero-padded 3-digit index, stable across reorganisations, allocated in `run_test` order.

**Charter purpose:** Anchor every test to a cluster-management-spec contract: bootstrap from a config file produces a healthy N-node cluster; destroy is idempotent and leaves no containers/data residue; runtime config can be applied, exported, and round-tripped without drift.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Idempotent bootstrap: if the cluster is already up, bootstrap is a no-op; otherwise, `aether cluster bootstrap` produces a healthy cluster of exactly NODE_COUNT nodes with a leader within the readiness budget. | `aether/docs/specs/cluster-bootstrap-spec.md §3` (Bootstrap flow); `aether/docs/specs/cluster-management-spec.md` |
| C2 | The bootstrap config file is honoured (file-existence pre-flight; cluster forms with the declared topology). | `cluster-bootstrap-spec.md §2` (Config format) |
| C3 | Leader election succeeds within the readiness budget after bootstrap; management API is reachable. | `test-readiness-contract.md §1.1`; `cluster-management-spec.md` (Management API) |
| C4 | Destroy is guarded behind `ALLOW_DESTROY=true`; with guard cleared, `aether cluster destroy --yes` terminates all containers and leaves no `/api/cluster/topology` reachable. | `cluster-management-spec.md §6` (Destroy) |
| C5 | After destroy: zero aether containers running, data directory cleaned (or warn-demoted clean-skip when residue is operator-acceptable). | `cluster-management-spec.md §6.2` (Cleanup) |
| C6 | Runtime config (`config_export`) is retrievable; `config_apply` produces a non-empty acknowledgement; applied config converges and is visible from **every** node's management endpoint (not just the entry point). | `cluster-management-spec.md §4` (Config plane) |
| C7 | Config export → re-apply is byte-equivalent (canonical-form): exporting the current config, re-applying it, then re-exporting must produce identical content. | `cluster-management-spec.md §4.3` (Round-trip invariant) |
| C8 | Cluster remains healthy and unchanged in size across config apply/export operations. | `test-readiness-contract.md §1.1` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-07-CLUSTER-MGMT-001 | `test_skip_if_running` | `test-bootstrap.sh:11` | C1 | core | Idempotent skip path; SOUND. |
| TC-07-CLUSTER-MGMT-002 | `test_config_exists` | `test-bootstrap.sh:22` | C2 | core | File-exists check; SOUND. |
| TC-07-CLUSTER-MGMT-003 | `test_bootstrap_cluster` | `test-bootstrap.sh:31` | C1 | core | `aether cluster bootstrap` under `set -euo pipefail`; warn-then-pass on no-CLI branch. Audit §1.9 GREEN-STICKER (RC2). |
| TC-07-CLUSTER-MGMT-004 | `test_cluster_forms` | `test-bootstrap.sh:42` | C1, C3 | core | `wait_for_cluster_ready 90`; SOUND. |
| TC-07-CLUSTER-MGMT-005 | `test_expected_node_count` | `test-bootstrap.sh:47` | C1 | core | Strict `assert_eq count 5`. |
| TC-07-CLUSTER-MGMT-006 | `test_leader_elected` | `test-bootstrap.sh:54` | C3 | core | `assert_ne leader ""` — passes for any non-empty string. Audit §1.9 NARROW (RC2). |
| TC-07-CLUSTER-MGMT-007 | `test_health_probes` | `test-bootstrap.sh:61` | C3, C8 | core | Strict 200 + `assert_cluster_healthy`. |
| TC-07-CLUSTER-MGMT-008 | `test_management_api_accessible` | `test-bootstrap.sh:66` | C3 | regression-net | `/api/nodes/status` non-empty. Audit §1.9 TAUTOLOGY (RC2). |
| TC-07-CLUSTER-MGMT-009 | `test_destroy_guard` | `test-destroy.sh:9` | C4 | core | Skip-by-default unless `ALLOW_DESTROY=true`; SOUND. |
| TC-07-CLUSTER-MGMT-010 | `test_cluster_exists` | `test-destroy.sh:18` | C4 | core | `assert_gt count 0`; SOUND. |
| TC-07-CLUSTER-MGMT-011 | `test_destroy_cluster` | `test-destroy.sh:25` | C4 | core | Captures rc/stderr explicitly; strict log_fail on docker rm failure. Prior `xargs -r ... \| true` masking REMEDIATED. |
| TC-07-CLUSTER-MGMT-012 | `test_cluster_gone` | `test-destroy.sh:53` | C4 | core | Asserts `http_status == 000` (unreachable); SOUND. |
| TC-07-CLUSTER-MGMT-013 | `test_no_containers_running` | `test-destroy.sh:65` | C5 | core | `list_aether_containers` empty; SOUND. |
| TC-07-CLUSTER-MGMT-014 | `test_data_cleaned` | `test-destroy.sh:76` | C5 | regression-net | Warn-then-pass on data-leftover branch (operator-acceptable per inline comment). Audit §1.9 GREEN-STICKER (RC2). |
| TC-07-CLUSTER-MGMT-015 | `test_cluster_ready` | `test-apply.sh:9` | C8 | smoke | `wait_for_cluster_ready`; SOUND. |
| TC-07-CLUSTER-MGMT-016 | `test_get_current_config` | `test-apply.sh:14` | C6 | regression-net | `config_export` non-empty. Audit §1.9 TAUTOLOGY (RC2). |
| TC-07-CLUSTER-MGMT-017 | `test_apply_config_override` | `test-apply.sh:20` | C6 | core | `config_apply` returns non-empty; explicit log_fail on empty. No echo-back / read-back verification. Audit §1.9 NARROW (RC2). |
| TC-07-CLUSTER-MGMT-018 | `test_config_converges` | `test-apply.sh:34` | C6, C8 | regression-net | `sleep 5; assert_cluster_healthy`. "Converges" claim narrowly verified — only health, not key/value visibility. Audit §1.9 NARROW (RC2). |
| TC-07-CLUSTER-MGMT-019 | `test_config_visible_on_all_nodes` | `test-apply.sh:40` | C6 | core | RC1-blocker #13 CLOSED in 04ff1fb79 — now iterates per-node management ports and asserts each returns the same config (replaces previous double-call to the entry-point endpoint). |
| TC-07-CLUSTER-MGMT-020 | `test_overrides_endpoint` | `test-apply.sh:80` | C6 | regression-net | Branches both `log_pass`d unconditionally. Audit §1.9 GREEN-STICKER (RC2). |
| TC-07-CLUSTER-MGMT-021 | `test_cluster_unchanged` | `test-apply.sh:91` | C8 | core | Strict `assert_eq count 5`. |
| TC-07-CLUSTER-MGMT-022 | `test_cluster_ready` | `test-export.sh:11` | C8 | smoke | SOUND. |
| TC-07-CLUSTER-MGMT-023 | `test_export_config` | `test-export.sh:16` | C6 | regression-net | Non-empty export. Audit §1.9 TAUTOLOGY (RC2). |
| TC-07-CLUSTER-MGMT-024 | `test_export_valid_json` | `test-export.sh:25` | C6 | regression-net | Grep `^[{[]` — leading-char regex only. Audit §1.9 NARROW (RC2). |
| TC-07-CLUSTER-MGMT-025 | `test_reapply_exported_config` | `test-export.sh:35` | C7 | core | Applies a hard-coded key/value rather than the exported document; explicit log_fail on empty. Audit §1.9 NARROW / mis-named (RC2). |
| TC-07-CLUSTER-MGMT-026 | `test_config_identical_after_reapply` | `test-export.sh:54` | C7 | core | RC1-blocker #14 CLOSED in 04ff1fb79 — now strict-asserts canonical-form equality between original and re-exported config (replaces previous "log diff, never assert"). |
| TC-07-CLUSTER-MGMT-027 | `test_cluster_healthy_after_roundtrip` | `test-export.sh:97` | C8 | core | `assert_cluster_healthy`. |

**Total tests:** 27.

---

## Suite-level invariants

- **Pre-conditions:** Cluster A non-destructive, 5 nodes, NODE_COUNT=5; blueprint `test-full` pre-pushed by harness; `aether` CLI on PATH (warn-then-pass demotion for the no-CLI branch is a soft fallback).
- **Side effects:** `test-destroy.sh` is gated by `ALLOW_DESTROY=true` and **must not** run in CI without explicit opt-in (otherwise it tears down Cluster A). `test-apply.sh` and `test-export.sh` write runtime config overrides; the overrides persist across tests within the suite.
- **Cleanup discipline:** `test-export.sh` has its own `cleanup()` trap (L105). Bootstrap and apply tests rely on `restore_cluster_baseline` between suites.
- **Per-node addressing:** TC-07-CLUSTER-MGMT-019 is the only test in this suite that walks per-node management endpoints; all other "all nodes" claims (e.g., TC-07-CLUSTER-MGMT-018 `test_config_converges`) only re-hit `$CLUSTER_ENDPOINT`.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-07-CLUSTER-MGMT-003 | Warn-then-pass on no-CLI branch; if `aether` is absent the test silently passes | Audit §1.9 GREEN-STICKER (RC2) |
| TC-07-CLUSTER-MGMT-006 | `assert_ne leader ""` — non-empty string passes (could be an error message) | Audit §1.9 NARROW (RC2) |
| TC-07-CLUSTER-MGMT-008 | `/api/nodes/status` non-empty; no content assertion | Audit §1.9 TAUTOLOGY (RC2) |
| TC-07-CLUSTER-MGMT-014 | Data residue is `log_warn → log_pass` (operator-acceptable) | Audit §1.9 GREEN-STICKER (RC2) |
| TC-07-CLUSTER-MGMT-016 / 023 | `config_export` non-empty; no JSON-shape or field assertions | Audit §1.9 TAUTOLOGY (RC2) |
| TC-07-CLUSTER-MGMT-017 | No echo-back verification — applied override is not read back | Audit §1.9 NARROW (RC2) |
| TC-07-CLUSTER-MGMT-018 | "Converges" claim narrowly verified — only health, not value visibility | Audit §1.9 NARROW (RC2) |
| TC-07-CLUSTER-MGMT-020 | Both branches `log_pass`d unconditionally; test cannot fail | Audit §1.9 GREEN-STICKER (RC2) |
| TC-07-CLUSTER-MGMT-024 | JSON validity asserted only via leading-char regex (`^[{[]`) | Audit §1.9 NARROW (RC2) |
| TC-07-CLUSTER-MGMT-025 | Applies a hard-coded key/value rather than the exported document; name implies full round-trip | Audit §1.9 NARROW / mis-named (RC2) |

No RC1-open findings remain in this suite.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter-author | Initial charter from audit 2026-05-21; reflects RC1-blockers #13 + #14 closed in commit 04ff1fb79 |
