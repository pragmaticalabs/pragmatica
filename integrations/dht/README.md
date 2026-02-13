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

## Dependencies

- `pragmatica-lite-messaging`
- `pragmatica-lite-core`
