# Rolling Updates Guide

Zero-downtime deployments with gradual traffic shifting and automatic health validation.

## Overview

Aether's rolling update system uses a **two-stage model**:

1. **Deploy Stage**: New version instances deployed with 0% traffic
2. **Route Stage**: Traffic gradually shifted from old to new version

This approach ensures:
- No downtime during updates
- Instant rollback capability
- Health-based progression gates
- Full control over traffic distribution

## Quick Start

```bash
# Start rolling update (deploys new version with 0% traffic)
aether update start org.example:my-slice 2.0.0

# Shift 25% traffic to new version
aether update routing <update-id> -r 1:3

# Shift 50% traffic
aether update routing <update-id> -r 1:1

# Shift all traffic to new version
aether update routing <update-id> -r 1:0

# Complete update (removes old version)
aether update complete <update-id>
```

## State Machine

```
PENDING → DEPLOYING → DEPLOYED → ROUTING → COMPLETING → COMPLETED
                ↓          ↓          ↓          ↓
             ROLLING_BACK ←─────────←─────────←──┘
                    ↓
               ROLLED_BACK

Any state → FAILED
```

| State | Description |
|-------|-------------|
| `PENDING` | Update requested but not yet started |
| `DEPLOYING` | New version instances being deployed (0% traffic) |
| `DEPLOYED` | New version healthy, ready for traffic routing |
| `ROUTING` | Traffic being shifted according to routing ratio |
| `COMPLETING` | Cleaning up old version instances |
| `COMPLETED` | Update finished, old version removed |
| `ROLLING_BACK` | Reverting to old version |
| `ROLLED_BACK` | Rollback complete, new version removed |
| `FAILED` | Update failed |

## Traffic Routing

Traffic distribution uses a ratio format: `new:old`

| Ratio | New Version | Old Version |
|-------|-------------|-------------|
| `0:1` | 0% | 100% |
| `1:3` | 25% | 75% |
| `1:1` | 50% | 50% |
| `3:1` | 75% | 25% |
| `1:0` | 100% | 0% |

The ratio is applied using weighted round-robin across available instances.

## Health Thresholds

Health thresholds determine when automatic progression is allowed:

```bash
# Start with custom health thresholds
aether update start org.example:my-slice 2.0.0 \
    --max-error-rate 0.01 \
    --max-latency-ms 500
```

| Preset | Error Rate | Latency | Use Case |
|--------|------------|---------|----------|
| Default | 1% | 500ms | General services |
| Strict | 0.1% | 200ms | Critical services |
| Relaxed | 5% | 1000ms | Batch processing |

When health thresholds are exceeded:
- Automatic progression pauses
- Manual approval or rollback required

## Cleanup Policies

| Policy | Behavior |
|--------|----------|
| `IMMEDIATE` | Remove old version immediately after completion |
| `GRACE_PERIOD` | Keep old version for 5 minutes after completion |
| `MANUAL` | Keep old version until manually removed |

```bash
# Start with specific cleanup policy
aether update start org.example:my-slice 2.0.0 --cleanup GRACE_PERIOD
```

## CLI Commands

### Start Update

```bash
aether update start <artifact-base> <version> [options]

Options:
  --instances, -i <n>      Number of new version instances (default: match current)
  --max-error-rate <rate>  Maximum error rate threshold (default: 0.01)
  --max-latency-ms <ms>    Maximum p99 latency threshold (default: 500)
  --manual-approval        Require manual approval for each routing change
  --cleanup <policy>       Cleanup policy: IMMEDIATE, GRACE_PERIOD, MANUAL
```

### Adjust Routing

```bash
aether update routing <update-id> -r <ratio>

# Examples:
aether update routing abc123 -r 1:3   # 25% new
aether update routing abc123 -r 1:1   # 50% new
aether update routing abc123 -r 1:0   # 100% new
```

### Monitor Update

```bash
# Get update status
aether update status <update-id>

# List active updates
aether update list

# View health metrics
aether update health <update-id>
```

### Complete or Rollback

```bash
# Complete update (requires 1:0 routing)
aether update complete <update-id>

# Rollback to old version
aether update rollback <update-id>
```

## REST API

### Start Rolling Update

```bash
curl -X POST http://localhost:8080/rolling-update/start \
  -H "Content-Type: application/json" \
  -d '{
    "artifactBase": "org.example:my-slice",
    "version": "2.0.0",
    "instances": 3,
    "maxErrorRate": 0.01,
    "maxLatencyMs": 500,
    "requireManualApproval": false,
    "cleanupPolicy": "GRACE_PERIOD"
  }'
```

### Adjust Routing

```bash
curl -X POST http://localhost:8080/rolling-update/<id>/routing \
  -H "Content-Type: application/json" \
  -d '{"routing": "1:1"}'
```

### Get Update Status

```bash
curl http://localhost:8080/rolling-update/<id>
```

### List Active Updates

```bash
curl http://localhost:8080/rolling-updates
```

### Get Health Metrics

```bash
curl http://localhost:8080/rolling-update/<id>/health
```

Response:
```json
{
  "updateId": "abc123",
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

### Complete Update

```bash
curl -X POST http://localhost:8080/rolling-update/<id>/complete
```

### Rollback

```bash
curl -X POST http://localhost:8080/rolling-update/<id>/rollback
```

## Best Practices

### Gradual Traffic Shifting

Don't jump from 0% to 100%. Use gradual steps:

```bash
# Recommended progression
aether update routing <id> -r 1:9   # 10% - initial canary
# Monitor for 5-10 minutes
aether update routing <id> -r 1:3   # 25%
# Monitor
aether update routing <id> -r 1:1   # 50%
# Monitor
aether update routing <id> -r 3:1   # 75%
# Monitor
aether update routing <id> -r 1:0   # 100%
# Complete
aether update complete <id>
```

### Health Monitoring

Always check health metrics before progressing:

```bash
aether update health <id>
```

Look for:
- Error rate within threshold
- Latency comparable to old version
- No unusual patterns in request count

### Rollback Strategy

Be prepared to rollback at any time:

```bash
# If issues detected
aether update rollback <id>
```

Rollback is instant - traffic immediately shifts back to old version.

### Critical Services

For critical services, use strict thresholds and manual approval:

```bash
aether update start org.example:payment-service 2.0.0 \
    --max-error-rate 0.001 \
    --max-latency-ms 200 \
    --manual-approval
```

## Troubleshooting

### Update Stuck in DEPLOYING

New version instances aren't becoming healthy:

1. Check instance logs: `aether logs org.example:my-slice:2.0.0`
2. Verify artifact is available in repository
3. Check for startup errors or configuration issues

### Health Validation Failing

Metrics exceed thresholds:

1. Check `aether update health <id>` for specific issues
2. Either fix the issue and wait, or rollback
3. Consider adjusting thresholds if they're too strict

### Rollback Failed

Rare, but if rollback gets stuck:

1. Check cluster quorum: `aether status`
2. Force undeploy new version: `aether undeploy org.example:my-slice:2.0.0`
3. Check KV-Store consistency

## Implementation Details

Rolling updates are coordinated by the leader node via Rabia consensus. All state is stored in the KV-Store:

- `rolling-update/{updateId}` - Update state
- `version-routing/{groupId}:{artifactId}` - Traffic routing configuration

Traffic routing is applied at the `SliceInvoker` level. When selecting an endpoint, the invoker:

1. Checks for active rolling update
2. If active, uses weighted round-robin based on routing ratio
3. Selects endpoint from appropriate version's instances

See [Management API Reference](../api/management-api.md#rolling-updates) for complete API documentation.
