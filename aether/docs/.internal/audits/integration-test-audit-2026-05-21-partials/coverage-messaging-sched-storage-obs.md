## Messaging (Pub-Sub)

| Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 22 Publisher/Subscriber API | Complete | `test-pub-sub.sh` exercises publish + subscriber API; relies on Java unit tests in catalog ("18 unit tests"). Integration tests: `test_publish_events` SOUND. | **COVERED** | audit §1.10: `test_publish_events` SOUND |
| 23 Topic subscription registry (competing consumers round-robin) | Complete | `test_competing_consumers_multi_instance` is GREEN-STICKER on single-instance branch (no real multi-instance assertion). Round-robin behavior never asserted. | **PARTIAL** | audit §1.10: `test_competing_consumers_multi_instance` MEDIUM green-sticker |
| 24 Message delivery (cross-node fan-out, failover) | Battle-tested | `test_subscriber_receives_events` is double warn-then-pass RC1-blocker — test name claims subscriber functionality but no consumer is ever attached and no event count is verified. Failover scenarios not exercised in integration suites (only Forge unit/integration test PubSubTest, which is not in `suites/`). | **PARTIAL** | audit §1.10: `test_subscriber_receives_events` **RC1-BLOCK**; `test_publish_events` SOUND |
| 25 Resource lifecycle (ref-counted, generated stop()) | Complete | No integration test exercises slice deactivation → consumer cleanup → re-deploy. `test-streaming-resources.sh` deploys but never verifies releaseAll/stop cleanup. | **NONE** |  — |

### Section summary
- 4 features classified
- **1 COVERED / 2 PARTIAL / 1 NONE**
- Notable RC1 gaps: feature 24 message-delivery contract is the RC1-blocker for pub-sub; feature 23 round-robin claim and feature 25 lifecycle have no strict assertion.

---

## Scheduled Invocation

| Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 26 Scheduled task registry (`paused` field, change listener) | Complete | `test_scheduled_tasks_endpoint` AMBER (non-empty-as-success). `paused` field round-trip never asserted in integration. | **PARTIAL** | audit §1.10 |
| 27 Scheduled task manager (SINGLE/ALL, quorum gating, interval parsing, cron, pause/resume) | Complete | `test_task_last_execution_advances` is **RC1-BLOCK**: 3 of 4 outcomes are warn-then-pass; the only failure path is unreachable. ExecutionMode ALL/SINGLE never differentiated in tests. Cron and interval-parsing paths not exercised at integration layer. | **PARTIAL** | audit §1.10: `test_task_last_execution_advances` **RC1-BLOCK** |
| 28 Cron expression parser (5-field, ranges/steps/lists) | Complete | Catalog cites 11 unit tests. No integration test validates a cron-scheduled task fires on schedule. | **PARTIAL** | unit-test backstop only |
| 29 Scheduled task KV types | Complete | No integration test inspects `ScheduledTaskKey`/`ScheduledTaskValue` shape directly; only the management API endpoint shape is poked (and AMBER). | **NONE** | — |
| 30 Deployment lifecycle wiring (publish/unpublish during activation/deactivation/reactivation/failure cleanup) | Complete | No integration test deploys → undeploys → asserts task entries removed from KV-Store. | **NONE** | — |
| 31 Scheduled tasks management API (list/filter/pause/resume/trigger CLI) | Complete | `test_pause_task` + `test_resume_task` are both GREEN-STICKER HIGH severity — pause/resume "is a real product capability that this suite cannot detect as broken." `trigger` (manual) and `filter` are not exercised. List works only as AMBER. | **PARTIAL** | audit §1.10: pause/resume HIGH green-sticker |
| 104 Execution state tracking (last execution, consecutive failures, total executions) | Complete | `lastExecutionTime` *is* read by `test_task_last_execution_advances`, but the test is the RC1-blocker; `consecutiveFailures` / `totalExecutions` never asserted. | **PARTIAL** | audit §1.10 |

### Section summary
- 7 features classified
- **0 COVERED / 5 PARTIAL / 2 NONE**
- Notable RC1 gaps: 2 RC1-blockers in `test-scheduled-tasks.sh` (11 `log_pass` / 0 hard `log_fail` in demoted paths); cron + ExecutionMode ALL/SINGLE + deployment-lifecycle KV cleanup all unverified. Entire scheduled-invocation surface (Complete in catalog) lacks a single SOUND integration test.

---

## Storage & Data

| Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 32 Artifact repository (Maven-compatible, chunked, MD5/SHA1, 64MB cap, metadata.xml) | Battle-tested | `09-artifacts` is "strongest suite in the audit." Push/resolve + 64KB/128KB/1MB/5MB boundary + SHA-256 strict integrity + cross-node replication all SOUND. **However**, the catalog claims MD5/SHA1 checksums and `metadata.xml` generation — tests verify SHA-256 only, not MD5/SHA1, and no `maven-metadata.xml` shape assertion. Chunking is implicit via large-artifact sizes. | **PARTIAL** | audit §1.11 "Strongest suite"; checksum-test files use SHA-256 only |
| 33 Distributed hash table (consistent hash ring, quorum R/W, anti-entropy, re-replication, DHTRebalancer) | Battle-tested | `test-artifact-replication.sh` exercises DHT replication via artifact path (SOUND), but: anti-entropy CRC32 digest exchange, re-replication on node departure (DHTRebalancer), and `scoped()` per-use-case config have no dedicated integration test. Quorum R/W indirectly validated via artifact cross-node integrity. | **PARTIAL** | audit §1.11 `test_integrity_across_nodes` SOUND; no anti-entropy/rebalancer test |
| 34 Configuration service (TOML, runtime overrides via KV-Store, env interpolation, sysprop fallback) | Complete | `07-cluster-mgmt/test-apply.sh` + `test-export.sh`: `test_apply_config_override` NARROW, `test_config_visible_on_all_nodes` is **RC1-BLOCK** (TAUTOLOGY — queries same endpoint twice), `test_config_identical_after_reapply` is **RC1-BLOCK** (computes byte counts but never asserts equality). Env-var interpolation and sysprop fallback never tested. | **PARTIAL** | audit §1.9: 2 RC1-blockers in config tests |
| 105 Hybrid Logical Clock | Complete | No integration test exercises HlcTimestamp / HlcClock semantics, drift detection, or counter overflow. Indirectly tested via DHT versioned-write replication. | **NONE** | grep: no HLC references in suites/ |
| 106 DHT versioned writes (HLC-stamped, version comparison, stale rejection) | Complete | `test-schema-versioned.sh` mentions "versioned" but is about schema migrations, not DHT-versioned puts. No stale-write rejection test. `withSuccess()` causal ordering claim untested. | **NONE** | no DHT versioned-write test |
| 107 Centralized timeout configuration (`TimeoutsConfig`, 13 subsystem groups, legacy `_ms` migration) | Complete | No integration test asserts `[timeouts.*]` TOML sections take effect, that `_ms` legacy migration runs, or that timeout overrides reach subsystems. Only `lib/common.sh` references timeouts for its own scaling logic. | **NONE** | grep: no TimeoutsConfig test reference |
| 206 KV-Store durable backup (TOML in git repo, BackupService, REST/CLI) | Complete | No integration test invokes BackupService, REST backup API, or CLI backup commands. No git-repo snapshot/restore round-trip test. | **NONE** | grep: zero backup test files |
| 207 Hierarchical Storage Engine (content-addressed, BlockId, Memory+LocalDisk tiers, SingleFlightCache, snapshot, gate, REST+CLI) | Complete | **14-storage is the weakest suite in the audit.** 4 of 9 tests silently `skip_test` or `log_warn → return 0` on absent functionality. `test_storage_list_contains_artifacts` is **HIGH** green-sticker (warn → return 0 when "artifacts" instance missing). `test_storage_snapshot` is **HIGH** green-sticker (empty body → return 0). Block-level CAS, tier-waterfall reads, SingleFlightCache dedup, StorageReadinessGate all unverified. | **PARTIAL** | audit §1.16: 4 silent-skip green stickers; "weakest suite" |

### Section summary
- 8 features classified
- **0 COVERED / 5 PARTIAL / 3 NONE**
- Notable RC1 gaps: 14-storage silent-skip pattern means a missing "artifacts" storage instance (mandatory for every deploy) goes undetected. Config-service has 2 RC1-blockers. HLC, DHT versioned writes, TimeoutsConfig, and KV-Store backup — all "Complete" features — have **no** integration test. Storage & Data is the weakest section in the catalog by integration-test correctness.

---

## Observability & Metrics

| Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 35 System metrics (CPU, heap, event-loop lag, 120-min window) | Battle-tested | `test_jvm_metrics` SOUND (heap covered) but the per-node 120-minute aggregation-window claim and event-loop-lag metric are not specifically asserted. No system-metric-specific test file. | **PARTIAL** | audit §1.13: `test_jvm_metrics` SOUND, but window/event-loop unverified |
| 36 Invocation metrics (per-method counts, P50/P95/P99, slow-invocation detection) | Complete | grep for `p50/p95/p99/percentile/slow.invocation/InvocationMetric` → **zero matches** in suites. Percentile metrics never asserted. `test_http_request_metrics` SOUND covers raw http_request metrics, not per-method invocation percentiles. | **PARTIAL** | grep result 0; `test_http_request_metrics` SOUND for adjacent claim |
| 37 Cluster metrics API (load, deployment timeline, error rates, saturation, health score, capacity prediction) | Battle-tested | `test_cluster_metrics` greps for `aether_/cluster_/node_` prefix in Prometheus body — SOUND but indirect. Saturation, health-score, capacity-prediction fields never asserted by name. Deployment timeline endpoint not tested. | **PARTIAL** | audit §1.13: `test_cluster_metrics` SOUND but shape-only |
| 38 Historical metrics (time-range queries 5m/15m/1h/2h, per-node snapshots) | Complete | No test exercises `?range=5m|15m|1h|2h` query parameters or per-node snapshot return shape. | **NONE** | grep: no time-range test |
| 39 Alert management (active/historical, threshold trigger, KV-Store persistence, CLI) | Complete | All 5 functions in `test-alerts.sh` SOUND (`test_thresholds_endpoint`, `test_set_alert_threshold`, `test_trigger_alert_condition`, `test_check_alerts_fired`, `test_alerts_have_fields`) — heavy remediation via `/api/alerts/inject`. KV-Store persistence across restart not specifically tested but injection→fire→assert path is sound. | **COVERED** | audit §1.13: all SOUND, prior REMEDIATED |
| 40 Dynamic thresholds (runtime warning/critical per metric) | Complete | `test_set_alert_threshold` + `test_trigger_alert_condition` SOUND cover threshold writing and triggering at runtime. | **COVERED** | audit §1.13: SOUND |
| 41 Prometheus export (Micrometer integration, scrape endpoint) | Battle-tested | `test_prometheus_endpoint_responds`, `test_valid_prometheus_format`, `test_no_empty_metric_values` all SOUND. Format validation strict (numeric values enforced). | **COVERED** | audit §1.13: all SOUND |
| 42 Unified invocation observability (sampling tracing, depth-to-SLF4J bridge, adaptive sampling, CLI/REST) | Complete | All 5 functions in `test-invocation-traces.sh` SOUND via `/api/traces/inject` test endpoint (`test_traces_endpoint`, `test_traces_contain_request_id`, `test_traces_contain_duration`, `test_traces_contain_depth`, plus generator). Adaptive sampling and SLF4J bridge mechanics not directly asserted, but the unified-observability surface is covered. | **COVERED** | audit §1.13: all SOUND, prior REMEDIATED |
| 43 Cluster event aggregator (1000-event ring buffer, 11 event types, REST/WebSocket/CLI) | Complete | `test-events-cluster-ordering.sh`: `test_inject_events_round_robin` + `test_all_nodes_agree_on_order` SOUND. Ring-buffer eviction (>1000) and all 11 event types not individually exercised — only injected synthetic events. WebSocket feed and CLI views not tested. | **PARTIAL** | audit §1.13: ordering tests SOUND, but capacity/type-coverage incomplete |

### Section summary
- 9 features classified
- **4 COVERED / 4 PARTIAL / 1 NONE**
- Notable RC1 gaps: feature 38 historical metrics (time-range queries) has **no** test. Feature 36 invocation P50/P95/P99 percentiles unverified despite catalog claim. Feature 35 120-min aggregation window unverified. Feature 43 ring-buffer capacity and WebSocket feed untested. Otherwise the section is the strongest of the four — heavy remediation via `/api/alerts/inject` + `/api/traces/inject` produced 4 fully COVERED features (39, 40, 41, 42).

---

## Aggregate totals across 4 sections

- **28 features classified**
- **5 COVERED / 16 PARTIAL / 7 NONE**
- All COVERED features (5/28 = 18%) live in **Observability & Metrics** (alerts, dynamic thresholds, prometheus, traces) plus pub-sub publish path. The other three sections produced **zero COVERED** features.
- **Highest-priority RC1 gaps** (Complete-in-catalog but PARTIAL/NONE):
  1. **Pub-sub feature 24 message delivery** — RC1-blocker `test_subscriber_receives_events` (double warn-then-pass)
  2. **Scheduled-invocation features 27/31/104** — RC1-blocker `test_task_last_execution_advances` + HIGH green-stickers on pause/resume
  3. **Storage feature 34 Configuration service** — 2 RC1-blockers in `07-cluster-mgmt` (TAUTOLOGY + missing assertion)
  4. **Storage feature 207 AHSE** — HIGH green-stickers in 14-storage; missing "artifacts" instance silently passes
  5. **Storage features 105/106/107/206** — HLC, DHT versioned writes, TimeoutsConfig, KV-Store backup have **zero** integration tests despite "Complete" status
  6. **Observability feature 38** — historical metrics time-range queries untested
