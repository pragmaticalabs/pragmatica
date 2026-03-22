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
| **OPERATOR** | Operational access | Drain/activate nodes, scaling, schema operations, canary/blue-green/rolling updates, backup, alerts, config overrides, scheduled tasks |
| **VIEWER** | Read-only access | Cluster status, metrics, logs, traces, events, health checks |

Roles are hierarchical: ADMIN has all OPERATOR permissions, and OPERATOR has all VIEWER permissions.

### Permission Mapping

**Read requests** (GET, HEAD, OPTIONS) are accessible to all authenticated roles (VIEWER and above).

**Mutation requests** (POST, PUT, DELETE) follow this mapping:

| Endpoint Category | Minimum Role | Examples |
|-------------------|-------------|----------|
| Blueprint management | ADMIN | `POST /api/blueprint`, `DELETE /api/blueprint/{id}` |
| Node shutdown | ADMIN | `POST /api/node/shutdown/{id}` |
| Backup restore | ADMIN | `POST /api/backup/restore/{id}` |
| Log level changes | ADMIN | `PUT /api/logging/levels` |
| Observability depth | ADMIN | `PUT /api/observability/depth` |
| Blueprint deploy (from artifact) | OPERATOR | `POST /api/blueprint/deploy` |
| Blueprint validate | VIEWER | `POST /api/blueprint/validate` |
| Node drain/activate | OPERATOR | `POST /api/node/drain/{id}`, `POST /api/node/activate/{id}` |
| Scaling | OPERATOR | `POST /api/scale` |
| Schema operations | OPERATOR | `POST /api/schema/*` |
| Deployment strategies | OPERATOR | `POST /api/canary/*`, `POST /api/blue-green/*`, `POST /api/rolling-update/*`, `POST /api/ab-test/*` |
| Backup trigger | OPERATOR | `POST /api/backup` |
| Config overrides | OPERATOR | `PUT /api/config/*` |
| Alert management | OPERATOR | `POST /api/alerts/clear` |
| Scheduled tasks | OPERATOR | `POST /api/scheduled-tasks/*` |
| Controller config | OPERATOR | `PUT /api/controller/*` |
| Threshold config | OPERATOR | `PUT /api/thresholds/*` |
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

## Cluster Status

### GET /api/status

Get overall cluster status including uptime, cluster info, slice count, and metrics summary.

**Response:**
```json
{
  "uptimeSeconds": 123456,
  "cluster": {
    "nodeCount": 3,
    "leaderId": "node-1",
    "nodes": [
      {"id": "node-1", "isLeader": true},
      {"id": "node-2", "isLeader": false}
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
  "isLeader": true,
  "leader": "node-1"
}
```

### GET /api/health

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
    {"name": "quorum", "status": "UP", "detail": "Connected peers: 2"}
  ]
}
```

Components checked:
- **consensus** — Is the node participating in consensus? DOWN during initial cluster formation.
- **routes** — Has the node received its initial route synchronization from the KV-Store?
- **quorum** — Does the node have a quorum (at least 2 nodes total)?

### GET /api/nodes

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

### GET /api/events

Get cluster events from the event aggregator. Returns structured events including topology changes, leader elections, deployments, slice failures, and network events.

**Query Parameters:**
- `since` (optional) -- ISO-8601 timestamp to filter events after a given time (e.g. `2024-01-15T10:30:00Z`).

**Examples:**
```bash
# All events
curl http://localhost:8080/api/events

# Events since a specific time
curl "http://localhost:8080/api/events?since=2024-01-15T10:30:00Z"
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

**Event Types:** `NODE_JOINED`, `NODE_LEFT`, `NODE_FAILED`, `LEADER_ELECTED`, `LEADER_LOST`, `QUORUM_ESTABLISHED`, `QUORUM_LOST`, `DEPLOYMENT_STARTED`, `DEPLOYMENT_COMPLETED`, `DEPLOYMENT_FAILED`, `SLICE_FAILURE`, `CONNECTION_ESTABLISHED`, `CONNECTION_FAILED`

**Severity Levels:** `INFO`, `WARNING`, `CRITICAL`

---

## Slice Management

> **Blueprint-only deployment model:** Slices are deployed and undeployed exclusively through blueprints.
> Individual deploy/undeploy endpoints have been removed to enforce dependency validation.
> Use `POST /api/blueprint` to deploy slices and `DELETE /api/blueprint/{id}` to undeploy them.

### GET /api/slices

List all deployed slice artifact identifiers.

**Response:**
```json
{
  "slices": [
    "org.example:my-slice:1.0.0",
    "org.example:other-slice:2.0.0"
  ]
}
```

### GET /api/slices/status

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

### GET /api/routes

List all registered HTTP routes from deployed slices.

**Response:**
```json
{
  "routes": [
    {
      "method": "GET",
      "path": "/orders",
      "nodes": ["node-1", "node-2"]
    }
  ]
}
```

### POST /api/scale

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

### POST /api/blueprint

Publish (apply) a blueprint definition. The request body is the raw blueprint YAML/JSON string.

**Request:** Raw blueprint content as request body (string).

**Response:**
```json
{
  "status": "applied",
  "blueprint": "my-blueprint",
  "slices": 3
}
```

### GET /api/blueprints

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

### GET /api/blueprint/{id}

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

### GET /api/blueprint/{id}/status

Get deployment status of a blueprint and each of its slices.

**Response:**
```json
{
  "id": "my-blueprint",
  "overallStatus": "DEPLOYED",
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "targetInstances": 3,
      "activeInstances": 3,
      "status": "DEPLOYED"
    }
  ]
}
```

Status values: `PENDING`, `DEPLOYING`, `DEPLOYED`, `SCALING_DOWN`. Overall: `DEPLOYED`, `PENDING`, `IN_PROGRESS`, `PARTIAL`.

### DELETE /api/blueprint/{id}

Delete a published blueprint.

**Response:**
```json
{
  "status": "deleted",
  "id": "my-blueprint"
}
```

### POST /api/blueprint/deploy

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
  "status": "deployed",
  "blueprintId": "org.example:my-app:1.0.0",
  "sliceCount": 5
}
```

### POST /api/blueprint/validate

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

---

## Metrics

### GET /api/metrics

Get cluster-wide metrics including per-node load and deployment metrics.

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

### GET /api/metrics/comprehensive

Get comprehensive minute-aggregated metrics for the most recent minute.

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
  "sampleCount": 60
}
```

### GET /api/metrics/derived

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

### GET /api/metrics/prometheus

Get Prometheus-format metrics for scraping.

**Content-Type**: `text/plain; version=0.0.4; charset=utf-8`

### GET /api/metrics/history

Get historical metrics for nodes over a time range.

**Query Parameters:**
- `range` (optional) -- Time range. Values: `5m`, `15m`, `1h` (default), `2h`.

**Example:**
```bash
curl "http://localhost:8080/api/metrics/history?range=15m"
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

### GET /api/node-metrics

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

### GET /api/artifact-metrics

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

### GET /api/invocation-metrics

Get per-method invocation metrics.

**Query Parameters:**
- `artifact` (optional) -- Filter by artifact (partial match)
- `method` (optional) -- Filter by method name (exact match)

**Examples:**
```bash
# All metrics
curl http://localhost:8080/api/invocation-metrics

# Filter by artifact
curl "http://localhost:8080/api/invocation-metrics?artifact=order-service"

# Filter by method
curl "http://localhost:8080/api/invocation-metrics?method=processOrder"
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

### GET /api/invocation-metrics/slow

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

### GET /api/invocation-metrics/strategy

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

### POST /api/invocation-metrics/strategy

Strategy changes are not currently supported. This endpoint always returns an error.

**Response:**
```json
{"error": "Strategy change via API is not supported"}
```

---

## Controller Configuration

### GET /api/controller/config

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

### POST /api/controller/config

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

### GET /api/controller/status

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

### POST /api/controller/evaluate

Trigger immediate controller evaluation.

**Response:**
```json
{
  "status": "evaluation_triggered"
}
```

---

## TTM (Time-series Trend Model)

### GET /api/ttm/status

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

### GET /api/ttm/training-data

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

### GET /api/alerts

Get all alerts (active + history combined).

**Response:**
```json
{
  "active": [...],
  "history": [...]
}
```

### GET /api/alerts/active

Get active alerts only.

### GET /api/alerts/history

Get alert history only.

### POST /api/alerts/clear

Clear all active alerts.

**Response:**
```json
{
  "status": "alerts_cleared"
}
```

---

## Threshold Configuration

### GET /api/thresholds

Get all configured alert thresholds.

**Response:**
```json
{
  "cpu.usage": {"warning": 0.7, "critical": 0.9},
  "heap.usage": {"warning": 0.7, "critical": 0.85}
}
```

### POST /api/thresholds

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

### DELETE /api/thresholds/{metric}

Remove an alert threshold. The removal is persisted to the KV-Store and replicated across all cluster nodes.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/thresholds/cpu.usage
```

**Response:**
```json
{
  "status": "threshold_removed",
  "metric": "cpu.usage"
}
```

---

## Dynamic Aspects

### GET /api/aspects

Get all configured dynamic aspects.

**Response:**
```json
{
  "org.example:my-slice:1.0.0/processOrder": "LOG_AND_METRICS",
  "org.example:my-slice:1.0.0/getStatus": "METRICS"
}
```

### POST /api/aspects

Set aspect mode on a method.

**Request:**
```json
{
  "artifact": "org.example:my-slice:1.0.0",
  "method": "processOrder",
  "mode": "LOG_AND_METRICS"
}
```

Available modes: `NONE`, `LOG`, `METRICS`, `LOG_AND_METRICS`

**Response:**
```json
{
  "status": "aspect_set",
  "artifact": "org.example:my-slice:1.0.0",
  "method": "processOrder",
  "mode": "LOG_AND_METRICS"
}
```

### DELETE /api/aspects/{artifact}/{method}

Remove aspect configuration for a method.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/aspects/org.example:my-slice:1.0.0/processOrder
```

**Response:**
```json
{
  "status": "aspect_removed",
  "artifact": "org.example:my-slice:1.0.0",
  "method": "processOrder"
}
```

---

## Traces

### GET /api/traces

List recent invocation traces.

**Query Parameters:**
- `limit` (int, default 100) -- Maximum traces to return
- `method` (string) -- Filter by callee method name
- `status` (string) -- Filter by outcome: `SUCCESS` or `FAILURE`
- `minDepth` (int) -- Minimum depth filter
- `maxDepth` (int) -- Maximum depth filter

**Example:**
```bash
curl "http://localhost:8080/api/traces?limit=50&method=processOrder"
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

### GET /api/traces/{requestId}

Get all trace nodes for a specific request ID.

**Example:**
```bash
curl http://localhost:8080/api/traces/abc-123
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

### GET /api/traces/stats

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

---

## Observability Depth

### GET /api/observability/depth

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

### POST /api/observability/depth

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

### DELETE /api/observability/depth/{artifact}/{method}

Remove a per-method depth override.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/observability/depth/org.example:my-slice:1.0.0/processOrder
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

## Log Level Management

Runtime log level control with cluster-wide persistence via KV-Store consensus.

### GET /api/logging/levels

Get all runtime-configured log level overrides.

**Response:**
```json
{
  "org.pragmatica.aether.node": "DEBUG",
  "org.pragmatica.consensus": "WARN"
}
```

### POST /api/logging/levels

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

### DELETE /api/logging/levels/{logger}

Reset a logger to its configuration default. The removal is persisted to the KV-Store and replicated across all cluster nodes.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/logging/levels/org.pragmatica.aether.node
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

### GET /api/config

Get all configuration values (base + overrides merged).

**Response:**
```json
{
  "database.host": "localhost",
  "database.port": "5432",
  "server.port": "8080"
}
```

### GET /api/config/overrides

Get only dynamic overrides from the KV store.

**Response:**
```json
{
  "database.port": "5433"
}
```

### POST /api/config

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

### DELETE /api/config/{key}

Remove a cluster-wide configuration override. The base value from TOML/env/system properties is restored.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/config/database.port
```

**Response:**
```json
{
  "status": "config_removed",
  "key": "database.port"
}
```

### DELETE /api/config/node/{nodeId}/{key}

Remove a node-specific configuration override.

**Example:**
```bash
curl -X DELETE http://localhost:8080/api/config/node/node-2/server.port
```

**Response:**
```json
{
  "status": "config_removed",
  "key": "server.port"
}
```

---

## Rolling Updates

All rolling update mutation endpoints (start, routing, complete, rollback) require the requesting node to be the cluster leader.

### GET /api/rolling-updates

List all active rolling updates.

**Response:**
```json
{
  "updates": [
    {
      "updateId": "2bKyJE8yxxxxxxxxxxx",
      "artifactBase": "org.example:my-slice",
      "oldVersion": "1.0.0",
      "newVersion": "2.0.0",
      "state": "ROUTING",
      "routing": "1:3",
      "newInstances": 3,
      "createdAt": 1704067200000,
      "updatedAt": 1704067200000
    }
  ]
}
```

### GET /api/rolling-update/{updateId}

Get a single rolling update by ID. Use `current` as the ID to resolve to the first active update.

**Response:**
```json
{
  "updateId": "2bKyJE8yxxxxxxxxxxx",
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

### GET /api/rolling-update/{updateId}/health

Get version health metrics for a rolling update.

**Response:**
```json
{
  "updateId": "2bKyJE8yxxxxxxxxxxx",
  "oldVersion": {
    "version": "1.0.0",
    "requestCount": 1000,
    "errorRate": 0.001,
    "avgLatencyMs": 45.0
  },
  "newVersion": {
    "version": "2.0.0",
    "requestCount": 250,
    "errorRate": 0.002,
    "avgLatencyMs": 50.0
  },
  "collectedAt": 1704067200000
}
```

### POST /api/rolling-update/start

Start a new rolling update. Requires leader node.

**Request:**
```json
{
  "artifactBase": "org.example:my-slice",
  "version": "2.0.0",
  "instances": 3,
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "requireManualApproval": false,
  "cleanupPolicy": "GRACE_PERIOD"
}
```

All fields except `artifactBase` and `version` are optional. Defaults: `instances=1`, `maxErrorRate=0.01`, `maxLatencyMs=500`, `requireManualApproval=false`, `cleanupPolicy=GRACE_PERIOD`.

**Response:**
```json
{
  "updateId": "2bKyJE8yxxxxxxxxxxx",
  "artifactBase": "org.example:my-slice",
  "oldVersion": "1.0.0",
  "newVersion": "2.0.0",
  "state": "DEPLOYING",
  "routing": "0:1",
  "newInstances": 3,
  "createdAt": 1704067200000,
  "updatedAt": 1704067200000
}
```

### POST /api/rolling-update/{updateId}/routing

Adjust traffic routing for a rolling update. Requires leader node.

**Request:**
```json
{
  "routing": "1:1"
}
```

Format: `new:old` (e.g., `"1:3"` = 25% new, 75% old; `"1:0"` = 100% new).

**Response:** Same as `GET /api/rolling-update/{updateId}`.

### POST /api/rolling-update/{updateId}/complete

Complete the rolling update (finalize new version, decommission old). Requires leader node.

**Response:** Same as `GET /api/rolling-update/{updateId}`.

### POST /api/rolling-update/{updateId}/rollback

Rollback to old version. Requires leader node.

**Response:** Same as `GET /api/rolling-update/{updateId}`.

---

## Cluster Topology

### GET /api/cluster/topology

Get cluster topology summary with core/worker node counts.

**Response:**
```json
{
  "coreCount": 5,
  "coreMax": 5,
  "coreMin": 5,
  "workerCount": 7,
  "clusterSize": 12,
  "coreNodes": ["node-1", "node-2", "node-3", "node-4", "node-5"]
}
```

### GET /api/cluster/governors

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

## Topology

### GET /api/topology

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
      "id": "topic-pub:org.example:url-shortener:1.0.0:click-events",
      "type": "TOPIC_PUB",
      "label": "click-events",
      "sliceArtifact": "org.example:url-shortener:1.0.0"
    },
    {
      "id": "topic-sub:org.example:analytics:1.0.0:click-events",
      "type": "TOPIC_SUB",
      "label": "click-events",
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
      "from": "topic-pub:org.example:url-shortener:1.0.0:click-events",
      "to": "topic-sub:org.example:analytics:1.0.0:click-events",
      "style": "DOTTED",
      "topicConfig": "click-events"
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
- Topic publishers: `topic-pub:{artifact}:{config}` (per-slice)
- Topic subscribers: `topic-sub:{artifact}:{config}` (per-slice)

**Cross-slice matching:** Publishers and subscribers with the same config suffix are connected many-to-many via DOTTED edges. The `topicConfig` field on these edges contains the matching config name.

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

### PUT /repository/{groupPath}/{artifactId}/{version}/{filename}

Upload an artifact file to the repository. Maximum upload size: 64 MB.

**Content-Type**: Binary content (e.g., `application/java-archive`).

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

### GET /api/workers

List all known worker nodes across all worker groups.

**Response:**
```json
[
  {
    "nodeId": "worker-1",
    "groupId": "default"
  },
  {
    "nodeId": "worker-2",
    "groupId": "default"
  }
]
```

### GET /api/workers/health

Get worker pool health summary.

**Response:**
```json
{
  "totalWorkers": 5,
  "totalGroups": 1,
  "isEmpty": false
}
```

### GET /api/workers/endpoints

List all worker-hosted slice endpoints across all groups.

**Response:**
```json
[
  {
    "artifact": "org.example:my-slice:1.0.0",
    "methodName": "processOrder",
    "workerNodeId": "worker-1",
    "instanceNumber": 0
  }
]
```

---

## Canary Deployments

All canary mutation endpoints require the requesting node to be the cluster leader.

### GET /api/canaries

List all active canary deployments.

**Response:**
```json
{
  "canaries": [
    {
      "canaryId": "abc123",
      "artifactBase": "org.example:my-service",
      "baselineVersion": "1.0.0",
      "canaryVersion": "2.0.0",
      "state": "EVALUATING",
      "currentStage": 2,
      "trafficPercent": 5,
      "instances": 3,
      "createdAt": 1704067200000
    }
  ]
}
```

### GET /api/canary/{canaryId}

Get canary deployment status.

**Response:**
```json
{
  "canaryId": "abc123",
  "artifactBase": "org.example:my-service",
  "baselineVersion": "1.0.0",
  "canaryVersion": "2.0.0",
  "state": "EVALUATING",
  "currentStage": 2,
  "trafficPercent": 5,
  "stages": [1, 5, 25, 50, 100],
  "instances": 3,
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "createdAt": 1704067200000,
  "updatedAt": 1704067230000
}
```

### GET /api/canary/{canaryId}/health

Get health comparison between baseline and canary versions.

**Response:**
```json
{
  "canaryId": "abc123",
  "baseline": {
    "version": "1.0.0",
    "requestCount": 9500,
    "errorRate": 0.001,
    "avgLatencyMs": 45.0
  },
  "canary": {
    "version": "2.0.0",
    "requestCount": 500,
    "errorRate": 0.002,
    "avgLatencyMs": 48.0
  },
  "evaluation": "PASS",
  "collectedAt": 1704067230000
}
```

### POST /api/canary/start

Start a new canary deployment. Requires leader node.

**Request:**
```json
{
  "artifactBase": "org.example:my-service",
  "version": "2.0.0",
  "instances": 3,
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "cleanupPolicy": "GRACE_PERIOD"
}
```

All fields except `artifactBase` and `version` are optional. Defaults: `instances=1`, `maxErrorRate=0.01`, `maxLatencyMs=500`, `cleanupPolicy=GRACE_PERIOD`.

**Response:** Same as `GET /api/canary/{canaryId}`.

### POST /api/canary/{canaryId}/promote

Promote the canary to the next traffic stage (e.g., 1% to 5%). Requires leader node.

**Response:** Same as `GET /api/canary/{canaryId}`.

### POST /api/canary/{canaryId}/promote-full

Promote the canary directly to 100% traffic. Requires leader node.

**Response:** Same as `GET /api/canary/{canaryId}`.

### POST /api/canary/{canaryId}/rollback

Rollback the canary deployment to the baseline version. Requires leader node.

**Response:** Same as `GET /api/canary/{canaryId}`.

---

## Blue-Green Deployments

All blue-green mutation endpoints require the requesting node to be the cluster leader.

### GET /api/blue-green-deployments

List all active blue-green deployments.

**Response:**
```json
{
  "deployments": [
    {
      "deploymentId": "bg-xyz",
      "artifactBase": "org.example:my-service",
      "blueVersion": "1.0.0",
      "greenVersion": "2.0.0",
      "activeSlot": "BLUE",
      "state": "GREEN_READY",
      "createdAt": 1704067200000
    }
  ]
}
```

### GET /api/blue-green/{deploymentId}

Get blue-green deployment status.

**Response:**
```json
{
  "deploymentId": "bg-xyz",
  "artifactBase": "org.example:my-service",
  "blueVersion": "1.0.0",
  "greenVersion": "2.0.0",
  "activeSlot": "BLUE",
  "state": "GREEN_READY",
  "instances": 3,
  "createdAt": 1704067200000,
  "updatedAt": 1704067230000
}
```

### POST /api/blue-green/deploy

Deploy the green version alongside the current blue. Requires leader node.

**Request:**
```json
{
  "artifactBase": "org.example:my-service",
  "version": "2.0.0",
  "instances": 3
}
```

**Response:** Same as `GET /api/blue-green/{deploymentId}`.

### POST /api/blue-green/{deploymentId}/switch

Switch all traffic from blue to green. Atomic switchover via single Rabia round (~100ms). Requires leader node.

**Response:** Same as `GET /api/blue-green/{deploymentId}`.

### POST /api/blue-green/{deploymentId}/switch-back

Switch traffic back from green to blue (instant rollback). Requires leader node.

**Response:** Same as `GET /api/blue-green/{deploymentId}`.

### POST /api/blue-green/{deploymentId}/complete

Complete the deployment: drain the inactive slot and clean up resources. Requires leader node.

**Response:** Same as `GET /api/blue-green/{deploymentId}`.

---

## A/B Testing

All A/B test mutation endpoints require the requesting node to be the cluster leader.

### GET /api/ab-tests

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

### GET /api/ab-test/{testId}

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

### GET /api/ab-test/{testId}/metrics

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

### POST /api/ab-test/create

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

**Response:** Same as `GET /api/ab-test/{testId}`.

### POST /api/ab-test/{testId}/conclude

Conclude the A/B test and promote the winning variant. Requires leader node.

**Request:**
```json
{
  "winner": "experiment"
}
```

**Response:** Same as `GET /api/ab-test/{testId}`.

---

## Endpoint Summary

| Method | Path | Section |
|--------|------|---------|
| GET | `/health/live` | Health Probes |
| GET | `/health/ready` | Health Probes |
| GET | `/api/status` | Cluster Status |
| GET | `/api/health` | Cluster Status |
| GET | `/api/nodes` | Cluster Status |
| GET | `/api/events` | Cluster Status |
| GET | `/api/slices` | Slice Management |
| GET | `/api/slices/status` | Slice Management |
| GET | `/api/routes` | Slice Management |
| POST | `/api/scale` | Slice Management |
| POST | `/api/blueprint` | Blueprint Management |
| GET | `/api/blueprints` | Blueprint Management |
| GET | `/api/blueprint/{id}` | Blueprint Management |
| GET | `/api/blueprint/{id}/status` | Blueprint Management |
| DELETE | `/api/blueprint/{id}` | Blueprint Management |
| POST | `/api/blueprint/deploy` | Blueprint Management |
| POST | `/api/blueprint/validate` | Blueprint Management |
| GET | `/api/metrics` | Metrics |
| GET | `/api/metrics/comprehensive` | Metrics |
| GET | `/api/metrics/derived` | Metrics |
| GET | `/api/metrics/prometheus` | Metrics |
| GET | `/api/metrics/history` | Metrics |
| GET | `/api/node-metrics` | Metrics |
| GET | `/api/artifact-metrics` | Metrics |
| GET | `/api/invocation-metrics` | Metrics |
| GET | `/api/invocation-metrics/slow` | Metrics |
| GET | `/api/invocation-metrics/strategy` | Metrics |
| POST | `/api/invocation-metrics/strategy` | Metrics |
| GET | `/api/controller/config` | Controller |
| POST | `/api/controller/config` | Controller |
| GET | `/api/controller/status` | Controller |
| POST | `/api/controller/evaluate` | Controller |
| GET | `/api/ttm/status` | TTM |
| GET | `/api/ttm/training-data` | TTM |
| GET | `/api/alerts` | Alert Management |
| GET | `/api/alerts/active` | Alert Management |
| GET | `/api/alerts/history` | Alert Management |
| POST | `/api/alerts/clear` | Alert Management |
| GET | `/api/thresholds` | Threshold Configuration |
| POST | `/api/thresholds` | Threshold Configuration |
| DELETE | `/api/thresholds/{metric}` | Threshold Configuration |
| GET | `/api/aspects` | Dynamic Aspects |
| POST | `/api/aspects` | Dynamic Aspects |
| DELETE | `/api/aspects/{artifact}/{method}` | Dynamic Aspects |
| GET | `/api/traces` | Traces |
| GET | `/api/traces/{requestId}` | Traces |
| GET | `/api/traces/stats` | Traces |
| GET | `/api/observability/depth` | Observability Depth |
| POST | `/api/observability/depth` | Observability Depth |
| DELETE | `/api/observability/depth/{artifact}/{method}` | Observability Depth |
| GET | `/api/logging/levels` | Log Level Management |
| POST | `/api/logging/levels` | Log Level Management |
| DELETE | `/api/logging/levels/{logger}` | Log Level Management |
| GET | `/api/config` | Dynamic Configuration |
| GET | `/api/config/overrides` | Dynamic Configuration |
| POST | `/api/config` | Dynamic Configuration |
| DELETE | `/api/config/{key}` | Dynamic Configuration |
| DELETE | `/api/config/node/{nodeId}/{key}` | Dynamic Configuration |
| GET | `/api/canaries` | Canary Deployments |
| GET | `/api/canary/{canaryId}` | Canary Deployments |
| GET | `/api/canary/{canaryId}/health` | Canary Deployments |
| POST | `/api/canary/start` | Canary Deployments |
| POST | `/api/canary/{canaryId}/promote` | Canary Deployments |
| POST | `/api/canary/{canaryId}/promote-full` | Canary Deployments |
| POST | `/api/canary/{canaryId}/rollback` | Canary Deployments |
| GET | `/api/blue-green-deployments` | Blue-Green Deployments |
| GET | `/api/blue-green/{deploymentId}` | Blue-Green Deployments |
| POST | `/api/blue-green/deploy` | Blue-Green Deployments |
| POST | `/api/blue-green/{deploymentId}/switch` | Blue-Green Deployments |
| POST | `/api/blue-green/{deploymentId}/switch-back` | Blue-Green Deployments |
| POST | `/api/blue-green/{deploymentId}/complete` | Blue-Green Deployments |
| GET | `/api/ab-tests` | A/B Testing |
| GET | `/api/ab-test/{testId}` | A/B Testing |
| GET | `/api/ab-test/{testId}/metrics` | A/B Testing |
| POST | `/api/ab-test/create` | A/B Testing |
| POST | `/api/ab-test/{testId}/conclude` | A/B Testing |
| GET | `/api/rolling-updates` | Rolling Updates |
| GET | `/api/rolling-update/{updateId}` | Rolling Updates |
| GET | `/api/rolling-update/{updateId}/health` | Rolling Updates |
| POST | `/api/rolling-update/start` | Rolling Updates |
| POST | `/api/rolling-update/{updateId}/routing` | Rolling Updates |
| POST | `/api/rolling-update/{updateId}/complete` | Rolling Updates |
| POST | `/api/rolling-update/{updateId}/rollback` | Rolling Updates |
| GET | `/api/topology` | Topology |
| GET | `/repository/info/{group}/{artifact}/{version}` | Artifact Repository |
| GET | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| PUT | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| POST | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| GET | `/dashboard` | Dashboard |
| WS | `/ws/dashboard` | WebSocket |
| WS | `/ws/status` | WebSocket |
| WS | `/ws/events` | WebSocket |

| GET | `/api/nodes/lifecycle` | Node Lifecycle |
| GET | `/api/node/lifecycle/{nodeId}` | Node Lifecycle |
| POST | `/api/node/drain/{nodeId}` | Node Lifecycle |
| POST | `/api/node/activate/{nodeId}` | Node Lifecycle |
| POST | `/api/node/shutdown/{nodeId}` | Node Lifecycle |
| GET | `/api/scheduled-tasks` | Scheduled Tasks |
| GET | `/api/scheduled-tasks/{configSection}` | Scheduled Tasks |
| POST | `/api/scheduled-tasks/{configSection}/{artifact}/{method}/pause` | Scheduled Tasks |
| POST | `/api/scheduled-tasks/{configSection}/{artifact}/{method}/resume` | Scheduled Tasks |
| POST | `/api/scheduled-tasks/{configSection}/{artifact}/{method}/trigger` | Scheduled Tasks |
| GET | `/api/scheduled-tasks/{configSection}/{artifact}/{method}/state` | Scheduled Tasks |
| GET | `/api/workers` | Worker Pools |
| GET | `/api/workers/health` | Worker Pools |
| GET | `/api/workers/endpoints` | Worker Pools |

---

## Node Lifecycle

Manage node lifecycle states for graceful operations (drain, shutdown, activation).

**States:** `JOINING`, `ON_DUTY`, `DRAINING`, `DECOMMISSIONED`, `SHUTTING_DOWN`

**State transitions:**
```
JOINING → ON_DUTY ←→ DRAINING → DECOMMISSIONED → SHUTTING_DOWN
                   ←────────────┘
         any KV state ──────────→ SHUTTING_DOWN
```

### GET /api/nodes/lifecycle

Get lifecycle state for all nodes.

**Response:**
```json
[
  {
    "nodeId": "node-1",
    "state": "ON_DUTY",
    "updatedAt": 1704067200000
  },
  {
    "nodeId": "node-2",
    "state": "DRAINING",
    "updatedAt": 1704067201000
  }
]
```

### GET /api/node/lifecycle/{nodeId}

Get lifecycle state for a specific node.

**Response:**
```json
{
  "nodeId": "node-1",
  "state": "ON_DUTY",
  "updatedAt": 1704067200000
}
```

### POST /api/node/drain/{nodeId}

Transition a node from `ON_DUTY` to `DRAINING`. The CDM will evacuate slices respecting the disruption budget.

**Response:**
```json
{
  "success": true,
  "nodeId": "node-1",
  "state": "DRAINING",
  "message": "Node draining initiated"
}
```

### POST /api/node/activate/{nodeId}

Transition a node from `DRAINING` or `DECOMMISSIONED` back to `ON_DUTY`.

**Response:**
```json
{
  "success": true,
  "nodeId": "node-1",
  "state": "ON_DUTY",
  "message": "Node activated"
}
```

### POST /api/node/shutdown/{nodeId}

Transition a node from any state to `SHUTTING_DOWN`.

**Response:**
```json
{
  "success": true,
  "nodeId": "node-1",
  "state": "SHUTTING_DOWN",
  "message": "Node shutdown initiated"
}
```

---

## Scheduled Tasks

### GET /api/scheduled-tasks

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

### GET /api/scheduled-tasks/{configSection}

Get scheduled tasks filtered by config section.

**Response:**
```json
{
  "tasks": [...],
  "configSection": "scheduling.cleanup"
}
```

### POST /api/scheduled-tasks/{configSection}/{artifact}/{method}/pause

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

### POST /api/scheduled-tasks/{configSection}/{artifact}/{method}/resume

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

### POST /api/scheduled-tasks/{configSection}/{artifact}/{method}/trigger

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

### GET /api/scheduled-tasks/{configSection}/{artifact}/{method}/state

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

---

## Backup Management

### POST /api/backup

Trigger a manual backup of the KV-Store state.

**Response:**
```json
{
  "success": true,
  "message": "Backup completed"
}
```

### GET /api/backups

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

### POST /api/backup/restore

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

## Schema Management

Manage datasource schema migrations across the cluster.

### GET /api/schema/status

Returns schema migration status for all datasources.

**Response:**
```json
{
  "datasources": [
    {
      "datasource": "orders_db",
      "currentVersion": 3,
      "lastMigration": "V003__add_index.sql",
      "status": "COMPLETED"
    }
  ]
}
```

### GET /api/schema/status/{datasource}

Returns schema status for a specific datasource.

**Response:**
```json
{
  "datasource": "orders_db",
  "currentVersion": 3,
  "lastMigration": "V003__add_index.sql",
  "status": "COMPLETED"
}
```

### GET /api/schema/history/{datasource}

Returns migration history for a datasource (placeholder -- currently returns current status).

### POST /api/schema/migrate/{datasource}

Triggers manual schema migration for a datasource. Sets status to `MIGRATING`.

**Response:**
```json
{
  "success": true,
  "message": "Migration triggered for orders_db"
}
```

### POST /api/schema/undo/{datasource}?targetVersion=N

Undoes migrations to the specified target version. Sets status to `PENDING` at the target version.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `targetVersion` | int | yes | Version to undo to |

**Response:**
```json
{
  "success": true,
  "message": "Undo to version 2 initiated for orders_db"
}
```

### POST /api/schema/retry/{datasource}

Retries a failed schema migration by resetting status from FAILED to PENDING. Only works when the datasource is in FAILED state; returns 409 Conflict otherwise.

**Response:**
```json
{
  "success": true,
  "message": "Retry initiated for orders_db"
}
```

### POST /api/schema/baseline/{datasource}?version=N

Baselines a datasource at the specified version (marks V001..V{N} as applied without executing).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `version` | int | yes | Version to baseline at |

**Response:**
```json
{
  "success": true,
  "message": "Baselined orders_db at version 3"
}
```

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
