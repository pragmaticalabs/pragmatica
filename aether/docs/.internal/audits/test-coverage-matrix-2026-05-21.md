# Test Coverage Matrix — 2026-05-21

| Field | Value |
|---|---|
| Branch | `release-1.0.0-rc1` |
| HEAD | `a52dd99d4` |
| Method | 5 parallel agents walking `aether/docs/reference/feature-catalog.md` section-by-section |
| Detail | `integration-test-audit-2026-05-21-partials/coverage-*.md` (5 source files) |
| Companion | `integration-test-audit-2026-05-21.md` (correctness audit) |

## Executive summary

**176 features classified across 19 sections. Top-line: 24 COVERED / 48 PARTIAL / 102 NONE / 2 N/A.**

Strict coverage is **~14%**. Half of NONE is expected (libraries, Forge-runtime, cloud provider unit tests). The other half — **~40-50 features** — is RC1-impactful: features the catalog claims Complete or Battle-tested but the integration suite has no strict assertion for.

| Section | Total | COVERED | PARTIAL | NONE | RC1 gaps |
|---|---|---|---|---|---|
| Deployment & Lifecycle | 15 | 2 | 7 | 6 | 4 (auto-scaling, slice invoker, A/B testing, deploy strategies) |
| Scaling & Control | 6 | 1 | 0 | 5 | 5 (minInstances, controller config, aspects, autoscaler) |
| Cluster & Consensus | 6 | 4 | 2 | 0 | 1 (cluster-generation as SUT, only as scaffolding) |
| Networking & Routing | 15 | 4 | 4 | 7 | 5 (passive LB, NodeRole.PASSIVE, KV-store typed-write) |
| Messaging (Pub-Sub) | 4 | 1 | 2 | 1 | 1 (sync ack untested) |
| Scheduled Invocation | 7 | 0 | 5 | 2 | 7 (entire subsystem PARTIAL/NONE) |
| Storage & Data | 8 | 0 | 5 | 3 | 8 (HLC, DHT versioned writes, TimeoutsConfig, KV-Store backup) |
| Observability & Metrics | 9 | 4 | 4 | 1 | 3 (historical metrics, percentiles, ring-buffer feed) |
| Resource Provisioning | 8 | 0 | 2 | 6 | 6 (HTTP client interceptors, PgNotification, all of #44/47/48) |
| Cloud Integration | 18 | 0 | 0 | 18 | (structural: integration-suite layer doesn't gate cloud providers) |
| Management | 20 | 2 | 4 | 12 (+2 N/A) | 6 (REST API weakened by 6 RC1-blockers; consumer groups, sync ack, etc.) |
| Developer Tooling | 14 | 0 | 1 | 13 | 1 (manual test plan covers; the rest is build/process) |
| Reusable Libraries | 8 | 0 | 1 | 7 | (Expected — unit-test domain) |
| Node Operations | 3 | 3 | 0 | 0 | 0 (strongest section in catalog) |
| Security & Resilience | 10 | 2 | 6 | 2 | 8 (mTLS, RBAC, cert lifecycle, blueprint membership guard, envelope versioning) |
| Embeddable Runtime | 3 | 0 | 0 | 3 | 2 (#74 Maven repos, #75 LB — not Forge-domain) |
| Worker Pools | 22 | 1 | 5 | 16 | 11 (systemic: WORKER-role topology never deployed in integration tests) |
| **Total** | **176** | **24** | **48** | **102** (+2 N/A) | **~68 RC1 gaps** |

(Worker-Pool subsection inflates by 16 NONE because the integration topology only deploys CORE-role nodes; not all 22 worker-pool features need to migrate to integration coverage, but the systemic absence is a real gap.)

---

## RC1-impacting gaps — Complete features with no strict coverage

This is the new Phase 4 backlog (test additions) on top of the 30 RC1-block test fixes already identified in the correctness audit. Each is a Complete-or-Battle-tested feature with PARTIAL or NONE classification that does **not** belong to an expected-NONE domain (libraries, Forge, cloud unit-tests).

### Domain A — Scheduled Invocation (7 items, all gaps)

The entire scheduled-invocation surface is PARTIAL/NONE. Three of the gaps are already RC1-blockers (`test_pause_task`, `test_resume_task`, `test_task_last_execution_advances`); the rest need new tests:
- Fixed-rate + cron scheduling — never asserted to actually fire on schedule
- Pause/resume — covered (badly) by the three RC1-blockers; needs honest assertions via `/api/scheduled-tasks/inject` (Phase 2 P5)
- Per-task last-execution time — RC1-blocker #16
- Recovery after node death — never tested

**Phase 4 cost:** ~5 new test functions; P5 (inject endpoint) unblocks them.

### Domain B — Storage & Data (5 items)

- **#105 HLC** (hybrid logical clock) — no test asserts monotonicity or wall-clock skew tolerance
- **#106 DHT versioned writes** — no test exercises version-conflict resolution
- **#107 TimeoutsConfig** (13 subsystem timeout groups) — no test validates timeout-takes-effect for any group
- **#206 KV-Store durable backup** — `/api/backups` endpoints + restore flow uncovered
- **AHSE / 14-storage** — already RC1-blockers (#23-#26)

**Phase 4 cost:** ~4 new test functions; some need failure injection (HLC skew, DHT version conflict).

### Domain C — Worker Pools (11 items, systemic)

Single biggest gap: integration topology only deploys CORE-role nodes. Worker pool was added in v0.21+ and the test infra never extended to WORKER-role.
- #81 Worker node, #84 CDM pool awareness, #85 Worker management API
- #97-101 Multi-group / zones / community / Governor address
- #132 Role-aware unified node (CORE→WORKER promotion)
- #151 Community-aware replication
- #153 Replication cooldown
- #156 Compound KV-Store key types (storage-layer assertion)

**Phase 4 cost:** Adding a 5-core + 2-worker cluster topology to the test environment (compose file change), then ~8-10 new test functions. Bigger lift — likely 2-3 days of test-infra work + 2-3 days of test writing. **Could be deferred to RC2 if worker-pool feature is gated behind a flag for RC1.**

### Domain D — Security & Resilience (8 items)

All security RC1-blockers (5 in the correctness audit) plus:
- **#88 Inter-node mTLS** — `test_tls_active` is tautology (RC1-blocker #3)
- **#90 Certificate lifecycle** — `test_rotation_under_load` self-admittedly vacuous (RC1-blocker #4)
- **#92 RBAC blueprint operator overrides** + strengthen_only — uncovered
- **#91 TLS default for containers** — never asserted
- **#60 Blueprint membership guard** — security boundary unverified
- **#56 Envelope format versioning** — runtime compat check never exercised
- **#203 Security hardening (RC1)** — ALPN pinning, plaintext-mode rejection need E2E tests

**Phase 4 cost:** ~6 new tests + the 6 RC1-blocker fixes already in Phase 4. Some require Phase 2 P3 (`tlsEnabled` field).

### Domain E — Networking & Routing (5 items)

- **#67 Passive LB**, **#68 NodeRole.PASSIVE** — Complete in catalog but zero tests. LB module is built into the test image (per README) but no suite exercises it. Major coverage gap.
- **#17 KV-Store** — no per-typed-key family test, no cross-node typed-write consistency assertion
- **#77 Topology graph** REST + WebSocket endpoints — uncovered

**Phase 4 cost:** ~5 new test functions. Passive LB test needs the test topology to include LB sidecars (which the current compose files have but no suite uses).

### Domain F — Deployment & Lifecycle (4 items)

- **#3 Unified deployment strategies** — 4 of 18 RC1-blockers (promote tests + dead rollback)
- **#7 CPU-based auto-scaling** (Battle-tested) — 03-scaling exercises only manual scale, never the autoscaler controller
- **#20 SliceInvoker** (Battle-tested) — service-to-service invocation has zero `SliceInvoker` evidence in suites
- **#135 A/B Testing** — header/cookie/ScopedValue split untested
- **#10 Dynamic controller config**, **#12 Dynamic aspects**, **#8 minInstances** — zero coverage

**Phase 4 cost:** Promote test fixes are already in Phase 4. Autoscaler + A/B + SliceInvoker require ~5 new tests + some test fixtures (CPU stress to trigger scale).

### Domain G — Management (6 items)

- **#49 REST API** — Battle-tested but weakened by 6 RC1-blockers (already in Phase 4)
- **#51 WebSocket streams** — zero coverage
- **#52 Dynamic log levels** — `aether logging set` exists but no test invokes it
- **#213 Cluster init wizard** — zero coverage (CLI exists, never exercised)
- Plus consumer groups, sync ack, batch replication, compression, encryption, transactional cursors, compound retention (all NONE)

**Phase 4 cost:** ~5 new tests; mostly mechanical CLI invocations + assertion of state changes.

### Domain H — Resource Provisioning (6 items)

- **#46 HTTP client** — `08-resources/test-http-client.sh` is misnamed; tests mgmt API, NOT the outbound HTTP client
- **#47 HTTP client interceptors** — retry/CB/rate-limit/logging/metrics — uncovered
- **#48 HTTP client request lifecycle** — uncovered
- **#209 PgNotification** — zero coverage

**Phase 4 cost:** ~4 new tests. Some need a test slice that uses the resources (HTTP client outbound calls, PG notify/listen).

### Domain I — Out-of-scope NONEs (expected, no action)

These are correctly NONE at the integration-suite layer:
- All **Reusable Libraries** (Pragmatica Core, JBCT, peglib, AHSE) — unit-test domain
- **Embeddable Runtime / Ember / Forge** — has its own `forge-tests` module
- **Cloud Integration** (18 features) — provider behavior tested in `aether/tests/cloud/` and provider unit tests; integration-suite layer is provider-agnostic
- **Developer Tooling** build-time / scaffolding / process items (12 of 14 features) — unit-tested or manual-DX-tested

---

## Summary of action items

| Action | Count | Phase | Notes |
|---|---|---|---|
| Test fixes (audit §2.2 RC1-blockers) | 30 | Phase 4 | Already scoped |
| New tests for Complete-but-NONE/PARTIAL features | ~40-45 | Phase 4 + Phase 7 | Many depend on Phase 2 product changes |
| Test-topology change (add WORKER-role node) | 1 | Phase 4 or RC2 | ~2-3 days; could defer if worker-pool is RC2 feature |
| Feature-catalog reclassifications | TBD | Phase 5 | Some "Complete" features should be "Partial" pending coverage |

**Re-estimated Phase 4 effort:** ~6-8 days (up from 3-5d) given the test-additions backlog. Total plan duration revises from ~3 weeks to **~4-5 weeks** at the RC1 bar.

**Decision deferred to the user:**
1. **Worker Pools** — close the systemic gap (test-topology change + ~10 tests) for RC1, or defer to RC2 with the worker-pool feature gated as `Partial`?
2. **Autoscaler (#7 CPU-based auto-scaling)** — Battle-tested with zero tests is incongruous; either downgrade catalog status or add ~3 tests now.
3. **Catalog hygiene** — should we mark features as `Partial` in the catalog where integration coverage is PARTIAL? That documents reality but downgrades the catalog's marketing tone.

---

## How to use this matrix

1. **Phase 4 PR backlog:** Each RC1-impactful gap in Domains A-H becomes a Phase 4 issue or PR.
2. **Phase 5 charters:** When writing a suite's CHARTER.md, cite the catalog features it covers. The matrix gives you the cross-reference.
3. **Future audits:** Re-spawn the 5 coverage agents at any tag boundary. Counts shrinking = remediation working.

## References

- Detailed per-section evidence: `integration-test-audit-2026-05-21-partials/coverage-{deploy-scale-cluster-net,messaging-sched-storage-obs,resources-cloud-mgmt,devtools-libs-nodeops-security,embeddable-workers-rollup}.md`
- Test correctness ratings: `integration-test-audit-2026-05-21.md` §1
- Feature catalog source: `aether/docs/reference/feature-catalog.md`
- Production-readiness plan: `aether/docs/internal/production-readiness-plan-2026-05-21.md`
