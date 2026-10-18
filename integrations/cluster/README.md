# Cluster Module

Cluster node assembly on top of the [consensus](../consensus/README.md) module: a Rabia-backed cluster node, a replicated key-value store, and cluster metrics messages.

## Overview

This module does not contain the consensus algorithm. The Rabia engine, state machine interface, leader manager and topology live in [`../consensus`](../consensus/README.md); this module wires them into a running node and provides the state machine most clusters run on it.

### Packages

- **`cluster.node`** - `ClusterNode<C>` (`self()`, `topologyManager()`, `start()`, `stop()`, `apply(List<C>)`) and its implementations: `rabia.RabiaNode` (assembles `QuicClusterNetwork`, `RabiaEngine`, leader manager and message routes, configured by `NodeConfig`), `ForwardingClusterNode` (forwards `apply` to a core peer, for worker nodes that do not take part in consensus), `SwitchableClusterNode` (switches between direct and forwarding modes at runtime)
- **`cluster.state.kvstore`** - `KVStore`, a `StateMachine` over `KVCommand` with structured keys, per-key fencing checks (epoch, owner, version, incarnation, community and leader-authority variants), and `KVStoreNotification` delivery
- **`cluster.metrics`** - wire messages for leader-independent health and connectivity observations (`ClusterSyncMessage`, `CommunityReport`, `PeerHealthObservation`, `PeerConnectivityObservation` and related types)

### Batch identity

Batch ids are content-derived: `StateMachine.createBatch` hashes the serializer-encoded command list (SHA-256), so identical command lists submitted by different nodes get the same batch id on every node, and `StateMachine.merge` combines batches that share an id. This is per-batch identity only; it makes no claim about client retries across separate submissions.

## Dependencies

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>cluster</artifactId>
    <version>1.0.0-rc5</version>
</dependency>
```

Compile-scope dependencies (from `pom.xml`), all `org.pragmatica-lite` unless noted:

- `core`
- `messaging`
- `utility`
- `consensus`
- `tcp`
- `serialization-api`
- `io.netty:netty-all`, `org.slf4j:slf4j-api`
