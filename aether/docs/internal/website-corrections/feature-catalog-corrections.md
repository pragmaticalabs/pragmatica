# Feature Catalog Corrections

Comparison of `https://pragmaticalabs.io/docs/feature-catalog.html` against `aether/docs/reference/feature-catalog.md` (release-1.0.0-rc1).

Generated: 2026-04-05

---

## 1. Status Legend Change

**Replace "Battle-tested" with "Integration-verified".**

Current:
> **Battle-tested** — Proven through multi-node E2E tests with failure injection

Proposed:
> **Integration-verified** — Proven through multi-node integration tests with failure injection (node kills, partitions, leader failovers) in Forge/EmberCluster

Rationale: "Battle-tested" implies production deployment experience. The project has no production deployments. The actual testing is Forge-based multi-node integration with chaos injection — thorough but simulated.

Affected features (all current "Battle-tested" → "Integration-verified"):
- #1 Blueprint management
- #2 Slice lifecycle
- #3 Deployment strategies
- #4 Auto-healing
- #7 CPU-based auto-scaling
- #13 Rabia consensus
- #14 Leader election
- #15 Quorum state management
- #16 Topology management
- #17 Distributed KV-Store
- #20 Service-to-service invocation
- #21 Version routing
- #24 Message delivery (Pub-Sub)
- #32 Artifact repository
- #33 Distributed hash table
- #35 System metrics
- #37 Cluster metrics API
- #41 Prometheus export
- #49 REST management API
- #53 E2E test framework
- #57 Forge simulator
- #59 Graceful quorum degradation
- #61 Health check endpoint
- #76 Forge integration tests

---

## 2. Entire Sections Missing from Website

The website shows ~66 features. The repo has 173. These sections are completely absent:

### Scheduled Invocation (7 features: #26-31, #104)
- Scheduled task registry, manager, cron parser, KV types, deployment wiring, management API, execution state tracking

### Node Operations (3 features: #63-65)
- Node lifecycle state machine (JOINING → ON_DUTY → DRAINING → DECOMMISSIONED → SHUTTING_DOWN)
- Graceful node drain with disruption budget
- Disruption budget enforcement

### Worker Pools (25+ features: #80-101, #132, #150-156)
- SWIM failure detection, worker nodes, governor election, worker endpoint registry
- CDM pool awareness, worker management API, core-to-core SWIM health
- Automatic topology growth, DHT node cleanup, DHT migrations
- Multi-group worker topology, community-aware placement, event-based scaling
- Role-aware unified node, DHT-backed ReplicatedMap, community-aware replication
- Endpoint DHT migration, replication cooldown, compound KV-Store keys

### Embeddable Runtime (3 features: #73-75)
- Ember embeddable cluster, remote Maven repositories, standalone load balancer

### Security & Resilience (5 features: #88-92)
- Inter-node mTLS, SWIM gossip encryption, certificate lifecycle
- TLS default for containers, RBAC per-route security

### Reusable Libraries (8 features: #166-173)
- State machine, DNS client, TOML parser/writer, KSUID generator
- Core parse library, multipart upload, ProblemDetail (RFC 7807), static file serving

---

## 3. Individual Features Missing from Website

These are in the repo catalog but absent from the website, scattered across existing sections:

| Repo # | Feature | Section | Status |
|--------|---------|---------|--------|
| 6 | Manifest versioning | Deployment | Complete |
| 34 | Configuration service | Storage & Data | Complete |
| 105 | Hybrid logical clock | Storage & Data | Complete |
| 106 | DHT versioned writes | Storage & Data | Complete |
| 107 | Centralized timeout config | Storage & Data | Complete |
| 126 | Blueprint artifacts | Deployment | Complete |
| 127 | Config separation | Deployment | Complete |
| 128 | Schema migration engine (full) | Deployment | Complete |
| 129 | Endpoint config | Deployment | Complete |
| 130 | Deployment state machine | Deployment | Complete |
| 131 | Consensus operation retry | Deployment | Complete |
| 135 | A/B testing | Deployment | Complete |
| 136 | Docker scaling test infra | Management | Complete |
| 137 | SharedScheduler consolidation | Networking | Complete |
| 138 | Core value objects | Developer Tooling | Complete |
| 139 | KV-Store durable backup | Storage & Data | Complete |
| 147 | Declarative cluster management | Management | Complete |
| 154 | Server UDP support | Networking | Complete |
| 155 | Shared EventLoopGroups | Networking | Complete |
| 158 | V1.0.0 roadmap | Developer Tooling | Complete |
| 161 | PgNotification subscriber | Resource Provisioning | Complete |
| 163 | JBCT compliance scorer | Developer Tooling | Complete |
| 164 | JBCT project scaffolding | Developer Tooling | Complete |
| 176 | Application config provisioning | Resource Provisioning | Complete |
| 178 | Cloud certificate adapters | Cloud Integration | Complete |
| 179 | Streaming retention enforcement | Messaging & Streaming | Complete |
| 180 | Consumer cursor persistence | Messaging & Streaming | Complete |
| 181 | Node metadata labels | Cloud Integration | Complete |
| 182 | PlacementHint provisioning | Cloud Integration | Complete |
| 183 | Same-version deploy rejection | Deployment | Complete |
| 184 | Disruption budget enforcement | Deployment | Complete |

---

## 4. Incorrect or Outdated on Website

| # | Issue | Current (Website) | Correct (Repo) |
|---|-------|-------------------|----------------|
| 6 | Envelope version | "v1-v6" or absent | Envelope version is now **1000** (clean break for rc1) |
| 13 | Rabia fault model | May still say "Byzantine" | Must be "crash-fault tolerant (CFT)" per SOSP 2021 paper |
| 65 | JBCT formatter | "CST-based formatter" | Flow-based formatter replaced CST-based in rc1. Trivia-independent, deterministic. |
| 128 | Schema migration | May show basic description | Full description: V/R/U/B types, auto-retry with exponential backoff, `schema_required` config, failure classification |
| 140 | AHSE | Shows basic description | Full implementation: SHA-256 BlockId, Memory + LocalDisk tiers, CAS capacity, write-through, tier-waterfall reads, SingleFlightCache, MetadataStore, SnapshotManager, 93 tests |
| 53 | E2E test framework | "Testcontainers-based" | E2E tests consolidated to Docker integration suite; Forge EmberCluster is the primary testing tool |
| 146 | In-memory streaming | Basic description | Now includes governor failover (#26 on website), cross-tier reads (#27), retention enforcement (#28), cursor persistence |
| 58 | Web dashboard | Not shown on website | Status is **Critical** — should be prominently displayed as the main gap |

---

## 5. RC1 Features Not Reflected on Website

These were implemented after the website was last updated:

| Feature | Description |
|---------|-------------|
| Flow-based formatter | Replaced CST-based formatter. Trivia-independent, deterministic layout. |
| Promise.allOrCancel() | Short-circuit on first failure, cancel remaining. Fixed instance all() parallelism. |
| Step composition | Transitive method-level annotations in slice processor. Slice → Step → Leaf chain. |
| Generic ReactiveMethodBinding | Replaced 5-bucket annotation classification. Extensible for custom annotations. Envelope v1000. |
| AHSE implementation | Full hierarchical storage engine with memory + disk tiers, 93 tests. |
| Streaming governor failover | WatermarkTracker, GovernorFailoverHandler with AHSE catch-up. |
| Cross-tier stream reads | TieredStreamReader with segment prefetch from AHSE. |
| Reconnection bug fix | TopologyObserver re-seeding when SWIM drains peer map during disconnect. |
| ContentStore resource | @ContentStoreQualifier, ContentStoreFactory SPI, AHSE-backed. |
| Cloud certificate adapters | ACM, GCP CM, Azure KV via CertificateProvider SPI. |
| ConfigFacade / ConfigurationSection | Typed config records with compile-time validation and runtime notification. |
| Node metadata labels | hostname, zone, instance-type, pool in NodeInfo via Hello handshake. |
| PlacementHint provisioning | Zone/host/affinity/anti-affinity hints for cloud provisioning. |

---

## 6. Numbering Discrepancy

Website uses a simplified 1-66 sequential numbering. Repo uses the original numbering (1-184 with gaps, linked to GitHub issues). The website should either:
- Match repo numbering for traceability
- Clearly state it's a curated subset and link to the full catalog

---

## 7. Statistics Update

Website may show outdated counts. Current repo stats:

| Status | Count |
|--------|-------|
| Integration-verified (was Battle-tested) | 24 |
| Complete | 140 |
| Critical | 1 |
| Partial | 2 |
| Planned | 6 |
| **Total** | **173** |

---

## 8. Known Limitations Update

Website may be missing these:

| Area | Limitation | Status |
|------|-----------|--------|
| Security | Self-signed certificates only | External CA SPI planned |
| Networking | Single-region only | Not planned |
| Dashboard | Node management requires major work | **Critical** — #58 |
| Persistence | GitBackedPersistence opt-in (inMemory default for LOCAL) | AHSE provides long-term fix |
| Consensus | No backpressure on proposal submission | Tracked as #68 |

---

## Summary of Actions

1. **Rename** "Battle-tested" → "Integration-verified" (24 features)
2. **Add** 6 missing sections (~50 features)
3. **Add** ~30 individual missing features
4. **Fix** 8 incorrect/outdated entries
5. **Add** 13 rc1 features
6. **Reconcile** numbering scheme
7. **Update** statistics
8. **Update** known limitations
