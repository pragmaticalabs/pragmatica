# Suite 04-streaming Charter

**Test-ID convention:** `TC-04-STREAMING-NNN` — zero-padded 3-digit index, stable across reorganisations, allocated in `run_test` order.

**Charter purpose:** Anchor every test in this suite to a streaming-spec contract. The suite verifies the publish → governor-visible → partition-readable → list-visible chain plus replication to non-governor nodes and behaviour under sustained load.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | A stream created via management API is registered in cluster metadata under its declared name. | `aether/docs/specs/streaming-spec.md §3` (Stream lifecycle); `event-stream-namespaces-spec.md` |
| C2 | A successful publish on stream `S` is reflected in subsequent partition-read responses (publish → read invariant). | `streaming-spec.md §4` (Publish/read) |
| C3 | Stream metadata and listing are reachable on the governor (lead-routing entry point) and replicated to non-governor nodes. | `streaming-spec.md §5` (Replication); `streaming-read-forwarding-spec.md` |
| C4 | A sustained publish workload at the documented RPS keeps the strict-2xx success rate within budget (≥ 95%). | `streaming-spec.md §6` (Throughput / error-rate budget) |
| C5 | The cluster remains healthy and at full membership after a stream-heavy workload. | `test-readiness-contract.md §1.1`; `streaming-spec.md §6` |
| C6 | The CLI `aether streams …` family routes correctly through the management API and returns structured output. | `aether/docs/reference/cli.md` (streams subcommand) |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-04-STREAMING-001 | `test_cluster_ready` | `test-stream-replication.sh:12` | C5 | smoke | Task-group readiness warn-demoted (RC2). Audit §1.6 GREEN-STICKER. |
| TC-04-STREAMING-002 | `test_create_stream` | `test-stream-replication.sh:18` | C1 | core | `assert_contains $STREAM_NAME` substring check (narrow). Audit §1.6 NARROW. |
| TC-04-STREAMING-003 | `test_publish_events_for_replication` | `test-stream-replication.sh:25` | C2 | core | Strict `assert_eq success 10`; per-publish rc tracked. |
| TC-04-STREAMING-004 | `test_stream_visible_on_governor` | `test-stream-replication.sh:36` | C3 | regression-net | Non-empty-as-success; admits error JSON. Audit §1.6 TAUTOLOGY (RC2). |
| TC-04-STREAMING-005 | `test_read_events_from_partition` | `test-stream-replication.sh:42` | C2, C6 | core | RC1-blocker #1 CLOSED in 3a61fef27 — now uses `aether streams read` and asserts ≥ published count. |
| TC-04-STREAMING-006 | `test_read_from_non_governor_node` | `test-stream-replication.sh:77` | C3 | core | Strict-asserts non-empty body + `$STREAM_NAME` on a non-leader endpoint. Metadata only (no data read). Audit §1.6 RC2 coverage gap. |
| TC-04-STREAMING-007 | `test_stream_in_list_after_replication` | `test-stream-replication.sh:130` | C1, C6 | regression-net | Substring on `stream_list`; no replication-factor assertion. |
| TC-04-STREAMING-008 | `test_cluster_ready` | `test-stream-consumer.sh:11` | C5 | smoke | Same warn-demoted task readiness as 001. |
| TC-04-STREAMING-009 | `test_publish_and_verify_count` | `test-stream-consumer.sh:17` | C2 | core | RC1-blocker #2 CLOSED in 3a61fef27 — publish stderr no longer silenced; per-publish failures counted; strict `msg_count >= published`. |
| TC-04-STREAMING-010 | `test_stream_metadata` | `test-stream-consumer.sh:75` | C1 | regression-net | Extracts `name` field; passes for any stream in the array. Audit §1.6 TAUTOLOGY (RC2). |
| TC-04-STREAMING-011 | `test_multiple_streams_isolation` | `test-stream-consumer.sh:88` | C1 | regression-net | Name promises isolation; check asserts only that first stream still listed. Audit §1.6 NARROW / mis-named (RC2). |
| TC-04-STREAMING-012 | `test_cluster_ready` | `test-stream-publish.sh:11` | C5 | smoke | Same warn-demoted task readiness. |
| TC-04-STREAMING-013 | `test_publish_single_event` | `test-stream-publish.sh:17` | C2 | regression-net | Non-empty body accepted. Audit §1.6 TAUTOLOGY (RC2). |
| TC-04-STREAMING-014 | `test_publish_batch` | `test-stream-publish.sh:24` | C2 | core | Strict `assert_eq success 50`; rc-per-publish. |
| TC-04-STREAMING-015 | `test_stream_info` | `test-stream-publish.sh:38` | C1 | regression-net | Non-empty body accepted. Audit §1.6 TAUTOLOGY (RC2). |
| TC-04-STREAMING-016 | `test_stream_appears_in_list` | `test-stream-publish.sh:44` | C1, C6 | core | Substring on `stream_list`. |
| TC-04-STREAMING-017 | `test_cluster_ready` | `test-stream-under-load.sh:22` | C5 | smoke | Warn-demoted task readiness. |
| TC-04-STREAMING-018 | `test_sustained_stream_publish` | `test-stream-under-load.sh:28` | C4 | core | Strict 2xx-only counting (post-fix from prior `< 400`); `assert_error_rate_below`. |
| TC-04-STREAMING-019 | `test_stream_info_after_load` | `test-stream-under-load.sh:65` | C1 | regression-net | Non-empty body. Audit §1.6 TAUTOLOGY (RC2). |
| TC-04-STREAMING-020 | `test_cluster_stable` | `test-stream-under-load.sh:71` | C5 | core | `cluster_member_count == 5`; `assert_cluster_healthy`. |
| TC-04-STREAMING-021 | `test_concurrent_publish_and_query` | `test-stream-under-load.sh:78` | C2 | regression-net | Loop is sequential, not concurrent. Race-condition coverage NOT exercised. Audit §1.6 NARROW (RC2). |

**Total tests:** 21.

---

## Suite-level invariants

- **Pre-conditions:** Cluster A non-destructive, 5 nodes, NODE_COUNT=5, blueprint `test-persistence` pre-pushed by harness. Each test file creates its own `$STREAM_NAME` (timestamped).
- **Side effects:** Creates streams; never destroys them (next test run reuses or creates new names). Drives sustained publish load (`test_sustained_stream_publish`). Does not scale the cluster.
- **Cleanup discipline:** No explicit EXIT trap; relies on `restore_cluster_baseline` between suites in the runner. Streams remain in KV-store as residue — `test_multiple_streams_isolation` exercises but does not delete the second stream.
- **Readiness gate:** `test_cluster_ready` across all four files calls `wait_for_all_tasks_active` (warn-demoted). Post control-plane-removal this helper delegates to `wait_for_cluster_ready` (member count + leader elected + active core floor — see test-readiness-contract.md §1.1), so the prior "half-ready cluster" race is gone: the gate now verifies real cluster readiness, not task-group assignment.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-04-STREAMING-001 / 008 / 012 / 017 | `test_cluster_ready` warn-demotes task readiness; suite proceeds with half-ready cluster | Audit §1.6 / §2.1 warn-then-pass census (RC2) |
| TC-04-STREAMING-002 | Substring match on stream name; no status-code check on POST | Audit §1.6 NARROW (RC2) |
| TC-04-STREAMING-004 / 015 / 019 | Non-empty-as-success — any error body passes | Audit §1.6 / §2.1 tautology census (RC2) |
| TC-04-STREAMING-006 | Reads metadata only from non-governor; does not verify replicated event data on non-governor | Audit §1.6 RC2 coverage gap |
| TC-04-STREAMING-010 | Extracted `name` field is not scoped to `$STREAM_NAME` — any stream in the array passes | Audit §1.6 TAUTOLOGY (RC2) |
| TC-04-STREAMING-011 | Mis-named; asserts only that first stream still listed (not "isolation") | Audit §1.6 NARROW (RC2) |
| TC-04-STREAMING-013 / 015 / 019 | Non-empty body accepted | Audit §1.6 / §2.1 tautology census (RC2) |
| TC-04-STREAMING-021 | Labelled "concurrent" but loop is sequential — publish→info race conditions not exercised | Audit §1.6 NARROW (RC2) |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter-author | Initial charter from audit 2026-05-21; reflects RC1-blockers #1 + #2 closed in 3a61fef27 |
