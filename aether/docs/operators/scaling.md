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

### Capacity requirements: how the autoscaler and the operator compose

Each source of capacity demand commits its own **requirement** record for a slice:

| Source | Writer | Expiry |
|--------|--------|--------|
| `REACTIVE` | the autoscaler, on every scaling decision | none |
| `OPERATOR` | `aether scale <artifact> -n <n>`; removed by `aether scale <artifact> --release` | none |
| `SCHEDULED`, `CAMPAIGN`, `PREDICTIVE`, `RECOVERY` | defined; no writer yet | per record |

The leader's **combiner** derives the slice's target from them:

```
target = clamp( max(minAvailable, every unexpired requirement), 3, maxInstances )
```

- The combiner is the only writer of the target's instance count once the target exists, with one exception: an A/B
  test's canary. A blueprint's declared count seeds the target when it is first created. A redeploy and a rolling update
  change the target's version and keep its count [mechanism: those writers carry the committed count; the combiner runs on
  every requirement change, target change, topology change and expiry].
- An A/B test runs its variant at a fixed 3 instances while the test deploys its variants or is active (#1721). The
  combiner and the autoscaler leave that slice alone meanwhile, so a canary never runs at the full target. Concluding or
  rolling back the test sets the slice to its floor (at least 3), and the combiner then restores the count the committed
  requirements ask for [mechanism: the combiner skips a slice whose A/B test runs a canary, and re-runs on the test's put].
- `max` takes the largest estimate of the same demand; requirements never add up.
- The cluster's size does not bound the target (placeable capacity is defined per placement audience in a later
  release). One node hosts at most one instance of a slice, so a target above the number of eligible nodes in the slice's
  placement audience leaves the excess unplaced: the leader raises `slice-target-exceeds-placement` (subject: the slice)
  and re-attempts the rest on every reconcile tick. It clears (`slice-target-placement-restored`) when nodes join or the
  target falls.
- Scale-down comes only from the autoscaler's requirement falling, under its cooldown. Releasing the operator requirement,
  lowering it, or a requirement expiring releases that claim and does not scale the slice down: the same write re-bases
  the autoscaler's requirement to the current count, so the slice holds its count until the autoscaler lowers it
  [mechanism: the release, or the combiner's removal of the expired record, and the re-base commit in one leader
  transaction]. With no unexpired requirement, the current count stands.
- No slice runs below 3 instances, whatever `maxInstances` says.
- When a blueprint is deleted or a deployment rolled back, the slice's requirement records are removed in the same
  transaction as its target, so re-applying the blueprint starts from the blueprint's own count [mechanism: the target
  removal paths of the default deployment engine carry the requirement removals; a requirement that outlives its target
  for any other reason is removed by the combiner while the target is absent, retried until it applies]. A cluster
  running the FSM2 deployment engine removes them only through the combiner [design intent — unverified].

```bash
# Hold at least 5 instances; the autoscaler can still go higher
aether scale org.example:order-processor:1.0.0 -n 5

# Remove the hold
aether scale org.example:order-processor:1.0.0 --release
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

`GET /api/v1/metrics/comprehensive` returns `ComprehensiveMetricsResponse` on the management
port (8080). The following command selects real response fields; latency percentiles describe
interval means, not a pooled request-latency distribution. Numeric values depend on collected samples.

```bash
curl -s http://localhost:8080/api/v1/metrics/comprehensive | jq '{
  minuteTimestamp, avgCpuUsage, totalInvocations,
  intervalMeanLatencyP50, intervalMeanLatencyP95, intervalMeanLatencyP99
}'
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

- [Forge Guide](../slice-developers/forge-guide.md) - Test scaling with chaos engineering
- [CLI Reference](../reference/cli.md) - All scaling commands
- [Architecture](../architecture/00-overview.md) - Understanding blueprints and convergence
