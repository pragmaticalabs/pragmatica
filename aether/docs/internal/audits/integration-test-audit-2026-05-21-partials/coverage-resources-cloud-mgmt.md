# Coverage classification — Resource Provisioning / Cloud Integration / Management

Scope: `aether/tests/integration/suites/**`. Forge JUnit (`aether/forge`) and Testcontainers E2E (`aether/e2e-tests/`) are explicitly out of scope.

Audit cross-reference: `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` (HEAD `a52dd99d4`).

## Resource Provisioning

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---------|-----------------|---------------|----------------|----------|
| 44 | SPI resource factories | Complete | No suite exercises `ServiceLoader` discovery, factory provisioning, or qualifier-typing directly. Implicitly exercised when SQL / stream / pub-sub blueprints deploy successfully, but no strict assertion that the factory pipeline is the gate. | **NONE** | `grep -r "ServiceLoader\|ResourceFactory\|qualifier" suites/` → 0 hits |
| 45 | Database resources (JDBC / R2DBC / jOOQ / postgres-async) | Complete | `08-resources/test-sql-connector.sh` deploys `test-persistence` blueprint and exercises one `@PgSql` slice via PUT/GET KV + 50-burst pool. Strict assertions on KV value match (`assert_eq` line 74) and majority-success threshold (line 101). Covers ONE driver (postgres-async) end-to-end; the other six drivers in the catalog claim are not asserted. Audit: `test_get_kv_pair` SOUND, `test_connection_pooling_rapid_requests` AMBER 50% threshold (§1.10). | **PARTIAL** | `suites/08-resources/test-sql-connector.sh:74,99-101` |
| 45a | Aether Store — `@PgSql` persistence | Complete | Same `test-sql-connector.sh` exercises the runtime path (PUT/GET round-trip). Compile-time validation, migration linter, codegen, named-parameter rewriting, query-narrowing, jbct scaffolding — none of these are exercised by integration tests (they belong to module unit tests / build pipeline). The runtime contract — `@PgSql` annotated method → row round-trips — is verified. | **PARTIAL** | `suites/08-resources/test-sql-connector.sh:58-80` |
| 45b | jOOQ XML schema export | Complete | No integration test invokes `export-jooq-xml` / `check-jooq-xml` Maven goals or asserts on emitted XML. Build-time tooling outside the integration suite scope. | **NONE** | `grep -r "jooq\|XMLDatabase\|export-jooq" suites/` → 0 hits |
| 46 | HTTP client resource | Complete | Catalog claim is **outbound** HTTP (timeouts, retries, SSL, Jackson). `08-resources/test-http-client.sh` is misnamed — it exercises the management API as a server, not outbound HTTP from a slice. Audit confirms file scope: all 6 functions assert on `${CLUSTER_ENDPOINT}/api/*` (§1.10, rows 1-6). No slice deployed that issues outbound HTTP under integration. | **NONE** | `suites/08-resources/test-http-client.sh:1-75` — title misnamed; no outbound HTTP under test |
| 47 | Interceptor framework (retry / circuit breaker / rate limit / logging / metrics) | Complete | `grep -r interceptor suites/` → 0 hits. `circuit.breaker` / `rate.limit` → 0 hits in resource context. No suite invokes a slice with an interceptor and asserts the cross-cutting behaviour (retry count, breaker open, rate-limit reject). | **NONE** | no matches anywhere in `suites/**` |
| 48 | Runtime extensions (`registerExtension()`) | Complete | No suite injects a runtime extension or asserts a factory consumes it. Internal SPI; not exercised. | **NONE** | `grep -r "registerExtension" suites/` → 0 hits |
| 209 | PgNotification subscriber | Complete | `grep -r "PgNotification\|listen.notify\|pg_notify" suites/` → 0 hits. `08-resources/test-streaming-resources.sh` is about the in-memory stream, not the PG-NOTIFY transport. | **NONE** | no matches in `suites/**` |

### Resource Provisioning summary
- 8 features classified
- **0 COVERED / 2 PARTIAL / 6 NONE**
- Notable RC1 gaps: Feature 46 (HTTP client) — the only file claiming to cover it (`test-http-client.sh`) actually tests the management API. Feature 47 (interceptor framework, multiple `Complete` sub-capabilities) — zero integration coverage. Feature 209 (PgNotification subscriber) — zero coverage despite Complete claim.

---

## Cloud Integration

Note: cloud-mode integration runs reuse the same 15 suites against cloud VMs; cloud-specific provider behaviour (compute provisioning, secrets resolution, discovery via labels, LB target sync) is exercised by `aether/tests/cloud/` (out of audit scope) and provider unit tests (out of scope). The integration suites under audit do NOT contain any cloud-provider-specific assertions.

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---------|-----------------|---------------|----------------|----------|
| 108 | EnvironmentIntegration SPI | Complete | No suite exercises the faceted SPI directly. Cloud-mode runs implicitly load a provider via env type, but no test in `suites/**` asserts that compute/secrets/LB/discovery facets resolved. | **NONE** | `grep -r "EnvironmentIntegration\|environment.integration" suites/` → 0 hits |
| 109 | SecretsProvider implementations (Env/File/Composite) | Complete | No suite reads a secret or asserts composite chain ordering. | **NONE** | `grep -r "SecretsProvider\|secrets" suites/` → 0 hits |
| 110 | DiscoveryProvider SPI | Complete | Cluster bootstrap is asserted (`07-cluster-mgmt/test-bootstrap.sh`), but no test asserts on `discoverPeers()` / `watchPeers()` / `registerSelf()` SPI behaviour. | **NONE** | `grep -r "DiscoveryProvider\|discoverPeers\|registerSelf" suites/` → 0 hits |
| 111 | Hetzner Cloud compute | Complete | No suite under `suites/**` provisions/terminates a Hetzner VM. Cloud bootstrap lives in `aether/tests/cloud/` (out of scope). Integration suites consume an already-provisioned cloud env via `ENV_TYPE=cloud` but do not exercise provider API directly. | **NONE** | `grep -ri "hetzner" suites/` → 0 hits |
| 112 | Hetzner Cloud discovery | Complete | Same — no in-suite assertion on label-based peer discovery. | **NONE** | as above |
| 113 | Hetzner Cloud load balancer | Complete | No LB target-management assertion. `lib/cluster.sh` resolves through cluster endpoint; LB target sync is implicit. | **NONE** | as above |
| 114 | Hetzner REST client | Complete | Provider unit tests only — not in integration suites. | **NONE** | as above |
| 115 | AWS REST client | Complete | as above. | **NONE** | `grep -ri "\baws\b" suites/` → 0 hits |
| 116 | AWS environment integration | Complete | as above. | **NONE** | as above |
| 117 | GCP REST client | Complete | as above. | **NONE** | `grep -ri "\bgcp\b" suites/` → 0 hits |
| 118 | GCP environment integration | Complete | as above. | **NONE** | as above |
| 119 | Azure REST client | Complete | as above. | **NONE** | `grep -ri "azure" suites/` → 0 hits |
| 120 | Azure environment integration | Complete | as above. | **NONE** | as above |
| 121 | XML mapper integration | Complete | Build-time codegen; not integration-tested. | **NONE** | no matches |
| 122 | CDM cloud VM termination on drain | Complete | `07-cluster-mgmt/test-destroy.sh` asserts containers absent post-destroy on Docker, but no in-suite test invokes drain on a cloud VM and asserts `ComputeProvider.terminate()` was called (no billing-side or instance-status check). Audit §1.9 confirms `test_no_containers_running` is docker-specific. | **NONE** | `suites/07-cluster-mgmt/test-destroy.sh:65` (docker-only assertion) |
| 123 | ComputeProvider SPI extensions (`provision`, `listInstances`) | Complete | Not exercised in integration suites. | **NONE** | as above |
| 124 | LoadBalancerProvider SPI extensions (7 default methods) | Complete | Not exercised. | **NONE** | as above |
| 125 | SecretsProvider SPI extensions (batch, watchRotation, caching) | Complete | Not exercised. | **NONE** | as above |

### Cloud Integration summary
- 18 features classified
- **0 COVERED / 0 PARTIAL / 18 NONE**
- Notable RC1 gaps: **every** cloud feature is uncovered by `suites/**`. This is structural — the `aether/tests/cloud/` directory and provider unit tests live elsewhere. For RC1, the catalog states "Complete" for 18 cloud features but the integration-suite gate is silent on all of them. If RC1 sign-off depends on integration suites only, cloud bootstrap, compute provisioning, secrets resolution, LB target sync, and CDM termination are all unverified at the suite layer.

---

## Management

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---------|-----------------|---------------|----------------|----------|
| 49 | REST management API (60+ endpoints, 13 route classes) | Battle-tested | Broad coverage: status/health (`08-resources/test-http-client.sh`, `07-cluster-mgmt`), blueprints/slices (06-deployment, 08-resources), scaling (03-scaling: 16/16 PASS per project memory), rolling updates (06-deployment — 3 RC1-blockers on `*_promote` per audit §1.8), config (07-cluster-mgmt — 2 RC1-blockers per audit §1.9), node lifecycle (02-chaos, 15-delegation), alerts (`11-observability/test-alerts.sh` SOUND via `/api/alerts/inject`), traces (`test-invocation-traces.sh` SOUND via `/api/traces/inject`), Prometheus (`test-prometheus-metrics.sh`), certificate status (`test-certificate-status.sh`), events (`test-events-cluster-ordering.sh`). Per audit headline: 11-observability is one of the "strongest suites" with material remediation. RBAC enforcement covered by `05-security/test-route-security.sh` (SOUND per §1.7). Aspects, TTM, thresholds, controller config: no dedicated tests found. | **PARTIAL** | `suites/11-observability/*`, `suites/05-security/test-route-security.sh`, `suites/07-cluster-mgmt/*`, `suites/06-deployment/*` |
| 50 | Interactive CLI (batch + REPL) | Complete | CLI dominates: ~55% of integration helpers use `aether ...` per audit §3 tooling table. Strict assertions on `aether status`, `aether nodes`, `aether scale`, `aether artifact push/pull`, `aether blueprint deploy`, `aether cluster bootstrap/destroy/apply/export`, `aether nodes drain/activate/shutdown`, `aether streams publish/info`, `aether events`. REPL mode never exercised. Batch mode is the dominant test path — implicitly battle-tested. | **COVERED** | `suites/lib/cluster.sh` wrappers + 16 suites consuming them |
| 213 | Cluster init wizard (`aether cluster init`) | Complete | `grep -r "cluster init\|aether init" suites/` → 0 hits. The wizard generates `cluster-config.toml`; integration tests consume a pre-existing config and run `aether cluster bootstrap`, never the init wizard. 67 unit tests across 6 classes (per catalog) live in jbct-init / cli modules, not in suites. | **NONE** | no matches in `suites/**` |
| 51 | WebSocket streams (`/ws/dashboard`, `/ws/status`, `/ws/events`) | Complete | `grep -rn "/ws/\|websocket" suites/` → 0 hits. No integration test opens a WebSocket. `11-observability/test-events-cluster-ordering.sh` consumes `/api/events` (REST), not `/ws/events`. | **NONE** | no matches in `suites/**` |
| 52 | Dynamic log levels (KV-driven runtime adjustment) | Complete | `grep -r "log.level\|loglevel\|/api/logging" suites/` → 1 unrelated hit in `02-chaos` (comment about "logging gap"). No test sets a log level via API/CLI and asserts the change took effect. | **NONE** | no matches |
| 53 | E2E test framework (Testcontainers, 10 test classes) | Battle-tested | OUT OF SCOPE — lives in `aether/e2e-tests/`, not `suites/**`. | **N/A** | not in scope of audit |
| 76 | Forge integration tests (EmberCluster, 16 test classes) | Battle-tested | OUT OF SCOPE — JUnit, not bash suites. | **N/A** | not in scope of audit |
| 136 | Docker integration test infrastructure | Battle-tested | This IS the audit's subject. 200+ test functions across 16 suites; 02-chaos and 03-scaling described as "battle-tested" per catalog and project memory. Audit confirms strongest suites (09-artifacts cryptographic, 10-database, 11-observability) and acknowledges 18 RC1-blockers across the rest. The infrastructure itself (compose, lib helpers) audit §1.1 finds zero RC1-blockers. | **COVERED** | `aether/tests/integration/docker-compose-{a,b}.yml`, `aether/tests/integration/run-tests.sh` |
| 146 | In-memory streaming (StreamPublisher/Subscriber, OffHeap ring buffer, REST + CLI) | Complete | `04-streaming/test-stream-publish.sh` exercises publish (single + batch of 50); `test-stream-consumer.sh` exercises consumer; `test-stream-under-load.sh` exercises load; REST `/api/streams/*` (create, publish, read, info) covered. Audit §1.10 flags `test_subscriber_receives_events` (in `08-resources/test-pub-sub.sh`) as RC1-BLOCK green-sticker — claims subscriber works but never attaches a consumer. Backpressure / eviction policy (DROP_OLDEST/REJECT_WHEN_FULL) / cursor persistence / consumer timeout / push-vs-pull notification → not asserted. Ring-buffer semantics, MemorySegment zero-copy → unit-test scope. | **PARTIAL** | `suites/04-streaming/test-stream-publish.sh`, `suites/08-resources/test-pub-sub.sh:52` (RC1-BLOCK per audit) |
| 137 | Cross-node stream routing (QUIC `StreamForwardMessage`) | Complete | `04-streaming/test-stream-replication.sh:55-106` (`test_read_from_non_governor_node`) asserts that stream metadata is reachable from a non-leader node — exercises the cross-node path. Strict `assert_ne` + `assert_contains` on metadata. Does NOT assert binary correlation or 5s timeout. Publish forwarding is implicit. | **PARTIAL** | `suites/04-streaming/test-stream-replication.sh:104-105` |
| 138 | Consumer group coordination | Complete | `08-resources/test-pub-sub.sh::test_competing_consumers_multi_instance` (audit §1.10 row 12) is GREEN-STICKER on single-instance branch and only verifies publish-without-error, not group rebalance/assignment. No `/api/consumers/*` group join/leave/status assertion. | **NONE** | `suites/08-resources/test-pub-sub.sh:64`, audit §1.10 (MEDIUM) |
| 139 | Sync replication ack (`minSyncReplicas`) | Complete | No test configures `StreamConfig.minSyncReplicas` or asserts PendingAck timeout behaviour. `test-stream-replication.sh` doesn't probe ack mechanics. | **NONE** | `grep -r "minSyncReplicas\|min.sync" suites/` → 0 hits |
| 140 | Batch replication (`ReplicationBatcher`, 100 events / 1ms window) | Complete | No test asserts batch coalescing or QUIC message-count reduction. | **NONE** | `grep -r "batch.replic\|ReplicationBatcher" suites/` → 0 hits |
| 141 | Consumer read-preference | **Partial** (catalog) | `grep -r "ReadPreference\|read.preference" suites/` → 0 hits. Catalog already self-declares Phase 2 remote-replica reads unimplemented. | **NONE** | no matches |
| 142 | Segment compression (LZ4/ZSTD) | Complete | `grep -r "compress" suites/` → 1 hit in `12-network/test-gossip-encryption.sh` (unrelated). No test sets `StreamConfig.compression` or asserts SegmentRef metadata. | **NONE** | no matches in stream suites |
| 143 | Segment encryption (AES-256-GCM) | Complete | `grep -r "encrypt" suites/` → all hits in gossip-encryption (12-network), none in stream context. No test sets `StreamConfig.encryptionKeyId`. | **NONE** | no matches in stream suites |
| 144 | Transactional cursor commits (`PgTransactionalCursorCommit`) | Complete | `grep -r "transactional.cursor\|cursor.commit\|exactly.once" suites/` → 0 hits. Exactly-once contract never asserted. | **NONE** | no matches |
| 145 | Compound retention (`RetentionMode.ALL/ANY`) | Complete | `grep -r "retention" suites/` → 0 hits in suites. No test exercises retention combinators. | **NONE** | no matches |
| 147 | Declarative cluster management (6-phase bootstrap, apply, plan, rollback) | Complete | `07-cluster-mgmt/test-bootstrap.sh` SOUND on cluster formation + node count (audit §1.9 rows 1-6). `test-apply.sh` partially sound but contains the `test_config_visible_on_all_nodes` RC1-BLOCK (audit §1.9 — calls `config_export` twice on same endpoint, never probes other nodes). `test-export.sh` contains the `test_config_identical_after_reapply` RC1-BLOCK (computes byte counts, never asserts equality). `test-destroy.sh` SOUND. `--full-check`, dual KV-Store TEMPLATE/CURRENT entries, floating IP attachment, `--resume`, `--rollback`, plan confirmation: none asserted explicitly. | **PARTIAL** | audit §1.9 (2 RC1-blockers); `suites/07-cluster-mgmt/test-apply.sh:40`, `test-export.sh:54` |
| 58 | Web dashboard | Critical (catalog) | Forge dashboard out of scope. Node management dashboard requires major work per catalog. No browser/UI integration test in `suites/**`. | **NONE** | by design — no UI tests in suite framework |

### Management summary
- 18 features classified (excluding 53 and 76 which are out of scope)
- **2 COVERED / 4 PARTIAL / 12 NONE**
- 2 `N/A` (Forge / E2E — out of scope)

---

### Aggregate summary across the three sections

| Section | Total | COVERED | PARTIAL | NONE | N/A |
|---|---|---|---|---|---|
| Resource Provisioning | 8 | 0 | 2 | 6 | 0 |
| Cloud Integration | 18 | 0 | 0 | 18 | 0 |
| Management | 20 | 2 | 4 | 12 | 2 |
| **Total** | **46** | **2** | **6** | **36** | **2** |

### Notable gaps for RC1 (Complete features that are PARTIAL or NONE)

**PARTIAL — feature claim exceeds suite-level evidence:**
1. **#45 Database resources** — only postgres-async driver exercised; six other drivers (JDBC, R2DBC, jOOQ, jOOQ-R2DBC, jOOQ-async, JPA) have zero integration assertions.
2. **#45a Aether Store `@PgSql`** — runtime PUT/GET verified; compile-time validation, migration linter, codegen, jOOQ XML export → out of integration scope (but catalog claim is broad).
3. **#49 REST management API** — most endpoints covered; aspects, TTM, thresholds, controller config, dynamic logging routes have no direct integration tests. 6 RC1-blockers in 05-security/06-deployment/07-cluster-mgmt directly degrade the "battle-tested" claim.
4. **#146 In-memory streaming** — basic publish/consume/info covered; eviction policy, cursor persistence, push notification, consumer timeout, ring-buffer semantics, REST `/consumers` group endpoints not asserted. Audit RC1-BLOCK `test_subscriber_receives_events`.
5. **#137 Cross-node stream routing** — non-governor read asserted; binary correlation + 5s timeout + publish forwarding not.
6. **#147 Declarative cluster management** — bootstrap + destroy SOUND; apply/export contain 2 RC1-BLOCK tautologies; `--resume`, `--rollback`, plan confirmation, floating IP, dual KV-Store TEMPLATE/CURRENT not asserted.

**NONE — Complete catalog claims with zero integration coverage:**
7. **#44 SPI resource factories** — ServiceLoader discovery never exercised.
8. **#46 HTTP client resource** — `test-http-client.sh` is misnamed (tests management server, not outbound client). True outbound HTTP-client behaviour (timeouts, retries, SSL, Jackson) has zero coverage.
9. **#47 Interceptor framework** — retry / circuit breaker / rate limit / logging / metrics interceptors: zero integration evidence.
10. **#48 Runtime extensions** — `registerExtension()` path uncovered.
11. **#209 PgNotification subscriber** — listen/notify, NotificationListenerFactory: zero coverage.
12. **#108–#125 Cloud Integration (18 features)** — entire section. Provider unit tests + `aether/tests/cloud/` cover them outside the audit scope, but if RC1 sign-off uses `suites/**` as the gate, every cloud capability ships unverified at that layer.
13. **#213 Cluster init wizard** — zero suite coverage (67 unit tests outside scope).
14. **#51 WebSocket streams** — `/ws/dashboard`, `/ws/status`, `/ws/events`: zero coverage.
15. **#52 Dynamic log levels** — runtime adjustment never asserted.
16. **#138 Consumer group coordination** — green-sticker on multi-instance branch; group join/leave/rebalance never asserted.
17. **#139 Sync replication ack** — `minSyncReplicas` config + PendingAck timeout not exercised.
18. **#140 Batch replication** — coalescing semantics not asserted.
19. **#142/#143 Segment compression/encryption** — no test sets `StreamConfig.compression` or `encryptionKeyId`.
20. **#144 Transactional cursor commits** — exactly-once contract never asserted.
21. **#145 Compound retention** — `RetentionMode.ALL/ANY` not exercised.
22. **#58 Web dashboard** — by design no UI tests in suites; catalog itself flags this as "Critical".

**Pattern:** the integration suite is strongest on cluster mechanics (bootstrap/destroy/chaos/scaling/artifacts/observability/security RBAC) and weakest on resource-factory richness (HTTP client, interceptors, PG notifications), on advanced streaming semantics (replication acks, batching, compression, encryption, cursor exactly-once, retention), and on cloud-provider integrations. The 18 cloud features uniformly read NONE — the suite layer is structurally blind to cloud-provider behaviour.
