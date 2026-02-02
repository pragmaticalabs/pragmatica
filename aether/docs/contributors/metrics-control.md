# Metrics Collection and Cluster Control Architecture

This document defines the metrics collection strategy and cluster controller architecture for Aether.

**See [vision-and-goals.md](../archive/vision-and-goals.md) for overall vision.**

## Overview

Aether uses a **push-based metrics collection** with **leader aggregation and broadcast** model. All cluster control
decisions (scaling, deployment) are made by a pluggable **Cluster Controller** that can be:

- Decision tree (deterministic, fast)
- Small local LLM (semi-autonomous)
- Large cloud LLM (strategic)

## Metrics Collection Architecture

### Core Principle

**Zero consensus I/O during normal operation**. All metrics flow through MessageRouter, never touch KV-Store.

### Collection Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                    Every 1 Second Cycle                          │
└─────────────────────────────────────────────────────────────────┘

Step 1: Leader Sends MetricsPing to All Nodes
┌──────────────┐
│   Leader     │ ─────> MetricsPing (includes leader metrics)
│   (Node A)   │                         │
└──────────────┘                         │
                                         ▼
                              ┌─────────────────┐
                              │  Node 1, 2, 3   │
                              └─────────────────┘

Step 2: Nodes Respond with MetricsPong
┌─────────┐                                    ┌──────────────┐
│ Node 1  │ ─────> MetricsPong ──────────────> │              │
└─────────┘                                    │              │
┌─────────┐                                    │   Leader     │
│ Node 2  │ ─────> MetricsPong ──────────────> │   (Node A)   │
└─────────┘                                    │              │
┌─────────┐                                    │              │
│ Node 3  │ ─────> MetricsPong ──────────────> │              │
└─────────┘                                    └──────────────┘

Benefits:
- All nodes store received metrics from ping/pong exchange
- Each node maintains 2-hour sliding window of historical data
- Any node can become leader (already has aggregated state)
- Leader failure = new leader has recent snapshot from last ping
- Fast recovery (new leader continues from last broadcast)
```

### Message Definitions

#### MetricsPing (Leader → All Nodes)

```java
record MetricsPing(
    NodeId sender,           // Leader node ID
    Map<String, Double> metrics  // Leader's own metrics
) {}
```

**Frequency**: Every 1 second
**Transport**: MessageRouter (non-blocking send)

#### MetricsPong (Node → Leader)

```java
record MetricsPong(
    NodeId sender,           // Responding node ID
    Map<String, Double> metrics  // Node's local metrics
) {}
```

**Metrics map includes:**
- `cpu.usage` - CPU utilization (0.0 to 1.0)
- `heap.used`, `heap.max`, `heap.usage` - JVM heap metrics
- `method.{name}.calls` - Call count per method
- `method.{name}.duration.total` - Total duration per method (ms)
- `method.{name}.duration.avg` - Average duration per method (ms)
- Custom slice-defined metrics

**Size**: ~100-500 bytes depending on method count

### Leader Failover Recovery

When new leader is elected:

```
1. New leader checks locally stored metrics from MetricsPong responses
   - If recent (< 2 seconds old): Continue using it
   - If stale: Request fresh snapshot from all nodes

2. If fresh snapshot needed:
   - Broadcast MetricsSnapshotRequest to all nodes
   - Nodes respond with their current counters
   - Leader aggregates and broadcasts first snapshot

3. Total recovery time: 1-2 RTTs (~10-100ms in LAN)
```

**Data Loss on Failover**: 1-2 update cycles (1-2 seconds) - Acceptable per requirements.

### Sliding Window Management

Leader maintains in-memory sliding window:

- **Window Size**: 2 hours (7,200 data points at 1 Hz)
- **Memory Footprint**: ~1-2 MB for typical cluster (10 nodes, 50 entry points)
- **Eviction**: FIFO when window full

Window used for:

- Trend detection (increasing/decreasing load)
- Pattern recognition (daily cycles, bursts)
- Anomaly detection (sudden spikes)

## Cluster Controller Architecture

### Controller Types

The Cluster Controller makes all topology decisions. Three implementations:

#### 1. Decision Tree Controller

**Use Case**: Deterministic, fast, no external dependencies

**Characteristics**:

- Runs on leader node
- Evaluates every metrics cycle (1 second)
- Configurable rules and thresholds
- Zero latency decisions

**Example Rules**:

```
IF avg_cpu > 0.8 AND trend = increasing
   THEN scale_up(artifact, instances + 1)

IF call_rate > 1000/sec AND p95_latency > 100ms
   THEN scale_up(artifact, instances * 2)

IF avg_cpu < 0.3 AND stable_for(10 minutes)
   THEN scale_down(artifact, instances - 1)
```

#### 2. Small Local LLM Controller

**Use Case**: Semi-autonomous, local, pattern learning

**Characteristics**:

- Runs on leader node or dedicated local machine
- Evaluates every 2-5 cycles (2-5 seconds)
- Learns patterns over time
- Low latency (~500ms-2s decision time)

**Model Suggestions**: Llama 3 8B, Mistral 7B, Phi-3 Mini

#### 3. Large Cloud LLM Controller

**Use Case**: Strategic, sophisticated, long-term optimization

**Characteristics**:

- External API call (OpenAI, Anthropic, Gemini)
- Evaluates every 30-60 cycles (30-60 seconds)
- Deep pattern analysis
- Higher latency but better decisions

**Model Suggestions**: GPT-4, Claude Opus, Gemini Ultra

### Layered Strategy

Controllers can be **composed in layers** for hybrid intelligence:

```
┌──────────────────────────────────────────────────────────────┐
│ Layer 1: Decision Tree (every 1 sec)                         │
│ - Handle immediate issues (CPU spike → scale up)             │
│ - Fast reactive responses                                    │
└───────────────────────────────┬──────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ Layer 2: Small LLM (every 2-5 sec)                           │
│ - Validate decision tree actions                             │
│ - Detect patterns (daily cycles, weekly trends)              │
│ - Override reactive decisions if needed                      │
└───────────────────────────────┬──────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────┐
│ Layer 3: Large LLM (every 30-60 sec)                         │
│ - Strategic planning (multi-cloud, cost optimization)        │
│ - Complex deployments (canary, blue/green)                   │
│ - Long-term capacity planning                                │
└──────────────────────────────────────────────────────────────┘

Each layer can veto or modify decisions from lower layers.
```

### Controller Interface

```java
public interface ClusterController {

    /**
     * Process cluster state and make control decisions.
     * Called by leader node at configured frequency.
     */
    Promise<ControlDecisions> evaluate(ControlContext context);

    /**
     * Context provided to controller for decision making.
     */
    record ControlContext(
        Map<NodeId, Map<String, Double>> currentMetrics,
        Map<NodeId, List<MetricsSnapshot>> historicalMetrics,  // Sliding window
        List<ClusterEvent> recentEvents,
        ClusterTopology topology,
        Map<Artifact, Blueprint> currentBlueprints
    ) {}

    /**
     * Decisions made by controller.
     */
    record ControlDecisions(
        List<BlueprintChange> blueprintChanges,
        List<NodeAction> nodeActions,
        Option<String> reasoning  // For LLM transparency
    ) {}

    sealed interface BlueprintChange {
        record ScaleSlice(Artifact artifact, int newInstanceCount) implements BlueprintChange {}
        record DeploySlice(Artifact artifact, int instances) implements BlueprintChange {}
        record RemoveSlice(Artifact artifact) implements BlueprintChange {}
        record UpdateSlice(Artifact artifact, Version newVersion) implements BlueprintChange {}
    }

    sealed interface NodeAction {
        record StartNode(CloudProvider provider, Region region, MachineType type) implements NodeAction {}
        record StopNode(NodeId nodeId, boolean graceful) implements NodeAction {}
        record MigrateSlices(NodeId fromNode, NodeId toNode, List<Artifact> slices) implements NodeAction {}
    }
}
```

## Cluster Events

Controllers receive events about cluster state changes in addition to metrics.

### Event Types

```java
sealed interface ClusterEvent {
    long timestamp();

    // Node lifecycle events
    record NodeJoined(NodeId nodeId, NodeCapabilities capabilities, long timestamp) implements ClusterEvent {}
    record NodeLeft(NodeId nodeId, Reason reason, long timestamp) implements ClusterEvent {}
    record NodeFailed(NodeId nodeId, Cause cause, long timestamp) implements ClusterEvent {}

    // Leadership events
    record LeaderChanged(Option<NodeId> oldLeader, NodeId newLeader, long timestamp) implements ClusterEvent {}

    // Slice lifecycle events
    record SliceActivated(NodeId nodeId, Artifact artifact, int instance, long timestamp) implements ClusterEvent {}
    record SliceDeactivated(NodeId nodeId, Artifact artifact, int instance, long timestamp) implements ClusterEvent {}
    record SliceFailed(NodeId nodeId, Artifact artifact, int instance, Cause cause, long timestamp) implements ClusterEvent {}

    // Blueprint events
    record BlueprintPublished(String name, Blueprint blueprint, Source source, long timestamp) implements ClusterEvent {}
    record BlueprintUpdated(String name, Blueprint oldBlueprint, Blueprint newBlueprint, long timestamp) implements ClusterEvent {}
    record BlueprintDeleted(String name, Blueprint blueprint, long timestamp) implements ClusterEvent {}

    // Consensus events
    record ValuePutCommitted(StructuredKey key, String value, long timestamp) implements ClusterEvent {}
    record ValueRemovedCommitted(StructuredKey key, long timestamp) implements ClusterEvent {}

    enum Reason { GRACEFUL_SHUTDOWN, NETWORK_PARTITION, CRASH, UNKNOWN }
    enum Source { HUMAN_OPERATOR, CONTROLLER_DECISION, EXTERNAL_API }
}
```

### Event Distribution

Events flow through dedicated `ClusterEventBus`:

```
┌─────────────────────────────────────────────────────────────┐
│                    Event Producers                           │
│  - NodeDeploymentManager (slice lifecycle)                   │
│  - LeaderManager (leadership changes)                        │
│  - TopologyManager (node join/leave)                         │
│  - ClusterDeploymentManager (blueprint changes)              │
│  - KVStore (consensus commits)                               │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
                ┌────────────────────────┐
                │  ClusterEventBus       │
                │  (MessageRouter based) │
                └───────────┬────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    Event Consumers                           │
│  - ClusterController (for decision making)                   │
│  - MetricsShaper (for correlation with metrics)              │
│  - AuditLogger (for compliance)                              │
│  - AlertManager (for notifications)                          │
└─────────────────────────────────────────────────────────────┘
```

**Key Properties**:

- Events never stored in KV-Store (ephemeral)
- In-memory only (recent events buffered)
- Delivered via MessageRouter
- Controllers get event stream in ControlContext

## Integration with Cluster Deployment

### Control Loop on Leader Node

```java
// Pseudocode for leader control loop

class LeaderControlLoop {
    private final ClusterController controller;
    private final MetricsAggregator metricsAggregator;
    private final ClusterEventBus eventBus;
    private final ClusterDeploymentManager deploymentManager;

    void run() {
        while (isLeader) {
            // 1. Wait for next cycle (1 second)
            await(nextCycle());

            // 2. Aggregate latest metrics
            var currentMetrics = metricsAggregator.getCurrentSnapshot();

            // 3. Broadcast to all nodes
            broadcast(currentMetrics);

            // 4. Check if controller should evaluate
            if (shouldEvaluate(controller)) {
                // 5. Prepare context
                var context = new ControlContext(
                    currentMetrics,
                    metricsAggregator.getHistoricalWindow(),
                    eventBus.getRecentEvents(since = lastEvaluation),
                    topology.getCurrentTopology(),
                    deploymentManager.getCurrentBlueprints()
                );

                // 6. Get controller decisions
                controller.evaluate(context)
                    .onSuccess(decisions -> applyDecisions(decisions))
                    .onFailure(cause -> logControllerError(cause));
            }
        }
    }

    boolean shouldEvaluate(ClusterController controller) {
        return switch (controller) {
            case DecisionTreeController -> true;  // Every cycle
            case SmallLLMController -> cycleCount % 2 == 0;  // Every 2 cycles
            case LargeLLMController -> cycleCount % 30 == 0;  // Every 30 cycles
        };
    }

    void applyDecisions(ControlDecisions decisions) {
        // Translate decisions to KV-Store updates
        for (var change : decisions.blueprintChanges()) {
            deploymentManager.applyBlueprintChange(change);
        }

        for (var action : decisions.nodeActions()) {
            nodeLifecycleManager.executeAction(action);
        }
    }
}
```

## Performance Characteristics

### Network Overhead

**Per Node** (10 entry points):

- Outbound: 100 bytes/sec (MetricsPong at 1 Hz)
- Inbound: 100 bytes/sec (MetricsPing from leader)
- Total: 400 bytes/sec per node

**Cluster-wide** (10 nodes):

- Total traffic: 4 KB/sec
- Negligible for modern networks (< 0.001% of 1 Gbps)

### CPU Overhead

**Per Node**:

- Metrics collection: < 0.1% CPU
- Message serialization: < 0.1% CPU
- Total: < 0.5% CPU

**Leader Node**:

- Aggregation: < 1% CPU
- Decision tree: < 1% CPU
- LLM inference (local): 5-20% CPU (depending on model)
- Total: 2-22% CPU (acceptable)

### Memory Overhead

**Per Node**:

- Recent snapshot: ~1 KB
- Event buffer (100 events): ~10 KB
- Total: ~20 KB

**Leader Node**:

- Sliding window (2 hours): ~1-2 MB
- Event history: ~50 KB
- Controller state: 1-10 MB (depending on type)
- Total: 2-15 MB (negligible)

### Latency

**Metrics propagation**: 1-2 ms (within datacenter)
**Leader failover recovery**: 10-100 ms
**Decision tree evaluation**: < 1 ms
**Small LLM evaluation**: 500-2000 ms
**Large LLM evaluation**: 2000-5000 ms

## Failure Scenarios

### Node Fails During Metrics Push

- Leader doesn't receive update
- Leader marks node as unhealthy after 3 missed updates (3 seconds)
- Metrics aggregation excludes failed node
- Controller informed via NodeFailed event

### Leader Fails Mid-Cycle

- New leader elected by consensus
- New leader has stored metrics from last ping/pong cycle (< 2 seconds old)
- New leader requests fresh snapshot from all nodes
- Control loop resumes within 1-2 cycles

### Network Partition

- Nodes can't reach leader
- Nodes retain last snapshot (useful for local decisions)
- When partition heals, nodes resync with new leader

### Controller Crashes/Hangs

- Watchdog timer detects hang (configurable timeout)
- Controller restarted or replaced with simpler fallback
- Decision tree can always serve as safe fallback

## Configuration

### Metrics Collection

```yaml
metrics:
  collection_interval: 1s
  sliding_window_duration: 2h
  snapshot_timeout: 500ms
  max_missed_updates: 3
```

### Controller

```yaml
controller:
  type: layered  # or: decision_tree, small_llm, large_llm

  decision_tree:
    evaluation_interval: 1s
    rules_file: /etc/aether/rules.yaml

  small_llm:
    evaluation_interval: 5s
    model_path: /models/mistral-7b
    max_tokens: 1024
    temperature: 0.3

  large_llm:
    evaluation_interval: 60s
    provider: openai  # or: anthropic, google
    model: gpt-4-turbo
    max_tokens: 2048
    api_key_env: OPENAI_API_KEY
```

## Next Steps

1. Implement MetricsCollector on each node
2. Implement MetricsAggregator on leader
3. Implement ClusterEventBus
4. Implement DecisionTreeController (simplest, first)
5. Design decision tree rule language
6. Implement LLM controllers later

---

This architecture ensures:

- ✅ Zero consensus I/O for metrics
- ✅ Fast recovery on leader failover
- ✅ All nodes have cluster-wide view
- ✅ Pluggable controller strategy
- ✅ Events integrated with metrics
- ✅ Layered intelligence possible
