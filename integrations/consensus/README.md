# Consensus Module

Rabia CFT (Crash Fault Tolerant) consensus algorithm for replicated state machines.

## Overview

Implements the [Rabia consensus algorithm](https://dl.acm.org/doi/10.1145/3477132.3483582) (published 2021), a crash-fault-tolerant protocol simpler than RAFT or Paxos. The engine's persistence is pluggable through `RabiaPersistence`; the default is in-memory (`RabiaPersistence.inMemory()`), so durability across restarts is whatever the persistence you supply provides. Features batch-based command processing, automatic state synchronization on node recovery, a super-majority fast path, and optional leader election (local or consensus-based).

Key components: `RabiaEngine` (consensus orchestration), `StateMachine` (user-provided replicated state), `ClusterNetwork` (node-to-node communication), `TopologyManager` (cluster topology and quorum calculations).

### Quorum Requirements

| Nodes | Quorum | Max Failures |
|-------|--------|--------------|
| 3     | 2      | 1            |
| 5     | 3      | 2            |
| 7     | 4      | 3            |

Crash faults only. With fewer than a quorum of nodes alive, commands are not decided until a quorum returns.

## Usage

```java
// 1. Implement your state machine
record Increment() implements Command {}

class CounterStateMachine implements StateMachine<Increment> {
    @Override
    public <R> List<R> process(Batch<Increment> batch) { /* apply each command in order, return one result per command */ }
    @Override
    public Result<byte[]> makeSnapshot() { /* serialize state */ }
    @Override
    public Result<Unit> restoreSnapshot(byte[] snapshot) { /* restore state */ }
    @Override
    public Unit reset() { /* back to initial state */ }
    @Override
    public Serializer serializer() { /* used for snapshots and for the deterministic batch id */ }
}

// 2. Configure topology
var topologyConfig = new TopologyConfig(
    nodeId, coreNodes.size(),
    timeSpan(1).seconds(), timeSpan(10).seconds(), coreNodes);

// 3. Create and start engine
var router = MessageRouter.mutable();
var topologyManager = TopologyObserver.topologyObserver(topologyConfig, router)
    .expect("valid topology config");
// QuicClusterNetwork additionally takes serializers + QUIC SSL contexts — see its constructors.
var network = new QuicClusterNetwork(topologyManager, serializer, deserializer, router,
                                     serverSslContext, clientSslContext);
var engine = new RabiaEngine<>(topologyManager, network, stateMachine, ProtocolConfig.defaultConfig());
engine.start().await();

// 4. Apply commands
engine.apply(List.of(new Increment())).await();
```

This sketch omits the message-routing table that delivers network and topology messages to the engine. The complete wiring (`QuicClusterNetwork`, `ConsensusBridge`, the quorum-presence router, routes for the network and topology message families) is `RabiaNode` in [`../cluster`](../cluster/src/main/java/org/pragmatica/cluster/node/rabia/RabiaNode.java).

### Leader Election

```java
// Local election (smallest node ID wins)
var leaderManager = LeaderManager.leaderManager(nodeId, router);

// Consensus-based election
var leaderManager = LeaderManager.leaderManager(nodeId, router, proposalHandler);

// Query leader
Option<NodeId> leader = leaderManager.leader();
boolean isLeader = leaderManager.isLeader();
```

## Dependencies

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>consensus</artifactId>
    <version>1.0.0-rc5</version>
</dependency>
```

Compile-scope dependencies (from `pom.xml`), all `org.pragmatica-lite`:

- `core`
- `messaging`
- `utility`
- `tcp`
- `statemachine`
- `serialization-api`
