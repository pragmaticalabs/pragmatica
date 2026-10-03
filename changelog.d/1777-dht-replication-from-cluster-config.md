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
  - Every node, workers included, uses the transitional quorums W = max(W_old, W_new) and R = max(R_old, R_new),
    capped at the new RF, until the cluster COMMITS the change as settled. No node switches on its own catch-up.
  - **Settle:** the leader moves the committed change record through three stages: applying, writers switched, and
    settled. Writers switched means every core and every worker that is not confirmed departed reported the change
    applied. Each core then runs a fresh catch-up pass. Settled means every core reported that pass complete. Every
    stage is a compare-and-set leader transaction, and every report is checked against the change version, so a
    report for an older change never advances a newer one
    [verified: aether/node/src/test/java/org/pragmatica/aether/node/DhtReplicationSettlementTest.java].
  - **Read of a value acked under the old factors:** the value, or a retryable `NotCaughtUp`, never "absent", for a
    CF raise at fixed RF and for an RF decrease, without the #428 fallback
    [verified: integrations/dht/src/test/java/org/pragmatica/dht/DHTReplicationChangeTest.java, in-JVM 3- and 5-node].
  - **Read, by a node that has finished its own catch-up, of a value a slower node wrote at the old quorum:** the
    value [verified: same test, `laggingWriter_atOldW_isReadByAnApplierThatCaughtUp_becauseNothingIsSettled` (v1882's
    probe scenario) and `writeAtOldW_afterTheFirstPass_isOnEveryReplicaBeforeTheChangeSettles`].
  - **Liveness:** a member that never reports keeps the change unsettled. Reads stay at the stricter quorum, so they
    become unavailable sooner, but they never return a false "absent". A change unsettled for 5 minutes emits
    `DHT_REPLICATION_UNSETTLED`, and its settling or replacement emits `DHT_REPLICATION_SETTLED`.
  - [unverified: a worker that the leader's membership has not yet seen, and that has not reported, is not waited for.]
- **Idempotency stores its dedup records in the replicated DHT at the `[replication]` factors**, not at the cache's
  RF 1. It was resolving the cache-scoped client.
  [verified: aether/resource/interceptors/src/test/java/org/pragmatica/aether/resource/interceptor/DhtNamespaceExtensionTest.java]
- A node refuses DHT operations with a retryable `ReplicationUnresolved` until it has read the committed factors after
  its consensus state is restored, rather than guessing a factor that could read fewer replicas than the cluster
  writes to.
- **Breaking:** `[dht.replication] target_rf` is removed, and a node config that still sets it is refused at load.
