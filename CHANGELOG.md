# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.0-rc1] - Unreleased

### Changed
- **Membership state-tracker consolidation, audit Steps 1–5** — partial implementation of [`aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md`](aether/docs/internal/audits/membership-state-tracker-audit-2026-05-07.md). Goal: collapse the 4 parallel state trackers + 6 debounce sidecars into a single canonical `MembershipView` + projections. Steps 6 (phase-aware SWIM cold-boot suppression), 7 (cross-node quorum aggregation, HIGH risk — needs `PeerObservationStore` reducer), and 8 (cleanup) deferred:
  - **Step 1:** new `MembershipDelta` record + `TopologyObserver.publishMembershipDeltas()` diff publisher hooked into `evaluateQuorumState`. Emits one `TopologyChangeNotification.NodeAdded`/`NodeRemoved` per snapshot edge
  - **Step 2:** `QuicClusterNetwork.processViewChange` no longer emits `TopologyChangeNotification.NodeAdded`/`NodeRemoved` upward — `TopologyObserver` is the canonical emitter. SHUTDOWN's `NodeDown` retained for self-shutdown semantics. DHT routing gap closed: `DHTTopologyListener.onNodeDown` mirrors `onNodeRemoved` (was missing — pre-existing routing gap)
  - **Step 3:** AetherNode's SWIM-FAULTY-to-disconnect lambda removed. `SwimHealthContext.routeFaulty` no longer calls `routeDisconnect`. QUIC eviction now flows post-consensus via `TopologyChangeNotification.NodeRemoved` → `clusterNetwork.disconnect`. Eviction trades sub-ms local-SWIM latency for a Rabia round-trip + projection (~200-500ms cloud RTT), but eliminates the N+1 fan-out cascade across every survivor's local SWIM listener
  - **Step 4:** `ClusterEventAggregator` no longer subscribes to `SwimObservation`. `NODE_FAILED`/`NODE_LEFT` events emit only via `onNodeLifecyclePut` (KV-Store source-of-truth) eliminating the duplicate witness emit
  - **Step 5:** `TopologyObserver.healthyActiveNodeCount`/`readyNodeCount` are snapshot-only — legacy `nodeStatesById`-derived fallback removed. Cold-boot windows where the snapshot is empty conservatively report `0` instead of leaking a transport-derived count that disagrees with the leader's view. `legacyHealthyActivePeerCount` and `activeTopologySize` private methods deleted
  - **Step 8 partial:** `SwimHealthContext.routeDisconnect` deleted (no callers post-Step 3); unused `NetworkServiceMessage` import dropped

### Fixed
- **CTM provisioning circuit breaker** — `ClusterTopologyManagerRecord` now bounds runaway provisioning when replacement VMs consistently fail to reach `ON_DUTY`. Counter increments on each slot deadline expiry and each provider API rejection; exponential backoff (30s → 60s → 120s → 240s → 300s cap) defers the next attempt; after `MAX_CONSECUTIVE_PROVISIONING_FAILURES = 3` consecutive failures the breaker trips and `handleDeficit` halts dispatch entirely until a successful node arrival (`onNodeReady`), `ClusterPhase.NORMAL` transition, leader (re)activation, or operator `setDesiredSize`. Closes the orphan-leak factory observed on cloud where a single 70 s slot timeout (cloud-init too slow for first-boot Docker pull + container start) cascaded into 7+ orphan VMs in 7 minutes
- **08-resources `test-sql-connector` slice routing flake on cloud** — extracted the inline retarget pattern into `retarget_app_endpoint_to_active_slice` lib helper that finds an ACTIVE slice owner, retargets `APP_ENDPOINT` to its public IP, and (optionally) probes a path until it returns < 500 to catch the brief window where ACTIVE was reported but the local route table is still settling
- **`cluster_node_count` snapshot lag** — added `cluster_node_count_quiesced` test helper that calls `await_generation_quiesced` against the current epoch before reading. For single-shot count assertions immediately after a state-changing action (`scale_cluster`, `kill_node`), avoids the `max(members, desired)` heuristic biasing toward the stale member count when the snapshot hasn't yet reflected the just-committed config write
- **CTM-provisioned VMs labeled `aether-cluster=unknown`** — replacement Hetzner VMs created by `ClusterTopologyManager` were tagged `aether-cluster=unknown` instead of the actual cluster name, breaking discovery-by-label and `cloud-reaper.sh --cluster X` scoping. Root cause: bootstrap's composed `aether.toml` never emitted `[cloud.discovery] cluster_name`, so `HetznerEnvironmentConfig.clusterName()` was `Option.empty()` on every running node. `HetznerComputeProvider.buildLabels()` then fell back to `"unknown"`. Two complementary fixes:
  - `BootstrapOverlayGenerator` now emits `[cloud.discovery] cluster_name = "<name>"` for cloud sources, populating the field that all four cloud factories' `applyDiscovery` reads
  - `ClusterTopologyManagerRecord.buildProvisionTags()` passes `aether-cluster=<name>` as an explicit override label (sourced from `ClusterConfigValue.clusterName`), so the provider's default never wins. Defense in depth: the new VM gets the right tag even if a node's TOML somehow lacks `[cloud.discovery]`
- **Bootstrap cleanup credential resolution** — `BootstrapCleanup.destroyVm` previously routed through `ProviderResolver.resolveCloudCompute(String)`, an overload that constructed a `CloudConfig` with `credentials = Map.of()`. Hetzner factory then read `getOrDefault("api_token", "")` and silently produced an empty Bearer token, causing every termination call on a partial-failure cleanup to return HTTP 401 — leaking ~5 orphan VMs per failed bootstrap and exhausting Hetzner cx33-fsn1 capacity within a few iterations. Three downstream fixes applied as one structural change so the discrepancy cannot recur:
  - `BootstrapCleanup` now uses the new `ProviderResolver.resolveCloudComputeForCleanup(String)` which sources credentials from the operator's environment (`HCLOUD_TOKEN`, `AWS_ACCESS_KEY_ID/SECRET/REGION`, `GCP_*`, `AZURE_*`) via the new `CloudCredentials.fromEnvironment` resolver. Mirrors the existing `defaultHetznerClient` SSH-key-cleanup path and the `tools/cloud-reaper.sh` contract
  - The broken `ProviderResolver.resolveCloudCompute(String)` overload (and its private `minimalCloudConfig`) is **deleted** so the credential-less path can no longer be selected
  - All four cloud factories (Hetzner / AWS / GCP / Azure) now fail-fast with `EnvironmentError.CredentialsMissing` listing the missing env vars, replacing the silent `getOrDefault("...", "")` pattern. Previously a missing token reached the SDK as an empty string and the API rejected with a generic 401/422 deep in the stack
- **Cleanup failure exit code** — bootstrap and destroy commands now return `ExitCode.CLEANUP_FAILED` (4) when post-failure cloud resource cleanup leaves orphan resources, distinct from generic `ERROR` (1). Previously the orchestrator discarded the cleanup `Result` via `_ = cleanupHook().apply(state)` and only printed a `WARN` line per orphan. CI / orphan-detection pipelines can now react specifically to leaks. Bootstrap wraps the original cause in `BootstrapError.BootstrapFailedWithOrphans`; destroy escalates via `printSummary`

### Added
- **ClusterGeneration distributed membership choreography** — epoch-fenced cluster-wide snapshots (`ClusterGenerationSnapshot`, `Epoch`, `Spokesman`, `ClusterQuiescence`). `GET /api/cluster/generation` exposes the current snapshot (always-safe, never 503); `POST /api/cluster/await-quiesced?epoch=T:C&timeout=30s` blocks until the queried node observes that epoch at quiescence. CLI: `aether cluster generation`, `aether cluster await-quiesced`. See [`aether/docs/specs/cluster-generation-spec.md`](aether/docs/specs/cluster-generation-spec.md)
- **`SliceState.ROUTING`** — new transitional state inserted between `ACTIVATING` and `ACTIVE`. `NodeDeploymentManager.performActivation` now publishes HTTP routes via consensus before flipping to `ACTIVE`; serial consensus ordering guarantees any node observing `ACTIVE` has already applied the routes entry. Slices without routes bypass ROUTING. Eliminates the race where a slice reported ACTIVE before its routes propagated cluster-wide, causing 404s on the first request
- **`ClusterFormationConfig`** — three configurable timeouts (`stabilizationWindow`, `postEstablishGrace`, `quorumLossHysteresis`; each 5s default) threaded through `AetherNodeConfig` builder and `NodeConfig` into `QuicClusterNetwork`. Replaces the two hardcoded `*_MS` constants
- **Compile-time management route registry** — 116-route `ManagementRoute` enum with `RouteMatcher` (O(1) hash lookup by method+prefix+paramCount), `RouteAssembler` (CLI path construction), `RouteTarget` sealed interface (LOCAL, ANY, TaskGroupTarget). All path string literals eliminated from 21 server-side route files and 37 CLI command files. Adding/renaming a route is a compiler error at every consumer
- **Task-group-aware management forwarding** — `TaskGroupAssignmentRegistry` seeds from KV-Store and maintains live `TaskGroup→NodeId` mapping via consensus notifications. LB `HttpForwarder.forwardManagement()` routes requests to the correct task-group owner. Enum-keyed dispatch in `ManagementRouter` bypasses legacy `RequestRouter` tree disambiguation
- **Node config composition** — 4-layer CLI-side config assembly: global default → per-source-type default → operator override (via `[source.X.node_config]` or template inheritance) → CLI bootstrap-time overlay. Node-side unchanged: still reads a single `aether.toml`. New `DefaultNodeConfig`, `NodeConfigComposer`, `BootstrapOverlayGenerator`. SSH path uses composed TOML via SCP. `UserDataTemplate` accepts composed document for cloud-init `write_files`. Follow-ups: #154 (Docker bootstrap), #155 (cloud provisioning wiring), #156 (Forge). Spec: `aether/docs/specs/node-config-composition-spec.md`
- **Server-side management forwarding** — every node's `ManagementServer` now owns an `HttpForwarder` and pre-routes incoming requests by `RouteTarget`: `LocalNode` → local; `TaskGroupTarget(g)` → forward when `ownerFor(g) != self`; `AnyCoreNode` → forward when local node is not in `coreNodes()`. Removes the need for clients to know which node owns a task group (REQ-6.4.1)
- **Cloud credential distribution** — `CloudCredentialsKey/Value` KV types with AES-GCM encryption. `BootstrapOrchestrator.storeCloudCredentials()` stores Hetzner token in cluster KV-Store during bootstrap, encrypted with `cluster_secret`. `HetznerComputeProvider` reads from KV-Store for auto-heal — token never on disk, in cloud-init, or in container env vars
- **Hetzner VM labels** — `CreateServerRequest` now includes `labels` field. `HetznerComputeProvider` and `BootstrapOrchestrator` apply `aether-cluster` and `aether-role` labels for lifecycle management and teardown
- **Private network support in bootstrap** — `BootstrapOrchestrator.buildCreateServerJson()` passes `networks` field. `DeploymentSpec.networkId()` configurable via TOML `deployment.network_id` with `${env:...}` interpolation
- **Cloud integration testing infrastructure** — `deploy-cloud.sh` (10-phase provisioning), `run-cloud-tests.sh`, `teardown-cloud.sh` for Hetzner Cloud. `CLOUD_MODE` flag in test library: SSH-via-bastion for `kill_node`/`start_node`, timeout multiplier, LB-routed API calls
- **Schema management helpers** — `schema_migrate`, `schema_retry`, `schema_history`, `schema_baseline`, `schema_undo` functions in integration test library
- **Blueprint publish endpoint** — `publish_blueprint` test helper for registering blueprints without deploying (required for v1→v2 strategy upgrade tests)
- **ManageableNode interface** — Extracted management API surface from AetherNode (~35 methods). `AetherNode extends ManageableNode`. All route sources + ManagementServer use `Supplier<ManageableNode>`. Enables passive nodes to serve management API
- **Passive LB ManagementServer** — `PassiveLBNode implements ManageableNode` with real KV-Store/topology/apply, no-ops for slice hosting. ManagementServer serves `/api/*` locally from LB's own synced state. `NoOpComponents` sealed interface with 13 stub implementations
- **PassiveNode.apply()** — Passive nodes can submit consensus proposals. Creates `Batch`, sends `NewBatch` to core nodes only (no traffic to other passive/worker nodes). Decision correlation resolves original promises
- **CTM auto-provisioning tags** — `buildProvisionTags()` builds 3-part PEERS from live topology using `NodeAddress.host()`. DockerComputeProvider provisions replacement containers with correct hostnames, API key, docker GID, unique names
- **DockerConfig enhancements** — `api_key`, `docker_gid` fields in `[cloud.compute]` config. Config-driven approach for production Docker deployments
- **`aether cluster bootstrap --cluster <name>` override flag** — CLI override for `[cluster].name` from the TOML; precedence is CLI > TOML > default. New `ClusterIdentity.withName(String)` and `ClusterBootstrapConfig.withClusterName(String)` copy methods. Validates against `^[a-z][a-z0-9-]{0,62}$`; invalid values exit with `ExitCode.USAGE` (64). Enables integration test harness to stamp per-suite cluster names without editing the TOML
- **`aether cluster destroy --keep-resources` flag** — debugging escape hatch that skips cloud resource termination; default behavior now terminates VMs via `BootstrapCleanup.cleanup(state)` before removing the registry entry. Previously destroy only drained nodes + removed the registry entry, leaking every provisioned VM (cost leak). Fixes the core teardown gap that made cloud testing unsafe
- **`exposeHostPorts` opt-in for `DockerComputeProvider`** — new `[cloud.compute] expose_host_ports` flag (default `false`, overlay-only). When enabled, CTM-provisioned containers publish their management port to the host via `-p {managementPortBase + nodeIndex}:8080`. Enabled in test config (`aether/docker/aether-node/aether.toml`) so integration tests on remote hosts can poll provisioned nodes directly. Production retains overlay-only behavior
- **Enriched `/api/cluster/topology`** — Returns live coreCount, connectedPeerCount, per-node details (nodeId, role, health, hostname, zone, address). Replaces stale `initialTopology()` with live `TopologyManager` data
- **`aether cluster topology` CLI** — Table output: NODE, ROLE, HEALTH, HOSTNAME, ZONE, ADDRESS columns
- **Chaos integration tests (02-chaos)** — 4 tests (19 assertions): kill non-leader, kill leader (re-election), kill multiple (quorum safety), kill under load (0% error rate through LB). Each verifies auto-heal restores cluster to target size
- **Scaling integration tests (03-scaling)** — 3 tests (16 assertions): quorum safety rejection, scale-up 5→7 (2s convergence), scale-down 7→5 under load (34s, 0% error rate). Cluster config seeding with initial-create fallback
- **Passive LB in docker-compose** — `aether-lb:local` Docker image built and deployed alongside cluster. All test traffic routes through LB. `deploy-compose.sh` builds both images
- **Initial cluster config seeding** — `POST /api/cluster/config` creates config when none exists (`.orElse()` fallback in `handleApplyConfig`)
- **Application config provisioning** — `@ResourceQualifier(type = ConfigurationSection.class)` pattern for typed config. Compile-time parser generation via `Result.all()`. Three-source merge (bundled `META-INF/config.toml` + `aether.toml` `[app.*]` + KV-Store). Runtime notification via single-threaded executor with record diff. ACTIVATE integration ensures config before routes
- **Config value object support** — Primitives, `Option<T>` variants, `List<String>`, core value objects (`TimeSpan`, `Url`, `Email`, `Uuid`, `NonBlankString`, `IsoDateTime`), and any user-defined type with `TypeName.typeName(String) → Result<T>` factory
- **Node metadata labels** — `NodeInfo.labels` (hostname, zone, instance-type, pool) propagated via Hello handshake, bootstrap from environment variables
- **PlacementHint provisioning** — `ZoneHint`, `HostGroupHint`, `AffinityHint`, `AntiAffinityHint` in `ProvisionSpec`. Cloud providers respect zone placement
- **Metadata-aware CTM scheduling** — Surplus comparator: spot-first → over-represented hosts → empty nodes → newest. Provisioning: zone-balanced placement based on current topology
- **ContentStore resource** — `@ContentStoreQualifier` annotation, `ContentStoreFactory` SPI for AHSE-backed content storage
- **Streaming retention enforcement** — Scheduled `RetentionEnforcer` removes expired segments from AHSE
- **Consumer cursor persistence** — `CursorStore` persists consumer group cursors in AHSE via named references
- **Governor failover** — `WatermarkTracker` + `GovernorFailoverHandler` for watermark-based replica catch-up from AHSE segments
- **Cross-tier stream reads** — `TieredStreamReader` with segment prefetch for optimized historical reads
- **Cloud certificate adapters** — AWS (Secrets Manager), GCP (Secret Manager), Azure (Key Vault) via `CertificateProvider` SPI with shared `CloudCertificateProvider`
- **Cloud provider placement** — AWS, GCP, Azure implement zone-aware provisioning from `ProvisionSpec.placement()`
- **Same-version deploy rejection** — Strategy deploys rejected when oldVersion == newVersion. `/api/blueprint/publish` for register-without-deploy
- **Disruption budget enforcement** — Drain endpoint checks quorum-based `minAvailable` before allowing DRAINING transition
- **Promise.allOrCancel()** — Cancels remaining promises on first failure; fixed instance `all()` from sequential to parallel
- **JBCT lint rules (4 new)** — `JBCT-PAT-03` blocking `.await()` detection (WARNING), `JBCT-RET-07` discarded `Result`/`Promise`/`Option` value (ERROR), `JBCT-STY-07` unnecessary intermediate variable before return (WARNING), `JBCT-STY-08` simple if/else with return in both branches (WARNING)
- **`@TerminalOperation` annotation** — Semantic suppression for `JBCT-PAT-03` on methods/classes where blocking is intentional (CLI, lifecycle, background threads)
- **Streaming read forwarding** — `ReadPreference.ANY_REPLICA`/`NEAREST` now routes reads to caught-up replica nodes via QUIC `ReadForward`/`ReadForwardResponse` protocol. Retry policy: primary fails → one alternate replica → error (never silent fallback to leader). `StreamReadRouter`, `RawEventDto`, `StreamReadForwardMetrics` (5 counters). Configurable split timeouts via `[streaming]` config section (`publish_forward_timeout`, `read_forward_timeout`). Defensive 28MB response cap with truncation flag. REST layer (`StreamRoutes.readEvents`) now honors the preference end-to-end
- **`ConsumerRuntimeState` async cursor loading** — eliminated blocking `.await()` in `subscribe()` by deferring cursor load to an async path; consumer starts after cursor resolves
- **jOOQ XML schema export** — `JooqXmlExporter` generates jOOQ `XMLDatabase`-compatible XML from pg-tools' static `Schema` model. No jOOQ dependency. Covers tables, columns, PK/FK/unique/check constraints, sequences, indexes, enums, domains, identity/generated columns, multi-schema. Two Maven goals: `export-jooq-xml` (generate) and `check-jooq-xml` (CI drift detection). `JooqTypeMapper` maps 25+ PostgreSQL types to jOOQ's information_schema conventions

- **Bootstrap phase extraction** — `ClusterBootstrapOrchestrator` refactored from 627-line monolith into 6 focused phase files (`BootstrapPhaseValidate`, `BootstrapPhaseProvision`, `BootstrapPhaseCollect`, `BootstrapPhaseDeploy`, `BootstrapPhaseFormation`, `BootstrapPhasePost`) plus thin orchestration skeleton
- **Pre-flight validation** — `ClusterBootstrapConfigValidator` wired into Phase 1 with warning emission. `PreflightChecker` runs cloud credential pings by default; `--full-check` flag enables SSH reachability, Docker CLI, and floating IP ownership checks in parallel per source
- **Bootstrap state persistence** — `BootstrapState` extended with `CreatedResource` tracking (VMs, firewall rules, floating IPs, containers, SSH configs), JSON serialization, file persistence to `~/.aether/clusters/<name>/bootstrap-state.json`. SHA-256 config hash. Resume from last completed phase with `--resume` flag. LIFO cleanup of all tracked resources on failure via `BootstrapCleanup`
- **Bootstrap Phase 2 enhancements** — VM tagging with `aether-cluster`/`aether-source`/`aether-role` labels. All provisioned resources tracked in state for cleanup
- **Parallel health checks** — Phase 5 polls ALL node addresses concurrently via `Promise.allOf()` instead of sequential single-node polling. Required for clusters with 50+ nodes
- **Dual KV-Store config entries** — `ClusterConfigKey.TEMPLATE` (configVersion=-1) stores original TOML with `${...}` placeholders intact for export roundtrip; `ClusterConfigKey.CURRENT` stores CLI-resolved config
- **API key file persistence** — Phase 5 saves API key to `~/.aether/clusters/<name>/api-key` with `0600` permissions
- **Floating IP attachment** — Phase 6 resolves `FloatingIpProvider` for elected LB sources and calls `attach()` for each configured floating IP
- **Forge health gate** — Phase 4 verifies forge process is reachable before proceeding to cluster formation (10s timeout with actionable error)
- **Node ID peer list fix** — `NodeConfigTemplate.buildPeersList()` uses real provisioned node IDs instead of generating sequential `clusterName-N` IDs
- **Full apply orchestrator** — `ApplyOrchestrator` with pre-flight cluster health check, terraform-style plan confirmation (`--yes` to skip), `ApplyState` persistence with `--resume`/`--rollback` support
- **Rolling restart** — `WaveExecutor` executes `RuntimeChange` via drain → destroy → provision → wait-for-ready, respecting `maxUnavailable` budget for core nodes. Workers restarted in parallel
- **Replace-before-retire** — `SourceFieldChange` provisions new nodes first, waits for cluster join, then drains and destroys old nodes
- **SSH drain** — SSH source removals drain via management API then `docker stop` via SSH. Hosts are preserved (not destroyed)
- **API key rotation** — `aether cluster rotate-key [--grace-period 5m]` generates new key, pushes to KV-Store, marks old key REVOKED with configurable grace period, updates local key file
- **API key revocation** — `aether cluster revoke-key <keyId> [--immediate]` revokes by ID with optional immediate effect
- **API key listing** — `aether cluster list-keys [--audit]` shows all keys with status; `--audit` includes full operation history
- **Multi-key auth** — `KvStoreApiKeyValidator` supports multiple concurrent ACTIVE keys with grace period for revoked keys. Enables zero-downtime rotation
- **API key audit trail** — All key operations (create, rotate, revoke, expire) logged in KV-Store as `ApiKeyAuditValue` entries
- **API key expiration sweep** — Periodic background task on leader (60s interval) marks expired keys

- **`tools/cloud-reaper.sh`** — standalone Hetzner cloud-resource reaper (kill-switch independent of bootstrap state). Lists every `aether-cluster`-labeled resource (servers, floating IPs, networks, firewalls, SSH keys) via `label_selector` API queries; default dry-run, `--destroy` flag deletes in correct order (servers → FIPs → firewalls → networks → SSH keys), `--cluster <name>` filters to a specific cluster, `--force` skips the 5s confirmation grace for CI use. Idempotent; exits non-zero on any deletion failure or remaining resources after destroy
- **`tools/provision-test-pg.sh`** — idempotent PostgreSQL test-VM provisioner for Hetzner. Creates a single labeled cx23 VM running PostgreSQL in Docker (`aether-cluster=test-pg`), uploads operator SSH key, runs a connectivity smoke test, and writes `PG_URL` to `/tmp/aether-test-pg.env` (mode 0600). `--print-only` recovers the URL when env file missing; `--destroy` nukes it. Used as the shared Forge backing store for all cloud bootstrap iterations, so cluster VMs can share state across teardowns
- **`aether cluster bootstrap --keep-on-failure` flag** — symmetric with `cluster destroy --keep-resources`. When set, a failed bootstrap skips automatic cleanup, leaving provisioned VMs and SSH keys in place for SSH-based diagnosis. Prints remediation hint with the kept-resource counts and a follow-up `aether cluster destroy` command. Critical for iterating on cloud bootstrap without burning Hetzner spend on every failed attempt
- **`aether cluster bootstrap --ssh-public-key <path>` flag** — explicit operator-public-key override for cloud-init injection. Resolution priority: CLI flag > TOML `[infrastructure.ssh] public_key_files` > `${AETHER_SSH_KEY}.pub` sibling. Cloud sources fail fast with a remediation message naming all three paths if no key resolves
- **`[infrastructure.ssh] public_key_files` TOML schema** — operator can declare one or more SSH public-key paths in the bootstrap config; `SshKeyResolver` reads them at Phase 2 and uploads to Hetzner via the SDK (reusing existing keys by fingerprint). Tracked as `SshKeyResource` in `BootstrapState` for cleanup
- **`[runtime.X] jar_url` TOML override** — pin the JVM-mode JAR URL when the auto-derived `https://github.com/pragmaticalabs/pragmatica/releases/download/v${version}{-candidate?}/aether-node.jar` is unsuitable (e.g. mirrors, prereleases without a stable tag). `RuntimeProfile.jarUrl()` accessor; `UserDataTemplate.resolveJarUrl(profile, version)` applies the override when present
- **JVM-mode cloud-bootstrap path** — `aether cluster bootstrap` against a `[runtime.default] type = "jvm"` source now provisions Hetzner VMs that install Eclipse Temurin 25 from Adoptium's apt repo, download the published `aether-node.jar`, and start it via `nohup java -jar … & disown` with per-node CLI args (`--node-id=`, `--port=`, `--management-port=`, `--peers=`, `--config=/opt/aether/config/aether.toml`). `BootstrapPhaseDeploy` is runtime-aware: cloud `DEPLOY_RUNTIME` SSHes each node and either (`container`) `docker rm -f && docker run -d` or (`jvm`) `pkill -f '^java -jar /opt/aether/aether-node.jar' && nohup java -jar … & disown` to inject the finalized PEERS list. Validated end-to-end on Hetzner with `aether/tests/integration/env/cloud-hetzner-jvm.toml`
- **Cloud SSH preflight in `BootstrapPhaseDeploy`** — before docker/JVM restart, polls each cluster VM with `ssh ... 'cloud-init status --wait'` (180s budget, 5s interval, removes successful hosts each iteration). Guarantees Docker is installed and the cloud-init initial container/JVM has run before the SSH-back command fires, eliminating the `bash: docker: command not found` race seen on slow VMs
- **`aether/tests/integration/env/cloud-hetzner-jvm.toml`** — JVM-mode test config matching the validated container path's `cloud-hetzner.toml`. Pins the `v1.0.0-rc1-candidate` JAR URL and disables mgmt API TLS for plain-HTTP health-check compatibility
- **`--cluster <name>` override on 17 cluster subcommands** — `ClusterTargetMixin` (Picocli `@Mixin`, ~50 LoC) extends the bootstrap-only `--cluster` flag to the full management surface: `status`, `topology`, `generation`, `tasks`, `await-quiesced`, `export`, `list-keys`, `apply`, `scale`, `drain`, `upgrade`, `migrate`, `create-key`, `revoke-key`, `rotate-key`, `destroy`, plus the existing `bootstrap`. Resolves the named cluster via `ClusterRegistry.entryFor(name)` → `ClusterHttpClient.setEndpointOverride`, reads its API key from `~/.aether/clusters/<name>/api-key` → `ClusterHttpClient.setApiKeyOverride`. Fail-fast on invalid name (regex `^[a-z][a-z0-9-]{0,62}$`) or missing registry entry. Eliminates per-command boilerplate; enables multi-cluster operator workflows without per-shell `aether use <name>`. Sealed `ClusterTargetMixin.ClusterTargetError` covers `InvalidClusterName`, `UnknownCluster`, `RegistryUnavailable`, `ApiKeyMissing`, `ApiKeyEmpty`, `ApiKeyReadFailed`
- **`aether cluster init` interactive wizard** — guided cluster bootstrap config generator. `--batch` for non-interactive mode (consumes a JSON spec via `--input`/stdin); `--output <path>` writes the generated TOML; `--format json|table|value|csv` for inspection. Picks runtime (`container`/`jvm`/`forge`/`docker`), source profile (`hetzner`/`aws`/`gcp`/`azure`/`ssh`/`docker`), node count, zone hint, image/JAR pin, SSH key paths, mTLS toggle, custom port assignments. Validates each section against the same parsers used by `aether cluster bootstrap`. See PR #173, #203
- **RBAC Tier 2 — three-role authorization model** — three hierarchical roles (`ADMIN`/`OPERATOR`/`VIEWER`) with per-route enforcement in the management API pipeline. `RoutePermissionRegistry` resolves required role by HTTP method + path prefix. 403 `Forbidden` for authorization failures. New `authorization_role` field on API keys (defaults to **`VIEWER`** — secure-by-default; was `ADMIN` in MVP draft). Existing routes annotated across all 40+ mutation endpoints; independent security audit passed clean. PR #202
- **JBCT-VO-02 lint rule recognizes parse + construct factory pattern** — value-object factory rule now accepts the canonical `parse(String) → Result<T>` + private `construct(...) → T` decomposition (and `tryConstruct` variant). Eliminated 47 `@SuppressWarnings("JBCT-VO-02")` occurrences across the codebase. PR #201
- **`@NullReturn` annotation** — JBCT-RET-03 escape hatch for legacy/Java-API methods that genuinely return `null` (Map.put, ConcurrentMap.compute callbacks, JDK collection APIs). Semantic suppression replaces ad-hoc `@SuppressWarnings("JBCT-RET-03")`. PR #192
- **`notification-emailer` slice example** — coverage example demonstrating `@Notify` resource (PostgreSQL LISTEN/NOTIFY) for slice-to-slice fan-out. Pairs with the `url-shortener-v2` example to exercise the `@Notify` codegen path. PR #195

### Removed
- **`AETHER_INSECURE_DEV_MODE` env var and dev-mode QUIC TLS paths** — `QuicTlsProvider.createDevClient`, `createInsecureClient`, `createSelfSignedServer` deleted. Every node now requires a resolved `TlsConfig` for QUIC cluster transport; `AETHER_CLUSTER_SECRET` becomes the single source of deterministic CA material. `DockerComposeGenerator` and `DockerComputeProvider` stopped emitting/propagating the flag. Also removed four `RabiaNode.rabiaNode` convenience overloads that silently supplied `Option.empty()`. **BREAKING** for anyone running nodes without cluster_secret
- **Legacy integration-test orchestrator scripts** — `deploy-compose.sh`, `deploy-cloud.sh`, `run-all.sh`, `run-suite.sh`, `setup.sh` deleted. Superseded by single `run-tests.sh` dual-cluster runner that handles `--env docker|remote|cloud` provisioning, suite execution, and teardown. README, architecture docs, `build-and-push.sh`, and cloud test harness updated accordingly

### Changed
- **PG VM Hetzner-firewalled, 5432 toggled per test run** — new `tools/pg-firewall.sh` (`init|open|close|status|destroy` subcommands) creates a Hetzner Cloud Firewall named `aether-pg-firewall` and applies it to the PG VM (resolved via `aether-cluster=test-pg` label). Baseline rules: 22/tcp from operator IP only; everything else implicitly denied. `open` adds 5432/tcp from `0.0.0.0/0` for the duration of an integration test run; `close` reverts to baseline. `aether/tests/integration/run-tests.sh` calls `open` once `--env cloud` is selected (right after the EXIT trap is installed) and `close` from `teardown()` after `cloud-reaper.sh`. PG remains invisible to the public internet on port 5432 outside the test window. Operator IP auto-detected via `ifconfig.me` on each `init`/`open`/`close` (override with `OPERATOR_IPS=<cidr>[,<cidr>...]`). Firewall is created/applied once via `pg-firewall.sh init`; subsequent `init` runs refresh the operator-IP rule after roaming networks
- **`build-linux-arm64-dist` runs on a native ARM runner (closes #211)** — `release.yml` switched from `ubuntu-latest` + `docker/setup-qemu-action` + `docker run --platform linux/arm64 azul/zulu-openjdk:25 …` to `runs-on: ubuntu-24.04-arm` with the standard `actions/setup-java@v4` + native Maven invocation. Drops the entire QEMU emulation layer. Job wall-clock collapsed from ~30–45 min (QEMU) to ~1m50s (native). Total release publish wall-clock is now ~5m30s end-to-end (build-and-release 2m → arm64+macos+docker-publish in parallel ~3m)
- **`12-network` capability gate enabled on cloud env** — `lib/suite.sh:detect_capabilities` now sets `CAP_NETWORK_PARTITION=true` for `docker|remote|cloud` (was `docker|remote`), unblocked by the local-SWIM-observation event-emission fix above
- **Membership architecture redesign (R1–R10)** — 10-phase rewrite of the cluster membership / consensus / leader-election / health-detection layers per [`aether/docs/specs/membership-architecture-spec.md`](aether/docs/specs/membership-architecture-spec.md). Eight architectural layers with one-way signal flow (Transport → SWIM → HealthReconciler → TopologyObserver → Rabia → Leader Election → CTM → Node Lifecycle FSM). Phases: **R1** Rabia gains durable `Paused` state retaining proposal log across transient quorum loss + explicit `reconfigure(ClusterConfig)`; **R2** SWIM emits canonical `SwimObservation` stream with cold-boot FAULTY suppression (`everSeenHealthy`) and transport-hint biased suspect window; **R3** new `HealthReconciler` is the sole writer of `NodeLifecycleKey`, owns `ClusterPhaseKey` (BOOTING/NORMAL/RECOVERING) state machine; **R4** `TopologyObserver` reduced to pure read-only KV projection; **R5** transport narrowed to emit only `TransportObservation`, `TopologyObserver` mutation API deleted; **R6** leader-election rank-staircase first-tick + always-listen KV poll across all FSM states; **R7** CTM phase-aware (suspended in BOOTING/RECOVERING) + `LifecycleWriter` SPI routes drain/decommission through HealthReconciler; **R8** new per-node `NodeLifecycle` FSM (STARTING→JOINING→ACTIVE→DRAINING→STOPPED) backs `/health/live` + `/health/ready`; single-writer rule for `NodeLifecycleKey` enforced; **R9** `aether status` exposes `clusterPhase` + per-node `lifecycleState` + `cluster.quorate`; new test helpers `wait_for_phase` / `wait_for_quorum` / `wait_for_node_lifecycle`; **R10** cleanup, JBCT lint pass, `LeaderManagerTest` stabilized via Awaitility, `ClusterTopologyManagerRecord.legacyLifecycleWriter` deleted. Two follow-up JBCT review rounds tightened thread-safety, null-policy, and value-object hygiene
- **Leader election rewritten as explicit state machine** — `LeaderManager` now delegates to a new `LeaderElectionFsm` backed by `integrations/statemachine`. Seven explicit states (`DORMANT → QUORUM_WAITING → ELECTING → LED → RE_ELECTING → QUORUM_LOST → STOPPED`) with declarative transition table, guards, and entry/exit actions replace the previous nine-atomic ad-hoc state. Silent early-returns are gone — every (state, event) either matches a transition or logs "ignored in state X". Key correctness fixes: (1) `LeaderCommitted(L)` where `L ∉ currentTopology` is now rejected with a WARN, eliminating the stale-commit replay that could re-install a dead leader and block re-election indefinitely; (2) `NodeRemoved`/`NodeDown` unified as `NodeGone` internally, removing the path-dependent guard that caused re-election to bail when the leader was already cleared; (3) in-flight proposals bounded by a timeout (`max(3×retryDelay, 5s)`) so a hung `propose()` Promise can no longer leave `proposalInFlight` stuck true forever; (4) `stuckElectionCount` relaxes the candidate pool to raw topology after N failed attempts, handling the degraded case where `expectedCluster` drifted; (5) `triggerElection()` arriving before `QuorumEstablished` is buffered and replayed on entry to `QUORUM_WAITING` rather than silently dropped. Single-thread dispatcher (daemon, per-node) serializes event processing; timers and proposal callbacks fire-and-forget onto `SharedScheduler`. New `LeaderManager.stop()` wired into `RabiaNode.stop()` chain. `integrations/statemachine` extended with `Builder.onEntry(state, action)` / `onExit(state, action)` and `InMemoryStateMachine.executeTransition` now runs `exit(from) → transition.action → entry(to)` (skipped for self-transitions). Follow-up cleanup #188 (rc2): collapse `NodeRemoved`/`NodeDown` at the notification layer as well
- **`QuicClusterNetwork` per-peer state machine** — introduced `PeerState` owning the full per-peer connection lifecycle (`INIT → CONNECTING → CONNECTED ⇄ EVICTED → REMOVED`) with explicit `offerOutbound`/`attach`/`evict`/`authoritativeRemove` transitions and a separate 10k-entry offline buffer. Collapsed five previously-parallel structures (`peerLinks`, `connectingInProgress`, `passivePeers`, `connectionEstablishedAt`, plus the broadcast queue-on-evict previously conflated with Netty writability backpressure) into a single `Map<NodeId, PeerState>`. `outboundQueues` retained only for channel-level writability backpressure. Eliminates whole classes of race conditions (duplicate connection attempts, ordering dependencies in `disconnect`, queue preservation vs. drop during transient evictions). Contributes to 6–12× speedups on Cluster A integration suites (04-streaming 310s → 46s, 08-resources 370s → 57s, 09-artifacts 218s → 18s) and recovery of 15-delegation from 0/2 to 2/2. 21 unit tests. See #185. Supersedes the reverted broadcast queue-on-evict attempt (cb8ee3952) which reused the 100-entry Netty backpressure queue as the wrong primitive
- **Integration test harness simplified via ClusterGeneration barrier** — new `aether/tests/integration/lib/generation.sh` exposes `await_generation_quiesced` / `generation_current` / `generation_quiesce_now` over `POST /api/cluster/await-quiesced`, preferring the `aether cluster await-quiesced` CLI over raw curl. Deleted: `self_heal` 3-step recovery + 4 chaos-suite call sites, `restore_baseline`, 4-iteration retry loops in `deploy_blueprint` / `publish_blueprint` / `deploy_start`, 5-iteration retry in `deploy_blueprints`, `tolerate-already-in-state` branch in canary complete, test-side 5..7 overprovision tolerance in `test-kill-node`, the drain-reset / rescale-fallback in `test-disruption-budget` and `test-stale-route-cleanup`. Propagation-race `sleep N` calls replaced with epoch barriers; legitimate chaos-timing sleeps (failure-detection windows after `kill_node`) kept. Timing instrumentation added: provisioning, cluster formation, blueprint deploy, per-suite quiesce-barrier duration, per-test duration all printed in the final summary. New `aether/tests/integration/README.md` covers prerequisites, env setup, building, running, suite selection, results format, troubleshooting, and adding tests (#174). See [`aether/docs/specs/cluster-generation-spec.md`](aether/docs/specs/cluster-generation-spec.md) §13.3
- **Aether relicensed to BSL 1.1** — `aether/**`, `jbct/slice-processor/`, and `jbct/slice-processor-tests/` carry per-file SPDX `BUSL-1.1` headers with `Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko`, `Change Date: 2030-01-01`, `Change License: Apache-2.0`. Root `LICENSE` turned into a monorepo routing document; Apache-2.0 text preserved in `LICENSE-APACHE-2.0`. `core/`, `integrations/`, and the rest of `jbct/` remain Apache-2.0. Canonical header template at `docs/legal/bsl-header.txt`, bulk applicator at `tools/license/apply-bsl.sh`. See issue #162 for physical relocation of the BSL-licensed slice-processor modules under `aether/` (deferred)
- **QUIC TLS always required** — `AetherNodeConfig.quicTls: TlsConfig` is now a mandatory builder field, independent of `cluster.tls` (which still gates HTTP TLS). `Main.java` always resolves a deterministic `TlsBundle` from `cluster_secret` / `AETHER_CLUSTER_SECRET` and fails startup if neither is set. `QuicSslContextFactory.createServer/createClient/createServerFromBundle/createClientFromBundle` accept ALPN `applicationProtocols` varargs; `QuicTlsProvider` wires the `"aether-cluster/1"` ALPN through for every handshake. Cluster transport now uses a shared CA — no more ephemeral-per-restart certs
- **HttpForwardResponse demuxed by `Pipeline`** — `AetherNode` now routes `HttpForwardResponse` to `ManagementServer.onHttpForwardResponse` for `Pipeline.MANAGEMENT` and to `AppHttpServer` otherwise. Fixes mgmt-forwarded requests timing out because responses were landing in the app forwarder's `pendingForwards` map (classic 13-edge-cases drain 503)
- **Composite API-key validator honors config-based credentials** — `KvStoreApiKeyValidator.validateApiKey` falls back to bypass only when BOTH config-based keys AND KV-store keys are absent. Previously an empty KV-store erroneously let unauthenticated requests through even when `appHttp.apiKeys` was configured (mgmt API returned 200 where it should have returned 401/403). `SecurityValidator.hasConfiguredCredentials()` default + `ApiKeySecurityValidator` override make the check composable
- **API-key KV iteration now type-safe** — `KvStoreApiKeyValidator` and `ApiKeyRoutes` (list / audit / sweep) migrated from `kvStore.snapshot().entrySet()` + `.asString()` filtering to `kvStore.forEach(ApiKeyKey.class, ApiKeyValue.class, …)`. Fixes `ClassCastException: LeaderKey cannot be cast to AetherKey` when the store contains non-`AetherKey` entries
- **Forward timeout config consolidation** — `AppHttpConfig.forwardTimeout` removed; HTTP forward timeouts now live in `[timeouts.forwarding]` as `app_timeout` (default 5s) and `management_timeout` (default 5s, used by management forwarding). `ForwardingTimeouts` record extended; `AppHttpConfig` factory overloads collapsed
- **jOOQ version bump** — 3.20.10/3.20.11 → 3.21.1 across root, integrations/db, and aether/resource (fixes version drift)
- **Cluster bootstrap spec** — node-group-centric configuration model with named source/runtime profiles, multi-zone via multi-source, template inheritance (`[template.X]`), elected floating-IP load balancer, deferred database URL resolution, `config_version` field, firewall rules with TCP/UDP protocol support, three node roles (`core`/`worker`/`spot`)
- **Tier 1 cluster-sync rename** — `MetricsMessage` → `ClusterSyncMessage`, `MetricsPing/Pong` → `ClusterSyncPing/Pong`, `MetricsCollector` → `ClusterSyncCollector`, `MetricsScheduler` → `ClusterSyncScheduler`, plus factory/method/test renames. Pure rename — zero behavior change. `ENVELOPE_FORMAT_VERSION` bumped (1000 → 1001) because the deterministic codec tag keys on FQCN. App-level metrics collectors (`InvocationMetricsCollector`, `DeploymentMetricsCollector`, `ArtifactMetricsCollector`, `EventLoopMetricsCollector`, `RabiaMetricsCollector`, `GCMetricsCollector`), `MetricsRoutes`, `DashboardMetricsPublisher`, and Tier-2 `WorkerMetrics*` types are untouched. Tier-2 rename tracked in issue #178. See `aether/docs/specs/clustersync-refactor-spec.md` commit 0
- **Cloud bootstrap pipeline activated end-to-end** — `BootstrapPhaseProvision.provisionCloudSource` now renders per-node `UserDataTemplate` output (previously dead code) and threads it through `ProvisionSpec.userData()` per VM. `BootstrapPhaseDeploy.deployCloudSource` is no longer a no-op: it runs the SSH preflight, restarts the runtime (container or JVM) with the finalized 3-part PEERS list, and health-polls `/health/live` per node. `BootstrapPhaseValidate` generates `clusterSecret` once at validate-time and persists it through `BootstrapState` so it's available to all downstream phases. The composed `aether.toml` is bind-mounted over the image's bundled `/app/aether.toml` (container) or `--config=`-pointed (JVM) so operator config wins. Per-node identity (`NODE_ID`, `CLUSTER_PORT`, `MANAGEMENT_PORT`, `PEERS`, `AETHER_CLUSTER_SECRET`) is delivered as env vars/CLI flags rather than TOML, matching `Main.java`'s schema. `BootstrapResult.endpoint` includes the management port (`http://<ip>:8080`) so `--wait` polling actually reaches the API. `--wait` polls `/api/cluster/status` for `state == "CONVERGED"` (was the wrong route+field). End-to-end validation: both `cloud-hetzner.toml` (container, image from `RuntimeProfile.image()`) and `cloud-hetzner-jvm.toml` (JVM, Temurin 25 + JAR from GitHub releases) reach `Cluster is healthy.` with leader elected and all task groups distributed
- **`BootstrapOverlayGenerator` schema** — `[cluster]` block now emits `tls = config.operations().tls().autoGenerate()` (operator-driven mTLS toggle for mgmt API; was inheriting default `true`). `[cluster.ports]` (was `[cluster].ports` placeholder) carries operator-specified `management` and `cluster` ports. `[node]` and `[cluster].peers` blocks dropped — Main reads neither; per-node identity is env-driven now. Database section flattened to `[database.<name>]` with auto-detected `async_url`/`jdbc_url` field name (was `[database.forge].async_url` only)

### Changed
- **`HETZNER_API_TOKEN` → `HCLOUD_TOKEN`** — Standardized to Hetzner's official env var name across all Java code, docs, and specs
- **`leader` → `active` rename** — `DeploymentManagerImpl` and `AbTestManager` field/method rename (`requireLeader()` → `requireActive()`), `DeploymentError.NOT_LEADER` → `NOT_ASSIGNED` with task-group-aware message
- **Path rearrangement** — All management API paths follow "params at tail" convention (`/api/deploy/{id}/promote` → `/api/deploy/promote/{id}`). Breaking wire-protocol change, acceptable per RC1 status
- **QUIC frame/data limits** — Bumped from 1MB/4MB/16MB to 32MB/32MB/64MB for frame length, stream data, and connection data. Enables large artifact forwarding through LB management pipeline
- **Disruption budget** — `checkDisruptionBudget()` now counts any non-ON_DUTY node state (DRAINING, DECOMMISSIONED, SHUTDOWN) as consuming a budget slot, not just DRAINING
- **Management API forwarding** — LB forwards all management API requests to core nodes via QUIC binary protocol (Pipeline.MANAGEMENT). Eliminates NoOp stubs, PassiveLBNode, and local handling. Endpoints that previously returned 500 or hung (artifacts, schema, drain, storage) now work correctly through the LB
- **TopologyManager.coreNodes()** — Maintained `Set<NodeId>` of non-passive nodes for O(1) core node lookup. Used by HttpForwarder for management pipeline node selection
- **PassiveNode simplified** — Removed `apply()` method and correlation map. KV-Store sync via decisions continues; consensus proposals no longer needed from passive nodes
- **Dashboard** — Fixed empty panels (strategies store endpoints, template fields). Added 10s secondary polling for topology/governors/strategies/streams/observability. Fixed success rate display
- **StreamAccessImpl → PartitionedStreamAccess** — JBCT naming compliance, removed Impl suffix
- **Example scripts** — `run-forge.sh` scripts now extract version from POM dynamically instead of hardcoding
- **JBCT-RET-07 rule refinements** — removed `onPresent` (Option side-effect, no error channel) and `timeout` (scheduling side-effect) from chain-terminal set; added string-literal stripping to prevent false positives on code-generation string content; added top-level assignment detection to exclude explicitly-typed local declarations
- **Naming consistency: Pragmatica Lite → Pragmatica Core; Aether → Unified Application Runtime** — terminology pass across docs, READMEs, in-source documentation, and user-facing CLI text. Library identity is "Pragmatica Core" (was "Pragmatica Lite"); Aether is consistently described as "Unified Application Runtime" in long-form references. Module/package/artifact names unchanged. PR #129

### Fixed
- **`CoreSwimHealthDetector.addObservationListener` lost listeners registered before SWIM started** — the previous body `protocol().onPresent(p -> p.addObservationListener(consumer))` silently dropped listeners when `protocol()` was empty (which is the case at AetherNode init time, before `QuorumStateNotification` arrives and triggers SWIM startup). Both `healthReconciler::onSwimObservation` and `eventAggregator::onSwimObservation` were attached at init, so both vanished. `healthReconciler` happened to also receive faults via the legacy `SwimHealthListener.onMemberFaulty` callback path, so health-driven recovery kept working — but `eventAggregator` had no fallback channel, so on cloud the events ring buffer never saw `NODE_FAILED`/`NODE_LEFT` after a kill (12-network suite). Fix: `addObservationListener` now buffers consumers in a `pendingObservationListeners` list (CoW); `seedAndWrap` re-attaches every pending listener to each freshly-started `SwimProtocol`, so registrations made during AetherNode init survive across SWIM start/restart cycles. Pre-existing `protocol().onPresent` attach is retained for runtime registrations during a Running state
- **`NODE_FAILED`/`NODE_LEFT` events emitted from local SWIM observation** — every node now records what it witnessed via SWIM, eliminating the leader bottleneck. `ClusterEventAggregator` lost `onNodeRemoved`/`onNodeDown` (both gated on the leader-only `TopologyChangeNotification` broadcast); new `onSwimObservation(SwimObservation)` emits `NODE_FAILED` on `FaultyObserved` and `NODE_LEFT` on `DepartedObserved`. Wired in `AetherNode` alongside the existing `healthReconciler` SWIM listener. Lifecycle KV writes (DRAINING/DECOMMISSIONED) still emit via `onNodeLifecyclePut` (KV-replicated, every node sees them). Operator drain and SWIM-detected failure now flow through separate, idempotent paths with no single point of failure for observability
- **Runtime cloud auto-scale wired end-to-end** — `BootstrapOverlayGenerator.cloudComputeSection` was emitting `provider` to `[cloud.compute]` (wrong section). `ConfigLoader.populateCloudConfig` reads from `[cloud]`, so on Hetzner/AWS/GCP/Azure clusters `lifecycleManager.isCloudManaged()` returned false and `/api/cluster/scale` requests logged `"no ComputeProvider, cannot auto-provision"` and silently no-op'd. New `cloudSection` + `cloudCredentialsSection` helpers emit `[cloud] provider = "..."` and `[cloud.credentials] api_token = "..."` for cloud-type sources. `cloudComputeSection` reworked: dropped misplaced `provider`, added `server_type` from CORE role's `instance_type`. 03-scaling now PASS on Hetzner. Operator-facing docs added: `docs/reference/cloud-integration.md` § Credential Propagation to Nodes, `docs/operators/runbooks/scaling.md` § Cloud Auto-Scaling, `docs/specs/cluster-bootstrap-spec.md` REQ-4.2.7
- **Manifest-driven version strings** — `aether --version` reported a hardcoded `"Aether 1.0.0-alpha"` even on `release-1.0.0-rc1`; `AetherNode.VERSION` was hardcoded `"1.0.0-rc1"`. New `BuildInfo` class in `aether-config` reads `Implementation-Version` + `Implementation-Build-Date` from the executable jar's MANIFEST.MF; new `AetherVersionProvider` (picocli `IVersionProvider`); POMs configure `maven-jar-plugin` and `maven-shade-plugin`'s `ManifestResourceTransformer` to inject the entries from `${project.version}` + `${maven.build.timestamp}`. `aether --version` now reports `Aether <project.version> (built <ISO-timestamp>)`. Falls back to `dev`/`unknown` on IDE classpath
- **Bootstrap formation auth chicken-and-egg** — `ClusterBootstrapOrchestrator.httpPost` → `ClusterHttpClient.postDirect` did not attach `X-API-Key`. On clusters where `[app-http] security_mode = "API_KEY"` the formation POSTs to `/api/cluster/config` and `/api/cluster/keys` got HTTP 401. New `postDirect(url, body, Option<String> apiKey)` overload; `BootstrapPhaseFormation.extractConfiguredApiKey` reads rich-syntax `[source.X.node_config.app-http.api-keys.<key>] authorization_role = "ADMIN"` (preferred) or simple `api_keys = [...]` list (fallback, VIEWER role yields HTTP 403). Cluster B integration test fixture updated to rich syntax with explicit ADMIN role
- **`BootstrapCleanup` NumberFormatException on cloud cleanup** — `BootstrapPhaseProvision` constructed `CreatedResource.ProvisionedVm` with `node.nodeId()` (e.g. `hetzner-eu-core-0`) as `resourceId`. On cleanup, `BootstrapCleanup.terminateInstance` passed that string to `HetznerComputeProvider.terminate` which expects a numeric Long → exception on every failed-bootstrap cleanup; reaper backstop saved us. Fixed by passing `node.serverId()` (the actual provider instance ID)
- **Cloud-aware integration test helpers** — `test-01-quorum-safety.sh:direct_scale_status` was Docker-only (port-hopping `${TARGET_HOST}`) — on cloud all attempts returned `status: 000`, 3p/3f. Now hits the leader directly via `cloud_public_ip` on cloud. `test-disruption-budget.sh` hardcoded `node-5/node-4/node-3` (Docker convention) — new `to_node_id` helper in `lib/common.sh` translates docker→cloud forms (`node-N` → `${CLOUD_SOURCE_NAME}-core-$((N-1))`). `wait_for_node_count_fast` falls back to slow-poll on cloud. `test-cert-rotation.sh` skips error-rate assertion when `renewalStatus = NOT_CONFIGURED` (no rotation possible on TLS-disabled cluster — proper coverage tracked in #209). 03-scaling now 3p/0f, 13-edge-cases drain test 6p/0f, 05-security 3p/0f on Hetzner


- **`aether cluster destroy --cluster <name>` flag** — symmetric with `cluster bootstrap --cluster <name>`. Resolves the named cluster via `ClusterRegistry.entryFor(name)` and operates on its bootstrap-state file regardless of which cluster is currently active. Without it, destroying a non-active cluster required `aether use <name>` first (and the integration-test cloud teardown was a swallowed no-op for the inactive A/B cluster). For arbitrary or unregistered clusters, `tools/cloud-reaper.sh --cluster <name> --destroy --force` remains the label-based safety net independent of registry/state-file presence
- **`aether cluster init -o` short alias collided with global `-o` `--format`** — PR #203 introduced `-o` as a short alias for `--output` on `ClusterInitCommand`, which Picocli rejected with `DuplicateOptionAnnotationsException` against the existing global `-o` for `OutputOptions.format`. The crash happened at every `aether` invocation, breaking the entire CLI. Short alias dropped — long form `--output <path>` retained
- **`postgres-async` LISTEN/UNLISTEN ordering for transactions** — `PgListenChannel` issued `LISTEN`/`UNLISTEN` on the implicit autocommit connection while a transaction was in progress on a separate connection, so async notifications arrived before the consumer's transaction had committed (or vice versa). Now LISTEN is deferred until the connection's transaction state is stable; UNLISTEN respects in-flight transaction. PR #194
- **`postgres-async` typed-get gaps for Boolean/UUID/byte[] and `TypeToken<T[]>`** — `PgRow.get(Boolean.class)`, `PgRow.get(UUID.class)`, and `PgRow.get(byte[].class)` returned `Cause` instead of mapping to the corresponding wire types; `TypeToken<T[]>` array decoding was missing entirely. Fixed via `PgValueDecoder` extensions + array-decoder dispatch. PR #197
- **`postgres-async` `PerformanceTest` hung CI 10-min timeout** — `PerformanceTest.java` is `@Tag("Slow")` and routinely runs >10 min, but Surefire's `excludedGroups>Infinite` only excluded `Infinite` (JUnit 5 tag-expression syntax via comma is broken; pipe is over-eager). Added explicit `<excludes><exclude>**/PerformanceTest.java</exclude></excludes>` so the default surefire run skips it. Run manually with `-DexcludedGroups=` or via the `slow-tests` profile when needed
- **`CertificateRenewalSchedulerStaleTimerTest` `@Disabled` — racy under CI** — `immediateRenewalBranch_storesScheduledFutureForCancellation` reproduces 2/2 in CI on slow runners due to a race between scheduler tick and assertion read. Disabled with redesign-note comment (`CountDownLatch` on transition or non-firing executor). Other two tests in the class are stable. Tracked for post-RC1
- **Cloud test harness on `run-tests.sh --env cloud`** — three patches enabling end-to-end cloud integration runs: (1) per-cluster bootstrap is gated on suite selection (`A_SUITES` / `B_SUITES` non-empty), preventing 11-VM bootstrap that exceeds Hetzner quota when only one cluster's suites were requested; (2) `CLUSTER_*_MGMT` / `CLUSTER_*_APP_DIRECT` are derived from `cloud_public_ip node-1` per cluster (was hardcoded to docker-compose `localhost:5150`/`5170`); (3) teardown uses `tools/cloud-reaper.sh --cluster <name> --destroy --force` (was `aether cluster destroy --cluster <name>` which was a no-op until commit `036057b4d` added the flag — leaking 5 VMs per cloud run). Smoke suite `00-smoke` now passes end-to-end on Hetzner: 5 nodes, `state=CONVERGED`, all assertions green, 165s wallclock
- **`ClusterInitCommand` + `ClusterConfigWizard` discarded `Result` returns (JBCT-RET-07)** — five unhandled `Result<...>` return values in the post-merge cluster-init wizard surfaced as JBCT lint errors. Resolved via extracted helpers that consume the `Result` chain end-to-end (no `@SuppressWarnings`)
- **Bootstrap silently swallowed cluster-config + API-key store failures** — `BootstrapPhaseFormation.storeClusterConfig` and `storeApiKey` printed `Warning: ...` on HTTP failure but reported bootstrap success regardless. The leader's `NodeLifecycle` FSM races with `/health/ready` quorum signal — Phase 6 detects readiness as soon as enough peers connect, but the leader's single-writer KV path requires its own NodeLifecycle to be ACTIVE (typically a few seconds later). On unlucky timing the cluster never had its config persisted, so `--wait` polled `state == CONVERGED` indefinitely. Now both stores retry every 2s for up to 60s; terminal failure fails bootstrap with new `BootstrapError.FormationWriteFailed`; success path is unchanged
- **Test-infra cloud-aware `reassign_task_group`** — `lib/cluster.sh` previously stripped a `node-` prefix and dereferenced the result as a port-offset integer; on cloud where leaders are `hetzner-eu-core-N`, this exploded with `set -u: hetzner: unbound variable`. Added env-aware branch: cloud uses `cloud_public_ip "$leader"` to resolve the leader's host, with the standard `MGMT_PORT` (no per-node offset). Docker/remote behavior unchanged
- **Test-infra capability probe parses `PG_URL`** — `detect_capabilities` checked `pg_isready -h ${PG_HOST:-${TARGET_HOST:-localhost}}`, defaulting to `localhost` for cloud runs and unconditionally setting `CAP_PERSISTENCE=false`. Now extracts host/port from `PG_URL` (the canonical source on cloud, sourced from `/tmp/aether-test-pg.env`) when `PG_HOST` is unset. Unblocks `06-deployment`, `08-resources`, `10-database`, `14-storage` on `--env cloud`
- **Test-infra teardown trap** — `set -e` plus a non-zero `print_results` exit (when any suite fails) made the script exit before Step 12 teardown, leaking 5 cloud VMs per failed run. Now an `EXIT` trap installed right after suite filter step calls `teardown` regardless of which step failed; `print_results` wrapped in `set +e/-e`. Same `SKIP_TEARDOWN` opt-out honored
- **Test-infra cloud-reaper path** — `${REPO_ROOT}/tools/cloud-reaper.sh` resolved to `aether/tools/cloud-reaper.sh` (REPO_ROOT is actually `aether/`, not the repo root) — the path didn't exist; teardown failed with "No such file or directory" as soon as the EXIT trap actually fired. Path now `${REPO_ROOT}/../tools/cloud-reaper.sh`
- **Test-infra `ENV_TYPE` not exported to suite subprocesses** — `lib/common.sh` set `ENV_TYPE` but never `export`ed it; suite scripts under parallel-suite-runner subprocesses got an empty value, defaulting cluster-shape branches to docker. Added `export ENV_TYPE`
- **Test-infra per-cluster API key sourced post-bootstrap** — RBAC Tier 2's secure-by-default (VIEWER) means every API call needs authentication. Default `API_KEY=aether-integration-test-key` (the docker hardcoded value) yielded `401 Unauthorized` against fresh cloud clusters. `run-tests.sh` now reads `~/.aether/clusters/<name>/api-key` after each cluster bootstrap and exports it as `AETHER_API_KEY`/`ADMIN_API_KEY`/`OPERATOR_API_KEY`
- **`BootstrapCleanup` couldn't terminate cloud VMs after a failed bootstrap** — `CreatedResource.ProvisionedVm.provider` was being stamped with the source TYPE (literal `"cloud"`) instead of the actual provider name (`"hetzner"`/`"aws"`/etc.). `BootstrapCleanup` then looked up `EnvironmentIntegrationFactory("cloud")` and got `No factory found for provider 'cloud'` — every failed bootstrap orphaned all 5 VMs, requiring manual reaper cleanup. `BootstrapPhaseProvision.resolveProviderName` now returns `source.provider().map(CloudProviderName::value).or(source.type().value())`, with package-private exposure for testability. Added `BootstrapCleanup.cleanup(state, cloudComputeResolver)` overload as a clean test seam. Regression tests pin both the construction site and the cleanup consumer
- **Cloud-init for cluster VMs included no SSH key, blocking all post-failure diagnosis** — `UserDataTemplate` had no `ssh_authorized_keys` section and `HetznerEnvironmentConfig.sshKeyIds` was never populated by the bootstrap orchestrator. New `BootstrapPhaseSshKey` (Phase 2) uploads-or-reuses (by MD5 fingerprint) the operator's public key to Hetzner via the SDK, stamps `SshKeyResource` in state for cleanup tracking, and threads the resulting key id into `ProvisionSpec`. New `SshAuthorizedKeysScript` cloud-init fragment installs both the operator key (`/root/.ssh/authorized_keys`) and a passwordless `aether` system user. Resolution order: `--ssh-public-key` CLI flag > `[infrastructure.ssh] public_key_files` TOML > `${AETHER_SSH_KEY}.pub` sibling. Cloud sources fail fast if no key resolves
- **JVM-mode JAR URL pointed at a non-existent repo** — `UserDataTemplate.appendJvmInstall` used `pragmaticalabs/aether/releases/...` (wrong repo path; correct is `pragmaticalabs/pragmatica`) and produced a `v${version}` tag that didn't exist for prerelease versions like `1.0.0-rc1`. Fixed repo path; tag derivation now appends `-candidate` when the version has a prerelease suffix; added `[runtime.X] jar_url` TOML override for explicit pinning
- **`UserDataTemplate.render` was never invoked from production code** — the rendered cloud-init script (Docker install, image pull, `aether.toml` write, container/JVM startup) existed but no production caller wired it through to provisioning. `BootstrapPhaseProvision` only delivered the SSH-keys-only script, leaving cluster VMs as bare Ubuntu with no aether-node runtime. Now `provisionCloudWithCompute` builds per-node user_data via `UserDataTemplate.render(...)`, threads it through `ProvisionSpec.userData()`, and provider integrations (Hetzner/AWS/GCP) honor the per-spec value over the factory-wide default
- **`docker run --config /config/aether.toml` was silently ignored** — `Main.findArg("--config=")` requires the `=`-joined form; space-separated args are dropped. Even with `=`, the image's hardcoded entrypoint passes `--config=/app/aether.toml` first and `findArg` returns the first match — bundled defaults always won. Fixed by bind-mounting the composed file directly over `/app/aether.toml:ro` (so the entrypoint's hardcoded path now points to operator config) and dropping the trailing `--config` arg. JVM mode uses the `=`-form correctly via direct CLI args
- **Cloud-init signal "done" arrived before Docker was usable** — `BootstrapPhaseDeploy`'s SSH preflight polled `ssh ... 'true'`, which succeeds the moment sshd accepts a connection. On slow Hetzner VMs (Temurin install, image pull) the SSH-back's first `docker rm -f` then failed with `bash: docker: command not found`. Preflight probe upgraded to `ssh ... 'cloud-init status --wait'` which blocks until cloud-init reaches a final state — guaranteeing Docker is installed and the cloud-init initial container has run before the deploy phase issues commands
- **Cloud SSH-back killed its own SSH session via overly broad `pkill`** — `pkill -f /opt/aether/aether-node.jar` matches against full process command lines, including the bash spawned by SSH (whose argv contains the JAR path as part of our long `pkill … nohup java …` script). pkill killed the SSH session itself → exit 255, no JVM started. Pattern anchored to `^java -jar /opt/aether/aether-node.jar` so only processes whose argv0 is `java` match
- **JVM mode tried JDK 21 against a Java 25 JAR** — `appendJvmInstall` ran `apt-get install openjdk-21-jre-headless`, but the published `aether-node.jar` is compiled to class file 69.0 (Java 25). Cloud-init succeeded, then the JVM crash-looped with `UnsupportedClassVersionError`. Now installs Eclipse Temurin 25 from Adoptium's apt repo (`packages.adoptium.net`), with proper signed-by keyring setup and version codename derivation
- **Composed `aether.toml` inherited `[cluster].tls = true` from defaults**, requiring mTLS for the management API and breaking plain-HTTP `wget --spider` health-checks (the published image's HEALTHCHECK and the deploy-phase poll). `BootstrapOverlayGenerator.clusterSection` now emits `tls = config.operations().tls().autoGenerate()` so `[operations.tls] auto_generate = false` flows through to a plain-HTTP mgmt API (cluster QUIC transport still uses TLS via `[tls].cluster_secret`)
- **Cloud SSH-back used the `aether` user (lacked docker group)** — cloud-init creates `aether` for SSH access but Docker is installed afterward, so `aether` is not in the `docker` group and `docker ps` returns "permission denied". Cloud sources now default to `root` for the SSH-back commands (cloud-init runs as root and has docker access). SSH-source path's `aether` default unchanged. Operator can override via `source.user`
- **Image tag derived from `cluster.version` instead of operator config** — `BootstrapPhaseDeploy.resolveContainerImage` was producing `ghcr.io/pragmaticalabs/aether-node:1.0.0` (which doesn't exist; only `:1.0.0-rc1-candidate` is published). Now reads `RuntimeProfile.image()` from the matching runtime profile (default = `"default"`) and uses it verbatim, falling back to derived only when no image is configured
- **PEERS list used `port + i` offset for multi-host clusters** — `buildThreePartPeers` emitted `nodeId:host:port+i` (a docker-compose holdover where multiple containers shared a host network). For SSH and cloud sources, every VM has its own host network and binds the same `clusterPort`. Peers list now emits `nodeId:host:port` for all entries
- **Cloud SSH preflight failed on the first VM that wasn't ready yet** — when DEPLOY_RUNTIME starts, slow VMs may still be booting / installing docker. The first SSH attempt timing out aborted the whole phase. Preflight now polls each cluster node with a 180s outer budget, removing successfully-reached hosts each iteration; failure aggregates and names every persistently-unreachable IP in the error message
- **Cloud `BootstrapPhaseDeploy` was a no-op for cloud sources** — printed `"Cloud-init already applied during provisioning"` and returned success without doing anything. Cluster never got the finalized PEERS list (cloud-init's container started with empty PEERS, falling through to `generatePeersFromConfig` → `aether-node-N:8090` defaults that don't resolve). Now SSHes each cloud node and `docker rm -f && docker run -d` (container) or `pkill && nohup java -jar` (JVM) with the finalized 3-part PEERS, then health-polls `/health/live`. Reuses the package-`static` `buildThreePartPeers` helper across both runtime branches
- **Bootstrap config TOML never persisted to KV-Store** — `BootstrapPhaseFormation.buildConfigJson` POSTed `{"clusterName": …, "version": …}` to `/api/cluster/config`, but the server's `ApplyConfigRequest` expects `{"tomlContent": …, "expectedVersion": <long>}`. Jackson reported `Type mismatch: expected long, got unknown at expectedVersion` (HTTP 500). Now sends the original TOML content (threaded through `BootstrapContext.rawTomlContent`) with `expectedVersion=0` (server's initial-store path)
- **Bootstrap CLI hit a dead `/api/cluster/api-key` legacy route** — `BootstrapPhaseFormation` POSTed to both the new `/api/cluster/keys` (succeeds) and the legacy `/api/cluster/api-key` (HTTP 404, no server handler). Removed the legacy fallback and the orphan `CLUSTER_API_KEY_SET` enum entry
- **`aether cluster bootstrap --wait` exit code was non-zero on success** — `--timeout` defaulted to `0` and a validator returned `ExitCode.ERROR` when `--wait` was passed without it; the bootstrap completed successfully but the CLI process exited non-zero, breaking scripts/CI. `--timeout` now defaults to `300` seconds (matching `aether cluster apply`); the validator is removed. Same fix applied symmetrically to `aether cluster scale` and `aether blueprint deploy`
- **`aether cluster bootstrap --wait` polled a route with no server handler** — `ManagementRoute.CLUSTER_HEALTH` (`/api/health`) is bound by `aether health` (per-node) but there is no cluster-health endpoint at `/api/health`. The poll always returned `UNKNOWN`. Now polls `CLUSTER_CONFIG_STATUS` (`/api/cluster/status`) and reads `state` from the response, treating `"CONVERGED"` as ready. Threads `BootstrapResult.apiKey()` and `BootstrapResult.endpoint()` to `ClusterHttpClient.setApiKeyOverride`/`setEndpointOverride` so the polling actually authenticates and targets the just-bootstrapped cluster (which isn't registered as the active cluster yet)
- **Cloud test harness used non-existent bastion + private network** — `cloud_ssh` invoked `-J ${AETHER_SSH_USER}@${BASTION_IP}` and `cloud_node_ip()` returned hardcoded `10.0.1.1${num}`, but the bootstrap config never created a private network or bastion. Switched to **Option A** (direct public-IP addressing): new `cloud_public_ip <node-id>` helper reads VM addresses from `~/.aether/clusters/<name>/bootstrap-state.json`; `cloud_ssh` connects directly. `BOOTSTRAP_CLUSTER_NAME` env var now matches what `aether cluster bootstrap --cluster <name>` registered. Includes 8 unit tests in `test/test-cloud-helpers.sh` against a synthetic state-file fixture. Bastion + private network model deferred to a future phase (requires non-trivial bootstrap code: `NetworkingType` enum extension, `BastionProvider` SPI, `BootstrapPhaseNetwork`)
- **Hetzner `cx22` instance type no longer exists** — both `cloud-hetzner.toml` and `cloud-hetzner-b.toml` updated to `cx33` (4 vCPU, 8 GB RAM). cx22 → 404 at provision
- **Cluster scale-up silently dropped on non-leader receivers** — `ManagementRoute.CLUSTER_SCALE` was declared `taskGroup(SCALING)` but `ClusterTopologyManagerRecord.onClusterConfigChanged` is leader-gated, so when the SCALING owner was not the leader, the scale request silently no-op'd (CTM Inactive → notification dropped). Reclassified to `RouteTarget.LEADER` (uses the `LeaderNode` infrastructure from the delegation Bug A fix). Also: `setDesiredSize` returns `Promise<Unit>` (was fire-and-forget `Result<Unit>` swallowing consensus apply failures). Also: removed the duplicate `ClusterConfigKey.CURRENT` write inside `executeScale` — the route handler is now the sole writer; CTM picks up the change via the existing KV ValuePut notification path
- **Self ON_DUTY bootstrap retry** — `HealthReconciler.proposeSelfOnDutyWrite` now retries up to 8 attempts with exponential backoff (200ms → 2s cap) on transient `ConsensusError.NodeInactive` rejections. R8 deletion of `NodeDeploymentManager.retryLifecycleOnDuty` regressed cold-start where the local lifecycle FSM hadn't reached ACTIVE before the self-ON_DUTY KV write fired. `AetherNode.bridgeSelfReadyToLifecycle` reordered to advance `nodeLifecycle.signalReady()` before `healthReconciler.signalSelfReady()` to narrow the race window. Without this fix the cluster never reached `coreCount=5` after `docker compose up` on remote
- **Aggregator quorum threshold structurally unreachable** — `ObservationAggregator.quorumThreshold` returned `(onDuty+1)/2` (= 3 for 5-node clusters), but each node's `HealthReconciler` only feeds its own local SWIM observation into the aggregator (no cross-node observation gossip). With 1 observer per aggregator and threshold ≥ 2, `tally()` permanently returned `none()`, and `proposeLifecycleWrite(target, DECOMMISSIONED)` was never invoked when peers died. Now leader-gated single-observer mode: leader's local SWIM FAULTY observation alone authorizes the lifecycle KV write (SWIM's own indirect-probe quorum already validates the observation). Followers' aggregators continue observing for diagnostics but propose no writes
- **Bridge KV `NodeLifecycleKey` to `/api/events`** — followers now surface `NODE_LEFT` (graceful drain → DECOMMISSIONED) and `NODE_FAILED` (abrupt loss with no prior DRAINING) on the events stream via `ClusterEventAggregator.onNodeLifecyclePut`. Previously `NODE_LEFT/NODE_FAILED` were emitted only on the leader via the QUIC `TopologyChangeNotification.NodeRemoved/NodeDown` path, leaving follower event buffers silent. The bridge uses a per-node lifecycle-state cache for idempotent edge detection and reads cluster size from the membership snapshot
- **Operator task-group reassignment ignored** — `CLUSTER_TASK_REASSIGN` route was declared `RouteTarget.taskGroup(DEPLOYMENT)` but `TaskAssignmentCoordinator.reassign()` is leader-bound; requests forwarded to the DEPLOYMENT owner (often not leader) returned `NOT_LEADER` which `curl -sf` swallowed silently. New `RouteTarget.LeaderNode` variant routes leader-bound management calls correctly via `ManageableNode.leaderId()`. `NOT_LEADER` now surfaces as HTTP 409. `HttpForwarder` gained `forwardToLeader` branch with typed `NoLeaderElected` / `LeaderDisconnected` / `NotLeader` causes
- **Auto-reassignment loops back to restarted node** — `TaskAssignmentCoordinator.isOrphanedOrFailed` orphan branch (topology-departure path) now calls `trackFailedNode(group, assignedTo)` to arm the 30s cooldown. `selectLeastLoadedNode` tie-breaker switched from `Comparator.naturalOrder()` on `NodeId` (which always preferred lexicographically-lowest) to a stable hash-based rotation `(group.hashCode() * 31 + node.hashCode()) & 0x7fffffff` so different groups prefer different nodes on tied load. `writeAssignment` clears the target node from `failedNodes` so an operator-issued assignment is not blocked by stale cooldown
- **CLI `--request-timeout` ignored by cluster commands** — `ClusterHttpClient` (used by `aether cluster *` subcommands) used a static `JdkHttpOperations` singleton with default ~60s request timeout, causing `aether cluster await-quiesced --timeout 60s` to time out at exactly 61s before the server's 60s blocking window completed. New `ClusterHttpClient.setRequestTimeout(Duration)` is wired from `AetherCli.main` to honor `--request-timeout` (default 130s, > server-side 120s max). All five HTTP methods (`doGet`/`doPost`/`doPut`/`getDirect`/`postDirect`) apply the timeout
- **Test-infra: `pick_non_leader` and `kill_node` exclude pinned MGMT entry-point** — on cluster B (`docker-compose-b.yml`, `restart: "no"`), `node-1` is the pinned operator entry-point at port 5160. Previously chaos tests could select and kill it, leaving the mgmt endpoint dead and cascading failures across all subsequent cluster-B suites. New `mgmt_entry_point_node()` helper resolves the pinned node from env (default `node-1` for cluster B); `pick_non_leader` filters it out of candidates; `kill_node` refuses to kill it. `test-kill-leader.sh` skips fast (instead of failing) when the leader is the pinned node since cluster B has no safe rotation path
- **Test-infra: `cluster_node_count` reads `/api/cluster/generation`** — fixture-port-only polling missed CTM-provisioned nodes (overlay-only by default). Now reads `core.members[].nodeId` count + `core.desiredSize` from the leader-side generation snapshot. Combined with `wait_for_node_count_fast` (curl-based, avoids ~1-2s CLI cold-start per poll), scaling tests observe new core members as they join
- **Long-suspected peers stayed in topology, wedging consensus writes** — `TopologyObserver.initReconcile` now evicts any peer whose `state.failedAttempts` passes `BackoffConfig.shouldDisable` (default 4 retries) by routing a `TopologyManagementMessage.RemoveNode`. Without this, a CTM-provisioned node that was externally terminated (e.g. `docker rm -f`) without a clean shutdown stayed indefinitely in `nodeStatesById` as `SUSPECTED` when QUIC didn't produce a hard disconnect event. The phantom kept appearing in `activeNodes` used by `ClusterDeploymentManager.cleanupStale{NodeArtifact,NodeRoutes,Slice}Entries`, so its KV entries survived across destructive suites — by suite 13 the forwarding layer was racing against dead addresses and `cluster.apply(...)` on blueprint publish timed out with `Promise timed out after 10000ms`. Eviction routes `RemoveNode` through the standard path, which now cascades to the existing CDM per-node cleanup
- **CDM `cleanupStale*` runs on periodic reconcile** — previously only on `activate()` after a leader handoff, so if the leader stayed put through several suites, accumulated orphan slice/node-artifact/node-routes entries never got swept
- **CTM deficit hysteresis** — `ClusterTopologyManagerRecord.handleDeficit` defers the actual `provisionNodes` call by `autoHealConfig.retryInterval()` (10s default) after transitioning to `Reconciling`. At the end of the hysteresis window, `attemptProvisionAfterHysteresis` re-reads `observer.activeNodeCount()`: if the deficit healed (peer reconnected, handleAddNodeMessage cleared its tombstone and re-added it), transition straight to `Converged` without provisioning. Absorbs transient QUIC flaps that would otherwise inflate the cluster to `configured + 1` nodes while a surplus-detect cycle catches up
- **CTM over-provisioning after kill-under-load (#166)** — `TopologyObserver.initReconcile` re-added every configured core node that was missing from `nodeStatesById`, intended for transient-disconnect recovery. After an external `docker kill`, the dead peer got resurrected from `config.coreNodes()` on every 5s reconciliation tick while CTM provisioned a replacement, producing a 6-node cluster instead of the 5-node target. Added a `tombstonedNodes` set: `handleRemoveNodeMessage` records the peer, `initReconcile` and `handleDiscoveredNodes` skip tombstoned peers, `handleAddNodeMessage` (explicit re-add via QUIC Hello from a restarted container) clears the entry, and the drain-to-self reseed path also clears. CTM reconciliation switched from `observer.activeNodeCount()` to a new `observer.healthyActiveNodeCount()` that filters by `NodeHealth.HEALTHY`; `/api/cluster/topology.coreCount` and CTM's provision-tag `aether.peers` list now consult the same health-aware filter. `QuicClusterNetwork.onPostEstablishGraceComplete` / `onQuorumLossConfirmed` also emit a `TopologyChangeNotification.nodeRemoved` after routing deferred `RemoveNode`, so CTM re-reconciles at the moment the topology actually shrinks instead of waiting for the next random event
- **Canary test re-lookup of deployment ID** — `test-deploy-canary.sh` called `deploy_list` → `deploy_extract_id` in every stage. `deploy_list` filters by `Deployment::isActive`, so once a deployment reaches COMPLETED it disappears from the list and the re-lookup returns empty, failing the COMPLETE assertion. Test now captures `DEPLOYMENT_ID` from the start-response and reuses it across stages. Canary COMPLETE tolerates the already-COMPLETED case by verifying final state via `deploy_status`
- **Cluster B state pollution between destructive suites** — `self_heal` now calls `restore_baseline` before the 120s CTM-auto-heal wait. `restore_baseline` removes any CTM-provisioned `aether-core-*` containers and starts stopped compose nodes, so the next suite on cluster B starts against the canonical `node-1..5` topology instead of a mix of original + provisioned replacements whose drifting identities confused slice placement in 13-edge-cases
- **`aether` CLI hangs indefinitely on a wedged management endpoint** — `AetherCli.rawGet/rawPost/rawPut/rawDelete` called `httpOps.sendString(request).await()` against an `HttpRequest` with no `.timeout(...)`. When a server accepted the connection but never responded (e.g. management forward to a dead task-group owner whose internal retry exhausted without surfacing an error), the await blocked forever. New `--request-timeout=<seconds>` option (default 60s, 0 disables) attaches a `TimeSpan.timeSpan(N).seconds().duration()` timeout to every HttpRequest builder
- **Test-persistence schema migration silently skipped under shared PostgreSQL** — `aether_schema_history`'s `(version, type)` PK is global to the database, so when an example blueprint applied `V001__create_tables.sql` before `test-persistence`'s `V1__create_kv.sql`, the latter was treated as already-applied and `kv_store` was never created. Bumped to `V900__create_kv.sql` to avoid collisions with examples and standard test fixtures (proper namespacing tracked separately)
- **Slice-processor dropped path/query params in body-bearing routes** — `generatePathBodyRoute`, `generateQueryBodyRoute`, and `generatePathQueryBodyRoute` emitted `.to((key, body) -> delegate.method(body))`, discarding the path lambda arg. When a slice method took a single combined record (e.g. `PutRequest(String key, String value)`) against `PUT /{key}` + JSON body, the body-parsed record had `key=null`, surfacing later as a SQL not-null violation or equivalent. Generators now walk the param record's components in declaration order, matching path/query names against component names and emitting `body.<component>()` for the rest — `delegate.put(new PutRequest(id, body.name()))`. Compile-time error when a path/query name has no matching record component. `MethodModel.recordComponents(TypeMirror)` helper added
- **Cluster B leader election storm** — `QuicClusterNetwork.handleQuorumCandidate` previously short-circuited the 5s stabilization window with an "all peers connected — establishing quorum immediately" path. On a concurrently-starting cluster (e.g. compose nodes with no staggered `depends_on` gate), this fired `QuorumStateNotification.established` before transient QUIC flap settled, which then mutated the Rabia topology mid-round and left consensus stuck in `Phase[value=0]` — proposals kept submitting every 3s with no `onLeaderCommitted`. Now always waits `stabilizationWindow`, then starts a `postEstablishGrace` window that buffers single-peer REMOVE events in `pendingRemovals` (cleared on ADD, flushed as real RemoveNode on expiry). Restores consensus progress on concurrent-start clusters without affecting staggered-start clusters
- **Cluster scale-up wouldn't provision new nodes** — three-layer bug: (1) `cluster-config.toml` declared `[core_topology]` but `ClusterBootstrapConfigParser` reads `[cluster.core]`, so stored `coreMax` defaulted to the current count and any scale > 5 was rejected with "Invalid core max"; (2) `/api/cluster/scale` route is `taskGroup(SCALING)` and forwards to the SCALING owner, but `ClusterTopologyManager` only runs on the consensus leader — `setDesiredSize()` on the non-leader node's inactive CTM was a no-op. Added a KV listener on every node: on `ClusterConfigKey` put, propagate `coreCount` into `CTM.setDesiredSize(...)`; the leader's active CTM reacts and reconciles; (3) `DockerComputeProvider` hardcoded `network_name = "aether-network"` while multi-cluster integration tests use `aether-a-network` / `aether-b-network`, and published host ports `8080..8084` that collided with the seed cluster. Added `AETHER_DOCKER_NETWORK` env-var override (auto-propagated to provisioned children) and dropped host-port publishing for provisioned nodes — they are reachable via the docker network only, with management traffic forwarded through existing exposed nodes
- **Task reassignment rejected with NOT_LEADER** — `PUT /api/cluster/tasks/reassign/{group}` is `taskGroup(DEPLOYMENT)` routed, but `TaskAssignmentCoordinator.reassign()` short-circuited to `CoordinatorError.NOT_LEADER` unless called on the consensus leader. Outer coordinator now writes `TaskAssignmentValue` directly via `clusterNode.apply([Put])` — consensus replicates the change, leader's active coordinator picks it up through its existing notification path, and each node's `TaskGroupActivator` activates/deactivates components as the KV change lands
- **Integration test wait conditions** — `is_cluster_ready` now waits for `>= NODE_COUNT` nodes (not just quorum of 3), eliminating race where `cluster_node_count` returned 4 before topology fully populated. `discover_endpoints` fetches LB endpoints via `aether` CLI and probes reachability before accepting — falls back to direct node access when discovered LB hostname is only resolvable inside the cluster network. `run-tests.sh` made safe against empty `A_SUITES`/`B_SUITES` arrays under `set -u`
- **`test-persistence` blueprint** — `read` route migrated from `GET /stream` (mismatched against single-param method) to `POST /stream`; `resources.toml` now declares the `[database]` section required by the `PgSqlConnector` qualifier (flagged by PR #161's new resource-config validator). `RouteSourceGenerator` correctly handles zero-parameter methods (`.to(_ -> delegate.foo())` instead of `new FooRequest()`) in both `generateNoParamsRoute` and `resolveParameterType`
- **`EmberCluster.setClusterSize`** — annotated with `@Contract` to satisfy JBCT-RET-01 for intentional side-effectful void methods
- **Envelope format version** bumped to v8 for config update manifest entries
- **JSON injection in CLI** — `aether cluster migrate` now escapes user-supplied values in JSON request body
- **Config parse safety** — Environment integration factories wrap `Long.parseLong`/`Integer.parseInt` with `Result.lift()` to prevent node crash on malformed config
- **Thread safety** — Replaced non-thread-safe `EnumMap` with `ConcurrentHashMap` in QUIC outbound queues
- **Null policy** — `DeploymentManagerImpl` uses `Option<Version>` instead of raw null in domain logic; `parseThresholds` wrapped with `Result.lift()` fallback
- **Composition** — Replaced 4x `fold(() → default, id)` with `.or(default)` in CTM; simplified nested fold in `GovernorFailoverHandler`
- **Factory methods** — Added JBCT-compliant factories for `MigrationStep`, `MigrationError`, `PgStreamError`, `CloudCertificateProviderError` subtypes
- **Test assertions** — Fixed assertion-free drain test; replaced silent-pass `assertThat(cause).isNull()` pattern in 4 provider test files
- **TaskGroupActivator infinite loop** — Skip ACTIVE/FAILED terminal states in `onTaskAssignmentPut` to prevent activation write-back triggering re-activation
- **Docker socket permissions** — `group_add` for host Docker GID in compose + `--group-add` in DockerComputeProvider for provisioned containers
- **Docker network name** — Explicit `name: aether-network` in compose avoids project prefix; provisioned containers join the correct network
- **Container name collisions** — Nano-time suffix on provisioned container names prevents conflicts across test runs
- **NODE_ID_TAG mismatch** — `NodeLifecycleManager` used hyphens (`aether-node-id`) but Docker labels use dots (`aether.node-id`), preventing container termination during scale-down
- **ClusterConfigApplier not wired** — ManagementServer used `unused()` no-op applier; scale operations stored config but CTM never called `setDesiredSize()`. Now wires real applier via `ManageableNode.clusterTopologyManager()`
- **LB phantom topology nodes** — 3-part PEERS parsing in LB Main.java eliminates random NodeId generation that polluted cluster topology
- **Consensus sync cancelled under load** — `advancePhase()` unconditionally set engine state to Idle, cancelling sync tasks when Decisions arrived during synchronization. Now only transitions InPhase→Idle, preserving Syncing state. Root cause of provisioned node 180s+ activation delay under HTTP load
- **LB binary response corruption** — `sendResponse()` round-tripped response bodies through UTF-8 String, replacing every non-UTF-8 byte with U+FFFD (3 bytes). Corrupted artifact GETs and any binary response. Fixed to write raw bytes via `ResponseWriter.write()`
- **LB management API on dedicated port** — Management API forwarding now requires explicit `LB_MANAGEMENT_PORT` configuration; absence disables forwarding entirely. Prevents accidentally exposing management API on public client port. Default `LB_MANAGEMENT_MAX_CONTENT_LENGTH` = 2 MiB
- **Auth on forwarded requests** — QUIC-forwarded management requests now enforce the same `validateManagementSecurity` check as direct HTTP. Prevents auth bypass via LB management port
- **RequestRouter walk-back** — `findRoute()` iterates descending headMap entries instead of single `floorEntry`, fixing route resolution when sibling prefixes (e.g. `/api/streams/publish/`, `/api/streams/read/`) shadow the parent (`/api/streams/`)
- **Unknown route fallback** — LB forwards unmatched management routes to any core node (legacy/Maven repository routes) instead of returning 502. Node-side returns proper 404 `HttpResponseData` for truly unknown paths
- **Integration test overhaul v2** — Dual-cluster architecture (non-destructive parallel + destructive sequential), `run-tests.sh --env docker|remote|cloud` single entry point, suite metadata (`suite.conf` on all 16 suites), capability-based filtering, self-heal between destructive tests, LB endpoint discovery via cluster status API
- **Test blueprints** — Three purpose-built test slices: `test-echo` (stateless), `test-persistence` (PgSql + streaming), `test-full` (multi-slice + delegation). Built as Step 5 of `build.sh`
- **Environment templates** — TOML configs for docker, remote, and cloud-hetzner environments (A/B cluster pairs), dual-cluster Docker Compose files with shared PostgreSQL
- **Zero external dependencies** — Eliminated python3, jq, and bc from integration test infrastructure. Shell-native JSON parsing via `lib/json.sh`, awk for floating-point arithmetic, Aether CLI as primary API client
- **Hetzner IT test safety** — Explicit surefire exclusion of `*IT.java` in hetzner environment module prevents accidental cloud server creation during `mvn verify`
- **JBCT suppressions for jOOQ XML** — `IndentingXmlStreamWriter`, `JooqXmlExporter` (javax.xml interface implementations), `ExportJooqXmlMojo`, `CheckJooqXmlMojo` (Maven API contract) properly annotated with `@Contract`/`@SuppressWarnings`
- **Deploy-compose CTM cleanup** — `deploy-compose.sh` explicitly kills `aether-core-*` containers before every deploy. Auto-provisioned containers from CTM survive `docker compose down` and previously broke consensus on subsequent runs
- **Integration test deploy helpers** — Deploy start/promote/rollback/complete/list/status use LB-routed `api_post`/`api_get` instead of `aether_failover` (which silently returned error JSON on wrong-owner nodes). Strategy tests baseline v1 first, then publish v2 for upgrade
- **SecurityPolicy deny-by-default** — Unrecognized security policy values now default to `apiKeyRequired()` instead of silently falling through to `publicRoute()`. Prevents config typos from creating unauthenticated routes
- **SQL injection in LISTEN/UNLISTEN** — PostgreSQL channel names validated against `^[a-zA-Z_][a-zA-Z0-9_]*$` before interpolation into simple query protocol
- **InsecureTrustManagerFactory gated in QUIC transport** — Insecure TLS mode now requires `AETHER_INSECURE_DEV_MODE=true` env var. Default (no TLS config) returns an error instead of silently disabling certificate validation
- **InsecureTrustManagerFactory gated in PostgreSQL driver** — PG SSL connections default to JVM system trust manager. Insecure mode requires `pragmatica.pg.insecure-tls=true` system property
- **XXE protection in Maven XML parsing** — Full XXE hardening (disallow-doctype-decl, external entities, XInclude) in `MavenSettingsCredentials` and `MavenLocalRepoLocator`
- **SHA-256 artifact checksums** — Artifact verification upgraded from SHA-1 to SHA-256 primary with SHA-1 fallback. Missing checksums now fail the download instead of being silently skipped
- **Cloud config secret redaction** — `toString()` overridden on `HetznerConfig`, `AwsConfig`, `AzureConfig`, `GcpConfig`, `S3Config` to redact API tokens, secret keys, and private keys
- **Docker Compose random secret** — Fallback cluster secret uses `SecureRandom` 32-byte hex instead of hardcoded `"auto-generated-compose-secret"`
- **SSH image name validation** — Docker image names validated against safe pattern before interpolation into SSH commands, preventing command injection
- **API key file storage** — Bootstrap writes API key to `~/.aether/clusters/<name>/api-key` with 600 permissions instead of printing to stdout
- **STRONG consistency eviction guard** — `REJECT_WHEN_FULL` eviction policy for STRONG streams prevents consensus-committed events from being silently evicted. AHSE required for STRONG stream creation
- **Failover recovery wiring** — `StreamingCoordinator.activate()` triggers `GovernorFailoverHandler` for all streams on STREAMING task group activation. Replays events from AHSE segments + replica watermarks
- **Cross-node stream publish forwarding** — Producers on any node can publish to any partition via direct QUIC messages (`StreamForwardMessage`). No HTTP overhead — binary protocol with correlation tracking and 5s timeout
- **Consumer group coordination** — Automatic partition assignment using KV-Store-backed `ConsumerGroupCoordinator` (leader-side round-robin) + `ConsumerGroupRegistry` (read-side mirror). Join/leave/status management API endpoints
- **Sync replication acknowledgment** — `replicateAndAwait(minSyncReplicas)` waits for N replica acks before resolving. Configurable via `StreamConfig.minSyncReplicas`
- **Batch replication** — `ReplicationBatcher` accumulates events per partition (100 events or 1ms window) and sends single `ReplicateEvents` message. 10-50x reduction in QUIC message count
- **Consumer read-preference** — `ReadPreference` enum (GOVERNOR, ANY_REPLICA, NEAREST) routes reads to replicas for load distribution
- **Push notification for co-located consumers** — `OffHeapRingBuffer.appendListeners` invoke consumer callbacks immediately on append. Eliminates polling latency for same-JVM consumers (~1-10us)
- **Adaptive polling** — Consumer poll interval adapts 1ms-50ms: doubles on empty poll, resets to 1ms on data. Replaces fixed 50ms
- **Producer batching API** — `StreamPublisher.publishBatch(List<T>)` with `OffHeapRingBuffer.appendBatch()` for single eviction check and batch replication
- **Zero-copy consumer** — `OffHeapRingBuffer.readSlice()` returns `MemorySegment` view into buffer. No `toArray()` copy for co-located consumers
- **Push consumer cursor persistence** — `ConsumerRuntimeState` loads initial cursor from `CursorStore` and checkpoints every 1000 events or 30s
- **Segment compression** — LZ4 and ZSTD compression for sealed segments via existing `CompressionCodec` infrastructure. Configured per stream via `StreamConfig.compression`
- **Segment encryption** — AES-256-GCM encryption for sealed segments via existing `BlockEncryptor`. Configured per stream via `StreamConfig.encryptionKeyId`
- **Transactional cursor commits** — `PgTransactionalCursorCommit` wraps cursor UPSERT + business logic in single PostgreSQL transaction for exactly-once semantics
- **Compound retention policies** — `RetentionMode.ALL`/`ANY` combinators for time + count + size retention policies
- **Stream deletion API** — `DELETE /api/streams/{name}` endpoint
- **Consumer cursor/lag API** — `GET /api/streams/consumers/{name}` endpoint with partition offsets
- **Stream memory configuration** — `STREAM_MAX_MEMORY_BYTES` env var (default 128MB) + `aether.streams.memory.used.ratio` Micrometer gauge
- **Consumer timeout** — Auto-unsubscribe consumers idle for 60s
- **QUIC auto-reconnect** — `TopologyObserver` re-adds configured core nodes removed from topology on each reconciliation cycle. Fixes LB losing connections to restarted nodes
- **CTM env propagation** — `DockerComputeProvider` propagates `AETHER_INSECURE_DEV_MODE` and `AETHER_CLUSTER_SECRET` to provisioned containers
- **QUIC missing-peer reconciler** — `QuicClusterNetwork` now ticks every 5s and dispatches `connectPeer` for any configured peer absent from `connectedPeers()`. Recovers from container-recreation reconnect asymmetry where a recreated peer re-handshakes with N-1 peers but silently misses one (sticky SUSPECTED never clears via per-pong fan because no traffic flows). Per-peer jittered exponential backoff (5s initial → 60s cap) held on `PeerState`; `CONNECTING` / `REMOVED` / wrong-direction skipped; cancellable on shutdown. Validated end-to-end by smoke gate recovery on the remote integration suite
- **`swimHints` projection TTL (60s default)** — `HealthReconcilerContext.swimHints` map entries now decay after `swim_hints_ttl` (configurable via `[operations.auto_heal]`). Defense-in-depth so sticky SUSPECTED self-heals when transport recovery is delayed; aligns with the project invariant "state reconstructible from KV-Store" — the in-memory projection map no longer holds non-decaying state forever. SWIM's own SUSPECT/FAULT signals remain authoritative

### Changed
- **`build.sh`** — Exports `AETHER_INSECURE_DEV_MODE=true` for development builds

## [1.0.0-alpha] - 2026-04-04

### Added
- **CTM bidirectional convergence** — ClusterTopologyManager now reliably converges cluster to configured size in both directions: scale-up (provision) and scale-down (terminate). Separate `configuredSizeRef` (operator intent) from `desiredSizeRef` (working target). Node selection for termination: empty nodes first, then most recently joined, never self. CAS-based state transitions eliminate race conditions
- **DockerComputeProvider** — `ComputeProvider` SPI implementation for Docker-based cluster scaling. Provisions/terminates containers via Docker CLI, label-based instance discovery, atomic port allocation. Enables integration test scaling without cloud providers
- **PostgreSQL persistence adapter** — `@PgSql` type-safe persistence with compile-time SQL validation. Annotation processor validates `@Query` SQL and generates CRUD from method names (Spring Data conventions: `findBy*`, `save`, `insert`, `deleteBy*`, `countBy*`, `existsBy*`). Named parameter rewriting (`:param` → `$N`), query narrowing (`SELECT *` → explicit columns), record expansion for INSERT/UPDATE
- **PostgreSQL tooling (aether-pg-tools)** — SQL parser (PEG-based, ~500 rules), event-sourced schema model (25 event types), 41-rule migration linter (lock hazards, type design, schema design, migration practice), Java record/enum code generation from schema
- **pg-maven-plugin** — standalone Maven plugin for generating Java records/enums from PostgreSQL migration SQL files (`mvn pg:generate`)
- **`PgSqlConnector`** — PostgreSQL-specific marker interface extending `SqlConnector`, async-only factory routing (no JDBC/R2DBC fallback)
- **`@PgSql` qualifier** — resource qualifier annotation for persistence interfaces and slice factory parameters
- **`jbct add-persistence`** — CLI command to add PostgreSQL persistence support to existing projects
- **`--with-persistence` flag** — option on `jbct init` to scaffold persistence from the start
- **pg-showcase example** — demonstrates all persistence patterns: `@Query` with joins, CRUD auto-generation, record expansion, multi-table, projections
- **PostgreSQL persistence guide** — comprehensive developer documentation with setup, examples, validation rules

- **Compile-time validation stages 3-4** — parameter type checking against schema columns, return record field mapping against SELECT output, CRUD column existence validation, NOT NULL column coverage for insert/save, safe type coercion support
- **Record expansion wiring** — `VALUES(:request)` and `SET :request` patterns now expand record fields in generated SQL with accessor expressions in factory code
- **Migration manifest** — `pg-maven-plugin` generates `migrations.list` for reliable annotation processor schema discovery
- **JBCT file size limit** — `[files] maxFileSize` in `jbct.toml` (default 1MB) auto-skips grammar-generated parsers from format/lint
- **JBCT glob excludes** — `[files] excludes` in `jbct.toml` for explicit file pattern exclusion from format/lint
- **`@Contract` suppresses all JBCT rules** — marks Java API boundary methods (annotation processors, Maven Mojos) as exempt from JBCT lint
- **pg:lint Maven goal** — migration linting via `mvn pg:lint`, reports lock hazards, type design issues, schema design problems
- **Unified blueprint-level deployment** — single `aether deploy` command and `/api/deploy` endpoint replacing separate canary/blue-green/rolling-update commands. All deployment strategies operate on entire blueprints (all slices atomically), not individual slices
- **Unified deployment spec** — `aether/docs/specs/unified-deploy-spec.md` with complete API design

### Changed
- **Flow-based JBCT formatter** — completely replaced trivia-entangled CstPrinter with FlowPrinter that makes layout decisions from code structure + width measurement only. Eliminates all blank-line accumulation bugs by design. 0 non-idempotent files across 1,970-file codebase
- **DeploymentMap renamed** — `DeploymentMapImpl` → `IndexedDeploymentMap` (JBCT naming compliance)
- **Standalone example POMs** — `url-shortener` (1.0.0) and `url-shortener-v2` (1.0.1) decoupled from parent POM version, produce same `org.pragmatica.aether.example:url-shortener` artifact at different versions for deployment strategy testing
- **Aether Store branding** — PostgreSQL persistence adapter branded as "Aether Store" in all user-facing documentation
- **build.sh** — replaced `-q` with grep filtering, JBCT formatting warnings visible, no more stalls on large files
- **Format logging** — JBCT formatter now logs reformatted files at WARN level (was DEBUG)
- **url-shortener examples** — migrated from raw `@Sql`/`SqlConnector` to typed `@PgSql` persistence interfaces
- **Deployment CLI** — `aether deploy --canary`, `aether deploy --blue-green`, `aether deploy --rolling` replace `aether canary`, `aether blue-green`, `aether update`
- **Deployment REST API** — `/api/deploy` replaces `/api/canary/*`, `/api/blue-green/*`, `/api/rolling-update/*`
- **Resource reference docs** — added `PgSqlConnector` section with link to persistence guide

### Fixed
- **SchemaLoader migration discovery** — expanded from 1 suffix to 28 common descriptions, plus manifest-first approach
- **Table name resolution** — `OrderRow` → `orders` (was `order`), correct pluralization via schema lookup
- **INSERT with record params** — expands record fields (was using parameter name as column)
- **FQCN in generated code** — `java.lang.Long` → `Long`, inner types simplified in factory output
- **FactoryGenerator mapper typeArg** — `getObject()` calls now include class argument for Instant/BigDecimal
- **`Result.failure(cause)` → `cause.result()`** — 7 sites in SchemaBuilder, CodegenPipeline, RecordGenerator
- **Multi-statement lambdas** — 6 extracted to named methods across SchemaBuilder, DdlAnalyzer, linter rules, TypeMapper
- **SWIM double-start race condition** — atomic `starting` flag prevents two ESTABLISHED notifications from creating duplicate SWIM protocols; transport bind failure now aborts protocol creation
- **Slice processor @PgSql detection** — `ResourceQualifierModel.fromParameter()` now checks type-level annotations, not just parameter annotations; persistence interfaces correctly classified as resources
- **Slice processor factory wrapping** — generated code maps `PgSqlConnector` through `{Interface}Factory` when resource type differs from parameter type
- **PgSqlConnectorFactory SPI registration** — added to `META-INF/services/org.pragmatica.aether.resource.ResourceFactory`
- **Blueprint deploy classifier** — CLI and server auto-append `:blueprint` classifier when only `groupId:artifactId:version` given
- **Integration test node count** — `cluster_node_count()` uses health endpoint (QUIC peers) instead of metrics-based status endpoint
- **Integration test deploy flow** — push artifacts before deploy, use CLI for deployment with failover
- **Status endpoint node count** — uses live `connectedPeerIds()` instead of stale metrics-based count
- **CLI SLF4J warnings** — added `slf4j-nop` to CLI dependencies
- **Docker healthcheck** — uses `/health/live` (no auth required) instead of `/api/health`
- **Audit logging** — set to WARN level, suppresses debug auth success noise
- **QUIC reconnection storm** — root cause was 2-part PEERS format creating wrong NodeIds (`node-aether-node-X-6000`). Fixed with 3-part format (`nodeId:host:port`). Also: self-connection guards in `onPeerConnected` and `processViewChange`, self-exclusion from reconciliation loop, `connectingInProgress` dedup guard
- **Deploy list endpoint** — CLI `deploy list` used wrong path `/api/deployments` (404), corrected to `GET /api/deploy`
- **Deploy immediate field** — CLI `deployImmediate()` used `"blueprint"` field, corrected to `"artifact"` for `/api/blueprint/deploy`
- **DeployCommand JSON bodies** — CLI now sends correct nested JSON matching API's `DeployRequest` schema: nested `"canary"/"blueGreen"/"rolling"` strategy configs, nested `"thresholds"` object
- **Slice processor FQCN in provide() calls** — `generateResourceProvideCall` and plain interface factory params now use `ImportTracker` for simple names instead of fully-qualified class names
- **Integration test scripts** — fixed JSON field paths (08-http-client), OOM prevention with RPS cap (04-under-load), temp file race condition (13-concurrent-deploys), strengthened disruption budget assertions (13-disruption-budget), relaxed error rate threshold
- **Integration test helpers** — added missing `schema_status()`, `drain_node()`, `activate_node()` functions; drain endpoint uses path params not JSON body
- **Integration test TLS handling** — certificate-status and cert-rotation tests handle `NOT_CONFIGURED` state when TLS is disabled
- **Integration test load target** — cert-rotation load test uses management endpoint `/health/live` (was app endpoint)
- **Smoke test node count** — uses `>=` assertion to accommodate passive LB node
- **Status endpoint self-node** — `/api/status` now includes responding node in `cluster.nodes` list (was excluded, showing 4/5)
- **Deployment lifecycle** — `start()` auto-advances through PENDING → DEPLOYING → DEPLOYED; `complete` allowed from DEPLOYED, ROUTING, or PROMOTING states
- **Dashboard auth** — API key login overlay with sessionStorage; static files bypass auth; no data fetching until key validated; 401 handling doesn't interrupt login
- **Dashboard success rate** — normalizes server values (0-100) to client fraction (0-1) across all data sources (REST, WebSocket, seed)
- **Dashboard nodes/slices** — populates nodes from REST `cluster.nodes`; fetches slice details from `/api/slices`
- **Release workflow** — added `binutils` for `objcopy` in arm64 jlink build
- **k6 load test** — sends `X-API-Key` header for authenticated app endpoints
- **Storage management test** — handles empty `{}` response when no storage configured
- **JBCT formatter blank-line artifacts** — removed 13,911 blank lines across 91 aether files from previous formatter bug

### Removed
- **Separate deployment commands** — `aether canary`, `aether blue-green`, `aether update` removed (use `aether deploy --strategy`)
- **Separate deployment REST endpoints** — `/api/canary/*`, `/api/blue-green/*`, `/api/rolling-update/*` removed (use `/api/deploy`)

## [0.25.0] - 2026-04-01

### Added
- **Hierarchical Storage Engine (AHSE)** — Content-addressed block storage with tiered Memory + Disk hierarchy. Core library at `integrations/storage` (zero Aether deps), Aether adapter at `aether/aether-storage`. BlockId (SHA-256), MemoryTier (CAS-bounded), LocalDiskTier (sharded filesystem), StorageInstance (write-through + tier-waterfall reads), SingleFlightCache (read dedup), MetadataStore (in-memory + KV-Store backed), SnapshotManager (dual-trigger: mutation count + time interval, rolling pruning), StorageReadinessGate (startup sequencing with read/write barriers), per-instance TOML config (`[storage.*]` sections), ArtifactStore migration (chunks via StorageInstance), config-driven StorageFactory with node wiring, per-node REST API (`/api/storage`, `/api/storage/{name}`, `/api/storage/{name}/snapshot`), per-cluster REST API (`/api/cluster/storage`, `/api/cluster/storage/{name}`) with KV-Store status publishing, CLI commands (`aether storage list/status/snapshot`), 107 unit + integration tests
- **Streaming Phase 1 runtime** — `StreamPublisherFactory` and `StreamAccessFactory` (ResourceFactory SPI), `StreamPublisherImpl` with partition-key routing or round-robin, `PartitionedStreamAccess` with cross-partition fetch and consensus cursor checkpointing, `StreamConsumerAdapter` for single-event and batch handlers, `StreamConfigParser` for blueprint `[streams.xxx]` TOML sections
- **CDM stream integration** — stream creation from blueprint config during deployment, consumer subscription registration at slice activation via KV-Store, unsubscription on deactivation
- **QUIC certificate rotation** — `CertificateRenewalScheduler` wired to node startup, triggers at 60% remaining validity, exponential retry backoff (5min→4h cap), server restart on same port with atomic SSL context swap
- **HTTP server certificate rotation** — ManagementServer and AppHttpServer receive renewed bundles and restart with new TLS contexts (H1 + H3)
- **Certificate expiry observability** — `GET /api/certificate` endpoint, `aether cert status` CLI command, expiry timestamp and renewal status
- **QUIC per-stream backpressure queue** — bounded queue (100 per peer per stream type) replaces silent drop, drain on channel writable, queue depth metrics
- **Declarative cluster management** — `aether-cluster.toml` config format with `[deployment]` + `[cluster]` sections, config parser with 14 validation rules, diff engine with field-to-action matrix, `aether cluster` CLI (bootstrap, apply, status, export, scale, upgrade, drain, destroy, list, use, remove), cluster registry (`~/.aether/clusters.toml`), cloud-init user-data template, KV-Store config storage with optimistic concurrency, 5 management API endpoints
- **Gossip key rotation** — `RotatingGossipEncryptor` with VarHandle hot-swap, epoch-day versioned keys from `SelfSignedCertificateProvider`, KV-Store `GossipKeyRotationHandler` for cluster-wide key distribution, 24-hour dual-key overlap window
- **TLS operator guide** — comprehensive documentation: auto-generated certs, manual cert files, rotation lifecycle, monitoring, gossip encryption, troubleshooting
- **On-premises SSH bootstrap** — `SshBootstrapOrchestrator` with per-node Docker deployment, `DockerComposeGenerator` for single-host testing, `SystemdUnitTemplate` for JVM deployments, `RemoteCommandRunner` (ProcessBuilder SSH/SCP), `--compose-only` flag
- **Notification hub example** — two-slice example exercising streaming + per-route security + Principal injection end-to-end
- **PostgreSQL LISTEN/NOTIFY resource** — `PgNotificationSubscriber` with dedicated connection, multi-channel config (`[pg-notifications.xxx]`), `PgNotification(channel, payload, pid)`, annotation processor detection, comprehensive developer guide
- **Dashboard observability** — depth registry config UI (inline edit, add/remove rules) + invocation requests tab (sortable metrics, slow requests, traces, filters)
- **Integration test suite** — 14 suites, 56 Docker-based test scripts (smoke, stability, chaos, scaling, streaming, security, deployment, cluster-mgmt, resources, artifacts, database, observability, network, edge-cases)
- **Installation binaries** — jlink custom JRE + shaded JAR bundles for node/cli/forge, multi-platform archives (linux-amd64, linux-arm64, darwin), platform-aware install.sh/upgrade.sh

- **Streaming Phase 2** — Governor-push replication (fire-and-forget with watermark tracking), strong consistency (Rabia consensus produce path for total ordering), sealed segment pipeline (EvictionListener → SegmentSealer → StorageSegmentSink → SegmentReader), consumer read-preference (LEADER/NEAREST/FOLLOWER_ONLY), governor failover recovery (watermark-based replica catch-up), tier-aware retention (aggressive post-seal eviction)
- **AHSE Phase 2** — RemoteTier (S3-backed StorageTier with SigV4 REST client), ContentStore (auto-chunking API with manifest blocks, compression integration), DemotionManager (4 eviction strategies: AGE/LFU/LRU/SIZE_PRESSURE, dormant/active lifecycle), StorageGarbageCollector (orphan collection with grace period, dormant/active), PromotionManager (frequency-based cold-to-hot promotion), write-behind policy (async slow-tier writes with bounded queue), cross-node prefetching (SWIM-piggybacked access hints)
- **AHSE Phase 3** — LZ4/ZSTD compression pipeline, AES-256-GCM block encryption, StorageBackedPersistence (ContentStore-backed RabiaPersistence)
- **S3 REST client** — SigV4-signed S3-compatible client in `integrations/cloud/aws/s3` (PutObject/GetObject/DeleteObject/HeadObject/ListObjectsV2, path-style MinIO support)
- **Architectural compliance** — dormant/active lifecycle on all background workers, KV-Store persistence abstractions (WatermarkStore, ReplicaAssignmentStore, TombstoneStore), SegmentIndex rebuild from storage refs, control-plane delegation investigation updated
- **Integration test metrics** — opt-in thread/heap/RSS collection before+after each test (`COLLECT_METRICS=true`)
- **Integration test README** — comprehensive setup guide, architecture docs, test-writing examples
- **Soak test exclusion** — `SKIP_SOAK=true` (default) in `run-all.sh` and `run-suite.sh` to skip long-running soak tests

### Removed
- **`-XX:+ZGenerational`** — removed from all JVM configurations (Java 25 makes generational ZGC the default)
- **`-XX:+UseCompactObjectHeaders`** — removed from all JVM configurations (no measurable impact in benchmarks)

### Changed
- **java-peglib 0.2.1** — parser regenerated, all 35 lint rules updated for new CST shape (ordered-choice container wrapping)
- **ConsumerConfig** — added `checkpointIntervalMs`, `maxRetries`, `deadLetterStream` fields (backward compatible)
- **StreamConfig** — added `maxEventSizeBytes` field with enforcement in `StreamPartitionManager.publishLocal()`
- **Nullable AtomicReference eliminated** — `CancellableTask` (VarHandle, 9 usages), `StoppableThread` (VarHandle, 4 usages), `AtomicHolder<T>` (VarHandle, 4 usages) in `core/` replace all `getAndSet(null)` patterns
- **Docker image base** — switched from `eclipse-temurin:25-alpine` to `eclipse-temurin:25-noble` (glibc required by netty-quiche native library)
- **SSH bootstrap** — Docker bridge network with container hostnames instead of `--network host`, env-var-based config (PEERS, CLUSTER_PORT, MANAGEMENT_PORT), `$HOME/aether` paths instead of `/opt/aether`
- **Docker config** — `repositories = ["builtin"]` (DHT is fully distributed; `local` fallback removed)
- **Integration test assertions** — cluster health checks use `/api/status` instead of `/health/ready` and `/api/nodes`
- **CLI global output formatting** — `--format` (json/table/value/csv), `--field` (dot-notation extraction), `--quiet`, `--no-color` / `NO_COLOR` env var on all ~100 commands via picocli mixin
- **CLI Jackson migration** — replaced hand-rolled JSON parsing with `JsonMapper` tree API; deleted `SimpleJsonReader`, `formatJson()`, `extractJsonString()` and duplicates
- **CLI standardized exit codes** — `SUCCESS=0`, `ERROR=1`, `TIMEOUT=2`, `NOT_FOUND=3` across all commands
- **CLI TLS support** — `--tls-skip-verify` / `-k` flag with trust-all SSL; scheme-aware URL resolution
- **CLI shell completions** — `aether generate-completion` for bash/zsh/fish; auto-install in `install.sh`
- **JsonMapper tree API** — `readTree()`, `extractField()`, `prettyPrint()` methods added to jackson integration module

### Changed
- **Cluster-wide `/api/slices`** — returns all slices across all nodes with per-node instance states, target counts, and version; old per-node behavior moved to `/api/node/slices`
- **Per-node route endpoint** — `/api/routes` moved to `/api/node/routes` for naming consistency
- **CLI `slices` command** — now shows cluster-wide view; added `node-slices`, `routes`, `node-routes` commands

### Added
- **Standalone passive load balancer** — `aether-lb.jar` shaded binary with `--peers`, `--http-port`, `--cluster-port` CLI args, joins cluster as PassiveNode, routes HTTP via binary protocol; includes Dockerfile
- **Passive node KV-Store snapshot sync** — passive nodes (LB) receive full KV-Store state on cluster join via `KVSyncRequest`/`KVSyncResponse`; LB works regardless of when it starts relative to blueprint deployment
- **Stream auto-creation on publish** — `POST /api/streams/{name}/publish` auto-creates stream with default config if it doesn't exist; follows Kafka `auto.create.topics.enable` pattern
- **Stream creation endpoint** — `POST /api/streams` for explicit stream creation with configurable partition count

### Changed
- **java-peglib 0.2.0** — PEG parser generator bumped from 0.1.8, Java25Parser regenerated (-2,940 lines net)

### Fixed
- **CLI version** — was hardcoded at 0.19.2, now correctly shows 0.25.0
- **`@CodecFor` annotation processor** — two-pass processing: register all types first, then generate codecs (fixes ordering issues); generates codecs for external records and enums
- **Java 25 ambiguous method reference** — `ListenNotifyTest.subscribe()` lambda → method reference for stricter overload resolution
- **Streaming API** — REST publish failed with "Stream not found" because streams were only created lazily by slice factories, not available via management API
- **Stream publish payload** — changed from base64-only to raw UTF-8 string for simpler management API usage
- **Stream memory allocation** — management API streams use 16MB default (was 1GB per stream, crashing containers)
- **Stream memory cap** — `StreamPartitionManager` enforces 128MB global off-heap cap, rejects new streams when exceeded instead of OOM crash
- **Idle stream reaper** — `reapIdleStreams()` destroys empty streams past retention age, freeing off-heap memory
- **Integration test `api_post`/`app_post`** — bash brace expansion bug `"${2:-{}}"` appended extra `}` to all POST bodies
- **Integration test suite** — API key auth (`aether-integration-test-key` default), correct url-shortener endpoints (`/api/v1/urls/`), stream payload format (`data` as string), stream info JSON parsing, concurrent deploy test, reduced streaming load duration (30s from 300s)
- **Autoscaler noisy log** — scale-down rule logged at INFO every 5s even when blocked by min-instances guard; changed to DEBUG
- **Java 25 TLS compatibility** — RSA self-signed certs for dev mode, BouncyCastle PEMParser for EC key loading (preserves named curve encoding for BoringSSL), explicit BC KeyFactory in `SelfSignedCertificateProvider`
- **Schema migration lock failover** — new leader scans for MIGRATING schemas with expired locks and resets to PENDING
- **Dashboard schema retry button** — FAILED migrations can be retried from dashboard UI
- **Certificate rotation race condition** — SSL contexts updated before server stop, eliminating null-server window

## [0.24.1] - 2026-03-25

### Added
- **ClusterTopologyManager** — new node lifecycle manager with reconciliation state machine (FORMING → CONVERGED ↔ RECONCILING). Handles auto-heal, scale-up/down, quorum safety. Replaces fragile boolean flags in CDM with clean state transitions. Single action path for all node count changes
- **Consensus-driven topology discovery** — Hello handshake carries node address; ON_DUTY lifecycle notifications trigger topology additions. Dynamically provisioned nodes become visible to all cluster members via consensus, not just the provisioning leader
- **Per-route security** — routes.toml `[security]` section with per-route policies (public/authenticated/role:name), type-safe `RouteSecurityPolicy` interface with `canAccess()`, `SecurityPolicy` sealed variants in Aether, route-level enforcement in AppHttpServer (per-route wins over global SecurityMode)
- **Principal/SecurityContext injection** — slice handler methods can declare `Principal` or `SecurityContext` parameters; code generator injects from `SecurityContextHolder` automatically
- **Blueprint security overrides** — operators can override route security at deploy time via `[security.overrides]` in blueprint.toml with `strengthen_only`/`full`/`none` policies
- **QUIC transport metrics** — `QuicTransportMetrics` with active connections, handshakes, messages sent/received, write failures, backpressure drops; exposed via `/api/metrics/transport`
- **Per-route request metrics** — Micrometer counters and timers per route pattern; security denial counters with denial type classification
- **Dashboard route security badges** — Routes panel on Deployments page shows security policy per route
- **Config validation warnings** — blueprint parser warns on unrecognized TOML sections
- **Streaming lifecycle operations spec** — §16 added to in-memory streams spec: replica count change, repartitioning, stream deletion, migration patterns

### Changed
- **Topology management refactored** — `TcpTopologyManager` renamed to `TopologyObserver` (pure observation); new `ClusterTopologyManager` wraps observer and manages cluster size. CDM no longer owns node provisioning
- **`NodeLifecycleValue` carries address** — host/port included in ON_DUTY registration for consensus-driven node discovery (backward compatible with old format)
- **`RouteSecurityPolicy` renamed to `SecurityPolicy`** — moved from transport-level to intent-based (Public, Authenticated, ApiKeyRequired, BearerTokenRequired, RoleRequired); extends generic `RouteSecurityPolicy` from http-routing layer
- **`[security]` section optional** — routes.toml without `[security]` defaults to PUBLIC with STRENGTHEN_ONLY policy (backward compatible)
- **Security validators handle all policy variants** — ApiKeySecurityValidator and JwtSecurityValidator now handle Authenticated and RoleRequired in addition to their primary types
- **Route security in KV-Store** — `NodeRoutesValue.RouteEntry` carries security field; serialization is backward compatible with old format

### Fixed
- **Node auto-heal** — killed nodes automatically replaced via ComputeProvider; batch provisioning proportional to deficit; quorum safety (never below 3); ON_DUTY health check before considering provision complete; leader failover detects ready nodes via consensus
- **Node departure healing** — SWIM FAULTY routes `RemoveNode` to topology manager; QUIC disconnect routes `RemoveNode` for passive LB; CDM rebuilds state before cleanup; sequential reconciliation prevents consensus batch collisions
- **Reconnection storm eliminated** — `ConnectionFailed` routed to topology manager for exponential backoff; reconciliation loop is sole reconnection driver; new nodes bypass ConnectionDirection for initial join
- **QUIC write failures detected** — `writeAndFlush()` listener detects failures, removes stale links, triggers reconnection
- **QUIC DataHandler error containment** — `exceptionCaught()` closes channel; deserialization wrapped in try-catch to prevent single malformed message from killing connection
- **QUIC write backpressure** — writability check before write; `WriteTimeoutHandler(10s)` in stream pipelines
- **QUIC Hello deserialization safety** — try-catch in both server and client Hello handlers
- **SecurityMode=NONE + authenticated route** — returns clear 401 "Route requires authentication but no security mode is configured" instead of vague error
- **WWW-Authenticate header** — no longer sent when SecurityMode=NONE (was misleadingly advertising ApiKey)
- **WebSocket auth timeout** — sends AUTH_TIMEOUT message before closing instead of silent disconnect
- **Overlapping route detection** — compile WARNING when two routes have same method+path pattern
- **Invocation metrics strategy** — returns 501 Not Implemented with clear message; CLI explains limitation

## [0.24.0] - 2026-03-24

### Added
- **QUIC cluster transport** — replaces TCP for all inter-node communication. Stream-per-message-type multiplexing (consensus stream 0, KV stream 1, HTTP forward stream 2, DHT stream 3), mandatory TLS 1.3 with auto-generated self-signed certs for dev, 0-RTT reconnection, connection migration, NodeId-ordered connection initiation. First Java distributed runtime on QUIC
- **Soak test infrastructure** — 4-hour k6 sustained load scenario with chaos injection phases (worker kill, rolling restart), Prometheus + Grafana monitoring with 14-panel auto-provisioned dashboard, automated pass/fail verdict (6 criteria: heap growth, GC pause, P99 drift, error rate, SWIM stability, node count), markdown report generation

### Changed
- **SWIM port offset** — changed from cluster_port+1 to cluster_port+100 to avoid port collisions in multi-node Forge
- **Passive LB** — uses own event loop groups instead of TCP server groups (QUIC has no TCP server)

### Fixed
- **QUIC message delivery** — DataHandler replaces Hello handler after handshake; messages were silently dropped post-Hello
- **QUIC message framing** — added LengthFieldBasedFrameDecoder to QUIC stream pipelines; QUIC streams are byte-oriented like TCP, not message-framed
- **QUIC message routing** — incoming messages now routed to MessageRouter via onMessageReceived callback
- **QUIC idle timeout** — disabled per RFC 9000 §10.1; peer-to-peer connections died after 30s of no traffic between consensus rounds (only leader→peer had regular MetricsPing traffic)
- **Passive LB event loop** — uses own groups instead of TCP server groups (QUIC has no TCP server)

## [0.23.1] - 2026-03-24

### Added
- **AppHttpServer: configurable request size limits** — `max_request_size` in TOML with `DataSize` parser (KB/MB/GB), 413 response when exceeded
- **AppHttpServer: multipart file upload** — `FileUpload`, `MultipartRequest` records, Netty `HttpPostRequestDecoder` integration, `RequestContext.multipartRequest()` accessor
- **AppHttpServer: API token auth** — `SecurityMode` config (none/api-key/jwt), reuses management RBAC infrastructure, `SecurityContextHolder` ScopedValue propagation to slice handlers, health endpoint bypasses auth
- **AppHttpServer: JWT auth with JWKS** — `JwtSecurityValidator`, `JwtTokenParser`, `JwtSignatureVerifier` (RS256/ES256), `JwksKeyStore` with TTL cache, clock skew tolerance — pure JDK crypto, no external JWT libraries
- **AppHttpServer: HTTP/3 via Netty QUIC** — `Http3Server` with dual-stack H1+H3 support, `QuicSslContextFactory`, `Alt-Svc` header for protocol upgrade hints, `HttpProtocol` config enum
- **Dashboard: new panels** — schema migration status, governor/community, deployment strategies (canary/blue-green/A/B), streams, cluster composition (core/worker counts)
- **Operational audit events** — 7 event types in cluster event stream (AccessDenied, NodeLifecycleChanged, ConfigChanged, BackupCreated/Restored, BlueprintDeployed/Deleted)
- **`@CodecFor` annotation** — compile-time + runtime codec validation for external types. Manual codecs required (no auto-generation), `REQUIRED_TYPES` validated at startup. Three-layer safety net: compile-time field check, `@CodecFor` declaration, runtime startup validation. Eliminates silent serialization failures permanently
- **Codec processor compile-time field validation** — ERROR for `@Codec` records with unregistered field types
- **ManagementServer HTTP/3** — dual-stack H1+H3 support matching AppHttpServer, `management_protocol` TOML config
- **NettyHttpOperations** — HTTP/1.1 + HTTP/3 client via Netty QUIC, alternative to JDK HttpClient. Full HTTP/3 stack (server + client) complete
- **Manual codecs for core types** — TimeSpan, Email, Url, NonBlankString, Uuid, IsoDateTime registered in NodeCodecs via `@CodecFor` with hand-written codecs

### Fixed
- **Dashboard: 24 audit issues** — alert data unwrapping, ALERT_RESOLVED broadcast, INITIAL_STATE node population, per-node metrics, real P50/P95/P99 percentiles, time range selector, WS auth, REST auth headers, error toasts, per-channel WS status, topology diffing, success rate chart Y-axis, latency panel after tab switch
- **JBCT compliance: 40+ issues** — constant-time API key comparison, generic error messages to clients, unknown role defaults to VIEWER, Result.lift for exception boundaries, Option for null policy, AtomicReference for thread safety, @Contract for lifecycle methods
- **`@Codec` on `AuthorizationRole`** — fixes serialization failure in HTTP forwarding
- **Pre-existing codec issues** — TimeSpan, MethodName, ExecutionMode, KVCommand, Blueprint, NodeLifecycleState, SchemaStatus all now have proper codec registration via `@Codec` or `@CodecFor`

## [0.23.0] - 2026-03-23

### Added
- **In-memory streaming (preview)** — ordered, replayable, consumer-paced streaming as a first-class Aether resource
  - `StreamPublisher<T>`, `StreamSubscriber`, `StreamAccess<T>` — slice-developer API with `@PartitionKey` annotation for partition routing
  - `OffHeapRingBuffer` — off-heap ring buffer using `MemorySegment` with circular wrap-around and retention eviction (count/size/age)
  - `StreamPartitionManager` — governor-local produce/consume with per-stream partition management
  - Annotation processor: detects stream resources, generates manifest entries, envelope format v7
  - `StreamConsumerRuntime` — push-based delivery with RETRY (exponential backoff), SKIP, and STALL error strategies
  - `DeadLetterHandler` — in-memory dead-letter storage for failed events
  - REST API: `GET /api/streams`, `GET /api/streams/{name}`, `POST /api/streams/{name}/publish`, `GET /api/streams/{name}/{partition}/read`
  - CLI: `aether stream list`, `aether stream status`, `aether stream publish`
  - KV-Store types for stream metadata, partition assignments, cursor checkpoints
  - 140+ tests across the streaming stack

## [0.22.0] - 2026-03-23

### Added
- **RBAC Tier 2 — role-based authorization** — three hierarchical roles (ADMIN/OPERATOR/VIEWER) with per-route enforcement in the management API pipeline. RoutePermissionRegistry resolves permissions by HTTP method and path prefix. 403 Forbidden for authorization failures. TOML config `authorization_role` field on API keys (defaults to ADMIN for backward compat). Independent security audit passed clean — all 40+ mutation routes verified
- **Operational audit events in cluster event stream** — 7 event types (AccessDenied, NodeLifecycleChanged, ConfigChanged, BackupCreated, BackupRestored, BlueprintDeployed, BlueprintDeleted) routed through ClusterEventAggregator alongside existing DeploymentEvent and SchemaEvent
- **Audit trail expansion** — AuditLog calls added to all mutation paths: schema migration lifecycle, CDM scaling decisions, config changes, backup/restore, node lifecycle transitions, blueprint deploy/undeploy

### Changed
- **Feature catalog updated** — reflects 0.21.1/0.21.2 additions, backup/restore contradictions resolved, statistics updated (145 features: 24 battle-tested, 113 complete)

## [0.21.2] - 2026-03-22

### Added
- **Schema migration failure recovery** — automatic retry with exponential backoff (5s/15s/45s) for transient failures, manual retry via `POST /api/schema/{ds}/retry` and `aether schema retry` CLI command
- **Schema migration events** — structured `SchemaEvent` hierarchy (MigrationStarted, MigrationCompleted, MigrationFailed, MigrationRetrying, ManualRetryRequested) with natural language explanations suitable for both human operators and LLM agents
- **Failure classification** — transient (connection timeout, lock contention) vs permanent (SQL syntax, checksum mismatch) with appropriate retry behavior
- **`schema_required` blueprint config** — `[deployment]` section option to skip schema migration gate, allowing slices that don't need schema to deploy immediately

## [0.21.1] - 2026-03-22

### Added
- **Docker scaling test infrastructure** — 5-core + 7-worker Docker Compose setup with phase-based orchestrator, k6 load tests (steady-state + scaling verification), Maven protocol artifact upload
- **CORE_MAX env var** — Docker containers configure core/worker role via environment variable instead of per-node TOML
- **X-Node-Id header on all HTTP responses** — enables k6 to verify traffic distribution across nodes
- **SWIM startup delay** — configurable cooldown after quorum (default 10s) before first probe, allowing TCP connections to stabilize
- **SWIM revival grace period** — recently-revived members skip probing for configurable duration (default 5s)

### Changed
- **SharedScheduler consolidation** — migrated 10 production schedulers to SharedScheduler (min 8 platform threads), eliminating thread pool proliferation across SWIM, CircuitBreaker, Retry, canary evaluation, and heartbeat
- **SWIM transport uses Netty built-in DnsNameResolver** — replaced custom DomainNameResolver with Netty's native DNS resolver, eliminating Promise chain overhead in the send path. DNS resolution stays entirely within Netty's event loop
- **SWIM logging levels** — recv messages at TRACE (was INFO), SUSPECT/FAULTY at WARN (was INFO)
- **SwimConfig uses TimeSpan** — replaced Duration with TimeSpan, relaxed defaults for Docker (period=1s, probeTimeout=800ms, suspectTimeout=15s)
- **PiggybackBuffer dissemination counting** — changed from drain-on-read to peek-and-age with configurable max disseminations, preventing premature update loss

### Fixed
- **InetSocketAddress codec missing** — no codec was registered for `InetSocketAddress`, causing silent serialization failure for ALL SWIM Ping/Ack messages with piggybacked membership updates. Every probe timed out, causing universal SUSPECT cascade. Root cause of all SWIM flapping
- **SWIM relay sequence collision** — relay Pings reused original requester's sequence number, colliding with local probes. Fixed with dedicated relay sequence and RelayInfo mapping
- **SWIM PingReq sender address** — handlePingReq looked up requester from member list (hostname-based, possibly missing). Fixed by passing actual UDP sender address
- **SWIM relay cleanup** — age-based expiry instead of pendingProbes presence check (which removed ALL relays since relay sequences are never in pendingProbes)
- **SWIM state priority enforcement** — FAULTY > SUSPECT > ALIVE at same incarnation prevents stale ALIVE piggyback from overriding SUSPECT
- **SWIM round-robin probing** — deterministic member selection instead of random, ensuring all members probed equally
- **SWIM FAULTY member cleanup** — bounded growth with 3× suspectTimeout eviction threshold
- **SWIM incarnation bump on Ack** — prevents stale SUSPECT piggyback from re-suspecting a node that just responded
- **Schema migration concurrency** — local deduplication via inFlightMigrations Set prevents duplicate migrations from concurrent KV-Store notifications
- **AppHttpConfig wiring** — Main.java now reads `[app-http]` TOML section and calls `withAppHttp()`
- **ConfigurationProvider wiring** — Main.java now builds and wires ConfigurationProvider from TOML file
- **Missing SqlConnector factories in node JAR** — added resource-db-async and resource-db-jdbc dependencies
- **GossipEncryptor race condition** — resolved at quorum time instead of assembly, when certificate provider is initialized

## [0.21.0] - 2026-03-21

### Added
- **Per-datasource schema migration engine** — full migration execution engine with Flyway-style versioned (V), repeatable (R), undo (U), and baseline (B) migration types. Schema history tracked in `aether_schema_history` table per datasource. Checksum validation, transactional per-script execution, configurable failure/failover policy
- **Schema orchestration** — distributed coordination layer with consensus-based locking, artifact resolution, and status tracking (PENDING → MIGRATING → COMPLETED/FAILED). CDM integration gates slice deployment on schema readiness
- **Schema management REST API and CLI** — REST endpoints (`/api/schema/status`, `/api/schema/migrate/{ds}`, `/api/schema/undo/{ds}`, `/api/schema/baseline/{ds}`) and CLI commands (`aether schema status|history|migrate|undo|baseline`)
- **Schema directory convention** — `schema/` root maps to default `[database]` config section (matching `@Sql`), subdirectories map to `[database.<name>]` sections. Single-datasource slices need no subdirectory
- **Schema migration executes end-to-end** — DatasourceConnectionProvider provisions SqlConnector per datasource, wiring migration engine to actual database execution
- **Strict datasource resolution** — missing config section causes explicit failure with descriptive error; no silent fallback or derivation
- **Removed embedded H2 from Forge** — Forge no longer provides an embedded H2 database; external PostgreSQL required via `start-postgres.sh`
- **Schema migration prerequisites** — `start-postgres.sh` scripts create the required database; migration engine requires pre-existing databases (creates tables, not databases)
- **Blueprint artifact auto-packaging** — `generate-blueprint` goal now automatically packages the blueprint JAR (no need to add `package-blueprint` explicitly). Schema directory default changed to `${project.basedir}/schema`
- **Forge artifact-based deployment** — `--blueprint` accepts artifact coordinates with classifier (`groupId:artifactId:version:classifier`). Forge resolves via configured Repository chain (local Maven repo in dev, DHT in production). TOML deployment path removed
- **Enriched `/api/nodes` endpoint** — now returns role (CORE/WORKER) and isLeader flag per node, with role sourced from `ActivationDirectiveValue` in KV-Store
- **`GET /api/cluster/governors` endpoint** — exposes governor announcements from KV-Store: governor ID, community, member count, and member list

### Deployment Strategies
- **Canary deployments** -- Progressive traffic shift with configurable stages (1% -> 5% -> 25% -> 50% -> 100%), auto-evaluation every 30s, health-based auto-rollback, KV-Store persistence, leader failover recovery
- **Blue-green deployments** -- Atomic traffic switchover (~100ms via single Rabia round), drain period, instant switch-back for rollback, 2x resource usage during transition
- **A/B testing** -- Deterministic traffic split by request context (header hash, cookie hash, header match, percentage), ScopedValue-based variant propagation through invoke chains, per-variant metrics collection
- **Deployment strategy coordinator** -- Mutual exclusion (one strategy per artifact), unified routing lookup for all strategies
- **HTTP version-aware routing** -- AppHttpServer checks deployment strategy routing before serving locally, forwards to remote node when weighted decision routes to other version
- **Blueprint deployment config** -- Optional `[deployment]` TOML section for strategy selection and configuration

### Changed
- **Deployment event aggregator — KV-Store driven** — deployment events (STARTED/COMPLETED/FAILED) now derived from `NodeArtifactKey` KV-Store notifications instead of manually injected local messages. All nodes see all deployment events. Deployment duration tracked from LOAD→ACTIVE, node join-to-first-deployment timing included
- **Jackson 3.1.0 LTS** — bumped from 3.0.3, annotations from 2.20 to 2.21
- **JBCT review compliance** — SharedScheduler for canary evaluation (was shutdownNow), AtomicBoolean for SliceInvoker.stop(), immutable FailoverContext collections, AB→Ab rename (acronym-as-word), factory methods for all value objects, Option for null policy, deployment audit logging via AuditLog, void helper suppressions
- **Role-aware unified AetherNode** — merged WorkerNode into AetherNode. Single `aether-node.jar` binary for both CORE and WORKER roles. Consensus observer mode (receives Decisions without voting), `ForwardingClusterNode` for transparent KV write forwarding, `SwitchableClusterNode` for runtime role switching. WORKER→CORE promotion supported. `aether/worker` module eliminated — components ported to `aether/node` and `aether-metrics`
- **Quorum fix for mixed clusters** — when `coreMax > 0`, consensus quorum calculated against core node count only (not total nodes including workers)
- **KV-commit-driven allocation/deallocation** — slice allocation and deallocation now triggered exclusively by KV-Store commit notifications (`onSliceTargetPut`/`onSliceTargetRemove`), eliminating double-allocation race in blueprint handler
- **ReconciliationAdjustment events** — CDM emits scaling events to cluster event stream when reconciliation adjusts instance counts

### Fixed
- **Deployment flow audit** — comprehensive CDM/NDM handoff audit: schema migration gate blocks ACTIVATE until migrations complete, exclusive schema lock acquisition prevents split-brain races, allocation index bounds check prevents IOOBE, drain eviction excluded from reconciliation, retry counters scoped to (artifact, node), optimistic sliceStates write removed, stuck timeout multiplier increased to 3×, blueprint stores combined into single consensus batch
- **Timeout failure misclassification** — `updateSliceStateWithRetry` re-classified already-classified failures through a string round-trip, converting transient timeouts (`CoreError.Timeout` → `Intermittent`) into fatal errors (`Fatal.UnexpectedError`). Pre-classified `failureReason` and `fatal` flag now passed directly to `NodeArtifactValue`
- **Consensus pipeline saturation during activation** — all consensus operations in NDM activation chain (topic subscriptions, scheduled tasks, endpoints, cleanup) now use `applyWithRetry` with 30s timeout × 2 retries, matching state transition retry behavior. Previously only `updateSliceStateWithRetry` had retry logic; bare `cluster.apply().timeout()` calls would fail under multi-slice deployment load
- **JBCT compliance across deployment subsystem** — factory methods for `SliceNodeKey`, `SliceDeployment`, `SuspendedSlice`, `ParsedArtifactCoords`; null checks replaced with `Option.option()`; multi-statement lambdas extracted to named methods; `create*Command` renamed to `build*Command`; `seedNodes` changed to `Set.copyOf()`; blueprint iteration snapshot in reconcile; `fold()` replaced with `.map().or()`
- **`coreMax` config wiring** — `core_max` from TOML `[cluster]` section now threaded through ConfigLoader → AetherConfig → AetherNodeConfig → TopologyConfig. Previously always defaulted to 0 (unlimited), preventing worker node assignment
- **Blueprint artifact resolution** — `publishFromArtifact` resolves via configured Repository chain (local Maven, DHT) with explicit classifier support. Clear error on missing classifier
- **Leader election reliability** — `triggerElection()` now defers with retry when called before LeaderManager is active, instead of silently dropping. Fixes flaky leader election in Forge (single-JVM multi-node) where rank-0 node's trigger was lost due to startup race
- **NDM promise chain ordering** — failure/success handlers in loading, activation, deactivation, and unloading chains changed from `onFailure`/`onSuccess` (async) to `withFailure`/`withSuccess` (sequential), preventing state write races
- **Activation timeout alignment** — ACTIVATING stall timeout (90s) and NDM activation chain timeout (90s) aligned; stall detector fires at 3 min (2× multiplier), after NDM has had time to fail and write FAILED state
- **Consensus operation timeouts** — all `cluster.apply()` calls in NDM now have 15s timeout, preventing orphaned Rabia proposals from hanging activation chains forever
- **Double slice allocation** — blueprint handler no longer allocates directly; allocation deferred to `onSliceTargetPut` notification, fixing race where 5 instances were created instead of 3
- **Multi-phase allocation double-write** — `tryAllocate()` now optimistically tracks allocations in `sliceStates`, preventing Phase 2/3 of `issueScaleUpCommands` from re-allocating nodes already assigned in Phase 1 (async `cluster.apply()` hadn't committed yet)
- **Blueprint deletion deallocation** — `handleAppBlueprintRemoval()` now issues deallocation commands before removing artifacts from `blueprints` map; previously deferred to `onSliceTargetRemove` which couldn't find the artifacts because they were already removed
- **SliceState ACTIVATING timeout** — test expected 60s but actual was 90s (aligned after activation timeout changes)
- **Cloud providers — AWS, GCP, Azure** — complete cloud integration for all major providers:
  - `integrations/xml/jackson-xml` — XML mapper module (Jackson XML) mirroring `JsonMapper` pattern, needed for AWS EC2 XML responses
  - `integrations/cloud/aws` — AWS cloud client with SigV4 signing from scratch, EC2 (XML), ELBv2 (JSON), Secrets Manager (JSON). No AWS SDK
  - `integrations/cloud/gcp` — GCP cloud client with RS256 JWT token management, Compute Engine, Network Endpoint Groups, Secret Manager. No GCP SDK
  - `integrations/cloud/azure` — Azure cloud client with dual OAuth2 tokens (management + Key Vault), ARM REST API, Resource Graph KQL, Key Vault. No Azure SDK
  - `aether/environment/aws` — AWS environment integration: EC2 compute, ELBv2 load balancing, tag-based discovery, Secrets Manager
  - `aether/environment/gcp` — GCP environment integration: Compute Engine, NEG load balancing, label-based discovery, Secret Manager
  - `aether/environment/azure` — Azure environment integration: VM compute, LB backend pools, Resource Graph discovery, Key Vault secrets
  - CDM `completeDrain()` now calls `ComputeProvider.terminate()` to stop billing on drained cloud VMs. Tag-based instance lookup via `aether-node-id`. Works uniformly for all providers (Hetzner, AWS, GCP, Azure)
  - `AetherNode` applies `aether-node-id` tag on startup via IP-based self-identification for CDM terminate correlation
  - `ComputeProvider` SPI extended: `provision(ProvisionSpec)` for detailed specs, `listInstances(TagSelector)` typed filter
  - `LoadBalancerProvider` SPI extended: 7 new default methods — `createLoadBalancer`, `deleteLoadBalancer`, `loadBalancerInfo`, `configureHealthCheck`, `syncWeights`, `deregisterWithDrain`, `configureTls`
  - `SecretsProvider` SPI extended: `resolveSecretWithMetadata`, `resolveSecrets` (batch), `watchRotation`
  - `CachingSecretsProvider` — TTL-cached wrapper for any SecretsProvider
  - New SPI types: `ProvisionSpec`, `TagSelector`, `LoadBalancerSpec`, `LoadBalancerInfo`, `HealthCheckConfig`, `TlsTerminationConfig`, `SecretValue`, `SecretRotationCallback`
- **Cloud integration — Hetzner end-to-end** — complete Hetzner Cloud integration for real cloud testing:
  - `SecretsProvider` implementations: `EnvSecretsProvider` (AETHER_SECRET_* env vars), `FileSecretsProvider` (/run/secrets files), `CompositeSecretsProvider` (first-success chain). Zero cloud dependencies, universal fallback
  - `DiscoveryProvider` SPI: label-based peer discovery replacing static TOML peer lists. `discoverPeers()`, `watchPeers()` (polling), `registerSelf()`/`deregisterSelf()`. Wired into AetherNode bootstrap — registers on start, deregisters on graceful shutdown
  - `HetznerDiscoveryProvider`: discovers peers via `aether-cluster` server labels, extracts host/port from private IPs and `aether-port` label, configurable poll interval
  - `ComputeProvider` extensions: `restart()`, `applyTags()`, `listInstances(tagFilter)` with default implementations. Hetzner provider overrides all three using API reboot/label update/label selector
  - `InstanceInfo.tags` field for cloud metadata passthrough (server labels → instance tags)
  - `HetznerClient` extensions: `listServers(labelSelector)`, `updateServerLabels()`, `rebootServer()`, `Server.labels` field
  - `EnvironmentIntegration.discovery()` facet with backward-compatible wiring
- **Blueprint Artifact Transition** — blueprints packaged as deployable JAR artifacts:
  - **Blueprint artifacts**: Blueprints are now packaged as deployable JAR artifacts containing `blueprint.toml`, optional `resources.toml` (app-level config), and optional `schema/` directory (database migration scripts)
  - **`PackageBlueprintMojo`**: New Maven plugin goal (`package-blueprint`) produces classifier `blueprint` JARs with `Blueprint-Id` and `Blueprint-Version` manifest entries
  - **`publishFromArtifact`**: New deployment path — upload blueprint JAR to ArtifactStore, then deploy via `POST /api/blueprint/deploy` or `aether blueprint deploy <coords>`
  - **Config separation**: Application config (`resources.toml`) travels with blueprint at GLOBAL scope; infrastructure endpoints (`[endpoints.*]` in `aether.toml`) stay at NODE scope. ConfigService merges both hierarchically (SLICE > NODE > GLOBAL)
  - **Schema migration prep**: Blueprint artifacts carry `schema/` migration scripts (root `schema/*.sql` maps to `[database]`, subdirectories `schema/<name>/*.sql` map to `[database.<name>]`). End-to-end execution via DatasourceConnectionProvider
  - **New KV types**: `BlueprintResourcesKey/Value`, `SchemaVersionKey/Value`, `SchemaMigrationLockKey/Value` for blueprint resources and schema tracking
  - **CLI commands**: `blueprint deploy <coords>` and `blueprint upload <file>` for artifact-based blueprint deployment
- **Notification resource (Phase 1 — Email)** — three new modules delivering async email notifications:
  - `integrations/net/smtp` — async SMTP client on Netty with STARTTLS, IMPLICIT TLS, AUTH PLAIN/LOGIN, multi-recipient support, connection-per-send. Full state machine (GREETING→EHLO→STARTTLS→AUTH→MAIL FROM→RCPT TO→DATA→QUIT)
  - `integrations/email-http` — HTTP email sender with pluggable vendor mappings via SPI. Built-in: SendGrid, Mailgun, Postmark, Resend. Hand-built JSON/form-data (no Jackson dependency)
  - `aether/resource/notification` — thin Aether resource wiring (`NotificationSender` + `NotificationSenderFactory`). Routes to SMTP or HTTP backend based on config. Exponential backoff retry. `@Notify` resource qualifier annotation for slice injection

## [0.20.0] - 2026-03-17

### Added
- **Scheduled task ExecutionMode** — replaced `boolean leaderOnly` with `ExecutionMode` enum (`SINGLE`, `ALL`). `SINGLE` (default) fires on leader only, `ALL` fires independently on every node with the slice deployed. TOML: `executionMode = "ALL"` in `[scheduling.*]` sections
- **Blueprint pub-sub validation** — deploy-time validation rejects blueprints where a publisher topic has no subscriber. `PubSubValidator` cross-references all publisher/subscriber config sections across all slices in the blueprint. Orphan publishers produce a descriptive error and the blueprint is not deployed
- **Transaction-mode connection pooling** — postgres-async driver now supports `PoolMode.TRANSACTION` which multiplexes N logical connections over M physical connections. Borrows per-query/transaction, returns on completion. Includes prepared statement migration across physical backends, LISTEN/NOTIFY pinning, nested transaction (savepoint) support, and `ReadyForQuery` transaction status parsing. Eliminates need for external PgBouncer
- **Compound KV-Store key types** — `NodeArtifactKey` (replaces per-method EndpointKey + SliceNodeKey) and `NodeRoutesKey` (replaces per-route HttpNodeRouteKey) with compound values. Single writer per node per artifact, ~10x reduction in entry count and consensus commits
- **Hybrid Logical Clock** — new `integrations/hlc` module providing `HlcTimestamp` (packed 48-bit micros + 16-bit counter) and thread-safe `HlcClock` with drift detection, used for DHT versioned writes
- **Cron scheduling** — wired existing `CronExpression` parser into `ScheduledTaskManager` with one-shot+re-schedule pattern. Cron tasks fire at the next matching time, then re-schedule automatically
- **Weeks interval unit** — `IntervalParser` now supports `w` suffix (e.g., `2w` = 14 days) for schedules that cron can't express naturally
- **Pause/resume scheduled tasks** — operators can pause and resume individual scheduled tasks via REST API (`POST .../pause`, `.../resume`) and CLI (`scheduled-tasks pause/resume`). Paused state persisted in KV-Store through consensus
- **Manual trigger** — fire any scheduled task immediately via REST API (`POST .../trigger`) or CLI (`scheduled-tasks trigger`), regardless of schedule or paused state
- **Execution state tracking** — `ScheduledTaskStateRegistry` tracks last execution time, consecutive failures, total executions per task. State written to KV-Store after each execution (fire-and-forget). REST API responses enriched with execution metrics
- **Execution state endpoint** — `GET /api/scheduled-tasks/{config}/{artifact}/{method}/state` returns detailed execution state including failure messages
- **Centralized timeout configuration** — all operator-facing timeouts consolidated into `TimeoutsConfig` with 14 subsystem groups. TOML `[timeouts.*]` sections with human-readable duration strings (`"5s"`, `"2m"`, `"500ms"`). Covers invocation, forwarding, deployment, rolling updates, cluster, consensus, election, SWIM, observability, DHT, worker, security, repository, and scaling. Legacy `_ms` fields (`forward_timeout_ms`, `cooldown_delay_ms`) supported with automatic migration. Reference: `aether/docs/reference/timeout-configuration.md`

### Changed
- **Invocation timeouts reduced** — server-side timeout 25s→15s, client-side invoker timeout 30s→20s. Faster failure detection for stuck invocations
- **Activation chain timeout increased** — 2m→5m to accommodate loading (2m) + activating (1m) with headroom
- **Local repository locate timeout reduced** — 30s→10s (local filesystem operations don't need 30s)
- **Config record field standardization** — all `long *Ms`/`int *Seconds`/`Duration` fields in config records replaced with `TimeSpan`. Affected: `AppHttpConfig`, `WorkerConfig`, `TtmConfig`, `RollbackConfig`, `AlertConfig.WebhookConfig`, `NodeConfig`, `PassiveLBConfig`
- **Control plane KV-Store migration (complete)** — all control plane data migrated from DHT to KV-Store with compound key types. Publishers write only `NodeArtifactKey`/`NodeRoutesKey` (no dual-write). All consumers (EndpointRegistry, DeploymentMap, HttpRouteRegistry, ControlLoop, ArtifactDeploymentTracker, LoadBalancerManager) handle new types via KVNotificationRouter. CDM cleanup uses new key types for stale entry removal. ~10x reduction in consensus commits per deployment
- **WorkerNetwork eliminated** — consolidated inter-worker TCP transport into NettyClusterNetwork (NCN) via PassiveNode's DelegateRouter. Workers now use a single Netty TCP stack instead of two. All inter-worker messaging (mutations, decisions, snapshots, metrics, DHT relay) flows through NCN's `Send`/`Broadcast` messages
- **Server UDP support** — `Server` now supports optional UDP port binding alongside TCP, sharing the same workerGroup (EventLoopGroup). Configured via `ServerConfig.withUdpPort()`. Foundation for future lightweight UDP messaging
- **SWIM sole failure detector** — removed NCN's Ping/Pong keepalive. SWIM is now the only failure detection mechanism. Eliminates redundant probing and simplifies the network layer
- **SWIM shared thread pool** — `NettySwimTransport` can use Server's workerGroup instead of creating a separate `NioEventLoopGroup(1)`. Passed via `CoreSwimHealthDetector` on quorum establishment
- **HTTP server shared EventLoopGroups** — `HttpServer` accepts external `EventLoopGroup` instances via new factory overload. `NettyHttpServer.createShared()` binds on provided groups without owning them (no shutdown on stop). AppHttpServer, ManagementServer, and AetherPassiveLB now share Server's boss/worker groups, reducing per-node thread pools from 6+ to 2
- **Worker module JBCT compliance** — converted 7 types (`MutationForwarder`, `GovernorCleanup`, `DecisionRelay`, `WorkerBootstrap`, `WorkerMetricsAggregator`, `WorkerDeploymentManager`, `GovernorElection`) from final classes/sealed interfaces to JBCT-compliant interfaces with local record implementations. Eliminated Mockito from all 7 worker test files, replaced with simple record stubs
- **DHT versioned writes** — every DHT put now carries an HLC version; storage rejects writes with version <= current, fixing out-of-order state overwrites (e.g., LOADED overwriting ACTIVE)
- **ReplicatedMap local cache** — `NamespacedReplicatedMap` now maintains a `ConcurrentHashMap` local cache with `forEach()` for iteration, enabling CDM to rebuild slice state from DHT
- **CDM state rebuild** — `ClusterDeploymentManager` rebuilds slice state from DHT `ReplicatedMap` instead of consensus KV-Store
- **DHT notification broadcasting** — active nodes broadcast DHT route mutations to passive peers (load balancers) via `DHTNotification` protocol messages

### Fixed
- **CDM reconciliation interval** — ClusterDeploymentManager was incorrectly wired to cluster topology interval (5s) instead of its own 30s deployment reconciliation cycle, causing 6x excessive reconciliation
- **TcpTopologyManager node resurrection race** — `get()`+`put()` pattern in connection failure/established handlers was not atomic; a concurrent `remove()` between the two calls could resurrect a removed node. Fixed with `computeIfPresent()` for atomic read-modify-write
- **Route eviction on node death** — `HttpRouteRegistry.evictNode()` existed but was never wired to `NodeRemoved` topology event. Dead nodes stayed in route tables until CDM's slow consensus-based cleanup completed (60s+). Now evicted immediately on disconnect. Also added `cleanupStaleNodeRoutes()` to periodic reconcile as defense-in-depth
- **NodeLifecycleKey race on restart** — `registerLifecycleOnDuty()` skipped write if key existed, but pending consensus batch could delete the stale key after the check. Now unconditionally writes ON_DUTY (only guards DECOMMISSIONED). Added `onRemove` defense-in-depth handler to re-register if key is unexpectedly removed
- **CDM LOAD command tracking race** — `issueLoadCommand()` put LOAD in `sliceStates` before consensus confirmed, causing phantom instances that blocked reconcile retries. Moved tracking to `withSuccess` callback
- **NDM pending LOAD scan** — NDM now scans KV-Store for pending LOAD commands on activation, catching commands issued by CDM before NDM transitioned from Dormant
- **Worker thread bottleneck offloading** — SWIM `DisconnectNode` routing uses `routeAsync` to avoid blocking shared SWIM thread. `StaticFileHandler` caches classpath resources in `ConcurrentHashMap` to eliminate repeated blocking I/O
- **HTTP forwarding zero-copy bodies** — removed unnecessary defensive `byte[]` cloning from `HttpRequestContext` and `HttpResponseData` constructors and accessors, eliminating ~4 array copies per forwarded request
- **Anti-entropy migration HLC poisoning** — migration data now carries HLC versions and uses `putVersioned()` instead of unversioned `put()` which was storing with `Long.MAX_VALUE`, permanently blocking all subsequent versioned writes to affected keys
- **GitBackedPersistence** — configure git user email/name after `git init` to prevent commit failures on CI runners without global git config
- **ReadTimeoutHandler removed** — Netty `ReadTimeoutHandler` removed from cluster network; SWIM health detection handles peer liveness instead
- **ReplicatedMap async notification race** — `NamespacedReplicatedMap` used `.onSuccess()` (async dispatch) for cache updates and subscriber notifications, causing rapid state transitions (LOADED→ACTIVE) to arrive out of order at CDM. Changed to `.withSuccess()` (synchronous dispatch) to preserve causal write ordering
- **ReplicatedMap subscriber re-entrance** — synchronous notification delivery exposed a re-entrance bug: when subscriber callbacks trigger nested puts (e.g., CDM reacting to LOADED by issuing ACTIVATE), the outer `forEach` continued delivering stale values to later subscribers (DeploymentMap). Replaced with drain loop (trampoline pattern) that enqueues notifications and processes them iteratively, ensuring each state transition is fully delivered to all subscribers before the next begins
- **Full DHT replication for control plane** — AetherMaps now uses `DHTConfig.FULL` replication so all nodes receive all control plane notifications (slice-nodes, endpoints, routes), fixing notification delivery gaps on non-replica nodes
- **Route eviction on node departure** — removed redundant `routeRegistry.evictNode()` call from `HttpForwarder`; DHT cleanup handles route removal
- **RemoteRepositoryTest** — assertion updated to accept both "Download failed" and "HTTP operation failed" error messages after HttpOperations refactor
- **CodecProcessor doubly-nested types** — `@Codec` annotation processor now recursively scans nested helper types inside permitted subclasses (e.g., `RouteEntry` inside `NodeRoutesValue`). Previously only scanned one nesting level, causing `No codec registered` errors at runtime
- **Virtual thread starvation in example tests** — `InMemoryDatabaseConnector` now uses synchronous `Promise.resolved()` instead of async `Promise.lift()` for in-memory operations, preventing carrier thread starvation on low-vCPU CI runners
- **Test await timeouts** — all example test `await()` calls now use 10-second timeouts to prevent indefinite hangs on resource-constrained environments

## [0.19.3]

### Multi-Blueprint Lifecycle
- Fixed critical bug: blueprint deletion now only removes artifacts owned by the deleted blueprint (was removing ALL artifacts)
- Fixed critical bug: `owningBlueprint` field in SliceTargetValue now correctly populated during blueprint deployment
- Added artifact exclusivity enforcement — prevents two blueprints from deploying the same artifact (rejects with descriptive error)
- Added deletion guard — prevents blueprint deletion while its artifacts have active rolling updates
- CDM state restore now correctly populates blueprint ownership from KV-Store
- Added `SliceTargetValue.sliceTargetValue(Version, int, int, Option<BlueprintId>)` factory

### Added
- **Governor mesh advertised address** — governors now announce a routable TCP address instead of hardcoded `0.0.0.0`. Auto-detects via `InetAddress.getLocalHost()` or uses configurable `advertise_address` in `[worker]` TOML section. Fixes cross-host governor mesh connections
- **Event-based community scaling** — governors monitor follower metrics locally and send scaling requests to core only when thresholds are sustained. Zero baseline bandwidth. Architecture:
  - **Worker metrics messages** — `WorkerMetricsPing`/`WorkerMetricsPong` between governor and followers (~100 bytes per pong)
  - **Community scaling messages** — `CommunityScalingRequest` (governor→core, event-driven), `CommunityMetricsSnapshotRequest`/`CommunityMetricsSnapshot` (core→governor, on-demand diagnostics)
  - **CommunityScalingEvaluator** — sliding window (5 samples × 5s default) with sustained-breach detection for CPU, P95 latency, error rate. Per-direction cooldown prevents thrashing
  - **WorkerMetricsAggregator** — governor-side component with periodic ping cycle, follower pong collection, JMX self-metrics, stale cleanup, evaluator integration
  - **ControlLoop community scaling handler** — validates evidence freshness (<30s), checks blueprint existence and cooldown, applies scaling via existing KV-Store path, publishes ScalingEvent
  - **Scaling cap includes workers** — `prepareChangeToBlueprint()` now counts worker nodes in cluster size for scaling cap calculation
  - **ClusterEvent types** — added `COMMUNITY_SCALE_REQUEST` and `COMMUNITY_METRICS_SNAPSHOT` to EventType enum

- **Passive Worker Pools Phase 2a — DHT-Backed ReplicatedMap** — moves high-cardinality endpoint data from consensus KV-Store to DHT, reducing write amplification from O(N) to O(3):
  - **`aether/aether-dht` module** — generic typed `ReplicatedMap<K,V>` abstraction with namespace-prefixed keys, `MapSubscription` event callbacks, `CachedReplicatedMap` (LRU + TTL), `ReplicatedMapFactory`
  - **Community-aware replication** — `ReplicationPolicy` with home-replica rule (1 home + 2 ring replicas = RF=3), `HomeReplicaResolver` for deterministic community-local selection, `ConsistentHashRing` spot-node exclusion filter
  - **Endpoint migration** — `EndpointRegistry` unified with DHT subscription events (core + worker endpoints in single registry), `NodeDeploymentManager` writes endpoints via DHT `ReplicatedMap`, `SliceInvoker` simplified to single-registry lookup
  - **Replication cooldown** — startup RF=1 with background push to RF=3 after configurable delay, rate-limited to prevent boot storm
  - **Governor mesh infrastructure** — `GovernorMesh` and `GovernorDiscovery` for cross-community DHT traffic routing (full wiring in Phase 2b)
  - **DHT node cleanup** — `DhtNodeCleanup` removes dead node endpoints from DHT on SWIM DEAD detection
  - **AetherMaps** — factory for 3 named maps (endpoints, slice-nodes, http-routes) with serializers
- **Worker Slice Execution (P1+P2a Completion)** — end-to-end worker node functionality: slices deployed with `WORKERS_PREFERRED` placement run on worker nodes, publish endpoints to DHT, and SliceInvoker routes traffic to workers:
  - **CDM worker awareness** — ClusterDeploymentManager discovers workers via `ActivationDirectiveKey(WORKER)`, populates `AllocationPool` with worker nodes, writes `WorkerSliceDirectiveKey/Value` directives to consensus for worker slice deployment
  - **PlacementPolicy in SliceTargetValue** — `placement` field (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL) added to slice target configuration. Management API `POST /api/scale` accepts optional `placement` parameter. CLI: `aether scale --placement`
  - **WorkerDeploymentManager** — sealed interface with Dormant/Active states managing slice lifecycle on workers: watches `WorkerSliceDirectiveKey` from KVNotificationRouter, self-assigns instances via consistent hashing of SWIM members, drives SliceStore load→activate chain, publishes endpoints and slice-node state to DHT
  - **WorkerInstanceAssignment** — deterministic consistent hashing for instance distribution across workers. Same inputs produce same assignment on every worker — no coordination needed
  - **Governor cleanup** — `GovernorCleanup` maintains per-node index of DHT entries (endpoints, slice-nodes, HTTP routes). On SWIM FAULTY/LEFT, governor removes dead node's entries from all three DHT maps. `GovernorReconciliation` runs on governor election to clean orphaned entries
  - **KVNotificationRouter on workers** — workers build notification router on PassiveNode's KVStore to watch `WorkerSliceDirectiveKey` entries, same pattern as AetherNode's notification wiring
  - **SliceNodeKey DHT migration** — SliceNodeKey reads/writes moved from consensus to `slice-nodes` ReplicatedMap. CDM, NDM, ControlLoop, DeploymentMap, ArtifactDeploymentTracker all subscribe via `asSliceNodeSubscription()` adapters
  - **HttpNodeRouteKey DHT migration** — HttpNodeRouteKey reads/writes moved from consensus to `http-routes` ReplicatedMap. HttpRoutePublisher, HttpRouteRegistry, AppHttpServer, LoadBalancerManager all subscribe via `asHttpRouteSubscription()` adapters
  - **WorkerEndpointRegistry removed** — dead code from Phase 1 replaced by DHT-backed endpoint registry. `WorkerRoutes`, `WorkerGroupHealthReport`, `WorkerEndpointEntry` deleted
  - **DHT replication config** — `[dht.replication]` TOML section for `cooldown_delay_ms`, `cooldown_rate`, `target_rf` with environment-aware defaults
- **Container image publishing** — `release.yml` builds multi-arch Docker images (amd64+arm64) via buildx, publishes to GHCR and Docker Hub. SHA256 checksums generated for all release artifacts
- **Upgrade script** (`aether/upgrade.sh`) — detects current version, downloads new JARs to temp dir, verifies SHA256 checksums, atomic binary swap with backup, running process detection
- **Rolling cluster upgrade script** (`aether/script/rolling-aether-upgrade.sh`) — API-driven zero-downtime upgrades: discovers nodes, drains → shuts down → waits for restart → activates → canary checks each node. Supports `--dry-run`, `--canary-wait`, `--api-key`, `--skip-download`
- **Passive worker pools design spec** (`aether/docs/specs/passive-worker-pools-spec.md`) — architecture for scaling to 10K+ nodes: elected governors, SWIM gossip, KV-Store split, auto flat↔layered transition, 3-phase rollout plan
- **Passive worker pools Phase 1** — foundation for scaling beyond Rabia consensus limits (5-9 nodes) with passive compute nodes:
  - **SWIM protocol module** (`integrations/swim/`) — UDP-based failure detection with periodic probes, indirect probing, piggybacked membership updates
  - **Worker node module** (`aether/worker/`) — WorkerNode composes PassiveNode + SWIM + Governor election + Decision relay + Mutation forwarding + Bootstrap
  - **Governor election** — pure deterministic computation (lowest ALIVE NodeId), no election messages exchanged
  - **Worker configuration** — `WorkerConfig` with SWIM settings, core node addresses, placement policy (CORE_ONLY, WORKERS_PREFERRED, WORKERS_ONLY, ALL)
  - **Worker endpoint registry** — non-consensus ConcurrentHashMap-based registry with round-robin load balancing, governor health report population
  - **SliceInvoker dual lookup** — core endpoints first, worker endpoints fallback via governor routing
  - **CDM pool awareness** — `AllocationPool` record, `WorkerSliceDirectiveKey`/`WorkerSliceDirectiveValue` in consensus KV-Store
  - **Worker management API** — `GET /api/workers`, `GET /api/workers/health`, `GET /api/workers/endpoints`
  - **CLI commands** — `aether workers list`, `aether workers health`

- **Multi-Group Worker Topology (Phase 2b)** — workers self-organize into zone-aware groups with per-group governors. Deterministic group computation from SWIM membership — same inputs produce same groups on every worker:
  - **WorkerGroupId** — `(groupName, zone)` identity record with `communityId()` format (`groupName:zone`)
  - **GroupAssignment** — deterministic zone-aware group computation: extracts zone from NodeId, splits zones exceeding `maxGroupSize` via round-robin subgroups
  - **GroupMembershipTracker** — tracks SWIM membership and computes zone-aware groups, exposes `myGroup()`, `myGroupMembers()`, `allGroups()`
  - **Per-group governor election** — governor election scoped to own group members, not all SWIM members
  - **Per-group Decision relay** — governor only relays Decisions to own group followers, reducing broadcast scope
  - **GovernorAnnouncementKey/Value** — governors announce themselves to consensus KV-Store. Core nodes track community sizes and governor identities via `ClusterDeploymentManager`
  - **CDM community-aware placement** — `AllocationPool` extended with `workersByCommunity` map. CDM tracks governor announcements for community-aware instance distribution. End-to-end wiring: CDM distributes instances across communities, writes per-community directives, workers filter by targetCommunity
  - **WorkerSliceDirectiveValue** extended with optional `targetCommunity` for community-scoped deployment
  - **AetherKey community serialization** — `GovernorAnnouncementKey` round-trip through KV-Store backup/restore with pipe-delimited communityId format
  - **Worker configuration** — `WorkerConfig` extended with `groupName` (default `"default"`), `zone` (default `"local"`), `maxGroupSize` (default `100`). TOML: `worker.group_name`, `worker.zone`, `worker.max_group_size`

- **KV-Store durable backup** — serializes cluster metadata (slice targets, node lifecycle, config) to a single TOML file managed in a local git repo. Git provides versioning, history, diffs, and optional remote push for offsite backup
  - **TOML Writer** (`integrations/config/toml`) — serialization support added to the custom TOML library, including inline table parsing
  - **KV-Store serializer** (`aether/slice`) — converts all 18 AetherKey/AetherValue types to/from TOML with pipe-delimited values grouped by key-type sections
  - **Git-backed persistence** (`integrations/consensus`) — `GitBackedPersistence` implements `RabiaPersistence` using git CLI via ProcessBuilder for atomic snapshots
  - **Backup configuration** — `[backup]` TOML section with enabled, interval, path, remote fields and environment-aware defaults
  - **Management API** — `POST /api/backup`, `GET /api/backups`, `POST /api/backup/restore`
  - **CLI commands** — `aether backup trigger`, `aether backup list`, `aether backup restore <commit>`

- **SWIM core-to-core health detection** (P1.13) — replaces TCP disconnect as health signal for core nodes. `CoreSwimHealthDetector` bridges SWIM membership events to `TopologyChangeNotification`. 1-2s failure detection vs 15s-2min with TCP. TCP disconnect no longer triggers topology removal — only SWIM `FAULTY`/`LEFT` does
- **Automatic topology growth** (P1.14) — CDM dynamically assigns core vs worker role to joining nodes. `RabiaEngine` activation gating: seed nodes auto-activate, non-seed nodes wait for CDM authorization. `TopologyConfig` extended with `coreMax`/`coreMin`. New `TopologyGrowthMessage` sealed interface (`ActivateConsensus`, `AssignWorkerRole`). Management API: `GET /api/cluster/topology`. CLI: `aether topology status`
- **E2E test rework: container networking** — replaced dual-mode networking (Linux host / macOS bridge with PID-based port allocation) with standard bridge networking for all platforms. All containers use identical internal ports (8080/8090) and communicate via DNS. Eliminates port conflicts and enables realistic test scenarios
- **E2E test scenarios** — 8 new tests leveraging container networking:
  - `RollingRestartE2ETest` — zero-downtime sequential node restart
  - `SwimDetectionE2ETest` — SWIM failure detection timing bound
  - `NodeDrainE2ETest` — graceful drain lifecycle via management API
  - `NetworkPartitionE2ETest` — minority partition isolation and reconvergence
  - `SliceLifecycleE2ETest` — full deploy/scale/invoke/undeploy cycle
  - `TopologyGrowthE2ETest` — dynamic node addition to running cluster
  - `LoadBalancerFailoverE2ETest` — slice invocation rerouting after failure
  - `LeaderIsolationE2ETest` — leader disconnect recovery without split-brain

### Security
- **Inter-node mTLS** — CertificateProvider SPI with SelfSignedCertificateProvider (BouncyCastle EC P-256, HKDF deterministic CA from shared `clusterSecret`). All TCP transports (consensus, DHT, management, app HTTP) secured with mutual TLS
- **SWIM gossip encryption** — AES-256-GCM symmetric encryption for all SWIM protocol messages. Wire format: `[keyId][nonce][ciphertext+GCM tag]`. Dual-key support for seamless rotation
- **Certificate renewal scheduler** — automatic renewal at 50% of validity (3.5 days for 7-day certs), 1-hour retry on failure
- **Gossip key rotation** — `GossipKeyRotationKey`/`GossipKeyRotationValue` in consensus KV store for coordinated key rotation
- **TLS by default** — DOCKER and KUBERNETES environments enable TLS automatically. `clusterSecret` configurable via TOML `[tls]` section or `AETHER_CLUSTER_SECRET` env var (dev default: `aether-dev-cluster-secret`)
- **Unit tests** — SelfSignedCertificateProviderTest (8 tests: deterministic CA, cert issuance, gossip key), AesGcmGossipEncryptorTest (10 tests: round-trip, dual-key, error cases), TlsConfig fromProvider bridge tests (4 tests)

### Changed
- Dockerfile version labels now use build-arg `VERSION` instead of hardcoded values
- TCP disconnect in `NettyClusterNetwork` no longer fires topology removal — reconnection continues while SWIM handles health detection
- `TcpTopologyManager` never routes `RemoveNode` on connection failure — always continues reconnection with backoff
- Dockerfile source URLs updated to `pragmaticalabs/pragmatica`
- `install.sh` enhanced with `--version` flag, SHA256 checksum verification, WSL2 detection
- Root `install.sh` references `main` branch instead of `release-0.19.3`

### Fixed
- `AetherNode.VERSION` updated from `0.19.0` to `0.19.3`
- `AetherUp.VERSION` updated from `0.7.2` to `0.19.3`
- **SWIM codecs not registered** — `NodeCodecs` was missing `SwimCodecs.CODECS`, causing all SWIM probes to fail silently
- **SWIM false positives during startup** — deferred SWIM start to after quorum establishment to prevent marking alive nodes FAULTY during cluster formation
- **Activation gating** — `isSeedNode()` always returned true because `TcpTopologyManager` requires self in `coreNodes`. Replaced with explicit `activationGated` boolean on `AetherNodeConfig`/`NodeConfig`, passed through to `RabiaEngine`
- **Passive LB false FAULTY** — removed SWIM from passive LB; core nodes don't know about the LB as a SWIM peer, so indirect probes always fail, cascading to false FAULTY for all core nodes. LB gets health info through consensus data stream instead
- **SWIM selfAddress corruption** — `CoreSwimHealthDetector` used `0.0.0.0` as selfAddress, which would corrupt member addresses when piggybacked via SWIM refutation updates. Now uses actual host from topology config

## [0.19.2] - 2026-03-08

### Added
- **`jbct add-slice`** — scaffold new slice into existing project (creates source, test, routes, config, manifest in sub-package)
- **`jbct add-event`** — generate pub-sub event annotations + auto-append messaging config to `aether.toml`
- **`jbct init --version`** — override dependency versions for pre-release testing
- **Unified installer** (`install.sh`) — downloads jbct, aether CLI, and aether-forge
- **Scaffold scripts** — `run-forge.sh`, `start-postgres.sh`, `stop-postgres.sh`, `deploy-forge.sh`, `deploy-test.sh`, `deploy-prod.sh`, `generate-blueprint.sh`
- **ALL_OR_NOTHING deployment atomicity** — default for all blueprint deployments; no partial deploys
- **Blueprint auto-rollback** — on deployment failure, all slices revert to previous state automatically
- **Cause-based deployment retry** — error propagation through KV store with `SliceLoadingFailure` hierarchy
- **Database URL inference** — type, host, and database name inferred from JDBC URL; explicit fields optional
- **Optional database port** — URL-only configuration supported (no separate port field required)
- **Config service factory methods** — record validation via factory methods in config records

### Fixed
- CLI REPL mode with `-c` connection flag now works correctly
- CLI missing `/api/` prefix on 31 management API paths
- Double JSON serialization in management API responses (pre-serialized strings no longer re-wrapped)
- Scale command preserves existing `minInstances` from blueprint
- Rollback route/endpoint/subscription cleanup via `forceCleanupSlice`
- Reactivation failure cleanup — full cleanup chain on slice reload failure
- Topology graph edge routing — links start right, arrows enter left
- `Verify.Is.blank()` null-safe (no longer throws on null input)
- Format-check error message now includes file names
- Slice processor error messages include file reference and slice name
- Domain error recovery from failed Promises in `SliceRouter`
- Infinite reconciliation loop for deterministic deployment failures
- `install.sh` uses semver sort instead of `/releases/latest`

### Changed
- `TimeSpan` instead of `Duration` in `PoolConfig` (plain-number-as-seconds support)
- Partial nested record merge with `DEFAULT` strategy
- HelloWorld scaffold in own subpackage (consistent with `add-slice`)

## [0.19.1] - 2026-03-05

### Added
- **postgres-async integration** — native async PostgreSQL driver wired into Aether resource provisioning
  - `asyncUrl` config field on `DatabaseConnectorConfig` for transport selection (priority 20, preferred over JDBC/R2DBC)
  - `postgres-r2dbc-adapter` module — R2DBC SPI adapter over postgres-async (ConnectionFactory, Connection, Statement, Result, Row, RowMetadata)
  - `db-async` module — `AsyncSqlConnector` using postgres-async directly (zero adapter overhead) with LISTEN/NOTIFY support
  - `db-jooq-async` module — `AsyncJooqConnector` via R2DBC adapter for full jOOQ compatibility
- **Configurable IO threads for postgres-async** — `io_threads` field in `[database.pool_config]` controls Netty event loop thread count. Default `0` = auto-detect (`max(availableProcessors, 8)`). Removes single-thread serialization bottleneck that limited throughput to ~3500 req/s
- **PubSubTest** — Forge-based cross-node pub-sub integration test: deploys url-shortener + analytics slices, verifies click event delivery (single, multi-click, leader failover)
- **Dashboard topology graph** — Deployments tab now shows endpoint→slice→resource data flow graph (SVG, column-based DAG layout). Compile-time topology data: HTTP routes, resources, pub-sub topics extracted from `.manifest` files (envelope v6). REST endpoint `GET /api/topology`, included in WebSocket `INITIAL_STATE`
- **Topology swim-lane layout** — complete rewrite of topology graph renderer with per-slice swim lanes, Manhattan routing for cross-slice topic connectors (right gutter) and dependency edges (left gutter), HSL color-coded topic groups, hover highlighting (dims non-related elements), and search filtering
- **Per-slice topology wire format** — topology nodes carry `sliceArtifact`, edges carry `topicConfig`. Resources and topics are now per-slice (no more shared nodes). Cross-slice pub-sub matching connects all publishers to all subscribers with the same config (many-to-many)
- **Route declaration order preservation** — `RouteConfig`, `RouteConfigLoader`, and `TomlDocument` now preserve TOML declaration order using `LinkedHashMap` instead of `Map.copyOf()`

### Performance
- **postgres-async driver optimizations** — single-buffer DataRow (N+1→3 allocations per row), connection pool lock consolidation (3→1 lock acquisitions per getConnection), static protocol constants, ByteArrayOutputStream elimination in wire protocol parsing. Benchmarked: **50% lower p95 at 2000 req/s** (4.78ms→2.38ms), **35% lower p95 at 5000 req/s** (180ms→117ms)

### Changed
- **E2E test suite reduced from 13 to 2 classes** — removed 11 tests that fully overlap with Forge equivalents (ClusterFormation, NetworkPartition, NodeDrain, SliceDeployment, ManagementApi, SliceInvocation, RollingUpdate, GracefulShutdown, Metrics, Controller, Ttm). Kept ArtifactRepositoryE2ETest (unique DHT coverage) and NodeFailureE2ETest (simplified to 2 focused container-specific tests)
- **Forge tests moved to class-level cluster setup** — 8 test classes converted from per-method to `@BeforeAll/@AfterAll` with `@TestInstance(PER_CLASS)`, reducing ~300 cluster starts to ~50
- **Sleep-based stabilization replaced with health endpoint polling** — removed all `Thread.sleep()` stabilization in Forge tests, replaced with awaitility polling on `/api/health` ready+quorum status
- **CI restructured** — Forge tests run in `build-and-test` job (no Docker needed); E2E job slimmed to 20-min timeout with 2 focused test classes. 5 heavy Forge tests (`@Tag("Heavy")`) excluded from CI 2-core runners
- **NodeFailureE2ETest simplified** — rewritten from 3 ordered shared-cluster tests to 2 independent tests (single node failure + leader failover) extending AbstractE2ETest
- **E2E default cluster size reduced from 5 to 3** — `AbstractE2ETest.clusterSize()` returns 3; NodeFailureE2ETest overrides to 5
- **E2E timeouts reduced** — DEFAULT_TIMEOUT 30→15s, DEPLOY_TIMEOUT 3min→90s, RECOVERY_TIMEOUT 60→30s, QUORUM_TIMEOUT 120→60s, CI multiplier 2.0→1.5
- **Forge pom.xml** — `reuseForks=true` (was false), process timeout 1800s
- **postgres-async tests skipped by default** — all 15 test classes require Testcontainers/Docker; `<skipTests>true</skipTests>` in module pom

## [0.19.0] - 2026-03-02

### Added
- **Ember** — embeddable headless cluster runtime extracted from `forge-cluster` into `aether/ember/` module with fluent builder API (`Ember.cluster(5).withH2().start()`)
- **Remote Maven repositories** — resolve slices from Maven Central or private Nexus repos (`repositories = ["local", "remote:central"]`). SHA-1 verification, local cache to `~/.m2/repository`, auth from `settings.xml`
- **Passive Load Balancer** — cluster-aware `aether/lb/` module: passive node joins cluster network, receives route table via committed Decisions, forwards HTTP requests via internal binary protocol (no HTTP re-serialization). Smart routing to correct node, automatic failover on node departure, live topology awareness
- Load balancer integration in Ember/Forge — auto-starts passive LB on cluster boot, configurable via `[lb]` TOML section
- **NodeRole** — `ACTIVE`/`PASSIVE` roles in `NodeInfo` for cluster membership. Passive nodes excluded from quorum and leader election but receive committed Decisions
- **HttpForwarder** — extracted reusable HTTP request forwarding from `AppHttpServer` into `aether-invoke` module with round-robin selection, retry with backoff, and node departure failover

### Fixed
- `InvocationMetricsTest` — fixed stale factory name `forgeH2Server` → `emberH2Server`
- Passive LB topology bootstrap — self node now included in `coreNodes` list (required by `TcpTopologyManager`)
- Passive LB topology manager lifecycle — `start()` now activates topology manager reconciliation loop, enabling cluster peer connections and Decision delivery

### Changed
- **PassiveNode abstraction** — extracted reusable passive cluster node infrastructure (`DelegateRouter`, `TcpTopologyManager`, `NettyClusterNetwork`, `KVStore`, message wiring) from `AetherPassiveLB` into `integrations/cluster` module (`PassiveNode<K,V>` interface). Follows `RabiaNode` pattern: interface + factory + inline record + `SealedBuilder` routes
- k6 test scripts default to routing through passive LB (`FORGE_NODES` → LB URL). Per-node scripts use `FORGE_ALL_NODES`
- `RepositoryType` converted from enum to sealed interface with `Local`, `Builtin`, and `Remote` record variants
- `forge-cluster` module deleted — all cluster management code now in `aether/ember/` with `Ember*` naming
- `ForgeCluster` → `EmberCluster`, `ForgeConfig` → `EmberConfig`, `ForgeH2Server` → `EmberH2Server`, `ForgeH2Config` → `EmberH2Config`

## [0.18.0] - 2026-02-26

### Added
- **Unified Invocation Observability (RFC-0010)** — sampling-based distributed tracing with depth-to-SLF4J bridge
  - `InvocationNode` trace record with requestId, depth, caller/callee, duration, outcome, hops
  - `AdaptiveSampler` — per-node throughput-aware sampling (auto-adjusts: 100% at low load, ~1% at 50K/sec)
  - `InvocationTraceStore` — thread-safe ring buffer (50K capacity) for recent traces
  - `ObservabilityInterceptor` — replaces `DynamicAspectInterceptor` with sampling + depth-based SLF4J logging
  - `ObservabilityDepthRegistry` — per-method depth config via KV-store consensus with cluster notifications
  - `ObservabilityConfig` — depth threshold + sampling target configuration
  - Wire protocol: `InvokeRequest` extended with `depth`, `hops`, `sampled` fields
  - `InvocationContext` — ScopedValue-based `DEPTH` and `SAMPLED` propagation across invocation chains
  - REST API: `GET /api/traces`, `GET /api/traces/{requestId}`, `GET /api/traces/stats`, `GET/POST/DELETE /api/observability/depth`
  - CLI: `traces list|get|stats`, `observability depth|depth-set|depth-remove`
  - Forge proxy routes for trace and depth endpoints
- Liveness probe (`/health/live`) and readiness probe (`/health/ready`) with component-level checks (consensus, routes, quorum) for container orchestrator compatibility
- RBAC Tier 1: API key authentication for management server, app HTTP server, and WebSocket connections
- Per-API-key names and roles via config (`[app-http.api-keys.*]` TOML sections or `AETHER_API_KEYS` env)
- SHA-256 API key hashing — raw keys never stored in memory
- Audit logging via dedicated `org.pragmatica.aether.audit` logger
- WebSocket first-message authentication protocol for dashboard, status, and events streams
- CLI `--api-key` / `-k` flag and `AETHER_API_KEY` environment variable for authenticated access
- `InvocationContext` principal and origin node propagation via ScopedValues + MDC
- App HTTP server `/health` endpoint (always 200, for LB health checks on app port)
- Node lifecycle state machine (JOINING → ON_DUTY ↔ DRAINING → DECOMMISSIONED → SHUTTING_DOWN) with self-registration on quorum, remote shutdown via KV watch, lifecycle key cleanup on departure
- Disruption budget (`minAvailable`) for slice deployments — enforced in scale-down and drain eviction
- Graceful node drain with CDM eviction orchestration respecting disruption budget, cancel drain support, automatic DECOMMISSIONED on eviction complete
- Management API endpoints for node lifecycle operations (`GET /api/nodes/lifecycle`, `GET /api/node/lifecycle/{nodeId}`, `POST /api/node/drain/{nodeId}`, `POST /api/node/activate/{nodeId}`, `POST /api/node/shutdown/{nodeId}`)
- CLI commands for node lifecycle management (`node lifecycle`, `node drain`, `node activate`, `node shutdown`)
- **Class-ID-based serialization for cross-classloader slice invocations** — deterministic hash-based Fury class IDs eliminate `ClassCastException` across slice classloaders
  - `Slice.serializableClasses()` — compile-time declaration of all serializable types per slice
  - `SliceCoreClasses` — sequential ID registration for core framework types (Option, Result, Unit)
  - `FurySerializerFactoryProvider` rewritten with `requireClassRegistration(true)`, hash-based IDs [10000-30000), recursive type expansion, collision detection
  - Envelope format version bumped to v4

### Fixed
- **Fury → Fory migration** — upgraded from `org.apache.fury:fury-core:0.10.3` to `org.apache.fory:fory-core:0.16.0-SNAPSHOT` (patched fork with cross-classloader fixes)
- Removed speculative `HttpRequestContext` decode from `InvocationHandler` — eliminated `ArrayIndexOutOfBoundsException` during cross-node slice invocations
- Removed debug logging from consensus `Decoder` and `Handler` (InvokeMessage trace noise)
- Removed SLF4J dependency from `slice-processor` annotation processor — eliminated "No SLF4J providers" warning during compilation
- Configurable observability depth threshold via `forge.toml` `[observability] depth_threshold` — set to -1 to suppress trace logging during local development
- `InvocationContext.runWithContext()` signature alignment in `AppHttpServer` and `InvocationContextPrincipalTest` (missing `depth`/`sampled` params)

### Changed
- `examples/url-shortener` upgraded from standalone 0.17.0 to reactor-integrated 0.18.0 (inherits parent POM, managed versions, installable for forge artifact resolution)
- `InvocationMetricsTest` forge integration test: deploys url-shortener multi-slice (UrlShortener + Analytics), generates 1K round-trip requests, validates invocation metrics, Prometheus, and traces across 5-node cluster
- **BREAKING:** Removed `DynamicAspectMode`, `DynamicAspectInterceptor`, `DynamicAspectRegistry`, `DynamicAspectRoutes`, `AspectProxyRoutes` — superseded by Unified Observability
- **BREAKING:** Removed `/api/aspects` REST endpoints and `aspects` CLI command — use `/api/observability/depth` and `observability` command instead
- Removed `DynamicAspectKey`/`DynamicAspectValue` from KV-store types — replaced by `ObservabilityDepthKey`/`ObservabilityDepthValue`
- **BREAKING:** `SerializerFactoryProvider.createFactory()` signature changed from `List<TypeToken<?>>` to `(List<Class<?>>, ClassLoader)` for class-ID-based registration
- Removed `CrossClassLoaderCodec`, `decodeForClassLoader()`, deprecated `sliceBridgeImpl()`/`sliceBridge()` factory methods

## [0.17.0] - 2026-02-23

### Added
- DHT anti-entropy repair pipeline — CRC32 digest exchange between replicas, automatic data migration on mismatch
- DHT re-replication on node departure — DHTRebalancer pushes partition data to new replicas when a node leaves
- Per-use-case DHT config via `DHTClient.scoped(DHTConfig)` — artifact storage (RF=3) and cache (RF=1) use independent configs
- SliceId auto-injection into ProvisioningContext for resource lifecycle tracking
- Scheduled task infrastructure — `ScheduledTaskRegistry`, `ScheduledTaskManager`, `CronExpression` parser, KV-Store types (`ScheduledTaskKey`, `ScheduledTaskValue`), deployment lifecycle wiring, management API (`GET /api/scheduled-tasks`), CLI subcommand, 29 unit tests
- 67 new unit tests: DHTNode (12), DistributedDHTClient (19), DHTAntiEntropy (10), DHTRebalancer (8), ArtifactStore (9), DHTCacheBackend (3), pub-sub (18: TopicSubscriptionRegistry 10, TopicPublisher 4, PublisherFactory 4)
- Blueprint membership guard on `POST /api/scale` — rejects scaling slices not deployed via blueprint
- Blueprint `minInstances` as hard floor for scale-down — enforced in auto-scaler, manual `/api/scale`, and rolling updates
- Pub-sub messaging infrastructure and resource lifecycle management (RFC-0011) — `Publisher<T>`, `Subscriber<T>`, `TopicSubscriptionRegistry`, `TopicPublisher`, `PublisherFactory`
- Pub-sub code generation in slice-processor — subscription metadata in manifest, `stop()` resource cleanup, envelope v2
- RFC-0010 Unified Invocation Observability (supersedes RFC-0009)
- Envelope format versioning for slice JARs — `ENVELOPE_FORMAT_VERSION` in ManifestGenerator, runtime compatibility check in SliceManifest
- Properties manifest (`META-INF/slice/*.manifest`) now included in per-slice JARs for full metadata at runtime
- JaCoCo coverage infrastructure across 6 aether modules (427 tests)
- Cluster event aggregator — `/api/events` REST endpoint (with `since` filter), `/ws/events` WebSocket feed (delta broadcasting), CLI `events` command. 11 event types collected into ring buffer (1000 events)

### Fixed
- ProvisioningContext sliceId propagation — resource lifecycle tracking now works correctly for consumer reference counting
- UNLOADING stuck state — CDM `reconcile()` now calls `cleanupOrphanedSliceEntries()`, NDM `handleUnloadFailure()` properly chains Promise
- Rolling update UNLOADING stuck state and missing SliceTargetKey creation
- Monotonic sequencing on `QuorumStateNotification` to prevent race condition during leader failover
- Slice JAR manifest repackaging for rolling update version mismatch
- JBCT compliance fixes for HttpClient JSON API
- Fast-path route eviction on node departure
- 20K/50K/100K rate buttons on Forge dashboard

### Enabled
- 5 previously disabled E2E tests: partition healing, quorum transitions, artifact failover survival, rolling update completion, rolling update rollback

### Changed
- **BREAKING:** Removed individual slice `POST /api/deploy` and `POST /api/undeploy` endpoints — use blueprint commands instead
- **BREAKING:** Removed `deploy` and `undeploy` CLI commands — use `blueprint apply` and `blueprint delete`

### Removed
- Individual slice deploy/undeploy from REST API, CLI, and Forge proxy
- `handleSliceTargetRemoval` from ClusterDeploymentManager (unreachable after deploy/undeploy removal)

## [0.16.0] - 2026-02-18

### Added
- `aether/resource/` module group consolidating all infrastructure resources
- `MethodInterceptor` interface in slice-api for per-method concerns (retry, circuit breaker, rate limit, logging, metrics)
- `ProvisioningContext` in slice-api for passing type tokens and key extractors to resource factories
- 5 interceptor `ResourceFactory` implementations in `resource-interceptors` module
- `integrations/statemachine` module (relocated from infra-statemachine)

### Fixed

### Changed
- **BREAKING:** Renamed packages `org.pragmatica.aether.infra.*` → `org.pragmatica.aether.resource.*`
- **BREAKING:** Resources no longer implement `Slice` interface — `DatabaseConnector`, `HttpClient`, `ConfigService` etc. are pure resource types
- Consolidated 10 infra-slices + infra-api + infra-services into 8 resource modules (api, db-jdbc, db-r2dbc, db-jooq, db-jooq-r2dbc, http, interceptors, services)
- Flattened db-connector hierarchy: `infra-db-connector/{api,jdbc,r2dbc,...}` → `resource/{api,db-jdbc,db-r2dbc,...}`

### Removed
- `aether/infra-api/` — merged into `resource/api`
- `aether/infra-slices/` — 10 modules dropped or relocated:
  - `infra-aspect` (unused JDK proxy factories; config types preserved in resource/api)
  - `infra-database` (toy in-memory SQL, superseded by db-connector)
  - `infra-scheduler` (thin JDK wrapper)
  - `infra-ratelimit` (duplicated core/RateLimiter)
  - `infra-lock` (in-memory only, no distributed backend)
  - `infra-pubsub` (in-memory only, no distributed backend)
- `aether/infra-services/` — merged into `resource/services`

## [0.15.1] - 2026-02-12

### Added
- ClusterEventAggregator for structured event collection (topology, leader, quorum, deployment events)
- MetricsCollector invocation metrics in cluster-wide gossip
- MetricsCollector topology change handlers for departed node cleanup

### Fixed
- SliceStore.unloadSlice() stuck in UNLOADING state when slice loading had previously failed
- Shared dependency loading fails for runtime-provided libraries (e.g. `core` embedded in shaded JAR)
- Orphaned SliceNodeKey entries not cleaned up after undeploy during leader change
- E2E multi-instance deployment test used hardcoded instance count instead of cluster size
- E2E BeforeEach cleanup now retries undeploy to handle leader changes during teardown
- Pre-populate DHT ring with known peers and harden distributed operations
- Distributed DHT wiring — DistributedDHTClient replaces LocalDHTClient for cross-node artifact resolution

### Changed
- Disabled TTM E2E tests (trivial checks not worth 90-minute 5-node cluster overhead)

## [0.15.0] - 2026-02-02

### Added
- Monorepo consolidation of three projects:
  - pragmatica-lite (v0.11.3) - Core functional library
  - jbct-cli (v0.6.1) - CLI and Maven plugin for JBCT formatting/linting
  - aetherx (v0.8.2) - Distributed runtime
- Unified version management across all modules
- Consolidated documentation structure
- Moved `cluster` module from aether to integrations (generic distributed networking)
- AppHttpServer immediate retry on node departure (no more 5-second timeout wait)
- Production tinylog configuration for aether/node
- Tinylog format now includes thread name: `[{thread}]`
- Request ID logging for critical log statements in AppHttpServer and SliceInvoker
- Blueprint CLI commands: `list`, `get`, `delete`, `status`, `validate` (also in REPL)
- Blueprint REST API endpoints: GET/DELETE `/api/blueprint/{id}`, GET `/api/blueprints`,
  GET `/api/blueprint/{id}/status`, POST `/api/blueprint/validate`
- Consolidated startup banner showing node configuration (ID, ports, peers, TTM, TLS)

### Changed
- All modules now use version 0.15.0
- Root POM provides dependency management for entire ecosystem
- Unified CI workflows at monorepo root
- E2E and Forge tests moved to `-Pwith-e2e` profile (require examples to be installed first)
- Standardized tinylog configurations across all modules (24 files)
- Added Fury and Netty logging suppression to all test configs
- Comprehensive logging level overhaul in aether module:
  - Hot paths (SliceInvoker, InvocationHandler, AppHttpServer) moved to DEBUG/TRACE
  - Routine operations (deployment, slice lifecycle) moved to DEBUG
  - Important events (leader/quorum changes, rolling updates) kept at INFO
  - Production logs are now scannable and concise

### Technical Notes
- Group IDs preserved for Maven Central compatibility:
  - `org.pragmatica-lite` for core, integrations, jbct modules
  - `org.pragmatica-lite.aether` for aether modules
- Build: `mvn install -DskipTests` (bootstraps jbct-maven-plugin automatically)
- E2E tests: `mvn verify -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests`

---

## Pre-Monorepo History

Historical changelogs for individual projects:

- [Pragmatica Core CHANGELOG](core/CHANGELOG.md)
- [JBCT CHANGELOG](jbct/CHANGELOG.md)
