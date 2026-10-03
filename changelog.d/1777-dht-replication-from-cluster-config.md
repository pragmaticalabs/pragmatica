### Changed (2026-10-03 — #1777 track 1: the DHT takes its replication from the cluster's `[replication]` section)
- **The DHT's replication factor was a node-local setting** (`[dht.replication] target_rf`, majority quorums), so two
  nodes with different files disagreed on placement, and the DHT ignored the cluster-wide `[replication]` defaults
  that streams, durable topics and durable entities use.
- The DHT now resolves RF and CF from the committed `[replication]` section: a write is acknowledged by CF replicas and
  a read asks RF − CF + 1 [mechanism: R + W = RF + 1 > RF, the same factors on every node from consensus state]. The
  cache namespace declares its own factors in the new committed `[cache]` section (default RF 1, CF 1).
- A committed change applies live: the keyspace is re-placed through the catch-up gate, and a read in the window
  answers the value or a retryable `NotCaughtUp`, never "absent"
  [verified: integrations/dht/src/test/java/org/pragmatica/dht/DHTReplicationResolutionTest.java, in-JVM five-node].
- A node refuses DHT operations with a retryable `ReplicationUnresolved` until it has read the committed factors after
  its consensus state is restored, rather than guessing a factor that could read fewer replicas than the cluster
  writes to.
- **Breaking:** `[dht.replication] target_rf` is removed, and a node config that still sets it is refused at load.
