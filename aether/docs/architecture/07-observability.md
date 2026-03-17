# Observability

This document describes the metrics pipeline, alerting, dynamic aspects, and monitoring interfaces.

## Metrics Architecture

```mermaid
graph TB
    subgraph Nodes["All Nodes"]
        MC1["MetricsCollector<br/>(Node 1)"]
        MC2["MetricsCollector<br/>(Node 2)"]
        MC3["MetricsCollector<br/>(Node 3)"]
    end

    subgraph Leader["Leader Node"]
        MAG["MetricsAggregator"]
        CL["ControlLoop"]
        AM["AlertManager"]
    end

    subgraph Outputs["Output Channels"]
        Prom["/metrics/prometheus"]
        WS["/ws/dashboard<br/>(WebSocket)"]
        REST["/api/metrics"]
    end

    MC1 -->|"MetricsPong<br/>(every 1s)"| MAG
    MC2 -->|"MetricsPong<br/>(every 1s)"| MAG
    MC3 -->|"MetricsPong<br/>(every 1s)"| MAG

    MAG -->|"MetricsPing<br/>(every 1s)"| MC1
    MAG -->|"MetricsPing<br/>(every 1s)"| MC2
    MAG -->|"MetricsPing<br/>(every 1s)"| MC3

    MAG --> CL
    MAG --> AM
    MAG --> Prom
    MAG --> WS
    MAG --> REST

    style MAG fill:#ffeb3b
```

**Key design**: Metrics flow via MessageRouter (gossip), never through consensus. Zero consensus I/O overhead for observability.

## Metrics Collection

### MetricsCollector (Every Node)

Collects local metrics and responds to leader's MetricsPing:

| Metric | Description |
|--------|-------------|
| Node CPU usage | Per-node CPU utilization (0.0-1.0) |
| Heap usage | JVM heap memory |
| GC time | Garbage collection duration |
| Calls per entry point | Request count per method per cycle |
| Total call duration | Aggregate processing time per cycle |
| Success/failure count | Per-method success and failure counts |

### MetricsAggregator (Leader Only)

```mermaid
sequenceDiagram
    participant MAG as MetricsAggregator<br/>(Leader)
    participant MC as MetricsCollector<br/>(Each Node)

    loop Every 1 second
        MAG->>MC: MetricsPing
        MC-->>MAG: MetricsPong<br/>{cpu, heap, gc, calls, latency}
    end

    MAG->>MAG: Aggregate cluster-wide snapshot
    MAG->>MAG: Update 2-hour sliding window
    MAG->>MAG: Broadcast snapshot to all nodes
```

Maintains a 2-hour sliding window for historical pattern detection (used by TTM predictor).

## Invocation Metrics

Per-method tracking with percentile latencies:

| Metric | Description |
|--------|-------------|
| Call count | Total invocations |
| Success/failure rate | Per-method |
| P50/P95/P99 latency | Percentile distribution |
| EMA latency | Exponentially weighted moving average |
| Slow invocations | Above configurable threshold |

### Slow Invocation Tracking

Configurable threshold strategies:

| Strategy | Description |
|----------|-------------|
| Fixed | Static threshold (e.g., 100ms) |
| Adaptive | Threshold based on recent P95 |
| Per-method | Different threshold per method |
| Composite | Combination of strategies |

## Dynamic Aspects

Runtime-configurable per-method instrumentation via `DynamicAspectInterceptor`:

```mermaid
graph LR
    API["/api/aspects"] -->|"PUT"| KV["KV-Store<br/>AspectConfigKey"]
    KV -->|"Notification"| DAI["DynamicAspectInterceptor"]
    DAI --> Slice["Slice Method"]

    subgraph Modes["Aspect Modes"]
        NONE["NONE - no interception"]
        LOG["LOG - entry/exit logging"]
        METRICS["METRICS - latency, count"]
        BOTH["LOG_AND_METRICS"]
    end
```

Toggle at runtime without redeployment or restart. Configuration persisted in KV-Store.

## Alerting

### AlertManager

```mermaid
graph TB
    MAG["MetricsAggregator"] -->|"Cluster snapshot"| AM["AlertManager"]

    AM --> Check["Check thresholds"]

    Check -->|"metric > warning"| Warning["WARNING alert"]
    Check -->|"metric > critical"| Critical["CRITICAL alert"]

    Warning --> WS["/ws/dashboard"]
    Critical --> WS
    Critical --> Events["ClusterEventAggregator"]
```

### Alert Thresholds

Stored in KV-Store via `AlertThresholdKey`:

| Configuration | Description |
|--------------|-------------|
| Metric | Which metric to monitor |
| Warning level | First threshold |
| Critical level | Second threshold |
| Cooldown | Minimum time between alerts |

### ClusterEventAggregator

Ring buffer collecting cluster events (up to 11 types):
- Node join/leave/fail
- Leader change
- Slice lifecycle events
- Blueprint changes
- Alert triggers
- Scaling decisions

## Prometheus Integration

```
GET /metrics/prometheus
```

Standard scrape-compatible format. Exposes all collected metrics:
- Node-level (CPU, heap, GC)
- Per-method invocation metrics
- Cluster topology
- Active alerts

## Dashboard (WebSocket)

```
WS /ws/dashboard
```

Real-time push (no polling):
- Topology visualization
- Live metrics graphs
- Active alerts
- Cluster events stream

Available in both Forge (local development) and production clusters.

## Related Documents

- [08-scaling.md](08-scaling.md) - How metrics drive scaling decisions
- [03-invocation.md](03-invocation.md) - DynamicAspectInterceptor in invocation chain
- [12-management.md](12-management.md) - Management API endpoints
