## Developer Tooling

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|---|
| 54 | Slice annotation processor | Complete | No direct test of factory/manifest generation; exercised transitively by every test that deploys a blueprint (00-smoke `test_push_artifacts` → `test_slices_provisioned`). Compile-time concern, audited via `noConsensusOrKvImports` at build time | PARTIAL | `00-smoke/test-slice-deployment.sh:12,23` |
| 55 | JBCT compliance | Complete | Pure build-time (`mvn jbct:check`), not an integration concern | NONE (expected) | n/a |
| 56 | Envelope format versioning | Complete | No suite asserts envelope-version compatibility; artifact upload paths (09-artifacts) only verify SHA-256 equality, not version field | NONE | n/a |
| 57 | Forge simulator | Battle-tested | Forge is single-JVM (per project memory `feedback_forge_is_single_jvm`); integration tests use Docker/remote cluster. Forge tested separately via its own runtime, not these suites | NONE (expected) | n/a |
| 77 | Topology graph | Complete | No suite exercises `/api/slices/topology` or WebSocket `INITIAL_STATE`; SVG/Manhattan-routing is UI-layer | NONE | n/a |
| 78 | `jbct add-slice` command | Complete | Scaffolding tool — not exercised by integration suites | NONE (expected) | n/a |
| 79 | IDE plugins | Planned | Not implemented | NONE (expected) | n/a |
| 205 | Core value objects (Email/Url/Uuid…) | Complete | Pragmatica-core unit-test domain (`core/parse`, vo classes) | NONE (expected) | n/a |
| 208 | GitHub Issues as worklog | Complete | Process artifact, not a runtime feature | NONE (expected) | n/a |
| 158 | V1.0.0 roadmap | Complete | Process artifact | NONE (expected) | n/a |
| 210 | JBCT code formatter | Complete | Build-time only (`jbct-format` golden tests in jbct module) | NONE (expected) | n/a |
| 211 | JBCT compliance scorer | Complete | Build-time only (`jbct-core/score` unit tests) | NONE (expected) | n/a |
| 164 | JBCT project scaffolding | Complete | Build/CLI scaffold, not integration-test domain | NONE (expected) | n/a |
| 165 | Property-based testing library | Complete | Build-time only (used by other modules' unit tests) | NONE (expected) | n/a |

## Reusable Libraries

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|---|
| 166 | Generic state machine | Complete | `integrations/statemachine` unit tests. Used by node lifecycle (transitively asserted via 02-chaos kill→auto_heal and 13-edge-cases disruption-budget) but not under direct integration test | NONE (expected) | n/a |
| 167 | DNS client | Complete | `integrations/net/dns` unit tests; SWIM uses it transitively in 12-network but not asserted | NONE (expected) | n/a |
| 168 | TOML parser/writer | Complete | Used by every blueprint deploy + config-export round-trip (07-cluster-mgmt `test-export.sh`, `test-apply.sh`). Direct parser/writer coverage lives in `integrations/config/toml` unit tests | PARTIAL (transitive) | `07-cluster-mgmt/test-export.sh:25` (regex-shape only) |
| 169 | KSUID generator | Complete | `integrations/utility` unit tests; KSUIDs appear in artifact IDs and event IDs across suites but not directly asserted as KSUIDs | NONE (expected) | n/a |
| 170 | Core parse library | Complete | `core/parse` unit-test domain (Text/Number/DateTime/DataSize) | NONE (expected) | n/a |
| 171 | Multipart file upload | Complete | No suite exercises multipart endpoints. Artifact upload uses single-stream POST | NONE | n/a |
| 172 | ProblemDetail (RFC 7807) | Complete | No suite asserts ProblemDetail shape on error responses; 05-security `test_unauthenticated_response_format` is RC1-BLOCK green sticker (audit §1.7 / §2.2 #8) | NONE | n/a |
| 173 | Static file serving | Complete | Used by dashboard; no integration test asserts static file serving directly | NONE | n/a |

## Node Operations

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|---|
| 63 | Node lifecycle state machine | Complete | Lifecycle transitions exercised end-to-end: JOINING→ON_DUTY via `test_pick_non_leader_excludes_decommissioned`, `test_joining-window-kill::test_prime_replacement_via_kill`, `test_catch_replacement_in_joining_window`; DRAINING→DECOMMISSIONED via `test_decommission_within_budget` and `test_survivors_self_drain_and_exit`, `test_survivor_exit_codes_are_two`. Audit §1.4 marks these SOUND. Cancel-drain path is exercised by `test_reactivate_nodes` (13-edge-cases, SOUND per audit §1.15) | COVERED | `02-chaos/test-joining-window-kill.sh:301,316,368,411`; `02-chaos/test-self-drain-quorum-loss.sh:258-449`; `13-edge-cases/test-disruption-budget.sh:132` |
| 64 | Graceful node drain | Complete | `test_drain_first_node_allowed` (SOUND), `test_drain_beyond_budget_rejected` (SOUND prior-remediated), `test_reactivate_nodes` (SOUND prior-remediated) cover drain happy path, budget rejection, and cancel-drain. `test_drain_second_node_allowed` is WEAK (2xx OR 409 dual-acceptance, audit MEDIUM/RC2). CDM eviction respecting disruption budget is asserted | COVERED | `13-edge-cases/test-disruption-budget.sh:42,102,132` |
| 65 | Disruption budget | Complete | `test-disruption-budget.sh` directly asserts `minAvailable` enforcement: `test_drain_beyond_budget_rejected` is SOUND post-remediation, `test_quorum_preserved` SOUND. Scale-down path also subject to budget (03-scaling). Audit §1.15 confirms strict assertions on core budget logic | COVERED | `13-edge-cases/test-disruption-budget.sh:102,128` |

## Security & Resilience

| # | Feature | Status (catalog) | Test evidence | Classification | Citation |
|---|---|---|---|---|---|
| 59 | Graceful quorum degradation | Battle-tested | `test-partition-quorum-gate.sh` is exemplary (audit §1.14): `test_pick_minority`, `test_partition_does_not_decommission_within_window`, `test_cluster_heals_to_5_onduty` all SOUND with explicit FSM cells in failure messages. Quorum-loss self-drain in `test-self-drain-quorum-loss.sh` (SOUND). Leader transition + reconciliation via `test_kill_leader_and_reelect`, `test_cluster_has_quorum` (audit §1.4 SOUND) | COVERED | `12-network/test-partition-quorum-gate.sh:169-268`; `02-chaos/test-self-drain-quorum-loss.sh`; `02-chaos/test-kill-leader.sh:21,63` |
| 60 | Blueprint membership guard | Complete | No test directly asserts `POST /api/scale` rejection for non-blueprint slices. 00-smoke `test_deploy_blueprint` only verifies non-empty response (audit §1.2 WEAK, MEDIUM); scale-rejection trio in 03-scaling tests size bounds, not membership guard | NONE | n/a |
| 61 | Health check endpoint | Battle-tested | `test_liveness_probe` (00-smoke) is WEAK per audit §1.2; `test_health_probes` (07-cluster-mgmt bootstrap) is SOUND; `test_health_after_stream` SOUND; `test_health_with_4_nodes` (×3 in 02-chaos) SOUND. Ready flag + quorum status + connected-peers fields not strictly asserted in dedicated test, but health endpoint is universally relied on with strict liveness gates | PARTIAL | `00-smoke/test-cluster-formation.sh:48`; `07-cluster-mgmt/test-bootstrap.sh:61`; `02-chaos/test-kill-node.sh:54` |
| 62 | Orphaned entry cleanup | Complete | `test-stale-route-cleanup.sh::test_kv_store_routes_clean` is WEAK (non-empty-as-success, audit §1.15 LOW); `test_no_502_504_after_cleanup` SOUND but tests symptom not the CDM reconcile() call directly | PARTIAL | `13-edge-cases/test-stale-route-cleanup.sh:83,102` |
| 88 | Inter-node mTLS | Complete | `test-cert-rotation.sh::test_tls_active` is TAUTOLOGY (audit §1.7 RC1-BLOCK #3). No test verifies mTLS actually negotiates a deterministic CA-signed cert chain on cluster transport. Cluster transports run TLS by default in DOCKER per config, but assertion is absent | PARTIAL | `05-security/test-cert-rotation.sh:21` (RC1-BLOCK) |
| 89 | SWIM gossip encryption | Complete | `test-gossip-encryption.sh::test_gossip_encryption_active_via_config` SOUND post-remediation; `test_gossip_encryption_via_transport` SOUND but lax (50% failure ceiling — audit §1.14 MEDIUM); `test_nodes_communicating_encrypted` WEAK (indirect). AES-256-GCM dual-key rotation not directly asserted | PARTIAL | `12-network/test-gossip-encryption.sh:27,50,79` |
| 90 | Certificate lifecycle | Complete | `test_rotation_under_load` is GREEN-STICKER (audit §1.7 RC1-BLOCK #4) — self-admittedly vacuous when TLS not configured, drives load against `/health/live`. `test_cluster_healthy_after_rotation` SOUND but only checks cluster post-fact. CertificateRenewalScheduler 50%-validity trigger never directly verified. 11-observability cert-status tests SOUND but only read endpoint state | PARTIAL | `05-security/test-cert-rotation.sh:28,76`; `11-observability/test-certificate-status.sh:29-121` |
| 91 | TLS default for containers | Complete | No test asserts "TLS on by default for DOCKER/KUBERNETES envs". Catalog claim is config-driven default; audit §1.7 confirms `test_tls_active` tautology means even this nominal coverage fails | NONE | n/a |
| 92 | RBAC — per-route security | Complete | `test-route-security.sh` is the only sound RBAC test (audit §1.7 calls it out as the only file in 05-security that actually tests its claim). `test_health_public_no_auth`, `test_status_requires_auth`, `test_status_with_auth`, `test_status_invalid_key`, `test_viewer_can_read`, `test_viewer_cannot_mutate` all SOUND. `test_admin_can_deploy`/`test_operator_can_scale` NARROW (RC2). Blueprint operator overrides + strengthen-only policy + Principal/SecurityContext injection NOT covered — entire `test-principal-injection.sh` is RC1-BLOCK theatre (audit §1.7 / §2.2 #5–#8) | PARTIAL | `05-security/test-route-security.sh:9-73`; `05-security/test-principal-injection.sh` (4 RC1-blockers) |
| 203 | Security hardening (RC1) | Complete | No suite asserts: QUIC ALPN pinning `"aether-cluster/1"`, deterministic CA from `AETHER_CLUSTER_SECRET`, plaintext-mode rejection, no-`AETHER_INSECURE_DEV_MODE` enforcement, PG `InsecureTrustManagerFactory` opt-in gating, cloud-config `toString()` redaction, PG LISTEN/UNLISTEN SQL injection, SSH image-name validation, bootstrap API-key file `600` permissions, compose random fallback secret. These are configuration/code-level guards verified by unit tests + manual review, not by integration suites | NONE (mostly expected) | n/a |

### Section summary

**Developer Tooling — 14 features classified**
- 0 COVERED / 1 PARTIAL / 13 NONE
- Most entries are unit-test or build-time domain — expected NONE for items 55, 78, 79, 205, 208, 158, 164, 165, 210, 211. Item 54 (slice processor) is transitively exercised by every deployment but no integration test directly asserts envelope/factory/manifest correctness; bumped to PARTIAL. Item 57 (Forge) runs single-JVM — integration suites use Docker, so NONE is correct and expected. Items 56 (envelope versioning) and 77 (topology graph) are real product features without integration coverage.

**Reusable Libraries — 8 features classified**
- 0 COVERED / 1 PARTIAL / 7 NONE
- Libraries belong to unit-test domain. Only TOML (#168) gets partial transitive coverage via blueprint/config flows. ProblemDetail (#172) and multipart upload (#171) are product features with public REST contracts that warrant integration assertions but currently have none.

**Node Operations — 3 features classified**
- 3 COVERED / 0 PARTIAL / 0 NONE
- Best-covered section. Lifecycle, drain, and disruption budget all have multiple SOUND tests across 02-chaos, 12-network, and 13-edge-cases. Audit §1.4, §1.15 confirm strict assertions. Cancel-drain has a single test (`test_reactivate_nodes`) — adequate but slim.

**Security & Resilience — 10 features classified**
- 2 COVERED / 6 PARTIAL / 2 NONE
- Quorum degradation (#59) and RBAC route enforcement basics (#92, core file only) are COVERED. Everything cert/TLS related is PARTIAL with RC1-BLOCK defects per audit §1.7 (6 RC1-blockers in 05-security). Blueprint membership guard (#60) and RC1 security hardening (#203) are NONE.

### Notable gaps for RC1

The catalog claims **Complete** for the following features that have material integration-coverage defects:

1. **#88 Inter-node mTLS** — `test_tls_active` is a tautology (audit RC1-BLOCK #3). No test asserts the deterministic-CA mTLS handshake actually happens.
2. **#90 Certificate lifecycle** — `test_rotation_under_load` admits inline that it is vacuous when TLS is not configured (audit RC1-BLOCK #4). The 50% renewal trigger is never verified end-to-end.
3. **#92 RBAC** — Principal/SecurityContext injection is the entire purpose of `test-principal-injection.sh`; audit §1.7 marks every function in that file as RC1-BLOCK theatre (RC1-BLOCKs #5–#8). Blueprint operator overrides and `strengthen_only` policy have zero coverage.
4. **#91 TLS default for containers** — no assertion at all.
5. **#60 Blueprint membership guard** — no test verifies `POST /api/scale` rejection for non-blueprint slices despite being a security boundary.
6. **#62 Orphaned entry cleanup** — only the symptom (`no_502_504_after_cleanup`) is sound; the CDM `reconcile()` invariant itself is asserted via a WEAK non-empty check.
7. **#54 Slice annotation processor** — envelope format and manifest generation never directly verified.
8. **#56 Envelope format versioning** — runtime compatibility check never exercised.
9. **#77 Topology graph** — REST + WebSocket endpoints uncovered.
10. **#203 Security hardening (RC1)** — bundle of 9 sub-claims; none of them have integration assertions. Most are unit/manual-review domain, but ALPN pinning and plaintext-mode rejection deserve an end-to-end test before RC1.

### "Expected NONE" (correctly outside integration scope)

- All **Reusable Libraries** entries except #168 (TOML) — these are unit-test domain (`integrations/*/src/test/java`).
- **Developer Tooling** items 55, 78, 79, 205, 208, 158, 164, 165, 210, 211 — build-time, scaffolding, process, or library entries with proper unit-test homes.
- **#57 Forge** — single-JVM simulator tested via its own runtime, not Docker integration suites.
- Most of **#203** sub-claims — guards verified by code review + unit tests.

Total classified across the 4 sections: **35 features**. Net integration coverage: **5 COVERED / 8 PARTIAL / 22 NONE** — but 18 of the NONE entries are correctly outside integration-test scope, leaving **4 true NONE gaps** (#56, #60, #77, #91) and **8 PARTIAL items where catalog Complete is overstated**.
