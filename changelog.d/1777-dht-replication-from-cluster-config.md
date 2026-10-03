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
    settled. Writers switched means every member the leader's membership view counts (cores, workers, reporters;
    members it holds `Dead` excluded) reported the change applied. Each core then runs a fresh catch-up pass. Settled
    means every core reported that pass complete. Every
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
  - **Write fence (CTO ruling R1c):** every put carries the writer's applied replication change, and a replica that
    has applied a newer one refuses it with the retryable `ReplicationChangeStale`. A put in flight across the whole
    change, or from a writer the roster missed, never lands an old-quorum write after the settle
    [verified: DHTReplicationChangeTest `straddlingPut_startedUnderTheOldFactors_isRefusedAfterTheChange_andTheRetrySucceeds`
    (v1882's round-3 probe), `writerExcludedFromTheSettle_isRefused_untilItAppliesTheChange`,
    `writerTaughtTheOldChange_isFencedByReplicasOnTheNewOne`]. The roster is the leader's membership view: a wrong one
    only delays or hastens the settle.
  - The two events are published at most once per transition (missed if the cluster-events owner cannot publish then).
  - A replica restarted after a change refuses writes (`ReplicationFenceUnknown`, retryable) until its state is restored
    and consensus has applied its log tail: an unknown fence refuses, never accepts [verified: DHTReplicationChangeTest
    `restartedReplica_refusesWrites_untilItHasAdoptedTheCommittedChange`,
    aether/node/src/test/java/org/pragmatica/aether/node/DhtReplicationFenceRestoreTest.java]. That refusal marks no writer
    stale, and any accepted write ends a stale episode [verified: `healthyWriterRefusedByUnknownFences_recordsNoStaleness`,
    `acceptedWrite_clearsARecordedStaleRefusal`].
  - A node whose writes the fence keeps refusing for over 5 minutes, without it adopting the change, emits
    `DHT_WRITER_STALE` itself, and `DHT_WRITER_STALE_RESOLVED` once it adopts (at most once each)
    [verified: aether/node/src/test/java/org/pragmatica/aether/node/DhtWriterStaleWatchTest.java].
  - Removes are not fenced in this change; the remove/tombstone fence lands with the durable deletes (#1777 track 3).
- **Idempotency stores its dedup records in the replicated DHT at the `[replication]` factors**, not at the cache's
  RF 1. It was resolving the cache-scoped client.
  [verified: aether/resource/interceptors/src/test/java/org/pragmatica/aether/resource/interceptor/DhtNamespaceExtensionTest.java]
- A node refuses DHT operations with a retryable `ReplicationUnresolved` until it has read the committed factors after
  its consensus state is restored, rather than guessing a factor that could read fewer replicas than the cluster
  writes to.
- **Breaking:** `[dht.replication] target_rf` is removed, and a node config that still sets it is refused at load.
