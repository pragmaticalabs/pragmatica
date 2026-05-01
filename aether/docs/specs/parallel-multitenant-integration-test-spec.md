# Parallel Multi-Tenant Integration Test Specification

**Version:** 1.0.0-rc1
**Status:** Draft
**Date:** 2026-04-21

## 1. Overview

This specification defines a strategy for running Aether's non-destructive integration suites concurrently against a single shared cluster, by enforcing per-slice isolation of every observable surface (HTTP endpoints, API keys, task groups, blueprints, storage). The parallel execution is both a test-time speedup and a functional validation of Aether's core multi-tenancy promise: a single cluster hosts many independent apps with no cross-tenant leakage.

### 1.1 Goals

| ID | Goal | Measurable Outcome |
|----|------|--------------------|
| G-1 | Every non-destructive test's HTTP traffic is uniquely prefixed | No two concurrent tests hit the same `(host, port, path)` triple |
| G-2 | API-key scope isolates test groups | Test A's API key cannot read or mutate test B's resources |
| G-3 | Task-group names carry slice prefix | `<slice>:METRICS`, `<slice>:STORAGE`, etc. never collide across tests |
| G-4 | All non-destructive suites run in one batch (or fewer, if capacity-bound) | Wall-clock ≤ slowest single suite + batch overhead |
| G-5 | Concurrent batch exercises real multi-tenant load | Post-batch: cluster reports all blueprints present, epoch advanced, no coreCount drift, no OOM |
| G-6 | Failures in one test do not cascade into others | If test A fails a timing window, tests B/C/D's outcomes are statistically independent |

### 1.2 Non-Goals

- Running destructive suites (02-chaos, 03-scaling, 05-security mutating, 12-network, 13-edge-cases) in parallel. Destructive suites kill nodes / mutate shared cluster state and remain serial, cluster-owning.
- Per-test cluster provisioning. The batch targets a single shared cluster to amortize setup.
- Capacity benchmarking. The test uses a capacity baseline but is not a perf benchmark.
- Cross-cluster federation scenarios.

### 1.3 Why this is not just a speedup

Aether's value proposition includes hosting many tenant slices on one cluster. Current tests validate each tenant alone. They say nothing about:

- Whether the routing layer correctly disambiguates concurrent traffic for different slices.
- Whether task-group scheduling handles many ACTIVE groups from different apps simultaneously.
- Whether the ClusterSync snapshot stays bounded under many-slice load.
- Whether per-slice storage/streams/config truly isolate.

Running concurrent tests is the cheapest direct probe of these multi-tenant guarantees.

## 2. Scope

### 2.1 In scope for this spec

- Non-destructive integration suites: 00-smoke, 04-streaming, 06-deployment, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 14-storage, 15-delegation.
- All example apps referenced by these suites (url-shortener, url-shortener-v2, comprehensive-persistence, jooq-xml-showcase, pg-showcase, and others in `examples/`).
- `run-tests.sh` and helper library under `aether/tests/integration/lib/`.
- `aether/tests/integration/lib/` — collision check at deploy time enforcing the prefix conventions across the parallel batch.

### 2.2 Out of scope

- Destructive suites (remain serial).
- Production deployments (this spec is test-harness-only).
- Dashboard / CLI.

## 3. Architecture

```
                            single Cluster B (5 core nodes)
                              ┌───────────────────────────┐
                              │  routing + forwarding     │
                              └──────────┬────────────────┘
                                         │
  ┌──────────────────┬───────────────────┼───────────────────┬──────────────────┐
  │                  │                   │                   │                  │
/url-shortener/...  /persist/...  /pg-showcase/...  /analytics/...  /events/...
     │                  │                   │                   │
  test group A     test group B       test group C       test group D     …
  API key A        API key B          API key C          API key D
  TG: urlsh:METRICS                   TG: pg:STORAGE                    
```

All tenants share:
- The same 5 core nodes (Rabia, KV-Store, DHT).
- The same HTTP management port (forwarding routes by slice prefix).
- The same App HTTP port (content dispatch by path prefix).

All tenants are independent in:
- HTTP path prefix (namespaced).
- API key (distinct key per tenant).
- Task group names (slice-prefixed).
- Blueprint GAV (unique artifact per app).
- Storage / stream / config resource IDs (namespaced).

## 4. Isolation Requirements

### 4.1 HTTP path prefixing (G-1)

**Rule**: every `@Http` / `@Route` endpoint in an integration-test blueprint MUST start with a unique slice base path derived from the slice-id.

**Format**: `/<slice-base>/...` where `<slice-base>` is declared in the slice's manifest (e.g. `urlshortener`, `pg-showcase`, `comprehensive`).

**Examples**:
- Before: `GET /shorten` (conflicts with anyone else's `/shorten`)
- After: `GET /url-shortener/api/v1/shorten`

**Enforcement**: this is a convention for example/test slices, **not** a global slice-processor rule — production slices may legitimately use bare paths. The parallel batch runner inspects the set of blueprints about to deploy and rejects the batch if any two share a path prefix. See §7 Phase 1.

**Migration impact**: every example blueprint's routes table + any test script hitting those routes.

### 4.2 API-key scoping (G-2)

**Rule**: every test group uses a distinct API key. The cluster admin key is reserved for cluster-management ops.

**Mechanism**: tests export `TEST_GROUP_API_KEY=aether-int-<group>-<random>` before blueprint push. Blueprint's `appHttp.apiKeys` list is seeded with that key at deploy time.

**Cross-check**: a test group's API key MUST NOT be able to authenticate against another test group's app endpoints. A negative test in the batch verifies this periodically.

### 4.3 Task-group namespacing (G-3)

**Rule**: task-group IDs carry slice prefix. `METRICS` becomes `url-shortener:METRICS`, etc.

**Mechanism**: existing `@TaskGroup` annotation accepts the prefix literally. Example slices spell the prefix in source.

**Enforcement**: same as §4.1 — runner-side collision check at batch deploy time, no slice-processor changes.

### 4.4 Blueprint / artifact uniqueness

**Already enforced** via `org:artifact:version` triple. No additional work required.

### 4.5 Resource ID namespacing

**Rule**: `@Sql`, `@PgSql`, `@Storage`, `@Stream` resource qualifier `config = "..."` strings used in example/test slices must carry the slice-id prefix (e.g., `url-shortener:database`).

**Enforcement**: runner-side collision check at batch deploy time, no slice-processor changes.

## 5. Execution Model

### 5.1 Batch partitioning

```
                 run-tests.sh --parallel=auto
                          │
                          ▼
           ┌─────────────────────────────────┐
           │  Partition non-destructive      │
           │  suites into batches of size N  │
           │  where N starts at total count  │
           └──────────────┬──────────────────┘
                          │
                          ▼
            ┌──────────────────────────────┐
            │  Deploy all batch blueprints │
            │  (parallel push to cluster)  │
            └──────────────┬───────────────┘
                           │
                           ▼
                 ┌─────────────────────┐
                 │ Run tests in batch   │
                 │ concurrently (bash   │
                 │ background jobs or   │
                 │ xargs -P)            │
                 └──────────┬───────────┘
                            │
                            ▼
                  ┌──────────────────────┐
                  │ Collect results;     │
                  │ fail fast on any red │
                  └──────────────────────┘
```

### 5.2 Batch size discovery

- **Start**: all non-destructive suites in one batch.
- **If saturation detected** (OOM on any node, API queue overflow, consensus epoch stall, >X% error rate from a specific test): bisect — halve the batch size. Record the maximum concurrent suite count as a capacity baseline per host profile (Docker local, Docker remote, Hetzner small, Hetzner large).
- **Baseline publishing**: per-environment recommended batch size lives in `run-tests.sh` or `env/<environment>.toml` so future CI runs pick up the known-good value automatically.

### 5.3 Pre-batch invariants

Before starting the batch:
- Cluster healthy (5 core nodes, leader elected, quorum).
- ClusterGenerationSnapshot quiesced.
- No prior-run blueprints remaining (`aether blueprint list` returns empty).

### 5.4 Post-batch invariants

After the batch completes (pass or fail):
- All batch blueprints present in `aether blueprint list`.
- Cluster still healthy (no node churn, no OOM crash).
- Epoch advanced by bounded amount (no runaway reprojection loop).
- No stray process left behind (all test PIDs collected).

## 6. Capacity Contracts

| Environment | Expected batch size | Heap per core node | Expected wall-clock |
|---|---|---|---|
| Docker local (MacBook Pro) | 3–5 | 512m | 5–10 min |
| Docker remote (192.168.0.71, 32 GB) | 8–12 | 512m | 4–8 min |
| Hetzner CX21 × 5 | ≥ 10 | 1024m | 3–6 min |

These are expected values to be validated and updated once the spec is implemented.

## 7. Implementation Phases

### Phase 1 — Naming hygiene (1–2 days)

The prefix discipline is a convention for example/test slices, **not** a global slice-processor rule. Enforced at the runner layer where it actually matters; production slices unaffected.

- Migrate all example blueprints in `examples/` to the prefix convention: routes (§4.1), task-group ids (§4.3), resource qualifier config strings (§4.5).
- Migrate all integration test scripts that hit those endpoints (`aether/tests/integration/suites/`).
- Add a `--check-isolation` step to `run-tests.sh` parallel mode: scan the set of blueprints about to deploy and reject the batch if any two collide on `(host, port, path-prefix)`, task-group id, or resource id. Runs before `aether deploy`.

### Phase 2 — Batch runner (1–2 days)

- `run-tests.sh --parallel[=N]` flag; default N = total non-destructive suite count.
- Parallel blueprint push step (replaces serial `for suite in ...; do deploy ...; done`).
- Parallel suite execution using bash job control (`&` + `wait`) with per-suite log capture.
- Pass/fail aggregation and fail-fast option.
- **Reuse the batch as steady-state load for destructive suites**: the same concurrent per-tenant traffic profile that drives the non-destructive batch becomes the background load for the destructive-suite window. See §7a for the chaos-under-real-load contract. This replaces today's trivial synthetic `/health/live` GET-only load with actual per-tenant application traffic, and turns chaos tests into multi-tenant resilience validation.

### Phase 3 — Capacity baselines (0.5 day)

- Run the parallel batch on each target environment (Docker local, Docker remote, Hetzner).
- Bisect where necessary to find stable batch size.
- Record baselines in `env/*.toml`.

### Phase 4 — CI integration (0.5 day)

- Replace sequential `run-tests.sh` call in CI with `run-tests.sh --parallel`.
- Keep serial mode as `--serial` for debugging specific failures.

Total estimate: **4–6 engineering days**, fits RC1 post-stabilization window.

## 7a. Destructive Suites Run Against the Live Batch as Load

This section turns the parallel batch into a realistic load generator for the destructive suites. It is the single biggest product-validation payoff in the spec — it replaces the current synthetic single-endpoint load with production-shaped, multi-tenant, multi-path concurrent traffic.

### 7a.1 Motivation

A k6 script — or any synthetic load generator — uniformly pounds one or a few endpoints with GET traffic at a target rate. That probes one dimension of cluster behavior (HTTP pipe throughput) and misses almost everything else:

- No variety in handler types: streams, pub-sub, SQL, PgSql, storage, scheduled tasks are all silent.
- No task-group diversity: typically a single slice's endpoints are hit, so only 1–2 task groups are active.
- No forwarding variety: all requests land on the same routing decision.
- No state accumulation: nothing grows a stream cursor, no consumer-group assignment churns, no schema migration runs concurrently.
- Traffic is uniform rate, not bursty or phase-aligned with anything.

The parallel non-destructive batch runs ~10 independent apps, each exercising a different resource stack (`@Sql`, `@PgSql`, `@Http`, streams, pub-sub, storage, scheduled tasks). Each test inherently generates its own traffic profile — inserts, queries, stream publishes, consumer reads, etc. When that batch is already running, the cluster is under genuine production-shaped load. Layering a chaos event on top — kill leader, scale down, partition a node — is a dramatically more meaningful resilience test.

### 7a.2 Contract

A destructive suite, when invoked via `run-tests.sh --parallel --with-destructive=<suite>`, MUST:

1. Wait for the non-destructive batch to reach steady state (all blueprints deployed, all target-instance counts ACTIVE, epoch quiesced).
2. Declare the chaos window start (`log_info "CHAOS START ts=<epoch>"`).
3. Execute the destructive scenario (kill, scale, partition).
4. Observe the cluster's recovery within the suite's existing SLA.
5. Declare the chaos window end (`log_info "CHAOS END ts=<epoch>"`).
6. Wait for post-chaos quiescence and hand control back.

Throughout, the non-destructive batch continues to run. Its per-tenant error/latency metrics are collected and joined against the chaos window.

### 7a.3 Per-tenant SLA under chaos

Each non-destructive test, when participating in a chaos-window run, advertises its per-tenant tolerance:

| Tolerance level | Max error rate during chaos window | Max p99 latency increase | Rationale |
|---|---|---|---|
| `CHAOS_TOLERANT_HIGH` | ≤ 2% | ≤ 3× baseline | Stateless read-heavy (health, status, blueprint list) |
| `CHAOS_TOLERANT_MEDIUM` | ≤ 5% | ≤ 5× baseline | Typical CRUD, stream publish, single-hop forwarding |
| `CHAOS_TOLERANT_LOW` | ≤ 10% | ≤ 10× baseline | Schema migration, task-group reassignment, multi-hop forwarding |
| `CHAOS_INTOLERANT` | Any error = fail | — | Consensus-critical invariants (leader election, config apply) |

The destructive suite's overall pass criterion becomes: for every tenant in the batch, per-tenant SLA met for its declared tolerance level. A tenant that violates its SLA does not fail the destructive suite by itself, but the aggregated violation count is published as a test-run metric. Hard failures (CHAOS_INTOLERANT) short-circuit to red.

### 7a.4 Scenarios

The existing destructive-suite scenarios map cleanly onto the parallel-batch load:

- **02-chaos/test-kill-leader**: all tenants see their traffic briefly refuse → recover within leader-election SLA. Per-tenant error rate is the direct measurement.
- **02-chaos/test-kill-under-load**: kill a non-leader during batch. Tenants whose task groups were assigned to the killed node should recover within reassignment SLA.
- **02-chaos/test-kill-multiple**: kill two non-leaders concurrently. Quorum preserved, batch continues with higher error rate.
- **03-scaling/test-02-scale-up**: cluster scales 5→7 while tenants continue. New nodes accept traffic and join task groups.
- **03-scaling/test-03-scale-down**: scales 7→5. Tenants experience reassignment traffic blip.
- **05-security/test-cert-rotation**: certs rotate while all tenants active; each tenant verifies its API key still authenticates post-rotation.
- **12-network/test-partition**: partition a node from the cluster while tenants continue; partitioned node's tenants receive forwarded traffic.
- **13-edge-cases/test-stale-route-cleanup**: route cleanup after node kill with live tenant traffic on affected paths.

### 7a.5 Why this is harder than any synthetic load

The key quality difference:

- **Heterogeneous handler mix**: each slice tests a different resource stack, so the cluster is simultaneously serving SQL reads, stream appends, consumer-group acks, schema migrations, and HTTP forwarding. A synthetic load tool hits one dimension at a time.
- **Stateful, not uniform**: tenant traffic drives state growth and convergence logic (stream compaction, schema migration progress, consumer-group rebalance). Uniform GET traffic doesn't.
- **Task-group diversity**: all declared task groups (METRICS, STORAGE, STREAMING, etc. across every tenant) are simultaneously ACTIVE. Chaos forces simultaneous re-assignment of all of them.
- **Forwarding and routing variance**: many path prefixes, many API keys, many target-instance sets — every routing and forwarding decision variant is exercised concurrently.
- **Free, no separate tooling**: it's the same test runner, the same cluster, the same log collection — just with the destructive event scheduled during the batch window.

A k6 script simulates "100 users hitting /api/users/list". The parallel batch simulates "10 different apps each running their own integration test, together under chaos". The second is closer to a real production incident than anything we could credibly synthesize.

### 7a.6 Implementation notes

- The `run-tests.sh` orchestration becomes: start batch → wait-for-steady-state → fork destructive-suite-driver → wait for both → aggregate results.
- Per-tenant error/latency metrics are captured from each tenant's own test-scope log; the destructive-suite driver does not need to instrument tenants.
- The chaos window is bounded by epoch markers in the ClusterGenerationSnapshot (use existing `await_generation_quiesced` as the end fence).
- Debuggability: per-tenant logs include the chaos-window timestamps, so post-run analysis can filter errors to the chaos window.
- Phase 2 of the roll-out MAY defer 7a to a Phase 2.5 sub-phase if parallel batch stability needs to land first.

## 8. Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| Flaky tests amplified by concurrent load | Phase 1 preflight identifies flaky suites; they are pinned serial until fixed |
| Diagnostic difficulty on failure | Per-test log files + epoch markers at start/end of each test |
| Accidental coupling via shared task-group state | Runner `--check-isolation` step rejects the batch before deploy (§7 Phase 1) |
| Remote host OOM | Batch size discovery + capacity baseline |
| Some example blueprints hard to migrate | Migration is per-blueprint and reversible; runner `--check-isolation` only fires when `--parallel` is used, so serial CI keeps working through the transition |

## 9. Testing the Spec's Implementation

A dedicated validation test — `run-tests.sh --parallel --validate-isolation` — runs the non-destructive batch and asserts:

- No two tests sent a request to the same path prefix.
- Each test's API key authenticated only against its own endpoints.
- No task-group IDs collided in the topology.
- `aether blueprint list` final state matches the expected set.

A second test — `--negative-isolation` — deliberately configures two tests to share a prefix and asserts the batch runner rejects deployment.

## 10. Out-of-Spec Future Work

- Run destructive suites in parallel against separate (isolated) clusters. Requires multi-cluster fixture support.
- Scheduled nightly "chaos batch": parallel non-destructive load + one destructive test concurrently, asserting non-destructive tests survive.
- Dashboard / CLI surface for querying "current cluster tenants" (useful debugging aid).

## 11. References

- `aether/docs/architecture/03-rate-limiting.md` — per-tenant rate limit philosophy, relevant to §4.1 path-prefix isolation.
- `aether/docs/specs/cluster-generation-spec.md` §14 — snapshot quiescence contract used in §5.4.
- Existing non-destructive suite inventory: `aether/tests/integration/suites/0*/`, `1*/`.
- `examples/` — example apps requiring migration.
