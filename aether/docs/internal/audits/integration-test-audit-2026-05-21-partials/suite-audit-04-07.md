# Audit — Suites 04 / 05 / 06 / 07

Counted: 89 `run_test` invocations across 16 files; 44 `log_pass` calls *outside* test bodies tracked via `run_test`-driven assertion wrappers — but a substantial share of tests rely on conditional log_pass branches that can fire even when the feature under test is broken. Demotion patterns and green-sticker assertions noted per function below.

---

## Suite 04-streaming

### test-stream-replication.sh

#### test_cluster_ready (L12-16)
- **Claims:** Cluster is healthy enough to begin replication test.
- **Actually checks:** Calls `wait_for_cluster_ready 60`; then `wait_for_all_tasks_active 60 || log_warn` — task-group failure is demoted to a warning; the test still passes.
- **Assertions:** L15 `log_pass "Cluster ready"` (unconditional once `wait_for_cluster_ready` succeeds).
- **Correctness:** GREEN-STICKER — passes even when task groups are not ACTIVE. Replication then runs against half-ready cluster.
- **Tooling:** CLI (helpers wrap `aether ...`).
- **Severity:** RC2.

#### test_create_stream (L18-23)
- **Claims:** Stream is created via POST `/api/streams`.
- **Actually checks:** `api_post` body contains `STREAM_NAME` substring.
- **Assertions:** L22 `assert_contains "$result" "$STREAM_NAME"`.
- **Correctness:** NARROW — substring match would pass even on an echoed error body containing the name; also no status-code check. Acceptable for a happy-path smoke but not a regression net.
- **Tooling:** curl-direct (api_post).
- **Severity:** RC2.

#### test_publish_events_for_replication (L25-34)
- **Claims:** 10 events publish successfully.
- **Actually checks:** Increments `success` only on rc=0 from `stream_publish`; counts must equal 10.
- **Assertions:** L33 `assert_eq "$success" "10"`.
- **Correctness:** SOUND — `stream_publish` errors are explicitly counted as failures.
- **Tooling:** CLI.
- **Severity:** —

#### test_stream_visible_on_governor (L36-40)
- **Claims:** Stream metadata visible at the (lead-routing) entry point.
- **Actually checks:** `stream_info` returns non-empty string.
- **Assertions:** L39 `assert_ne "$info" ""`.
- **Correctness:** TAUTOLOGY — any non-empty body (even `{"error":"…"}`) passes. No assertion that the body actually describes `STREAM_NAME`.
- **Tooling:** CLI.
- **Severity:** RC2.

#### test_read_events_from_partition (L42-49)
- **Claims:** After publishing 10 events, partition-0 read returns events.
- **Actually checks:** Non-empty payload, contains substring `"events"`.
- **Assertions:** L47 non-empty; L48 contains `events`.
- **Correctness:** NARROW — `"events"` substring matches the field name even when the array is empty (`{"events":[]}`). Does not assert ≥1 event, no count, no key correlation.
- **Tooling:** curl-direct (api_get).
- **Severity:** RC1-BLOCK — this is the publish→read invariant; an empty array is the exact regression mode for streaming.

#### test_read_from_non_governor_node (L55-106)
- **Claims:** Cross-node replication: stream metadata reachable from a non-leader.
- **Actually checks:** Resolves non-leader endpoint; `curl -sf` for `/api/streams/${STREAM_NAME}`; demands non-empty body containing `STREAM_NAME`.
- **Assertions:** L104 non-empty; L105 contains stream name. Note: `result=$(curl … 2>/dev/null)` — stderr is swallowed; rc captured and emitted as `log_warn` (L98), then assertion still runs strictly. Good.
- **Correctness:** SOUND for the metadata-only path. Prior audit flagged warn-then-pass demotion at L46-50 / L81-87; current code is the post-fix version that explicitly states "Empty IS the failure mode" and runs strict asserts. But: still only reads metadata, not events — actual replicated *data* read from a non-governor is not exercised.
- **Tooling:** mixed (CLI for `cluster_leader`/`pick_non_leader`/`cloud_public_ip`, curl-direct for the alt-endpoint probe).
- **Severity:** RC2 — strengthened from prior version; coverage gap (data, not just metadata) is a follow-up.

#### test_stream_in_list_after_replication (L108-112)
- **Claims:** Stream appears in `aether streams list` after replication.
- **Actually checks:** `stream_list` output contains `STREAM_NAME`.
- **Assertions:** L111 contains.
- **Correctness:** SOUND but narrow — list contains substring; no replication-factor assertion.
- **Tooling:** CLI.
- **Severity:** —

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_create_stream | NARROW | curl-direct | RC2 |
| test_publish_events_for_replication | SOUND | CLI | — |
| test_stream_visible_on_governor | TAUTOLOGY | CLI | RC2 |
| test_read_events_from_partition | NARROW | curl-direct | **RC1-BLOCK** |
| test_read_from_non_governor_node | SOUND | mixed | RC2 |
| test_stream_in_list_after_replication | SOUND/NARROW | CLI | — |

### test-stream-consumer.sh

#### test_cluster_ready (L11-15)
- Same warn-demoted task-group readiness as above. GREEN-STICKER, RC2, CLI.

#### test_publish_and_verify_count (L17-44)
- **Claims:** After 20 publishes, stream tracks ≥1 message.
- **Actually checks:** Publishes 20 events (publish errors silenced with `> /dev/null 2>&1` — failures NOT counted!), then `sleep 2`, parses `totalEvents` with grep/sed fallback if `json_value` fails.
- **Assertions:** L30 stream_info non-empty; L43 `assert_gt msg_count 0`.
- **Correctness:** GREEN-STICKER (publish errors silenced — only the *count read* needs to succeed) + LOG-PARSE (grep+sed on JSON instead of structured parse). `msg_count="${msg_count:-0}"` followed by `assert_gt $msg_count 0` will silently pass even when the field is missing — wait no, 0 fails `> 0`. Actually correct on that point. But the publish step is the leak.
- **Tooling:** mixed (CLI + log-grep on JSON).
- **Severity:** RC1-BLOCK — silencing publish stderr means broken publish never trips the count.

#### test_stream_metadata (L46-57)
- **Claims:** `name` field present in stream metadata.
- **Actually checks:** Extracts `name` via `json_value` then grep+sed fallback; asserts non-empty.
- **Assertions:** L56 non-empty.
- **Correctness:** TAUTOLOGY — passes for any other stream's name in the array (no scoping by `STREAM_NAME`).
- **Tooling:** mixed (CLI + log-grep).
- **Severity:** RC2.

#### test_multiple_streams_isolation (L59-67)
- **Claims:** Isolated stream creation doesn't drop the original.
- **Actually checks:** Publishes to a *second* stream with stderr silenced (rc ignored), then asserts the first stream is still in `stream_list`.
- **Assertions:** L66 contains original name.
- **Correctness:** NARROW — "isolation" is not tested at all; it only asserts the first stream still exists. Renaming the test to "second stream creation does not delete the first" would be honest.
- **Tooling:** CLI.
- **Severity:** RC2 — mis-named, weakly assertive.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_publish_and_verify_count | GREEN-STICKER+LOG-PARSE | mixed | **RC1-BLOCK** |
| test_stream_metadata | TAUTOLOGY | mixed | RC2 |
| test_multiple_streams_isolation | NARROW | CLI | RC2 |

### test-stream-publish.sh

#### test_cluster_ready (L11-15) — GREEN-STICKER, CLI, RC2 (same as above).

#### test_publish_single_event (L17-22)
- **Claims:** Single publish returns a response.
- **Actually checks:** `stream_publish` returns non-empty.
- **Assertions:** L21 non-empty.
- **Correctness:** TAUTOLOGY — any error body passes.
- **Tooling:** CLI.
- **Severity:** RC2.

#### test_publish_batch (L24-36)
- **Claims:** 50/50 events publish.
- **Actually checks:** Counts `stream_publish` rc=0 (errors silenced as 2>&1 but rc preserved).
- **Assertions:** L35 `assert_eq success 50`.
- **Correctness:** SOUND.
- **Tooling:** CLI.
- **Severity:** —

#### test_stream_info (L38-42)
- **Claims:** Stream info endpoint returns a body.
- **Actually checks:** non-empty.
- **Assertions:** L41.
- **Correctness:** TAUTOLOGY — error body passes.
- **Tooling:** CLI. **Severity:** RC2.

#### test_stream_appears_in_list (L44-48)
- SOUND/NARROW. CLI. —

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_publish_single_event | TAUTOLOGY | CLI | RC2 |
| test_publish_batch | SOUND | CLI | — |
| test_stream_info | TAUTOLOGY | CLI | RC2 |
| test_stream_appears_in_list | SOUND | CLI | — |

### test-stream-under-load.sh

#### test_cluster_ready — GREEN-STICKER, CLI, RC2.

#### test_sustained_stream_publish (L28-63)
- **Claims:** Sustained publish error rate < 5%.
- **Actually checks:** Loops for `STREAM_DURATION` at `STREAM_RPS`, counts strictly `2xx` as success (L46 `-ge 200 -lt 300`). Prior audit flagged `< 400` (would have included 3xx) — current code corrects this.
- **Assertions:** L62 `assert_error_rate_below` against `success:failure` string.
- **Correctness:** SOUND — strict 2xx is the right gate.
- **Tooling:** curl-direct.
- **Severity:** —

#### test_stream_info_after_load (L65-69)
- **Claims:** Stream info still reachable after load.
- **Actually checks:** non-empty.
- **Assertions:** L68.
- **Correctness:** TAUTOLOGY.
- **Tooling:** CLI. **Severity:** RC2.

#### test_cluster_stable (L71-76)
- **Claims:** All 5 nodes survive, cluster healthy.
- **Actually checks:** `cluster_member_count == 5`; `assert_cluster_healthy`.
- **Correctness:** SOUND (hardcoded 5 — see prior MEMORY note that NODE_COUNT defaults exist; here hardcoded).
- **Tooling:** CLI.
- **Severity:** —

#### test_concurrent_publish_and_query (L78-100)
- **Claims:** Publish + query in parallel both succeed.
- **Actually checks:** Loops 20× sequentially (not parallel — `pub` and `query` happen in the same iteration but back-to-back); flags fail if any rc != 0.
- **Assertions:** L88/L90 log_pass else log_fail.
- **Correctness:** NARROW — labelled "concurrent" but is sequential. Bug class: race conditions in publish+info will not be caught.
- **Tooling:** CLI.
- **Severity:** RC2.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_sustained_stream_publish | SOUND | curl-direct | — |
| test_stream_info_after_load | TAUTOLOGY | CLI | RC2 |
| test_cluster_stable | SOUND | CLI | — |
| test_concurrent_publish_and_query | NARROW | CLI | RC2 |

---

## Suite 05-security

### test-cert-rotation.sh

#### test_cluster_ready (L16-19)
- Simple `wait_for_cluster_ready 60` + `log_pass`. SOUND. CLI.

#### test_tls_active (L21-26)
- **Claims:** TLS is enabled (cluster config is reachable).
- **Actually checks:** `cluster_config` returns non-empty.
- **Assertions:** L25 non-empty.
- **Correctness:** TAUTOLOGY — does *not* check for any TLS-related field; "config retrievable" is unrelated to TLS being active. Test name lies.
- **Tooling:** CLI. **Severity:** RC1-BLOCK — security-suite test that doesn't test the security property it claims.

#### test_rotation_under_load (L28-74)
- **Claims:** Cert rotation under load keeps error rate < 5%.
- **Actually checks:** Starts load (`GET /health/live`!) — but `/health/live` is *public* and bypasses TLS-cert-rotation impact entirely. Probes `/api/certificates`; if `renewalStatus=NOT_CONFIGURED`, takes early-pass with explanatory log_pass (L70). Otherwise POSTs `/api/config` rotation directive and asserts error rate.
- **Assertions:** L70 unconditional pass (vacuous); L73 `assert_error_rate_below`.
- **Correctness:** GREEN-STICKER on the no-rotation path (vacuous pass admitted by comment, cross-referenced to #209). Even the rotation path drives traffic at `/health/live` which is unauthenticated and cert-rotation-irrelevant. The whole test cannot fail.
- **Tooling:** mixed (CLI + curl-direct).
- **Severity:** RC1-BLOCK — explicitly self-described vacuous when TLS is not configured; load target is the wrong endpoint for the assertion when it does fire.

#### test_cluster_healthy_after_rotation (L76-79)
- `sleep 5; assert_cluster_healthy`. SOUND. CLI.

#### test_all_nodes_present (L81-85)
- `assert_ge cluster_member_count NODE_COUNT`. SOUND. CLI.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | SOUND | CLI | — |
| test_tls_active | TAUTOLOGY | CLI | **RC1-BLOCK** |
| test_rotation_under_load | GREEN-STICKER | mixed | **RC1-BLOCK** |
| test_cluster_healthy_after_rotation | SOUND | CLI | — |
| test_all_nodes_present | SOUND | CLI | — |

### test-principal-injection.sh

#### test_cluster_ready — SOUND. CLI.

#### test_admin_identity_in_response (L14-19)
- **Claims:** Authenticated request includes/acknowledges caller identity.
- **Actually checks:** `curl -sf` to `/api/nodes/status` with admin key; asserts non-empty body.
- **Assertions:** L18 non-empty.
- **Correctness:** TAUTOLOGY — doesn't grep for principal/identity field, doesn't even verify the response IS the status payload (just non-empty). Name is "principal in response"; check is "endpoint responded".
- **Tooling:** curl-direct.
- **Severity:** RC1-BLOCK — security-suite test that does not check the security property at all.

#### test_different_keys_different_identity (L21-34)
- **Claims:** Different API keys yield potentially different views (i.e., principal is honored).
- **Actually checks:** Both keys return non-empty.
- **Assertions:** L32 / L33 non-empty.
- **Correctness:** TAUTOLOGY — never compares the two responses; the test name promises "different identity" verification, the body never compares. Would pass if both responses are byte-identical (no principal injection at all).
- **Tooling:** curl-direct.
- **Severity:** RC1-BLOCK.

#### test_app_endpoint_principal (L36-52)
- **Claims:** App endpoint enforces auth on protected paths.
- **Actually checks:** `http_status` against `/api/health` (no auth) and `/` (with key); passes if *either* status > 0 (i.e., the app responded at all).
- **Assertions:** L46 OR-condition log_pass (any positive HTTP code passes); else log_fail.
- **Correctness:** GREEN-STICKER — "responding to requests" is not "enforcing auth on protected paths". `200` to unauth `/api/health` passes, no auth-required path is exercised, no 401 expected for non-auth probe.
- **Tooling:** curl-direct.
- **Severity:** RC1-BLOCK — labelled principal/auth enforcement, asserts liveness.

#### test_unauthenticated_response_format (L54-64)
- **Claims:** 401 includes WWW-Authenticate header.
- **Actually checks:** `curl -s -D - -o /dev/null` (note: no `-w "%{http_code}"`, no 401-status check at all). Grep for header; if absent, **`log_warn` then unconditional `log_pass`** (L62).
- **Assertions:** L59 / L62.
- **Correctness:** GREEN-STICKER (warn-then-pass demotion, exactly the pattern from the prior audit). Also doesn't even verify the response is 401.
- **Tooling:** curl-direct.
- **Severity:** RC1-BLOCK.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | SOUND | CLI | — |
| test_admin_identity_in_response | TAUTOLOGY | curl-direct | **RC1-BLOCK** |
| test_different_keys_different_identity | TAUTOLOGY | curl-direct | **RC1-BLOCK** |
| test_app_endpoint_principal | GREEN-STICKER | curl-direct | **RC1-BLOCK** |
| test_unauthenticated_response_format | GREEN-STICKER | curl-direct | **RC1-BLOCK** |

### test-route-security.sh

#### test_health_public_no_auth (L9-13)
- assert 200 on `/health/live` unauthenticated; assert_cluster_healthy. SOUND. mixed.

#### test_status_requires_auth (L15-20)
- curl, expect 401. **SOUND** — exact code check.
- curl-direct.

#### test_status_with_auth (L22-25)
- assert_http_status 200 with admin key. SOUND. mixed.

#### test_status_invalid_key (L27-32)
- curl, expect 403. SOUND. curl-direct.

#### test_viewer_can_read (L34-43)
- assert 200 on two endpoints with viewer key. SOUND. mixed.

#### test_viewer_cannot_mutate (L45-55)
- POST /api/scale with viewer; expect 403. SOUND. curl-direct.

#### test_admin_can_deploy (L57-71)
- **Claims:** Admin auth accepted on `/api/blueprints/validate`.
- **Actually checks:** Status not in {401, 403}.
- **Assertions:** L65 — any other status passes (including 400/500).
- **Correctness:** NARROW — appropriate for "auth pass" semantics, but a 5xx server-side bug would also be reported as a pass. Comment acknowledges (`auth should pass`). For an auth-only test, sound; for a deployment test, weak.
- **Tooling:** curl-direct.
- **Severity:** RC2.

#### test_operator_can_scale (L73-93)
- Same pattern as admin_can_deploy; SOUND for auth semantics, NARROW for outcome. curl-direct. RC2.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_health_public_no_auth | SOUND | mixed | — |
| test_status_requires_auth | SOUND | curl-direct | — |
| test_status_with_auth | SOUND | mixed | — |
| test_status_invalid_key | SOUND | curl-direct | — |
| test_viewer_can_read | SOUND | mixed | — |
| test_viewer_cannot_mutate | SOUND | curl-direct | — |
| test_admin_can_deploy | NARROW | curl-direct | RC2 |
| test_operator_can_scale | NARROW | curl-direct | RC2 |

---

## Suite 06-deployment

### test-deploy-immediate.sh

#### test_cluster_ready (L11-19)
- `wait_for_cluster_ready 60`, `wait_for_node_count 5 30`, then `push_blueprint`. No assertion on push success.
- **Correctness:** GREEN-STICKER (no assertion on push). CLI. RC2.

#### test_immediate_deploy (L21-26)
- `aether_failover deploy …`; assert non-empty result.
- **Correctness:** TAUTOLOGY — error body passes. CLI. RC2.

#### test_cluster_healthy_after_deploy (L28-32)
- `await_generation_quiesced … || log_warn` (demoted), then `assert_cluster_healthy`.
- **Correctness:** SOUND on health check; warn-demoted quiesce. mixed. —

#### test_slices_active (L34-39)
- `wait_for_slices_active 1 60`; `assert_gt slices_total_instances 0`.
- **Correctness:** SOUND. CLI. —

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_immediate_deploy | TAUTOLOGY | CLI | RC2 |
| test_cluster_healthy_after_deploy | SOUND | mixed | — |
| test_slices_active | SOUND | CLI | — |

### test-deploy-rolling.sh

#### test_cluster_ready — GREEN-STICKER (warn-demoted tasks). CLI. RC2.

#### test_rolling_start (L18-29)
- Five sequential helpers (deploy_cleanup, push v1, deploy v1, await quiesce | warn, push v2, publish v2, await quiesce | warn). Two quiesce demotions. Final `assert_contains result "deploymentId"`.
- **Correctness:** NARROW — quiesces demoted to warn; happy-path assertion narrow but acceptable. CLI. RC2.

#### test_rolling_promote (L31-41)
- `deploy_list`, extract id, `deploy_promote`, await quiesce | warn, `deploy_status` logged but **never asserted**.
- **Correctness:** GREEN-STICKER — promote outcome is logged not asserted. The id assertion (L35) is the only gate. CLI. **RC1-BLOCK** — promote success is the whole point of the rolling-deploy test.

#### test_rolling_complete (L43-51)
- Re-extracts id from `deploy_list`, calls `deploy_complete`, asserts result contains "COMPLETED".
- **Correctness:** SOUND. CLI. —

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_rolling_start | NARROW | CLI | RC2 |
| test_rolling_promote | GREEN-STICKER | CLI | **RC1-BLOCK** |
| test_rolling_complete | SOUND | CLI | — |

### test-deploy-canary.sh

#### test_cluster_ready — GREEN-STICKER. CLI. RC2.

#### test_canary_start (L23-38)
- Long setup; final assertions: `deployment list contains "deploymentId"` (NARROW — just substring), captured id non-empty.
- **Correctness:** NARROW. CLI. RC2.

#### test_canary_list (L40-44)
- `deploy_list` contains "CANARY". NARROW substring. CLI. RC2.

#### test_canary_promote (L46-53)
- DEPLOYMENT_ID assertion, promote called, await quiesce | warn, status logged but not asserted.
- **Correctness:** GREEN-STICKER — promote outcome unasserted (same as rolling). CLI. **RC1-BLOCK**.

#### test_canary_complete (L55-70)
- Branches: if status already COMPLETED, log_pass; else assert deploy_complete output contains "COMPLETED".
- **Correctness:** NARROW — substring match. The early branch is reasonable (idempotency). CLI. RC2.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_canary_start | NARROW | CLI | RC2 |
| test_canary_list | NARROW | CLI | RC2 |
| test_canary_promote | GREEN-STICKER | CLI | **RC1-BLOCK** |
| test_canary_complete | NARROW | CLI | RC2 |

### test-deploy-blue-green.sh

#### test_cluster_ready — GREEN-STICKER. CLI. RC2.

#### test_blue_green_start (L18-30) — NARROW (substring on deploymentId, double quiesce-demotion). CLI. RC2.

#### test_blue_green_promote (L32-42) — GREEN-STICKER (promote outcome logged, never asserted). CLI. **RC1-BLOCK**.

#### test_blue_green_complete (L56-64) — SOUND (assert_contains "COMPLETED"). CLI. —

Note: `test_blue_green_rollback` is defined (L44-54) but **never invoked** via `run_test` (see L73-76). The rollback path is dead code. Severity: RC2 (coverage gap), or RC1-BLOCK if rollback is part of the blue-green contract — it is, per the spec.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | GREEN-STICKER | CLI | RC2 |
| test_blue_green_start | NARROW | CLI | RC2 |
| test_blue_green_promote | GREEN-STICKER | CLI | **RC1-BLOCK** |
| test_blue_green_rollback (defined, not run) | n/a | CLI | **RC1-BLOCK** |
| test_blue_green_complete | SOUND | CLI | — |

### test-schema-migration.sh

#### test_cluster_ready (L20-31)
- Discovers datasource via `wait_for` predicate; explicit log_fail path.
- **Correctness:** SOUND. mixed. —

#### test_schema_status (L34-45)
- Per-datasource `schema_status`; asserts non-empty.
- **Correctness:** TAUTOLOGY — non-empty only; doesn't grep for currentVersion or healthy state. CLI. RC2.

#### test_schema_status_all (L49-59)
- Global status; asserts response begins with `[` or `{` (JSON-shape).
- **Correctness:** NARROW — shape-only, no content. CLI. RC2.

#### test_trigger_migration (L65-94)
- `wait_for` predicate that asserts `currentVersion ≥ 900` within 60s. Explicit log_fail with final-version diagnostic.
- **Correctness:** SOUND — proper polling, numeric comparison, fail-loud. mixed. —

#### test_schema_retry (L96-126)
- Per-contract behavior: accept 2xx OR 500 with body matching `'not in FAILED state'`. Comments document the contract.
- **Correctness:** SOUND — handles documented contract correctly. mixed. —

#### test_cluster_healthy_after_migration (L128-130) — SOUND. CLI. —

Prior audit flagged "empty body counts as success on schema endpoints". Current code corrects this: L55-58 explicitly fails on non-JSON, L86-88 polls until currentVersion ≥ 900 strictly.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | SOUND | mixed | — |
| test_schema_status | TAUTOLOGY | CLI | RC2 |
| test_schema_status_all | NARROW | CLI | RC2 |
| test_trigger_migration | SOUND | mixed | — |
| test_schema_retry | SOUND | mixed | — |
| test_cluster_healthy_after_migration | SOUND | CLI | — |

---

## Suite 07-cluster-mgmt

### test-bootstrap.sh

#### test_skip_if_running (L11-20) — Skip path if cluster already up. Acceptable. mixed.

#### test_config_exists (L22-29) — File-exists check; SOUND. shell.

#### test_bootstrap_cluster (L31-40)
- If `aether` in PATH: runs `aether cluster bootstrap` and logs pass. **No assertion** on `aether cluster bootstrap`'s success — it executes with `set -euo pipefail`, so a non-zero rc would abort the script, but `log_pass` immediately after the command means an exit-0 with internal warnings would still log_pass. Acceptable due to `set -e`.
- Else: log_warn → log_pass (warn-then-pass demotion).
- **Correctness:** GREEN-STICKER on the no-CLI path (the test name implies bootstrap was performed; the demotion path silently passes when bootstrap was skipped). CLI/shell. RC2.

#### test_cluster_forms (L42-45) — `wait_for_cluster_ready 90`; SOUND. CLI.

#### test_expected_node_count (L47-52) — `assert_eq count 5`. SOUND. CLI.

#### test_leader_elected (L54-59) — `assert_ne leader ""`. NARROW — passes for any non-empty string (could be an error message). CLI. RC2.

#### test_health_probes (L61-64) — Two SOUND probes (200 + cluster healthy). mixed.

#### test_management_api_accessible (L66-70) — `api_get /api/nodes/status` non-empty. TAUTOLOGY (no content assertion; error body passes). curl-direct. RC2.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_skip_if_running | SOUND | mixed | — |
| test_config_exists | SOUND | shell | — |
| test_bootstrap_cluster | GREEN-STICKER (no-CLI path) | CLI | RC2 |
| test_cluster_forms | SOUND | CLI | — |
| test_expected_node_count | SOUND | CLI | — |
| test_leader_elected | NARROW | CLI | RC2 |
| test_health_probes | SOUND | mixed | — |
| test_management_api_accessible | TAUTOLOGY | curl-direct | RC2 |

### test-destroy.sh

#### test_destroy_guard (L9-16) — Skip-by-default unless ALLOW_DESTROY=true; SOUND. shell.

#### test_cluster_exists (L18-23) — `assert_gt count 0`; SOUND. CLI.

#### test_destroy_cluster (L25-51)
- Uses `aether cluster destroy --yes` (if CLI present) with explicit rc check; else captures stop/rm stderr+rc separately and `log_fail` on rm failure.
- Prior audit flagged "xargs -r docker rm -f | true (cleanup masks failure)" — current code captures both rc and stderr and fails on non-zero. **SOUND.** mixed (CLI + docker).

#### test_cluster_gone (L53-63) — Asserts http_status `000` (unreachable). SOUND. curl-direct.

#### test_no_containers_running (L65-74) — `list_aether_containers` empty. SOUND. docker.

#### test_data_cleaned (L76-97)
- Captures rc/stderr explicitly; fail on infra failure; if directory has files, `log_warn` then `log_pass` (explicitly admitted: "data cleanup is optional").
- **Correctness:** GREEN-STICKER on the data-leftover branch — warn-then-pass demotion. Reasonable for "destroy" being primarily about containers, but the test name promises data cleanup. mixed. RC2.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_destroy_guard | SOUND | shell | — |
| test_cluster_exists | SOUND | CLI | — |
| test_destroy_cluster | SOUND | mixed | — |
| test_cluster_gone | SOUND | curl-direct | — |
| test_no_containers_running | SOUND | docker | — |
| test_data_cleaned | GREEN-STICKER | mixed | RC2 |

### test-apply.sh

#### test_cluster_ready — SOUND. CLI.

#### test_get_current_config (L14-18) — `config_export` non-empty. TAUTOLOGY — no JSON-shape check, no field check. CLI. RC2.

#### test_apply_config_override (L20-32) — `config_apply` returns non-empty; explicit log_fail on empty. NARROW (no echo-back / read-back verification). CLI. RC2.

#### test_config_converges (L34-38) — `sleep 5; assert_cluster_healthy`. SOUND but the "converges" claim is unverified — only health is checked, not that the applied key/value reached all nodes. NARROW. mixed. RC2.

#### test_config_visible_on_all_nodes (L40-48) — Calls `config_export` twice on the same endpoint (`$CLUSTER_ENDPOINT`); does not actually probe individual nodes. Both calls non-empty.
- **Correctness:** TAUTOLOGY — test name lies; "all nodes" is never probed. CLI. **RC1-BLOCK** — claims cross-node convergence, only re-hits the entry point.

#### test_overrides_endpoint (L50-59)
- Branches: data → log_pass; empty → log_pass.
- **Correctness:** GREEN-STICKER — both branches pass unconditionally; the test cannot fail. curl-direct. RC2.

#### test_cluster_unchanged (L61-65) — `assert_eq count 5`. SOUND. CLI.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | SOUND | CLI | — |
| test_get_current_config | TAUTOLOGY | CLI | RC2 |
| test_apply_config_override | NARROW | CLI | RC2 |
| test_config_converges | NARROW | mixed | RC2 |
| test_config_visible_on_all_nodes | TAUTOLOGY | CLI | **RC1-BLOCK** |
| test_overrides_endpoint | GREEN-STICKER | curl-direct | RC2 |
| test_cluster_unchanged | SOUND | CLI | — |

### test-export.sh

#### test_cluster_ready — SOUND. CLI.

#### test_export_config (L16-23) — non-empty export. TAUTOLOGY. CLI. RC2.

#### test_export_valid_json (L25-33) — grep `^[{[]`. NARROW (regex-shape only, not parseability). shell. RC2.

#### test_reapply_exported_config (L35-52) — apply a hard-coded `{key,value}` instead of the actual exported document; explicit log_fail on empty. SOUND for the narrow claim, but the test name promises **re-applying the exported config** (i.e., round-trip semantics). NARROW.
- **Correctness:** NARROW / mis-named. CLI. RC2.

#### test_config_identical_after_reapply (L54-68) — Computes byte counts of orig and new; **logs them but never asserts equality**. `log_pass` unconditional at L67.
- **Correctness:** GREEN-STICKER — test name promises "identical", body computes size diff and logs it, never asserts. Cannot fail. CLI. **RC1-BLOCK** — round-trip identity is the whole feature being tested.

#### test_cluster_healthy_after_roundtrip — SOUND. CLI.

#### Summary
| Function | Correctness | Tooling | Severity |
|---|---|---|---|
| test_cluster_ready | SOUND | CLI | — |
| test_export_config | TAUTOLOGY | CLI | RC2 |
| test_export_valid_json | NARROW | shell | RC2 |
| test_reapply_exported_config | NARROW | CLI | RC2 |
| test_config_identical_after_reapply | GREEN-STICKER | CLI | **RC1-BLOCK** |
| test_cluster_healthy_after_roundtrip | SOUND | CLI | — |

---

## Cross-suite roll-up

89 test functions audited. Pattern frequencies:

- **TAUTOLOGY (non-empty / substring "responds"):** 13
- **GREEN-STICKER (warn-then-pass demotion, unconditional log_pass, vacuous early-pass):** 14
- **NARROW (substring instead of structured parse; "not 401/403" admits 5xx; auth-test in name only):** 13
- **SOUND:** ~49

**RC1-BLOCKERS (12):**
1. `04-streaming/test-stream-replication.sh::test_read_events_from_partition` — `"events"` substring passes against empty array.
2. `04-streaming/test-stream-consumer.sh::test_publish_and_verify_count` — publish stderr silenced, failures uncounted.
3. `05-security/test-cert-rotation.sh::test_tls_active` — config-retrievable mis-labelled as TLS-active.
4. `05-security/test-cert-rotation.sh::test_rotation_under_load` — vacuous pass when TLS not configured (self-admitted); load drives `/health/live` (no cert path).
5. `05-security/test-principal-injection.sh::test_admin_identity_in_response` — non-empty body, no principal field check.
6. `05-security/test-principal-injection.sh::test_different_keys_different_identity` — never compares the two responses.
7. `05-security/test-principal-injection.sh::test_app_endpoint_principal` — "any positive HTTP code" passes.
8. `05-security/test-principal-injection.sh::test_unauthenticated_response_format` — warn-then-pass on missing WWW-Authenticate; status code never asserted to be 401.
9. `06-deployment/test-deploy-rolling.sh::test_rolling_promote` — promote outcome logged, never asserted.
10. `06-deployment/test-deploy-canary.sh::test_canary_promote` — same.
11. `06-deployment/test-deploy-blue-green.sh::test_blue_green_promote` — same; **and** `test_blue_green_rollback` is defined but never run.
12. `07-cluster-mgmt/test-apply.sh::test_config_visible_on_all_nodes` — only re-hits entry point; "all nodes" unverified.
13. `07-cluster-mgmt/test-export.sh::test_config_identical_after_reapply` — name promises identity, body never asserts identity.

**Suite 05-security is the worst** — out of 18 functions, only 8 are SOUND; the entire `test-principal-injection.sh` file is non-functional as a security gate. Counts log_pass calls vs run_test entries reveal: many `log_pass`-bearing branches inside conditionals that fire even when the underlying property is broken.

**Tooling distribution:** Predominantly CLI (helpers) with curl-direct used for status-code probes and direct endpoint inspection. No log-grep test bodies; minor JSON log-parse in `test-stream-consumer.sh::test_publish_and_verify_count` (grep+sed JSON parser). No docker-exec tests except in `test-destroy.sh` (appropriate for teardown).

**Demotion ratio:** ~44 log_pass calls vs 89 run_test invocations isn't a fair ratio because run_test wraps each function's pass/fail; the real signal is the *number of conditional log_pass branches that fire on the unhappy path*. Those are the 14 GREEN-STICKER findings above.
