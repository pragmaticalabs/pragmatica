# Suite Audit: 08-resources, 09-artifacts, 10-database, 11-observability

Production-readiness audit. Each test catalogued with claim, actual check, severity. Self-check pass:fail ratios at end.

---

## Suite 08-resources

### test-http-client.sh

#### test_cluster_ready (L9)
- **Claims:** Cluster ready
- **Actually checks:** `wait_for_cluster_ready 60`; `wait_for_all_tasks_active 60` demoted to log_warn on failure.
- **Assertions:** L11 task-groups failure is warn-only.
- **Correctness:** AMBER — task-not-active is silently demoted, but readiness gate itself is strict.
- **Tooling:** lib helpers.
- **Severity:** MEDIUM — pre-condition leniency; subsequent tests will detect a truly-broken cluster.

#### test_mgmt_health_endpoint (L15)
- **Claims:** Management health returns healthy.
- **Actually checks:** `assert_cluster_healthy` (delegated to lib).
- **Correctness:** SOUND (assuming lib helper enforces healthy contract).
- **Tooling:** lib.

#### test_mgmt_status_json (L19)
- **Claims:** /api/nodes/status returns JSON; cluster.nodes.0.id non-empty.
- **Actually checks:** L22 non-empty body; L26 `cluster.nodes.0.id` non-empty.
- **Correctness:** SOUND.
- **Tooling:** aether CLI (`aether_field` with dot-path).

#### test_mgmt_nodes_json (L29)
- **Claims:** cluster topology returns coreCount > 0.
- **Actually checks:** `aether cluster topology --format value --field coreCount > 0`. Note `2>/dev/null || echo 0` swallows error.
- **Correctness:** AMBER — stderr swallow on CLI failure; falls back to 0 which then trips assert_gt. So failure path still surfaces, just opaque.
- **Tooling:** aether CLI.
- **Severity:** LOW.

#### test_mgmt_content_type (L38)
- **Claims:** Response carries `application/json`.
- **Actually checks:** assert_contains on response headers.
- **Correctness:** SOUND.
- **Tooling:** curl-direct.

#### test_mgmt_invalid_path (L44)
- **Claims:** Authenticated request to unknown path returns 404.
- **Actually checks:** `assert_http_status ... "404"` — EXACT status match with API key.
- **Correctness:** SOUND — this is the prior-audit GREEN-STICKER FIXED. Earlier `< 500` check is gone; now exact 404 assertion with proper auth header to bypass 401-before-routing.
- **Tooling:** lib `assert_http_status`.
- **Severity:** REMEDIATED.

#### test_mgmt_concurrent_requests (L55)
- **Claims:** All 20 concurrent requests succeed.
- **Actually checks:** `assert_eq "$success" "20"`. Note `api_get ... 2>/dev/null` swallows stderr.
- **Correctness:** SOUND — strict equality on 20 successes. stderr swallow only masks diagnostic detail, not pass/fail outcome.
- **Tooling:** lib `api_get`.

**Summary 08-http-client:** 7 functions, all SOUND or AMBER. **Prior `< 500` finding REMEDIATED.**

---

### test-pub-sub.sh

#### test_cluster_ready (L12) — SOUND.

#### test_stream_exists_or_created (L17)
- **Claims:** Stream exists or auto-creates.
- **Actually checks:** L24 exact JSON `"name":"<stream>"` regex; on miss, demotes to `log_pass "Stream list endpoint responds"`.
- **Correctness:** GREEN-STICKER — the prior substring grep is FIXED to a proper field-anchored regex. BUT the else branch still demotes: any malformed/empty response that doesn't contain the stream is reported as success.
- **Tooling:** lib `stream_list`.
- **Severity:** LOW — this test is genuinely permissive ("exists or auto-creates"); subsequent publish tests would catch breakage.

#### test_publish_events (L32)
- **Claims:** All N events published.
- **Actually checks:** `assert_eq "$success" "$EVENT_COUNT"` (default 25). Strict.
- **Correctness:** SOUND. **But note** L36 `2>/dev/null` swallows stream_publish stderr — opaque failure mode (same pattern flagged elsewhere by "silent stderr is a trap").
- **Tooling:** lib `stream_publish`.
- **Severity:** LOW (outcome strict, only diagnostics lost).

#### test_stream_info_after_publish (L46)
- **Claims:** Stream info available after publishing.
- **Actually checks:** `assert_ne "$info" ""`.
- **Correctness:** AMBER — only checks endpoint returned anything, not that the publish actually landed. Any non-empty JSON passes.
- **Tooling:** lib.
- **Severity:** MEDIUM — claim/check mismatch ("after publish" implies state observation, but only endpoint liveness is verified).

#### test_subscriber_receives_events (L52)
- **Claims:** Subscriber receives events.
- **Actually checks:** L56-61 — non-empty stream_info → log_pass "Subscriber endpoint responds with stream data"; empty → log_warn then `log_pass "Stream info endpoint responds"`.
- **Correctness:** GREEN-STICKER — DOUBLE warn-then-pass demotion. Test name claims "subscriber receives events" but no consumer is ever attached and no event count is verified. Cannot fail unless API returns 5xx (and even then, stream_info likely just returns "").
- **Tooling:** lib stream_info.
- **Severity:** RC1-BLOCK — tautological "endpoint responds" passed off as functional subscriber test. This is the institutionalized warn-then-pass the audit was looking for.

#### test_competing_consumers_multi_instance (L64)
- **Claims:** Competing consumers work.
- **Actually checks:** If ≥2 instances → publish 10 events, assert_eq success=10. If <2 instances → log_warn + `log_pass "Single-instance pub/sub works"`.
- **Correctness:** GREEN-STICKER (single-instance branch). Multi-instance branch is SOUND — strict eq on publish success. But "single-instance pub/sub works" is asserted with zero evidence in that branch.
- **Tooling:** lib.
- **Severity:** MEDIUM — single-instance branch is unconditional pass.

#### test_cluster_healthy_after_pubsub (L87) — SOUND.

**Summary 08-pub-sub:** 7 functions, 2 GREEN-STICKER (subscriber_receives_events, competing_consumers single-instance branch), 1 AMBER (stream_info_after_publish).

---

### test-scheduled-tasks.sh

#### test_cluster_ready (L9) — SOUND.

#### test_scheduled_tasks_endpoint (L14)
- **Claims:** Returns data.
- **Actually checks:** `assert_ne "$tasks" ""`.
- **Correctness:** AMBER — any non-empty body (e.g., `[]`) passes.
- **Severity:** LOW.

#### test_task_last_execution_advances (L20)
- **Claims:** Last execution time advances after waiting 30s.
- **Actually checks:** Three branches:
  - No tasks → log_warn + `log_pass "Endpoint responds"` (L23-27).
  - Both ts=0 → log_warn + `log_pass "stable"` (L48-50).
  - Changed → log_pass (L51-52).
  - Unchanged → log_warn + `log_pass "stable"` (L53-55).
- **Correctness:** GREEN-STICKER — 3 of 4 outcomes are warn-then-pass demotions. The only failure is unreachable (every code path ends in log_pass).
- **Severity:** RC1-BLOCK — claims advancement but cannot fail when advancement is absent.

#### test_pause_task (L59)
- **Claims:** Task paused.
- **Actually checks:** L76 non-empty result → log_pass; empty → log_warn + `log_pass "Pause endpoint responds"`.
- **Correctness:** GREEN-STICKER — endpoint-responds demotion.
- **Severity:** HIGH — pause/resume is a real product capability; this test cannot detect broken pause.

#### test_resume_task (L84)
- **Claims:** Task resumed.
- **Actually checks:** Same pattern as pause.
- **Correctness:** GREEN-STICKER.
- **Severity:** HIGH.

#### test_cluster_healthy_after_task_ops (L109) — SOUND.

**Summary 08-scheduled-tasks:** 6 functions, 3 GREEN-STICKER (advance, pause, resume). pass:fail = 11:0 — pure demotion file.

---

### test-sql-connector.sh

#### test_cluster_ready (L13) — SOUND.

#### test_deploy_sql_app (L18)
- **Claims:** SQL-backed app deployed.
- **Actually checks:** push/deploy returns demoted to log_warn (L19-20 `|| log_warn`); then `wait_for_slices_active 1 120`; `retarget_app_endpoint_to_active_slice ... || true`.
- **Correctness:** AMBER — push/deploy failures don't fail the test; only the wait_for_slices_active gate is strict. The retarget `|| true` is acknowledged in comments as "still tested via DHT".
- **Severity:** LOW — wait_for_slices_active provides the real assertion.

#### test_put_kv_pair (L38)
- **Claims:** PUT returns 2xx.
- **Actually checks:** `[ status >= 200 ] && [ status < 400 ]` → log_pass; else log_fail.
- **Correctness:** AMBER — accepts 3xx as success. For a PUT, 301/302 to a redirect would pass, but no such redirects exist for these endpoints; effectively SOUND.
- **Severity:** LOW.

#### test_get_kv_pair (L58)
- **Claims:** GET returns expected value.
- **Actually checks:** PUT precondition; then `value == "resolve-test-value"` exact match via `json_value`.
- **Correctness:** SOUND.

#### test_connection_pooling_rapid_requests (L82)
- **Claims:** Pool burst succeeds.
- **Actually checks:** L99 `assert_gt success 0` (almost tautological) AND L101 `assert_gt success $((POOL_BURST/2))` — majority threshold. Status check `>= 200 && < 400`.
- **Correctness:** AMBER — "at least some succeeded" + "majority succeeded" but not "all succeeded". For pool integrity, this masks ~50% failure rate.
- **Severity:** MEDIUM — production pool exhaustion likely manifests as 50/50 success/fail, which still passes.

#### test_cluster_healthy_after_sql_load (L104) — SOUND.

**Summary 08-sql:** 6 functions, mostly SOUND, AMBER threshold on pool test.

---

### test-streaming-resources.sh

#### test_cluster_ready (L13) — SOUND.

#### test_deploy_notification_hub (L18)
- **Claims:** Notification hub deployed.
- **Actually checks:** Body is `log_pass "Notification hub deployed"` only.
- **Correctness:** GREEN-STICKER — empty test that unconditionally passes.
- **Severity:** LOW — by-design (streams auto-create, no blueprint needed) but the test should be deleted, not faked.

#### test_stream_publisher_provisioned (L23)
- **Claims:** Stream visible in list.
- **Actually checks:** Exact field regex (FIXED from substring grep prior finding); on miss, log_warn + `log_pass "Stream list endpoint responds"`.
- **Correctness:** GREEN-STICKER (else branch) — same warn-then-pass pattern.
- **Severity:** MEDIUM.

#### test_publish_notifications (L37)
- **Claims:** All N notifications published.
- **Actually checks:** `assert_eq success EVENT_COUNT`. **Note L41 stderr swallow.** The prior `>/dev/null 2>&1 || true` finding is REMEDIATED — `|| true` is gone, failure increments the counter.
- **Correctness:** SOUND. **Prior `|| true` REMEDIATED.**

#### test_subscriber_receives_notifications (L51)
- **Claims:** Subscriber receives.
- **Actually checks:** `assert_ne info ""`.
- **Correctness:** AMBER — only endpoint liveness; same pattern as pub-sub's subscriber test but with strict assert (no warn-then-pass).
- **Severity:** LOW.

#### test_analytics_counts_increment (L58)
- **Claims:** Counts increment.
- **Actually checks:** Hard fail if failures > 1; warn if 0 < failures ≤ 1; else log_pass if info_before/after both non-empty, else demoted log_pass "Stream endpoints respond".
- **Correctness:** AMBER — counts are NOT actually compared; only "info available before AND after" is checked. The test name is misleading.
- **Severity:** MEDIUM — claim mismatch (no count comparison happens).

#### test_cluster_healthy_after_streaming (L93) — SOUND.

**Summary 08-streaming:** 7 functions; 2 GREEN-STICKER (deploy_notification_hub empty, stream_publisher_provisioned else branch), 1 AMBER (analytics_counts_increment), prior `|| true` REMEDIATED.

---

## 08-resources Summary Table

| Test File | SOUND | AMBER | GREEN-STICKER | Severity High |
|-----------|-------|-------|---------------|---------------|
| test-http-client.sh | 5 | 2 | 0 | none (404 fix verified) |
| test-pub-sub.sh | 3 | 1 | 2 | subscriber_receives_events RC1-BLOCK |
| test-scheduled-tasks.sh | 2 | 1 | 3 | advance/pause/resume RC1-BLOCK |
| test-sql-connector.sh | 4 | 2 | 0 | pool threshold MEDIUM |
| test-streaming-resources.sh | 3 | 2 | 2 | analytics MEDIUM |

**Prior-audit status:**
- test-http-client.sh:42-49 (< 500 as 404) — **REMEDIATED**
- test-pub-sub.sh:20 substring — **REMEDIATED** (now field-anchored regex)
- test-streaming-resources.sh:27 substring — **REMEDIATED**
- test-streaming-resources.sh:63 `|| true` — **REMEDIATED** (failures tracked, hard threshold)

---

## Suite 09-artifacts

### test-artifact-push-resolve.sh

All six functions are SOUND:
- **test_cluster_ready** (L24) — strict readiness + warn on task-active.
- **test_generate_artifact** (L30) — asserts size > 0.
- **test_push_artifact** (L37) — `[ status >= 200 ] && [ status < 300 ]`. Strict 2xx.
- **test_resolve_artifact** (L53) — strict 2xx with 2s replication wait (acknowledged sleep).
- **test_checksum_matches** (L68) — exact SHA-256 equality via `assert_eq`. Hard cryptographic check.
- **test_cluster_healthy_after** (L75) — exact `health=="healthy"` via aether CLI.

**No green stickers.** The hardcoded 2s replication sleep (L55) is the only soft spot but acknowledged in comment.

### test-artifact-replication.sh

- **test_cluster_ready** (L26) — Strict, requires ≥2 nodes via `assert_ge`.
- **test_identify_second_node** (L34)
  - **Claims:** Discover or fall back.
  - **Actually checks:** If endpoint provided → log_pass; else attempt grep on address/host (acknowledged the field doesn't exist post-T2.6); else fall back to CLUSTER_ENDPOINT (gateway round-robin).
  - **Correctness:** AMBER — fall-back-to-same-endpoint is acknowledged in L42-46 comment. The gateway *does* dispatch differently, so DHT replication is still exercised; just less direct.
  - **Severity:** LOW.
- **test_push_to_primary** (L72) — strict 2xx.
- **test_wait_for_replication** (L89) — 10s sleep + unconditional log_pass.
  - **Correctness:** GREEN-STICKER — hardcoded sleep substitutes for event wait. Empty body counts as success.
  - **Severity:** LOW — the *subsequent* resolve test asserts strict outcome.
- **test_resolve_from_second_node** (L96) — strict 2xx.
- **test_integrity_across_nodes** (L109) — strict SHA-256 equality.

**Summary:** 1 GREEN-STICKER (wait_for_replication is decorative), 1 AMBER (identify_second_node fallback). Core checksum-equality assertion makes this suite robust.

### test-large-artifact.sh

The helper `push_and_verify_size` is the SAME pattern — strict 2xx + SHA-256 equality.

- **test_cluster_ready** (L18) — strict + warn on task-active.
- **test_64kb_boundary** (L67), **test_128kb** (L71), **test_1mb** (L75), **test_5mb** (L79) — all delegate to push_and_verify_size.
- **test_5mb** has skip branch (L80-84) — `MAX_SIZE_MB < 5` → log_warn + log_pass "skipped by config". AMBER — by-design skip.
- **test_cluster_healthy_after_large_artifacts** (L88) — exact health check.

**Prior finding re-check:** Line 43 inverted check `[ status -lt 200 ] || [ status -ge 300 ]` — this has been REWRITTEN. L45 is now `if ! { [ "$push_status" -ge 200 ] && [ "$push_status" -lt 300 ]; }` (positive form with negation). The prior buggy inversion is REMEDIATED.

**Summary:** 6 functions, all SOUND. Prior inverted-check finding REMEDIATED. Excellent suite.

## 09-artifacts Summary Table

| Test File | SOUND | AMBER | GREEN-STICKER |
|-----------|-------|-------|---------------|
| test-artifact-push-resolve.sh | 6 | 0 | 0 |
| test-artifact-replication.sh | 4 | 1 | 1 |
| test-large-artifact.sh | 6 | 0 | 0 |

**This is the strongest suite in the audit batch.** Cryptographic checksums make assertions non-fakeable.

---

## Suite 10-database

### test-schema-baseline.sh

The prior-audit "Schema status responds (empty is valid)" GREEN-STICKER is **REMEDIATED**. All tests now:
1. Discover a real datasource via `/api/schema/status` GET filtering for `"datasource":"..."` field.
2. Assert datasource discovery succeeded (fails hard if not).
3. Address per-datasource endpoints.

- **test_cluster_ready** (L29) — push/deploy + `wait_for "tracked datasource discovered"` strict 60s timeout. Hard log_fail on miss.
- **test_schema_baseline_endpoint** (L49) — strict: empty datasource → fail; api_post failure → fail; empty body → fail (assert_ne).
- **test_schema_status_after_baseline** (L66) — case match: empty/UNKNOWN/FAILED → log_fail; anything else → log_pass.
  - **Correctness:** SOUND — strict acknowledgment that the baseline call landed.
- **test_slices_active_after_baseline** (L82) — `assert_gt instances 0` against real cluster state.
- **test_baseline_idempotent** (L90) — second POST returns 2xx + non-empty.
- **test_cluster_healthy_after_baseline** (L103) — SOUND.

**No green stickers. Prior finding REMEDIATED.**

### test-schema-retry.sh

- **test_cluster_ready** (L22) — Strict datasource discovery; hard fail.
- **test_schema_status_before_retry** (L37) — strict non-empty.
- **test_schema_retry_endpoint** (L54)
  - **Claims:** Endpoint contract works.
  - **Actually checks:** Either schema_retry succeeds (2xx) OR response contains "not in FAILED state" message → pass; else hard fail with body dump.
  - **Correctness:** SOUND — explicit contract: the documented orchestrator message proves the endpoint is wired. Comment acknowledges fault-injection TODO for the deeper FAILED→HEALTHY transition.
- **test_schema_status_after_retry** (L83) — case match on status_field: empty/UNKNOWN → fail; FAILED → pass (expected per orchestrator contract); anything else → pass.
  - **Correctness:** SOUND — orchestrator contract documented and tested.
- **test_retry_idempotent** (L100) — same contract.
- **test_cluster_healthy_after_retry** (L123) — SOUND.

**Summary:** 6 functions all SOUND. Excellent prior-state remediation. Contract-driven assertions explicit about what's testable without fault injection.

### test-schema-versioned.sh

- **test_cluster_ready** (L20) — Strict datasource discovery.
- **test_schema_status_endpoint** (L35) — strict non-empty after discovery.
- **test_migrations_applied** (L53)
  - **Claims:** currentVersion ≥ 1 after V900 migration.
  - **Actually checks:** `[ "$current_version" -gt 0 ]`. Sentinel `${current_version:--1}` prevents empty-string-to-zero coercion. **Prior `2>/dev/null` swallow finding is REMEDIATED** — sentinel handles missing field.
  - **Correctness:** SOUND. Compares against a real artifact (test-persistence ships V900).
- **test_schema_history_entries** (L72) — strict non-empty && not literal "null". SOUND.
- **test_global_schema_status** (L91) — Strict JSON-shape check (case match on starting `[` or `{`).
- **test_cluster_healthy_after_schema_check** (L105) — SOUND.

**Prior finding re-check:** L44 `[ "$applied_count" -gt 0 ]` after `2>/dev/null` parse swallow — REMEDIATED. The new check at L62 uses sentinel `${current_version:--1}` to distinguish missing field from zero. Comment at L51-52 explicitly addresses "canonical no-migrations state previously could not distinguish".

**Summary:** 6 functions all SOUND. Prior finding REMEDIATED.

## 10-database Summary Table

| Test File | SOUND | AMBER | GREEN-STICKER |
|-----------|-------|-------|---------------|
| test-schema-baseline.sh | 6 | 0 | 0 |
| test-schema-retry.sh | 6 | 0 | 0 |
| test-schema-versioned.sh | 6 | 0 | 0 |

**All prior green-stickers in 10-database REMEDIATED.** Suite is among the strongest.

---

## Suite 11-observability

### test-transport-metrics.sh — TARGETED PRIOR FINDING

Prior audit flagged L32-34, 44-45, 55-56, 73-74 as warn-then-pass demotion.

- **test_cluster_ready** (L9) — SOUND.
- **test_transport_metrics_endpoint** (L14)
  - **Claims:** Endpoint returns data.
  - **Actually checks:** `assert_ne metrics ""`.
  - **Correctness:** AMBER — any non-empty body passes. But subsequent tests are now strict.
- **test_active_connections_metric** (L22)
  - **Claims:** Connection metric present.
  - **Actually checks:** Regex match for `active.?connections|connections.?active|connectionCount` OR `"<...>connect<...>":` → log_pass; else log_fail with body dump.
  - **Correctness:** SOUND. **Prior finding REMEDIATED** — no warn-then-pass; explicit log_fail.
- **test_messages_sent_metric** (L33) — same pattern, strict log_fail on miss. **REMEDIATED.**
- **test_messages_received_metric** (L44) — same pattern. **REMEDIATED.**
- **test_transport_metrics_non_zero** (L58)
  - **Claims:** At least one numeric value > 0.
  - **Actually checks:** Regex `:[[:space:]]*[1-9][0-9]*` → log_pass; else log_fail.
  - **Correctness:** SOUND. This is a NEW STRICT test (didn't exist in prior audit). Catches "all-zero metrics" failure mode explicitly.

**Summary 11-transport-metrics:** 5 functions, all SOUND/AMBER. **All four prior-flagged warn-then-pass demotions REMEDIATED.** The "non-zero values" test was added as a new affirmative check.

### test-prometheus-metrics.sh — TARGETED PRIOR FINDING

Prior audit flagged L42-44, 53-55, 63-65, 77-80 as warn-then-pass demotion.

- **test_cluster_ready** (L9) — SOUND.
- **test_prometheus_endpoint_responds** (L14) — strict 2xx status check.
- **test_valid_prometheus_format** (L25) — strict: `assert_ne body ""` AND `assert_gt has_metric_line 0`. Has sentinel `|| has_metric_line=-1` for grep failure detection.
- **test_http_request_metrics** (L38)
  - **Claims:** HTTP request metrics present.
  - **Actually checks:** grep for `http_request|aether_http_requests|http_server_requests` → log_pass; else log_fail with body sample.
  - **Correctness:** SOUND. **REMEDIATED.**
- **test_jvm_metrics** (L52) — same pattern. **REMEDIATED.**
- **test_cluster_metrics** (L65) — same pattern. **REMEDIATED.**
- **test_no_empty_metric_values** (L79)
  - **Claims:** All metric lines have numeric values.
  - **Actually checks:** Sophisticated grep-rc handling: rc=0 (matches found) → bad_lines count; rc=1 (no matches) → 0; rc>1 (grep error) → hard log_fail. Threshold `bad_lines ≤ 2`.
  - **Correctness:** SOUND with documented thresholds and distinguishing grep failure modes.

**Summary 11-prometheus-metrics:** 7 functions, all SOUND. **All four prior-flagged warn-then-pass demotions REMEDIATED.** Pattern now: explicit log_fail with body dump on miss.

### test-alerts.sh — TARGETED PRIOR FINDING

Prior audit flagged L31-33, 59-65, 71-72, 83-85 as warn-then-pass demotion.

- **test_cluster_ready** (L17) — SOUND.
- **test_thresholds_endpoint** (L25) — strict `assert_http_status 200`.
- **test_set_alert_threshold** (L33)
  - **Claims:** POST accepts threshold + visible in GET.
  - **Actually checks:** POST with corrected body shape `{metric,warning,critical}` (comment notes prior body shape `{metric,operator,value,severity,name}` was wrong and warn-then-pass demotion swallowed the 500). Then GET /api/thresholds and `assert_contains` for the metric name.
  - **Correctness:** SOUND. **REMEDIATED with documented rationale.**
- **test_trigger_alert_condition** (L60)
  - **Claims:** Inject alert via /api/alerts/inject.
  - **Actually checks:** POST + extract `alertId` from response; hard fail if missing.
  - **Correctness:** SOUND. Uses NEW management API capability `/api/alerts/inject` (test-only endpoint added to close coverage gap).
- **test_check_alerts_fired** (L82)
  - **Claims:** GET /api/alerts surfaces injected alertId.
  - **Actually checks:** `assert_contains alerts $INJECTED_ALERT_ID` (correlated by id). Hard pre-condition guard.
  - **Correctness:** SOUND. **REMEDIATED — substring match is valid here since the injected ID is unique per-pid.**
- **test_alerts_have_fields** (L104)
  - **Claims:** Alert has name/severity/message/source fields.
  - **Actually checks:** Four `assert_contains` calls with documented substring rationale (comment explains brittle JSON re-encoding from `AlertManager.activeAlertsAsJson()`; substring is sufficient to catch omission; post-RC1 refactor planned).
  - **Correctness:** SOUND with documented limitation.
- **test_cluster_healthy_after_alerts** (L124) — SOUND.

**Summary 11-alerts:** 7 functions, all SOUND. **All four prior-flagged warn-then-pass demotions REMEDIATED.** The inject-endpoint architectural fix (operator-driven test injection) closes the original "no test-only metric to trigger threshold" gap.

### test-certificate-status.sh — TARGETED PRIOR FINDING

Prior audit flagged L25, 49, 84, 108.

- **test_cluster_ready** (L22) — SOUND.
- **test_certificate_endpoint** (L29) — strict non-empty + log_fail on api_get failure. **REMEDIATED at L25.**
- **test_expires_at_field** (L41)
  - **Claims:** expiresAt present and parseable when TLS configured.
  - **Actually checks:** Branch on `renewalStatus == NOT_CONFIGURED`:
    - NOT_CONFIGURED: empty expiresAt → log_pass (correctly absent); non-empty → log_fail.
    - Configured: empty → log_fail; non-ISO-8601 shape → log_fail; valid → log_pass.
  - **Correctness:** SOUND — handles both TLS-configured and not-configured paths strictly. **REMEDIATED at L49** (prior demotion gone).
- **test_seconds_until_expiry** (L73)
  - **Claims:** Seconds > 0 when TLS configured.
  - **Actually checks:** Strict branch on NOT_CONFIGURED; otherwise `[ seconds -gt 0 ]` or log_fail.
  - **Correctness:** SOUND. **REMEDIATED at L84.**
- **test_renewal_status_field** (L103)
  - **Claims:** renewalStatus present with known value.
  - **Actually checks:** Empty → log_fail; case match on known values → log_pass; unknown → log_fail.
  - **Correctness:** SOUND. **REMEDIATED at L108.**
- **test_certificate_not_expired** (L121) — Branch on NOT_CONFIGURED; otherwise strict expiry > 0 check.
  - **Correctness:** SOUND.

**Summary 11-certificate-status:** 6 functions, all SOUND. **All four prior-flagged warn-then-pass demotions REMEDIATED.** NOT_CONFIGURED branches are correctly vacuous (the field SHOULD be absent), not demoted demotions.

### test-events-cluster-ordering.sh — NEW FILE (not in prior audit)

- **test_cluster_ready** (L19) — SOUND.
- **test_inject_events_round_robin** (L28)
  - **Claims:** Round-robin injection across nodes.
  - **Actually checks:** Iterate ports `MGMT_PORT + i` per node, inject 3 alerts each. Hard fail if `injected < NODE_COUNT * 2` (allows 1/3 transient curl loss).
  - **Correctness:** SOUND with documented tolerance.
  - **Caveat:** Curl uses `2>/dev/null` (per silent-stderr-is-a-trap memory) — diagnostic loss but outcome is strict.
- **test_wait_for_replication** (L53)
  - **Claims:** Replication completes within 15s.
  - **Actually checks:** Poll for MARKER on node 0; on found → log_pass; on timeout → **log_pass with "marker not yet visible; order assertion follows"** (L66).
  - **Correctness:** GREEN-STICKER — tautological pass. The "order assertion follows" rationale is reasonable (the next test asserts on marker visibility across all nodes), but the test name and structure are misleading — it should be named `test_replication_window_elapsed` or removed.
  - **Severity:** LOW — the subsequent strict test makes this acceptable, but the warn-then-pass pattern is here in spirit.
- **test_all_nodes_agree_on_order** (L76)
  - **Claims:** All nodes see same ordered subsequence.
  - **Actually checks:** Iterate nodes, GET /api/events, extract marker-bearing events by regex on `"name":"<MARKER...>"`, compare to reference (first node).
  - **Correctness:** SOUND. Hard log_fail on no marker events; hard log_fail on order divergence with REFERENCE vs NODE dump to stderr.
- **test_cluster_healthy_after** (L112) — SOUND.

**Summary 11-events-cluster-ordering:** 5 functions, 1 GREEN-STICKER (wait_for_replication unconditional pass), 4 SOUND. The order-agreement test (the suite's actual purpose) is rigorous.

### test-invocation-traces.sh — TARGETED PRIOR FINDING

Prior audit flagged L35, 55, 75 ("Traces endpoint responds (empty)").

- **test_cluster_ready** (L19) — SOUND.
- **_inject_trace** (L29) helper — strict: hard log_fail on api_post failure; hard log_fail on missing requestId field.
- **test_generate_traceable_requests** (L51) — Calls _inject_trace 3 times; any failure propagates via `|| return 1`. SOUND.
- **test_traces_endpoint** (L64) — strict 200 status.
- **test_traces_contain_request_id** (L79)
  - **Claims:** Surfaces three injected requestIds.
  - **Actually checks:** `assert_contains` for each of 3 unique requestIds.
  - **Correctness:** SOUND. **REMEDIATED at L35** — prior "Traces endpoint responds (empty)" replaced with affirmative contains-check.
- **test_traces_contain_duration** (L96) — strict `assert_contains "durationMs":100`, `:250`, `:500` (exact literal substring with unique values).
  - **Correctness:** SOUND. **REMEDIATED at L55.**
- **test_traces_contain_depth** (L115) — strict assertions on depth=1, depth=2, and `callee` operation-name correlation for depth=0.
  - **Correctness:** SOUND with documented depth=0-not-unique correlation strategy. **REMEDIATED at L75.**

**Summary 11-invocation-traces:** 6 functions, all SOUND. **All three prior-flagged "Traces endpoint responds (empty)" demotions REMEDIATED.** The `/api/traces/inject` endpoint was added to provide deterministic field values for assertions.

## 11-observability Summary Table

| Test File | SOUND | AMBER | GREEN-STICKER | Prior findings status |
|-----------|-------|-------|---------------|------------------------|
| test-transport-metrics.sh | 4 | 1 | 0 | All 4 REMEDIATED + new non-zero test |
| test-prometheus-metrics.sh | 7 | 0 | 0 | All 4 REMEDIATED |
| test-alerts.sh | 7 | 0 | 0 | All 4 REMEDIATED via inject endpoint |
| test-certificate-status.sh | 6 | 0 | 0 | All 4 REMEDIATED via NOT_CONFIGURED branch |
| test-events-cluster-ordering.sh | 4 | 0 | 1 (wait_for_replication) | New file, mostly sound |
| test-invocation-traces.sh | 6 | 0 | 0 | All 3 REMEDIATED via inject endpoint |

**Headline:** 11-observability — the prior audit's "institutionalized warn-then-pass" subsystem — has been **systematically remediated**. The 2026-05-10b handover mentioned ~17 conversions; this audit verifies all observability-suite green-stickers in the prior report are gone. The architectural enablers were:
1. `/api/alerts/inject` test-injection endpoint (replaces threshold-driven firing that the runtime can't trigger).
2. `/api/traces/inject` test-injection endpoint (replaces "no deterministic trace source" problem).
3. NOT_CONFIGURED branch handling for cert tests (vacuous-but-explicit).
4. Sophisticated grep-rc handling in prometheus (distinguishes "no matches" from "grep error").

---

## Cross-Suite Findings

### Remaining Green-Stickers (this batch)

| Severity | Test | File:Line | Rationale |
|----------|------|-----------|-----------|
| RC1-BLOCK | test_subscriber_receives_events | 08-pub-sub.sh:52-62 | Tautological "endpoint responds" warn-then-pass; cannot detect broken subscriber. |
| RC1-BLOCK | test_task_last_execution_advances | 08-scheduled-tasks.sh:20-57 | 3 of 4 branches end in log_pass; cannot fail when advancement is absent. |
| HIGH | test_pause_task | 08-scheduled-tasks.sh:59-82 | Endpoint-responds demotion on empty result. |
| HIGH | test_resume_task | 08-scheduled-tasks.sh:84-107 | Same. |
| MEDIUM | test_competing_consumers_multi_instance | 08-pub-sub.sh:64-85 | Single-instance branch is unconditional pass. |
| MEDIUM | test_deploy_notification_hub | 08-streaming-resources.sh:18-21 | Empty body counts as success. |
| MEDIUM | test_stream_publisher_provisioned else branch | 08-streaming-resources.sh:32-34 | Warn-then-pass on stream-not-listed. |
| LOW | test_stream_exists_or_created else branch | 08-pub-sub.sh:27-29 | Same. |
| LOW | test_wait_for_replication | 09-replication.sh:89-94 | Decorative sleep + unconditional pass; subsequent test is strict. |
| LOW | test_wait_for_replication | 11-events-cluster-ordering.sh:53-67 | Timeout demotes to log_pass; subsequent test is strict. |

### Pass:Fail Demotion Self-Check (estimated from inspection)

- 08-resources/test-scheduled-tasks.sh: 11 log_pass, 0 hard log_fail — pure demotion file.
- 08-resources/test-pub-sub.sh: 9 log_pass, 0 hard log_fail in demoted tests.
- All other files: roughly balanced — every assert_* path produces a fail on miss.

### Strongest Suites

1. **09-artifacts** — Cryptographic checksum equality makes results non-fakeable.
2. **10-database** — All prior green-stickers remediated with documented contract reasoning.
3. **11-observability** — Massive remediation effort; inject endpoints close runtime-can't-trigger gaps.

### Weakest Suite

**08-resources/test-scheduled-tasks.sh** is the only file in this audit batch that remains a pure green-sticker / warn-then-pass cluster. Recommend RC1-blocking rewrite or feature deferral.

### Architectural Pattern Observations

1. **Test-only injection endpoints** (`/api/alerts/inject`, `/api/traces/inject`) are the architectural lever that unblocked the observability rewrite. Same pattern should be applied to scheduled-tasks (no equivalent inject endpoint exists).
2. **Datasource-discovery pattern** (10-database) — `wait_for "tracked datasource"` + strict per-datasource addressing — generalizes well; the schema suite is a model for other "endpoint smoke became strict assertion" rewrites.
3. **Branch-on-configuration** (cert tests' NOT_CONFIGURED branch) — distinct from warn-then-pass: it tests the "field correctly absent" contract rather than demoting to "endpoint responds".
4. **Documented substring rationale** (alerts test) — when full JSON parsing is brittle due to serialization quirks, documented substring with unique-token correlation is acceptable. The comment trail is part of the contract.

### Recommendations

1. **Rewrite 08-resources/test-scheduled-tasks.sh** using the inject-endpoint pattern. Add `/api/scheduled-tasks/inject` if one doesn't exist.
2. **Tighten 08-pub-sub.sh** subscriber test — either drop it or wire up a real consumer assertion via stream offset progression.
3. **Delete 08-streaming-resources.sh test_deploy_notification_hub** — empty test masquerading as deployment validation.
4. **Rename or restructure 11-events-cluster-ordering test_wait_for_replication** — the tautological pass is misleading even though the next test makes it functionally moot.
