# Deployment Guide

Zero-downtime deployments with multiple strategies, health monitoring, and automatic rollback.

## Overview

Aether provides a unified deployment system through a single `aether deploy` command and `/api/deploy` REST endpoint. Four strategies are available:

| Strategy | Flag | Use Case |
|----------|------|----------|
| Immediate | *(default)* | Fast deployment, no traffic control |
| Canary | `--canary` | Progressive traffic shift with health monitoring |
| Blue-Green | `--blue-green` | Atomic switchover with instant rollback |
| Rolling | `--rolling` | Gradual traffic shifting with fine-grained control |

All strategies share a common lifecycle: **start** -> **promote** -> **complete** (or **rollback** at any point).

## Quick Start

```bash
# Immediate deployment
aether deploy org.example:my-slice:2.0.0

# Canary with 3 instances
aether deploy org.example:my-slice:2.0.0 --canary -n 3

# Blue-green deployment
aether deploy org.example:my-slice:2.0.0 --blue-green -n 3

# Rolling deployment
aether deploy org.example:my-slice:2.0.0 --rolling -n 3
```

## Canary Deployments

Routes a small percentage of traffic to a new version, automatically monitors health, and progressively increases traffic or rolls back based on configurable thresholds.

### Stages

Default progression: 1% -> 5% -> 25% -> 50% -> 100%

Each stage has a minimum observation period before progression is allowed.

### Workflow

```bash
# Start canary
aether deploy org.example:my-service:2.0.0 --canary -n 3

# Check health comparison
aether deploy status <id>

# Promote through stages
aether deploy promote <id>    # 1% -> 5%
aether deploy promote <id>    # 5% -> 25%
aether deploy promote <id>    # 25% -> 50%
aether deploy promote <id>    # 50% -> 100%

# Finalize
aether deploy complete <id>

# Or rollback at any point
aether deploy rollback <id>
```

### Auto-Rollback

If health thresholds are breached during any stage, the canary automatically rolls back to the baseline version.

## Automatic Rollback of a Broken Version

Outside a managed deployment (canary, blue-green, rolling), the leader watches for a version that is
broken on **every** instance and rolls it back to the previous version.

**What counts as broken.** Only failures the slice bridge itself produces: the method threw instead of
returning, the request could not be decoded or the response encoded, or the method does not exist in
this version. A failure the slice method returns (a business error) never counts, however often it
happens. An execution timeout does not count either: a stall is as consistent with overload or a
downstream outage as with a broken version.

**The verdict.** For a version with at least one ACTIVE instance, every hosting node must report at least
3 such defects and no success within a 30-second window, and every hosting node's metrics must be
fresh (a node that stopped reporting makes the version undecidable, never a trigger). Counts come from
the per-node execution counters each node ships on its cluster-sync pong; only the leader decides.
[mechanism: `AllInstancesFailedDetector` over per-window deltas of the pong counters]

**When it rolls back.** Only within the bake window after the version became the target (default 15
minutes). Outside it the same pattern is treated as an incident, not a bad deploy: the `SliceFailure`
cluster event and the slice-failure alert are raised, but nothing is rolled back. It also never rolls
back while a managed deployment owns the artifact, never to a version that already failed once, and it
honours the cooldown (5 minutes) and the rollback budget (2). The rollback record — count, last rollback
and failed versions — is committed together with the new target, so these limits hold across a leader
change.
[verified: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/AutoRollbackOnAllInstancesFailedTest.java`]

**What it does not detect.** A version that fails only on some instances or only for some requests;
business-logic regressions; hangs and deadlocks; exceptions thrown asynchronously inside the slice's
own Promise chain; a version that receives no traffic. A hosting node that dies stops counting as a
host only once membership declares it DEAD; until then — including during the cold-boot convergence
window, when a never-healthy peer is reported UNKNOWN rather than FAULTY — its stale metrics make the
version undecidable and nothing triggers.

**Recovery.** After an automatic rollback the failed version is recorded and is never an automatic
rollback target again. Deploy a fixed version, or roll forward manually. When the rollback budget is
exhausted, automatic rollback stops for that slice; there is no operator surface to reset the budget
yet. The alert is held in the leader's memory; a leader change clears it, while the cluster event stays
in the event stream.

## Blue-Green Deployments

Runs two complete deployment environments simultaneously. Traffic switches atomically between blue (current) and green (new) versions.

### Workflow

```bash
# Deploy green version alongside blue
aether deploy org.example:my-service:2.0.0 --blue-green -n 3

# Verify green is ready
aether deploy status <id>

# Switch traffic atomically (~100ms via single Rabia round)
aether deploy promote <id>

# If issues, instant rollback
aether deploy rollback <id>

# When satisfied, finalize and clean up
aether deploy complete <id>
```

## Rolling Deployments

Deploys new version instances with 0% traffic, then gradually shifts traffic from old to new version using configurable ratios.

### Traffic Routing

Traffic distribution uses a ratio format: `new:old`

| Ratio | New Version | Old Version |
|-------|-------------|-------------|
| `0:1` | 0% | 100% |
| `1:3` | 25% | 75% |
| `1:1` | 50% | 50% |
| `3:1` | 75% | 25% |
| `1:0` | 100% | 0% |

### Workflow

```bash
# Start rolling deployment
aether deploy org.example:my-slice:2.0.0 --rolling -n 3

# Promote through traffic stages
aether deploy promote <id>    # shift to next ratio

# Complete and cleanup
aether deploy complete <id>

# Or rollback
aether deploy rollback <id>
```

## Health Thresholds

All strategies support health-based guardrails:

```bash
aether deploy org.example:my-service:2.0.0 --canary \
    --error-rate 0.01 \
    --latency 500
```

| Preset | Error Rate | Latency | Use Case |
|--------|------------|---------|----------|
| Default | 1% | 500ms | General services |
| Strict | 0.1% | 200ms | Critical services |
| Relaxed | 5% | 1000ms | Batch processing |

When health thresholds are exceeded, automatic progression pauses and manual approval or rollback is required.

## Cleanup Policies

| Policy | Behavior |
|--------|----------|
| `IMMEDIATE` | Remove old version immediately after completion |
| `GRACE_PERIOD` | Keep old version for 5 minutes after completion |
| `MANUAL` | Keep old version until manually removed |

```bash
aether deploy org.example:my-slice:2.0.0 --rolling --cleanup GRACE_PERIOD
```

## REST API

All deployment operations use the unified `/api/deploy` endpoint:

```bash
# Start deployment
curl -X POST http://localhost:8080/api/deploy \
  -H "Content-Type: application/json" \
  -d '{
    "artifactBase": "org.example:my-slice",
    "version": "2.0.0",
    "strategy": "CANARY",
    "instances": 3,
    "maxErrorRate": 0.01,
    "maxLatencyMs": 500
  }'

# List active deployments
curl http://localhost:8080/api/deploy

# Get deployment status
curl http://localhost:8080/api/deploy/<id>

# Get health metrics
curl http://localhost:8080/api/deploy/<id>/health

# Promote
curl -X POST http://localhost:8080/api/deploy/<id>/promote

# Rollback
curl -X POST http://localhost:8080/api/deploy/<id>/rollback

# Complete
curl -X POST http://localhost:8080/api/deploy/<id>/complete
```

## Best Practices

### Gradual Traffic Shifting

Don't jump from 0% to 100%. Use gradual steps and monitor between each:

```bash
aether deploy org.example:my-service:2.0.0 --canary -n 3
# Monitor for 5-10 minutes at each stage
aether deploy promote <id>
# Monitor
aether deploy promote <id>
# Complete when satisfied
aether deploy complete <id>
```

### Critical Services

For critical services, use strict thresholds and manual approval:

```bash
aether deploy org.example:payment-service:2.0.0 --canary \
    --error-rate 0.001 \
    --latency 200 \
    --manual-approval
```

### Rollback Strategy

Be prepared to rollback at any time:

```bash
aether deploy rollback <id>
```

Rollback is instant -- traffic immediately shifts back to the old version.

## Troubleshooting

### Deployment Stuck in DEPLOYING

New version instances aren't becoming healthy:

1. Check instance logs
2. Verify artifact is available in repository
3. Check for startup errors or configuration issues

### Health Validation Failing

Metrics exceed thresholds:

1. Check `aether deploy status <id>` for specific issues
2. Either fix the issue and wait, or rollback
3. Consider adjusting thresholds if they're too strict

### Rollback Failed

Rare, but if rollback gets stuck:

1. Check cluster quorum: `aether status`
2. Check KV-Store consistency

## Implementation Details

Deployments are coordinated by the leader node via Rabia consensus. All state is stored in the KV-Store:

- `deploy/{deploymentId}` -- Deployment state
- `version-routing/{groupId}:{artifactId}` -- Traffic routing configuration

Traffic routing is applied at the `SliceInvoker` level. When selecting an endpoint, the invoker checks for active deployments and uses the appropriate routing strategy (weighted round-robin for rolling/canary, slot-based for blue-green).

See [Management API Reference](../reference/management-api.md#deployments) for complete API documentation.
