# Aether Forge & Demos

This document covers Aether Forge (the cluster simulator) and demonstration applications included with Aether.

## Overview

Aether includes:

| Component | Purpose | Audience |
|-----------|---------|----------|
| **Aether Forge** (`forge/`) | Cluster simulator with visual dashboard for resilience testing | Developers, DevOps, executives |
| **Order Domain Demo** (`examples/order-demo/`) | Multi-slice business domain example | Developers, architects |

---

## Aether Forge

**Aether Forge** is a single-JVM cluster simulator with a visual web dashboard for testing and demonstrating
Aether's distributed capabilities. It provides:

- **Multi-node simulation**: Run multiple Aether nodes in a single JVM
- **Chaos engineering**: Kill nodes, crash nodes, simulate network partitions
- **Load testing**: Generate configurable request loads
- **Real-time dashboard**: Visualize cluster health, metrics, and events
- **API control**: Full REST API for automation and integration

### Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ForgeServer                               │
│                     (HTTP port 8888)                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────┐         │
│  │ ForgeCluster │   │LoadGenerator │   │ ForgeMetrics │         │
│  │              │   │              │   │              │         │
│  │ ┌────┬────┐  │   │ Continuous   │   │ Success rate │         │
│  │ │N1  │N2  │  │◄──│ KV-Store     │──►│ Latency      │         │
│  │ ├────┼────┤  │   │ operations   │   │ Throughput   │         │
│  │ │N3  │N4  │  │   │              │   │              │         │
│  │ ├────┴────┤  │   └──────────────┘   └──────────────┘         │
│  │ │   N5    │  │                                                │
│  │ └─────────┘  │                                                │
│  └──────────────┘                                                │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│  ForgeApiHandler          │  StaticFileHandler                   │
│  /api/status              │  /index.html                         │
│  /api/kill/{nodeId}       │  /dashboard.js                       │
│  /api/crash/{nodeId}      │  /style.css                          │
│  /api/add-node            │                                      │
│  /api/rolling-restart     │                                      │
│  /api/load/set/{rate}     │                                      │
│  /api/load/ramp           │                                      │
│  /api/events              │                                      │
│  /api/reset-metrics       │                                      │
└─────────────────────────────────────────────────────────────────┘
```

### Components

| Component | File | Description |
|-----------|------|-------------|
| `ForgeServer` | `forge/src/.../ForgeServer.java` | Main entry point, starts HTTP server and cluster |
| `ForgeCluster` | `forge/src/.../ForgeCluster.java` | Manages multiple AetherNodes in-process |
| `LoadGenerator` | `forge/src/.../LoadGenerator.java` | Generates continuous KV-Store operations |
| `ForgeMetrics` | `forge/src/.../ForgeMetrics.java` | Aggregates success/failure/latency metrics |
| `ForgeApiHandler` | `forge/src/.../ForgeApiHandler.java` | REST API for dashboard interactions |
| `StaticFileHandler` | `forge/src/.../StaticFileHandler.java` | Serves web dashboard files |

### Dashboard Features

The web dashboard (`index.html`) provides:

- **Cluster Topology Visualization**: D3.js-powered node graph showing leader and node states
- **Real-Time Metrics**: Requests/sec, success rate, average latency
- **Charts**: Historical success rate and throughput (Chart.js)
- **Event Timeline**: Scrolling log of cluster events
- **Control Panel**:
    - Chaos operations: Kill node, kill leader, crash node, rolling restart
    - Load control: Slider and preset buttons (1K, 5K, 10K req/sec)
    - Ramp-up functionality for gradual load increase

### Prerequisites

- JDK 25+
- Maven 3.9+
- Built Aether project (`mvn clean install`)

### Running Locally

```bash
# Build Forge
cd forge
mvn package

# Run with defaults (5 nodes, 1000 req/sec)
java -jar target/aether-forge.jar

# Or with custom settings
CLUSTER_SIZE=7 LOAD_RATE=2000 java -jar target/aether-forge.jar
```

The dashboard opens automatically at `http://localhost:8888`.

### Running with Docker

```bash
# Build image
cd forge
docker build -t aether-forge .

# Run container
docker run -p 8888:8888 aether-forge

# Or with custom settings
docker run -p 8888:8888 \
  -e CLUSTER_SIZE=7 \
  -e LOAD_RATE=2000 \
  aether-forge
```

### Running with Docker Compose

```bash
cd forge
docker-compose up
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `FORGE_PORT` | 8888 | HTTP server port |
| `CLUSTER_SIZE` | 5 | Initial number of nodes |
| `LOAD_RATE` | 1000 | Initial requests per second |
| `JAVA_OPTS` | (see Dockerfile) | JVM options |

---

## Forge Demo Scenarios

### Scenario 1: Basic Cluster Formation

**Goal**: Demonstrate that the cluster forms and handles load.

**Steps**:

1. Start the demo with defaults
2. Observe dashboard showing 5 healthy nodes
3. Note consistent ~1000 req/sec throughput
4. Success rate should be 100%

**Expected Result**: Green success rate, stable throughput, leader clearly marked.

---

### Scenario 2: Node Failure Recovery

**Goal**: Demonstrate cluster continues operating when a follower node fails.

**Steps**:

1. Start demo with 5 nodes
2. Wait for stable metrics (30 seconds)
3. Click "KILL NODE" and select a non-leader node
4. Observe brief dip in success rate
5. Watch as remaining nodes take over

**Expected Result**:

- Brief dip in success rate (< 5 seconds)
- Throughput recovers to previous level
- Dashboard shows 4 healthy nodes

---

### Scenario 3: Leader Failure Recovery

**Goal**: Demonstrate automatic leader election when leader fails.

**Steps**:

1. Start demo with 5 nodes
2. Wait for stable metrics
3. Click "KILL LEADER"
4. Observe leader re-election

**Expected Result**:

- Slightly longer recovery than follower failure (due to leader election)
- New leader automatically selected (first node in sorted topology)
- Success rate recovers within 5-10 seconds

---

### Scenario 4: Cascading Failures

**Goal**: Demonstrate cluster survives multiple failures (up to N/2 - 1).

**Steps**:

1. Start demo with 5 nodes
2. Kill 2 nodes in succession (wait 5 seconds between kills)
3. Observe that cluster continues operating with 3 nodes
4. Optionally kill a 3rd node to demonstrate quorum loss

**Expected Result**:

- With 3/5 nodes: Cluster operates normally
- With 2/5 nodes: Cluster loses quorum, operations fail

---

### Scenario 5: Node Addition (Scale Out)

**Goal**: Demonstrate dynamic cluster expansion.

**Steps**:

1. Start demo with 3 nodes
2. Observe throughput ceiling
3. Click "+ ADD NODE" repeatedly to add 2 more nodes
4. Observe throughput capacity increase

**Expected Result**:

- New nodes join cluster within seconds
- Load distributes across all nodes
- Throughput capacity increases proportionally

---

### Scenario 6: Rolling Restart

**Goal**: Demonstrate zero-downtime upgrades.

**Steps**:

1. Start demo with 5 nodes at 5000 req/sec
2. Click "ROLLING RESTART"
3. Observe nodes restarting one by one
4. Monitor success rate throughout

**Expected Result**:

- Each node restarts sequentially
- Success rate remains high (may dip briefly per node)
- No total outage during process
- Timeline shows each node restart event

---

### Scenario 7: Load Spike Handling

**Goal**: Demonstrate behavior under sudden load increase.

**Steps**:

1. Start demo with 5 nodes at 1000 req/sec
2. Suddenly set load to 10K req/sec using button
3. Observe latency increase and potential backpressure
4. Click "RAMP UP" to demonstrate gradual increase

**Expected Result**:

- Sudden spike: Higher latency, possible brief success rate drop
- Ramped increase: Smoother transition, stable success rate

---

### Scenario 8: Chaos Engineering Sequence

**Goal**: Comprehensive resilience demonstration for executives.

**Steps**:

1. Start with 5 nodes, 1000 req/sec
2. Wait 30 seconds for baseline
3. Kill a follower node
4. Wait for recovery (10 seconds)
5. Add a new node
6. Wait 10 seconds
7. Kill the leader
8. Wait for new leader election
9. Initiate rolling restart
10. During rolling restart, ramp load to 5K req/sec
11. After restart completes, kill 2 nodes simultaneously

**Expected Result**:

- Cluster survives all operations
- Success rate never drops to 0%
- Recovery happens automatically without manual intervention

---

## Order Domain Demo

A multi-module demonstration of slice-based microservices architecture using the Aether runtime.

### Architecture

```
                           ┌─────────────────────┐
                           │   HTTP Gateway      │
                           │ (demo-order.blueprint) │
                           └──────────┬──────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌───────────────┐           ┌─────────────────┐           ┌───────────────┐
│  place-order  │           │get-order-status │           │ cancel-order  │
│   (3 inst)    │           │    (2 inst)     │           │   (2 inst)    │
│  Lean Slice   │           │   Lean Slice    │           │  Lean Slice   │
└───────┬───────┘           └─────────────────┘           └───────┬───────┘
        │                                                         │
        ├─────────────────────────────────────────────────────────┤
        │                                                         │
        ▼                                                         ▼
┌───────────────────┐                                 ┌───────────────────┐
│ inventory-service │                                 │  pricing-service  │
│     (2 inst)      │                                 │     (2 inst)      │
│   Service Slice   │                                 │   Service Slice   │
└───────────────────┘                                 └───────────────────┘
        │                                                         │
        └─────────────────────────┬───────────────────────────────┘
                                  │
                                  ▼
                         ┌───────────────┐
                         │ order-domain  │
                         │ (shared types)│
                         └───────────────┘
```

### Module Structure

```
examples/order-demo/
├── order-domain/           # Shared domain types (no slice)
│   └── OrderId, ProductId, CustomerId, Money, OrderStatus
├── inventory-service/      # Service Slice: stock management
│   └── checkStock, reserveStock, releaseStock
├── pricing-service/        # Service Slice: price calculations
│   └── getPrice, calculateTotal
├── place-order/            # Lean Slice: place order use case
│   └── placeOrder
├── get-order-status/       # Lean Slice: status query use case
│   └── getOrderStatus
├── cancel-order/           # Lean Slice: cancellation use case
│   └── cancelOrder
└── demo-order.blueprint    # Deployment configuration (TOML format)
```

### Slice Types

**Service Slices** (multiple methods):

- `InventoryServiceSlice`: checkStock, reserveStock, releaseStock
- `PricingServiceSlice`: getPrice, calculateTotal

**Lean Slices** (single use case):

- `PlaceOrderSlice`: Orchestrates order placement
- `GetOrderStatusSlice`: Retrieves order status
- `CancelOrderSlice`: Handles order cancellation

### Blueprint Configuration

The `demo-order.blueprint` file defines the deployment using TOML format:

```toml
# Aether Blueprint - Order Demo
id = "demo-order:0.1.0"

[slices.inventory_service]
artifact = "org.pragmatica-lite.aether.demo:inventory-service:0.1.0"
instances = 2

[slices.pricing_service]
artifact = "org.pragmatica-lite.aether.demo:pricing-service:0.1.0"
instances = 2

[slices.place_order]
artifact = "org.pragmatica-lite.aether.demo:place-order:0.1.0"
instances = 3

[slices.get_order_status]
artifact = "org.pragmatica-lite.aether.demo:get-order-status:0.1.0"
instances = 2

[slices.cancel_order]
artifact = "org.pragmatica-lite.aether.demo:cancel-order:0.1.0"
instances = 2
```

**Note**: Routes are self-registered by slices during activation via RouteRegistry.

### PlaceOrder Flow

The `PlaceOrderSlice` demonstrates orchestrated inter-slice calls:

```
1. ValidatRequest
       │
       ▼
2. CheckAllStock ─────────────► InventoryService.checkStock (parallel)
       │
       ▼
3. CalculateTotal ────────────► PricingService.calculateTotal
       │
       ▼
4. ReserveAllStock ───────────► InventoryService.reserveStock (parallel)
       │
       ▼
5. CreateOrder ───────────────► PlaceOrderResponse
```

### Building the Demo

```bash
cd examples/order-demo
mvn clean install
```

This produces 6 slice JARs:

- `order-domain/target/order-domain-0.1.0.jar`
- `inventory-service/target/inventory-service-0.1.0.jar`
- `pricing-service/target/pricing-service-0.1.0.jar`
- `place-order/target/place-order-0.1.0.jar`
- `get-order-status/target/get-order-status-0.1.0.jar`
- `cancel-order/target/cancel-order-0.1.0.jar`

### Running with CLI

```bash
# Start cluster
aether cluster start --nodes 3

# Deploy blueprint
aether blueprint apply examples/order-demo/demo-order.blueprint

# Check status
aether slice list

# Test endpoints
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": "CUST-123", "items": [{"productId": "PROD-ABC123", "quantity": 2}]}'

curl http://localhost:8080/api/orders/ORD-xxx

curl -X DELETE http://localhost:8080/api/orders/ORD-xxx
```

### Key Implementation Patterns

**1. Parse, Don't Validate**:

```java
public static Result<ValidPlaceOrderRequest> validPlaceOrderRequest(PlaceOrderRequest request) {
    // Returns Result<T> - only valid if parsing succeeds
}
```

**2. Inter-Slice Invocation**:

```java
invoker.invokeAndWait(
    INVENTORY,
    MethodName.methodName("checkStock").unwrap(),
    new CheckStockRequest(item.productId(), item.quantity()),
    StockAvailability.class
)
```

**3. Pipeline Context Records**:

```java
private record ValidWithStockCheck(ValidPlaceOrderRequest request) {}
private record ValidWithPrice(ValidPlaceOrderRequest request, OrderTotal total) {}
```

**4. Parallel Operations**:

```java
var stockChecks = request.items().stream()
    .map(item -> invoker.invokeAndWait(...))
    .toList();
return Promise.allOf(stockChecks)...
```

---

## API Reference

### Forge API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Full cluster status, metrics, load info |
| `/api/add-node` | POST | Add a new node to the cluster |
| `/api/kill/{nodeId}` | POST | Gracefully stop a node |
| `/api/crash/{nodeId}` | POST | Abruptly terminate a node |
| `/api/rolling-restart` | POST | Restart all nodes sequentially |
| `/api/load/set/{rate}` | POST | Set request rate immediately |
| `/api/load/ramp` | POST | Gradually ramp to target rate |
| `/api/events` | GET | Get event timeline |
| `/api/reset-metrics` | POST | Reset all metrics and events |

### Status Response Format

```json
{
  "cluster": {
    "nodes": [
      {"id": "node-1", "port": 5050, "state": "healthy", "isLeader": true},
      {"id": "node-2", "port": 5051, "state": "healthy", "isLeader": false}
    ],
    "leaderId": "node-1",
    "nodeCount": 5
  },
  "metrics": {
    "requestsPerSecond": 1000.0,
    "successRate": 99.8,
    "avgLatencyMs": 2.5,
    "totalSuccess": 150000,
    "totalFailures": 300
  },
  "load": {
    "currentRate": 1000,
    "targetRate": 1000,
    "running": true
  },
  "uptimeSeconds": 120
}
```

---

## Troubleshooting

### Demo Fails to Start

**Symptom**: `Cluster start failed` error

**Solutions**:

1. Ensure ports 5050-5060 are available
2. Check for other Aether processes: `lsof -i :5050`
3. Increase startup timeout if slow machine

### Dashboard Not Loading

**Symptom**: Browser shows connection refused

**Solutions**:

1. Verify demo is running: `curl http://localhost:8888/api/status`
2. Check firewall settings
3. For Docker, ensure port mapping: `-p 8888:8888`

### Low Success Rate

**Symptom**: Success rate below 90% even with all nodes healthy

**Solutions**:

1. Reduce load rate
2. Increase JVM heap: `JAVA_OPTS="-Xmx4g"`
3. Check system resources (CPU, memory)

### Node Won't Join

**Symptom**: Added node stays in "joining" state

**Solutions**:

1. Check port availability for new node
2. Verify network connectivity between nodes
3. Check logs for consensus errors
