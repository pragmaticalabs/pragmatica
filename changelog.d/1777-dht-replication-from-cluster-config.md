### Changed (2026-10-03 — #1777 track 1: the DHT takes its replication from the cluster's `[replication]` section)
- **The DHT's replication factor was a node-local setting** (`[dht.replication] target_rf`, majority quorums), so two
  nodes with different files disagreed on placement, and the DHT ignored the cluster-wide `[replication]` defaults
  that streams, durable topics and durable entities use.
- The DHT now resolves RF and CF from the committed `[replication]` section: a write is acknowledged by CF replicas and
  a read asks RF − CF + 1 [mechanism: R + W = RF + 1 > RF, the same factors on every node from consensus state]. The
  cache namespace declares its own factors in the new committed `[cache]` section (default RF 1, CF 1).
- A committed change applies live, per operation:
  - ANY change of RF or CF re-opens the catch-up gate on every partition a node replicates, not only on the partitions
    it gains.
  - Until the node has caught up, its operations use the transitional quorums W = max(W_old, W_new) and
    R = max(R_old, R_new), capped at the new RF.
  - **Read of a value acked under the old factors:** the value, or a retryable `NotCaughtUp`, never "absent", for a
    CF raise at fixed RF and for an RF decrease, without the #428 fallback
    [verified: integrations/dht/src/test/java/org/pragmatica/dht/DHTReplicationChangeTest.java, in-JVM 3- and 5-node].
  - **Read by a node still settling of a value written at the old quorum by a node that has not applied the change:**
    the value [verified: same test, `laggingWriterAtTheOldQuorum_isReadByASettlingCoordinator`].
  - **The same read by a node that has already finished its own catch-up:** [design intent — unverified]. The switch
    back to the new quorums is per node, so it is not covered for writes by a node that has not yet applied the change.
    A worker has no partitions and switches as it applies the change.
- **Idempotency stores its dedup records in the replicated DHT at the `[replication]` factors**, not at the cache's
  RF 1. It was resolving the cache-scoped client.
  [verified: aether/resource/interceptors/src/test/java/org/pragmatica/aether/resource/interceptor/DhtNamespaceExtensionTest.java]
- A node refuses DHT operations with a retryable `ReplicationUnresolved` until it has read the committed factors after
  its consensus state is restored, rather than guessing a factor that could read fewer replicas than the cluster
  writes to.
- **Breaking:** `[dht.replication] target_rf` is removed, and a node config that still sets it is refused at load.
