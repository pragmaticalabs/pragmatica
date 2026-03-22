# V1.0.0 Roadmap

## Target: v1.0.0-rc1 by April 25, 2026

### Current State
- 130/141 features Complete/Battle-tested
- Schema migration failure recovery delivered (0.21.2)
- Docker scaling test passing (0% failures, 12 nodes)
- SWIM stabilized (InetSocketAddress codec fix)
- Core value objects added to core module

### Release Cadence
- 0.22.0: Audit trail + docs cleanup
- 0.23.0: RBAC Tier 2 MVP
- 0.24.0: Dashboard wiring
- 0.25.0: Soak test + release pipeline
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

Currently covered:
- Auth success/failure
- Management access
- WebSocket auth
- Deployment start/promote/rollback/complete/auto-rollback

Missing (add `AuditLog` calls):
- Schema migration: started, completed, failed, manual retry
- Scaling decisions: reconciliation scale-up/down, CDM adjustments
- Config changes: dynamic config overlay updates
- Backup/restore operations
- Node lifecycle: drain, activate, shutdown, decommission
- Blueprint deploy/undeploy
- Secret resolution (success/failure, not the secret itself)

**Files to modify:** SchemaOrchestratorService, ClusterDeploymentManager, BackupService, NodeLifecycleManager, DynamicConfigRoutes, BlueprintService

---

## Week 2: RBAC Tier 2 MVP (0.23.0)

### Scope
Minimum viable role-based access control for production teams.

**Roles:**
| Role | Access |
|------|--------|
| ADMIN | All endpoints (deploy, drain, shutdown, config, RBAC management) |
| OPERATOR | Operational endpoints (status, scaling, drain, schema retry, backup) |
| VIEWER | Read-only endpoints (status, metrics, logs, traces) |

**Implementation:**
1. `Role` enum: ADMIN, OPERATOR, VIEWER
2. Per-route role requirement: annotation or static map in each `*Routes.java`
3. `SecurityValidator` enforcement: check API key's role against route requirement
4. TOML config: `[[api_keys]]` entries get `role = "ADMIN"` field (default: ADMIN for backward compat)
5. CLI: `--role` option for key management

**Spec exists:** `aether/docs/specs/rbac-spec.md`

**Files:** SecurityValidator, ManagementServer, all *Routes.java files, TOML config parser, AetherCli

---

## Week 3: Dashboard Wiring (0.24.0)

### Scope
Connect existing REST/WebSocket APIs to the management dashboard frontend.

**Panels needed:**
| Panel | Data Source | Status |
|-------|------------|--------|
| Cluster overview | `/api/status`, `/api/nodes` | Partial |
| Deployment status | `/api/deployments`, WebSocket push | Partial |
| Schema migration | `/api/schema/status` | New |
| Governor/community | `/api/cluster/governors` | New |
| Deployment strategies | `/api/canary/list`, `/api/bluegreen/list`, `/api/ab/list` | New |
| Worker group health | `/api/cluster/topology` + metrics | New |
| Node lifecycle | `/api/nodes/lifecycle` | Existing |

**Approach:** All REST endpoints exist. Dashboard needs frontend wiring + WebSocket subscription for real-time updates.

---

## Week 4: Soak Test + Release Pipeline (0.25.0)

### 4a. Soak Test (M — 3-5 days)
**Foundation:** Docker scaling test infrastructure from 0.21.1

**Scenario:**
- 5 core + 7 worker nodes, PostgreSQL
- Deploy url-shortener blueprint
- k6 sustained load: 4 hours at 100 RPS
- Monitor via Prometheus scrape:
  - JVM heap usage (no unbounded growth)
  - GC pause time (no degradation)
  - Connection pool active/idle counts
  - SWIM membership stability (zero FAULTY events after warmup)
  - Request latency p99 (no drift)
- Pass criteria: all metrics stable within 10% of 1-hour mark at 4-hour mark

**Chaos during soak:**
- Hour 1: steady state baseline
- Hour 2: kill 1 worker, verify recovery
- Hour 3: rolling restart of 2 core nodes
- Hour 4: steady state, verify no leaks

### 4b. Release Pipeline Verification (S — 1-2 days)
- Verify Maven Central publish end-to-end
- Verify GitHub Actions release workflow (container images, checksums)
- Test `install.sh` and `upgrade.sh` scripts
- Verify Docker image builds from released artifacts

---

## Week 5: Buffer + RC

- Fix anything soak test reveals
- Final feature catalog update
- Final changelog review
- Tag `v1.0.0-rc1`
- Announce beta period

---

## POST-V1 (tracked, not blocking)

| Item | Priority |
|------|----------|
| Secret rotation provider implementations | High |
| Capacity planning guide | High |
| Upgrade path automated test (0.x → 1.0) | High |
| Log aggregation documentation | Medium |
| Error message actionability audit | Medium |
| IDE support / IntelliJ plugin | Low |
| Hetzner cloud integration test | Low |
| OOM graceful degradation guidance | Low |

---

## Known Risks

1. **RBAC Tier 2 scope creep** — keep it to 3 roles, static per-route map. No dynamic policies.
2. **Dashboard frontend effort** — if frontend is more than wiring, may need to cut scope (schema + governors only).
3. **Soak test infrastructure** — Prometheus integration needed. May require Docker Compose extension.
4. **`watchRotation` gap** — V1 docs should clearly state "SPI available, provider implementations coming" rather than claiming rotation support.
