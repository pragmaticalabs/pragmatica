# Invocation Metrics System

This document describes the per-method invocation metrics system in Aether.

## Overview

The metrics system implements a **dual-track approach**:

1. **Tier 1 - Aggregated Metrics**: Lightweight counters for all invocations
2. **Tier 2 - Slow Call Capture**: Detailed information for debugging slow invocations

This design provides minimal overhead during normal operation while capturing detailed diagnostics for outliers.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Every Invocation (nanosecond cost)                         │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  1. Increment counter                               │   │
│  │  2. Add duration to sum                             │   │
│  │  3. Increment histogram bucket                      │   │
│  │  4. IF duration > threshold → capture to ring buffer│   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Every Interval (configurable, e.g., 1 second)              │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Publish: MethodMetrics + SlowInvocations[]         │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Components

### MethodMetrics

Aggregated statistics for a single slice method:

| Field | Type | Description |
|-------|------|-------------|
| `count` | `AtomicLong` | Total invocation count |
| `successCount` | `AtomicLong` | Successful invocations |
| `failureCount` | `AtomicLong` | Failed invocations |
| `totalDurationNs` | `AtomicLong` | Sum of all durations |
| `histogram` | `AtomicInteger[5]` | Latency distribution |

**Histogram buckets:**
- Bucket 0: < 1ms
- Bucket 1: 1ms - 10ms
- Bucket 2: 10ms - 100ms
- Bucket 3: 100ms - 1s
- Bucket 4: >= 1s

**Derived metrics:**
- Throughput: `count / interval`
- Average latency: `totalDurationNs / count`
- Success rate: `successCount / count`
- Percentile estimates from histogram

### SlowInvocation

Captured details for slow calls:

```java
record SlowInvocation(
    MethodName methodName,
    long timestampNs,
    long durationNs,
    int requestBytes,
    int responseBytes,
    boolean success,
    Option<String> errorType
) {}
```

**Ring buffer behavior:**
- Maximum 10 slow invocations per method per interval
- Oldest entries replaced when buffer is full
- All entries cleared on snapshot

### ThresholdStrategy

Determines when an invocation is considered "slow":

#### Fixed Strategy

Same threshold for all methods:

```java
ThresholdStrategy.fixed(100)  // 100ms threshold
```

#### Adaptive Strategy

Learns from observed latencies, captures outliers (default 3x average):

```java
ThresholdStrategy.adaptive(10, 1000)  // Min 10ms, max 1000ms
ThresholdStrategy.adaptive(10, 1000, 3.0)  // With custom multiplier
```

**Behavior:**
- Uses exponential moving average (EMA) with α = 0.1
- Threshold = `average × multiplier`, clamped to [min, max]
- Requires 10 observations before switching from minimum threshold

#### Per-Method Strategy

Different thresholds for specific methods:

```java
ThresholdStrategy.perMethod(100)  // 100ms default
    .withThreshold(placeOrderMethod, 500)   // 500ms for placeOrder
    .withThreshold(checkStockMethod, 50);   // 50ms for checkStock
```

#### Composite Strategy

Combines per-method overrides with adaptive fallback:

```java
ThresholdStrategy.composite(
    Map.of(placeOrderMethod, 500L),  // Explicit thresholds
    10, 1000  // Adaptive fallback range
)
```

## Usage

### Creating a Metrics Collector

```java
// With default adaptive strategy (10-1000ms, 3x multiplier)
var collector = InvocationMetricsCollector.invocationMetricsCollector();

// With custom strategy
var collector = InvocationMetricsCollector.invocationMetricsCollector(
    ThresholdStrategy.adaptive(10, 500, 2.0)
);
```

### Recording Invocations

Automatic recording via InvocationHandler:

```java
var handler = InvocationHandler.invocationHandler(self, network, collector);
```

Manual recording (if needed):

```java
collector.recordSuccess(artifact, method, durationNs, requestBytes, responseBytes);
collector.recordFailure(artifact, method, durationNs, requestBytes, "ErrorType");
```

### Taking Snapshots

```java
// Snapshot and reset (for periodic publishing)
List<MethodSnapshot> snapshots = collector.snapshotAndReset();

// Snapshot without reset (for monitoring)
List<MethodSnapshot> current = collector.snapshot();
```

### MethodSnapshot Structure

```java
record MethodSnapshot(
    Artifact artifact,
    MethodMetrics.Snapshot metrics,
    List<SlowInvocation> slowInvocations,
    long currentThresholdNs
) {}
```

## Integration with AetherNode

To enable metrics collection in a node:

```java
var metricsCollector = InvocationMetricsCollector.create(
    ThresholdStrategy.adaptive(10, 1000)
);

var invocationHandler = InvocationHandler.invocationHandler(
    self, network, metricsCollector
);
```

Access the collector for snapshot publishing:

```java
invocationHandler.metricsCollector()
    .onPresent(collector -> {
        var snapshots = collector.snapshotAndReset();
        // Publish to metrics system
    });
```

## Per-Invocation Cost

| Operation | Cost |
|-----------|------|
| Counter increment | ~5 ns |
| Histogram update | ~2 ns |
| Threshold check | ~5 ns |
| Slow call capture | ~50 ns (rare) |
| **Total (normal)** | **~12 ns** |
| **Total (slow)** | **~62 ns** |

## Configuration Recommendations

### High-Throughput Services

```java
ThresholdStrategy.fixed(50)  // 50ms fixed, very low overhead
```

### General Use

```java
ThresholdStrategy.adaptive(10, 1000)  // Adaptive, learns patterns
```

### Mixed Workloads

```java
ThresholdStrategy.composite(
    Map.of(
        heavyMethod, 5000L,    // 5s for heavy operations
        lightMethod, 10L       // 10ms for light operations
    ),
    50, 500  // Adaptive fallback
)
```

## Metrics Data Flow

```
┌──────────────┐     ┌────────────────────┐     ┌──────────────────┐
│ Invocation   │────>│ InvocationHandler  │────>│ MetricsCollector │
│ (request)    │     │ (records metrics)  │     │ (aggregates)     │
└──────────────┘     └────────────────────┘     └──────────────────┘
                                                        │
                                                        v
┌──────────────┐     ┌────────────────────┐     ┌──────────────────┐
│ Dashboard/   │<────│ MetricsScheduler   │<────│ snapshot()       │
│ Monitoring   │     │ (publishes)        │     │ (resets)         │
└──────────────┘     └────────────────────┘     └──────────────────┘
```

## Files

| File | Description |
|------|-------------|
| `MethodMetrics.java` | Aggregated counters and histogram |
| `SlowInvocation.java` | Captured slow call details |
| `ThresholdStrategy.java` | Slow detection strategies |
| `InvocationMetricsCollector.java` | Main collector with ring buffer |
| `InvocationHandler.java` | Integration point (records metrics) |
