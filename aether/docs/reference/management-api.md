# Aether Management API Reference

This document describes the HTTP Management API for Aether cluster management.

**Base URL**: `http://<node-address>:<management-port>` (default port: 8080)

**Content-Type**: All requests and responses use `application/json` unless noted otherwise.

## Authentication

Currently no authentication is required. TLS can be enabled via `AetherNodeConfig.withTls()`.

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

### GET /api/nodes

List all known cluster node IDs.

**Response:**
```json
{
  "nodes": ["node-1", "node-2", "node-3"]
}
```

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
  "instances": 5
}
```

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

---

## Endpoint Summary

| Method | Path | Section |
|--------|------|---------|
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
| GET | `/api/rolling-updates` | Rolling Updates |
| GET | `/api/rolling-update/{updateId}` | Rolling Updates |
| GET | `/api/rolling-update/{updateId}/health` | Rolling Updates |
| POST | `/api/rolling-update/start` | Rolling Updates |
| POST | `/api/rolling-update/{updateId}/routing` | Rolling Updates |
| POST | `/api/rolling-update/{updateId}/complete` | Rolling Updates |
| POST | `/api/rolling-update/{updateId}/rollback` | Rolling Updates |
| GET | `/repository/info/{group}/{artifact}/{version}` | Artifact Repository |
| GET | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| PUT | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| POST | `/repository/{group}/{artifact}/{version}/{file}` | Artifact Repository |
| GET | `/dashboard` | Dashboard |
| WS | `/ws/dashboard` | WebSocket |
| WS | `/ws/status` | WebSocket |
| WS | `/ws/events` | WebSocket |

| GET | `/api/scheduled-tasks` | Scheduled Tasks |
| GET | `/api/scheduled-tasks/{configSection}` | Scheduled Tasks |

---

## Scheduled Tasks

### GET /api/scheduled-tasks

List all registered scheduled tasks and active timer count.

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
      "registeredBy": "node-1"
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

---

## Error Responses

All errors return JSON with an `error` field:

```json
{
  "error": "Invalid artifact format"
}
```

Common HTTP status codes:
- `400 Bad Request` -- Invalid request format or missing required fields
- `404 Not Found` -- Resource not found
- `500 Internal Server Error` -- Server error
