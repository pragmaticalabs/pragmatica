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
Optional<String> primary = ring.primaryFor("user:123");

// Get nodes for replication (primary + replicas)
List<String> nodes = ring.nodesFor("user:123", 3);
```

### DHT Node

```java
var node = DHTNode.dhtNode("node-1", storage, ring, DHTConfig.DEFAULT);

// Local operations
node.putLocal("key".getBytes(), "value".getBytes()).await();
Option<byte[]> value = node.getLocal("key".getBytes()).await();
```

### Configuration

```java
DHTConfig config = DHTConfig.DEFAULT;              // 3 replicas, quorum of 2
DHTConfig custom = DHTConfig.withReplication(5);   // 5 replicas, quorum of 3
DHTConfig full = DHTConfig.FULL;                   // All nodes store everything
DHTConfig single = DHTConfig.SINGLE_NODE;          // For testing
```

### Consistency per mode

Replication factor is not the same as consistency — choose a mode by the guarantee it earns, not by replica count:

| Mode | Quorum | Consistency | Notes |
|------|--------|-------------|-------|
| `DEFAULT` / `withReplication(n)` | W=R=majority | Quorum overlap (`W+R > N`) — **necessary but not sufficient** for freshness | No read-repair: the read set may still return a stale replica (`hasQuorumOverlap()` is a config check, not a runtime guarantee) |
| `FULL` | W=R=1 | **Eventually consistent, NOT linearizable** | A write acks after one local put; a read returns the first non-empty response with no version reconciliation. `FULL` also disables anti-entropy and rebalancing — a write lost before async replication is not repaired |
| `SINGLE_NODE` | W=R=1, RF=1 | Single-replica | Testing only |

The authoritative contract for each mode lives in the `DHTConfig` javadoc (`DHTConfig.FULL`, `hasQuorumOverlap()`). Callers that need freshness or crash-durability from `FULL` must not assume it — see the downstream consumer's own guarantees (e.g. Aether's system maps run over `FULL` and are documented as eventual + not crash-durable).

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

- `pragmatica-lite-messaging`
- `pragmatica-lite-core`
