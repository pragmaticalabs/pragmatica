# Suite 11-observability Charter

**Test-ID convention:** `TC-11-OBSERVABILITY-NNN`.

**Scope:** Observability surfaces — Prometheus exposition, transport-layer metrics, alerts (thresholds, injection, retrieval), TLS certificate status, invocation traces, and cluster event ordering. Per audit §1.13 this is the most heavily-remediated suite in the audit batch: the "institutionalised warn-then-pass subsystem" flagged by the prior audit (88638126) has been systematically rewritten. The architectural enablers were two test-only injection endpoints (`/api/alerts/inject`, `/api/traces/inject`) plus NOT_CONFIGURED branch handling for TLS-absent environments.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state before any observability probe runs. | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | `/metrics` (Prometheus exposition) returns 2xx and at least one `# HELP`/`# TYPE`/sample line (valid Prometheus text format). | `aether/docs/reference/management-api.md §Metrics`; `[CONTRACT-GAP]` for explicit Prometheus format SLA. |
| C3 | `/metrics` exposes HTTP request metrics (one of `http_request|aether_http_requests|http_server_requests`). | `aether/docs/reference/management-api.md §Metrics` |
| C4 | `/metrics` exposes JVM metrics (one of the canonical `jvm_*` series). | `aether/docs/reference/management-api.md §Metrics` |
| C5 | `/metrics` exposes cluster-level metrics (cluster size, member count, leader-status family). | `aether/docs/reference/management-api.md §Metrics` |
| C6 | `/metrics` lines all carry numeric values (no empty/`NaN` exposition lines; bounded tolerance of ≤2 malformed lines). | `[CONTRACT-GAP]` — threshold is empirical, not specced. |
| C7 | `/api/metrics/transport` returns transport metrics with active-connections, messages-sent, messages-received series. | `aether/docs/reference/management-api.md §Metrics` |
| C8 | Transport metric values include at least one strictly-positive number (the cluster IS actively communicating). | `aether/docs/reference/management-api.md §Metrics` |
| C9 | `/api/thresholds` is reachable with 200. | `aether/docs/reference/management-api.md §Alerts` |
| C10 | POST `/api/thresholds` accepts `{metric, warning, critical}` body shape, and the metric subsequently appears in GET `/api/thresholds`. | `aether/docs/reference/management-api.md §Alerts`; prior body shape `{metric,operator,value,severity,name}` was wrong per audit §1.13. |
| C11 | POST `/api/alerts/inject` accepts a test-injection request and returns a response containing an `alertId`. | `[CONTRACT-GAP]` — test-only endpoint, mirror `/api/traces/inject`. |
| C12 | GET `/api/alerts` surfaces the previously-injected `alertId` (alert lifecycle is wired end-to-end). | `aether/docs/reference/management-api.md §Alerts` |
| C13 | Each entry in `/api/alerts` carries the canonical fields `name`, `severity`, `message`, `source` (documented substring rationale — AlertManager JSON re-encoding is brittle; post-RC1 refactor planned). | `aether/docs/reference/management-api.md §Alerts` |
| C14 | `/api/certificate/status` returns a non-empty body with `renewalStatus` set to a known enum value. | `aether/docs/reference/management-api.md §Certificate Status` |
| C15 | When TLS is configured, `expiresAt` is present and ISO-8601 parseable; when `renewalStatus == NOT_CONFIGURED`, `expiresAt` MUST be absent (vacuous-but-explicit branch). | `aether/docs/reference/management-api.md §Certificate Status` |
| C16 | When TLS is configured, `secondsUntilExpiry > 0`; when NOT_CONFIGURED, the field is correctly absent. | `aether/docs/reference/management-api.md §Certificate Status` |
| C17 | When TLS is configured, certificate is not expired (`secondsUntilExpiry > 0`); when NOT_CONFIGURED, branch is vacuous. | `aether/docs/reference/management-api.md §Certificate Status` |
| C18 | Round-robin event injection across all node management ports succeeds in ≥ NODE_COUNT × 2 of NODE_COUNT × 3 attempts (allows ~1/3 transient curl loss). | `[CONTRACT-GAP]` — tolerance is documented in test, not in a spec. |
| C19 | After a bounded replication window, all nodes return the same ordered subsequence of marker-bearing events from `/api/events` (cluster event log ordering is consistent across nodes). | `aether/docs/specs/event-stream-namespaces-spec.md`; `aether/docs/reference/management-api.md §Events` |
| C20 | POST `/api/traces/inject` accepts a test-injection request specifying `requestId, durationMs, depth, callee` and produces a trace entry. | `[CONTRACT-GAP]` — test-only endpoint, mirror `/api/alerts/inject`. |
| C21 | GET `/api/traces` surfaces each previously-injected `requestId`. | `aether/docs/reference/management-api.md §Traces` |
| C22 | GET `/api/traces` surfaces each injected trace's `durationMs` (exact substring with unique values 100, 250, 500). | `aether/docs/reference/management-api.md §Traces` |
| C23 | GET `/api/traces` surfaces injected `depth` values (1, 2) plus `callee` operation-name correlation for depth=0 (depth=0 is not unique). | `aether/docs/reference/management-api.md §Traces` |
| C24 | Cluster remains healthy after each observability workload. | `aether/docs/specs/test-readiness-contract.md §1.1` |

---

## Test-to-contract map

### test-transport-metrics.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-11-OBSERVABILITY-001 | `test_cluster_ready` | `test-transport-metrics.sh:9` | C1 | smoke | — |
| TC-11-OBSERVABILITY-002 | `test_transport_metrics_endpoint` | `test-transport-metrics.sh:14` | C7 | regression-net | `assert_ne metrics ""`. AMBER per audit §1.13 — endpoint liveness only, but subsequent strict checks compensate. |
| TC-11-OBSERVABILITY-003 | `test_active_connections_metric` | `test-transport-metrics.sh:22` | C7 | core | Regex match (multiple synonym families) → log_pass; else hard log_fail with body dump. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-004 | `test_messages_sent_metric` | `test-transport-metrics.sh:33` | C7 | core | Same pattern. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-005 | `test_messages_received_metric` | `test-transport-metrics.sh:44` | C7 | core | Same pattern. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-006 | `test_transport_metrics_non_zero` | `test-transport-metrics.sh:58` | C8 | core | Regex `:[[:space:]]*[1-9][0-9]*` → log_pass; else log_fail. NEW strict test per audit §1.13 (catches all-zero metrics). |

### test-prometheus-metrics.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-11-OBSERVABILITY-007 | `test_cluster_ready` | `test-prometheus-metrics.sh:9` | C1 | smoke | — |
| TC-11-OBSERVABILITY-008 | `test_prometheus_endpoint_responds` | `test-prometheus-metrics.sh:14` | C2 | core | Strict 2xx status check. |
| TC-11-OBSERVABILITY-009 | `test_valid_prometheus_format` | `test-prometheus-metrics.sh:25` | C2 | core | Strict `assert_ne body ""` AND `assert_gt has_metric_line 0`. Sentinel `|| has_metric_line=-1` for grep failure detection. |
| TC-11-OBSERVABILITY-010 | `test_http_request_metrics` | `test-prometheus-metrics.sh:38` | C3 | core | Grep alternation across three canonical metric families; log_fail with body sample on miss. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-011 | `test_jvm_metrics` | `test-prometheus-metrics.sh:52` | C4 | core | Same pattern. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-012 | `test_cluster_metrics` | `test-prometheus-metrics.sh:65` | C5 | core | Same pattern. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-013 | `test_no_empty_metric_values` | `test-prometheus-metrics.sh:79` | C6 | core | Sophisticated grep-rc handling: rc=0 → bad_lines count; rc=1 → 0; rc>1 → hard log_fail (grep error). Threshold `bad_lines ≤ 2`. |

### test-alerts.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-11-OBSERVABILITY-014 | `test_cluster_ready` | `test-alerts.sh:17` | C1 | smoke | — |
| TC-11-OBSERVABILITY-015 | `test_thresholds_endpoint` | `test-alerts.sh:25` | C9 | core | Strict `assert_http_status 200`. |
| TC-11-OBSERVABILITY-016 | `test_set_alert_threshold` | `test-alerts.sh:33` | C10 | core | POST with corrected body shape `{metric, warning, critical}` (prior shape `{metric,operator,value,severity,name}` was wrong; warn-then-pass swallowed the 500). Then GET `/api/thresholds` and `assert_contains` on metric name. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-017 | `test_trigger_alert_condition` | `test-alerts.sh:60` | C11 | core | Uses test-only `/api/alerts/inject` endpoint; extracts `alertId` from response; hard fail if missing. Replaces threshold-driven firing the runtime can't trigger. |
| TC-11-OBSERVABILITY-018 | `test_check_alerts_fired` | `test-alerts.sh:82` | C12 | core | `assert_contains alerts $INJECTED_ALERT_ID` (correlated by unique-per-pid id). Hard pre-condition guard on pre-injected id. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-019 | `test_alerts_have_fields` | `test-alerts.sh:104` | C13 | core | Four `assert_contains` calls (name, severity, message, source) with documented substring rationale — AlertManager JSON re-encoding is brittle; substring is sufficient to catch field omission; post-RC1 refactor planned. |
| TC-11-OBSERVABILITY-020 | `test_cluster_healthy_after_alerts` | `test-alerts.sh:124` | C24 | core | — |

### test-certificate-status.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-11-OBSERVABILITY-021 | `test_cluster_ready` | `test-certificate-status.sh:22` | C1 | smoke | — |
| TC-11-OBSERVABILITY-022 | `test_certificate_endpoint` | `test-certificate-status.sh:29` | C14 | core | Strict non-empty + log_fail on `api_get` failure. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-023 | `test_expires_at_field` | `test-certificate-status.sh:41` | C15 | core | Branch on `renewalStatus == NOT_CONFIGURED`: NOT_CONFIGURED + empty expiresAt → log_pass; NOT_CONFIGURED + non-empty → log_fail; Configured + empty → log_fail; Configured + non-ISO-8601 → log_fail; Configured + valid → log_pass. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-024 | `test_seconds_until_expiry` | `test-certificate-status.sh:73` | C16 | core | Strict branch on NOT_CONFIGURED; otherwise `[ seconds -gt 0 ]` or log_fail. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-025 | `test_renewal_status_field` | `test-certificate-status.sh:103` | C14 | core | Empty → log_fail; case match on known enum values → log_pass; unknown → log_fail. Prior warn-then-pass REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-026 | `test_certificate_not_expired` | `test-certificate-status.sh:121` | C17 | core | Branch on NOT_CONFIGURED; otherwise strict `expiry > 0`. |

### test-events-cluster-ordering.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-11-OBSERVABILITY-027 | `test_cluster_ready` | `test-events-cluster-ordering.sh:19` | C1 | smoke | — |
| TC-11-OBSERVABILITY-028 | `test_inject_events_round_robin` | `test-events-cluster-ordering.sh:28` | C18 | core | Iterates ports `MGMT_PORT + i` per node, injects 3 alerts each. Hard fail if `injected < NODE_COUNT * 2` (allows ~1/3 transient curl loss). Caveat: curl uses `2>/dev/null` per silent-stderr-is-a-trap memory — diagnostic loss but outcome is strict. |
| TC-11-OBSERVABILITY-029 | `test_wait_for_replication` | `test-events-cluster-ordering.sh:53` | C19 | regression-net | Polls for MARKER on node 0; on found → log_pass; on timeout → log_pass with "marker not yet visible; order assertion follows". GREEN-STICKER per audit §1.13, LOW — the next strict test compensates. Test name is misleading vs. structure. |
| TC-11-OBSERVABILITY-030 | `test_all_nodes_agree_on_order` | `test-events-cluster-ordering.sh:76` | C19 | core | Iterates nodes, GETs `/api/events`, extracts marker-bearing events via regex on `"name":"<MARKER...>"`, compares each node's ordered subsequence to the reference (first node). Hard log_fail on no marker events; hard log_fail on order divergence with REFERENCE vs NODE dump to stderr. |
| TC-11-OBSERVABILITY-031 | `test_cluster_healthy_after` | `test-events-cluster-ordering.sh:112` | C24 | core | — |

### test-invocation-traces.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-11-OBSERVABILITY-032 | `test_cluster_ready` | `test-invocation-traces.sh:19` | C1 | smoke | — |
| TC-11-OBSERVABILITY-033 | `test_generate_traceable_requests` | `test-invocation-traces.sh:51` | C20 | core | Calls helper `_inject_trace` 3 times with distinct `(requestId, durationMs, depth)` triples. Helper hard-fails on api_post failure OR missing `requestId` in response; any helper failure propagates via `|| return 1`. |
| TC-11-OBSERVABILITY-034 | `test_traces_endpoint` | `test-invocation-traces.sh:64` | C21 | core | Strict 200 status. |
| TC-11-OBSERVABILITY-035 | `test_traces_contain_request_id` | `test-invocation-traces.sh:79` | C21 | core | `assert_contains` for each of 3 unique requestIds. Prior "Traces endpoint responds (empty)" REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-036 | `test_traces_contain_duration` | `test-invocation-traces.sh:96` | C22 | core | Strict `assert_contains "durationMs":100`, `:250`, `:500` (exact literal substring with unique values). Prior "Traces endpoint responds (empty)" REMEDIATED per audit §1.13. |
| TC-11-OBSERVABILITY-037 | `test_traces_contain_depth` | `test-invocation-traces.sh:115` | C23 | core | Strict assertions on depth=1, depth=2, and `callee` operation-name correlation for depth=0 (depth=0 is not unique across traces). Prior "Traces endpoint responds (empty)" REMEDIATED per audit §1.13. |

---

## Suite-level invariants

- **Pre-conditions:** cluster A (non-destructive). The cluster MAY have TLS configured or not — `test-certificate-status.sh` handles both via NOT_CONFIGURED branching. NODE_COUNT honored for `test-events-cluster-ordering.sh` round-robin tests.
- **Side effects:** writes alert injections (`/api/alerts/inject`), trace injections (`/api/traces/inject`), and threshold definitions (`/api/thresholds` POST). State is per-pid and per-test-run; collisions across re-runs are avoided by unique injected ids.
- **Cleanup discipline:** no explicit EXIT trap. Trace and alert state persists between runs by design — observability data is meant to be observable across the cluster's lifetime.
- **Architectural levers** (cited by audit §1.13 as the remediation enablers):
  1. `/api/alerts/inject` test-injection endpoint (replaces threshold-driven firing the runtime can't trigger).
  2. `/api/traces/inject` test-injection endpoint (replaces "no deterministic trace source" problem).
  3. NOT_CONFIGURED branch handling for cert tests (vacuous-but-explicit, distinct from warn-then-pass).
  4. Sophisticated grep-rc handling in prometheus tests (distinguishes "no matches" from "grep error" — rc=1 ≠ rc>1).
- **Tooling:** this suite is heavily `curl-direct` rather than CLI-driven. The audit §3 cross-cut flags this as a possible CLI gap; for now it is acceptable because the assertions are byte-level on response bodies and adding a CLI indirection would dilute the direct-on-API contract.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-11-OBSERVABILITY-002 | Only checks endpoint liveness, not metric content. | Audit §1.13 AMBER, LOW — compensated by TC-11-OBSERVABILITY-003/004/005/006 strict checks. |
| TC-11-OBSERVABILITY-013 | `bad_lines ≤ 2` threshold is empirical, not specced. | Contract gap C6. |
| TC-11-OBSERVABILITY-019 | Field assertions use substring matching due to brittle AlertManager JSON re-encoding. Documented inline. | Post-RC1 refactor planned (audit §1.13). |
| TC-11-OBSERVABILITY-028 | Curl uses `2>/dev/null` — diagnostic loss on individual curl failure (silent-stderr-is-a-trap pattern), though the strict `injected < NODE_COUNT * 2` floor catches outcome regressions. | Audit §1.13 SOUND with caveat — consider capturing stderr to a per-iteration log for diagnostics. |
| TC-11-OBSERVABILITY-029 | Timeout path is unconditional `log_pass`. Decorative — the next test's strict order-agreement assertion is the real contract gate. | Audit §1.13 GREEN-STICKER, LOW — rename to `test_replication_window_elapsed` to align name with semantics. |

### Contract gaps

- **C2** — no explicit Prometheus exposition-format SLA documented in `management-api.md`. The test's "≥1 `# HELP`/`# TYPE`/sample line" is reasonable but informal.
- **C6** — "≤2 empty-valued metric lines" threshold lives only in test code. Either tighten to zero (strict) or document the tolerance.
- **C11, C20** — `/api/alerts/inject` and `/api/traces/inject` are test-only endpoints critical to making observability testable, but neither has a dedicated spec section. Add a `management-api.md §Test Injection` paragraph that documents both endpoints (and the equivalent `/api/scheduled-tasks/inject` from suite 08) as a coordinated test-injection surface.
- **C18** — event-injection tolerance (`< NODE_COUNT * 2` of `NODE_COUNT * 3`) is documented in the test but not in `event-stream-namespaces-spec.md`. Codify whether 33% transient loss is acceptable for the cluster event ingestion path or whether the floor should be tightened.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter authoring agent | Initial charter; TC-11-OBSERVABILITY-001 through TC-11-OBSERVABILITY-037 catalogued from audit §1.13. All prior warn-then-pass demotions (transport/prometheus/alerts/cert/traces) recorded as REMEDIATED via inject endpoints and NOT_CONFIGURED branching. |
