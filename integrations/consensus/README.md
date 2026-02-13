# Consensus Module

Rabia CFT (Crash Fault Tolerant) consensus algorithm for replicated state machines.

## Overview

Implements the [Rabia consensus algorithm](https://dl.acm.org/doi/10.1145/3477132.3483582) (published 2021), a crash-fault-tolerant protocol simpler than RAFT or Paxos that does not rely on a persistent event log. Features batch-based command processing, automatic state synchronization on node recovery, a super-majority fast path, and optional leader election (local or consensus-based).

Key components: `RabiaEngine` (consensus orchestration), `StateMachine` (user-provided replicated state), `ClusterNetwork` (node-to-node communication), `TopologyManager` (cluster topology and quorum calculations).

### Quorum Requirements

| Nodes | Quorum | Max Failures |
|-------|--------|--------------|
| 3     | 2      | 1            |
| 5     | 3      | 2            |
| 7     | 4      | 3            |

## Usage

```java
// 1. Implement your state machine
class CounterStateMachine implements StateMachine<CounterCommand> {
    @Override
    public <R> R process(CounterCommand command) { /* apply command */ }
    @Override
    public Result<byte[]> makeSnapshot() { /* serialize state */ }
    @Override
    public Result<Unit> restoreSnapshot(byte[] snapshot) { /* restore state */ }
}

// 2. Configure topology
var topologyConfig = new TopologyConfig(
    nodeId, coreNodes.size(),
    timeSpan(1).seconds(), timeSpan(10).seconds(), coreNodes);

// 3. Create and start engine
var router = MessageRouter.mutable();
var topologyManager = TcpTopologyManager.tcpTopologyManager(topologyConfig, router)
    .expect("valid topology config");
var network = new NettyClusterNetwork(topologyManager, router);
var engine = new RabiaEngine<>(topologyManager, network, stateMachine, ProtocolConfig.defaultConfig());
engine.configure(router);
engine.start().await();

// 4. Apply commands
engine.apply(List.of(new Increment())).await();
```

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

- `pragmatica-lite-tcp`
- `pragmatica-lite-messaging`
- `pragmatica-lite-serialization`
- `pragmatica-lite-core`
