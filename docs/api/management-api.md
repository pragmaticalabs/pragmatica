# Aether Management API Reference

This document describes the HTTP Management API for Aether cluster management.

**Base URL**: `http://<node-address>:<management-port>` (default port: 8080)

**Content-Type**: All requests and responses use `application/json` unless noted otherwise.

## Authentication

Currently no authentication is required. TLS can be enabled via `AetherNodeConfig.withTls()`.

---

## Cluster Status

### GET /status

Get overall cluster status.

**Response:**
```json
{
  "node": "node-1",
  "status": "LEADER",
  "quorumSize": 3,
  "connectedNodes": 3,
  "uptime": 123456
}
```

### GET /health

Get node health status.

**Response:**
```json
{
  "status": "healthy",
  "quorum": true,
  "nodeCount": 3,
  "sliceCount": 5
}
```

### GET /nodes

List all connected cluster nodes.

**Response:**
```json
{
  "nodes": [
    {"id": "node-1", "address": "192.168.1.1:5000", "status": "CONNECTED"},
    {"id": "node-2", "address": "192.168.1.2:5000", "status": "CONNECTED"}
  ]
}
```

---

## Slice Management

### GET /slices

List all deployed slices.

**Response:**
```json
{
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "instances": 3,
      "activeInstances": 3,
      "state": "ACTIVE"
    }
  ]
}
```

### GET /slices/status

Get detailed slice status including per-node state.

**Response:**
```json
{
  "slices": [
    {
      "artifact": "org.example:my-slice:1.0.0",
      "nodes": [
        {"nodeId": "node-1", "state": "ACTIVE"},
        {"nodeId": "node-2", "state": "ACTIVE"}
      ]
    }
  ]
}
```

### POST /deploy

Deploy a slice to the cluster.

**Request:**
```json
{
  "artifact": "org.example:my-slice:1.0.0",
  "instances": 3
}
```

**Response:**
```json
{
  "status": "deployed",
  "artifact": "org.example:my-slice:1.0.0",
  "instances": 3
}
```

### POST /scale

Scale a deployed slice.

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

### POST /undeploy

Remove a slice from the cluster.

**Request:**
```json
{
  "artifact": "org.example:my-slice:1.0.0"
}
```

**Response:**
```json
{
  "status": "undeployed",
  "artifact": "org.example:my-slice:1.0.0"
}
```

---

## Metrics

### GET /metrics

Get cluster-wide metrics snapshot.

**Response:**
```json
{
  "load": {
    "node-1": {"cpu.usage": 0.45, "heap.used": 0.60},
    "node-2": {"cpu.usage": 0.52, "heap.used": 0.55}
  },
  "deployment": {
    "totalDeployments": 10,
    "avgDeploymentTimeMs": 1234
  }
}
```

### GET /invocation-metrics

Get per-method invocation metrics.

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

### GET /invocation-metrics/slow

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

### GET /metrics/prometheus

Get Prometheus-format metrics for scraping.

**Content-Type**: `text/plain; version=0.0.4`

---

## Controller Configuration

### GET /controller/config

Get current controller configuration.

**Response:**
```json
{
  "cpuScaleUpThreshold": 0.8,
  "cpuScaleDownThreshold": 0.2,
  "callRateScaleUpThreshold": 1000,
  "evaluationIntervalMs": 1000
}
```

### POST /controller/config

Update controller configuration.

**Request:**
```json
{
  "cpuScaleUpThreshold": 0.75,
  "cpuScaleDownThreshold": 0.15,
  "callRateScaleUpThreshold": 500,
  "evaluationIntervalMs": 2000
}
```

All fields are optional; only provided fields will be updated.

**Response:**
```json
{
  "status": "updated",
  "config": {
    "cpuScaleUpThreshold": 0.75,
    "cpuScaleDownThreshold": 0.15,
    "callRateScaleUpThreshold": 500,
    "evaluationIntervalMs": 2000
  }
}
```

### GET /controller/status

Get controller status.

**Response:**
```json
{
  "enabled": true,
  "evaluationIntervalMs": 1000,
  "config": { ... }
}
```

### POST /controller/evaluate

Trigger immediate controller evaluation.

**Request:** `{}`

**Response:**
```json
{
  "status": "evaluation_triggered"
}
```

---

## Alert Management

### GET /alerts

Get all alerts (active + history).

**Response:**
```json
{
  "active": [
    {
      "metric": "cpu.usage",
      "nodeId": "node-1",
      "value": 0.92,
      "threshold": 0.9,
      "severity": "CRITICAL",
      "triggeredAt": 1704067200000
    }
  ],
  "history": [
    {
      "timestamp": 1704067100000,
      "metric": "cpu.usage",
      "nodeId": "node-1",
      "value": 0.75,
      "severity": "WARNING",
      "status": "RESOLVED"
    }
  ]
}
```

### GET /alerts/active

Get active alerts only.

### GET /alerts/history

Get alert history only.

### POST /alerts/clear

Clear all active alerts.

**Request:** `{}`

**Response:**
```json
{
  "status": "alerts_cleared"
}
```

---

## Threshold Configuration

### GET /thresholds

Get all configured alert thresholds.

**Response:**
```json
{
  "cpu.usage": {"warning": 0.7, "critical": 0.9},
  "heap.usage": {"warning": 0.7, "critical": 0.85}
}
```

### POST /thresholds

Set a threshold.

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

---

## Rolling Updates

### POST /rolling-update/start

Start a new rolling update.

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

### GET /rolling-updates

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
      "routing": "1:3"
    }
  ]
}
```

### GET /rolling-update/{id}

Get rolling update status.

### POST /rolling-update/{id}/routing

Adjust traffic routing.

**Request:**
```json
{
  "routing": "1:1"
}
```

Format: `new:old` (e.g., "1:3" = 25% new, 75% old)

### POST /rolling-update/{id}/approve

Manually approve current routing configuration.

### POST /rolling-update/{id}/complete

Complete the rolling update.

### POST /rolling-update/{id}/rollback

Rollback to old version.

### GET /rolling-update/{id}/health

Get version health metrics.

**Response:**
```json
{
  "updateId": "2bKyJE8yxxxxxxxxxxx",
  "oldVersion": {
    "version": "1.0.0",
    "requestCount": 1000,
    "errorRate": 0.001,
    "avgLatencyMs": 45
  },
  "newVersion": {
    "version": "2.0.0",
    "requestCount": 250,
    "errorRate": 0.002,
    "avgLatencyMs": 50
  },
  "collectedAt": 1704067200000
}
```

---

## Artifact Repository

### PUT /repository/{group}/{artifact}/{version}/{filename}

Upload an artifact to the repository.

**Content-Type**: `application/java-archive`

### GET /repository/{group}/{artifact}/{version}/{filename}

Download an artifact.

### GET /repository/artifacts

List all artifacts.

---

## Error Responses

All errors return JSON with an `error` field:

```json
{
  "error": "Invalid artifact format"
}
```

Common HTTP status codes:
- `400 Bad Request` - Invalid request format
- `404 Not Found` - Resource not found
- `500 Internal Server Error` - Server error
