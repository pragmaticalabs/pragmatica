## Deployment & Lifecycle

| Feature (from catalog) | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 1. Blueprint management | Battle-tested | `00-smoke/test-slice-deployment.sh::test_deploy_blueprint` (WEAK non-empty-as-success), `test_slices_provisioned` (SOUND), `test_blueprint_listed` (substring); strict-deploy assertion in 06-deployment, 13-edge-cases | COVERED | audit §1.2, §1.8, §1.15 |
| 2. Slice lifecycle (state machine) | Battle-tested | `00-smoke::test_slices_provisioned` (SOUND), `06-deployment::test_slices_active` (SOUND), `06-deployment::test_immediate_deploy` is TAUTOLOGY but `test_cluster_healthy_after_deploy` is SOUND. No test directly traverses DOWNLOADING→LOADING→STARTING→ACTIVE→UNLOADING; only ACTIVE end-state asserted | PARTIAL | audit §1.2, §1.8 (test_slices_active SOUND; immediate_deploy TAUTOLOGY RC1-blocker-adjacent) |
| 3. Unified deployment strategies (immediate/canary/blue-green/rolling, promote, rollback) | Battle-tested | `06-deployment/test-deploy-{immediate,rolling,canary,blue-green}.sh`. Three `*_promote` functions are GREEN-STICKER (RC1-BLOCK); `test_blue_green_rollback` is DEAD CODE never invoked (RC1-BLOCK); only `test_rolling_complete` and `test_blue_green_complete` and `test_immediate.*active` are SOUND | PARTIAL | audit §1.8 (RC1-blockers #9, #10, #11, #12) |
| 4. Auto-healing (bidirectional convergence, metadata-aware scheduling, PlacementHint, host-spread, zone-balance) | Battle-tested | `02-chaos/test_auto_heal` (×4 SOUND), `02-chaos::test_cluster_recovers_to_five_on_duty` (SOUND), `12-network::test_cluster_heals_to_5_onduty` (SOUND). Recovery to N nodes is covered. **No test exercises spot-first, host-spread, zone-balanced placement or PlacementHint metadata**; node-label propagation (hostname/zone/instance-type/pool) untested | PARTIAL | audit §1.4, §1.14 (auto_heal SOUND but covers count-only, not metadata-aware placement) |
| 5. Classloader isolation | Complete | No test verifies per-slice classloader isolation. `13-edge-cases::test_artifact_isolation` (SOUND) verifies artifact-level isolation only | NONE | audit: no entry |
| 6. Manifest versioning (envelope v1-v6) | Complete | No test for envelope version negotiation / backward-compat manifest evolution | NONE | audit: no entry |
| 66. Compile-time serde (@Codec) | Complete | No integration test for codec wire format. (Likely covered by unit tests outside scope) | NONE | audit: no entry |
| 102. Multi-blueprint lifecycle independence (owningBlueprint, artifact exclusivity, owner-filtered deletion, restore with ownership) | Complete | `13-edge-cases/test-concurrent-deploys.sh::test_concurrent_deploy` (SOUND), `test_artifact_isolation` (SOUND), but `test_both_blueprints_visible` is GREEN-STICKER (HIGH). **No test exercises blueprint deletion with cross-blueprint artifacts, exclusivity rejection, or restore-with-ownership** | PARTIAL | audit §1.15 (concurrent_deploy SOUND; both_blueprints_visible HIGH-RC2) |
| 126. Blueprint Artifacts (JAR with resources.toml and schema/) | Complete | `09-artifacts/test-artifact-push-resolve.sh` (all SOUND, SHA-256 strict); `00-smoke::test_push_artifacts` (SOUND) | COVERED | audit §1.11 |
| 127. Config Separation (app vs infra, hierarchical merge) | Complete | `07-cluster-mgmt/test-apply.sh::test_apply_config_override` (NARROW), `test_config_converges` (NARROW); `test_config_visible_on_all_nodes` TAUTOLOGY (RC1-BLOCK) — never probes other nodes | PARTIAL | audit §1.9 (RC1-blocker #13) |
| 128. Schema Migration Engine (V/R/U/B, history table, checksum, orchestrator, CDM gating, retry, REST/CLI) | Complete | `06-deployment/test-schema-migration.sh::test_trigger_migration` (SOUND), `test_schema_retry` (SOUND); `10-database/*` all SOUND (schema-baseline, schema-versioned, schema-retry); `test_schema_status` TAUTOLOGY, `test_schema_status_all` NARROW | COVERED | audit §1.8, §1.12 |
| 129. Endpoint Config (`[endpoints.*]` in aether.toml) | Complete | Implicit via config tests (07-cluster-mgmt) but no test asserts endpoint-section parsing/use | NONE | audit: no entry |
| 130. Deployment State Machine (RFC-0014: 11-state lifecycle, schema gate, dependency-gated activation, drain protocol) | Complete | `13-edge-cases/test-disruption-budget.sh::test_drain_first_node_allowed` (SOUND), `test_drain_beyond_budget_rejected` (SOUND), `test_drain_second_node_allowed` (WEAK 2xx-OR-409 dual accept), `test_quorum_preserved` (SOUND), `test_reactivate_nodes` (SOUND). Schema-gated activation: `10-database` strict. **11-state lifecycle, quorum-loss/restoration reconciliation, failure classification not directly verified** | PARTIAL | audit §1.15 (disruption-budget mostly SOUND; second_drain WEAK) |
| 131. Consensus Operation Retry (applyWithRetry 30s × 2) | Complete | No test injects consensus pipeline saturation to verify retry triggers | NONE | audit: no entry |
| 135. A/B Testing (header/cookie/percentage split, ScopedValue variant propagation) | Complete | `06-deployment/test-deploy-canary.sh` (canary traffic shifting partially tested; promote GREEN-STICKER RC1-block). **No test exercises header- or cookie-based deterministic split, nor ScopedValue propagation** | NONE | audit §1.8 (canary tests focus on lifecycle, not A/B traffic splitting) |

### Section summary

- 15 features classified
- 2 COVERED / 7 PARTIAL / 6 NONE
- **Notable gaps for RC1 (Complete/Battle-tested with PARTIAL or NONE):**
  - Slice lifecycle state machine — only end-state ACTIVE asserted, no full state traversal
  - Unified deployment strategies — 4 RC1-blockers (3 promote tests + dead-code rollback)
  - Auto-healing — metadata-aware placement (PlacementHint, spot-first, host-spread, zone-balance, node labels) **completely untested**
  - Classloader isolation, Manifest versioning, Compile-time serde — no integration coverage (acceptable if unit-tested)
  - Multi-blueprint lifecycle — owner-filtered deletion, exclusivity rejection, restore ownership untested
  - Config Separation — visible-on-all-nodes test is RC1-blocker TAUTOLOGY
  - Deployment State Machine — 11-state lifecycle/reconciliation/failure-classification not directly verified
  - Consensus Operation Retry, Endpoint Config — zero integration coverage
  - A/B Testing — header/cookie/ScopedValue propagation untested

## Scaling & Control

| Feature (from catalog) | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 7. CPU-based auto-scaling (DecisionTreeController, ScaleUp/ScaleDown) | Battle-tested | **No test exercises automatic CPU-threshold-driven scaling**. 03-scaling tests only manual scale endpoints. No `DecisionTreeController` / `ControlLoop` / CPU-threshold integration test | NONE | audit §1.5 (all scaling tests are manual scale, not autoscaler) |
| 8. minInstances enforcement | Complete | No test verifies minInstances as a hard floor across auto-scaler/manual/rolling | NONE | audit: no entry |
| 9. Manual scale API (`POST /api/scale`) | Complete | `03-scaling/test-02-scale-up.sh::test_scale_up_to_7` (SOUND), `test_7_nodes_healthy` (SOUND), `test_restore_to_5` (SOUND), `test-03-scale-down.sh::test_scale_down_under_load` (SOUND); rejection trio (`test_reject_scale_to_{1,2}`, `_above_max`) is PARTIAL (5xx accepted). `test_no_data_loss` is HIGH severity tautology | COVERED | audit §1.5 (core scale-up/down SOUND; rejection guards PARTIAL) |
| 10. Dynamic controller config (runtime CPU thresholds, evaluation interval) | Complete | No test exercises runtime threshold adjustment | NONE | audit: no entry |
| 11. TTM predictive scaling (ONNX, forecast, adaptive tree) | Partial | No integration test (feature disabled by default per catalog) | NONE | audit: no entry (expected; Partial feature) |
| 12. Dynamic aspects (LOG, METRICS, LOG_AND_METRICS via KV-Store) | Complete (superseded by #42) | No test exercises dynamic aspect runtime injection | NONE | audit: no entry |

### Section summary

- 6 features classified
- 1 COVERED / 0 PARTIAL / 5 NONE
- **Notable gaps for RC1 (Complete/Battle-tested with PARTIAL or NONE):**
  - **CPU-based auto-scaling (Battle-tested)** — NONE: catalog claim is contradicted; 03-scaling exercises manual scale-out only, never the autoscaler controller
  - **minInstances enforcement (Complete)** — NONE: hard-floor enforcement untested across all 3 paths
  - **Dynamic controller config (Complete)** — NONE: runtime threshold update untested
  - **Dynamic aspects (Complete)** — NONE: KV-Store-driven instrumentation untested at integration level
  - `test_no_data_loss` (03-scaling) is HIGH-severity tautology (name vs check mismatch)

## Cluster & Consensus

| Feature (from catalog) | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 13. Rabia consensus (leaderless CFT, formal invariants, supermajority, quorum intersection, value locking) | Battle-tested | Indirect via `02-chaos/test_auto_heal`, `test_kill_leader_and_reelect` (SOUND), `test_cluster_has_quorum` (SOUND), `test_quorum_maintained` (SOUND), `test-self-drain-quorum-loss.sh::test_pick_victims_and_kill_three_simultaneously` (SOUND), `test_survivors_self_drain_and_exit` (SOUND); 12-network partition-quorum-gate (all SOUND). No formal-invariant integration test (unit-level only) | COVERED | audit §1.4, §1.14 |
| 14. Leader election (lightweight, virtually instant re-election) | Battle-tested | `00-smoke::test_leader_elected` (SOUND), `02-chaos/test-kill-leader.sh::test_kill_leader_and_reelect` (SOUND, fail-closed); `01-stability::test_no_leader_change` (WEAK name/check mismatch — checks existence not stability), `02-chaos::test_leader_unchanged` / `test_leader_still_active` WEAK | COVERED | audit §1.2, §1.4 (kill_leader_and_reelect SOUND; other "leader unchanged" name-vs-check mismatches are noise) |
| 15. Quorum state management (monotonic-sequenced notifications, graceful degradation, restoration) | Battle-tested | `02-chaos::test_cluster_has_quorum` (SOUND), `test_quorum_maintained` (SOUND); `13-edge-cases::test_quorum_preserved` (SOUND); `12-network/test-partition-quorum-gate.sh::test_partition_does_not_decommission_within_window` (SOUND), `test_cluster_heals_to_5_onduty` (SOUND). Monotonic-sequenced notifications themselves not directly asserted | COVERED | audit §1.4, §1.14, §1.15 |
| 16. Topology management (discovery, add/remove events, health, grace period) | Battle-tested | `00-smoke::test_all_nodes_visible` (SOUND), `02-chaos::test_health_with_4_nodes` (×3 SOUND), `test_auto_heal` (×4 SOUND), `test-joining-window-kill.sh::test_prime_replacement_via_kill` (SOUND), `test_catch_replacement_in_joining_window` (SOUND-widened); grace period covered via joining-window tests | COVERED | audit §1.2, §1.4 |
| 17. Distributed KV-Store (consensus-replicated, typed keys) | Battle-tested | `08-resources/test-sql-connector.sh::test_put_kv_pair` (AMBER 3xx-as-success), `test_get_kv_pair` (SOUND); replication implicit via every consensus test. No test enumerates the 12 typed key families or asserts cross-node consistency of a typed write | PARTIAL | audit §1.10 |
| 175. ClusterGeneration choreography (epoch-fenced snapshot, Spokesman, ClusterQuiescence, GET/POST endpoints, CLI) | Complete | **Used pervasively as test infrastructure** (`lib/generation.sh`, `await_generation_quiesced`), but no test directly validates the feature: monotonic epoch advance, snapshot caching, `await-quiesced` blocking semantics, CLI behavior. Indirectly exercised by every test that calls it — but absent helper-as-SUT pattern means the feature itself is unverified at integration level | PARTIAL | audit: no entry (helper not tested as SUT) |

### Section summary

- 6 features classified
- 4 COVERED / 2 PARTIAL / 0 NONE
- **Notable gaps for RC1 (Complete/Battle-tested with PARTIAL or NONE):**
  - **Distributed KV-Store (Battle-tested)** — PARTIAL: only opportunistic kv put/get smoke test; no per-typed-key family coverage, no cross-node consistency assertion of a single typed write
  - **ClusterGeneration choreography (Complete)** — PARTIAL: feature is heavily used as test scaffolding (`lib/generation.sh`) but never tested as the system-under-test (monotonic epoch advance, blocking semantics, CLI exit codes). This is a verification gap — every test depends on it working, yet no test would detect if it broke (it would manifest as flaky cascades elsewhere)

## Networking & Routing

| Feature (from catalog) | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|
| 18. HTTP route registration (dynamic per-slice via KV-Store) | Complete | `00-smoke::test_app_endpoint_reachable` (PARTIAL), `test_app_request_succeeds` (SOUND); `13-edge-cases/test-stale-route-cleanup.sh::test_slices_deployed` + `test_app_routes_reachable` (SOUND), `test_no_502_504_after_cleanup` (SOUND), `test_kv_store_routes_clean` (WEAK) | COVERED | audit §1.2, §1.15 |
| 19. Endpoint registry (artifact-to-node mapping) | Complete | Indirectly via stale-route-cleanup (route table reflects endpoint changes), but no direct test of the artifact-to-node mapping registry | PARTIAL | audit §1.15 (stale-route cleanup tests route table, not registry per se) |
| 20. Service-to-service invocation (SliceInvoker: HTTP routing, LB, timeout/retry, metrics) | Battle-tested | **No test exercises SliceInvoker / service-to-service calls**. `grep -r SliceInvoker` returns zero matches in suites | NONE | audit: no entry |
| 21. Version routing (traffic split old/new during deployment) | Battle-tested | `06-deployment/test-deploy-canary.sh::test_canary_start` (NARROW), `test_canary_complete` (NARROW); `test-deploy-rolling.sh::test_rolling_start` (NARROW quiesce-warns). **No test asserts the configured ratio is reflected in traffic distribution** | PARTIAL | audit §1.8 (NARROW + promote RC1-blockers) |
| 67. Passive load balancer (NodeRole.PASSIVE, route table, binary protocol forwarding, mgmt forwarding) | Complete | **No test exercises NodeRole.PASSIVE / passive LB**. `grep -r passive\|PASSIVE\|NodeRole` returns zero in suites | NONE | audit: no entry |
| 68. NodeRole cluster membership (ACTIVE/PASSIVE, quorum/leader exclusion, deliverToPassive filtering) | Complete | No test exercises PASSIVE role membership behavior | NONE | audit: no entry |
| 69. HttpForwarder (round-robin, retry/backoff, departure failover) | Complete | `13-edge-cases/test-stale-route-cleanup.sh::test_kill_node_hosting_routes` (WEAK quiesce-warn pass), `test_no_502_504_after_cleanup` (SOUND — measures failover effectiveness) | PARTIAL | audit §1.15 (kill_node_hosting_routes WEAK; no_502_504 SOUND but doesn't isolate forwarder behavior) |
| 161. Compile-time route registry (`ManagementRoute` enum, RouteMatcher/Assembler/Target, 116 routes) | Complete | Indirectly exercised by every CLI/mgmt call. No test asserts route-enum coverage / compile-time route safety at integration level (compile-time inherently caught at build) | NONE | audit: no entry |
| 162. Task-group-aware forwarding (TaskGroupAssignmentRegistry, consensus, encrypted credentials) | Complete | `15-delegation/test-01-task-assignments.sh::test_tasks_api_returns_data` (SOUND), `test_all_groups_assigned` (SOUND), `test_all_groups_active` (SOUND), `test_assignments_point_to_valid_nodes` (SOUND-narrow), `test_deployment_group_functional` (SOUND), `test_tasks_distributed` GREEN-STICKER (HIGH); `test-02-reassignment.sh::test_operator_reassign` (SOUND), `test_reassignment_status_active` (SOUND), `test_node_failure_reassignment` (SOUND). **Encrypted-credentials-in-KV path not tested** | COVERED | audit §1.17 |
| 163. Cloud testing infrastructure (Hetzner, CLOUD_MODE, SSH bastion, timeout multiplier, aether-cloud.toml) | Complete | `lib/common.sh`, `lib/cluster.sh`, `lib/topology.sh` reference `CLOUD_MODE` infrastructure but **no integration test exercises the cloud path itself**. Feature is the testing infra, not testable in unit form | PARTIAL | audit: no entry (feature is test scaffolding) |
| 204. SharedScheduler consolidation (8 platform threads, 10 schedulers migrated) | Complete | No integration test verifies thread-pool consolidation (likely unit/profile-level) | NONE | audit: no entry |
| 154. Server UDP support (DatagramChannel on shared workerGroup) | Complete | No test verifies UDP bind / shared worker group | NONE | audit: no entry |
| 155. Shared EventLoopGroups (HTTP shares Server's boss/worker) | Complete | No test verifies thread-pool sharing | NONE | audit: no entry |
| 159. QUIC transport (QuicClusterNetwork, server, client, TLS provider, metrics) | Complete | `12-network/test-quic-connectivity.sh::test_all_nodes_connected` (SOUND-narrow, one node sampled), `test_kill_node_and_detect_drop` (SOUND), `test_connections_recovered` (SOUND); transport-metrics covered in 11-observability (all SOUND) | COVERED | audit §1.13, §1.14 |
| 160. HTTP/3 server (Http3Server, Http3ServerAdapter) | Complete | No test exercises HTTP/3 client→server path. QUIC transport tests are cluster-internal only | NONE | audit: no entry |

### Section summary

- 15 features classified
- 4 COVERED / 4 PARTIAL / 7 NONE
- **Notable gaps for RC1 (Complete/Battle-tested with PARTIAL or NONE):**
  - **Service-to-service invocation / SliceInvoker (Battle-tested)** — NONE: zero integration coverage of the core call path between slices
  - **Version routing (Battle-tested)** — PARTIAL: canary/rolling traffic-split ratio never verified; promote functions are RC1-blockers
  - **Endpoint registry (Complete)** — PARTIAL: indirectly exercised, not directly asserted
  - **Passive load balancer + NodeRole PASSIVE membership (#67, #68 Complete)** — NONE: entire passive-LB feature has zero integration tests
  - **HttpForwarder (Complete)** — PARTIAL: failover-by-effect only, no direct round-robin / retry assertion
  - **HTTP/3 server (#160)** — NONE: no client→server HTTP/3 test (QUIC tests are cluster-transport only)
  - **Compile-time route registry, Task-group encrypted creds, SharedScheduler, UDP server, Shared EventLoopGroups** — NONE: infrastructure features without integration coverage (most acceptable if unit-tested at component level)

---

## Cross-section roll-up

| Section | COVERED | PARTIAL | NONE | Total |
|---|---|---|---|---|
| Deployment & Lifecycle | 2 | 7 | 6 | 15 |
| Scaling & Control | 1 | 0 | 5 | 6 |
| Cluster & Consensus | 4 | 2 | 0 | 6 |
| Networking & Routing | 4 | 4 | 7 | 15 |
| **Total** | **11** | **13** | **18** | **42** |

**Top-tier RC1 verification gaps** (Battle-tested / Complete with NONE classification — catalog claims production-ready, but the integration suite would not detect a regression):

1. **CPU-based auto-scaling (#7, Battle-tested)** — autoscaler controller untested; only manual scale API exercised
2. **Service-to-service invocation / SliceInvoker (#20, Battle-tested)** — zero coverage of cross-slice HTTP routing
3. **Passive load balancer + NodeRole PASSIVE (#67, #68, Complete)** — passive role untested at integration level
4. **A/B Testing (#135, Complete)** — header/cookie/ScopedValue propagation untested
5. **minInstances enforcement (#8), Dynamic controller config (#10), Dynamic aspects (#12)** — Complete features with zero integration coverage
6. **Manifest versioning (#6), Classloader isolation (#5), Consensus retry (#131), Endpoint Config (#129), SharedScheduler (#204), UDP server (#154), Shared EventLoopGroups (#155), Compile-time route registry (#161), HTTP/3 server (#160)** — Complete features lacking integration tests; acceptable iff unit-tested at component level

**RC1 PARTIAL features with audit-flagged RC1-BLOCKers** (test exists, fails to assert the claim):

- Unified deployment strategies (#3) — 4 RC1-blockers in 06-deployment (promote ×3 + dead rollback)
- Config Separation (#127) — `test_config_visible_on_all_nodes` RC1-blocker (probes same endpoint twice)
- Auto-healing (#4) — metadata-aware placement untested; count-only convergence covered
- ClusterGeneration choreography (#175) — used as helper, never as SUT
