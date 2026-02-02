# Alerts and Thresholds Guide

Monitor cluster health with configurable alert thresholds that persist across restarts and sync across nodes.

## Overview

Aether's alert system provides:
- **Configurable thresholds** for CPU, memory, and custom metrics
- **Warning and critical severity levels**
- **Cluster-wide persistence** via consensus KV-Store
- **Automatic sync** across all nodes
- **Alert history** for debugging and audit

## Quick Start

```bash
# View current thresholds
aether thresholds list

# Set CPU threshold (warn at 70%, critical at 90%)
aether thresholds set cpu.usage 0.7 0.9

# View active alerts
aether alerts active

# Clear all alerts
aether alerts clear
```

## Threshold Configuration

### Setting Thresholds

Thresholds have two levels:
- **Warning**: Indicates potential issues
- **Critical**: Requires immediate attention

```bash
# CLI: aether thresholds set <metric> <warning> <critical>
aether thresholds set cpu.usage 0.7 0.9
aether thresholds set heap.usage 0.7 0.85
```

Via REST API:

```bash
curl -X POST http://localhost:8080/thresholds \
  -H "Content-Type: application/json" \
  -d '{
    "metric": "cpu.usage",
    "warning": 0.7,
    "critical": 0.9
  }'
```

### Viewing Thresholds

```bash
# CLI
aether thresholds list

# REST API
curl http://localhost:8080/thresholds
```

Response:
```json
{
  "cpu.usage": {"warning": 0.7, "critical": 0.9},
  "heap.usage": {"warning": 0.7, "critical": 0.85}
}
```

### Removing Thresholds

```bash
# CLI
aether thresholds remove cpu.usage

# REST API
curl -X DELETE http://localhost:8080/thresholds/cpu.usage
```

## Default Thresholds

If no thresholds are configured, these defaults apply:

| Metric | Warning | Critical |
|--------|---------|----------|
| `cpu.usage` | 0.7 (70%) | 0.9 (90%) |
| `heap.usage` | 0.7 (70%) | 0.85 (85%) |

Default thresholds are in-memory only until explicitly set via API.

## Alert Management

### Viewing Alerts

```bash
# All alerts (active + history)
aether alerts list

# Active alerts only
aether alerts active

# Alert history
aether alerts history
```

Via REST API:

```bash
# All alerts
curl http://localhost:8080/alerts

# Active only
curl http://localhost:8080/alerts/active

# History only
curl http://localhost:8080/alerts/history
```

### Alert Response Format

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

### Clearing Alerts

```bash
# CLI
aether alerts clear

# REST API
curl -X POST http://localhost:8080/alerts/clear
```

## Cluster-Wide Persistence

Thresholds are stored in the consensus KV-Store:
- **Survive node restarts**: Configuration is persisted
- **Sync across nodes**: Changes propagate automatically
- **Consistent view**: All nodes see the same thresholds

### How It Works

1. When you set a threshold, it's written to KV-Store via consensus
2. All nodes receive the update via KVStoreNotification
3. Each node updates its local cache
4. On restart, thresholds are loaded from KV-Store

### Key Format

Thresholds are stored with key format: `alert-threshold/{metricName}`

```
alert-threshold/cpu.usage
alert-threshold/heap.usage
alert-threshold/custom.metric
```

## Custom Metrics

You can define thresholds for any metric name:

```bash
# Custom application metrics
aether thresholds set order.latency.p99 100 500
aether thresholds set queue.depth 1000 5000
aether thresholds set error.rate 0.01 0.05
```

Custom metrics must be emitted by your application code and collected by the metrics system.

## CLI Reference

### thresholds list

List all configured thresholds.

```bash
aether thresholds list
```

### thresholds set

Set or update a threshold.

```bash
aether thresholds set <metric> <warning> <critical>

# Examples:
aether thresholds set cpu.usage 0.7 0.9
aether thresholds set heap.usage 0.7 0.85
```

### thresholds remove

Remove a threshold.

```bash
aether thresholds remove <metric>
```

### alerts list

List all alerts (active and history).

```bash
aether alerts list
```

### alerts active

List active alerts only.

```bash
aether alerts active
```

### alerts history

List alert history.

```bash
aether alerts history
```

### alerts clear

Clear all active alerts.

```bash
aether alerts clear
```

## REST API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/thresholds` | List all thresholds |
| POST | `/thresholds` | Set a threshold |
| DELETE | `/thresholds/{metric}` | Remove a threshold |
| GET | `/alerts` | Get all alerts |
| GET | `/alerts/active` | Get active alerts |
| GET | `/alerts/history` | Get alert history |
| POST | `/alerts/clear` | Clear active alerts |

See [Management API Reference](../reference/management-api.md) for complete details.

## Integration with Controller

The DecisionTreeController can use alert thresholds for scaling decisions:

```
Alert: cpu.usage CRITICAL (0.92 > 0.9)
  → Controller evaluates scaling rules
  → If CPU high for sustained period, scale up
```

Configure controller thresholds separately from alert thresholds:

```bash
# Alert thresholds (for notification)
aether thresholds set cpu.usage 0.7 0.9

# Controller config (for scaling)
curl -X POST http://localhost:8080/controller/config \
  -d '{"cpuScaleUpThreshold": 0.8, "cpuScaleDownThreshold": 0.2}'
```

## Best Practices

### Setting Appropriate Thresholds

1. **Warning** should trigger investigation, not panic
2. **Critical** should indicate imminent service impact
3. Leave headroom between warning and critical

```bash
# Good: Clear separation
aether thresholds set cpu.usage 0.7 0.9    # 20% gap

# Bad: Too close together
aether thresholds set cpu.usage 0.85 0.9   # 5% gap (alert fatigue)
```

### Monitoring Workflow

1. Set thresholds for key metrics
2. Monitor dashboard or alerts endpoint
3. Investigate warnings before they become critical
4. Clear resolved alerts to reduce noise

### High-Availability

Thresholds are replicated via consensus:
- Safe to set from any node
- Changes visible cluster-wide within seconds
- No single point of failure

## Troubleshooting

### Thresholds Not Persisting

1. Check cluster quorum: `aether status`
2. Verify consensus is working: `aether nodes`
3. Check for errors in logs

### Alerts Not Triggering

1. Verify thresholds are set: `aether thresholds list`
2. Check metrics are being collected: `curl http://localhost:8080/metrics`
3. Verify metric names match exactly

### Too Many Alerts

1. Increase warning threshold to reduce noise
2. Consider if metric is appropriate for alerting
3. Use `aether alerts clear` to reset
