# Aether Forge: Continuous Testing and Chaos Engineering

Aether Forge is a built-in testing environment that lets you run realistic simulations of your distributed application, inject failures, and verify resilience.

## Why Forge?

Traditional testing approaches fall short for distributed systems:

| Approach | Problem |
|----------|---------|
| Unit tests | Don't test distribution |
| Integration tests | Don't test failure scenarios |
| Staging environment | Expensive, often outdated |
| Production testing | Risky |

Forge provides:
- **Local multi-node cluster** in a single process
- **Realistic load generation** with configurable patterns
- **Chaos operations** (kill nodes, network partitions, latency injection)
- **Visual dashboard** for real-time monitoring
- **Automated verification** of system invariants

## Quick Start

```bash
# Start Forge with default configuration
cd your-project
aether forge start

# Open dashboard
open http://localhost:8080
```

The dashboard shows:
- Cluster topology (nodes, slices, connections)
- Real-time metrics (CPU, latency, throughput)
- Chaos controls (kill node, inject latency, etc.)
- Event log

## Configuration

### forge.yml

```yaml
# forge.yml - Forge configuration

cluster:
  nodes: 5                    # Number of nodes to simulate
  startPort: 4040             # Base port for cluster communication

slices:
  - artifact: "com.example:order-processor:1.0.0"
    instances: 3
  - artifact: "com.example:inventory-service:1.0.0"
    instances: 2
  - artifact: "com.example:pricing-service:1.0.0"
    instances: 2

load:
  enabled: true
  pattern: "steady"           # steady, ramp, spike, realistic
  requestsPerSecond: 100
  duration: "10m"

chaos:
  enabled: false              # Enable via dashboard or CLI
  scenarios:
    - type: "kill-node"
      probability: 0.01       # 1% chance per minute
    - type: "latency-injection"
      targetSlice: "inventory-service"
      delayMs: 500
      probability: 0.05

verification:
  - name: "no-data-loss"
    check: "all-orders-processed"
  - name: "latency-sla"
    check: "p99-latency < 500ms"
```

### Command Line Options

```bash
# Start with specific node count
aether forge start --nodes 7

# Start with chaos enabled
aether forge start --chaos

# Start with custom load pattern
aether forge start --load-pattern spike --rps 500

# Start in headless mode (no dashboard)
aether forge start --headless --duration 30m
```

## Load Generation

### Load Patterns

#### Steady

Constant request rate:

```
RPS ───────────────────────────
    │████████████████████████████
    └───────────────────────────→ time
```

```yaml
load:
  pattern: "steady"
  requestsPerSecond: 100
```

#### Ramp

Gradual increase:

```
RPS                          ████
    │                    ████
    │                ████
    │            ████
    │        ████
    │    ████
    │████
    └───────────────────────────→ time
```

```yaml
load:
  pattern: "ramp"
  startRps: 10
  endRps: 500
  rampDuration: "5m"
```

#### Spike

Sudden bursts:

```
RPS     ██
    │   ██           ██
    │   ██    ██     ██
    ████████████████████████████
    └───────────────────────────→ time
```

```yaml
load:
  pattern: "spike"
  baseRps: 100
  spikeRps: 1000
  spikeDuration: "30s"
  spikeInterval: "5m"
```

#### Realistic

Based on real-world patterns (e.g., e-commerce):

```
RPS         ████
    │      ██  ██
    │     ██    ██      ████
    │    ██      ██    ██  ██
    ████          ██████    ████
    └───────────────────────────→ time
       9am      12pm      6pm
```

```yaml
load:
  pattern: "realistic"
  profile: "ecommerce"    # or "api", "batch", "custom"
  peakRps: 500
  offPeakRps: 50
```

### Per-Slice Load

```yaml
load:
  slices:
    - artifact: "com.example:order-processor:1.0.0"
      method: "processOrder"
      requestsPerSecond: 50
      requestTemplate:
        customerId: "${uuid}"
        items:
          - productId: "${randomProduct}"
            quantity: "${randomInt(1,5)}"

    - artifact: "com.example:inventory-service:1.0.0"
      method: "checkStock"
      requestsPerSecond: 200
      requestTemplate:
        productId: "${randomProduct}"
```

## Chaos Operations

### Via Dashboard

The dashboard provides one-click chaos operations:

- **Kill Node**: Immediately terminate a node
- **Kill Leader**: Terminate the current leader node
- **Rolling Restart**: Restart nodes one by one
- **Network Partition**: Isolate a node from others
- **Latency Injection**: Add delay to specific slice calls

### Via CLI

```bash
# Kill a specific node
aether forge chaos kill-node node-3

# Kill the current leader
aether forge chaos kill-leader

# Rolling restart all nodes
aether forge chaos rolling-restart --delay 30s

# Inject latency into a slice
aether forge chaos inject-latency \
  --slice order-processor \
  --delay 500ms \
  --probability 0.1 \
  --duration 5m

# Create network partition
aether forge chaos partition \
  --isolate node-2,node-3 \
  --duration 2m
```

### Automated Chaos

```yaml
chaos:
  enabled: true
  schedule:
    - operation: "kill-random-node"
      interval: "10m"
    - operation: "inject-latency"
      target: "inventory-service"
      delay: "200ms"
      duration: "1m"
      interval: "5m"
```

## Verification

### Built-in Checks

```yaml
verification:
  # All requests should eventually succeed
  - name: "eventual-success"
    check: "error-rate < 1%"

  # Latency SLA
  - name: "latency-sla"
    check: "p99-latency < 500ms"

  # No stuck requests
  - name: "no-stuck-requests"
    check: "all-requests-complete-within 30s"

  # Data consistency
  - name: "consistency"
    check: "all-nodes-same-state"
```

### Custom Verification

```java
// Custom verification class
public class OrderVerification implements ForgeVerification {

    @Override
    public VerificationResult verify(ForgeContext context) {
        // Get all submitted orders
        var submittedOrders = context.getSubmittedRequests("order-processor", "processOrder");

        // Get all orders from database
        var storedOrders = orderRepository.findAll();

        // Verify all submitted orders are stored
        var missing = submittedOrders.stream()
            .filter(req -> !storedOrders.containsKey(req.idempotencyKey()))
            .toList();

        if (missing.isEmpty()) {
            return VerificationResult.pass("All orders processed");
        } else {
            return VerificationResult.fail("Missing orders: " + missing.size());
        }
    }
}
```

```yaml
verification:
  - name: "order-consistency"
    class: "com.example.OrderVerification"
```

## Scenarios

### Scenario 1: Node Failure During Load

Test that the system handles node failures gracefully:

```yaml
# scenario-node-failure.yml
name: "Node Failure During Load"

steps:
  - action: "start-load"
    rps: 200
    duration: "10m"

  - action: "wait"
    duration: "2m"
    description: "Let system stabilize"

  - action: "kill-node"
    target: "random"
    description: "Kill a random node"

  - action: "wait"
    duration: "1m"
    description: "Observe recovery"

  - action: "verify"
    checks:
      - "error-rate < 5%"
      - "recovery-time < 30s"
      - "no-data-loss"

  - action: "stop-load"
```

Run with:
```bash
aether forge scenario run scenario-node-failure.yml
```

### Scenario 2: Leader Failover

Test leader election and recovery:

```yaml
name: "Leader Failover"

steps:
  - action: "start-load"
    rps: 100

  - action: "wait"
    duration: "1m"

  - action: "kill-leader"
    repeat: 3
    interval: "30s"
    description: "Kill leader 3 times"

  - action: "verify"
    checks:
      - "new-leader-elected-within 5s"
      - "no-split-brain"
      - "state-consistent"
```

### Scenario 3: Cascade Failure

Test that one slow service doesn't take down others:

```yaml
name: "Cascade Failure Prevention"

steps:
  - action: "start-load"
    rps: 200

  - action: "inject-latency"
    target: "inventory-service"
    delay: "5s"
    probability: 0.5
    duration: "5m"
    description: "Simulate slow inventory service"

  - action: "verify"
    during: "latency-injection"
    checks:
      - "order-processor-latency < 10s"
      - "order-processor-error-rate < 10%"
      - "pricing-service-unaffected"

  - action: "wait"
    duration: "1m"
    description: "Recovery period"

  - action: "verify"
    checks:
      - "all-services-healthy"
      - "latency-back-to-normal"
```

### Scenario 4: Hot Deployment

Test deploying new versions under load:

```yaml
name: "Hot Deployment"

steps:
  - action: "start-load"
    rps: 300

  - action: "deploy"
    artifact: "com.example:order-processor:2.0.0"
    strategy: "rolling"
    description: "Deploy new version"

  - action: "verify"
    during: "deployment"
    checks:
      - "no-dropped-requests"
      - "latency-increase < 20%"

  - action: "verify"
    checks:
      - "all-instances-new-version"
      - "no-errors"
```

## Dashboard Features

### Topology View

Visual representation of your cluster:

```
┌─────────────────────────────────────────────────────────────────┐
│  Cluster Topology                                    [Refresh]  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│    ┌─────────┐      ┌─────────┐      ┌─────────┐              │
│    │ Node 1  │──────│ Node 2  │──────│ Node 3  │              │
│    │ (Leader)│      │         │      │         │              │
│    └────┬────┘      └────┬────┘      └────┬────┘              │
│         │                │                │                     │
│    ┌────┴────┐      ┌────┴────┐      ┌────┴────┐              │
│    │ Order   │      │ Order   │      │Inventory│              │
│    │Processor│      │Processor│      │ Service │              │
│    └─────────┘      └─────────┘      └─────────┘              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Metrics Charts

Real-time charts showing:
- Requests per second (per slice)
- Latency distribution (P50, P95, P99)
- Error rates
- CPU and memory per node
- Scaling events

### Chaos Controls

```
┌─────────────────────────────────────────────────────────────────┐
│  Chaos Engineering                                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  [Kill Random Node]  [Kill Leader]  [Rolling Restart]          │
│                                                                 │
│  Latency Injection:                                            │
│  Target: [order-processor ▼]  Delay: [500] ms  [ Inject ]      │
│                                                                 │
│  Network Partition:                                            │
│  Isolate: [node-3 ▼]  Duration: [60] s  [ Partition ]          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Event Log

```
┌─────────────────────────────────────────────────────────────────┐
│  Events                                           [Clear] [▼]  │
├─────────────────────────────────────────────────────────────────┤
│ 14:32:15  SCALE      order-processor scaled 3→4 instances      │
│ 14:32:00  CHAOS      Node node-2 killed                        │
│ 14:31:58  LEADER     Node node-1 became leader                 │
│ 14:31:45  RECOVERY   Node node-2 rejoined cluster              │
│ 14:31:30  SCALE      inventory-service scaled 2→3 instances    │
│ 14:31:00  LOAD       Started load generation (200 RPS)         │
│ 14:30:00  START      Forge cluster started with 5 nodes        │
└─────────────────────────────────────────────────────────────────┘
```

## CI/CD Integration

### GitHub Actions

```yaml
# .github/workflows/chaos-test.yml
name: Chaos Testing

on:
  push:
    branches: [main]
  schedule:
    - cron: '0 2 * * *'  # Nightly

jobs:
  chaos-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'

      - name: Build
        run: mvn clean install -DskipTests

      - name: Run Forge Scenarios
        run: |
          aether forge start --headless &
          sleep 30  # Wait for cluster to form
          aether forge scenario run scenarios/node-failure.yml
          aether forge scenario run scenarios/leader-failover.yml
          aether forge stop

      - name: Upload Results
        uses: actions/upload-artifact@v4
        with:
          name: forge-results
          path: forge-results/
```

### Docker Compose

```yaml
# docker-compose.forge.yml
version: '3.8'

services:
  forge:
    build:
      context: .
      dockerfile: Dockerfile.forge
    ports:
      - "8080:8080"
    volumes:
      - ./slices:/app/slices
      - ./forge.yml:/app/forge.yml
    command: forge start --headless --duration 1h
```

## Best Practices

### 1. Test Before Every Release

```bash
# Add to your release process
mvn verify
aether forge scenario run scenarios/release-validation.yml
```

### 2. Start Simple

```yaml
# First scenario: basic health
steps:
  - action: "start-load"
    rps: 50
    duration: "5m"
  - action: "verify"
    checks:
      - "no-errors"
```

### 3. Gradually Increase Chaos

```yaml
# Week 1: Node failures
# Week 2: Network partitions
# Week 3: Cascading failures
# Week 4: All combined
```

### 4. Monitor Production Patterns

Use production metrics to create realistic load profiles:

```yaml
load:
  pattern: "from-production"
  source: "prometheus://metrics.example.com"
  timeRange: "last-7d"
  scale: 0.1  # 10% of production load
```

## Troubleshooting

### Forge Won't Start

```bash
# Check port availability
lsof -i :4040-4050
lsof -i :8080

# Check Java version
java -version  # Must be 25+

# Check slice JARs exist
ls -la slices/*/target/*.jar
```

### Load Not Generating

```bash
# Check configuration
aether forge config validate

# Check slice deployment
aether forge status
# Should show slices as "active"
```

### Chaos Not Working

```bash
# Enable chaos mode
aether forge chaos enable

# Check chaos log
aether forge events --type chaos
```

## Next Steps

- [Scaling Guide](scaling.md) - Understand what Forge tests
- [CLI Reference](cli-reference.md) - All Forge commands
- [Architecture](../architecture-overview.md) - How Aether handles failures
