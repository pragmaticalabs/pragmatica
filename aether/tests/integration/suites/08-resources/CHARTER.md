# Suite 08-resources Charter

**Test-ID convention:** `TC-08-RESOURCES-NNN`.

**Scope:** Per-test contract mapping for the resource subsystems exposed to user slices: declarative HTTP client, in-memory event streams (pub/sub + notification hubs), scheduled tasks, and the SQL connector. Tests in this suite drive the cluster's Management API + CLI surfaces against a non-destructive cluster A.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state (N members, leader elected, ≥N-1 active cores) before any resource probe runs. | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | Management API serves `/api/nodes/status`, `/api/cluster/topology`, and unknown-path 404s with correct `Content-Type` and authentication semantics. | `aether/docs/reference/management-api.md §Status / §Topology` |
| C3 | Concurrent Management API requests do not lose responses (no thread-safety regression in request dispatch). | `aether/docs/reference/management-api.md` (operational expectation) |
| C4 | In-memory event streams auto-create on first publish and surface in `/api/streams` listing. | `aether/docs/specs/in-memory-streams-spec.md`; `aether/docs/specs/streaming-spec.md` |
| C5 | `stream_publish` returns success exactly when the event is committed to the stream's partition log. | `aether/docs/specs/in-memory-streams-spec.md` |
| C6 | `streams read <name> <partition>` returns events previously published to the same `(stream, partition)`. | `aether/docs/specs/in-memory-streams-spec.md`; `aether/docs/specs/streaming-read-forwarding-spec.md` |
| C7 | Multiple slice instances may consume from the same stream without publish-side errors (competing-consumer wiring is intact). | `aether/docs/specs/in-memory-streams-spec.md §Competing Consumers` |
| C8 | `/api/scheduled-tasks` enumerates declared `@Scheduled` tasks with the expected `(configSection, artifact, method, lastExecutionAt, paused)` fields. | `aether/docs/reference/management-api.md §Scheduled Tasks` |
| C9 | `/api/scheduled-tasks/inject` synchronously triggers the named task and advances `lastExecutionAt` (`currentExecutionMs > pre-inject lastExecutionAt`). | `[CONTRACT-GAP]` — test-only injection endpoint; no canonical spec section yet (analogous to `/api/alerts/inject`, `/api/traces/inject`). |
| C10 | `scheduled-tasks pause` returns `success=true, action=paused` and the next list call observes `paused=true` for the same task. | `aether/docs/reference/management-api.md §Scheduled Tasks` |
| C11 | `scheduled-tasks resume` returns `success=true` and the next list call observes `paused=false` for the same task. | `aether/docs/reference/management-api.md §Scheduled Tasks` |
| C12 | SQL-backed slice deploys, reaches `slices_active >= 1`, and serves PUT/GET requests against its declared route. | `aether/docs/specs/unified-deploy-spec.md §3`; `aether/docs/reference/slice-api.md §@Sql` |
| C13 | A PUT followed by a GET of the same key returns the exact value previously written (SQL connector round-trip). | `aether/docs/reference/slice-api.md §@Sql` |
| C14 | Connection-pool survives a rapid burst: a strict majority of `POOL_BURST` concurrent requests succeed without 5xx. | `[CONTRACT-GAP]` — pool sizing spec; threshold is empirical, not specced. |
| C15 | Notification-hub streams (declarative `@Notify`) accept N publishes and report stream info before and after. | `aether/docs/specs/notification-resource-spec.md`; `aether/docs/specs/streaming-spec.md` |
| C16 | Cluster remains healthy after every resource workload (no node leaves, no leader churn induced). | `aether/docs/specs/test-readiness-contract.md §1.1` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-08-RESOURCES-001 | `test_cluster_ready` | `test-http-client.sh:9` | C1 | smoke | `wait_for_cluster_ready 60`; lib enforces readiness contract. |
| TC-08-RESOURCES-002 | `test_mgmt_health_endpoint` | `test-http-client.sh:15` | C2 | core | Delegates to `assert_cluster_healthy`. |
| TC-08-RESOURCES-003 | `test_mgmt_status_json` | `test-http-client.sh:19` | C2 | core | Asserts `cluster.nodes.0.id` non-empty via `aether_field` dot-path. |
| TC-08-RESOURCES-004 | `test_mgmt_nodes_json` | `test-http-client.sh:29` | C2 | regression-net | `coreCount > 0` via CLI; AMBER per audit §1.10 (stderr swallow falls back to `0`, so failure surfaces as `0 > 0` fail). |
| TC-08-RESOURCES-005 | `test_mgmt_content_type` | `test-http-client.sh:38` | C2 | core | `assert_contains` on `application/json` in response headers. |
| TC-08-RESOURCES-006 | `test_mgmt_invalid_path` | `test-http-client.sh:44` | C2 | core | Exact 404 with API key (REMEDIATED from prior `< 500` per audit §1.10). |
| TC-08-RESOURCES-007 | `test_mgmt_concurrent_requests` | `test-http-client.sh:55` | C3 | core | Strict `success == 20` over parallel `api_get` calls. |
| TC-08-RESOURCES-008 | `test_cluster_ready` | `test-pub-sub.sh:12` | C1 | smoke | — |
| TC-08-RESOURCES-009 | `test_stream_exists_or_created` | `test-pub-sub.sh:17` | C4 | regression-net | Field-anchored regex on `"name":"<stream>"` (prior substring REMEDIATED per audit §1.10); else-branch is permissive `log_pass "endpoint responds"` (audit §1.10 GREEN-STICKER, LOW — auto-create path proves out via TC-08-RESOURCES-010). |
| TC-08-RESOURCES-010 | `test_publish_events` | `test-pub-sub.sh:32` | C5 | core | `assert_eq success == EVENT_COUNT` (default 25). |
| TC-08-RESOURCES-011 | `test_stream_info_after_publish` | `test-pub-sub.sh:46` | C4 | regression-net | `assert_ne info ""`. AMBER per audit §1.10 — only endpoint liveness, not state observation. |
| TC-08-RESOURCES-012 | `test_subscriber_receives_events` | `test-pub-sub.sh:52` | C5, C6 | core | CLOSED in c37ecae9 — RC1-blocker #15. Publishes 10 events on partition 0, reads them back via `aether_json streams read`, asserts `event_count >= 10` from `"offset"` occurrence count. Prior version was double warn-then-pass (audit §1.10). |
| TC-08-RESOURCES-013 | `test_competing_consumers_multi_instance` | `test-pub-sub.sh:87` | C7 | regression-net | Multi-instance branch strict `assert_eq success == 10`; single-instance branch is unconditional pass (audit §1.10 GREEN-STICKER, MEDIUM — branch entered only when topology lacks 2 instances). |
| TC-08-RESOURCES-014 | `test_cluster_healthy_after_pubsub` | `test-pub-sub.sh:110` | C16 | core | `assert_cluster_healthy`. |
| TC-08-RESOURCES-015 | `test_cluster_ready` | `test-scheduled-tasks.sh:9` | C1 | smoke | — |
| TC-08-RESOURCES-016 | `test_scheduled_tasks_endpoint` | `test-scheduled-tasks.sh:14` | C8 | regression-net | `assert_ne tasks ""` on `/api/scheduled-tasks`. AMBER per audit §1.10 — empty list `[]` would pass. |
| TC-08-RESOURCES-017 | `test_task_last_execution_advances` | `test-scheduled-tasks.sh:20` | C8, C9 | core | CLOSED in c37ecae9 — RC1-blocker #16. Captures pre-inject `lastExecutionAt`, calls `scheduled-tasks inject`, asserts `currentExecutionMs > pre_ts`. Prior version had 3 of 4 branches end in `log_pass` (audit §1.10). |
| TC-08-RESOURCES-018 | `test_pause_task` | `test-scheduled-tasks.sh:68` | C10 | core | CLOSED in c37ecae9 — HIGH severity in audit §1.10. Calls `scheduled-tasks pause` via failover CLI, asserts `success=true` in response AND `tasks.0.paused=="true"` on readback. |
| TC-08-RESOURCES-019 | `test_resume_task` | `test-scheduled-tasks.sh:114` | C11 | core | CLOSED in c37ecae9 — HIGH severity in audit §1.10. Symmetric to TC-08-RESOURCES-018: asserts `success=true` and `tasks.0.paused=="false"` on readback. |
| TC-08-RESOURCES-020 | `test_cluster_healthy_after_task_ops` | `test-scheduled-tasks.sh:157` | C16 | core | — |
| TC-08-RESOURCES-021 | `test_cluster_ready` | `test-sql-connector.sh:13` | C1 | smoke | — |
| TC-08-RESOURCES-022 | `test_deploy_sql_app` | `test-sql-connector.sh:18` | C12 | regression-net | Strict `wait_for_slices_active 1 120`; push/deploy failure demoted to `log_warn` and `retarget_app_endpoint_to_active_slice` is `|| true` (AMBER per audit §1.10, LOW — slices-active gate is the real assertion). |
| TC-08-RESOURCES-023 | `test_put_kv_pair` | `test-sql-connector.sh:38` | C12 | core | Range check `200 <= status < 400` (AMBER per audit §1.10 — accepts 3xx, but no redirects exist for these endpoints). |
| TC-08-RESOURCES-024 | `test_get_kv_pair` | `test-sql-connector.sh:58` | C13 | core | Exact-string match on `value == "resolve-test-value"` via `json_value`. |
| TC-08-RESOURCES-025 | `test_connection_pooling_rapid_requests` | `test-sql-connector.sh:82` | C14 | core | `assert_gt success > 0` AND `assert_gt success > POOL_BURST/2`. AMBER per audit §1.10, MEDIUM — 50% failure rate would still pass. |
| TC-08-RESOURCES-026 | `test_cluster_healthy_after_sql_load` | `test-sql-connector.sh:104` | C16 | core | — |
| TC-08-RESOURCES-027 | `test_cluster_ready` | `test-streaming-resources.sh:13` | C1 | smoke | — |
| TC-08-RESOURCES-028 | `test_deploy_notification_hub` | `test-streaming-resources.sh:18` | C15 | regression-net | Body is unconditional `log_pass` (audit §1.10 GREEN-STICKER, MEDIUM). By-design (`@Notify` streams auto-create) but should be deleted or replaced with a meaningful readiness check. |
| TC-08-RESOURCES-029 | `test_stream_publisher_provisioned` | `test-streaming-resources.sh:23` | C4 | regression-net | Field-anchored regex on stream name (prior substring REMEDIATED); else-branch is warn-then-`log_pass "Stream list endpoint responds"` (audit §1.10 GREEN-STICKER, MEDIUM). |
| TC-08-RESOURCES-030 | `test_publish_notifications` | `test-streaming-resources.sh:37` | C5, C15 | core | `assert_eq success == EVENT_COUNT`. Prior `|| true` swallow REMEDIATED per audit §1.10. |
| TC-08-RESOURCES-031 | `test_subscriber_receives_notifications` | `test-streaming-resources.sh:51` | C15 | regression-net | `assert_ne info ""`. AMBER per audit §1.10 — endpoint liveness only. |
| TC-08-RESOURCES-032 | `test_analytics_counts_increment` | `test-streaming-resources.sh:58` | C15 | regression-net | Hard fail if `failures > 1`; warn if `0 < failures <= 1`; otherwise pass on info-before-and-after non-empty. AMBER per audit §1.10, MEDIUM — claim/check mismatch, counts are never compared. |
| TC-08-RESOURCES-033 | `test_cluster_healthy_after_streaming` | `test-streaming-resources.sh:93` | C16 | core | — |

---

## Suite-level invariants

- **Pre-conditions:** cluster A (non-destructive, parallel-safe). NODE_COUNT honored; `wait_for_cluster_ready 60` gates all subsequent tests.
- **Side effects:** publishes events into stream `${PUB_SUB_STREAM:-integration-pubsub}` and the notification-hub stream (`@Notify`-declared); deploys the SQL slice (`url-shortener` / `test-persistence`); pauses + resumes the first scheduled task in the live list.
- **Cleanup discipline:** no explicit EXIT trap — `test_cluster_healthy_after_*` at the end of each file serves as a post-condition check. Resume MUST follow pause to leave the suite idempotent for subsequent re-runs.
- **Stream namespace:** `${PUB_SUB_STREAM}` is environment-overridable to avoid collision when running suites in parallel.
- **Single-writer hygiene:** all task pause/resume calls go via `aether_failover scheduled-tasks ...` so the CLI's leader-failover handling owns the write path.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-08-RESOURCES-009 | Else-branch is warn-then-pass (`Stream list endpoint responds`); cannot fail if list endpoint returns 200 with an unrelated body. | Audit §1.10 GREEN-STICKER, LOW — covered functionally by TC-08-RESOURCES-010 (publish path proves auto-create). |
| TC-08-RESOURCES-011 | Only checks endpoint liveness, not that the previous publish landed (no offset comparison). | Audit §1.10 AMBER, MEDIUM. |
| TC-08-RESOURCES-013 | Single-instance branch is an unconditional `log_pass "Single-instance pub/sub works"`. | Audit §1.10 GREEN-STICKER, MEDIUM — only entered when topology lacks 2 instances; requires topology pinning to make deterministic. |
| TC-08-RESOURCES-016 | `assert_ne ""` accepts `[]` as success (would mask "tasks endpoint responds but lists nothing"). | Audit §1.10 AMBER, LOW. |
| TC-08-RESOURCES-022 | push/deploy stderr swallow; retarget is `|| true`. Slices-active gate is the real assertion. | Audit §1.10 AMBER, LOW. |
| TC-08-RESOURCES-025 | Pool burst accepts 50%+1 successes as pass; production exhaustion that manifests as 50/50 would not trip the threshold. | Audit §1.10 AMBER, MEDIUM — pool sizing contract (C14) is not specced. |
| TC-08-RESOURCES-028 | `test_deploy_notification_hub` body is unconditional `log_pass`. | Audit §1.10 GREEN-STICKER, MEDIUM — recommend deletion or replacement with a deployment-state assertion. |
| TC-08-RESOURCES-029 | Else-branch warn-then-pass on stream-not-listed. | Audit §1.10 GREEN-STICKER, MEDIUM. |
| TC-08-RESOURCES-031 | Endpoint liveness only; no consumer attached. | Audit §1.10 AMBER, LOW — parallel rewrite path to TC-08-RESOURCES-012 would mirror the inject-endpoint approach for `@Notify`. |
| TC-08-RESOURCES-032 | Counts are never compared — only "info available before AND after" is checked. The test name (`counts_increment`) is misleading. | Audit §1.10 AMBER, MEDIUM. |

### Contract gaps

- **C9** — no spec home for `/api/scheduled-tasks/inject`. The endpoint exists and is documented inline in code; needs a §Scheduled Tasks Test Injection paragraph in `management-api.md` mirroring the `/api/alerts/inject` and `/api/traces/inject` precedent.
- **C14** — connection-pool burst threshold is empirical (`POOL_BURST/2`); not a specced contract. Either (a) write a pool-sizing spec stating "≥X% of POOL_BURST must succeed under burst load" and codify the threshold there, or (b) flip to `assert_eq success == POOL_BURST` and treat any 5xx as a real regression.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter authoring agent | Initial charter; TC-08-RESOURCES-001 through TC-08-RESOURCES-033 catalogued from audit §1.10. RC1-blockers #15, #16 + HIGH pause/resume marked CLOSED in c37ecae9. |
