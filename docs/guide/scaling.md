# How Scaling Works in Aether

Aether automatically scales your slices based on observed load. This guide explains when, why, and how scaling happens.

## The Scaling Loop

Every second, Aether's control loop evaluates whether scaling is needed:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  1. COLLECT                                                              │
│     Each node reports: CPU, memory, request counts, latencies           │
│                                                                          │
│  2. AGGREGATE                                                            │
│     Leader node combines metrics from all nodes                         │
│     Historical data stored in 2-hour sliding window                     │
│                                                                          │
│  3. EVALUATE                                                             │
│     Decision tree checks thresholds                                     │
│     Pattern detection for predictive scaling                            │
│                                                                          │
│  4. DECIDE                                                               │
│     Update blueprint (desired state)                                    │
│     Scale up, scale down, or maintain                                   │
│                                                                          │
│  5. CONVERGE                                                             │
│     Cluster reconciles actual state with desired state                  │
│     New instances started, excess instances stopped                     │
└─────────────────────────────────────────────────────────────────────────┘
```

## What Triggers Scaling

### Scale Up

| Metric | Threshold | Action |
|--------|-----------|--------|
| CPU usage | > 70% sustained | Add instance |
| Request latency | > P95 threshold | Add instance |
| Queue depth | > configured limit | Add instance |
| Error rate | > 1% | Add instance (if errors are timeout-related) |

### Scale Down

| Metric | Threshold | Action |
|--------|-----------|--------|
| CPU usage | < 30% sustained | Remove instance (if > min instances) |
| Request rate | Near zero for 5 min | Remove instance |

### Predictive Scaling

Aether learns from the 2-hour historical window:

```
Example: E-commerce site with lunch rush

11:00 - Traffic normal, 2 instances of OrderProcessor
11:30 - Aether detects rising pattern from yesterday's data
11:45 - Preemptively scales to 5 instances
12:00 - Lunch rush hits, instances already warm
12:30 - Traffic peaks, Aether adds 2 more instances
13:30 - Traffic subsides, gradual scale down
14:00 - Back to 2 instances
```

## The Idempotency Requirement

**Critical**: For scaling to work safely, slice methods must be idempotent.

### Why Idempotency Matters

When Aether scales, requests might be retried:

```
Client ──request──→ Node 1 ──→ [SliceA instance 1]
                       │
                       × (Node 1 fails mid-processing)
                       │
                       └──retry──→ Node 2 ──→ [SliceA instance 2]
```

If the method isn't idempotent, the retry could cause duplicate side effects.

### Making Methods Idempotent

#### Pattern 1: Idempotency Keys

```java
public Promise<OrderResult> processOrder(ProcessOrderRequest request) {
    // Check if already processed
    return orderRepository.findByIdempotencyKey(request.idempotencyKey())
        .fold(
            // Not found - process normally
            () -> doProcessOrder(request),
            // Found - return cached result
            existing -> Promise.success(OrderResult.success(existing))
        );
}
```

#### Pattern 2: Conditional Writes

```java
public Promise<Unit> updateInventory(UpdateInventoryRequest request) {
    // Only update if version matches (optimistic locking)
    return inventoryRepository.updateIfVersion(
        request.productId(),
        request.newQuantity(),
        request.expectedVersion()
    );
}
```

#### Pattern 3: Natural Idempotency

Some operations are naturally idempotent:

```java
// Setting a value is idempotent
public Promise<Unit> setUserPreference(String userId, String key, String value) {
    return preferencesRepository.set(userId, key, value);
}

// Reading is always idempotent
public Promise<User> getUser(String userId) {
    return userRepository.findById(userId);
}
```

### What If I Can't Make It Idempotent?

If a method truly can't be idempotent (rare), you have options:

1. **Single instance mode**: Configure the slice to never scale beyond 1 instance
2. **Saga with compensation**: Use the saga pattern with compensating transactions
3. **External coordination**: Use a distributed lock (but this limits scaling benefits)

```java
// Force single instance
@SliceConfig(maxInstances = 1)
public class PaymentProcessorSlice implements Slice {
    // This slice will never be distributed
}
```

## Scaling Configuration

### Blueprint-Level Configuration

```json
{
  "artifact": "com.example:order-processor:1.0.0",
  "scaling": {
    "minInstances": 2,
    "maxInstances": 10,
    "targetCpuPercent": 60,
    "targetLatencyMs": 100,
    "scaleUpCooldown": "30s",
    "scaleDownCooldown": "5m"
  }
}
```

### CLI Configuration

```bash
# Set scaling parameters
aether scale order-processor \
  --min 2 \
  --max 10 \
  --target-cpu 60 \
  --target-latency 100ms

# Disable auto-scaling (manual only)
aether scale order-processor --auto=false --instances 5
```

### Programmatic Configuration

```java
var config = AetherNodeConfig.builder()
    .defaultScaling(ScalingConfig.builder()
        .minInstances(1)
        .maxInstances(20)
        .targetCpuPercent(70)
        .scaleUpCooldown(Duration.ofSeconds(30))
        .scaleDownCooldown(Duration.ofMinutes(5))
        .build())
    .build();
```

## Instance Placement

Aether decides where to run instances:

### Default Strategy: Spread

Instances are spread across nodes for resilience:

```
3 instances of OrderProcessor on 3 nodes:

Node 1: [OrderProcessor]
Node 2: [OrderProcessor]
Node 3: [OrderProcessor]
```

### Affinity Rules

You can configure placement preferences:

```json
{
  "artifact": "com.example:order-processor:1.0.0",
  "placement": {
    "affinity": {
      "sameNodeAs": ["com.example:order-repository:1.0.0"]
    },
    "antiAffinity": {
      "notSameNodeAs": ["com.example:payment-processor:1.0.0"]
    }
  }
}
```

### Resource-Based Placement

Aether considers node resources:

```
Node 1: 80% CPU used → avoid placing new instances
Node 2: 30% CPU used → preferred for new instances
Node 3: 50% CPU used → acceptable
```

## Observing Scaling

### Real-Time Status

```bash
aether status --watch

# Output (updates every second):
# Nodes: 3 (healthy)
#
# Slice                    Instances  CPU    Latency  Status
# order-processor          5/10       62%    45ms     scaling-up
# inventory-service        3/5        28%    12ms     stable
# payment-processor        1/1        15%    89ms     stable (single-instance)
```

### Metrics API

```bash
curl http://localhost:5040/metrics/slices

{
  "order-processor": {
    "instances": 5,
    "targetInstances": 7,
    "cpuPercent": 72,
    "latencyP50Ms": 23,
    "latencyP95Ms": 89,
    "latencyP99Ms": 145,
    "requestsPerSecond": 1250,
    "scaling": {
      "status": "scaling-up",
      "reason": "CPU above threshold (72% > 70%)",
      "lastScaleEvent": "2025-01-15T14:32:00Z"
    }
  }
}
```

### Scaling Events

```bash
aether events --type scaling

# 14:32:00 order-processor scaled 5→6 (CPU: 72%)
# 14:32:45 order-processor scaled 6→7 (CPU: 71%)
# 14:35:00 order-processor scaled 7→6 (cooldown, CPU: 45%)
```

## Scaling Internals

### The Decision Tree Controller

Aether uses a decision tree for fast, deterministic scaling decisions:

```
                    ┌─────────────────┐
                    │ Check CPU Usage │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
          CPU < 30%     30% ≤ CPU ≤ 70%   CPU > 70%
              │              │              │
              ▼              ▼              ▼
        Scale Down?      Check Latency   Scale Up
              │              │              │
              ▼              ▼              ▼
        instances > min  Latency OK?    instances < max?
              │              │              │
              ▼              ▼              ▼
           Remove 1      No Action       Add 1
```

### Cooldown Periods

To prevent oscillation:

- **Scale up cooldown**: 30 seconds (default)
- **Scale down cooldown**: 5 minutes (default)

```
Time    Event
14:00   Traffic spike, CPU hits 80%
14:00   Scale up: 3→4 instances
14:00   Cooldown starts (30s)
14:00   CPU still 75% (ignored - in cooldown)
14:01   Cooldown ends, CPU 72%
14:01   Scale up: 4→5 instances
...
14:30   Traffic drops, CPU falls to 25%
14:30   Scale down: 5→4 instances
14:30   Cooldown starts (5 minutes)
14:35   Cooldown ends, CPU still 25%
14:35   Scale down: 4→3 instances
```

### Instance Startup Time

Aether keeps slice JARs cached on each node:

1. **First deployment**: Download JAR, load classes (~seconds)
2. **Subsequent scaling**: Classes already loaded (~milliseconds)

No container spin-up, no cold starts.

## Best Practices

### 1. Start Conservative

```bash
# Start with generous resources
aether scale my-slice --min 2 --max 5

# Monitor for a week
# Then tune based on actual patterns
```

### 2. Set Realistic Latency Targets

```bash
# Don't set latency target below your actual P50
# If P50 is 50ms, don't target 10ms
aether scale my-slice --target-latency 100ms  # Reasonable
```

### 3. Use Forge for Load Testing

```bash
# Before production, test scaling behavior
aether forge start
# Generate load, watch scaling, verify no issues
```

### 4. Monitor Scale Events

```bash
# Set up alerts for unusual scaling
# Too many scale events might indicate:
# - Latency target too aggressive
# - Memory leak causing restarts
# - External dependency issues
```

## Troubleshooting

### Slice Won't Scale Up

Check:
1. `maxInstances` limit reached?
2. In cooldown period?
3. No available nodes with capacity?

```bash
aether status --verbose
aether events --type scaling --last 10
```

### Slice Scaling Too Aggressively

Check:
1. Latency target too low?
2. CPU target too low?
3. Cooldown too short?

```bash
# Increase thresholds
aether scale my-slice --target-cpu 80 --target-latency 200ms
```

### Instances Crashing During Scale

Check:
1. Memory limits hit?
2. Database connection pool exhausted?
3. External service rate limiting?

```bash
aether logs my-slice --tail 100
```

## Next Steps

- [Forge Guide](forge-guide.md) - Test scaling with chaos engineering
- [CLI Reference](cli-reference.md) - All scaling commands
- [Architecture](../architecture-overview.md) - Understanding blueprints and convergence
