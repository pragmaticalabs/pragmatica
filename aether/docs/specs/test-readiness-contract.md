# Test Readiness Contract

Status: RC1. Authoritative reference for integration-test setup helpers and operator readiness probes.

This document pins the canonical definitions that integration tests use to decide *"is the cluster ready?"*, *"how many nodes do we have?"*, *"which health endpoint answers which question?"*. Before this contract existed, three helpers claimed to test readiness with three different definitions and silently disagreed when the cluster was mid-transition; two helpers claimed to count nodes from two different sources and silently disagreed when nodes were JOINING.

The contract codifies what each definition actually measures, when to use it, and which API endpoint backs it. New helpers and new suites MUST cite this contract; deviations require a written rationale in the deviating site.

---

## 1. "Cluster is ready"

### 1.1 The canonical contract

A cluster is **ready** when ALL of the following hold simultaneously:

1. **Generation snapshot is converged** — `/api/cluster/generation` reports `core.members[]` with cardinality `≥ expected` (default `expected = NODE_COUNT`). The seed-node lifecycle write bug is fixed — `ClusterDeploymentState.handleNodeAdded` now plants a `JOINING` `NodeLifecycleKey` for seed nodes, so the initial leader's own entry is present from cluster bootstrap; see §6.
2. **Leader is elected** — `/api/nodes/status` reports `cluster.leaderId` ≠ `"none"` and ≠ empty.
3. **Active-core floor is met** — `/api/cluster/topology` reports `coreCount ≥ expected - 1`. (The `expected-1` floor tolerates one node lagging the consensus write of `ON_DUTY` while the rest of the cluster is operational — bounds the RC2 `MembershipView` convergence lag.)

Properties (1)–(3) describe the cluster's *self-view* — the consensus plane's authoritative record of cluster shape. The contract is purely cluster-side; no per-node port probing. (An earlier revision added a Property 4 that iterated `MGMT_PORT..MGMT_PORT+N-1` calling `/health/ready` per port, but it broke under CTM auto-heal: replacement nodes are provisioned at ports outside that fixed range, so post-chaos tests cascaded with phantom "node X not ready" failures even though the cluster was operationally healthy. Tests that need per-node readiness verification should probe `/health/ready` directly with the appropriate port list.)

### 1.2 Canonical helper

`wait_for_cluster_ready [timeout] [expected_count]` in `aether/tests/integration/lib/cluster.sh`. Default `timeout=120s`, default `expected=NODE_COUNT` (strict full size).

**Default usage** (`wait_for_cluster_ready` with no expected): expects the full cluster — `members ≥ NODE_COUNT`, leader elected, `active cores ≥ NODE_COUNT - 1`. Property 3's `N-1` floor tolerates the RC2 `MembershipView` convergence lag.

**Lenient usage** (`wait_for_cluster_ready <timeout> $((NODE_COUNT - 1))`): chaos tests legitimately operating at N-1 mid-recovery can pass `NODE_COUNT-1` explicitly to acknowledge the expected degraded baseline.

**Fast paths**:
- If the cluster is already ready at call time, the helper returns in 0s without entering the `wait_for` polling loop.
- If `MGMT_ENTRY_POINT/health/live` doesn't respond within 1s, the helper fast-fails immediately rather than wasting the full timeout on an unreachable cluster (caps the cascading slowdown when one suite leaves the cluster broken for downstream suites).

### 1.3 Why "ready" is not the same as "healthy"

Healthy ≠ ready.
- "Ready" = "I can accept new work right now". A draining node is not ready (it's still alive and serving in-flight requests but rejects new ones).
- "Healthy" = "I'm operating within expected parameters". A draining node is still healthy (drain is a deliberate operator action, not a fault).

Tests check ready before submitting requests; operators check healthy on dashboards.

### 1.4 Deprecated helpers (RC1 hard-cut)

| Old helper | Status | Notes |
|---|---|---|
| `is_cluster_ready` | Folded into `wait_for_cluster_ready` (snapshot check, no wait) | Predicate-only check still available as the inner predicate of `wait_for_cluster_ready` |
| `wait_for_cluster` | Folded into `wait_for_cluster_ready` | Was just a wrapper around `is_cluster_ready` via `wait_for` |
| `wait_for_all_nodes_ready` | Folded into `wait_for_cluster_ready` (now item 4 of the canonical contract) | Per-node `/health/ready` loop is now part of the canonical check |

---

## 2. Node count vocabulary

Two endpoints expose what looks like a "node count" but measure different things. Tests MUST use the name that matches the semantic they want.

### 2.1 `cluster_member_count` (generation snapshot)

Source: `/api/cluster/generation` → count of entries in `core.members[]`.

Semantic: every node the cluster knows about, including:
- nodes in `JOINING` state (not yet ON_DUTY)
- nodes in `DRAINING` state (not yet decommissioned)
- nodes in any transient state

Use when:
- Asserting "the cluster has decided on N members" regardless of whether they're all yet operational
- Counting expected vs observed cardinality during convergence
- Driving fan-out loops (e.g., "for each member, do X") where you want to include lagging nodes

Do NOT use when:
- Asserting how many nodes are actually serving traffic (use `cluster_active_core_count`)

### 2.2 `cluster_active_core_count` (topology snapshot)

Source: `/api/cluster/topology` → `coreCount` field.

Semantic: nodes that are simultaneously ON_DUTY (FSM intent) AND reachable in the aggregated reachability snapshot (operator-visible healthy).

Use when:
- Asserting "N nodes are currently operational" for service-level checks
- Computing "did the cluster lose enough nodes to break quorum?"
- Operator-facing assertions about service capacity

Do NOT use when:
- Counting all members regardless of state (use `cluster_member_count`)

### 2.3 When the two values diverge

Divergence is **normal** during:
- Node startup (`member_count` includes JOINING; `active_core_count` does not — counts only ON_DUTY)
- Node drain (`member_count` still includes the draining node; `active_core_count` excludes it once FSM commits DRAINING)
- Network partitions (`active_core_count` drops to reachable cores; `member_count` doesn't change until decommission)

Tests should EXPECT divergence during these phases. Asserting equality during transitions is a test bug.

### 2.4 Deprecated names (RC1 hard-cut)

| Old name | New name | Rationale |
|---|---|---|
| `cluster_node_count` | `cluster_member_count` | "node count" was ambiguous about whether transient states count |
| `cluster_node_count_on_duty_healthy` | `cluster_active_core_count` | "on-duty-healthy" was implementation-specific (FSM state name + reachability term); "active core" is operator-readable |

---

## 3. Health endpoint contract

Four endpoints expose health information. Each has a distinct semantic.

| Endpoint | Status code semantics | Body shape (status field) | Source of truth | Intent |
|---|---|---|---|---|
| `GET /health/live` | 200 if JVM is responding; never 5xx for liveness-only check | `{"status": "UP"\|"DOWN", "nodeId", "state", "ready"}` | In-memory NodeState ∈ {STARTING, JOINING, ACTIVE, DRAINING, STOPPED} | Liveness probe (k8s livenessProbe). "Is the process alive?" |
| `GET /health/ready` | 200 if ready to accept new work; 503 if not ready | `{"status": "UP"\|"DOWN", "nodeId", "state", "ready", "components[]"}` | Composite: consensus + quorum + routes + lifecycle | Readiness probe (k8s readinessProbe). "Should the load balancer send me traffic?" |
| `GET /api/health` | 200 always (returns body even if unhealthy) | `{"status": "healthy"\|"unhealthy", "ready", "quorum", "nodeCount", "connectedPeers", ...}` | Aggregated node + cluster view | Legacy direct check; use before LB available. Operator dashboard. |
| `GET /api/nodes/status` | 200 always | Full cluster overview (see `StatusResponse` in `ManagementApiResponses.java`) | Composite: KV ∪ MembershipView ∪ topology ∪ leader | Cluster-wide topology query; "who's the leader?", "what's the cluster shape?" |

### 3.1 When to use which

Question: **"Is THIS node's JVM process running?"** → `/health/live`. Cheapest possible probe; returns 200 even during drain.

Question: **"Should THIS node receive new traffic?"** → `/health/ready`. Returns 503 during drain, during startup before consensus, during quorum loss. Read by the LB.

Question: **"Is the WHOLE CLUSTER operational?"** → `/api/health`. Aggregated. Use when you don't want to construct a per-node fan-out.

Question: **"Who's the leader / how many members / what's the topology?"** → `/api/nodes/status`. Full picture, not a probe; use for orchestration logic.

### 3.2 What NOT to do

- ❌ Don't use `/api/health` as a per-node readiness probe (it's aggregated; doesn't tell you if THIS node is ready)
- ❌ Don't use `/health/live` to decide whether to send traffic (a draining node is alive but should not receive new requests)
- ❌ Don't use `/api/nodes/status` for liveness probes (it's expensive — full KV + MembershipView + topology join)
- ❌ Don't use `/health/ready` to count nodes (it returns one node's view, not a cluster cardinality)

---

## 4. Error rate thresholds

Different test classes use different error rate floors. The threshold should reflect the **disruption magnitude** of the test scenario, not be arbitrary.

| Test class | Threshold | Rationale |
|---|---|---|
| Soak (long-running, steady-state, no disruption) | **1.0%** | Baseline noise floor. Any sustained error rate above 1% over hours is a regression. |
| Operational events (scale-down, stream-soak) | **2.0%** | Single moderate disruption (one node drained, one stream reconfigured) — small percentage of in-flight requests lost during the cutover window. |
| Mid-flight ops with concurrent load (cert rotation, stream-under-load) | **5.0%** | Multi-step operational event happening while load is sustained — TLS handshake retries, partition reassignment. |
| Simultaneous chaos (kill-under-load) | **10.0%** | Multiple nodes killed during sustained load — in-flight requests on killed nodes are lost; replacement provisioning + failover takes seconds. |

### 4.1 Choosing a threshold for a new test

Ask:
1. **How many nodes does my test disrupt simultaneously?** (0 = soak baseline; 1 = operational; 1+concurrent load = mid-flight; N = chaos.)
2. **How long is the disruption window?** Longer windows tolerate more lost requests in absolute count, but error rate is per-request so the threshold scales with window length only indirectly.
3. **Does the cluster have a failover path that takes time?** (Cert rotation = no requests fail, just retry. Kill = requests on the killed node are lost outright until LB drops it.)

Pick the threshold tier whose disruption magnitude most closely matches your test. Document the choice inline at the top of the suite file with a comment referencing this contract.

### 4.2 What NOT to do

- ❌ Don't pick a threshold higher than your scenario justifies "to avoid flakes" — that masks real regressions. If a 2% test flakes, the right fix is to understand the source of the variance, not to bump to 5%.
- ❌ Don't pick lower than 1% even for "stable" suites — measurement noise alone can exceed 1% over millions of requests.
- ❌ Don't compare absolute counts; use rate.

---

## 5. Endpoint quick reference

For test authors. Lookup table.

| You want to know... | Use... | Notes |
|---|---|---|
| Is the cluster ready for my test? | `wait_for_cluster_ready` (§1) | Composite check |
| How many members does the cluster have right now? | `cluster_member_count` (§2.1) | Includes JOINING |
| How many cores are operational? | `cluster_active_core_count` (§2.2) | ON_DUTY + reachable |
| Is THIS node's JVM up? | `/health/live` (§3) | Cheap probe |
| Should THIS node receive traffic? | `/health/ready` (§3) | Returns 503 if not |
| Aggregated cluster health? | `/api/health` (§3) | One-shot composite |
| Who's the leader / what's the topology? | `/api/nodes/status` (§3) | Full picture |
| What error rate is acceptable for my test? | §4 tier table | Match to disruption magnitude |

---

## 6. Future work

- **Seed-node lifecycle write** — RESOLVED. `ClusterDeploymentState.handleNodeAdded` now plants a `JOINING` `NodeLifecycleKey` for seed nodes when no entry exists (idempotent — preserves existing state). The standard MembershipFsm machinery then drives `JOINING → ON_DUTY`. Effect: the seed node (initial leader at cluster bootstrap) appears in `/api/nodes/lifecycle`, `/api/cluster/generation` `core.members[]`, and KV-Store from cluster start. Property 1 of §1.1 returned to strict equality (`== NODE_COUNT`). The N-1 relaxation in `aether/tests/integration/lib/cluster.sh::_cluster_is_ready` and the three relaxed assertions in `aether/tests/integration/suites/00-smoke/test-cluster-formation.sh` (introduced in `ef5013881`) are reverted in the same commit. Code site: `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/fsm/ClusterDeploymentState.java::ensureSeedNodeLifecycleEntry`. Regression test: `ClusterDeploymentStateSeedNodeLifecycleTest` (3 cases — plant-when-absent, idempotent-when-present, non-seed-no-op).
- **`SelfDrainInitiated` event** (T3.1) — RESOLVED in this commit. `SELF_DRAIN_INITIATED` is now emitted by the draining node itself at the `ACTIVE → DRAINING` transition (intentionally NOT leader-gated — a partition victim is the only authoritative source). `test-self-drain-quorum-loss.sh` consumes the event from `/api/events` via the unioned-multi-node `topology_events_since` helper, replacing the `docker logs | grep 'Self-drain: DRAINING on'` workaround.
- **Node departure widening** (RC2 #224) — `NODE_LEFT` vs `NODE_FAILED` event split + `reason=transport-failure` vs `reason=swim-faulty` widening is bound to the two-path SwimFaulty vs TransportUnreachable architectural finding. Out of scope for RC1.
- **Per-suite threshold rationale comments** (T5.4) — each suite using a non-1% threshold should carry a one-line comment naming the tier from §4 it falls into. Mechanical follow-up.
