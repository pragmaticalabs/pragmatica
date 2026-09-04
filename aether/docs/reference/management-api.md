# Aether Management API Reference

This document describes the HTTP Management API for Aether cluster management.

**Base URL**: `http://<node-address>:<management-port>` (default port: 8080)

**Content-Type**: All requests and responses use `application/json` unless noted otherwise.

## Authentication

When API keys are configured, all management endpoints require authentication via the `X-API-Key` header:

```
X-API-Key: your-api-key
```

**Exceptions (always public, no auth required):**
- `GET /health/live` — Liveness probe
- `GET /health/ready` — Readiness probe

**Error responses:**
- `401 Unauthorized` — Missing `X-API-Key` header. Response includes `WWW-Authenticate: ApiKey realm="Aether"`.
- `403 Forbidden` — Invalid API key provided.

When no API keys are configured, all endpoints are accessible without authentication (backward compatible).

## Authorization (RBAC)

Aether supports role-based access control (RBAC) with three hierarchical authorization roles. Each API key can be assigned a role that determines which endpoints it can access.

### Roles

| Role | Level | Description |
|------|-------|-------------|
| **ADMIN** | Full access | Deploy blueprints, shutdown nodes, manage logging, configure observability, RBAC management |
| **OPERATOR** | Operational access | Drain/activate nodes, scaling, schema operations, deployments (canary/blue-green/rolling), backup, alerts, config overrides, scheduled tasks |
| **VIEWER** | Read-only access | Cluster status, metrics, logs, traces, events, health checks |

Roles are hierarchical: ADMIN has all OPERATOR permissions, and OPERATOR has all VIEWER permissions.

### Permission Mapping

**Read requests** (GET, HEAD, OPTIONS) are accessible to all authenticated roles (VIEWER and above).

**Mutation requests** (POST, PUT, DELETE) follow this mapping:

| Endpoint Category | Minimum Role | Examples |
|-------------------|-------------|----------|
| Blueprint management | ADMIN | `POST /api/v1/blueprints`, `DELETE /api/v1/blueprints/{id}` |
| Node shutdown | ADMIN | `POST /api/v1/nodes/shutdown/{id}` |
| Backup restore | ADMIN | `POST /api/v1/backups/restore/{id}` |
| Log level changes | ADMIN | `PUT /api/v1/logging/levels` |
| Observability depth | ADMIN | `PUT /api/v1/observability/depth` |
| Observability config (write) | ADMIN | `POST`/`DELETE /api/v1/observability/config` |
| Blueprint deploy (from artifact) | OPERATOR | `POST /api/v1/blueprints/deploy` |
| Blueprint validate | ADMIN | `POST /api/v1/blueprints/validate` |
| Node drain | OPERATOR | `POST /api/v1/nodes/drain/{id}` |
| Scaling | OPERATOR | `POST /api/v1/scale` |
| Schema operations | OPERATOR | `POST /api/v1/schema/*` |
| Deployment strategies | OPERATOR | `POST /api/v1/deploy`, `POST /api/v1/deploy/promote/*`, `POST /api/v1/deploy/rollback/*`, `POST /api/v1/deploy/complete/*`, `POST /api/v1/ab-tests/*` |
| Backup trigger | OPERATOR | `POST /api/v1/backups` |
| Config overrides | OPERATOR | `PUT /api/v1/config/*` |
| Alert management | OPERATOR | `POST /api/v1/alerts/clear` |
| Scheduled tasks | OPERATOR | `POST /api/v1/scheduled-tasks/*` |
| Controller config | OPERATOR | `PUT /api/v1/controller/*` |
| Threshold config | OPERATOR | `PUT /api/v1/thresholds/*` |
| Artifact repository | OPERATOR | `POST /repository/*` |
| All other mutations | ADMIN | Default for unlisted mutation endpoints |

### TOML Configuration

Assign authorization roles to API keys using the `authorization_role` field:

```toml
[app-http]
enabled = true

# Rich format with authorization roles
[app-http.api-keys.my-admin-key-value]
name = "cluster-admin"
roles = ["admin"]
authorization_role = "ADMIN"

[app-http.api-keys.my-operator-key-value]
name = "deploy-bot"
roles = ["service"]
authorization_role = "OPERATOR"

[app-http.api-keys.my-viewer-key-value]
name = "monitoring"
roles = ["service"]
authorization_role = "VIEWER"
```

**Default behavior:** When `authorization_role` is omitted, the key defaults to `ADMIN` for full backward compatibility. Existing configurations with no `authorization_role` field continue to work unchanged.

**Simple key format:** Keys defined using the simple string list (`api_keys = ["key1", "key2"]`) always receive ADMIN authorization.

### Authorization Failure Response

When an authenticated user lacks the required role for an endpoint, the server returns:

- **`403 Forbidden`** with body:
```json
{
  "error": "Forbidden: VIEWER role insufficient for this operation (requires OPERATOR)"
}
```

This is distinct from the authentication 403 (invalid API key). The authorization 403 indicates the key is valid but the assigned role lacks permission.

---

## Request Routing

A management request may land on any core node, but where it is ultimately served depends on the route:

- **Control-plane read/write routes forward to the leader** (the control plane is leader-only). This covers cluster/node/slice status, topology, lifecycle, scheduled-task control-plane reads, config, controller, thresholds, observability depth, routes, and workers. A request received by a follower is transparently forwarded to the current leader.
- **Per-node diagnostic routes are served node-locally** (metrics, traces, alerts, logs, storage, TTM, DHT replication map, certificates). These report the receiving node's own view and are not forwarded.
- **Per-node addressed routes** (those with an `{id}` node parameter) are forwarded to the named node.

---

## Cluster Status

### Schema stability contract

A small set of response fields are consumed by automated tooling (the integration-test harness
parses them with field-path extraction and grep). These fields are **frozen**: they MUST NOT be
renamed or have their JSON type changed without a major version bump and a corresponding update to
every consumer. Adding new fields alongside them is always allowed. The contract test
`aether/tests/integration/lib/contract-test.sh` (driven by `lib/schema-contract.toml`) enforces
this at push time: it fails if a harness lib references a status field outside the frozen list, or
if a frozen field disappears from this document.

| Endpoint | Frozen fields |
|----------|--------------|
| `GET /api/v1/cluster/status` | `leaderId`, `coreCount`, `clusterPhase` |
| `GET /api/v1/nodes/status` | `nodeId`, `lifecycleState`, `isLeader` |
| `GET /api/v1/nodes/lifecycle` | array element `nodeId`, `derivedStatus` |
| `GET /api/v1/nodes/live` | `nodeId`, `swimAlive`, `reportedState` |

The historical `id` vs `nodeId` rename — which silently broke harness field extraction until a
cloud run failed — is the regression this contract exists to prevent.

### GET /api/v1/nodes/status

Get overall cluster status including uptime, cluster info, slice count, and metrics summary.

**Response:**
```json
{
  "uptimeSeconds": 123456,
  "cluster": {
    "nodeCount": 3,
    "leaderId": "node-1",
    "nodes": [
      {"id": "node-1", "isLeader": true, "kvState": "READY", "derivedStatus": "READY"},
      {"id": "node-2", "isLeader": false, "kvState": "READY", "derivedStatus": "READY"}
    ]
  },
  "sliceCount": 5,
  "metrics": {
    "requestsPerSecond": 1500.0,
    "successRate": 99.5,
    "avgLatencyMs": 12.3
  },
  "nodeId": "node-1",
  "status": "running",
  "runtimeState": "ACTIVE",
  "lifecycleState": "READY",
  "clusterPhase": "NORMAL",
  "isLeader": true,
  "leader": "node-1"
}
```

`runtimeState` carries the JVM/process state machine (`NodeState`: `STARTING` / `JOINING` / `ACTIVE` / `DRAINING` / `STOPPED`) — "is the process up and serving".

`lifecycleState` carries the node's heartbeat-reported readiness (`SYNCING` / `READY` / `DRAINING`) as cached by the leader from the leader↔node heartbeat. This state is node-authoritative and is **never** stored in or committed to the KV-Store. Empty string when the leader has not yet received a heartbeat from the node (cold-start transient). See `aether/docs/specs/membership-architecture-v2-spec.md`.

### GET /api/v1/nodes/endpoint/{id}

Resolve a single node to its cluster-transport `host:port` and a best-effort reachability flag.
The request lands on any node and is forwarded to the node identified by `{id}` (via the standard
`nodeIdParam(0)` forwarding pattern shared with `/api/v1/nodes/status/{id}`, `/health/live/{id}`,
etc.), so the address returned is the target node's own view of its transport endpoint.

**Response:**
```json
{
  "nodeId": "node-1",
  "address": "10.0.0.7:7100",
  "reachable": true
}
```

- `address`: the node's cluster-transport `host:port` (no scheme). Falls back to the node's own
  advertised address during the cold-start window before it registers itself in its topology view.
- `reachable`: result of a best-effort TCP connect probe (2 s timeout) against `address`. A connect
  failure yields `false` without failing the request — the address is always reported so the caller
  learns where to dial. Equivalent to the harness `_resolve_live_endpoint` connect check.

### GET /api/v1/nodes/live

Unified live-node document served from **any** core node. Joins three sources into one view:
consensus topology (`address` + `role`), the SWIM-derived membership view (`swimAlive`), and the
node-reported work-state map (`reportedState`). The node universe is the union of topology and
reported-state keys. A **zombie** — a node still present in the reported-state map but absent from
both the topology and the SWIM membership view — surfaces with `swimAlive=false` and
`address=null`, and is counted in `zombieCount`.

**Response:**
```json
{
  "nodes": [
    {"nodeId": "node-1", "address": "10.0.0.7:7100", "role": "CORE", "swimAlive": true, "reportedState": "READY"},
    {"nodeId": "node-2", "address": "10.0.0.8:7100", "role": "CORE", "swimAlive": true, "reportedState": "READY"},
    {"nodeId": "node-3", "address": null, "role": "CORE", "swimAlive": false, "reportedState": "READY"}
  ],
  "liveCount": 2,
  "zombieCount": 1
}
```

- `swimAlive`: SWIM membership presence — the single source of truth for "is this node alive".
- `address`: `null` for a node not present in the consensus topology (e.g. a zombie known only via a
  stale reported-state entry).
- `role`: `"CORE"` / `"WORKER"` / `"SPOT"` from the node's `role` label, defaulting to `"CORE"`.
- `reportedState`: node-reported work-state (`SYNCING` / `READY` / `DRAINING`) from the metrics pong;
  empty string when no pong has been observed.
- `liveCount`: number of entries with `swimAlive=true`. `zombieCount`: the remainder.

### GET /api/v1/health

Get node health status including readiness and quorum.

**Response:**
```json
{
  "status": "healthy",
  "ready": true,
  "quorum": true,
  "nodeCount": 3,
  "connectedPeers": 2,
  "metricsNodeCount": 3,
  "sliceCount": 5
}
```

### GET /health/live

Liveness probe for container orchestrators. Always returns 200 if the process is running.
No authentication required.

**Response (200 OK):**
```json
{
  "status": "UP",
  "nodeId": "node-1"
}
```

### GET /health/live/{id}

Per-node variant of `/health/live`. The request lands on any node and is forwarded to the
node identified by `{id}` (via the standard `nodeIdParam(0)` forwarding pattern shared with
`/api/v1/nodes/status/{id}`, `/api/v1/nodes/inflight/{id}`, `/api/v1/nodes/slices/{id}`, etc.). Response
shape matches `GET /health/live`, but authentication does **not**: `ManagementServer.handleProbeRequest`
exact-matches only the bare `/health/live` path before the security gate runs, so the `{id}` form falls
through to `validateManagementSecurity` and requires authentication (VIEWER) like any other route when
`security_mode` is not `none`.

### GET /health/ready

Readiness probe for container orchestrators. Returns 200 when the node is ready to receive traffic, 503 when not ready.
No authentication required.

**Response (200 OK or 503 Service Unavailable):**
```json
{
  "status": "UP",
  "nodeId": "node-1",
  "components": [
    {"name": "consensus", "status": "UP", "detail": "Cluster active"},
    {"name": "routes", "status": "UP", "detail": "Route sync received"},
    {"name": "quorum", "status": "UP", "detail": "Reachable core members: 3 / required: 2"}
  ]
}
```

Components checked:
- **consensus** — Is the node participating in consensus? DOWN during initial cluster formation.
- **routes** — Has the node received its initial route synchronization from the KV-Store?
- **quorum** — Does the node hold quorum? True iff its counted strict core-member set meets the
  consensus simple-majority threshold (`coreCount / 2 + 1`), sourced from the same per-node
  quorum-loss signal the minority self-drain uses. A minority partition (e.g. 2 of 5) reports DOWN.

### GET /health/ready/{id}

Per-node variant of `/health/ready`. Forwarded to the node identified by `{id}` via the standard
`nodeIdParam(0)` forwarding pattern. Response shape matches `GET /health/ready`, but authentication
does **not**: `ManagementServer.handleProbeRequest` exact-matches only the bare `/health/ready` path
before the security gate runs, so the `{id}` form falls through to `validateManagementSecurity` and
requires authentication (VIEWER) like any other route when `security_mode` is not `none`.

### GET /api/v1/nodes

List all known cluster nodes with role and leader status.

**Response:**
```json
{
  "nodes": [
    {"nodeId": "node-1", "role": "CORE", "isLeader": true},
    {"nodeId": "node-2", "role": "CORE", "isLeader": false},
    {"nodeId": "node-3", "role": "WORKER", "isLeader": false}
  ]
}
```

- `role`: `"CORE"` (consensus participant) or `"WORKER"` (passive compute). Defaults to `"CORE"` if no `ActivationDirective` exists.

### GET /api/v1/whoami

Returns the principal, authorization role, and roles attached to the request's authentication context. Useful for integration-test identity assertions and operator triage — confirms which API key (or anonymous viewer fallback) the management plane resolved for the caller.

**Response (authenticated admin API key):**
```json
{
  "principal": "api-key:ops-admin",
  "authorizationRole": "ADMIN",
  "roles": ["admin", "service"],
  "authenticated": true
}
```

**Response (no API key supplied; anonymous viewer):**
```json
{
  "principal": "anonymous",
  "authorizationRole": "VIEWER",
  "roles": [],
  "authenticated": false
}
```

- `principal` — `api-key:<keyName>` / `user:<subject>` / `service:<name>` / `anonymous`. Identifies the entity the cluster authenticated.
- `authorizationRole` — coarse RBAC tier: `ADMIN`, `OPERATOR`, or `VIEWER`. See [Authorization (RBAC)](#authorization-rbac).
- `roles` — sorted, lower-case role values granted to the principal (e.g. `admin`, `service`, `user`).
- `authenticated` — `false` for the anonymous viewer fallback, `true` for any non-anonymous principal.

Route is `LOCAL` (no forwarding) — the answer is per-request, not per-cluster.

### GET /api/v1/events

Get cluster events from the event aggregator. Returns structured events including topology changes, leader elections, deployments, slice failures, and network events.

**Routing:** ANY — served from any core node, not leader-bound (#267). cluster-events is a replicated single-partition stream; a node that is not a cluster-events replica read-forwards to a CAUGHT_UP replica, so the endpoint stays available during leader churn/election (it previously returned 503 when no leader was present — exactly when operators most need events).

**Staleness:** a read served from a replica reflects that replica's CAUGHT_UP watermark, which may trail the owner by the in-flight replication window (typically sub-second under steady load). This is acceptable for observability and is preferable to a 503 during re-election. Reads are not linearizable across the partition.

**Query Parameters:**
- `sinceEpoch` (optional) -- Rabia term epoch for cursor-based pagination (default: 0).
- `sinceSeq` (optional) -- Sequence number for cursor-based pagination (default: -1, meaning from the beginning).

Both parameters must be used together to form a cursor. Clients should persist the `originEpoch` and `originSeq` fields from the last received event and pass them on the next request.

**Examples:**
```bash
# All events
curl http://localhost:8080/api/v1/events

# Events after a known cursor position
curl "http://localhost:8080/api/v1/events?sinceEpoch=3&sinceSeq=42"
```

**Response:**
```json
[
  {
    "timestamp": "2024-01-15T10:30:00Z",
    "type": "NODE_JOINED",
    "severity": "INFO",
    "summary": "Node node-2 joined cluster (now 3 nodes)",
    "details": {
      "nodeId": "node-2",
      "clusterSize": "3"
    }
  },
  {
    "timestamp": "2024-01-15T10:30:01Z",
    "type": "LEADER_ELECTED",
    "severity": "INFO",
    "summary": "Node node-1 elected as leader",
    "details": {
      "leaderId": "node-1"
    }
  }
]
```

**Event Types** (the 32 closed-set `ClusterEvent` variants; the `type` discriminator is the SCREAMING_SNAKE_CASE of the record name):

- `NODE_JOINED` -- a node joined the cluster (sourced from the transport `PeerJoined` handshake; leader-gated). Severity INFO.
- `NODE_LEFT` -- a node gracefully departed (consensus-committed decommission/drain decision; leader-gated). Severity WARNING.
- `NODE_FAILED` -- a node departure was confirmed via the FSM DEAD edge (the signal that also drives auto-heal; leader-gated). Severity CRITICAL.
- `LEADER_ELECTED` -- a node was elected cluster leader (leader-gated). Severity INFO.
- `LEADER_LOST` -- leadership was lost and an election is in progress (leader-gated). Severity WARNING.
- `QUORUM_ESTABLISHED` -- cluster quorum was (re-)established (leader-gated). Severity INFO.
- `QUORUM_LOST` -- cluster quorum was lost (leader-gated). Severity CRITICAL.
- `DEPLOYMENT_STARTED` -- an artifact began deploying to a node. Severity INFO.
- `DEPLOYMENT_COMPLETED` -- an artifact finished deploying on a node (`details` may carry `durationMs`). Severity INFO.
- `DEPLOYMENT_FAILED` -- an artifact deployment failed on a node (`details` carries `reason`). Severity WARNING.
- `SCALE_UP` -- an artifact was scaled up to more instances. Severity INFO.
- `SCALE_DOWN` -- an artifact was scaled down to fewer instances. Severity INFO.
- `SLICE_FAILURE` -- all instances of a slice method failed. Severity CRITICAL.
- `CONNECTION_ESTABLISHED` -- a transport connection to a peer was established. Severity INFO.
- `CONNECTION_FAILED` -- a transport connection to a peer failed. Severity WARNING.
- `COMMUNITY_SCALE_REQUEST` -- a community-tier scale request was recorded. Severity INFO.
- `COMMUNITY_METRICS_SNAPSHOT` -- a community-tier metrics snapshot was recorded. Severity INFO.
- `ACCESS_DENIED` -- an operation was denied by RBAC (`details` carries `principal`, `method`, `path`, `requiredRole`, `actualRole`). Severity WARNING.
- `NODE_LIFECYCLE_CHANGED` -- a node lifecycle transition was requested/applied (leader-gated). Severity INFO.
- `CONFIG_CHANGED` -- dynamic config was added, updated, or removed. Severity INFO.
- `BACKUP_CREATED` -- a KV backup/commit was created. Severity INFO.
- `BACKUP_RESTORED` -- a KV backup was restored. Severity WARNING.
- `BLUEPRINT_DEPLOYED` -- a blueprint was deployed. Severity INFO.
- `BLUEPRINT_DELETED` -- a blueprint was deleted. Severity INFO.
- `GENERATION_CHANGED` -- the cluster generation epoch advanced (leader-gated; see below). Severity INFO.
- `STREAM_REGISTERED` -- a stream was registered (carries the stream `ResourceAddress`). Severity INFO.
- `STREAM_DELETED` -- a stream was deleted (carries the stream `ResourceAddress`). Severity INFO.
- `ALERT_INJECTED` -- an operator-injected synthetic alert, replicated cluster-wide so every node serves it on `/api/v1/alerts`. Severity per inject.
- `TRACE_INJECTED` -- an operator-injected synthetic invocation trace, replicated cluster-wide so every node serves it on `/api/v1/traces`.
- `SELF_DRAIN_INITIATED` -- the draining node reports its own drain start (per-node fact, NOT leader-gated; see below). Severity WARNING.
- `STREAM_MEMORY_EXCEEDED` -- a node's off-heap stream budget was exhausted at stream create or growth (per-node fact, NOT leader-gated; throttled per `(stream, phase)`). Severity WARNING.
- `DEPARTURE_PUSH_INCOMPLETE` -- a gracefully-departing node could not confirm, within the drain grace window, that every locally-held DHT chunk reached a surviving replica (per-node fact, NOT leader-gated; see below). Severity WARNING.
- `SCALE_CAPPED` -- the leader autoscaler's requested instance count for an artifact was reduced by a cap before being applied (leader-side; emitted only on a real reduction). Severity WARNING.

`GENERATION_CHANGED` is a **documented-but-dormant** event type: nothing emits it on the current
codebase. The event record (`OperationalEvent.GenerationChanged`, with `oldEpoch`, `newEpoch`, and
`reason` — a `GenerationReason` enum name) and its aggregator route both exist, but every emission
path belonged to the v1 spec's leader-resident reconciler, which was never built (see
[`cluster-topology-overhaul-spec.md`](../specs/cluster-topology-overhaul-spec.md) W9); the
`GenerationChangedSink` seam has no live implementation, so generation-epoch advances (which DO
happen — the leader's per-tenure counter and term bumps) currently produce no operational event.
Tracked in #722. See [`cluster-generation-spec.md`](../specs/cluster-generation-spec.md) §14.4 for
the original design intent.

`SELF_DRAIN_INITIATED` (severity `WARNING`) is emitted by the draining node itself when its `SelfDrainCoordinator` flips from `ACTIVE` to `DRAINING` (see `aether/docs/specs/membership-architecture-v2-spec.md`). Unlike most other events, this one is NOT leader-gated — a partition victim is the only authoritative source for "I'm self-draining" and may not be able to reach the leader at all. `details` carries `nodeId` (the draining node), `reason` (one of `sustained-below-quorum`, `quorum-disappeared`, `rabia-paused`), and `graceMs` (the configured in-flight grace before forced halt). Best-effort: if the publish does not reach a quorum before `Runtime.halt(2)` lands, the event is lost.

`DEPARTURE_PUSH_INCOMPLETE` (severity `WARNING`, issue #427) is emitted by a gracefully-departing node when its bounded departure-push (which forwards every locally-held DHT chunk to its new replicas before the node halts) could not confirm all chunks reached a surviving replica within the drain grace window. Like `SELF_DRAIN_INITIATED` it is NOT leader-gated — the leaving node is the only source of truth for its own unpushed chunks. `details` carries `nodeId`, `keysAtRisk` (count of unconfirmed chunks), and `sampleKeys` (a bounded, comma-joined hex sample of the at-risk keys, for operator follow-up). Best-effort: the keys are named rather than silently lost, but if the publish does not land before `Runtime.halt(2)`, the event is lost.

`DEPLOYMENT_FAILED` is emitted **once per (artifact, node) pair** whose deployment attempt failed — `ClusterEventAggregator.handleDeploymentFailed` fires on each node-artifact KV transition to `FAILED`, so a blueprint spread across N nodes that fails deterministically on all of them produces N separate events, each with its own `nodeId` in `details.nodeId` and the failure text in `details.reason`. Because `cluster-events` is a single replicated stream, all N events are visible from `GET /api/v1/events` on **any** node, not only the one that failed.

This matters because most deploy-facing surfaces don't show it immediately: `POST /api/v1/blueprints` only reports `"status": "applied"` on **acceptance**, before deployment is attempted, and is never updated with the outcome. Under the default `ALL_OR_NOTHING` mode (see [02-deployment.md](../architecture/02-deployment.md#deployment-atomicity)), a deterministic slice-load failure rolls back the entire blueprint and removes the blueprint's key from the KV store outright (`ClusterDeploymentState.unloadBlueprintSlices`) — so `GET /api/v1/slices/status` shows nothing for the artifact, but the terminal `FAILED`/`ROLLED_BACK` outcome recorded at the same time survives that removal (#759 Phase 2 — see `SliceRoutes.handleGetBlueprintStatus`): `GET /api/v1/blueprints/status/{id}` answers `200` with `overallStatus` `FAILED` or `ROLLED_BACK`, `cause`, and `failingSlices`, not `404`. `404 BLUEPRINT_NOT_FOUND` now means only that neither a terminal outcome nor a live KV entry exists for that id. **`GET /api/v1/events` remains the per-node timeline of why a blueprint failed** — the query parameters (`sinceEpoch`/`sinceSeq`) are cursor-based, not artifact-based, so "filtering for `DEPLOYMENT_FAILED`" means fetching the feed and matching `details.artifact` yourself; the aggregator retains at most 10,000 events cluster-wide (`ClusterEventAggregator.MAX_RETAINED_EVENTS`), not per-artifact, so an individual event can roll off under high event volume before an operator looks — the durable outcome summary at `statusUrl` does not expire the same way. See the [Failure Almanac](failure-almanac.md#per-node-deployment-failure-under-all_or_nothing-rollback) for the worked example and operator playbook.

**Severity Levels:** `INFO`, `WARNING`, `CRITICAL`

---

### GET /api/v1/certificates

Return the node's certificate status and runtime TLS posture. Used by operators
and the CLI (`aether certs status`) to verify TLS is active and to inspect the
active certificate's expiry and most recent renewal.

**Response:**
```json
{
  "tlsEnabled": true,
  "expiresAt": "2026-06-20T12:00:00Z",
  "secondsUntilExpiry": 2591999,
  "lastRenewalAt": "2026-05-21T12:00:00Z",
  "renewalStatus": "HEALTHY"
}
```

**Fields:**
- `tlsEnabled` -- `true` when the app-HTTP server is bound with TLS at this node
  (i.e. `[cluster] tls = true` was honoured at startup and a `CertificateProvider`
  resolved). This is the authoritative active-TLS signal — integration tooling
  should assert on this field rather than inferring from `renewalStatus`.
- `expiresAt` -- ISO-8601 `notAfter` of the currently-served certificate, or
  `"N/A"` when no renewal scheduler is wired.
- `secondsUntilExpiry` -- Seconds remaining until `expiresAt`. `0` when not
  configured.
- `lastRenewalAt` -- ISO-8601 timestamp of the most recent successful renewal,
  or `"N/A"` when no renewal scheduler is wired.
- `renewalStatus` -- `RenewalStatus` enum from `CertificateRenewalScheduler`:
  `INITIALIZING`, `HEALTHY`, `RENEWING`, `FAILED`, `STOPPED`, or
  `NOT_CONFIGURED` (placeholder when no scheduler is wired).

When `tlsEnabled` is `false`, the remaining fields are set to placeholders
(`"N/A"` / `0` / `"NOT_CONFIGURED"`) — operators consuming this endpoint should
gate on `tlsEnabled` before reading the cert metadata.

### POST /api/v1/certificates/configure-short-validity

**Dev-mode only.** Reconfigures the `CertificateRenewalScheduler` so the active certificate appears to expire in `validitySeconds` from now, causing the renewal timer to reschedule at the recomputed 40%-of-remaining mark (24s for `validitySeconds=60`). Used by `Strengthen-cert-rotation-trigger` integration tests (see `aether/docs/.internal/production-readiness-followup-2026-05-21.md` P-NEW-I) to observe automatic cert rotation in seconds rather than waiting hours.

Gated by the `AETHER_INSECURE_DEV_MODE=true` environment variable on the node. When the gate is closed the endpoint returns a failure response and the scheduler is untouched. Precondition: a node with operator-provided TLS certificates refuses to start in dev-mode, so this route is never reachable on a node configured with real TLS.

**RBAC:** OPERATOR · **Routing:** LOCAL (operates on the node receiving the request)

**Request:**
```json
{
  "validitySeconds": 60
}
```

`validitySeconds` must be in the range `1..86400` (24h ceiling — defensive against absurd inputs). The endpoint fails with a validation error outside that range.

**Response:**
```json
{
  "status": "short_validity_configured",
  "validitySeconds": 60,
  "newExpiresAt": "2026-05-21T12:01:00Z",
  "secondsUntilExpiry": 60
}
```

The endpoint also fails when no `CertificateRenewalScheduler` is configured (TLS disabled at startup).

---

## Slice Management

> **Blueprint-only deployment model:** Slices are deployed and undeployed exclusively through blueprints.
> Individual deploy/undeploy endpoints have been removed to enforce dependency validation.
> Use `POST /api/v1/blueprints` to deploy slices and `DELETE /api/v1/blueprints/{id}` to undeploy them.

### GET /api/v1/slices

Returns cluster-wide slice data including per-node instances, target counts, and version information.

**Query parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `state` | string | no | Case-insensitive slice instance state (e.g. `ACTIVE`, `LOADED`), or a `+`-separated union of states (e.g. `LOADED+ACTIVE`). When present, the response filters `instances[]` per slice to only those whose `state` is a member of the set (uppercase normalisation + split-on-`+` server-side). Slices with no matching instances are dropped from the response. Omit for unfiltered output. An empty filter (`+` alone) matches no instance. |

**Examples:**
- `GET /api/v1/slices?state=ACTIVE` — only slices that have at least one `ACTIVE` instance; each slice's `instances[]` restricted to `ACTIVE` entries.
- `GET /api/v1/slices?state=LOADED+ACTIVE` — only slices that have at least one `LOADED` or `ACTIVE` instance; each slice's `instances[]` restricted to those two states.

**Response:**
```json
{
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "targetInstances": 3,
      "minInstances": 1,
      "version": "1.0.0",
      "instances": [
        {"nodeId": "node-1", "state": "ACTIVE", "failureReason": ""},
        {"nodeId": "node-2", "state": "ACTIVE", "failureReason": ""},
        {"nodeId": "node-3", "state": "ACTIVE", "failureReason": ""}
      ]
    }
  ]
}
```

### GET /api/v1/nodes/slices

Returns a flat list of slice artifact identifiers loaded on the connected node (the previous behavior of `GET /api/v1/slices`).

**Response:**
```json
{
  "slices": [
    "org.example:my-slice:1.0.0",
    "org.example:other-slice:2.0.0"
  ]
}
```

### GET /api/v1/slices/status

Get detailed slice status including per-node state and health.

**Response:**
```json
{
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "state": "ACTIVE",
      "instances": [
        {"nodeId": "node-1", "state": "ACTIVE", "health": "HEALTHY"},
        {"nodeId": "node-2", "state": "ACTIVE", "health": "HEALTHY"}
      ]
    }
  ]
}
```

### GET /api/v1/slices/config/{id}

Return the effective configuration view for a loaded slice with per-key attribution of which layer of the slice-composite (`slice.toml ⊕ KV-overlay ⊕ node.toml`) produced the resolved value.

`id` is the slice's full artifact coordinates (`group:artifact:version`).

Each entry's `source` is one of:
- `"slice.toml"` — value comes from the slice's intrinsic `META-INF/resources.toml`
- `"KV"` — value comes from the operator-supplied KV overlay (via `POST /api/v1/config`)
- `"node.toml"` — value comes from the node's static `node.toml` file

Entries are sorted alphabetically by `key`. Returns a failure when the slice is not loaded or the node has per-slice config disabled.

**Response:**
```json
{
  "sliceId": "org.example:my-slice:1.0.0",
  "entries": [
    {"key": "topic.orders", "value": "orders.v1", "source": "slice.toml"},
    {"key": "schedule.interval", "value": "30s", "source": "KV"},
    {"key": "datasource.url", "value": "jdbc:postgresql://...", "source": "node.toml"}
  ]
}
```

### GET /api/v1/nodes/routes

List HTTP routes registered on the connected node.

**Response:**
```json
{
  "routes": [
    {
      "method": "GET",
      "path": "/orders",
      "nodes": ["node-1", "node-2"],
      "security": "none"
    }
  ]
}
```

### GET /api/v1/routes

List HTTP routes across the cluster.

**Response:**
```json
{
  "routes": [
    {
      "method": "GET",
      "path": "/orders",
      "nodes": ["node-1", "node-2"],
      "security": "none"
    }
  ]
}
```

### GET /api/v1/versions

List the versioned slices deployed on this node and their API version registries (#198 §11.3).
Route target is `LOCAL` — the response reflects the versioned slices the queried node hosts, read
from its in-memory route publisher. A node hosting no versioned slice returns `{"slices":[]}`.

Per slice the response carries the version-agnostic `apiPrefix`, the header-mode detection knobs
(`requireVersionHeader`, and `defaultVersion` — the version served when the version header is
absent; omitted when no version declares `defaultIfMissing`), and per-version lifecycle metadata:
`version`, `deprecated`, `sunset` (RFC 3339 date; omitted when none), and `defaultIfMissing` (`true`
for the version equal to `defaultVersion`).

These are the same lifecycle facts surfaced as response headers on a served request — see the
`Deprecation`, `Sunset`, and `Link: …; rel="successor-version"` headers (#198 §8.2).

**Response:**
```json
{
  "slices": [
    {
      "slice": "org.example:orders:1.0.0",
      "apiPrefix": "/api/orders",
      "requireVersionHeader": false,
      "defaultVersion": 2,
      "versions": [
        { "version": 1, "deprecated": true, "sunset": "2026-12-31", "defaultIfMissing": false },
        { "version": 2, "deprecated": false, "defaultIfMissing": true }
      ]
    }
  ]
}
```

### POST /api/v1/scale

Scale a blueprint-deployed slice to a new instance count. The slice must be part of an active blueprint.

**Request:**
```json
{
  "artifact": "org.example:my-slice:1.0.0",
  "instances": 5,
  "placement": "WORKER_PREFERRED"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `artifact` | string | Yes | Full artifact coordinates (group:artifact:version) |
| `instances` | integer | Yes | Target number of instances |
| `placement` | string | No | Placement strategy: `CORE_ONLY` (default), `WORKER_PREFERRED`, `WORKER_ONLY`. When omitted, preserves existing placement or defaults to `CORE_ONLY` for new targets. |

**Response:**
```json
{
  "status": "scaled",
  "artifact": "org.example:my-slice:1.0.0",
  "instances": 5
}
```

**Error (slice not in any blueprint):**
```json
{
  "error": "Slice is not part of any active blueprint. Deploy via blueprint."
}
```

---

## Blueprint Management

### POST /api/v1/blueprints

Publish (apply) a blueprint definition. The request body is the raw blueprint YAML/JSON string.

**Request:** Raw blueprint content as request body (string).

**Response:**
```json
{
  "status": "applied",
  "blueprint": "my-blueprint",
  "targetInstances": 3,
  "activeInstances": 0,
  "failedInstances": 0,
  "statusUrl": "/api/v1/blueprints/status/my-blueprint"
}
```

> **`"applied"` means accepted, not deployed.** This response is written before allocation runs, and it is never updated with the outcome. `targetInstances`/`activeInstances`/`failedInstances` are a live snapshot of the deployment map taken at response time — typically all-zero for a fresh publish, since nothing has had time to activate yet. `statusUrl` points directly at [`GET /api/v1/blueprints/status/{id}`](#get-apiv1blueprintsstatusid); poll it for progress. If a blueprint stays `PENDING` past its expected time, fetch [`GET /api/v1/events`](#get-apiv1events) and match `details.artifact` yourself for `DEPLOYMENT_FAILED` to see the per-node failure reason (`details.reason`) and when it happened — under the default `ALL_OR_NOTHING` mode a failure rolls back the whole blueprint and removes it from the KV store entirely, but `statusUrl` now answers with the durable terminal outcome (`FAILED`/`ROLLED_BACK`, `cause`, `failingSlices`) rather than `404` (#759 Phase 2); the event feed is still the timeline of what happened on which node, not a replacement for that summary.

### GET /api/v1/blueprints

List all published blueprints.

**Response:**
```json
{
  "blueprints": [
    {"id": "my-blueprint", "sliceCount": 3},
    {"id": "other-blueprint", "sliceCount": 2}
  ]
}
```

### GET /api/v1/blueprints/{id}

Get blueprint details including slices and dependencies.

**Response:**
```json
{
  "id": "my-blueprint",
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "instances": 3,
      "isDependency": false,
      "dependencies": ["org.example:shared-lib:1.0.0"]
    }
  ],
  "dependencies": ["org.example:shared-lib:1.0.0"]
}
```

### GET /api/v1/blueprints/status/{id}

Get deployment status of a blueprint and each of its slices. `{id}` is the
blueprint id, which is artifact-shaped (`group:artifact:version`), so its
colons are percent-encoded in the path segment.

This is the surface `aether blueprints deploy --wait` polls. `activeInstances`
is counted from the same replicated deployment map that backs
`GET /api/v1/slices/status`, so the two endpoints cannot disagree about whether a
deployment finished.

**Response — deployed (happy path):**
```json
{
  "id": "my-blueprint",
  "overallStatus": "DEPLOYED",
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "targetInstances": 3,
      "activeInstances": 3,
      "failedInstances": 0,
      "status": "DEPLOYED"
    }
  ],
  "cause": "",
  "failingSlices": [],
  "timestampMs": 0
}
```

`cause`, `failingSlices`, and `timestampMs` are present on every response, not only a failing one
`[mechanism: BlueprintStatusResponse is a record — Java records serialize every component, so
these three fields can never be omitted; the happy-path construction site supplies the degenerate
defaults shown above rather than leaving them out]`. Treat a present-but-empty `cause`/
`failingSlices` and a `timestampMs` of `0` as "no terminal failure recorded," not as missing data.

**Response — terminal failure (blueprint removed from the KV store):**
```json
{
  "id": "org.example:my-app:1.0.0",
  "overallStatus": "FAILED",
  "slices": [],
  "cause": "instance allocation failed: insufficient capacity for org.example:my-slice:1.0.0",
  "failingSlices": ["org.example:my-slice:1.0.0"],
  "timestampMs": 1735689600000
}
```

Once a blueprint's key leaves the KV store entirely — `ALL_OR_NOTHING` rollback only — the deployment
map has nothing left to report per-slice, so `slices` is empty. This is the durable terminal outcome
instead: `overallStatus` is `FAILED` or `ROLLED_BACK`, `cause` is the human-readable failure reason,
`failingSlices` lists the full artifact coordinates (`group:artifact:version`, never a bare slice id)
of every slice that failed, and `timestampMs` is the epoch-millis time the outcome was recorded. Before
this outcome key existed, the same request answered `404` with no way to learn what happened
`[mechanism: exercised by `BlueprintDeployStatusTest` and `BlueprintStatusAggregationTest`
against the real route handler; no live multi-node failure-injection run — #759]`.

**Response — terminal failure, blueprint still live (`BEST_EFFORT`, or a restored blueprint with a
lingering outcome):**
```json
{
  "id": "org.example:my-app:1.0.0",
  "overallStatus": "PARTIAL",
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "targetInstances": 2,
      "activeInstances": 1,
      "failedInstances": 1,
      "status": "FAILED"
    },
    {
      "artifact": "org.example:other-slice:1.0.0",
      "targetInstances": 3,
      "activeInstances": 3,
      "failedInstances": 0,
      "status": "DEPLOYED"
    }
  ],
  "cause": "instance allocation failed: insufficient capacity for org.example:my-slice:1.0.0",
  "failingSlices": ["org.example:my-slice:1.0.0"],
  "timestampMs": 1735689600000
}
```
`BEST_EFFORT` records a terminal outcome for the failed slice without removing the blueprint's KV
entry, so siblings keep serving; a restored blueprint can likewise stay fully healthy while an outcome
from the original failed deploy lingers, because nothing later clears a terminal record for a
blueprint id that stays live. Either way `slices` is NOT empty here — it carries the same live
per-slice detail as the happy path, alongside the outcome's `cause`/`failingSlices`/`timestampMs`.
`overallStatus` is always `PARTIAL` in this shape, even on the rare case where every instance is
currently `ACTIVE`, so a caller always knows to check `cause` rather than assume a bare `DEPLOYED`
`[mechanism: `SliceRoutes.resolveTerminalOutcomeStatus` consults the live blueprint before choosing
between this shape and the empty-`slices` shape above; pinned by `BlueprintStatusAggregationTest`
(`statusRoute_blueprintLiveAndHealthyWithLingeringRolledBackOutcome_reportsPartialWithLiveSliceCounts`,
`statusRoute_blueprintLiveWithTerminalFailure_bestEffort_reportsPartialWithSliceCounts`) — unit-level,
no live multi-node failure-injection run — #759]`. Recovery: a `BEST_EFFORT` `PARTIAL` clears by
redeploying the failed slice; a lingering restore-time outcome clears the next time that blueprint id
is redeployed or deleted.

Per-slice status values: `PENDING`, `DEPLOYING`, `DEPLOYED`, `SCALING_DOWN`, `FAILED`. Overall: `DEPLOYED`, `PENDING`, `IN_PROGRESS`, `PARTIAL`, `FAILED`, `ROLLED_BACK`.

`failedInstances` counts `SliceState.FAILED` entries still present in the deployment map for that
slice's artifact (e.g. a `BEST_EFFORT` deploy, or a query landing before `ALL_OR_NOTHING` rollback
cleanup removes the entry). Whenever it is non-zero the slice's `status` is `FAILED` regardless of
`activeInstances`. `overallStatus` is `FAILED` only when **every** slice is `FAILED` (or the
blueprint itself is gone entirely with a terminal `FAILED` outcome — see above); a mix of `FAILED`
and non-`FAILED` slices reports `PARTIAL` instead — under `ALL_OR_NOTHING` that mix is transient
(rollback moves it on to `ROLLED_BACK`), under `BEST_EFFORT` it can be the durable state siblings
keep serving through. Recovery is the same as the `PARTIAL` shape above: redeploy the failed
slice(s). `[mechanism: `SliceRoutes.computeOverallStatus`; pinned through the real route by
`BlueprintStatusAggregationTest#statusRoute_reportsPartial_whenOneSliceFailedAndSiblingFullyDeployed`
(mix → `PARTIAL`) and `#statusRoute_reportsFailed_whenEverySliceHasFailed` (every slice failed →
`FAILED`) — #759 review round 4]`.

### DELETE /api/v1/blueprints/{id}

Delete a published blueprint.

**Response:**
```json
{
  "status": "deleted",
  "id": "my-blueprint"
}
```

### POST /api/v1/blueprints/deploy

Deploy a blueprint from an artifact in the cluster's artifact repository.

**Request Body:**
```json
{
  "artifact": "org.example:my-app:1.0.0"
}
```

**Response:**
```json
{
  "status": "pending",
  "blueprint": "org.example:my-app:1.0.0",
  "targetInstances": 5,
  "activeInstances": 0,
  "failedInstances": 0,
  "statusUrl": "/api/v1/blueprints/status/org.example%3Amy-app%3A1.0.0"
}
```

#759 — `status` is earned off the deployment map at response time, not assumed from a successful
publish; deployment is asynchronous, so **`pending` is the normal, expected response for a
first-time deploy** — no node has had a chance to activate a slice yet. Three honest outcomes,
checked in this priority order:

- **`degraded`** — at least one target instance is already `FAILED` in the deployment map.
  Reachable on `BEST_EFFORT` deploys, or a redeploy onto an artifact with a lingering failure that
  raced `ClusterDeploymentState`'s `ALL_OR_NOTHING` rollback cleanup.
- **`deployed`** — every declared target instance is already observed `ACTIVE`. Only realistic for
  an idempotent redeploy of an already fully-healthy artifact set.
- **`pending`** — the default: at least one target instance has not yet activated, which is every
  first-time deploy of a fresh artifact.

`targetInstances`/`activeInstances`/`failedInstances` are the same live snapshot used to derive
`status`. `statusUrl` (`{id}` percent-encoded, since blueprint ids are artifact-shaped) always
points at [`GET /api/v1/blueprints/status/{id}`](#get-apiv1blueprintsstatusid) — this is the endpoint
the CLI's `aether blueprints deploy --wait` polls (`DeploymentWait`) until it reports `DEPLOYED`.
Under the default `ALL_OR_NOTHING` mode a deterministic failure rolls back the whole blueprint and
removes its KV entry outright, so the `deployed`/`degraded`/`pending` status this call derived from
the deployment map disappears along with it — but the terminal `FAILED`/`ROLLED_BACK` outcome
recorded at the same time survives that removal, and `statusUrl` now answers `200` with that
outcome (`overallStatus`, `cause`, `failingSlices`) instead of `404` (#759 Phase 2). `--wait` itself
still only treats `DEPLOYED` as complete — a rolled-back deployment exits with `TIMEOUT` by design
(see `DeploymentWait`'s own header), not by reading the failure — so a caller that needs the reason
either polls `statusUrl` directly after `--wait` gives up, or watches
[`GET /api/events`](#get-apievents) for `DEPLOYMENT_FAILED`, matched against `details.artifact`
yourself (see the `DEPLOYMENT_FAILED` discussion above), which gives the per-node timeline that
`statusUrl`'s summary does not.

#### Single-migrator gate (409 Conflict)

Both artifact-based entry points (`/deploy` and `/publish`) refuse a request with **409 Conflict**
when the artifact declares migrations for a datasource that a **different** blueprint already
migrates. The refusal happens before any KV command is applied, so a refused publish writes
nothing.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Blueprint 'org.example:other-app:1.0.0' rejected — datasource 'database' is already migrated by blueprint 'org.example:my-app:1.0.0'. Declare the migrations in one blueprint only, or give this blueprint its own datasource section."
}
```

Rules:

- Ownership is compared on the artifact base (`group:artifact`, version stripped). The **same**
  blueprint republishing at a newer version is an owner advancing its own schema, not a conflict.
- A blueprint that declares **no** migrations passes the gate trivially. Sharing a datasource for
  reads and writes stays legal — only duplicate *migration ownership* is refused.
- The gate runs for `registerOnly` publishes too, so `POST /api/v1/blueprints/publish` is refused on
  the same terms as `/deploy`.
- `POST /api/v1/blueprints` (raw blueprint content) is **not** subject to the gate: migrations are
  read from the artifact jar's `schema/` directory, so a raw-DSL blueprint carries none.

**What this check does and does not promise.** Both routes are task-group targeted
(`DEPLOYMENT`), so a request arriving at any node is forwarded to the node that owns the deployment
task group; the ownership lookup therefore reads that owner's state rather than a possibly-stale
follower's. The check itself is a read of the existing schema record followed by a write of the
publish commands — not a compare-and-swap. Two publishes issued **concurrently** for the same
unclaimed datasource can therefore both observe it unclaimed and both proceed. Sequential
publishes — the realistic operator case — are reliably refused. This is the same deploy-time
read-then-write window every other validation on this path uses.

Why refuse rather than namespace: datasource names are cluster-global (the default
`schema/V001__*.sql` layout yields the name `database` for every blueprint, resolved against the
same node-global config section), so two blueprints migrating one physical database would
interleave unrelated version sequences.

### POST /api/v1/blueprints/publish

Publish a blueprint from an artifact already present in the cluster's artifact
repository. Orthogonal to `POST /api/v1/blueprints` (which takes raw blueprint
content in the body). Same body shape as `POST /api/v1/blueprints/deploy`, and subject to the same
[single-migrator gate](#single-migrator-gate-409-conflict).

**Request Body:**
```json
{
  "artifact": "org.example:my-app:1.0.0"
}
```

CLI: `aether blueprints publish <group:artifact:version>` appends the
`:blueprint` qualifier automatically when constructing the body.

**Response:**
```json
{
  "status": "published",
  "blueprint": "org.example:my-app:1.0.0",
  "targetInstances": 5,
  "activeInstances": 0,
  "failedInstances": 0,
  "statusUrl": "/api/v1/blueprints/status/org.example%3Amy-app%3A1.0.0"
}
```

`status` is always the fixed literal `published` — a register-only publish never activates the
blueprint, so `activeInstances`/`failedInstances` are typically all-zero until a later
`POST /api/v1/blueprints/deploy` actually targets this artifact.

### POST /api/v1/blueprints/validate

Validate a blueprint without applying it.

**Request:** Raw blueprint content as request body (string).

**Response (valid):**
```json
{
  "valid": true,
  "id": "my-blueprint",
  "sliceCount": 3,
  "errors": []
}
```

**Response (invalid):**
```json
{
  "valid": false,
  "id": "",
  "sliceCount": 0,
  "errors": ["Unknown artifact: org.example:missing:1.0.0"]
}
```

### Security Overrides

Operators can override per-route security policies at deploy time via the `[security]` section in blueprint.toml.

#### TOML Syntax

```toml
[security]
override_policy = "strengthen_only"    # strengthen_only | full | none

[security.overrides]
"GET /api/v1/urls/*" = "authenticated"     # Wildcard suffix match
"POST /api/v1/admin/reset" = "role:admin"  # Exact match
"* /api/v1/health" = "public"              # Any HTTP method
```

#### Override Policy

| Policy | Behavior |
|--------|----------|
| `strengthen_only` (default) | Operator can only make routes MORE restrictive (public->authenticated, authenticated->public is rejected) |
| `full` | Operator can change security in any direction |
| `none` | No overrides allowed -- slice developer's security is final |

#### Security Values

| Value | Meaning |
|-------|---------|
| `public` | No authentication required |
| `authenticated` | Any valid credential (API key or JWT based on server security mode) |
| `role:<name>` | Requires specific role in SecurityContext |

#### Pattern Matching

| Pattern | Matches |
|---------|---------|
| `"GET /api/v1/urls/"` | Exact method + path |
| `"GET /api/v1/urls/*"` | GET requests to any path starting with `/api/v1/urls/` |
| `"* /api/v1/urls/*"` | Any HTTP method to paths starting with `/api/v1/urls/` |

#### Strength Ordering

For `strengthen_only` policy, security levels are ordered: `public (0) < authenticated (1) < role:* (2)`. Overrides that weaken security are silently ignored.

---

## Metrics

### GET /api/v1/metrics

Get cluster-wide metrics including per-node load and deployment metrics.

**Scope: cluster-wide, despite the route's `LOCAL` routing declaration** — `LOCAL` governs
routing (no forwarding), not response scope. Any node answers with a `load` entry for **every**
node it knows, so fetch this ONCE and select nodes by id; polling it per node returns the same
cluster-wide map N times (the #591 instrument mis-read exactly this and had to hard-fail on it).

**Response:**
```json
{
  "load": {
    "node-1": {"cpu.usage": 0.45, "heap.used": 268435456},
    "node-2": {"cpu.usage": 0.52, "heap.used": 234881024}
  },
  "deployments": {
    "org.example:my-slice:1.0.0": [
      {
        "nodeId": "node-1",
        "status": "ACTIVE",
        "fullDeploymentMs": 1234,
        "netDeploymentMs": 800,
        "transitions": {"DOWNLOADING": 200, "LOADING": 400, "STARTING": 200},
        "startTime": 1704067200000,
        "activeTime": 1704067201234
      }
    ]
  }
}
```

### GET /api/v1/metrics/comprehensive

Get comprehensive metrics. **Scope: node-local** — the answering node reports itself.

The response has two halves with different time semantics, deliberately:

- The top-level fields are **minute-aggregated** (the most recent completed minute bucket); they
  are zeros until the first bucket exists.
- The `consensus` block (#674) is **live** — cumulative monotonic totals read from the consensus
  collector at request time, present from the node's first request. Consumers measuring load
  difference the totals over their own window (the same contract as `/api/v1/metrics/transport`).
  `pendingBatches` is a level, not a total; `avgDecisionLatencyMs` is derived over the cumulative
  counts; `leaderId` is absent until a leader is known.

**Response:**
```json
{
  "minuteTimestamp": 1704067200000,
  "avgCpuUsage": 0.45,
  "avgHeapUsage": 0.60,
  "avgEventLoopLagMs": 1.2,
  "avgLatencyMs": 12.3,
  "totalInvocations": 15000,
  "totalGcPauseMs": 50,
  "latencyP50": 8.0,
  "latencyP95": 25.0,
  "latencyP99": 80.0,
  "errorRate": 0.005,
  "eventCount": 120,
  "sampleCount": 60,
  "consensus": {
    "role": "LEADER",
    "leaderId": "node-1",
    "pendingBatches": 0,
    "decisionsCount": 1042,
    "proposalsCount": 998,
    "voteRound1Count": 2084,
    "voteRound2Count": 2011,
    "fastPathCount": 812,
    "syncSuccessCount": 3,
    "syncFailureCount": 0,
    "avgDecisionLatencyMs": 4.7
  }
}
```

### GET /api/v1/metrics/derived

Get derived (computed) metrics including trends, saturation, and health score.

**Response:**
```json
{
  "requestRate": 250.0,
  "errorRate": 0.005,
  "gcRate": 0.8,
  "latencyP50": 8.0,
  "latencyP95": 25.0,
  "latencyP99": 80.0,
  "eventLoopSaturation": 0.1,
  "heapSaturation": 0.6,
  "cpuTrend": 0.02,
  "latencyTrend": -0.01,
  "errorTrend": 0.0,
  "healthScore": 0.95,
  "stressed": false,
  "hasCapacity": true
}
```

### GET /api/v1/metrics/prometheus

Get Prometheus-format metrics for scraping.

**Content-Type**: `text/plain; version=0.0.4; charset=utf-8`

Includes the consensus-load gauges (#674): `consensus_decisions_total`, `consensus_proposals_total`,
`consensus_vote_round1_total`, `consensus_vote_round2_total`, `consensus_fast_path_total`,
`consensus_sync_success_total`, `consensus_sync_failure_total` (monotonic totals) and
`consensus_pending_batches` (a level) — the same names and values as the comprehensive response's
`consensus` block.

### GET /api/v1/metrics/transport

Get transport-layer metrics. **Scope: node-local** — a flat map of **node-level** QUIC counters
(the answering node's own totals; there is no per-peer attribution): `quic_messages_sent_total` /
`quic_messages_received_total` (protocol-message counts), `quic_bytes_sent_total` /
`quic_bytes_received_total` (#726), `quic_active_connections`, handshake totals/failures,
backpressure and write-failure indicators, stream-zombie heal counters. All counters are monotonic;
consumers difference them over their own window.

**`quic_bytes_sent_total` / `quic_bytes_received_total` (#726)** count PAYLOAD bytes at the lane
boundary — the serialized frame handed to the channel on send, and the frame decoded from the
buffer on receive — after the pipeline has already stripped QUIC framing, TLS encryption overhead,
and retransmits. This is **not a wire-byte or bandwidth figure**; do not treat it as one.

### GET /api/v1/metrics/history

Get historical metrics for nodes over a time range.

**Query Parameters:**
- `range` (optional) -- Time range. Values: `5m`, `15m`, `1h` (default), `2h`.

**Example:**
```bash
curl "http://localhost:8080/api/v1/metrics/history?range=15m"
```

**Response:**
```json
{
  "timeRange": "15m",
  "nodes": {
    "node-1": [
      {
        "timestamp": 1704067200000,
        "metrics": {"cpu.usage": 0.45, "heap.used": 268435456}
      }
    ]
  }
}
```

### GET /api/v1/metrics/timeouts

Get per-subsystem cumulative timeout-fired counters. One entry per
`TimeoutsConfig` subsystem group (14 subsystems mirroring the
`[timeouts.*]` TOML sections in `aether.toml`). Counters are
`LongAdder`-backed, monotonically increasing for the lifetime of the node
process; subsystems with zero fires are still present in the response.

Used by TC-07-G3 (integration test) to verify that operator-configured
timeout settings actually fire when exercised — currently the only
observable signal for `[timeouts.*]` taking effect.

**Example:**
```bash
curl http://localhost:8080/api/v1/metrics/timeouts
```

**Response:**
```json
{
  "subsystems": {
    "invocation":    {"firedCount": 0},
    "forwarding":    {"firedCount": 0},
    "deployment":    {"firedCount": 0},
    "rollingUpdate": {"firedCount": 0},
    "cluster":       {"firedCount": 0},
    "consensus":     {"firedCount": 12},
    "election":      {"firedCount": 0},
    "swim":          {"firedCount": 0},
    "observability": {"firedCount": 0},
    "dht":           {"firedCount": 3},
    "worker":        {"firedCount": 0},
    "security":      {"firedCount": 0},
    "repository":    {"firedCount": 0},
    "scaling":       {"firedCount": 0}
  }
}
```

### POST /api/v1/metrics/backfill

**Dev-mode-only.** Seeds synthetic historical-metric samples into the local
node's `ClusterSyncCollector` ring buffer so historical-range queries
(`/api/v1/metrics/history?range=...`) can be exercised deterministically
without waiting hours for the sliding window to populate organically.
Gated by `AETHER_INSECURE_DEV_MODE=true` — same gate pattern as
`/api/v1/scheduled-tasks/inject` and `/api/v1/alerts/inject`. Precondition: a node with
operator-provided TLS certificates refuses to start in dev-mode, so this route is never
reachable on a node configured with real TLS.

Used by TC-11-H1 (historical-metrics range queries) to make the 5m, 15m,
1h, 2h range assertions deterministic.

**Request body:**
```json
{
  "metric": "cpu.usage",
  "startTimeMs": 1704067200000,
  "endTimeMs":   1704074400000,
  "intervalMs":  60000,
  "valueFn":     "linear"
}
```

Fields:
- `metric` — metric key written into each synthetic snapshot's map; non-blank.
- `startTimeMs` / `endTimeMs` — inclusive epoch-millis window; `startTimeMs < endTimeMs`.
- `intervalMs` — spacing between successive samples; `> 0`.
- `valueFn` — synthetic-value generator. One of:
  - `"constant:<double>"` (e.g. `constant:42.5`) — emits the same value at every sample.
  - `"linear"` — 0 at `startTimeMs`, 1 at `endTimeMs`, linear in between.
  - `"sine"` — `0.5 + 0.5·sin(2π·progress)`.
  - Any unknown value falls back to `constant:0.0`.

**Response:**
```json
{
  "nodeId":         "node-1",
  "metric":         "cpu.usage",
  "samplesWritten": 121,
  "startTimeMs":    1704067200000,
  "endTimeMs":      1704074400000
}
```

**Error responses (all surface a structured cause message):**
- Missing/blank `metric` field → `metric field is required`.
- `startTimeMs >= endTimeMs` → `startTimeMs must be strictly less than endTimeMs`.
- `intervalMs <= 0` → `intervalMs must be greater than 0`.
- `AETHER_INSECURE_DEV_MODE` unset / `false` → `metrics backfill requires AETHER_INSECURE_DEV_MODE=true`.

### GET /api/v1/nodes/metrics

Get per-node CPU and heap metrics.

**Response:**
```json
[
  {
    "nodeId": "node-1",
    "cpuUsage": 0.45,
    "heapUsedMb": 256,
    "heapMaxMb": 512
  }
]
```

### GET /api/v1/artifacts/metrics

Get artifact storage and deployment metrics.

**Response:**
```json
{
  "artifactCount": 5,
  "chunkCount": 120,
  "memoryBytes": 52428800,
  "memoryMB": "50.00",
  "deployedCount": 3,
  "deployedArtifacts": [
    "org.example:my-slice:1.0.0",
    "org.example:other-slice:2.0.0"
  ]
}
```

### GET /api/v1/invocations/metrics

Get per-method invocation metrics.

**Query Parameters:**
- `artifact` (optional) -- Filter by artifact (partial match)
- `method` (optional) -- Filter by method name (exact match)

**Examples:**
```bash
# All metrics
curl http://localhost:8080/api/v1/invocations/metrics

# Filter by artifact
curl "http://localhost:8080/api/v1/invocations/metrics?artifact=order-service"

# Filter by method
curl "http://localhost:8080/api/v1/invocations/metrics?method=processOrder"
```

**Response:**
```json
{
  "snapshots": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "method": "processOrder",
      "count": 1000,
      "successCount": 990,
      "failureCount": 10,
      "totalDurationNs": 50000000000,
      "p50DurationNs": 10000000,
      "p95DurationNs": 100000000,
      "avgDurationMs": 50.0,
      "slowInvocations": 5
    }
  ]
}
```

### GET /api/v1/invocations/metrics/slow

Get slow invocation details.

**Response:**
```json
{
  "slowInvocations": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "method": "processOrder",
      "durationNs": 500000000,
      "durationMs": 500.0,
      "timestampNs": 1704067200000000000,
      "success": false,
      "error": "TimeoutException"
    }
  ]
}
```

### GET /api/v1/invocations/metrics/strategy

Get current slow invocation threshold strategy.

**Response (Fixed):**
```json
{"type": "fixed", "thresholdMs": 100}
```

**Response (Adaptive):**
```json
{"type": "adaptive", "minMs": 10, "maxMs": 1000, "multiplier": 3.0}
```

**Response (PerMethod):**
```json
{"type": "perMethod", "defaultMs": 100}
```

**Response (Composite):**
```json
{"type": "composite"}
```

### POST /api/v1/invocations/metrics/strategy

Strategy changes are not currently supported. This endpoint always returns an error.

**Response:**
```json
{"error": "Strategy change via API is not supported"}
```

---

## Controller Configuration

### GET /api/v1/controller/config

Get current controller configuration.

**Response:**
```json
{
  "cpuScaleUpThreshold": 0.8,
  "cpuScaleDownThreshold": 0.2,
  "callRateScaleUpThreshold": 1000.0,
  "evaluationIntervalMs": 1000
}
```

### POST /api/v1/controller/config

Update controller configuration. All fields are optional; only provided fields will be updated.

**Request:**
```json
{
  "cpuScaleUpThreshold": 0.75,
  "cpuScaleDownThreshold": 0.15,
  "callRateScaleUpThreshold": 500.0,
  "evaluationIntervalMs": 2000
}
```

**Response:**
```json
{
  "status": "updated",
  "config": {
    "cpuScaleUpThreshold": 0.75,
    "cpuScaleDownThreshold": 0.15,
    "callRateScaleUpThreshold": 500.0,
    "evaluationIntervalMs": 2000
  }
}
```

### GET /api/v1/controller/status

Get controller status including whether it is enabled and its configuration.

**Response:**
```json
{
  "enabled": true,
  "evaluationIntervalMs": 1000,
  "config": {
    "cpuScaleUpThreshold": 0.8,
    "cpuScaleDownThreshold": 0.2,
    "callRateScaleUpThreshold": 1000.0,
    "evaluationIntervalMs": 1000
  }
}
```

### POST /api/v1/controller/evaluate

Trigger immediate controller evaluation.

**Response:**
```json
{
  "status": "evaluation_triggered"
}
```

### GET /api/v1/controller/decisions

Get the leader control loop's per-slice scaling decision snapshot (#425). Returns the latest
decision recorded for each registered artifact during the most recent evaluation cycle, plus the
cluster-average CPU usage surfaced as honest node-capacity context (`clusterCpuContext` is never
acted on by the autoscaler — the per-artifact composite load is the sole scaling driver). Snapshot
read only, no hot-path cost. LEADER-bound (the control loop runs on the leader).

Each decision carries an `outcome` (one of `SCALED_UP`, `SCALED_DOWN`, `HELD`, `BLOCKED`, `CAPPED`)
and the `guard` that shaped it (one of `NONE`, `WINDOW_NOT_FULL`, `SLICE_IN_PROGRESS`, `COOLDOWN`,
`MAX_INSTANCES`, `CLUSTER_CAP`, `ERROR_BLOCK`), together with the driving `loadFactor` and the
instance arithmetic (`currentInstances`, `requestedInstances`, `cappedInstances`) and the `atMs`
timestamp.

**Response:**
```json
{
  "clusterCpuContext": 0.42,
  "decisions": [
    {
      "artifact": "org.example:orders:1.0.0",
      "outcome": "CAPPED",
      "guard": "MAX_INSTANCES",
      "loadFactor": 1.8,
      "currentInstances": 2,
      "requestedInstances": 5,
      "cappedInstances": 3,
      "atMs": 1720512000000
    }
  ]
}
```

---

## TTM (Time-series Trend Model)

### GET /api/v1/ttm/status

Get TTM engine status including configuration, state, and latest forecast.

**Response:**
```json
{
  "enabled": true,
  "active": true,
  "state": "RUNNING",
  "modelPath": "/models/ttm.onnx",
  "inputWindowMinutes": 60,
  "evaluationIntervalMs": 30000,
  "confidenceThreshold": 0.8,
  "hasForecast": true,
  "lastForecast": {
    "timestamp": 1704067200000,
    "confidence": 0.92,
    "recommendation": "ScaleUp"
  }
}
```

### GET /api/v1/ttm/training-data

Export TTM training data (last 120 minute-aggregated samples).

**Response:**
```json
[
  {
    "timestamp": 1704067200000,
    "cpuUsage": 0.45,
    "heapUsage": 0.60,
    "eventLoopLagMs": 1.2,
    "latencyMs": 12.3,
    "invocations": 15000,
    "gcPauseMs": 50,
    "latencyP50": 8.0,
    "latencyP95": 25.0,
    "latencyP99": 80.0,
    "errorRate": 0.005,
    "eventCount": 120
  }
]
```

---

## Alert Management

### GET /api/v1/alerts

Get all alerts (active + history combined).

**Response:**
```json
{
  "active": [...],
  "history": [...]
}
```

### GET /api/v1/alerts/active

Get active alerts only.

### GET /api/v1/alerts/history

Get alert history only.

### POST /api/v1/alerts/clear

Clear all active alerts.

**Response:**
```json
{
  "status": "alerts_cleared"
}
```

### POST /api/v1/alerts/inject

Insert a synthetic alert entry directly, bypassing threshold evaluation. The entry is visible via `GET /api/v1/alerts` (active list) immediately after this call returns and is also written to alert history with status `INJECTED`. Used by integration tests and operator tooling when no threshold-driven path can produce the alert under test.

**RBAC:** OPERATOR · **Routing:** ANY (node-local; alerts are not consensus-replicated)

**Request:**
```json
{
  "name": "test-alert",
  "severity": "WARNING",
  "message": "synthetic alert from operator",
  "metric": "test.integration.counter",
  "value": 42.0
}
```

`name`, `severity` (one of `INFO`, `WARNING`, `CRITICAL`), and `message` are required. `metric` and `value` are optional context fields.

**Response:**
```json
{
  "alertId": "injected-1715431200000-1",
  "name": "test-alert",
  "severity": "WARNING",
  "message": "synthetic alert from operator",
  "timestamp": 1715431200000
}
```

---

## Threshold Configuration

### GET /api/v1/thresholds

Get all configured alert thresholds.

**Response:**
```json
{
  "cpu.usage": {"warning": 0.7, "critical": 0.9},
  "heap.usage": {"warning": 0.7, "critical": 0.85}
}
```

### POST /api/v1/thresholds

Set an alert threshold. Thresholds are persisted to the KV-Store and replicated across all cluster nodes.

**Request:**
```json
{
  "metric": "cpu.usage",
  "warning": 0.7,
  "critical": 0.9
}
```

**Response:**
```json
{
  "status": "threshold_set",
  "metric": "cpu.usage",
  "warning": 0.7,
  "critical": 0.9
}
```

### DELETE /api/v1/thresholds/{metric}

Remove an alert threshold. The removal is persisted to the KV-Store and replicated across all cluster nodes.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/v1/thresholds/cpu.usage
```

**Response:**
```json
{
  "status": "threshold_removed",
  "metric": "cpu.usage"
}
```

---

## Traces

### GET /api/v1/traces

List recent invocation traces.

**Query Parameters:**
- `limit` (int, default 100) -- Maximum traces to return
- `method` (string) -- Filter by callee method name
- `status` (string) -- Filter by outcome: `SUCCESS` or `FAILURE`
- `minDepth` (int) -- Minimum depth filter
- `maxDepth` (int) -- Maximum depth filter

**Example:**
```bash
curl "http://localhost:8080/api/v1/traces?limit=50&method=processOrder"
```

**Response:**
```json
{
  "traces": [
    {
      "requestId": "abc-123",
      "method": "processOrder",
      "depth": 2,
      "durationNs": 15000000,
      "status": "SUCCESS",
      "timestamp": 1704067200000
    }
  ]
}
```

### GET /api/v1/traces/{id}

Get all trace nodes for a specific request ID.

**Example:**
```bash
curl http://localhost:8080/api/v1/traces/abc-123
```

**Response:**
```json
{
  "requestId": "abc-123",
  "nodes": [
    {
      "method": "processOrder",
      "depth": 0,
      "durationNs": 15000000,
      "status": "SUCCESS"
    },
    {
      "method": "validateInventory",
      "depth": 1,
      "durationNs": 5000000,
      "status": "SUCCESS"
    }
  ]
}
```

### GET /api/v1/traces/stats

Get aggregated trace statistics.

**Response:**
```json
{
  "totalTraces": 10000,
  "avgDepth": 2.3,
  "avgDurationMs": 15.0,
  "successRate": 0.995,
  "methodCounts": {
    "processOrder": 5000,
    "validateInventory": 3000
  }
}
```

### POST /api/v1/traces/inject

Insert a synthetic trace entry directly into the node-local trace store. The entry is visible via `GET /api/v1/traces` immediately after this call returns and is indistinguishable in shape from runtime-emitted traces, except for the synthetic `nodeId=@injected` / `caller=@injected` markers. Used by integration tests and operator tooling when no deterministic invocation path can produce a trace under test.

**RBAC:** OPERATOR · **Routing:** ANY (node-local; the invocation trace store is not consensus-replicated, so the inject lands on the receiving node and the read-back must hit the same node)

**Request:**
```json
{
  "operation": "processOrder",
  "durationMs": 123,
  "depth": 2,
  "requestId": "req-abc-123",
  "traceId": "trace-xyz-789"
}
```

`operation` is required and maps to the trace's `callee` field. `durationMs` defaults to `10`, `depth` defaults to `0`. `requestId` and `traceId` are independently optional; if both are omitted, a UUID is generated and used as the trace correlator; if only `traceId` is given, it fills the `requestId` slot (the trace store keys entries by `requestId`).

**Response:**
```json
{
  "traceId": "req-abc-123",
  "requestId": "req-abc-123",
  "operation": "processOrder",
  "durationMs": 123,
  "depth": 2,
  "timestamp": "2026-05-11T08:30:00.000Z"
}
```

---

## DHT (Distributed Hash Table)

### GET /api/v1/dht/replication-map

Operator-facing inspection of the active DHT replication topology — which keys
live on which nodes under the current replication factor. The endpoint walks
the local DHT storage tier (one node's view; for cluster-wide audits query
every node and union) and reports, for each key, the ordered list of node IDs
responsible for replication.

**Query parameters (optional):**
- `limit` — max entries to return (default 100, capped at 10000).
- `prefix` — only include keys whose UTF-8 byte prefix matches.

**Response:**
```json
{
  "replicationFactor": 3,
  "totalKeys":         142,
  "returned":          100,
  "entries": [
    {"key": "user:1",  "nodes": ["node-1", "node-2", "node-3"]},
    {"key": "user:2",  "nodes": ["node-2", "node-3", "node-1"]},
    {"key": "order:1", "nodes": ["node-3", "node-1", "node-2"]}
  ]
}
```

`nodes[0]` is the primary; subsequent entries are replicas walking the
consistent-hash ring clockwise. `totalKeys` reflects the count of keys
matching the supplied `prefix` (or full storage size when no prefix is
given); `returned` is bounded by `limit`. See
`aether/docs/.internal/production-readiness-followup-2026-05-21.md` P-NEW-F.

### POST /api/v1/dht/inject

**Dev-mode-only.** Writes a value into the local DHT storage tier with an
operator-supplied HLC timestamp, bypassing the regular `DHTClient.put` path
that always advances the node's clock to `now()`. Enables TC-10-G2 (DHT
versioned writes) to build deterministic version-conflict scenarios without
racing the live clock.

Gated by `AETHER_INSECURE_DEV_MODE=true` — same gate pattern as
`/api/v1/alerts/inject`, `/api/v1/scheduled-tasks/inject`, and
`/api/v1/metrics/backfill`. Route target is `LOCAL` — tests POST directly to
the node they wish to mutate (no leader forwarding). Precondition: a node with
operator-provided TLS certificates refuses to start in dev-mode, so this route is never
reachable on a node configured with real TLS.

**Request body:**
```json
{
  "key":   "user:1",
  "value": "alice",
  "hlc":   {"physical": 1716280000000000, "logical": 0}
}
```

Fields:
- `key` — DHT key (UTF-8); MUST be non-blank.
- `value` — DHT value (UTF-8 string; serialized to bytes server-side).
- `hlc.physical` — physical-microseconds component of the explicit timestamp.
- `hlc.logical` — logical-counter component of the explicit timestamp.

**Response:**
```json
{
  "key":          "user:1",
  "committedHlc": {"physical": 1716280000000000, "logical": 0},
  "written":      true
}
```

`committedHlc` is the timestamp actually recorded for the write. It MAY be
advanced relative to the request when the node's local clock had already
moved past the supplied timestamp (HLC merge rule guarantees monotonic
advancement). `written` is `true` when the storage layer accepted the value
as the newest version, `false` when a stale-version write was suppressed.

**Error responses (all surface a structured cause message):**
- Missing/blank `key` field → `key field is required`.
- Missing/null `value` field → `value field is required`.
- Missing/null `hlc` field → `hlc field is required`.
- `AETHER_INSECURE_DEV_MODE` unset / `false` → `dht inject requires AETHER_INSECURE_DEV_MODE=true`.

---

## Storage (Hierarchical Storage Engine)

Operator-facing inspection and snapshot control for the node's Hierarchical Storage
Engine instances (#207) — the content-addressed block stores that back content,
artifact, and stream storage. Each instance exposes its tier topology
(`MEMORY` / `LOCAL_DISK` / `REMOTE`), per-tier capacity utilisation, a
`ReadinessState` (`LOADING_SNAPSHOT` / `SNAPSHOT_LOADED` / `READY`), and metadata
snapshot epoch/timestamp.

The `/api/v1/storage*` routes are **per-node diagnostics** served node-locally (the
receiving node's own instances). The `/api/v1/cluster/storage*` routes are
**leader-aggregated**: the leader publishes every node's storage status into the
KV-Store and returns the cluster-wide rollup.

### GET /api/v1/storage

List the storage instances on the connected node with their tier utilisation and
readiness.

**Routing:** LOCAL (node-local; not forwarded). **RBAC:** VIEWER.

**Response:**
```json
{
  "instances": [
    {
      "name": "content",
      "tiers": [
        {"level": "MEMORY",     "usedBytes": 1048576,  "maxBytes": 134217728,   "utilizationPct": 0.8},
        {"level": "LOCAL_DISK", "usedBytes": 52428800, "maxBytes": 10737418240, "utilizationPct": 0.5}
      ],
      "readiness": {"state": "READY", "isReadReady": true, "isWriteReady": true},
      "wal": null
    },
    {
      "name": "streams",
      "tiers": [
        {"level": "MEMORY",     "usedBytes": 2097152,  "maxBytes": 134217728,   "utilizationPct": 1.6},
        {"level": "LOCAL_DISK", "usedBytes": 31457280, "maxBytes": 10737418240, "utilizationPct": 0.3}
      ],
      "readiness": {"state": "READY", "isReadReady": true, "isWriteReady": true},
      "wal": {"totalBytes": 8388608, "walPartitions": 4}
    }
  ]
}
```

- `utilizationPct`: `usedBytes / maxBytes` as a percentage rounded to one decimal (`0.0` when `maxBytes` is 0).
- `readiness.isReadReady` / `isWriteReady`: whether the instance's `StorageReadinessGate` currently admits reads / writes.
- `wal` (#634-3): live stream-WAL usage — non-`null` **only on the `streams` instance**. The WAL is a
  sibling directory of the segment store, not a storage tier, so without this field the `streams`
  instance under-reports its real disk footprint by the entire WAL. Every other instance reports
  `null` rather than a zero that would read as "has a WAL, currently empty".
- `wal.totalBytes`: sum of live WAL bytes across every stream partition on this node — derived from
  the same snapshot as [`GET /api/v1/storage/retention`](#get-apiv1storageretention)'s `walTotalBytes`,
  so the two surfaces always agree.
- `wal.walPartitions`: number of partitions on this node that currently have a WAL.

### GET /api/v1/storage/{name}

Detail for a single named storage instance on the connected node, including the
latest metadata-snapshot marker. Returns a failure when no instance with that name
exists on the node.

**Routing:** LOCAL. **RBAC:** VIEWER.

**Response:**
```json
{
  "name": "content",
  "tiers": [
    {"level": "MEMORY",     "usedBytes": 1048576,  "maxBytes": 134217728,   "utilizationPct": 0.8},
    {"level": "LOCAL_DISK", "usedBytes": 52428800, "maxBytes": 10737418240, "utilizationPct": 0.5}
  ],
  "snapshot": {"lastEpoch": 42, "lastTimestampMs": 1716280000000},
  "readiness": {"state": "READY", "isReadReady": true, "isWriteReady": true},
  "wal": null
}
```

- `snapshot.lastEpoch` / `lastTimestampMs`: epoch and epoch-millis of the most recent metadata snapshot taken by the instance's `SnapshotManager` (`0` when none has been taken yet).
- `wal`: same semantics as on `GET /api/v1/storage` — non-`null` only when `{name}` is `streams`.

### POST /api/v1/storage/snapshot/{name}

Force an immediate metadata snapshot of the named storage instance (calls the
instance's `SnapshotManager.forceSnapshot()`). Returns the epoch and timestamp of
the snapshot just taken.

**Routing:** routed to the `STORAGE` task-group owner. **RBAC:** ADMIN (default for unlisted mutations).

**Request:** empty body (`{}`).

**Response:**
```json
{
  "name": "content",
  "epoch": 43,
  "timestampMs": 1716280001234
}
```

### GET /api/v1/cluster/storage

Cluster-wide rollup of every node's storage instances. The leader first publishes
each node's current storage status into the KV-Store, then groups the statuses by
instance name and reports per-instance totals plus a per-node breakdown.

**Routing:** LEADER (forwarded to the current leader). **RBAC:** VIEWER.

**Response:**
```json
{
  "instances": [
    {
      "name": "content",
      "nodeCount": 3,
      "totalUsedBytes": 157286400,
      "totalMaxBytes": 32212254720,
      "totalWalBytes": 0,
      "nodes": [
        {
          "nodeId": "node-1",
          "tiers": [
            {"level": "MEMORY",     "usedBytes": 1048576,  "maxBytes": 134217728,   "utilizationPct": 0.8},
            {"level": "LOCAL_DISK", "usedBytes": 52428800, "maxBytes": 10737418240, "utilizationPct": 0.5}
          ],
          "readiness": {"state": "READY", "isReadReady": true, "isWriteReady": true},
          "walBytes": 0
        }
      ]
    }
  ]
}
```

- `totalUsedBytes` / `totalMaxBytes`: sums across every tier of every node hosting the instance.
- `walBytes` (#634-3): the node's live stream-WAL bytes as published with its storage status — non-zero
  only on the `streams` instance rows (`0` everywhere else). `totalWalBytes` is the sum of `walBytes`
  across the instance's nodes: on the `streams` instance it is the cluster's total WAL footprint.

### GET /api/v1/cluster/storage/{name}

Cluster-wide detail for a single named instance — the per-node breakdown including
each node's snapshot marker. Returns a failure when no node reports an instance with
that name.

**Routing:** LEADER. **RBAC:** VIEWER.

**Response:**
```json
{
  "name": "streams",
  "nodeCount": 3,
  "totalUsedBytes": 94371840,
  "totalMaxBytes": 32212254720,
  "totalWalBytes": 25165824,
  "nodes": [
    {
      "nodeId": "node-1",
      "tiers": [
        {"level": "MEMORY",     "usedBytes": 2097152,  "maxBytes": 134217728,   "utilizationPct": 1.6},
        {"level": "LOCAL_DISK", "usedBytes": 31457280, "maxBytes": 10737418240, "utilizationPct": 0.3}
      ],
      "snapshot": {"lastEpoch": 42, "lastTimestampMs": 1716280000000},
      "readiness": {"state": "READY", "isReadReady": true, "isWriteReady": true},
      "walBytes": 8388608
    }
  ]
}
```

- `walBytes` / `totalWalBytes`: same semantics as on `GET /api/v1/cluster/storage` — per-node live WAL
  bytes and their sum; non-zero only for the `streams` instance.

### WAL placement (`[storage.streams] wal_path`)

The stream WAL's base directory is configurable per node via the `streams` storage instance's TOML
section (#634-3):

```toml
[storage.streams]
wal_path = "/data/aether/stream-wal"
```

- **Empty or absent (the default):** the WAL directory is DERIVED as
  `<sibling of the artifacts instance's disk_path>/stream-segments/<nodeId>/wal` — the pre-#634-3
  location, byte-identical, so existing deployments need no config change.
- **Set:** the effective directory is `<wal_path>/<nodeId>`. The node-id suffix is appended
  unconditionally — multiple nodes on one host (an in-JVM `EmberCluster`, co-located containers)
  must never share a WAL directory, and the mandatory suffix keeps that invariant independent of
  operator input.

An unwritable WAL directory is a **boot error** (#634 item 2): a node that cannot honour the
durability its streams declare refuses to start rather than silently acking publishes without fsync.

### GET /api/v1/storage/retention

The tri-floor retention view (#634-3/4): for every `(stream, partition)` this node holds anything
for, the local sources of history (WAL, in-memory ring, sealed segments) joined with the two
retention floors that drive reclamation (the durable sealed bound and the entity checkpoint), plus
the joint **tri-floor invariant** verdict evaluated over all of them.

**Routing:** LOCAL (per-node view; not forwarded). **RBAC:** VIEWER.

**The tri-floor invariant.** An entity partition with a committed checkpoint must have SOME local
source starting at or below `checkpoint + 1` — `coveredFrom <= checkpointFloor + 1`, where
`coveredFrom` is the MINIMUM of the sources' start offsets. This is deliberately the NECESSARY half
of reachability, not the sufficient one: the check does not prove the union of sources is hole-free
up to the head (reclamation is oldest-first on every mover, so an interior hole has no producer
today — a clean verdict means "no source starts too late", not "every record is present"). The
sources cover: sealed segments `[earliestSegment, sealedThrough]`, the in-memory ring
`[ringTail, head]`, and the WAL's replayable window `(truncatedUpto, lastOffset]` (records at or
below the truncation watermark are discarded on replay regardless of their physical presence). A
MATERIALIZED partition holding nothing at all (`coveredFrom = -1`) under a committed checkpoint is
also violated — the restarted-empty case. Either way a future fold cannot rebuild the partition
without serving state that is missing committed writes — so it will REFUSE; this surface reports the
condition BEFORE that refusal is the first symptom. The three sources are read as a NON-ATOMIC cut
(WAL snapshot, then segment index, then KV), so a single read can show a transient phantom — the
periodic watch therefore requires TWO consecutive violated observations before raising.

**Scope: LOCAL.** WAL, ring, and segment offsets describe the node you query. `checkpointFloor` is
read from replicated KV, so it agrees across nodes; the verdict does not — a partition can be
violated on one node while a replica still holds the range. That per-node asymmetry is what makes
the view actionable (see recovery below).

**Response:**
```json
{
  "walTotalBytes": 8388608,
  "partitions": [
    {
      "stream": "entity:orders",
      "partition": 0,
      "wal": {
        "sizeBytes": 2097152,
        "lastOffset": 1900,
        "truncatedUpto": 1502,
        "lastCompactedUpto": 1502,
        "fsyncCount": 1901,
        "fsyncMeanMicros": 84.3,
        "fsyncMaxMicros": 2210.5,
        "failStopped": false
      },
      "ringTail": 1650,
      "sealedThrough": 1502,
      "earliestSegment": 0,
      "checkpointFloor": 1502,
      "coveredFrom": 0,
      "violated": false,
      "violation": ""
    }
  ]
}
```

| Field | Description |
|-------|-------------|
| `walTotalBytes` | Total live WAL bytes across every partition on this node — the same number the `streams` storage instance reports as `wal.totalBytes` (both derive from one snapshot) |
| `partitions[]` | One row per `(stream, partition)` this node holds anything for — materialized (ring/WAL) or held only as sealed segments — sorted by stream, then partition |
| `stream` / `partition` | The partition coordinate (`entity:`-prefixed streams are durable-entity logs) |
| `wal` | The partition's live WAL counters; `null` when it has no WAL (non-durable path, or a segment-only row) |
| `wal.sizeBytes` | End of valid data in the WAL file (live bytes; lazily-truncated records still count until compaction reclaims them) |
| `wal.lastOffset` | Last appended offset (`-1` when nothing appended yet) |
| `wal.truncatedUpto` | Truncation watermark — records at or below it are discarded on replay regardless of physical presence, so the replayable window is `(truncatedUpto, lastOffset]`; resets on crash by design (a reclamation hint, not a durability fact) |
| `wal.lastCompactedUpto` | Last offset physically reclaimed by a compaction rewrite (`-1` when the file was never compacted) |
| `wal.fsyncCount` | Group commits completed since the WAL was opened |
| `wal.fsyncMeanMicros` / `fsyncMaxMicros` | Mean / slowest single fsync since open, in microseconds |
| `wal.failStopped` | `true` when this partition's WAL refused further appends after a failed fsync (#634-7 fail-stop — a retried fsync can falsely succeed after the OS drops the dirty pages, so the WAL stops instead). Every publish on the partition fails until the recovery action: **restart the node** — reopen re-scans the file and trims to the valid prefix; nothing acked is lost |
| `ringTail` | Earliest offset still in the in-memory ring (`-1` when the materialized ring is EMPTY — has never held a record, e.g. right after a restart; a partition with no ring at all appears only as a segment-only row or not at all) |
| `sealedThrough` | The durable sealed bound — what WAL truncation chases (`-1` when nothing is sealed) |
| `earliestSegment` | Earliest sealed-segment start offset still retained (`-1` when no segments are retained) |
| `checkpointFloor` | The entity checkpoint (`throughOffset`, from replicated KV); `-1` when no fold has ever checkpointed, or the stream is not an entity log |
| `coveredFrom` | The MINIMUM start offset across local sources (`earliestSegment`, `ringTail`, WAL window start); `-1` when this node holds nothing replayable — which under a committed checkpoint is itself a violation (restarted-empty) |
| `violated` | The tri-floor invariant failed: a checkpoint exists and either no local source reaches back to `checkpoint + 1` (`coveredFrom > checkpointFloor + 1`) or nothing local exists at all (`coveredFrom = -1`) |
| `violation` | Human-readable gap description naming the missing offset range (`""` when not violated) |

Absence is data here: `-1` per the table above, and a `null` `wal` on a partition of a durable stream
means that partition is on the non-durable path on this node.

**Periodic invariant watch.** The metrics-threshold alert path is evaluated only while a dashboard
client is connected, so a violation nobody polls for would stay invisible. A periodic watch re-runs
this same assembly **every 5 minutes** and, for each NEWLY violated partition, WARN-logs and raises
one operator alert — name **`retention-invariant`**, severity **`CRITICAL`** — through the alert
injection path (visible via `GET /api/v1/alerts/active` and `aether alerts active`). A raise requires the
SAME partition to be violated on TWO consecutive ticks (the tri-floor join is a non-atomic cut, so a
truncate landing between reads can synthesize a one-tick phantom). No re-alert while the same
violation persists; a partition that recovers and later relapses re-earns its two ticks and alerts
again.

**Operator recovery — what clears a `violated` row.** A violated partition means a fold from the
checkpoint would refuse on this node: the records in `[checkpointFloor + 1, coveredFrom - 1]` are on
no local source. Recovery is restoring the missing range from a replica that still holds it
(re-replication via partition backfill). If no replica holds the range, the remaining option is
accepting the documented loss and re-baselining the checkpoint. The flag is computed on read: it
clears on the next read (and the next watch tick) after local sources again cover `checkpoint + 1`.

**Dashboard: dormant slot, decided explicitly (QUAD invariant, #494).** No panel is added. This is a
PER-NODE coverage diagnostic; per the 2026-07-20 owner ruling a dormant dimension must show a true
degenerate value rather than a fabricated one, and a cluster aggregate of per-node coverage verdicts
has no honest degenerate rendering (nodes legitimately differ — that difference is the signal). The
periodic `retention-invariant` alert is the push-side surface; this endpoint and
`aether storage retention` are the pull side.

---

## Observability Depth

Per-method logging-ladder depth thresholds. Since #277 these routes are backed by the unified
observability config store (see [Observability Config](#observability-config) below), not a
separate depth registry. `POST /api/v1/observability/depth` **materializes** a method-scope config:
on an unconfigured method it pins the baseline-equivalent toggles (logging + metrics + tracing on,
spans off) with the requested depth — so setting a depth never darkens an injection point, it only
changes the logging-ladder threshold. `DELETE` removes the method-scope config, falling back to the
next-broader scope (else the baseline default depth).

### GET /api/v1/observability/depth

List all configured per-method depth overrides.

**Response:**
```json
{
  "overrides": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "method": "processOrder",
      "depthThreshold": 3
    }
  ]
}
```

### POST /api/v1/observability/depth

Set a per-method depth threshold.

**Request:**
```json
{
  "artifact": "org.example:my-slice:1.0.0",
  "method": "processOrder",
  "depthThreshold": 3
}
```

**Response:**
```json
{
  "status": "depth_set",
  "artifact": "org.example:my-slice:1.0.0",
  "method": "processOrder",
  "depthThreshold": 3
}
```

### DELETE /api/v1/observability/depth/{artifact}/{method}

Remove a per-method depth override.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/v1/observability/depth/org.example:my-slice:1.0.0/processOrder
```

**Response:**
```json
{
  "status": "depth_removed",
  "artifact": "org.example:my-slice:1.0.0",
  "method": "processOrder"
}
```

---

## Observability Config

Per-injection-point runtime observability facet control (#277). An injection point is one
`artifactBase/methodName` dispatch seam; each resolves to one of three **effective states**:

- **`baseline`** — no config at any scope. The ambient facets run (depth-leveled logging, sampled
  tracing, invocation counting), spans off. "Off means baseline, not blind."
- **`configured`** — an explicit non-off config. Only the facets its toggles select run
  (`logging`, `metrics`, `tracing`; `spans` is reserved, no body yet — #304), at the config's own
  `depth`. Logging and tracing share one sampling decision, so a logging-only config logs sampled
  successes plus all failures.
- **`darkened`** — an explicit all-off config. Identity: the call runs untouched (one volatile
  read), the operator's deliberate opt-out.

**Scope hierarchy** (nearest wins, whole-snapshot — never a per-field merge): method scope
(`artifactBase` + `methodName`) → artifact scope (`artifactBase` + `*`) → global scope (`*` + `*`)
→ baseline. Pass `*` in the artifact and/or method segment to address the artifact or global scope.

> Reads are leader-forwarded. The replicated config fields (`state`, toggles, `depth`) are
> cluster-consistent; the per-node live field `invocationCount` (and baseline cells that have no
> config key) reflect the responding leader node.

### GET /api/v1/observability/config

List the effective observability state of every known injection point and config scope.

**Response:**
```json
[
  {
    "artifactBase": "org.example:my-slice",
    "methodName": "processOrder",
    "state": "configured",
    "logging": true,
    "metrics": true,
    "spans": false,
    "tracing": true,
    "depth": 2,
    "invocationCount": 1421
  }
]
```

`invocationCount` is `null` when no live cell is registered for the key (e.g. a wildcard scope).
For a `baseline` entry the toggle fields show the baseline-equivalent set (what actually runs).

### GET /api/v1/observability/config/{artifactBase}/{methodName}

Effective state for a single injection point or scope. `*` is accepted for the artifact (`base/*`)
and global (`*/*`) scopes.

### POST /api/v1/observability/config

Set a whole config snapshot at a scope (ADMIN). Absent boolean fields are `false` (facet off).

**Request:**
```json
{
  "artifact": "org.example:my-slice",
  "method": "processOrder",
  "logging": true,
  "metrics": true,
  "spans": false,
  "tracing": true,
  "depth": 2
}
```

**Response:**
```json
{
  "status": "config_set",
  "artifact": "org.example:my-slice",
  "method": "processOrder",
  "logging": true,
  "metrics": true,
  "spans": false,
  "tracing": true,
  "depth": 2
}
```

Use `*` for `artifact` and/or `method` to set the artifact or global scope.

### DELETE /api/v1/observability/config/{artifactBase}/{methodName}

Remove the config at a scope (ADMIN); resolution falls back to the next-broader scope (else the
baseline).

**Response:**
```json
{
  "status": "config_removed",
  "artifact": "org.example:my-slice",
  "method": "processOrder"
}
```

---

## Log Level Management

Runtime log level control with cluster-wide persistence via KV-Store consensus.

### GET /api/v1/logging/levels

Get all runtime-configured log level overrides.

**Response:**
```json
{
  "org.pragmatica.aether.node": "DEBUG",
  "org.pragmatica.consensus": "WARN"
}
```

### POST /api/v1/logging/levels

Set log level for a specific logger. The change is persisted to the KV-Store and replicated across all cluster nodes.

**Request:**
```json
{
  "logger": "org.pragmatica.aether.node",
  "level": "DEBUG"
}
```

Available levels: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`, `OFF`

**Response:**
```json
{
  "status": "level_set",
  "logger": "org.pragmatica.aether.node",
  "level": "DEBUG"
}
```

### DELETE /api/v1/logging/levels/{logger}

Reset a logger to its configuration default. The removal is persisted to the KV-Store and replicated across all cluster nodes.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/v1/logging/levels/org.pragmatica.aether.node
```

**Response:**
```json
{
  "status": "level_reset",
  "logger": "org.pragmatica.aether.node"
}
```

---

## Dynamic Configuration

Configuration overrides are persisted to the KV-Store and replicated across all cluster nodes. Overrides take precedence over base configuration from TOML/environment/system properties.

**Note:** Dynamic configuration routes are only available when `DynamicConfigManager` is enabled.

### GET /api/v1/config

Get all configuration values (base + overrides merged).

**Response:**
```json
{
  "database.host": "localhost",
  "database.port": "5432",
  "server.port": "8080"
}
```

### GET /api/v1/config/overrides

Get only dynamic overrides from the KV store.

**Response:**
```json
{
  "database.port": "5433"
}
```

### POST /api/v1/config

Set a configuration override. Omit `nodeId` for cluster-wide, include it for node-specific.

**Request (cluster-wide):**
```json
{
  "key": "database.port",
  "value": "5433"
}
```

**Request (node-specific):**
```json
{
  "key": "server.port",
  "value": "9090",
  "nodeId": "node-2"
}
```

**Response:**
```json
{
  "status": "config_set",
  "key": "database.port",
  "value": "5433"
}
```

### DELETE /api/v1/config/{key}

Remove a cluster-wide configuration override. The base value from TOML/env/system properties is restored.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/v1/config/database.port
```

**Response:**
```json
{
  "status": "config_removed",
  "key": "database.port"
}
```

### DELETE /api/v1/config/nodes/{id}/{key}

Remove a node-specific configuration override.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/v1/config/nodes/node-2/server.port
```

**Response:**
```json
{
  "status": "config_removed",
  "key": "server.port"
}
```

---

## Deployments

Unified deployment API supporting immediate, canary, blue-green, and rolling strategies. All deployment mutation endpoints (start, promote, complete, rollback) require the requesting node to be the cluster leader.

### GET /api/v1/deploy

List all active deployments across all strategies.

**Response:**
```json
{
  "deployments": [
    {
      "deploymentId": "2bKyJE8yxxxxxxxxxxx",
      "strategy": "ROLLING",
      "artifactBase": "org.example:my-slice",
      "oldVersion": "1.0.0",
      "newVersion": "2.0.0",
      "state": "ROUTING",
      "newInstances": 3,
      "createdAt": 1704067200000,
      "updatedAt": 1704067200000
    }
  ]
}
```

### GET /api/v1/deploy/{id}

Get a single deployment by ID. Use `current` as the ID to resolve to the first active deployment.

**Response:**
```json
{
  "deploymentId": "2bKyJE8yxxxxxxxxxxx",
  "strategy": "ROLLING",
  "artifactBase": "org.example:my-slice",
  "oldVersion": "1.0.0",
  "newVersion": "2.0.0",
  "state": "ROUTING",
  "routing": "1:3",
  "newInstances": 3,
  "createdAt": 1704067200000,
  "updatedAt": 1704067200000
}
```

Strategy-specific fields vary:
- **ROLLING**: includes `routing` (traffic ratio)
- **CANARY**: includes `currentStage`, `trafficPercent`, `stages`
- **BLUE_GREEN**: includes `activeSlot` (`BLUE` or `GREEN`)

### POST /api/v1/deploy

Start a new deployment. Requires leader node.

**Request:**
```json
{
  "artifactBase": "org.example:my-slice",
  "version": "2.0.0",
  "strategy": "ROLLING",
  "instances": 3,
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "requireManualApproval": false,
  "cleanupPolicy": "GRACE_PERIOD"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `artifactBase` | string | Yes | Artifact coordinates (group:artifact) |
| `version` | string | Yes | Target version |
| `strategy` | string | No | `IMMEDIATE` (default), `CANARY`, `BLUE_GREEN`, `ROLLING` |
| `instances` | integer | No | Number of new version instances (default: 1) |
| `maxErrorRate` | float | No | Max error rate threshold (default: 0.01) |
| `maxLatencyMs` | integer | No | Max latency threshold in ms (default: 500) |
| `requireManualApproval` | boolean | No | Require manual approval (default: false) |
| `cleanupPolicy` | string | No | `IMMEDIATE`, `GRACE_PERIOD` (default), `MANUAL` |

**Response:** Same as `GET /api/v1/deploy/{id}`.

### POST /api/v1/deploy/promote/{id}

Advance a deployment to its next stage. The behavior depends on the strategy:
- **ROLLING**: Shifts traffic to the next routing ratio
- **CANARY**: Promotes to the next traffic stage (e.g., 1% to 5%)
- **BLUE_GREEN**: Switches all traffic to the new version

Requires leader node.

**Response:** Same as `GET /api/v1/deploy/{id}`.

### POST /api/v1/deploy/rollback/{id}

Rollback to old version. Requires leader node.

**Response:** Same as `GET /api/v1/deploy/{id}`.

### POST /api/v1/deploy/complete/{id}

Complete the deployment (finalize new version, decommission old). Requires leader node.

**Response:** Same as `GET /api/v1/deploy/{id}`.

---

## Cluster Topology

### GET /api/v1/cluster/topology

Get live cluster topology with per-node details. Returns core/passive/worker counts from the actual connected topology (not static boot-time config).

When a cluster generation snapshot is available the `coreCount` field is derived from the snapshot's `ON_DUTY`+`HEALTHY` core members, and the response additionally carries the current `epoch` string (`"rabiaTerm:localCounter"`). When no snapshot is available yet, `coreCount` falls back to `topologyManager.reportedActiveNodeCount()` and the `epoch` field is omitted.

The `fsmMembers` array (Wave-1 diagnostic extension, cluster-topology-overhaul spec item 6) exposes the queried node's authoritative per-member `MembershipFsm` truth: lifecycle state (`Observed` / `Member` / `Suspect` / `Departing` / `Dead`), the SWIM incarnation high-water mark, and the last-known descriptor `role` / `source` labels. DEAD members are included (retained for incarnation-fenced rejoin), so a remote run reads membership truth without `docker logs`.

Each `nodeDetails` and `fsmMembers` entry carries BOTH `role` (the self-asserted descriptor role from `AETHER_ROLE`) and `assignedRole` (#259 — the CDM-assigned role from the KV-Store `ActivationDirective`, or `UNASSIGNED` when none). The two diverge for worker-demoted nodes: a node still reporting descriptor `role: "core"` but `assignedRole: "WORKER"` is running in observer mode under a CDM demotion. The CLI `cluster topology` table surfaces this as the `ASSIGNED` column.

**Response:**
```json
{
  "coreCount": 5,
  "coreMax": 15,
  "coreMin": 3,
  "workerCount": 0,
  "clusterSize": 5,
  "coreNodes": ["node-1", "node-2", "node-3", "node-4", "node-5"],
  "connectedPeerCount": 5,
  "nodeDetails": [
    {
      "nodeId": "node-1",
      "role": "ACTIVE",
      "assignedRole": "CORE",
      "health": "CONNECTED",
      "hostname": "aether-node-1",
      "zone": "",
      "address": "aether-node-1:6000"
    },
    {
      "nodeId": "lb-passive",
      "role": "PASSIVE",
      "assignedRole": "UNASSIGNED",
      "health": "CONNECTED",
      "hostname": "aether-lb",
      "zone": "",
      "address": "0.0.0.0:7000"
    }
  ],
  "epoch": "7:142",
  "fsmMembers": [
    {
      "nodeId": "node-1",
      "fsmState": "Member",
      "incarnation": 3,
      "role": "core",
      "assignedRole": "CORE",
      "source": "docker"
    },
    {
      "nodeId": "node-9",
      "fsmState": "Dead",
      "incarnation": 1,
      "role": "core",
      "assignedRole": "WORKER",
      "source": "docker"
    }
  ]
}
```

### GET /api/v1/cluster/journal

Dump the queried node's transition journal (cluster-topology-overhaul spec, Wave 1 Enrichment A) — a bounded, in-memory, per-node ring buffer recording **every membership-FSM transition** (layer `FSM`) and **every transport peer-lifecycle transition** (layer `PEER`), plus the dialer expected-vs-actual Hello diagnostic (`cause` prefixed `dialer-hello`) and the boot future-history detection (`cause: boot-future-history`). Local route (each node serves its own journal); read-only; pure observability — not consensus, not KV, lost on restart. Capacity defaults to 4096 entries per layer (override with the `aether.journal.capacityPerLayer` system property).

**Query parameters:**

| Parameter | Description |
|-----------|-------------|
| `layer` | `fsm` or `peer`; omitted returns both layers merged in sequence order |
| `limit` | Maximum entries per layer, newest kept (default `256`) |

**Response:**
```json
{
  "count": 2,
  "entries": [
    {
      "seq": 17,
      "wallClockMs": 1765432100123,
      "layer": "FSM",
      "nodeId": "node-3",
      "from": "Suspect",
      "to": "Dead",
      "cause": "Stopped",
      "incarnation": 2,
      "role": "core"
    },
    {
      "seq": 18,
      "wallClockMs": 1765432100456,
      "layer": "PEER",
      "nodeId": "node-3",
      "from": "CONNECTED",
      "to": "REMOVED",
      "cause": "authoritative-remove",
      "incarnation": -1,
      "role": ""
    }
  ]
}
```

Errors: `layer` values other than `fsm` / `peer` are rejected.

### GET /api/v1/cluster/membership

Membership diagnostics — the responding node's authoritative `MembershipFsm` lifecycle view plus its quorum-loss self-drain readiness. Purpose: diagnose SWIM-under-concurrent-loss situations without log-scraping — per survivor, which peers are SUSPECT/DEAD and whether this node's quorum-loss self-drain window is armed and below threshold. **Per-node local view** (each node serves its own membership state); read-only; **not leader-forwarded** — query an individual node to read *that* node's view (e.g. each survivor's view during a multi-core-loss window).

`requiredThreshold` is the simple-majority quorum threshold `coreCount / 2 + 1` (`0` while the core count is still unknown at bootstrap). `armed` is a cold-start latch: a node that has never observed a quorate count never self-drains. Since #557 the count behind that latch is the *observed-reachability* core count (`MembershipFsm.strictCoreObservedMemberCount`), not the boot-seeded one — previously every configured core counted from process start, so the latch armed on configuration before the cluster had formed and the cold-start guard was already spent when formation began. DEAD members are retained in `members[]` for incarnation-fenced rejoin.

**RBAC:** VIEWER · **Routing:** LOCAL

**Response:**
```json
{
  "nodeId": "core-1",
  "strictCoreMemberCount": 2,
  "countedCoreMemberCount": 5,
  "requiredThreshold": 3,
  "belowThreshold": true,
  "armed": true,
  "coreAbsence": {
    "armed": true,
    "fenced": false,
    "sinceLastPingMs": 6200,
    "remainingMs": 3800,
    "thresholdMs": 10000
  },
  "members": [
    {"nodeId": "core-1", "state": "Member",  "incarnation": 1, "role": "core", "strictCore": true,  "countsTowardEffective": true},
    {"nodeId": "core-3", "state": "Suspect", "incarnation": 2, "role": "core", "strictCore": false, "countsTowardEffective": true},
    {"nodeId": "core-4", "state": "Dead",    "incarnation": 2, "role": "core", "strictCore": false, "countsTowardEffective": false}
  ]
}
```

| Field | Description |
|-------|-------------|
| `nodeId` | The node answering — whose local view this is |
| `strictCoreMemberCount` | Core members in FSM state exactly `Member` — the quorum-loss numerator |
| `countedCoreMemberCount` | Core members counting toward effective membership (`Member` + `Suspect`) |
| `requiredThreshold` | Simple-majority quorum threshold `coreCount / 2 + 1` (`0` while core count unknown at bootstrap) |
| `belowThreshold` | Whether the strict count is currently below threshold |
| `armed` | Whether this node has ever observed a quorate count (cold-start latch; a never-quorate node never self-drains). "Observed" is literal since #557: the count is reachability-derived, so the latch cannot arm on configuration alone |
| `coreAbsence` | #590 — the COMMUNITY tier's fence, the twin of the core-tier fields above. The core broadcasts `ClusterSyncPing` cluster-wide; a node that stops receiving them has lost the core and dissolves itself locally, without the consensus write an isolated community could never complete |
| `coreAbsence.armed` | Whether a term-accepted core ping has ever arrived. `false` means this node has never heard the core and is cold-starting — **not** isolated. Without this latch every community would fence itself during formation |
| `coreAbsence.fenced` | Whether the local dissolve has already fired. Terminal: recovery is a re-join, not a node deciding on its own that it is serving again |
| `coreAbsence.sinceLastPingMs` | Age of the last term-accepted ping (`-1` if none ever arrived). Pings from a stale leader are rejected by term fencing and do **not** refresh this, so a partitioned-away former leader cannot hold a community open |
| `coreAbsence.remainingMs` | Time left before this node fences itself (`-1` when unarmed or already fenced). **The field to watch during a suspected partition** |
| `coreAbsence.thresholdMs` | The configured `timeouts.cluster.core_absence` |
| `members[]` | Every tracked peer (DEAD retained for incarnation-fenced rejoin) |
| `members[].state` | FSM lifecycle state (`Observed` / `Member` / `Suspect` / `Departing` / `Dead`) |
| `members[].incarnation` | Member incarnation |
| `members[].role` | `core` / `worker` / `unknown` |
| `members[].strictCore` | In the strict `Member`-only quorum set |
| `members[].countsTowardEffective` | In the `Member` + `Suspect` counted set |

> **Why core-absence is on a LOCAL endpoint and not the leader's.** A node approaching its core-absence
> fence is, by definition, one the core is losing contact with — so a leader-forwarded answer is the one
> answer nobody can obtain during the incident it describes. Query the suspect node's own management
> port. The leader's complementary view (which communities the core has stopped counting, on the longer
> `timeouts.cluster.community_absence` window) is what re-places slices; the two windows are ordered
> `core_absence < community_absence`, refused at config load otherwise, so a community always stops
> serving before its work is handed to anyone else.

### GET /api/v1/ownership/{domain}

Committed ownership + fence diagnostics (#345 item 1f) — for every partition/key in the requested domain: the owner `NodeId`, the committed fence `Epoch`, the responding node's LOCAL per-domain epoch high-water, and a `fenced` flag. Purpose: let an operator (or the cloud handover test) verify that the ownership fence engaged after a takeover — the committed `epoch` is the live fencing token the Rabia applier compares to reject a deposed owner's strictly-older epoch, and `fenced` pinpoints the node/arc that has already observed a newer epoch than the still-committed owner (the deposed-owner window). **Per-node local view** (each node serves its own committed state and its own high-water table); read-only; **not leader/owner-forwarded**. The committed ownership atoms are Rabia-replicated, so a read off any caught-up node reflects the fenced owner; the high-water is per-node, so `fenced` is answered from THIS node's fence table.

`{domain}` is one of `community` (governor ownership of a community — identity is the community id, owner is the governor), `dht` (DHT partition ownership — identity is the partition id), or `stream` (stream-partition ownership — identity is `{stream}:{partition}`). Entries are sorted by `identity`; an empty `entries[]` means no ownership of that domain is committed yet. Any other `{domain}` value is rejected with a `400` (`Unknown ownership domain '<value>' …`).

**RBAC:** VIEWER · **Routing:** LOCAL

**Response (`GET /api/v1/ownership/stream`):**
```json
{
  "domain": "stream",
  "entries": [
    {"identity": "orders:0", "owner": "core-1", "epoch": {"rabiaTerm": 7, "localCounter": 3}, "highWater": {"rabiaTerm": 7, "localCounter": 3}, "fenced": false},
    {"identity": "orders:1", "owner": "core-2", "epoch": {"rabiaTerm": 7, "localCounter": 1}, "highWater": {"rabiaTerm": 8, "localCounter": 0}, "fenced": true}
  ]
}
```

| Field | Description |
|-------|-------------|
| `domain` | The requested domain (`community` / `dht` / `stream`) |
| `entries[]` | One row per committed ownership atom, sorted by `identity` |
| `entries[].identity` | Domain-specific partition/key: community id (`community`), partition id (`dht`), or `{stream}:{partition}` (`stream`) |
| `entries[].owner` | Committed owner `NodeId` (governor id for `community`) |
| `entries[].epoch` | Committed fence `Epoch` (`fenceEpoch`) as `{rabiaTerm, localCounter}` — the fencing token |
| `entries[].highWater` | This node's LOCAL per-domain monotonic epoch high-water as `{rabiaTerm, localCounter}`; equals `epoch` in steady state, floors to `epoch` when the arc has not been observed |
| `entries[].fenced` | `true` when `highWater` is strictly after `epoch` — the deposed-owner window in which this node has observed a newer epoch than the committed owner record shows, so the committed owner would be rejected as stale here (`false` in steady state) |

### GET /api/v1/cluster/generation

Get the current cluster generation snapshot as observed by the queried node. The snapshot is a leader-projected view of core members, communities, and DHT partition ownership at a specific epoch; every node caches the latest snapshot it received via pings and serves it locally. This endpoint is always safe to call — when no snapshot has been received yet it returns an empty skeleton with `epoch: null` and `mode: "unknown"` instead of a 503.

See [`cluster-generation-spec.md`](../specs/cluster-generation-spec.md) §14.1 for the underlying model.

**Response (snapshot present):**
```json
{
  "epoch": { "rabiaTerm": 7, "localCounter": 142 },
  "rabiaTerm": 7,
  "mode": "HIERARCHICAL",
  "quiescence": "QUIESCED",
  "quiescenceDetail": "",
  "core": {
    "desiredSize": 5,
    "members": [
      {
        "nodeId": "node-1",
        "host": "aether-node-1",
        "port": 6000,
        "lifecycle": "ON_DUTY",
        "healthHint": "HEALTHY",
        "joinedEpoch": { "rabiaTerm": 7, "localCounter": 0 },
        "lastSeenEpoch": { "rabiaTerm": 7, "localCounter": 142 }
      }
    ]
  },
  "communities": [
    {
      "communityId": "worker-pool-a",
      "governorNodeId": "node-6",
      "communityTerm": 3,
      "communityEpoch": { "rabiaTerm": 3, "localCounter": 42 },
      "memberCount": 4,
      "health": { "healthy": 4, "suspected": 0, "faulty": 0 },
      "partitions": ["worker-pool-a"],
      "lastAckAtCore": { "rabiaTerm": 7, "localCounter": 140 },
      "quiescence": "QUIESCED",
      "quiescenceDetail": ""
    }
  ],
  "partitions": [
    {
      "partitionId": "core",
      "ownerNodeId": "node-1",
      "ownerCommunityId": "core",
      "ownerEpoch": { "rabiaTerm": 7, "localCounter": 0 },
      "ownershipTerm": 1
    }
  ]
}
```

**Response (no snapshot yet):**
```json
{
  "epoch": null,
  "rabiaTerm": 0,
  "mode": "unknown",
  "quiescence": "UNKNOWN",
  "quiescenceDetail": "",
  "core": { "desiredSize": 0, "members": [] },
  "communities": [],
  "partitions": []
}
```

### POST /api/v1/cluster/await-quiesced

Block until the queried node has `observedEpoch >= requested` AND the local snapshot reports cluster-wide quiescence at that epoch. Useful for tests and operators that need to wait for a known steady state before proceeding.

**Query parameters:**
- `epoch` — required, in `term:counter` form (e.g. `7:142`).
- `timeout` — optional, default `30s`, max `120s`. Plain numbers are treated as seconds; suffix `s` is permitted.

**Status codes:**
- `200 OK` — quiescence reached.
- `408 Request Timeout` — deadline elapsed before reaching the requested epoch + quiescence.
- `400 Bad Request` — missing/malformed `epoch` or `timeout`.

**Response (success):**
```json
{
  "epoch": "7:142",
  "quiescence": "QUIESCED",
  "waitedMs": 1234
}
```

See [`cluster-generation-spec.md`](../specs/cluster-generation-spec.md) §14.1.

### GET /api/v1/cluster/governors

List active community governors (worker pool leaders elected via SWIM).

**Response:**
```json
{
  "governors": [
    {
      "governorId": "node-6",
      "community": "default:local:0",
      "memberCount": 4,
      "members": ["node-6", "node-7", "node-8", "node-9"]
    }
  ]
}
```

Returns empty list if no worker communities exist (all nodes are core).

---

## Declarative Cluster Configuration

### GET /api/v1/cluster/config

Get the current cluster configuration from the KV-Store.

**Response:**
```json
{
  "tomlContent": "[cluster]\nname = \"production\"\n...",
  "clusterName": "production",
  "version": "0.21.1",
  "coreCount": 5,
  "desiredTopology": [
    {"sourceName": "hetzner-eu", "role": "core", "count": 3},
    {"sourceName": "hetzner-eu", "role": "worker", "count": 4},
    {"sourceName": "aws-us", "role": "core", "count": 2}
  ],
  "coreMin": 3,
  "coreMax": 9,
  "deploymentType": "local",
  "configVersion": 7,
  "updatedAt": 1711468800000
}
```

`desiredTopology` is the authoritative desired shape, per source and per role. `coreCount` is
DERIVED from it — the sum of the `core` entries — rather than stored alongside it, so the two
cannot drift. It is retained because most consumers only need the total, but it cannot say where
those cores live, which is what `POST /api/v1/cluster/scale` needs in a multi-source cluster.

### GET /api/v1/cluster/provisioning

Provisioning diagnostics — answers "why is a core-membership deficit being or not being filled?" without log-scraping. Combines the leader's end-of-pass reconcile decision snapshot, the provisioning circuit-breaker state, and the most recent provisioning failure. Surfaced only on the leader that owns a Cluster Topology Manager; on any other node a `leader: false` body with zeroed counters and an explanatory `lastReason` is returned (the numeric fields are not meaningful in that case).

`deficit` is `configuredCoreCount - effective`, clamped to `>= 0`. `lastTrigger` is the reconcile trigger that produced the snapshot (`NONE` when not leader). `lastProvisionFailure` is `null` when no provisioning failure has been recorded.

**RBAC:** VIEWER · **Routing:** LEADER

**Response (leader):**
```json
{
  "leader": true,
  "configuredCoreCount": 5,
  "countedCoreMembers": 4,
  "effective": 4,
  "deficit": 1,
  "armedForProvisioning": true,
  "reachedFullMembership": true,
  "quorumSafe": true,
  "lastTrigger": "DEFICIT_FOLLOW_UP",
  "lastReason": "deficit 1; provisioning replacement",
  "deficitAgeMs": 4200,
  "circuitBreaker": {
    "consecutiveFailures": 0,
    "tripped": false,
    "nextAllowedMs": 0
  },
  "lastProvisionFailure": null
}
```

**Response (non-leader / no topology manager):**
```json
{
  "leader": false,
  "configuredCoreCount": 0,
  "countedCoreMembers": 0,
  "effective": 0,
  "deficit": 0,
  "armedForProvisioning": false,
  "reachedFullMembership": false,
  "quorumSafe": false,
  "lastTrigger": "NONE",
  "lastReason": "Provisioning diagnostics available only on the leader that owns a cluster topology manager",
  "deficitAgeMs": 0,
  "circuitBreaker": {"consecutiveFailures": 0, "tripped": false, "nextAllowedMs": 0},
  "lastProvisionFailure": null
}
```

### GET /api/v1/cluster/status

Get aggregated cluster status including node health, slice deployment info, and certificate status.

**Response:**
```json
{
  "clusterName": "production",
  "desiredVersion": "0.21.1",
  "desiredCoreCount": 5,
  "actualCoreCount": 5,
  "state": "CONVERGED",
  "leaderId": "node-1",
  "nodes": [
    {"nodeId": "node-1", "role": "core", "kvState": "READY", "derivedStatus": "READY", "version": "0.21.1", "isLeader": true}
  ],
  "slicesDeployed": 12,
  "sliceInstances": 36,
  "certificateExpiresAt": "2026-04-25T00:00:00Z",
  "certificateDaysRemaining": 29,
  "configVersion": 7,
  "uptimeSeconds": 86400
}
```
Per-node fields: `kvState` is the node's heartbeat-reported readiness (`SYNCING` / `READY` / `DRAINING`) as cached by the leader from the leader↔node heartbeat. Despite the legacy field name, this value is **never** read from, stored in, or committed to the KV-Store — it is node-authoritative and presence/heartbeat-derived; empty string when the leader has not yet received a heartbeat. `derivedStatus` is the operator-visible projection of presence (SWIM/QUIC) ∪ heartbeat-readiness ∪ quorum. See `aether/docs/specs/membership-architecture-v2-spec.md`.

### POST /api/v1/cluster/config

Apply a cluster configuration change. Computes a diff against the stored config and executes actionable changes.

**Request:**
```json
{
  "tomlContent": "[cluster]\nname = \"production\"\n...",
  "expectedVersion": 7
}
```

**Response (applied):**
```json
{
  "configVersion": 8,
  "clusterName": "production",
  "coreCount": 5,
  "updatedAt": 1711468800000
}
```

**Response (dry-run / no actionable changes):**
```json
{
  "clusterName": "production",
  "fromVersion": 7,
  "toVersion": 7,
  "plannedChanges": ["[NOOP] core.count unchanged"],
  "changeCount": 0,
  "rejectedCount": 0
}
```

### POST /api/v1/cluster/scale

Scale one `(source, role)` of the desired cluster topology.

Cluster state stores a desired count per source and per role, so a scale request names which
source absorbs the change. A cluster-wide core count cannot express that: with cores in two
sources, "scale cores to 7" does not say where the new nodes go.

**RBAC:** ADMIN · **Routing:** LEADER

**Request:**
```json
{
  "source": "hetzner-eu",
  "role": "core",
  "count": 7,
  "expectedVersion": 7
}
```

| Field | Description |
|-------|-------------|
| `source` | Source name. Blank asks the server to infer it, which succeeds only when exactly one source declares `role`. |
| `role` | `core`, `worker` or `spot`. Blank defaults to `core`. |
| `count` | Target node count for this source and role. |
| `expectedVersion` | Config version read from `GET /api/v1/cluster/config`; the request is rejected if it no longer matches. |

**Response:**
```json
{
  "success": true,
  "source": "hetzner-eu",
  "role": "core",
  "previousCount": 5,
  "newCount": 7,
  "configVersion": 8
}
```

**Validation.** Quorum arithmetic applies to `core` only — it is what keeps a majority reachable,
and it is checked against the resulting **cluster-wide** core total, not the per-source count.
Scaling one core source to 1 is accepted when another source carries 2, because the cluster total
is 3. Worker and spot counts carry no quorum constraint and are required only to be non-negative — scaling a worker tier to zero (drain-all) is legitimate.

**Refusals** (both HTTP 400):

- Undeclared `(source, role)` — the topology is changed with `aether cluster apply`, not with a
  scale. Adding the pair here would turn a mistyped source name into a real provisioning target.
- Blank `source` when several sources declare the role. The response names the candidates.

**Conflicts** (HTTP 409):

- `expectedVersion` no longer matches the stored config version (checked before the write).
- The write itself lost a concurrent race (RFC-0018): the KV applier rejects a config write built on
  a stale read, and the route confirms the requested count actually landed before reporting success.
  A `VersionConflict` here means another writer — an operator or the auto-heal reconciler — advanced
  the config between this request's read and its commit. Recovery: re-read
  `GET /api/v1/cluster/config` and re-issue the scale with the fresh `expectedVersion`.

**`health` values (changed 2026-08-27, #558).** This field previously reported `NodeState.health`,
which was `HEALTHY` for every node the observer had ever discovered — nothing ever drove a node out of
that state, so a dead node still read `HEALTHY`. It now reports what is actually known:

| Value | Meaning |
|-------|---------|
| `CONNECTED` | A live transport link to this node is observed right now |
| `DISCOVERED` | The observer knows this node id, but there is no live link |
| `UNKNOWN` | The node is not in the observer's map at all |

Note that `DISCOVERED` is not a claim of ill health — it means no live link is currently observed, which
during formation is routine. For a liveness judgement prefer the membership view's on-duty set.

### GET /api/v1/cluster/topology/circuit-breaker

Snapshot of the CTM (Cluster Topology Manager) provisioning circuit breaker. The breaker trips after `MAX_CONSECUTIVE_PROVISIONING_FAILURES` (default 3) failed slot-deadline expirations or provider API rejections, halting auto-heal until a recovery trigger fires (`setDesiredSize`, `onNodeReady`, phase NORMAL transition, leader handoff, or operator reset).

**RBAC:** ADMIN · **Routing:** LEADER

**Response:**
```json
{
  "consecutiveFailures": 0,
  "trippedAt": 3,
  "nextAllowedMs": 0,
  "tripped": false
}
```

### POST /api/v1/cluster/topology/circuit-breaker/reset

Operator-triggered reset of the CTM provisioning circuit breaker. Use after fixing an underlying provisioning issue (provider credentials, network connectivity, capacity quota) when none of the auto-recovery triggers (above) have fired. Returns the prior consecutive-failure count for the audit log.

**RBAC:** ADMIN · **Routing:** LEADER

**Response:**
```json
{
  "status": "reset",
  "priorFailureCount": 3
}
```

### GET /api/v1/cluster/topology/auto-heal

Snapshot of the CTM auto-heal toggle. When `enabled=false`, `handleDeficit` is a no-op — deficit-driven replacement provisioning is halted until re-enabled. Operator-controlled gate, distinct from the failure-driven circuit breaker. Use during disruption-budget testing, planned maintenance windows, or any scenario where the cluster should not automatically rebuild after node loss.

**RBAC:** ADMIN · **Routing:** LEADER

**Response:**
```json
{
  "enabled": true
}
```

### POST /api/v1/cluster/topology/auto-heal/enable

Re-enable CTM auto-heal. If a deficit exists at the time of the call, the next reconcile picks it up immediately (no scheduled poll wait). Returns the prior `enabled` state for the audit log.

**RBAC:** ADMIN · **Routing:** LEADER

**Response:**
```json
{
  "enabled": true,
  "previousState": false
}
```

### POST /api/v1/cluster/topology/auto-heal/disable

Disable CTM auto-heal. The change applies immediately to the next `handleDeficit` invocation — already-in-flight provisioning attempts continue to completion. Returns the prior `enabled` state for the audit log.

**RBAC:** ADMIN · **Routing:** LEADER

**Response:**
```json
{
  "enabled": false,
  "previousState": true
}
```

### POST /api/v1/cluster/upgrade

Initiate a cluster version upgrade. Phase 1 updates the version in the KV-Store config. Full rolling upgrade orchestration uses existing RollingUpdateManager infrastructure.

**RBAC:** ADMIN

**Request:**
```json
{
  "targetVersion": "0.26.0"
}
```

**Response:**
```json
{
  "status": "INITIATED",
  "from": "1.0.0-rc1",
  "to": "0.26.0"
}
```

**Error (already at target version):**
```json
{
  "error": "Cluster is already at version 0.26.0"
}
```

---

## API Key Management

### POST /api/v1/cluster/keys

Create or update an API key entry. Used by `aether cluster rotate-key` to push new keys.

**RBAC:** ADMIN

**Request:**
```json
{
  "keyId": "ak_1a2b3c4d",
  "keyHash": "<SHA-256 hex>",
  "status": "ACTIVE",
  "expiresAt": -1,
  "gracePeriodMs": 300000
}
```

**Response:**
```json
{
  "status": "stored",
  "keyId": "ak_1a2b3c4d"
}
```

### GET /api/v1/cluster/keys

List all API keys with status.

**RBAC:** ADMIN

**Response:** a JSON array of key records. Each record carries its own `status`
(`ACTIVE`, `REVOKED`, or `EXPIRED`) — clients must read the per-record field rather than matching
a status token against the whole document.

```json
[
  {
    "keyId": "ak_1a2b3c4d",
    "status": "ACTIVE",
    "createdAt": 1712000000000,
    "expiresAt": -1,
    "revokedAt": -1,
    "gracePeriodMs": 300000,
    "authorizationRole": "ADMIN"
  }
]
```

### POST /api/v1/cluster/keys/revoke/{id}

Revoke an API key. The key remains valid during its grace period.

**RBAC:** ADMIN

**Request:**
```json
{
  "immediate": false
}
```

**Response:**
```json
{
  "status": "revoked",
  "keyId": "ak_1a2b3c4d",
  "gracePeriodMs": 300000
}
```

### GET /api/v1/cluster/keys/audit

List API key audit trail (create, rotate, revoke, expire events).

**RBAC:** ADMIN

**Response:**
```json
{
  "entries": [
    {
      "keyId": "ak_1a2b3c4d",
      "action": "CREATED",
      "timestamp": 1712000000000,
      "operatorHint": "bootstrap"
    }
  ]
}
```

---

## Topology

### GET /api/v1/slices/topology

Get the cluster-wide topology graph showing data flow between endpoints, slices, resources, and pub-sub topics. Nodes are grouped per-slice with `sliceArtifact` for swim-lane layout. Topic connectors carry `topicConfig` for cross-slice pub-sub matching.

The graph preserves route declaration order from TOML configuration files.

**Response:**
```json
{
  "nodes": [
    {
      "id": "endpoint:GET:/api/shorten",
      "type": "ENDPOINT",
      "label": "GET /api/shorten",
      "sliceArtifact": "org.example:url-shortener:1.0.0"
    },
    {
      "id": "slice:org.example:url-shortener:1.0.0",
      "type": "SLICE",
      "label": "UrlShortener",
      "sliceArtifact": "org.example:url-shortener:1.0.0"
    },
    {
      "id": "topic-pub:org.example:url-shortener:1.0.0:org.example.url-shortener:click-events:1.0.0",
      "type": "TOPIC_PUB",
      "label": "org.example.url-shortener:click-events:1.0.0",
      "sliceArtifact": "org.example:url-shortener:1.0.0"
    },
    {
      "id": "topic-sub:org.example:analytics:1.0.0:org.example.url-shortener:click-events:1.0.0",
      "type": "TOPIC_SUB",
      "label": "org.example.url-shortener:click-events:1.0.0",
      "sliceArtifact": "org.example:analytics:1.0.0"
    }
  ],
  "edges": [
    {
      "from": "endpoint:GET:/api/shorten",
      "to": "slice:org.example:url-shortener:1.0.0",
      "style": "SOLID",
      "topicConfig": ""
    },
    {
      "from": "topic-pub:org.example:url-shortener:1.0.0:org.example.url-shortener:click-events:1.0.0",
      "to": "topic-sub:org.example:analytics:1.0.0:org.example.url-shortener:click-events:1.0.0",
      "style": "DOTTED",
      "topicConfig": "org.example.url-shortener:click-events:1.0.0"
    }
  ]
}
```

**Node types:** `ENDPOINT`, `SLICE`, `TOPIC_PUB`, `TOPIC_SUB`, `RESOURCE`

**Edge styles:**
- `SOLID` — direct intra-slice connections (endpoint→slice, slice→resource) and slice-to-slice dependencies
- `DOTTED` — cross-slice pub-sub topic connectors (topic-pub→topic-sub)

**Node ID formats:**
- Endpoints: `endpoint:{method}:{path}`
- Slices: `slice:{artifact}`
- Resources: `resource:{artifact}:{type}:{config}` (per-slice)
- Topic publishers: `topic-pub:{artifact}:{namespace}:{topic}:{version}` (per-slice)
- Topic subscribers: `topic-sub:{artifact}:{namespace}:{topic}:{version}` (per-slice)

**Topic addressing:** Pub/sub topics are identified by their resolved canonical
`namespace:topic:version` address (the same `ResourceAddress` model used by streams — see
[Stream Namespaces](#stream-namespaces)). A bare/legacy topic declaration (`order-events`) has its
namespace derived from the publishing slice's blueprint Maven coordinates (`groupId.artifactId`) and
its version defaulted to `1.0.0`; an explicitly-namespaced declaration is used verbatim. The
`namespace`/`label`/`topicConfig` values therefore carry the fully-qualified address, so a bare
publisher and an explicitly-namespaced subscriber of the same logical topic resolve to the same
address and connect. The `system` namespace is reserved for framework-internal topics.

**Cross-slice matching:** Publishers and subscribers whose resolved `namespace:topic:version`
addresses are equal are connected many-to-many via DOTTED edges. The `topicConfig` field on these
edges contains the matching canonical address.

---

## Artifact Repository

### GET /repository/info/{groupPath}/{artifactId}/{version}

Get artifact metadata including size, checksums, and deployment status.

**Example:**
```bash
curl http://localhost:8080/repository/info/org/example/my-slice/1.0.0
```

**Response:**
```json
{
  "artifact": "org.example:my-slice:1.0.0",
  "size": 1048576,
  "chunkCount": 16,
  "md5": "d41d8cd98f00b204e9800998ecf8427e",
  "sha1": "da39a3ee5e6b4b0d3255bfef95601890afd80709",
  "deployedAt": 1704067200000,
  "isDeployed": true
}
```

### GET /repository/{groupPath}/{artifactId}/{version}/{filename}

Download an artifact file from the repository.

**Content-Type**: Determined dynamically by file extension.

CLI: `aether artifacts get <group:artifact:version> [--out=<file>] [--file=<filename>]`
streams the response bytes to stdout (default) or to the `--out` file. The
`--file` option overrides the default `<artifactId>-<version>.jar` filename
segment.

### PUT /repository/{groupPath}/{artifactId}/{version}/{filename}

Upload an artifact file to the repository. Maximum upload size: 64 MB.

**Content-Type**: Binary content (e.g., `application/java-archive`).

**Idempotent (RC1)**: this endpoint is idempotent. Both a fresh upload and a
duplicate upload (where the artifact is already present in the store) return
HTTP 200 OK with a JSON body. Clients can distinguish the two cases via the
`status` field instead of grepping error strings or relying on a 4xx status.

Fresh upload response:

```json
{
  "status": "uploaded",
  "coords": "org.example:my-slice:1.0.0",
  "size": 524288,
  "md5": "...",
  "sha1": "..."
}
```

Duplicate upload response (artifact already in store; size/md5/sha1 read from
persisted metadata without re-reading the underlying chunks):

```json
{
  "status": "already-present",
  "coords": "org.example:my-slice:1.0.0",
  "size": 524288,
  "md5": "...",
  "sha1": "..."
}
```

Failure responses are unchanged: 4xx/5xx with the standard
`application/problem+json` envelope.

### POST /repository/{groupPath}/{artifactId}/{version}/{filename}

Alternative upload method (same behavior as PUT).

### GET /repository/{groupPath}/{artifactId}/maven-metadata.xml

Get Maven metadata XML for an artifact.

**Content-Type**: `application/xml`

---

## Dashboard

### GET /dashboard

Serves the built-in cluster monitoring dashboard (static HTML/JS/CSS files).

**Content-Type**: `text/html`

Open in browser: `http://localhost:8080/dashboard`

---

## WebSocket Endpoints

### WS /ws/dashboard

Real-time dashboard metrics streaming via WebSocket.

**Connection:**
```javascript
const ws = new WebSocket('ws://localhost:8080/ws/dashboard');
ws.onmessage = (event) => {
  const metrics = JSON.parse(event.data);
  console.log(metrics);
};
```

### WS /ws/status

Real-time cluster status streaming via WebSocket. Pushes periodic JSON snapshots containing uptime, node metrics, slices, and cluster info.

**Message Format:**
```json
{
  "uptimeSeconds": 123456,
  "nodeMetrics": [
    {
      "nodeId": "node-1",
      "isLeader": true,
      "cpuUsage": 0.45,
      "heapUsedMb": 256,
      "heapMaxMb": 512
    }
  ],
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "state": "ACTIVE",
      "instances": [
        {"nodeId": "node-1", "state": "ACTIVE"}
      ]
    }
  ],
  "cluster": {
    "nodes": [
      {"id": "node-1", "isLeader": true}
    ],
    "leaderId": "node-1",
    "nodeCount": 3
  }
}
```

### WS /ws/events

Real-time cluster event streaming via WebSocket. Pushes only new events since the last broadcast (1-second interval). No data is sent when there are no new events.

**Connection:**
```javascript
const ws = new WebSocket('ws://localhost:8080/ws/events');
ws.onmessage = (event) => {
  const events = JSON.parse(event.data);
  events.forEach(e => console.log(`[${e.severity}] ${e.type}: ${e.summary}`));
};
```

**Message Format:**
```json
[
  {
    "timestamp": "2024-01-15T10:30:00Z",
    "type": "NODE_JOINED",
    "severity": "INFO",
    "summary": "Node node-2 joined cluster (now 3 nodes)",
    "details": {
      "nodeId": "node-2",
      "clusterSize": "3"
    }
  }
]
```

Messages are only sent when new events have occurred since the previous broadcast. Each message is a JSON array of `ClusterEvent` objects.

## WebSocket Authentication

When API keys are configured, WebSocket connections require first-message authentication:

1. Client connects to WebSocket endpoint
2. Server sends: `{"type":"AUTH_REQUIRED"}`
3. Client sends: `{"type":"AUTH","apiKey":"your-api-key"}`
4. Server responds: `{"type":"AUTH_SUCCESS"}` or `{"type":"AUTH_FAILED","reason":"..."}`

If not authenticated within 5 seconds, the connection is closed.

When no API keys are configured, WebSocket connections are immediately authorized.

---

## Worker Pools

### GET /api/v1/workers

List worker nodes across all communities, read from committed consensus state (the governor
announcements written by each community's `GovernorAnnouncer`). One row per worker; a worker that is
also its community's governor is flagged with `isGovernor`. Members of dissolved communities are
omitted. Rows are ordered by community, then node id.

This is the per-worker projection of the same announcements that `/api/v1/cluster/governors` projects
per-community.

**Response:**
```json
{
  "workers": [
    {
      "nodeId": "governor-1",
      "community": "east",
      "governorId": "governor-1",
      "isGovernor": true,
      "communityTerm": 7,
      "announcedAt": 1700000000000
    },
    {
      "nodeId": "worker-a",
      "community": "east",
      "governorId": "governor-1",
      "isGovernor": false,
      "communityTerm": 7,
      "announcedAt": 1700000000000
    }
  ]
}
```

A cluster running no workers returns `{"workers":[]}`.

### GET /api/v1/workers/health

**Not implemented — returns HTTP 501.** Workers publish only their community roster to consensus
(`GovernorAnnouncementValue`); no per-worker health fact is replicated, so the leader has nothing to
report. Use `GET /api/v1/workers` for the roster and `GET /api/v1/cluster/membership` for per-node SWIM
state. The corresponding `aether workers health` CLI subcommand was removed in #525.

### GET /api/v1/workers/endpoints

**Not implemented — returns HTTP 501.** Only the *governor's* `tcpAddress` is recorded in consensus,
never per-worker endpoints, so a cluster-wide worker endpoint table cannot be assembled. Use
`GET /api/v1/routes` for the cluster HTTP route table. The corresponding `aether workers endpoints` CLI
subcommand was removed in #525.

---

<!-- Canary and blue-green deployment endpoints are now unified under /api/v1/deploy above. -->

---

## A/B Testing

All A/B test mutation endpoints require the requesting node to be the cluster leader.

### GET /api/v1/ab-tests

List all active A/B tests.

**Response:**
```json
{
  "tests": [
    {
      "testId": "ab-001",
      "artifactBase": "org.example:my-service",
      "variants": {
        "control": {"version": "1.0.0", "weight": 50},
        "experiment": {"version": "2.0.0", "weight": 50}
      },
      "state": "RUNNING",
      "createdAt": 1704067200000
    }
  ]
}
```

### GET /api/v1/ab-tests/{id}

Get A/B test status.

**Response:**
```json
{
  "testId": "ab-001",
  "artifactBase": "org.example:my-service",
  "variants": {
    "control": {"version": "1.0.0", "weight": 50},
    "experiment": {"version": "2.0.0", "weight": 50}
  },
  "splitStrategy": "HEADER_HASH",
  "state": "RUNNING",
  "createdAt": 1704067200000,
  "updatedAt": 1704067230000
}
```

### GET /api/v1/ab-tests/metrics/{id}

Get per-variant metrics for an A/B test.

**Response:**
```json
{
  "testId": "ab-001",
  "variants": {
    "control": {
      "version": "1.0.0",
      "requestCount": 5000,
      "errorRate": 0.001,
      "avgLatencyMs": 45.0
    },
    "experiment": {
      "version": "2.0.0",
      "requestCount": 5100,
      "errorRate": 0.002,
      "avgLatencyMs": 42.0
    }
  },
  "collectedAt": 1704067230000
}
```

### POST /api/v1/ab-tests/create

Create a new A/B test. Requires leader node.

**Request:**
```json
{
  "artifactBase": "org.example:my-service",
  "variants": {
    "control": {"version": "1.0.0", "weight": 50},
    "experiment": {"version": "2.0.0", "weight": 50}
  },
  "splitStrategy": "HEADER_HASH",
  "instances": 3
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `artifactBase` | string | Yes | Base artifact coordinates (group:artifact) |
| `variants` | object | Yes | Map of variant name to version + weight |
| `splitStrategy` | string | No | `HEADER_HASH`, `COOKIE_HASH`, `HEADER_MATCH`, `PERCENTAGE` (default: `PERCENTAGE`) |
| `instances` | integer | No | Instances per variant (default: 1) |

**Response:** Same as `GET /api/v1/ab-tests/{id}`.

### POST /api/v1/ab-tests/conclude/{id}

Conclude the A/B test and promote the winning variant. Requires leader node.

**Request:**
```json
{
  "winner": "experiment"
}
```

**Response:** Same as `GET /api/v1/ab-tests/{id}`.

---

## Endpoint Summary

Every route below is served under the `/api/v1/v1` prefix (landed 2026-08-28, #300), composed at one
site by `ManagementRoute`'s `API_BASE` constant [mechanism:
`aether/aether-management-api/.../route/ManagementRoute.java`; design:
[`management-api-versioning-spec.md`](../specs/management-api-versioning-spec.md) §2.1]. Four
surfaces are deliberately unversioned carve-outs and stay bare: the health probes
(`/health/live`, `/health/ready` — spec §2.2), the artifact-repository routes (`/repository/**` —
spec §2.2), the dashboard (`/dashboard`, a static asset, not a management-API route), and the
WebSocket endpoints (`/ws/*`, likewise outside the `ManagementRoute` enum). The Stream Management
and Stream Namespaces sections below are intentionally not covered by this note or this table — a
separate, still in-flight consolidation effort (spec §3.2–§3.3) owns that surface; see
[`versioning-and-compatibility.md`](versioning-and-compatibility.md) for status.

| Method | Path | Section |
|--------|------|---------|
| GET | `/health/live` | Health Probes |
| GET | `/health/live/{id}` | Health Probes |
| GET | `/health/ready` | Health Probes |
| GET | `/health/ready/{id}` | Health Probes |
| GET | `/api/v1/nodes/status` | Cluster Status |
| GET | `/api/v1/health` | Cluster Status |
| GET | `/api/v1/nodes` | Cluster Status |
| GET | `/api/v1/events` | Cluster Status |
| GET | `/api/v1/slices` | Slice Management (cluster-wide) |
| GET | `/api/v1/nodes/slices` | Slice Management (per-node) |
| GET | `/api/v1/slices/status` | Slice Management |
| GET | `/api/v1/slices/config/{id}` | Slice Management |
| GET | `/api/v1/nodes/routes` | Slice Management (per-node) |
| GET | `/api/v1/routes` | Slice Management (cluster-wide) |
| POST | `/api/v1/scale` | Slice Management |
| POST | `/api/v1/blueprints` | Blueprint Management |
| GET | `/api/v1/blueprints` | Blueprint Management |
| GET | `/api/v1/blueprints/{id}` | Blueprint Management |
| GET | `/api/v1/blueprints/{id}/status` | Blueprint Management |
| DELETE | `/api/v1/blueprints/{id}` | Blueprint Management |
| POST | `/api/v1/blueprints/deploy` | Blueprint Management |
| POST | `/api/v1/blueprints/publish` | Blueprint Management |
| POST | `/api/v1/blueprints/validate` | Blueprint Management |
| GET | `/api/v1/metrics` | Metrics |
| GET | `/api/v1/metrics/comprehensive` | Metrics |
| GET | `/api/v1/metrics/derived` | Metrics |
| GET | `/api/v1/metrics/prometheus` | Metrics |
| GET | `/api/v1/metrics/transport` | Metrics |
| GET | `/api/v1/metrics/history` | Metrics |
| GET | `/api/v1/metrics/timeouts` | Metrics |
| POST | `/api/v1/metrics/backfill` | Metrics (dev-mode only) |
| GET | `/api/v1/nodes/metrics` | Metrics |
| GET | `/api/v1/artifacts/metrics` | Metrics |
| GET | `/api/v1/invocations/metrics` | Metrics |
| GET | `/api/v1/invocations/metrics/slow` | Metrics |
| GET | `/api/v1/invocations/metrics/strategy` | Metrics |
| POST | `/api/v1/invocations/metrics/strategy` | Metrics |
| GET | `/api/v1/controller/config` | Controller |
| POST | `/api/v1/controller/config` | Controller |
| GET | `/api/v1/controller/status` | Controller |
| GET | `/api/v1/controller/decisions` | Controller |
| POST | `/api/v1/controller/evaluate` | Controller |
| GET | `/api/v1/ttm/status` | TTM |
| GET | `/api/v1/ttm/training-data` | TTM |
| GET | `/api/v1/alerts` | Alert Management |
| GET | `/api/v1/alerts/active` | Alert Management |
| GET | `/api/v1/alerts/history` | Alert Management |
| POST | `/api/v1/alerts/clear` | Alert Management |
| GET | `/api/v1/thresholds` | Threshold Configuration |
| POST | `/api/v1/thresholds` | Threshold Configuration |
| DELETE | `/api/v1/thresholds/{metric}` | Threshold Configuration |
| GET | `/api/v1/traces` | Traces |
| GET | `/api/v1/traces/{id}` | Traces |
| GET | `/api/v1/traces/stats` | Traces |
| GET | `/api/v1/observability/depth` | Observability Depth |
| POST | `/api/v1/observability/depth` | Observability Depth |
| DELETE | `/api/v1/observability/depth/{artifact}/{method}` | Observability Depth |
| GET | `/api/v1/observability/config` | Observability Config |
| GET | `/api/v1/observability/config/{artifactBase}/{methodName}` | Observability Config |
| POST | `/api/v1/observability/config` | Observability Config |
| DELETE | `/api/v1/observability/config/{artifactBase}/{methodName}` | Observability Config |
| GET | `/api/v1/dht/replication-map` | DHT |
| POST | `/api/v1/dht/inject` | DHT (dev-mode only) |
| GET | `/api/v1/storage` | Storage (per-node) |
| GET | `/api/v1/storage/{name}` | Storage (per-node) |
| GET | `/api/v1/storage/retention` | Storage (per-node) |
| POST | `/api/v1/storage/snapshot/{name}` | Storage |
| GET | `/api/v1/cluster/storage` | Storage (cluster-wide) |
| GET | `/api/v1/cluster/storage/{name}` | Storage (cluster-wide) |
| GET | `/api/v1/entity/checkpoints` | Durable Entities (per-node) |
| GET | `/api/v1/entity/keyspaces` | Durable Entities |
| GET | `/api/v1/logging/levels` | Log Level Management |
| POST | `/api/v1/logging/levels` | Log Level Management |
| DELETE | `/api/v1/logging/levels/{logger}` | Log Level Management |
| GET | `/api/v1/config` | Dynamic Configuration |
| GET | `/api/v1/config/overrides` | Dynamic Configuration |
| POST | `/api/v1/config` | Dynamic Configuration |
| DELETE | `/api/v1/config/{key}` | Dynamic Configuration |
| DELETE | `/api/v1/config/nodes/{id}/{key}` | Dynamic Configuration |
| GET | `/api/v1/deploy` | Deployments |
| GET | `/api/v1/deploy/{id}` | Deployments |
| POST | `/api/v1/deploy` | Deployments |
| POST | `/api/v1/deploy/promote/{id}` | Deployments |
| POST | `/api/v1/deploy/rollback/{id}` | Deployments |
| POST | `/api/v1/deploy/complete/{id}` | Deployments |
| GET | `/api/v1/ab-tests` | A/B Testing |
| GET | `/api/v1/ab-tests/{id}` | A/B Testing |
| GET | `/api/v1/ab-tests/metrics/{id}` | A/B Testing |
| POST | `/api/v1/ab-tests/create` | A/B Testing |
| POST | `/api/v1/ab-tests/conclude/{id}` | A/B Testing |
<!-- Rolling update endpoints replaced by unified /api/v1/deploy above -->
| GET | `/api/v1/slices/topology` | Topology |
| GET | `/repository/info/{group}/{artifact}/{version}` | Artifact Repository |
| GET | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| PUT | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| POST | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| GET | `/dashboard` | Dashboard |
| WS | `/ws/dashboard` | WebSocket |
| WS | `/ws/status` | WebSocket |
| WS | `/ws/events` | WebSocket |

| GET | `/api/v1/nodes/lifecycle` | Node Lifecycle |
| GET | `/api/v1/nodes/lifecycle/{id}` | Node Lifecycle |
| POST | `/api/v1/nodes/drain/{id}` | Node Lifecycle |
| POST | `/api/v1/nodes/shutdown/{id}` | Node Lifecycle |
| GET | `/api/v1/scheduled-tasks` | Scheduled Tasks |
| GET | `/api/v1/scheduled-tasks/{section}` | Scheduled Tasks |
| POST | `/api/v1/scheduled-tasks/pause/{section}/{artifact}/{methodName}` | Scheduled Tasks |
| POST | `/api/v1/scheduled-tasks/resume/{section}/{artifact}/{methodName}` | Scheduled Tasks |
| POST | `/api/v1/scheduled-tasks/trigger/{section}/{artifact}/{methodName}` | Scheduled Tasks |
| GET | `/api/v1/scheduled-tasks/state/{section}/{artifact}/{methodName}` | Scheduled Tasks |
| POST | `/api/v1/scheduled-tasks/inject` | Scheduled Tasks (dev-mode only) |
| GET | `/api/v1/scheduled-tasks/executions-by-node/{section}/{artifact}/{methodName}` | Scheduled Tasks |
| POST | `/api/v1/certificates/configure-short-validity` | Certificates (dev-mode only) |
| GET | `/api/v1/workers` | Worker Pools |
| GET | `/api/v1/workers/health` | Worker Pools (501 — not implemented) |
| GET | `/api/v1/workers/endpoints` | Worker Pools (501 — not implemented) |
| POST | `/api/v1/cluster/migrate` | Cluster Migration (501 — not implemented) |
| POST | `/api/v1/cluster/migrate/plan` | Cluster Migration (501 — not implemented) |

---

## Node Lifecycle

Observe node membership/readiness and drive graceful operations (drain, shutdown, activation).

Node membership is presence-derived (SWIM/QUIC via NTT); node readiness/drain is heartbeat-reported and leader-cached. This state is **never** stored in or committed to the KV-Store. See `aether/docs/specs/membership-architecture-v2-spec.md`.

**Readiness values (heartbeat-reported):** `SYNCING`, `READY`, `DRAINING`. A node that is no longer present (SWIM/QUIC) simply disappears from the membership view — there is no terminal KV state.

### GET /api/v1/nodes/lifecycle

Get membership + readiness for all nodes.

**Routing:** LEADER — the readiness view is leader-cached from the heartbeat, so a request received by a follower is forwarded to the current leader.

**Query parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `state` | string | no | Case-insensitive readiness value (e.g. `READY`, `DRAINING`), or a `+`-separated union (e.g. `READY+SYNCING`). When present, the response is filtered to entries whose `state` is a member of the set. Omit for the unfiltered list. An empty filter (`+` alone) matches no entry. |

**Examples:**
- `GET /api/v1/nodes/lifecycle?state=READY` — only nodes currently `READY`.
- `GET /api/v1/nodes/lifecycle?state=READY+SYNCING` — nodes whose readiness is `READY` or `SYNCING`.

The `state` value is node-authoritative and heartbeat-reported (`NodeReportedState`); it is never read from the KV-Store.

**Response:**
```json
[
  {
    "nodeId": "node-1",
    "state": "READY",
    "updatedAt": 0
  },
  {
    "nodeId": "node-2",
    "state": "DRAINING",
    "updatedAt": 0
  }
]
```

### GET /api/v1/nodes/lifecycle/{id}

Get membership + readiness for a specific node.

**Response:**
```json
{
  "nodeId": "node-1",
  "state": "READY",
  "updatedAt": 0
}
```

### POST /api/v1/nodes/drain/{id}

Begin draining a node. The leader delivers a `DRAIN` command on the leader↔node heartbeat; the node self-drains (finishes in-flight requests) and reports `DRAINING` on its heartbeat. The CDM evacuates slices respecting the disruption budget. No node-state KV write happens on this path.

**Disruption-budget guard — core-scoped, workers bypass entirely.** Workers carry no consensus weight, so the guard applies only to CORE targets: draining a core is refused with `409 Conflict` when it would leave fewer than `coreCount / 2 + 1` core-scoped nodes operational, where `coreCount` and the post-drain count are both computed from `MembershipFsm.coreCountedMembers()` (never from raw presence, which would count workers alongside cores). A **worker** target bypasses this guard entirely rather than being checked against a narrowed worker-only threshold — there is no worker-capacity floor. If operational continuity for workers is ever needed, that is a distinct feature with its own semantics, not an implicit side effect of the core-quorum guard.
`[mechanism: role resolved via MembershipFsm.memberDescriptor + ActivationDirective override (label-first, directive-overrides), quorum arithmetic in NodeLifecycleRoutes.checkDisruptionBudget]`

Which guard applied — and why — is always visible in `message`, both on success and (implicitly, via `detail`) on rejection, rather than left for the operator to infer from silence:
- `"...core-guard skipped (role=worker)"` — the target is a worker; the guard did not run.
- `"...core-guard applied (role=core, available=<n>, min=<m>)"` — the target is core; the guard ran and passed.

**Recovery when a core drain is rejected:** wait for an in-flight core drain to finish departing (it stops counting once membership no longer reports it), or grow core capacity, then retry. There is no override flag — the guard cannot be forced past for a core target.

**Response (success):**
```json
{
  "success": true,
  "nodeId": "node-1",
  "state": "DRAINING",
  "message": "Drain command enqueued; target will self-drain via heartbeat DRAIN command (core-guard applied (role=core, available=3, min=3))"
}
```

**Response (worker target — guard bypassed):**
```json
{
  "success": true,
  "nodeId": "worker-2",
  "state": "DRAINING",
  "message": "Drain command enqueued; target will self-drain via heartbeat DRAIN command (core-guard skipped (role=worker))"
}
```

**Response (core target — budget exceeded, 409 Conflict):**
```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Disruption budget exceeded: draining node-3 would leave 2 core-scoped operational nodes, minimum is 3 (role=core; worker drains bypass this guard)"
}
```

### POST /api/v1/nodes/shutdown/{id}

Enqueue a graceful shutdown for a node via the membership-v2 DRAIN-command channel. The leader's cluster-sync heartbeat carries `NodePingCommand.DRAIN` to the target, which self-drains (finishes in-flight requests) via its `DrainProcedure` and then halts; the CTM grace-terminate backstop reaps the container if it never self-exits. No direct lifecycle KV write happens on this path.

**Response:**
```json
{
  "success": true,
  "nodeId": "node-1",
  "state": "STOPPED",
  "message": "Shutdown command enqueued; target will self-drain then halt via heartbeat DRAIN command"
}
```

### POST /api/v1/nodes/promote/{id}

Promote a node to a new role (CORE or WORKER) by writing a fresh `ActivationDirective` for the node through consensus. The downstream `ClusterDeploymentManager` consumes the resulting `ActivationDirectivePutReceived` event and drives the role-aware node machinery (`ForwardingClusterNode` / `SwitchableClusterNode`) to align runtime behavior to the new role.

Route target is `LEADER` — the management plane forwards the request to the consensus writer automatically when the caller hits a follower. Requests with an unsupported `targetRole`, a missing body, or an unparseable `{id}` segment fail with a 400-style validation error.

**Authorization:** ADMIN (role transitions are a topology operation).

**Request:**
```json
{
  "targetRole": "WORKER"
}
```

Accepted values for `targetRole` (case-insensitive): `"CORE"`, `"WORKER"`. Promoting a node to the role it already carries is a no-op and reports `success=true` with `previousRole == newRole` without emitting a consensus write.

**Response:**
```json
{
  "success": true,
  "nodeId": "node-2",
  "previousRole": "CORE",
  "newRole": "WORKER",
  "message": "Promoted node from CORE to WORKER"
}
```

## Scheduled Tasks

### GET /api/v1/scheduled-tasks

List all registered scheduled tasks with active timer count and execution state.

**Response:**
```json
{
  "tasks": [
    {
      "configSection": "scheduling.cleanup",
      "artifact": "com.example:my-slice:1.0.0",
      "method": "cleanup",
      "interval": "5m",
      "cron": "",
      "leaderOnly": true,
      "paused": false,
      "registeredBy": "node-1",
      "lastExecutionAt": 1710345600000,
      "nextFireAt": 1710345900000,
      "consecutiveFailures": 0,
      "totalExecutions": 42
    }
  ],
  "activeTimers": 1
}
```

### GET /api/v1/scheduled-tasks/{section}

Get scheduled tasks filtered by config section.

**Response:**
```json
{
  "tasks": [...],
  "configSection": "scheduling.cleanup"
}
```

### POST /api/v1/scheduled-tasks/pause/{section}/{artifact}/{methodName}

Pause a scheduled task. Cancels the active timer; the task remains registered but will not fire until resumed.

**Response:**
```json
{
  "success": true,
  "configSection": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup",
  "action": "paused"
}
```

### POST /api/v1/scheduled-tasks/resume/{section}/{artifact}/{methodName}

Resume a paused scheduled task. Restarts the timer with the configured interval or cron expression.

**Response:**
```json
{
  "success": true,
  "configSection": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup",
  "action": "resumed"
}
```

### POST /api/v1/scheduled-tasks/trigger/{section}/{artifact}/{methodName}

Manually trigger a scheduled task immediately, regardless of its schedule or paused state.

**Response:**
```json
{
  "success": true,
  "configSection": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup",
  "action": "triggered"
}
```

### GET /api/v1/scheduled-tasks/state/{section}/{artifact}/{methodName}

Get detailed execution state for a specific scheduled task.

**Response:**
```json
{
  "configSection": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup",
  "lastExecutionAt": 1710345600000,
  "nextFireAt": 1710345900000,
  "consecutiveFailures": 0,
  "totalExecutions": 42,
  "lastFailureMessage": "",
  "updatedAt": 1710345600000
}
```

### POST /api/v1/scheduled-tasks/inject

**Dev-mode only.** Synchronously fire a scheduled task and advance its `lastExecutionAt` timestamp, bypassing the normal schedule. Used by integration tests that need a deterministic way to drive scheduled-task assertions — replaces the warn-then-pass demotion described in `aether/docs/.internal/audits/integration-test-audit-2026-05-21.md` §2.2 (RC1-blocker #16).

Gated by the `AETHER_INSECURE_DEV_MODE=true` environment variable on the node. When the gate is closed the endpoint returns a failure response and the task is not invoked. Precondition: a node with operator-provided TLS certificates refuses to start in dev-mode, so this route is never reachable on a node configured with real TLS.

**RBAC:** OPERATOR · **Routing:** LOCAL (operates on the node receiving the request)

**Request:**
```json
{
  "section": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup"
}
```

All three fields are required. The `(section, artifact, method)` triple identifies the task using the same coordinates as the `pause`/`resume`/`trigger`/`state` endpoints below (e.g. `/api/v1/scheduled-tasks/pause/{section}/{artifact}/{methodName}`).

**Response:**
```json
{
  "section": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup",
  "previousExecutionMs": 1710345600000,
  "currentExecutionMs": 1710345605123
}
```

`previousExecutionMs` is `0` when no prior state entry exists; otherwise it equals the `lastExecutionAt` value visible via `/api/v1/scheduled-tasks/state/{section}/{artifact}/{methodName}` immediately before the injection. `currentExecutionMs > previousExecutionMs` is guaranteed on success — tests may assert strict monotonic advancement without polling.

### GET /api/v1/scheduled-tasks/executions-by-node/{section}/{artifact}/{methodName}

Surface per-node execution attribution for a scheduled task. Used by `TC-08-F3` to distinguish SINGLE-mode tasks (exactly one node executes per fire) from ALL-mode tasks (every cluster member executes per fire).

**RBAC:** OPERATOR · **Routing:** ANY (the receiving node services the request)

**Response:**
```json
{
  "section": "scheduling.cleanup",
  "artifact": "com.example:my-slice:1.0.0",
  "method": "cleanup",
  "executions": [
    {"nodeId": "node-1", "count": 12, "lastExecutionMs": 1710345600000}
  ]
}
```

`executions` is empty when the task has no prior state. Otherwise each entry pairs a `nodeId` with the number of executions attributed to it and the millisecond epoch of the most recent execution. **RC1 limitation:** the current implementation reports the task's `registeredBy` node as the sole executor (count = `totalExecutions`, lastExecutionMs = `lastExecutionAt`). A follow-up tracks adding per-node execution counters to the KV state so ALL-mode tasks can produce true per-node breakdowns. Tests should currently assert on cumulative totals via this endpoint or via `/api/v1/scheduled-tasks/state/{section}/{artifact}/{methodName}`.

---

## Backup Management

### POST /api/v1/backups

Trigger a manual backup of the KV-Store state.

**Response:**
```json
{
  "success": true,
  "message": "Backup completed"
}
```

### GET /api/v1/backups

List available backups.

**Response:**
```json
[
  {
    "commitId": "abc123",
    "message": "Backup phase 42 at 2026-03-10T12:00:00Z",
    "timestamp": "2026-03-10T12:00:00Z"
  }
]
```

### POST /api/v1/backups/restore

Restore from a specific backup.

**Request body:**
```json
{
  "commit": "abc123"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Restore completed"
}
```

---

## Error Responses

All management-plane failures share one wire shape: an RFC 9457 `application/problem+json`
envelope with the standard `type` / `title` / `status` / `detail` / `instance` members plus a
`requestId` extension for tracing.

```json
{
  "type": "about:blank",
  "title": "Internal Server Error",
  "status": 500,
  "detail": "<cause message>",
  "instance": "/api/v1/cluster/topology",
  "requestId": "a1b2c3d4"
}
```

Cause → status resolution: a cause implementing `HttpStatusAware` surfaces its `httpStatus()`
(e.g. `ClusterConfigError.VersionConflict` → 409, `ClusterNotFound` → 404). Any other cause —
including serialization failures and unmapped domain causes (#308) — falls back to HTTP `500`
but STILL returns the structured `problem+json` body above, never a bare/empty 500. A scripted
client can therefore always parse `status` and `detail`.

The `aether` CLI honors `--format json` on error paths: with `--format json` a failure is
emitted to stderr as a structured `{"error":"<message>"}` object; otherwise the human-readable
`Error: <message>` form is used.

## Schema Management

Manage datasource schema migrations across the cluster.

### Migration ownership and the activation gate

Every schema record names the blueprint that **owns** it — the blueprint whose artifact declared
the migration set. Ownership is what scopes the deployment-side activation gate:

> A slice is withheld from activation **if and only if its own blueprint owns a datasource whose
> migration is in `PENDING`, `MIGRATING` or `FAILED`**. A failed or in-flight migration owned by
> any *other* blueprint does not affect it.

`COMPLETED` is the only status that releases activation. Ownership is compared on the blueprint's
artifact base (`group:artifact`, version stripped), so a blueprint that advances from `1.0.0` to
`1.0.1` still owns the records its earlier version wrote.

**Known limit — this scopes by ownership, not by usage.** The gate matches records to slices via
the *migrator*, not via the readers. A blueprint that reads or writes a datasource **without
declaring migrations for it** is never held when that datasource's owner fails. Do not read this
gate as protecting every consumer of a datasource; it protects the migrator's own slices.

Three further conditions are resolved from deployment state, and a slice for which they cannot be
resolved is reported ready rather than held: the gate applies only when the slice's blueprint is
present in the leader's blueprint map, has `schema_required` set, and carries an owner. No record
can be attributed to a blueprint that carries no owner, so holding it would be an unclearable hold.

Datasource names are **cluster-global** — the default `schema/V001__*.sql` layout yields the name
`database` for every blueprint, and all of them resolve it against the same node-global config
section. That is why a publish claiming a datasource another blueprint already migrates is refused
at deploy time (see [`POST /api/v1/blueprints/deploy`](#post-apiv1blueprintsdeploy)) rather than
namespaced per blueprint.

Recovery from a `FAILED` **or `PENDING`** hold: `POST /api/v1/schema/retry/{datasource}` (`FAILED`
or `PENDING` -> `PENDING` -> `COMPLETED`; #724 widened the guard to accept a datasource already
`PENDING` — a migration that never dispatched has no other lever short of retry or a redeploy),
`POST /api/v1/schema/baseline/{datasource}?version=N` (-> `COMPLETED`), or redeploy the owning
blueprint. The leader also emits a `SCHEMA_ACTIVATION_BLOCKED` audit entry naming the datasource,
the owning blueprint, and the held slices when it observes a `FAILED` record. The currently held
slices for any blocking record are also visible on demand via `heldSlices` on
[`GET /api/v1/schema/status/{datasource}`](#get-apiv1schemastatusdatasource) (#760) — no need to wait
for the audit entry or read DEBUG logs. **Log cadence:** the node log emits one `WARN` when a
slice first enters (or its blocking record changes) a schema hold and one `WARN` when the hold
clears; re-observations of an unchanged hold log at `DEBUG`, so a long-running hold does not
repeat the `WARN` on every re-evaluation tick.

### GET /api/v1/schema/status

Returns schema migration status for all datasources.

**Response:**
```json
{
  "datasources": [
    {
      "datasource": "orders_db",
      "currentVersion": 3,
      "lastMigration": "V003__add_index.sql",
      "status": "COMPLETED",
      "owningBlueprint": "org.example:my-app:1.0.0",
      "heldSlices": []
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `datasource` | string | Datasource name (cluster-global) |
| `currentVersion` | int | Highest version recorded for this datasource |
| `lastMigration` | string | Filename of the last migration recorded |
| `status` | string | `PENDING`, `MIGRATING`, `COMPLETED`, `FAILED` |
| `owningBlueprint` | string | Blueprint id (`group:artifact:version`) that declared the migrations. Whose slices this record holds while `status` is not `COMPLETED` |
| `heldSlices` | string[] | Slices owned by this record's blueprint that are currently sitting in `LOADED` state and being held back from `ACTIVE` by a blocking `status` (#760). Requires BOTH a blocking status (`PENDING`, `MIGRATING`, or `FAILED`) AND at least one owned slice in `LOADED`; empty when `status` is `COMPLETED`, and equally empty under a blocking status if no owned slice is currently `LOADED` (e.g. already `ACTIVE`, or not yet loaded) `[mechanism: ClusterDeploymentState.blocksSliceActivation]` |

### GET /api/v1/schema/status/{datasource}

Returns schema status for a specific datasource.

**Response:**
```json
{
  "datasource": "orders_db",
  "currentVersion": 3,
  "lastMigration": "V003__add_index.sql",
  "status": "COMPLETED",
  "owningBlueprint": "org.example:my-app:1.0.0",
  "heldSlices": []
}
```

While a migration is blocking activation, `heldSlices` names the affected slices directly:

```json
{
  "datasource": "orders_db",
  "currentVersion": 2,
  "lastMigration": "V002__add_column.sql",
  "status": "FAILED",
  "owningBlueprint": "org.example:my-app:1.0.0",
  "heldSlices": ["order-writer", "order-reader"]
}
```

Answers `404 Not Found` when the datasource has no schema record.

### GET /api/v1/schema/history/{datasource}

Returns migration history for a datasource (placeholder -- currently returns the same body as
`GET /api/v1/schema/status/{datasource}`, including `owningBlueprint`).

### POST /api/v1/schema/migrate/{datasource}

Triggers manual schema migration for a datasource. Sets status to `MIGRATING`. Preserves the
existing record's artifact coordinates and owning blueprint.

**Refused with `409 Conflict` (#760 review BLOCKING 1)** when the record is `COMPLETED` **and**
the owning blueprint has at least one slice instance already `ACTIVE`. Re-arming a COMPLETED
record to `MIGRATING` has no orchestrator effect by itself — only a `PENDING` record's KV `Put`
dispatches an actual migration run — but `MIGRATING` is itself a blocking status with no automatic
clearing path, so the next slice instance to reach `LOADED` (scale-up, rolling redeploy, a
rejoining node) would be held indefinitely on a record the operator just re-armed. A COMPLETED
record with **zero** live ACTIVE slices is unaffected and still goes through. Recovery: `baseline`
or `undo` first if a genuine re-migration is intended.

**Also refused with `409 Conflict` (#760/#724 review round 2 item l)** when the record is already
`PENDING`: a fresh `PENDING` `Put` is what dispatches a migration run, so re-arming an already-`PENDING`
record to `MIGRATING` has no dispatch effect of its own — it neither adds nor replaces any in-flight
tracking (a `PENDING` record can otherwise sit with zero in-flight tracking at all, which is exactly
the stuck state #724 fixed) — and would strand the record with no automatic clearing path. Recovery:
`POST /api/v1/schema/retry/{datasource}` accepts `PENDING` immediately: `writeRetryStatus` re-triggers
dispatch, not only once the record has since failed — the `409` body's "or use retry if it has since
failed" below describes the common case, not a precondition retry actually enforces. Dispatch itself
requires this node's deployment FSM to be the elected leader and `Active` (the consensus Put that
`writeRetryStatus` writes is only ever consumed by that state's handler); given that, it still
no-ops when either guard `SchemaOrchestratorService.acquireLock` checks is already held — the local
per-JVM fence (`inFlightMigrations`) or the cross-node consensus lock — so a retry racing an
in-progress attempt for the same datasource returns `LOCK_HELD` rather than starting a second run.
`migrate` itself does not re-trigger dispatch `[mechanism: SchemaRoutes.guardReactivation switches on
the observed status before any orchestrator effect and returns SchemaAlreadyPending for PENDING
without writing MIGRATING]`.

**Response (success):**
```json
{
  "success": true,
  "message": "Migration triggered for orders_db"
}
```

**Response (409 — blueprint already serving):**
```json
{
  "status": 409,
  "detail": "Schema for datasource 'orders_db' is already COMPLETED and serving 3 active slice instances — re-triggering migration would hold the next slice to activate with no automatic recovery; baseline or undo first if a re-migration is genuinely intended"
}
```

**Response (409 — already PENDING):**
```json
{
  "status": 409,
  "detail": "Schema for datasource 'orders_db' already has a migration PENDING — re-arming to MIGRATING has no dispatch effect of its own and would strand the record with no automatic clearing path; wait for the pending migration to dispatch, or use retry if it has since failed"
}
```

### POST /api/v1/schema/undo/{datasource}?targetVersion=N

Undoes migrations to the specified target version. Sets status to `PENDING` at the target version.
Preserves the existing record's artifact coordinates and owning blueprint.

Note that `PENDING` is a **blocking** status: while the undo is outstanding, the owning blueprint's
slices are withheld from activation.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `targetVersion` | int | no | Version to undo to (defaults to `0` when absent; a non-integer value is a `400`) |

**Response:**
```json
{
  "success": true,
  "message": "Undo to version 2 initiated for orders_db"
}
```

### POST /api/v1/schema/retry/{datasource}

Retries a schema migration by resetting status to `PENDING` and clearing the attempt counter. This
is the primary operator recovery from a `FAILED` activation hold, and (#724) also the only lever
for a datasource stuck `PENDING` with a migration that never dispatched — either way the migration
runs again, and reaching `COMPLETED` releases the owning blueprint's slices.

Works when the datasource is in `FAILED` **or `PENDING`** state; `MIGRATING` (a runner is already
in flight) and `COMPLETED` (nothing marked it failed) are refused with `409 Conflict` and
``Schema for datasource '<name>' is not in FAILED state (currently <STATUS>) — retry applies to FAILED or PENDING migrations only``
(the observed status is named, so the refusal explains itself without a second call).

**Response:**
```json
{
  "success": true,
  "message": "Retry initiated for orders_db"
}
```

### POST /api/v1/schema/baseline/{datasource}?version=N

Baselines a datasource at the specified version (marks V001..V{N} as applied without executing).
Sets status to `COMPLETED`, which releases any activation hold on the owning blueprint's slices.

The existing record's artifact coordinates and owning blueprint are **inherited**, not rewritten.
A datasource with **no existing schema record cannot be baselined** — the call fails with
`404 Not Found` and ``Schema status not found for datasource '<name>'`` rather than fabricating an
unowned record. Publish (or deploy) the owning blueprint first so the record exists.

`version` is optional and defaults to `1` when absent; a present-but-non-integer value is rejected
with `400 Bad Request`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `version` | int | no | Version to baseline at (defaults to `1` when absent; a non-integer value is a `400`) |

**Response:**
```json
{
  "success": true,
  "message": "Baselined orders_db at version 3"
}
```

> **Status-code contract — applies to every endpoint in this Schema Management group.** The schema
> failure causes implement `HttpStatusAware`, so the management error funnel renders each as an
> RFC 9457 ProblemDetail carrying its semantic status. Both the status code and the `detail` message
> are a stable contract; scripted clients may match on either.
>
> | Condition | Status | `detail` |
> |-----------|--------|----------|
> | Datasource has no schema record (every route that reads one) | `404 Not Found` | ``Schema status not found for datasource '<name>'`` |
> | `retry` against a datasource that is not `FAILED` or `PENDING` | `409 Conflict` | ``Schema for datasource '<name>' is not in FAILED state (currently <STATUS>) — retry applies to FAILED or PENDING migrations only`` |
> | `migrate` against a COMPLETED record whose owning blueprint has ≥1 live ACTIVE slice | `409 Conflict` | ``Schema for datasource '<name>' is already COMPLETED and serving <N> active slice instance(s) — re-triggering migration would hold the next slice to activate with no automatic recovery; baseline or undo first if a re-migration is genuinely intended`` |
> | `migrate` against a record that is already `PENDING` | `409 Conflict` | ``Schema for datasource '<name>' already has a migration PENDING — re-arming to MIGRATING has no dispatch effect of its own and would strand the record with no automatic clearing path; wait for the pending migration to dispatch, or use retry if it has since failed`` |
> | `?version=` / `?targetVersion=` present but not an integer | `400 Bad Request` | ``Invalid '<parameter>' parameter: '<value>' is not an integer`` |
>
> An **absent** `version`/`targetVersion` is not an error — it takes the documented default
> (`1` for baseline, `0` for undo). Any other failure on these routes remains a `500`.

---

## Stream Management

Endpoints for managing event streams. Streams must be created via stream configuration in blueprints.

> **Two route families, with three operations migrated.** Endpoints in this first group
> historically used flat `/api/v1/streams/{name}` addressing (single stream name, no version).
> **Stream Info**, **Partition Details**, and **Partition Replica State** were folded onto
> namespaced `(namespace, stream, version)` catalog addressing (management-api-versioning-spec.md
> §3.2, #742) -- their flat registrations are gone; the URLs below are the current catalog-form
> ones, kept in this section because the operation itself is unchanged. **Publish Event** and
> **Delete Stream** were folded the same way but with no in-place replacement here -- use
> **Publish** / **Delete Stream Version** under [Stream Namespaces](#stream-namespaces) instead.
> Every other endpoint below (List Streams, Create Stream, Read Events, Join/Leave/Status Consumer
> Group) remains on flat addressing, unaffected by this fold. The
> [Stream Namespaces](#stream-namespaces) group uses namespaced `(namespace, stream, version)`
> addressing throughout and is the surface the `aether stream` CLI drives. New integrations
> should prefer namespaced routes.

### List Streams

```
GET /api/v1/streams
```

**Auth:** ALL_AUTHENTICATED

**Response:**
```json
{
  "streams": [
    {
      "name": "events",
      "partitions": 4,
      "totalEvents": 1024,
      "totalBytes": 65536
    }
  ]
}
```

### Stream Info

```
GET /api/v1/streams/{namespace}/{stream}/{version}/info
```

**Auth:** ALL_AUTHENTICATED

**Response:**
```json
{
  "name": "events",
  "partitions": 4,
  "totalEvents": 1024,
  "totalBytes": 65536,
  "partitionDetails": [
    {
      "partition": 0,
      "headOffset": 255,
      "tailOffset": 0,
      "eventCount": 256
    }
  ]
}
```

### Partition Details

```
GET /api/v1/streams/{namespace}/{stream}/{version}/partitions/{partition}
```

**Auth:** ALL_AUTHENTICATED

**Response:**
```json
{
  "partition": 0,
  "headOffset": 255,
  "tailOffset": 0,
  "eventCount": 256
}
```

### Partition Replica State

```
GET /api/v1/streams/{namespace}/{stream}/{version}/replicas/{partition}
```

**Auth:** ALL_AUTHENTICATED · **Routing:** STREAMING task group

Replication/backfill-health sensor for the stream-replication class (#260/#261/#333). Returns the partition's replica set as seen by the answering node's `ReplicaRegistry`, with the deterministic HRW owner resolved via the read path's owner resolver. Each replica entry carries its replication `state` (`SYNCING` / `CAUGHT_UP` / `LAGGING`), its acked `confirmedOffset`, and whether it `isHrwOwner`. To detect the #333 write-idle residual, compare a `CAUGHT_UP` replica's `confirmedOffset` against the response's `ownerHeadOffset`.

**Owner authority (read this):** the per-peer confirmed-watermark view is advanced by the owner's `DefaultReplicationManager.handleAck`, so the `ReplicaRegistry` is **authoritative only on the partition's HRW owner** — a non-owner mostly knows only itself. The response is therefore **owner-aware, not owner-forwarded**: per-partition-owner forwarding is not a management `RouteTarget` variant (the owner is computed from `(namespace, stream, version)`+`partition`, not a single path param), and the stream forward transport carries only event reads. `servedByOwner` is `true` when the answering node IS the resolved owner (then `replicas` is the complete, authoritative set). **Routing caveat (#490):** this route is delegate-routed (STREAMING task group), so the answering node is an arbitrary streaming-capable delegate — re-querying a different management port still lands on a delegate, and `servedByOwner=true` is generally unobservable here. To reach the owner's authoritative view over HTTP, query the **local variant below** against the `hrwOwner` node's own management port.

### Partition Replica State (per-node local view)

```
GET /api/v1/streams/{name}/{partition}/replicas-local
```

**Auth:** ALL_AUTHENTICATED · **Routing:** LOCAL (answered by the receiving node, never delegated)

#490 per-node variant of the endpoint above, following the `/api/v1/cluster/membership` pattern: the node whose management port you query answers **from its own** `ReplicaRegistry` and owner resolver. Same response shape. Use it to (a) obtain the owner-authoritative view — query the `hrwOwner` node's port and expect `servedByOwner: true` — or (b) sweep every node's port to compare per-node replica views during failover diagnosis (each node reports what IT believes; divergent views are themselves the operator-meaningful signal). Snapshot read, no hot-path cost.

**Response (served by the owner — authoritative):**
```json
{
  "stream": "system:cluster-events:1.0.0",
  "partition": 0,
  "hrwOwner": "core-1",
  "servedByOwner": true,
  "ownerHeadOffset": 256,
  "earliestRetainedOffset": 0,
  "replicas": [
    {"nodeId": "core-1", "state": "CAUGHT_UP", "confirmedOffset": 255, "isHrwOwner": true},
    {"nodeId": "core-3", "state": "CAUGHT_UP", "confirmedOffset": 255, "isHrwOwner": false},
    {"nodeId": "core-4", "state": "SYNCING",   "confirmedOffset": 240, "isHrwOwner": false}
  ]
}
```

| Field | Description |
|-------|-------------|
| `stream` | Partition manager's local stream name |
| `partition` | Partition number queried |
| `hrwOwner` | Resolved deterministic HRW owner node id (`""` during the bootstrap window before placement is known) |
| `servedByOwner` | Whether the answering node is itself the HRW owner — i.e. whether `replicas` is the complete authoritative view |
| `ownerHeadOffset` | The answering node's local next-expected offset (head + 1); on the owner this is the true tail used to spot a lagging `CAUGHT_UP` replica (#333) |
| `earliestRetainedOffset` | Earliest offset still retained locally (`-1` when the partition is absent/empty) |
| `replicas[]` | Every registered replica for the partition, sorted by node id |
| `replicas[].state` | Replication state: `SYNCING` / `CAUGHT_UP` / `LAGGING` |
| `replicas[].confirmedOffset` | The replica's acked confirmed watermark |
| `replicas[].isHrwOwner` | Whether this replica is the resolved HRW owner |

### Entity Checkpoints

```
GET /api/v1/entity/checkpoints
```

**Auth:** ALL_AUTHENTICATED · **Routing:** LOCAL (never delegate-routed)

Per-node durable-entity checkpoint observability (#345 I3). Each node checkpoints only the partitions it
FOLDS, so a delegate's answer would describe a different node's work — query a specific node's management
port to see that node's view.

**Why this surface exists.** A checkpoint is the only thing that ever bounds an entity log: the retention
floor refuses to reclaim any segment at or above a partition's committed checkpoint, so until one is
written nothing is reclaimed at all. A checkpoint driver that silently stopped produces no immediate
symptom — writes succeed, reads succeed, failover still works — and surfaces hours later as unbounded disk
growth with nothing pointing at the cause. Before this endpoint the driver logged only FAILURES, so a
driver that never ran and one that ran perfectly produced identical output.

**What to read.** `writes` is the positive signal: it must climb while a keyspace is taking writes. Flat
`writes` under load is the fault condition. `failures` and `checkpointedThrough` localise it — a partition
whose offset stops advancing while its siblings move is stuck on its own, not cluster-wide. A partition
this node has never folded is ABSENT from `checkpointedThrough` rather than reported as `0`: "nothing to
say about it" and "checkpointed through offset 0" are different claims. An empty `keyspaces` list means
this node hosts no durable-entity keyspace — a true answer, not an error.

Assembled ON REQUEST from counters the checkpoint tick already maintains; no hot-path accounting is added.

**Dashboard: dormant slot, decided explicitly (QUAD invariant, #494).** No panel is added. The dashboard is
ontology-shaped — it presents cluster-wide dimensions — and this is a PER-NODE diagnostic whose value is in
comparing one node's counters against its own history, not in a cluster aggregate. Summing `writes` across
nodes would produce a number that looks meaningful and is not: nodes checkpoint different partitions, so the
sum answers no operator question. Per the 2026-07-20 owner ruling, a dormant dimension must show a true
degenerate value rather than a fabricated one, and there is no honest degenerate rendering here — so the
slot stays dormant with this decision recorded, and the CLI plus this endpoint are the operator surface.
Revisit if a cluster-wide "keyspaces with stalled checkpointing" alert is wanted; that is a different
(aggregatable) question and would earn a panel.

**Response:**
```json
{
  "keyspaces": [
    {
      "keyspace": "orders",
      "partitionCount": 8,
      "writes": 214,
      "failures": 0,
      "checkpointedThrough": {"0": 1841, "3": 990, "5": 1502}
    }
  ]
}
```

### Entity Keyspaces (hosting view)

```
GET /api/v1/entity/keyspaces
```

**Auth:** ALL_AUTHENTICATED · **Routing:** LOCAL (any caught-up node answers identically)

Per-keyspace HOSTING view (#634-3): which nodes hold a committed per-node registration for each
durable-entity keyspace. **`hosts[]` IS the candidate set the leader mints entity-arc owners over**
(the 02w hosting-set fix): a keyspace's partition arcs are owned by nodes from this set and no
others — before the fix ownership was minted over ALL nodes, handing partitions to nodes that had
never registered the keyspace, and the defect was diagnosed from typed write refusals instead of one
GET. This endpoint is that missing surface.

Unlike `/api/v1/entity/checkpoints` above (per-node driver state), this view is assembled from the
committed registration records in **replicated KV**, so any caught-up node answers identically — no
need to sweep ports. An empty `keyspaces` list means no node in the cluster has registered a
durable-entity keyspace.

`partitionCountsDisagree: true` marks a rolling-redeploy window: hosts declared different partition
counts for the keyspace, and `partitionCount` reports the MAX declared — arcs span the max until the
configs re-converge (the merge mirrors the ownership reconciler's own, so what you read here is what
the leader acts on). Persistent disagreement outside a deploy window means a node is running a stale
slice version.

Assembled ON REQUEST from committed records the runtime already maintains; no hot-path accounting.

**Dashboard: dormant slot, decided explicitly (QUAD invariant, #494).** No panel is added. The
hosting set changes only on deploy/unload/node-restart and is naturally read at those moments through
this endpoint or `aether entity keyspaces`; a standing panel would show a static list. Per the
2026-07-20 owner ruling the slot stays dormant with this decision recorded; the CLI plus this
endpoint are the operator surface. Revisit if hosting-set churn becomes an alertable condition —
that is a different (event-shaped) question and would earn a panel.

**Response:**
```json
{
  "keyspaces": [
    {
      "keyspace": "orders",
      "partitionCount": 8,
      "hosts": ["core-1", "core-2", "core-3"],
      "partitionCountsDisagree": false
    }
  ]
}
```

| Field | Description |
|-------|-------------|
| `keyspaces[]` | One row per registered durable-entity keyspace, sorted by keyspace name |
| `keyspace` | The entity keyspace (its stream is `entity:<keyspace>`) |
| `partitionCount` | Declared partition count — the max across hosts while a rolling redeploy is in flight |
| `hosts[]` | Node ids with a committed registration for the keyspace, sorted — the exact candidate set entity-arc owners are minted over |
| `partitionCountsDisagree` | `true` while hosts declare different partition counts (rolling-redeploy window; arcs span the max until configs re-converge) |

---

### Stream hydration

```
GET /api/v1/streams/hydration
```

**Auth:** ALL_AUTHENTICATED · **Routing:** STREAMING task group

Per-node hydration observability for the placement-aware-stream-hydration work (#265) — the §6 regression sensor. Assembled ON REQUEST from the answering node's `StreamPartitionManager` (the live `streams` map plus the off-heap budget counters; no hot-path accounting is added). PER-NODE: `totalAllocatedBytes` / `maxTotalBytes` are that node's off-heap budget, `overBudget` its follower over-subscribe condition (`totalAllocatedBytes > maxTotalBytes` — false in steady state since increment 3 removed over-subscription), and `deferredPartitions` the node-wide count of held-but-not-yet-materialized partitions (the budget-defer sensor). Each `streams[]` row reports `partitionsDeclared` (configured partition count), `ringsMaterialized` (rings actually built on this node), `partitionsDeferred` (held partitions not yet materialized — budget-deferred per §6 or pre-membership), `floorBytesAllocated` (per-partition floor × materialized ring count), and the placement-role tally `ownerPartitions` / `replicaPartitions` / `nonePartitions` for this node.

Materialization is placement-gated (increment 2): `ringsMaterialized` drops below `partitionsDeclared` on non-replicas, and `replicaPartitions` / `nonePartitions` are non-zero on a node that is not OWNER of every partition. Per §6 (increment 3) a follower that cannot admit a held partition's floor NO LONGER over-subscribes — it holds the partition metadata-only and reports it under `partitionsDeferred` until budget frees, at which point the deferred-retry hook materializes it. This surface is how that memory win and any budget pressure are observed.

**Partition caps (increment 4, §7).** The response root also carries the derived partition-cap values: `perStreamCeiling` (the absolute per-stream partition ceiling, `1024`, enforced at create/build and re-checked pre-commit), `clusterAggregateGuard` (`100 × nodes × maxDeclaredReplicas` — the Kafka-style guard bounding aggregate ring memory, `-1` when the cluster size is unknown on a non-cluster manager), `currentAggregatePartitionSlots` (the current cluster ring-slot total `Σ partitions × replicas`), `aggregateHeadroom` (`guard − current`, `-1` when unenforced), and `configOverCeilingStreams` (count of streams whose committed config declares more partitions than the ceiling). Each `streams[].overCeiling` flags that per stream. A create breaching the ceiling or the aggregate guard is rejected PRE-COMMIT on the committing node; a follower observing an over-ceiling committed config never rejects it — it emits a `CommittedConfigOverCeiling` event and sets these flags, and materialization proceeds under the budget backstop.

**Reshuffle lifecycle (increment 5, §5/§14.2).** The response root also carries the reshuffle-lifecycle sensors: `releaseCandidates` (materialized partitions currently DEBOUNCING toward release because their placement role went NONE — a role regained within the ~10s / 2-tick window cancels the candidacy at zero cost), `releasedPartitionsSinceBoot` (running count of partition rings released on confirmed role loss, freeing ring memory + budget while KEEPING the WAL on disk), and `materializeQueueDepth` (partitions queued behind the `reshuffle_concurrency = 2` slot limit, system streams first). A release fires only once the local replica view shows ≥ the effective (clamped) RF other replicas CAUGHT_UP AND the committed ownership record names a different owner. `system:*` streams bypass the budget reject (they oversubscribe with a named event and drain first) — cluster-critical streams are never starved behind app-stream pressure.

**Response:**
```json
{
  "totalAllocatedBytes": 10641408,
  "maxTotalBytes": 134217728,
  "overBudget": false,
  "deferredPartitions": 0,
  "perStreamCeiling": 1024,
  "clusterAggregateGuard": 500,
  "currentAggregatePartitionSlots": 5,
  "aggregateHeadroom": 495,
  "configOverCeilingStreams": 0,
  "releaseCandidates": 0,
  "releasedPartitionsSinceBoot": 0,
  "materializeQueueDepth": 0,
  "streams": [
    {"stream": "orders", "partitionsDeclared": 4, "ringsMaterialized": 4, "partitionsDeferred": 0, "floorBytesAllocated": 5320704, "overCeiling": false, "ownerPartitions": 4, "replicaPartitions": 0, "nonePartitions": 0},
    {"stream": "system:cluster-events:1.0.0", "partitionsDeclared": 1, "ringsMaterialized": 1, "partitionsDeferred": 0, "floorBytesAllocated": 2660352, "overCeiling": false, "ownerPartitions": 1, "replicaPartitions": 0, "nonePartitions": 0}
  ]
}
```

| Field | Description |
|-------|-------------|
| `totalAllocatedBytes` | This node's live off-heap bytes reserved across all streams |
| `maxTotalBytes` | This node's off-heap budget ceiling |
| `overBudget` | Whether `totalAllocatedBytes > maxTotalBytes` (false in steady state since increment 3 removed over-subscription, §6) |
| `deferredPartitions` | Node-wide count of held partitions not yet materialized — the budget-defer sensor (§6) |
| `perStreamCeiling` | Absolute per-stream partition ceiling (§7) — a create/commit over this is rejected pre-commit (`1024`) |
| `clusterAggregateGuard` | Aggregate partition guard `100 × nodes × maxDeclaredReplicas` (`-1` when cluster size unknown) (§7) |
| `currentAggregatePartitionSlots` | Current cluster ring-slot total `Σ partitions × replicas` across known streams (§7) |
| `aggregateHeadroom` | Remaining aggregate slots `guard − current` (`-1` when the guard is unenforced) (§7) |
| `configOverCeilingStreams` | Count of streams whose committed config declares more partitions than the ceiling (§7) |
| `releaseCandidates` | Materialized partitions currently debouncing toward release (placement role went NONE) (§5, increment 5) |
| `releasedPartitionsSinceBoot` | Running count of partition rings released on role loss (ring + budget freed, WAL kept) (§5) |
| `materializeQueueDepth` | Partitions queued behind the `reshuffle_concurrency = 2` slot limit (system first) (§14.2) |
| `streams[].stream` | Partition manager's local stream name |
| `streams[].partitionsDeclared` | Configured partition count for the stream |
| `streams[].ringsMaterialized` | Partition rings actually built on this node (below declared on non-replicas) |
| `streams[].partitionsDeferred` | Held partitions not yet materialized — budget-deferred (§6) or pre-membership |
| `streams[].floorBytesAllocated` | Per-partition floor × materialized ring count |
| `streams[].overCeiling` | Whether this committed config declares more partitions than the per-stream ceiling (§7) |
| `streams[].ownerPartitions` | Partitions this node OWNS under the current placement supplier |
| `streams[].replicaPartitions` | Partitions this node is a non-owner REPLICA of |
| `streams[].nonePartitions` | Partitions this node neither owns nor replicates |

### Declarative Stream Consumers

```
GET /api/v1/streams/declarative-consumers
```

**Auth:** ALL_AUTHENTICATED · **Routing:** LOCAL (per-node)

**CLI:** `aether streams consumers`

What this node knows about declarative `[streams.X]` consumers — slice methods annotated with a `@ResourceQualifier(type = StreamSubscriber.class)` qualifier, which the runtime invokes for every event on the partitions assigned to them (#488). The declaration itself is cluster-wide committed KV, so **every node answers with the same declarations**; what differs per node is which partitions that node has actually attached.

**Which node consumes which partition (#535).** Exactly one node is assigned per `(stream, partition, consumer group)`. Given the candidate set — nodes where the declaring artifact is `ACTIVE`, intersected with the live member view — the assignee is the partition's HRW owner when the owner is in that set, otherwise the HRW pick over the set itself. An assignee that is not the owner holds no ring for the partition and reads THROUGH the owner. Before #535 the rule was owner-gating alone, which meant a slice deployed at default replication frequently had no node that both owned a partition and hosted the consumer, and such partitions were consumed by nobody while every node truthfully reported `attachedSubscriptions: 0`.

`partitionAssignments` is computed identically on every node, so a single call to any node answers "who consumes partition 3, and does it read locally?" — reads are forwarded whenever `consumerNode` differs from `ownerNode`.

`eventTypePublishable` is absent when this node cannot know: the probe needs the slice's own codec registry, which only a node hosting the slice has, so reporting `false` there would fabricate a value. A deployment still activating produces the same empty candidate set as a slice that is nowhere, so the two are reported differently — "not being consumed YET" (normal, logged at INFO) versus the `unassignedPartitions` gap (logged at ERROR).

**Guarantee.** At-least-once delivery per partition, conditional on the slice being `ACTIVE` on at least one live node. Duplicates arise from redelivery after a handler failure under `RETRY`, from the reconcile-tick window during an ownership or placement change (old and new assignee may both deliver), and from resuming at the last checkpoint (≤1000 events or ≤30s of progress) rather than the last delivered offset after an ungraceful move — a graceful detach flushes the exact cursor. Not effectively-once: there is no fencing token on delivery, and two transiently-divergent assignment views can both deliver and both write the cursor, last write winning.

**Response:**
```json
{
  "attachedSubscriptions": 2,
  "consumers": [
    {
      "stream": "orders",
      "configSection": "streams.orders",
      "artifact": "com.example:order-slice:1.0.0",
      "method": "onOrderPlaced",
      "consumerGroup": "orders-onOrderPlaced",
      "batchMode": false,
      "eventType": "com.example.OrderPlaced",
      "sliceDeployedLocally": true,
      "eventTypePublishable": true,
      "assignedPartitions": [
        {"partition": 0, "committedOffset": 42, "stalled": false},
        {"partition": 2, "committedOffset": 17, "stalled": false}
      ],
      "partitionAssignments": [
        {"partition": 0, "consumerNode": "node-1", "ownerNode": "node-1"},
        {"partition": 1, "consumerNode": "node-3", "ownerNode": "node-2"},
        {"partition": 2, "consumerNode": "node-1", "ownerNode": "node-4"}
      ],
      "diagnostic": "consuming partitions [2] of stream orders whose owner is another node — reads for them are forwarded to the owner"
    }
  ]
}
```

> **Empty fields are omitted.** The serializer drops empty collections and absent optionals, so a healthy
> consumer carries no `unassignedPartitions` key at all rather than `[]`, and `eventTypePublishable` is
> absent (not `false`) on a node that cannot determine it. Check for the field's PRESENCE, not for an
> empty value.

| Field | Description |
|-------|-------------|
| `attachedSubscriptions` | Subscriptions actually attached ON THIS NODE — the number of partitions assigned here, not the stream's partition count |
| `consumers[].stream` | Stream the consumer is declared against |
| `consumers[].configSection` | The `[streams.X]` section in the slice's `resources.toml` |
| `consumers[].artifact` | Artifact declaring the consumer |
| `consumers[].method` | The slice method the runtime invokes |
| `consumers[].consumerGroup` | Derived consumer group owning the durable cursor |
| `consumers[].batchMode` | Whether the method takes `List<T>` (delivered as singleton batches in this release) |
| `consumers[].eventType` | Declared event type |
| `consumers[].sliceDeployedLocally` | Whether the declaring slice is loaded on THIS node |
| `consumers[].eventTypePublishable` | Whether the slice's own codec registry knows the event type (#526). **Absent when this node cannot know** — the probe needs the slice's codec, which only a node hosting the slice has |
| `consumers[].assignedPartitions` | Live subscriptions on this node: `partition`, `committedOffset` (next offset to read — one past the last delivered), `stalled` |
| `consumers[].unassignedPartitions` | **The loud gap:** partitions no node can consume because the slice is `ACTIVE` nowhere. Absent when there is no gap. It is NOT a gap for this node to lack the slice — since #535 the owner need not host it. During a deploy the same emptiness is reported as "not being consumed YET" in `diagnostic` rather than as a gap |
| `consumers[].partitionAssignments` | Full partition→node map: `consumerNode` (who consumes it), `ownerNode` (who owns it). Reads are forwarded whenever they differ. Either is `null` during the bootstrap window; `consumerNode` is also `null` when nothing can consume |
| `consumers[].diagnostic` | Operator-facing explanation of whichever condition applies; empty when the consumer is healthy and reading locally |

An empty `consumers` list means no slice in the cluster declares a `[streams.X]` consumer — the honest answer; rows are never fabricated.

### Create Stream

```
POST /api/v1/streams
```

**Auth:** OPERATOR_AND_ABOVE

Creates a stream with the given name and optional partition count. Idempotent — returns success if stream already exists.

**Request:**
```json
{
  "name": "my-stream",
  "partitions": 4
}
```

**Response:**
```json
{
  "name": "my-stream",
  "partitions": 4,
  "status": "created"
}
```

### Publish Event

Folded into the namespaced route family -- see [Publish](#publish) under
[Stream Namespaces](#stream-namespaces). The flat `POST /api/v1/streams/{name}/publish`
registration has been removed; nothing serves that path any more.

### Read Events

```
GET /api/v1/streams/read/{name}/{partition}?from={offset}&max={count}
```

**Auth:** ALL_AUTHENTICATED

**Query Parameters:**
- `from` (optional, default 0) -- Starting offset
- `max` (optional, default 100) -- Maximum number of events to return
- `readPreference` (optional) -- Replica read preference hint

**CLI:**
```bash
aether streams read <name> <partition> [--since <offset>] [--limit <count>]
```

**Response:**
```json
{
  "events": [
    {
      "offset": 0,
      "data": "<base64-encoded-payload>",
      "timestamp": 1711234567890
    }
  ]
}
```

### Delete Stream

Folded into the namespaced route family -- see
[Delete Stream Version](#delete-stream-version) under [Stream Namespaces](#stream-namespaces).
The flat `DELETE /api/v1/streams/{name}` registration has been removed; nothing serves that path
any more.

### Join Consumer Group

```
POST /api/v1/streams/groups/join
```

**Auth:** OPERATOR_AND_ABOVE

Registers a consumer in a consumer group on the given stream. Idempotent — re-joining the same
`(groupId, consumerId)` pair refreshes the binding.

**Request:**
```json
{
  "groupId": "orders-workers",
  "streamName": "orders",
  "partitionCount": 4,
  "consumerId": "worker-1"
}
```

**CLI:**
```bash
aether streams consumer-group join <group> <stream> --consumer-id <id> [--partitions <N>]
```

**Response:**
```json
{
  "groupId": "orders-workers",
  "streams": {
    "orders": [
      {"consumerId": "worker-1", "partitions": [0, 1, 2, 3]}
    ]
  }
}
```

### Leave Consumer Group

```
POST /api/v1/streams/groups/leave
```

**Auth:** OPERATOR_AND_ABOVE

Removes a consumer from a consumer group on the given stream. Idempotent — leaving an
already-absent consumer is a no-op.

**Request:**
```json
{
  "groupId": "orders-workers",
  "streamName": "orders",
  "consumerId": "worker-1"
}
```

**CLI:**
```bash
aether streams consumer-group leave <group> <stream> --consumer-id <id>
```

**Response:** Same envelope as join — the post-departure `GroupStatusResponse`.

### Consumer Group Status

```
GET /api/v1/streams/groups/{id}
```

**Auth:** ALL_AUTHENTICATED

Returns the per-stream consumer assignments for the given group. The response covers
every stream the group is bound to.

**CLI:**
```bash
aether streams consumer-group status <group> [<stream>]
```

**Response:**
```json
{
  "groupId": "orders-workers",
  "streams": {
    "orders": [
      {"consumerId": "worker-1", "partitions": [0, 1]},
      {"consumerId": "worker-2", "partitions": [2, 3]}
    ]
  }
}
```

---

## Stream Namespaces

Namespaced stream routes address a stream by its fully-qualified `(namespace, stream, version)`
triple. They back the `aether stream` CLI command group and the stream-namespaces registry. The
namespace partitions the global stream key space; `system` is reserved for framework-internal
streams (see the **`system:*` write gate** below).

All routes below are members of the `STREAMING` task group and are forwarded to the owning node.
Path parameters appear in route order as `{namespace}/{stream}/{version}` (version is the
`MAJOR.MINOR.PATCH` schema version).

### List Registered Streams (namespaced)

```
GET /api/v1/streams/list[?namespace={ns}]
```

Snapshot of all registered stream versions across the namespace registry. The optional
`namespace` query parameter filters to a single namespace.

### List Versions

```
GET /api/v1/streams/versions/{namespace}/{stream}
```

Lists all registered versions of a given `(namespace, stream)`.

### Latest Version

```
GET /api/v1/streams/latest/{namespace}/{stream}
```

Resolves the highest registered version for a `(namespace, stream)`.

### Stream Metadata

```
GET /api/v1/streams/metadata/{namespace}/{stream}/{version}
```

Returns the registry entry for an exact stream version (config, partitions, retention, reference
count, `registeredBy`, `registeredAtEpochMs`).

### Read Events (tail polling)

```
GET /api/v1/streams/events/{namespace}/{stream}/{version}?fromOffset={n}&maxEvents={k}
```

Paginated event read used by `aether stream tail`. Returns a page of events plus `nextOffset`
and `hasMore`. `maxEvents` is server-capped (1000). A streaming `tail` subscription
(SSE/WebSocket) over `/api/v1/streams/tail/...` is **deferred to issue #212**; polling `/events` is
the supported tail mechanism today.

### Tail (reserved)

```
GET /api/v1/streams/tail/{namespace}/{stream}/{version}
```

Reserved for the deferred SSE/WebSocket subscription (#212). Operators tail via polling
`/api/v1/streams/events/...` in the interim.

### Publish

```
POST /api/v1/streams/{namespace}/{stream}/{version}/publish
POST /api/v1/streams/{namespace}/{stream}/{version}/publish-batch
```

**Auth:** OPERATOR_AND_ABOVE. Publishes one event (or a batch). **Writes to `system:*` streams
are rejected with `405 Method Not Allowed`** — see below.

### Delete Stream Version

```
DELETE /api/v1/streams/{namespace}/{stream}/{version}
```

**Auth:** OPERATOR_AND_ABOVE. Force-purges a specific stream version. Rejected for `system:*`.
Same path as [Stream Metadata](#stream-metadata) — `GET` reads, `DELETE` purges; no separate verb
segment disambiguates them.

### Consumer Groups (namespaced)

```
GET    /api/v1/streams/groups/{namespace}/{stream}/{version}            # list groups
POST   /api/v1/streams/groups/create/{namespace}/{stream}/{version}     # create a durable group
DELETE /api/v1/streams/groups/delete/{namespace}/{stream}/{version}/{group}   # delete a group
```

`create` and `delete` are **OPERATOR_AND_ABOVE** and are subject to the `system:*` write gate.
Deleting a group releases its reference on the stream version.

### Namespace Registry Lookup

```
GET /api/v1/stream-namespaces/list
GET /api/v1/stream-namespaces/get/{namespace}/{stream}/{version}
```

Read-only views served **locally on every node** (target `LOCAL`) — non-governor nodes answer
these from replicated registry state (see [Stream metadata registries](#stream-metadata-registries)).
`list` returns all registry entries; `get` is an exact lookup returning `404` when the address is
not registered. Each entry carries `namespace`, `stream`, `version`, `registeredBy`,
`registeredAtEpochMs`, and `refCount`.

### `system:*` write gate (405)

Mutating HTTP requests (`POST`/`PUT`/`PATCH`/`DELETE`) that target a stream in the `system`
namespace are rejected with **`405 Method Not Allowed`, regardless of role** — even when
management security is disabled. The check runs ahead of the role/auth pipeline in
`ManagementServer`, so it short-circuits before role evaluation.

Each identity-bearing write route — the catalog-form
`STREAMS_PUBLISH`/`STREAMS_DELETE`/`STREAMS_GROUP_CREATE`/`STREAMS_GROUP_DELETE` —
resolves its target through the same `ManagementRoute` route-match the real dispatch path uses
(never a raw path-segment scan), reduces the match to an engine key, and rejects when that key
names one of `SystemStreams.ALL`. A route match whose params fail to resolve to a valid identity
(e.g. a malformed version) fails closed — denied, not passed through.

`STREAM_CREATE` is excluded from this **pre-auth, path-based** gate: its target name is a JSON
body field (`StreamCreateRequest`), not a path param, so the gate — which resolves identity from
route-match + path params only, by design (no parallel body parser) — structurally cannot see it.
CREATE is protected instead by a **separate, post-auth, handler-level guard**: `StreamRoutes`
rejects a reserved system-stream name unconditionally, as the first statement of the sole method
that ever mints a stream, before any state change. This closes the window `createStreamWithConfig`'s
idempotent create-if-absent behavior does not cover on its own — a create racing ahead of
`SystemStreamBootstrap` registering `SystemStreams.ALL` at cluster startup would otherwise find no
existing stream and mint a caller-controlled config under the reserved name. Being honest about the
mechanism: this protection runs **after** authentication, not before it — auth level does not
change the outcome (an authenticated caller with full privileges naming a framework stream in the
body is rejected the same as anyone else), but it is not the same short-circuit-before-role-check
guarantee the path-based gate above gives the other write routes.

`CONSUMER_GROUP_JOIN`/`CONSUMER_GROUP_LEAVE` carry their target
stream name in the request body rather than the path — a known, currently open gap this path-only
gate cannot see, closed once these routes gain path-resolvable identity via the catalog-form
reshape (management-api-versioning-spec.md §3.3). Tracked as its own ticket (rc4 provisional,
cross-referencing #300), pending an evidence-based answer to whether joining/leaving a consumer
group on a framework stream actually mutates state or is merely untidy.

Reads of `system:*` streams (e.g. `system:cluster-events`) are unaffected; only writes are gated.
The compile-time SPI split already blocks application code from producing into system streams;
this is the HTTP-path guard.

### Stream metadata registries

Stream metadata is held in **two complementary, consensus-replicated registries** (issue #215):

- **`StreamConfigKey`** (`stream-config/{stream}`) — a flat per-stream record of config,
  retention, and partition count. Each node hydrates it locally via `onStreamConfigPut` as the
  consensus log is applied, so the live stream stack can read config without a registry round-trip.
- **`StreamRegistryKey`** (`stream-registry/{namespace}/{stream}/{version}`) — the namespaced
  registry entry carrying the consensus-mediated reference count and registration metadata, read
  directly from the replicated KV store.

Because both registries are replicated through consensus, **non-governor nodes serve stream
metadata (and the `/api/v1/stream-namespaces/*` views) from local replicated state** rather than
forwarding to the governor — the #215 fix that gave every node a durable, consistent view of the
stream registry.

---

All errors return JSON with an `error` field:

```json
{
  "error": "Invalid artifact format"
}
```

Common HTTP status codes:
- `400 Bad Request` -- Invalid request format or missing required fields
- `401 Unauthorized` -- Missing API key (when authentication is configured)
- `403 Forbidden` -- Invalid API key
- `404 Not Found` -- Resource not found
- `500 Internal Server Error` -- Server error

---

## App HTTP Security

The app HTTP server (serving slice-generated endpoints) supports configurable authentication. Three security modes are available:

### Security Modes

| Mode | Header | Description |
|------|--------|-------------|
| `api-key` | `X-API-Key` | Reuses management API key infrastructure (**default** when `security_mode` is omitted — issue #290, "secure by default"; a fresh cluster with no provisioned key auto-generates one ADMIN key on first leadership and prints it once, see [SECURITY.md](../../../SECURITY.md#default-security-posture-management-api)) |
| `jwt` | `Authorization: Bearer <token>` | JWT with JWKS validation (RS256/ES256) |
| `none` | -- | No authentication — must be set explicitly; dev/eval only, never for anything reachable over an untrusted network |

### Health Endpoint Bypass

Health endpoints always bypass authentication regardless of security mode:
- `GET /health/live` -- Liveness probe
- `GET /health/ready` -- Readiness probe

### SecurityContext Propagation

When authentication succeeds, a `SecurityContext` is created and propagated to slice handlers via `SecurityContextHolder`. The context contains:
- **Principal** -- authenticated identity (API key name or JWT subject)
- **Roles** -- assigned roles (`ADMIN`, `SERVICE`, etc.)
- **Claims** -- additional metadata (JWT claims when using JWT mode)
- **AuthorizationRole** -- hierarchical role for RBAC (`ADMIN`, `OPERATOR`, `VIEWER`)

Slice handlers can access the security context to make authorization decisions.

### Configuration

#### Mode: API Key (Default)

Reuses the same `api-keys` configuration as the management API. Requests must include an
`X-API-Key` header. `security_mode` defaults to `api-key` when omitted (issue #290). If no key is
provisioned, the first elected leader generates one random ADMIN key on first startup and prints
it once, prominently, to its log -- capture it, since it is not retrievable afterward except by
rotating it via `/api/v1/cluster/keys`.

```toml
[app-http]
enabled = true
port = 8070
# security_mode omitted -> defaults to "api-key"

# Simple key list (all keys get ADMIN authorization)
api_keys = ["my-secret-key-1", "my-secret-key-2"]
```

Or with rich key configuration including RBAC roles:

```toml
[app-http]
enabled = true
port = 8070
security_mode = "api-key"

[app-http.api-keys.my-admin-key]
name = "admin-service"
roles = ["admin"]
authorization_role = "ADMIN"

[app-http.api-keys.my-viewer-key]
name = "monitoring"
roles = ["service"]
authorization_role = "VIEWER"
```

#### Mode: JWT

Token-based authentication using JWKS (JSON Web Key Set) for public key validation. Supports RS256 and ES256 algorithms using JDK crypto (no external libraries).

```toml
[app-http]
enabled = true
port = 8070
security_mode = "jwt"
jwks_url = "https://auth.example.com/.well-known/jwks.json"
issuer = "https://auth.example.com/"
audience = "my-api"
role_claim = "role"
jwks_cache_ttl_seconds = 3600
```

#### Mode: None

No authentication -- all requests are allowed with a system principal. Must be set explicitly
(`security_mode = "none"`); it is never the default. Appropriate only for a single-node local/dev
instance, never for anything reachable over an untrusted network -- see the
[Bootstrap Config Reference](bootstrap-config.md#a-security_mode--none--why-deveval-bootstrap-needs-it)
for why the dev/eval bootstrap path uses it and what it gives up.

```toml
[app-http]
enabled = true
port = 8070
security_mode = "none"
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `jwks_url` | Yes | -- | JWKS endpoint URL for public key fetching |
| `issuer` | No | _(skip validation)_ | Expected `iss` claim value |
| `audience` | No | _(skip validation)_ | Expected `aud` claim value |
| `role_claim` | No | `"role"` | JWT claim name for role extraction |
| `jwks_cache_ttl_seconds` | No | `3600` | JWKS key cache TTL in seconds |

### Request Size Limits

The app HTTP server enforces a configurable maximum request body size:

```toml
[app-http]
enabled = true
max_request_size = "5MB"
```

The `max_request_size` field accepts human-readable data size values:

| Format | Example | Bytes |
|--------|---------|-------|
| `KB` | `"512KB"` | 524,288 |
| `MB` | `"5MB"` | 5,242,880 |
| `GB` | `"1GB"` | 1,073,741,824 |

Default: `10MB` (10,485,760 bytes). Requests exceeding this limit receive `413 Request Entity Too Large`.

### Multipart File Upload

The app HTTP server supports multipart file uploads via Netty's `HttpPostRequestDecoder`. Multipart requests are subject to the same `max_request_size` limit. Slice-generated routes with file upload parameters automatically handle multipart decoding.
