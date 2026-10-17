# Distributed Hash Table (DHT)

Consistent hashing with configurable replication for distributed data storage.

## Overview

Provides a consistent hash ring with virtual nodes for even key distribution, configurable replication factor and quorum settings, and pluggable storage engines. Uses a message-based protocol for distributed operations integrated with `MessageRouter`.

The hash ring uses 150 virtual nodes per physical node and 1024 partitions by default.

## Usage

### Consistent Hash Ring

```java
import org.pragmatica.dht.ConsistentHashRing;

var ring = ConsistentHashRing.<String>consistentHashRing();
ring.addNode("node-1");
ring.addNode("node-2");
ring.addNode("node-3");

// Find primary node for a key
Option<String> primary = ring.primaryFor("user:123");

// Get nodes for replication (primary + replicas)
List<String> nodes = ring.nodesFor("user:123", 3);
```

### DHT Node

```java
// node ids are NodeId values and the ring is a ConsistentHashRing<NodeId>
var nodeId = NodeId.nodeId("node-1").unwrap();
var node = DHTNode.dhtNode(nodeId, storage, ring, DHTConfig.DEFAULT);

// Local operations
node.putLocal("key".getBytes(), "value".getBytes()).await();
Option<byte[]> value = node.getLocal("key".getBytes()).await();
```

### Configuration

```java
DHTConfig config = DHTConfig.DEFAULT;              // 3 replicas, quorum of 2
DHTConfig custom = DHTConfig.dhtConfig(5, 3, 3).unwrap();  // replication factor 5, write quorum 3, read quorum 3 (validated)
DHTConfig full = DHTConfig.FULL;                   // All nodes store everything
DHTConfig single = DHTConfig.SINGLE_NODE;          // For testing
```

### Consistency per mode

Replication factor is not the same as consistency — choose a mode by the guarantee it earns, not by replica count:

| Mode | Quorum | Consistency | Notes |
|------|--------|-------------|-------|
| `DEFAULT` / `dhtConfig(n, w, r)` | `DEFAULT`: RF=3, W=R=2 | **Not linearizable.** A read collects R responses and returns the entry newest by (owner epoch, version), tombstones included | `hasQuorumOverlap()` is a config check, not a runtime guarantee: nothing at runtime enforces `W+R > N`, so the R replicas read can all miss the latest acked write. On a quorum miss, a bounded fallback probes ring members outside the read set and read-repairs a stranded copy back; there is no read-repair on the normal hit path |
| `FULL` | W=R=1 | **Eventually consistent, NOT linearizable** | A write acks after one local put; a read takes the first response (R=1), so there is no cross-replica reconciliation. `FULL` also disables anti-entropy and rebalancing — a write lost before async replication is not repaired |
| `SINGLE_NODE` | W=R=1, RF=1 | Single-replica | Testing only |

The mechanisms are `DistributedDHTClient.get` with `QuorumCollector.newestEntryCollector`, and `DHTConfig.hasQuorumOverlap()` / `DHTConfig.FULL` for the configuration side. Callers that need freshness or crash-durability from `FULL` must not assume it — see the downstream consumer's own guarantees (e.g. Aether's system maps run over `FULL` and are documented as eventual + not crash-durable).

## API surface & compatibility

The supported public API of this module is the client-facing surface: `DHTClient`,
`DHTNode`, `DHTConfig`, `ConsistentHashRing`, and the storage SPI (`StorageEngine`).
Source/binary compatibility promises apply to these types only.

Wire-protocol types (`DHTMessage` and its variants, and any type exchanged between
nodes) are **internal**, public only for transport wiring. They may gain variants or
fields in any release without notice; exhaustive switches or direct constructions in
external code are unsupported. All nodes of a cluster must run the same release —
mixed-version clusters are not supported (no protocol version negotiation yet; see
the protocol-versioning tracking issue for GA plans).

## Dependencies

```xml
<dependency>
    <groupId>org.pragmatica-lite</groupId>
    <artifactId>dht</artifactId>
    <version>1.0.0-rc4</version>
</dependency>
```

Compile-scope dependencies (from `pom.xml`), all `org.pragmatica-lite`:

- `core`
- `messaging`
- `consensus`
- `serialization-api`
