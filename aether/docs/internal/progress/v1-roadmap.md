# V1.0.0 Roadmap

## Target: v1.0.0-rc1 by May 2, 2026

### Current State
- 130/141 features Complete/Battle-tested
- Schema migration failure recovery delivered (0.21.2)
- Docker scaling test passing (0% failures, 12 nodes)
- SWIM stabilized (InetSocketAddress codec fix)
- Core value objects added to core module

### Methodology
All features follow the [Evolutionary Implementation Protocol](../contributors/evolutionary-implementation.md):
decompose into layers, hard correctness gate per layer, no layer starts until the previous is green.

### Release Cadence
- 0.22.0: Audit trail + docs cleanup
- 0.23.0: RBAC Tier 2 MVP
- 0.24.0: Streaming MVP (in-memory, preview)
- 0.25.0: Dashboard wiring
- 0.26.0: Soak test + release pipeline
- 1.0.0-rc1: Buffer + beta fixes

---

## Week 1: Docs Cleanup + Audit Trail (0.22.0)

### 1a. Commit Architecture Docs (S — 1 day)
- 13 architecture docs exist on disk, not in git
- Review and commit to `aether/docs/architecture/`
- Fix feature catalog: update to reflect 0.21.1/0.21.2 changes
- Fix catalog contradiction: backup/restore listed as both Planned and Complete

### 1b. Audit Trail Expansion (S — 2-3 days)

**Goal:** Every mutation path has an audit log entry.

**Layered breakdown:**

| Layer | Scope | Gate |
|-------|-------|------|
| 1 | Identify all mutation paths, define audit event categories | Checklist document |
| 2 | Add `AuditLog` calls to schema migration + blueprint deploy/undeploy | Unit tests verify log output |
| 3 | Add to scaling decisions, config changes, backup/restore, node lifecycle | Integration test: Forge produces audit entries for all categories |

Currently covered: auth, management access, WebSocket, deployment lifecycle.

Missing: schema migration, scaling decisions, config changes, backup/restore, node lifecycle, blueprint deploy/undeploy, secret resolution.

**Files:** SchemaOrchestratorService, ClusterDeploymentManager, BackupService, NodeLifecycleManager, DynamicConfigRoutes, BlueprintService

---

## Week 2: RBAC Tier 2 MVP (0.23.0)

**Layered breakdown:**

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | `Role` enum (ADMIN/OPERATOR/VIEWER), `RoutePermission` record | 0.5 day | Types compile, factory tests |
| 2 | `RoleEnforcer` — check API key role against route requirement | 1 day | Unit tests: all role × route combinations |
| 3 | Wire into `SecurityValidator` + `ManagementServer`, annotate all `*Routes.java` | 1.5 days | Integration: viewer GET OK, viewer POST drain rejected |
| 4 | TOML config (`role` in `[[api_keys]]`), CLI key management, docs | 1 day | Config round-trip, CLI output |
| 5 | Audit log for access denied, default role = VIEWER for unspecified | 0.5 day | Full test suite |

**Total: 4.5 days**

**Roles:**
| Role | Access |
|------|--------|
| ADMIN | All endpoints (deploy, drain, shutdown, config, RBAC management) |
| OPERATOR | Operational endpoints (status, scaling, drain, schema retry, backup) |
| VIEWER | Read-only endpoints (status, metrics, logs, traces) |

**Spec:** `aether/docs/specs/rbac-spec.md`

---

## Week 3-4: Streaming MVP (0.24.0) — Preview

In-memory streaming as a preview feature. Full spec at `aether/docs/specs/streaming-spec.md`.
Provides the programming model and core runtime. Consumer groups, CDC, persistence are post-V1.

**Layered breakdown:**

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | Types: `StreamPublisher<T>`, `StreamSubscriber`, `StreamAccess<T>`, `@PartitionKey`, config records | 1 day | Compile, factory tests, serialization round-trip |
| 2 | `OffHeapRingBuffer` — produce, consume, wrap-around, retention eviction | 2 days | Unit tests (single + concurrent), benchmark |
| 3 | Annotation processor: detect stream resources, generate manifest, bump envelope version | 1.5 days | Processor unit tests, generated code compiles |
| 4 | `StreamPartitionManager` + KV types, governor-local produce/consume, CDM lifecycle | 2 days | Forge integration: produce 1000 events, consume all, verify order |
| 5 | REST endpoints (`/api/streams/*`), CLI (`aether stream`), docs | 1 day | REST round-trip, CLI output |
| 6 | Consumer error handling (retry/skip/stall), dead-letter, co-located zero-copy | 1.5 days | Failure injection test |

**Total: 9 days**

**Post-V1 streaming roadmap:**
- Phase 2: Replication + governor failover → durability without persistence
- Phase 3: PostgreSQL WAL-backed streams + transactional cursor commits → exactly-once delivery
- Phase 3 architecture: stream segments stored as PostgreSQL rows, cursor positions as transactional updates, consumer acknowledges within the same DB transaction as side effects → true exactly-once

---

## Week 5: Dashboard Wiring (0.25.0)

**Layered breakdown:**

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | Identify all required WebSocket message types, define push event records | 0.5 day | Types compile |
| 2 | Backend: WebSocket push for schema status, governors, deployment strategies | 1.5 days | WebSocket subscription test |
| 3 | Frontend: wire new panels to existing + new endpoints | 2 days | Visual verification in browser |
| 4 | Stream status panel (if streaming landed) | 1 day | Dashboard shows active streams |

**Panels:**
| Panel | Data Source | Status |
|-------|------------|--------|
| Cluster overview | `/api/status`, `/api/nodes` | Partial |
| Deployment status | `/api/deployments`, WebSocket push | Partial |
| Schema migration | `/api/schema/status` | New |
| Governor/community | `/api/cluster/governors` | New |
| Deployment strategies | `/api/deploy` (unified), `/api/ab-tests` | New |
| Worker group health | `/api/cluster/topology` + metrics | New |
| Streams | `/api/streams/*` | New (if 0.24.0 landed) |
| Node lifecycle | `/api/nodes/lifecycle` | Existing |

---

## Week 6: Soak Test + Release Pipeline (0.26.0)

### 6a. Soak Test (M — 3-5 days)

**Layered breakdown:**

| Layer | Scope | Duration | Gate |
|-------|-------|----------|------|
| 1 | Prometheus Docker Compose extension, metric scrape config | 0.5 day | Metrics visible in Prometheus |
| 2 | k6 4-hour sustained load scenario (100 RPS) | 1 day | Script runs, metrics recorded |
| 3 | Chaos phases: kill worker (hour 2), rolling restart (hour 3) | 1 day | Recovery verified, no SWIM faults |
| 4 | Pass/fail criteria automation, report generation | 1 day | Automated verdict |

**Pass criteria:** All metrics stable within 10% of 1-hour mark at 4-hour mark. Zero SWIM FAULTY after warmup. No heap growth trend.

### 6b. Release Pipeline Verification (S — 1-2 days)
- Maven Central publish end-to-end
- GitHub Actions release workflow (container images, checksums)
- `install.sh` and `upgrade.sh` scripts
- Docker image builds from released artifacts

---

## Week 7: Buffer + RC

- Fix anything soak test reveals
- Final feature catalog update
- Final changelog review
- Tag `v1.0.0-rc1`
- Announce beta period

---

## PRE-GA: Case Study Preparation

**Source:** `../jbct-loan/CASE-STUDY-PLAN.md`

Loan Processing Application — same spec implemented in JBCT slice and Spring Boot. Controlled comparison for evaluators.

| Task | When | Notes |
|------|------|-------|
| Update JBCT slice to current Aether (DB provisioning, schema migrations, blueprints) | Pre-alpha | Must be fully functional, not stubs |
| Verify Spring Boot implementation | Pre-alpha | All endpoints functional, tests passing |
| Set up dedicated repository structure | Pre-GA | Both projects build, CI validates |
| Write comparison documents (code, metrics, operations, AI generation, spec methodology) | Pre-GA | 6 documents, agent task |
| Create case-study.html for pragmaticalabs.io | GA day | Publish with release |
| Performance comparison in real cloud (EKS vs Aether cluster) | Post-GA, post-first-customer | Requires cloud budget |

---

## POST-V1 (tracked, not blocking)

| Item | Priority | Notes |
|------|----------|-------|
| Case study: cloud performance comparison (Phase 5) | High | EKS vs Aether, same hardware, same k6 scripts |
| Streaming Phase 2: replication + failover | High | Durability without persistence |
| Streaming Phase 3: PostgreSQL + exactly-once | High | Production streaming |
| Secret rotation provider implementations | High | watchRotation SPI exists, zero providers implement it |
| Capacity planning guide | High | Sizing guidance for operators |
| Upgrade path automated test (0.x → 1.0) | High | rolling-aether-upgrade.sh exists, needs CI automation |
| Consumer groups + CDC adapter | Medium | Streaming Phase 1+ |
| Log aggregation documentation | Medium | Guidance, not code |
| Error message actionability audit | Medium | Pass through all Cause messages |
| IDE support / IntelliJ plugin | Low | jbct add-slice scaffolding exists |
| Hetzner cloud integration test | Low | Spec exists, needs cloud environment |
| OOM graceful degradation guidance | Low | Document -XX:+ExitOnOutOfMemoryError recommendation |

---

## Known Risks

1. **Streaming scope creep** — MVP is in-memory preview. No persistence, no replication, no exactly-once in V1. Mark as "preview" in docs.
2. **RBAC Tier 2 scope creep** — keep it to 3 roles, static per-route map. No dynamic policies.
3. **Dashboard frontend effort** — if frontend is more than wiring, cut scope to schema + governors + streams only.
4. **Soak test infrastructure** — Prometheus integration needed. May require Docker Compose extension.
5. **`watchRotation` gap** — V1 docs should clearly state "SPI available, provider implementations coming."
6. **Ring buffer off-heap complexity** — MemorySegment API is powerful but error-prone. Layer 2 gate (unit tests + concurrent tests) is critical before integration.
