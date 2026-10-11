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
  - **A writer that is itself a replica** cannot meet an old quorum with its own unfenced local slot (v1882 r6 F10): the put
    is acknowledged only on remote evidence (a success; any reply other than a success or a stale refusal keeps it waiting), any `ReplicationChangeStale` refusal seen first fails it, and a put
    refused as stale never clears the stale-writer record. The writer's own slot is applied only AFTER the evidence arrives, so a
    stale put never writes it and nothing is undone (an apply-then-undo left a concurrent current writer's ack resting on a copy
    that was later rolled back). A refusal after the acknowledgement is recorded, not revoked. Cost: a writer that is a replica
    applies its own copy one round trip later than before
    [verified: DHTReplicationChangeTest `v1882r6_excludedWriterThatIsAReplica_localSlotAcceptsAtWold`,
    `v1882r10_orderA_…`, `v1882r10_orderB_…`, `v1882r10_probeS_…`, `v1882r7_orderC_…`]
    [verified: `v1882r9_orderE_nonStaleRefusalFirst_thenStale_…`, `v1882r9_nonStaleRefusalFirst_thenSuccess_acks`]
    The wait for that evidence is one tenth of the operation timeout (3 s by default), so a partitioned replica set does not
    stall every W=1 write for the whole timeout [verified: `v1882r9b_allRemotesSilent_acksWithinTheEvidenceWait_…`]
    An owner-epoch fence refusal is evidence like a stale one, and failure evidence ends the operation at once with its typed
    failure (no waiting for a silent replica, no local apply); the gate releases once every REMOTE slot has replied
    [verified: `v1882r11_probeU_…`, `DHTDeposedWriterSameEpochTest`]
    [limit: inside the no-evidence window two writers can each apply their own slot and a late fence refusal rolls one back,
    leaving an acknowledged write that counted it as "superseded" on one replica; pinned as an enabled tripwire
    `DHTDeposedWriterSameEpochTest.residual_noEvidenceWindow_…`; #1683-class]
    [limit: with NO evidence — every remote silent, down or fence-unknown until every remote replied or the
    wait runs out — the put is acknowledged on the local slot and sets no stale record; a replica on the newer change that
    does not answer within the wait (slow, GC-paused, partitioned) cannot refute it (`v1882r7_orderD_…`,
    `v1882r9_allRemoteRepliesNonStale_…`); #1683-class]. The #1818 rollback (a put that lost its quorum to owner-epoch
    fences) restores the entry the write displaced, read in the same step as the write, instead of deleting it
    [verified: `DHTDeposedWriterRollbackTest.deposedOwnerWrite_overwritingALocalPrior_isRestoredToThePrior_notDeleted`].
    [unverified: M7, the consensus-caught-up wiring replaced by `true`, stays green over 2325 aether/node tests; the unsafe
    direction (never pending, so the fence is confirmed early) is the restore-prefix residual, [limit: #1683]]
    [unverified: the guard that keeps a late refusal from being cleared by the acknowledgement's own clear step is not
    pinned: that race is not reachable in the in-JVM harness].
  - The two events are published at most once per transition (missed if the cluster-events owner cannot publish then).
  - A replica restarted after a change refuses writes (`ReplicationFenceUnknown`, retryable) until its state is restored
    and consensus reports no catch-up pending: an unknown fence refuses, never accepts [verified: DHTReplicationChangeTest
    `restartedReplica_refusesWrites_untilItHasAdoptedTheCommittedChange`,
    aether/node/src/test/java/org/pragmatica/aether/node/DhtReplicationFenceRestoreTest.java]. That refusal marks no writer
    stale, and any accepted write ends a stale episode [verified: `healthyWriterRefusedByUnknownFences_recordsNoStaleness`,
    `acceptedWrite_clearsARecordedStaleRefusal`]. The catch-up signal sees only log positions the node has been told
    about, so a committed change in a tail it has not yet received is not detected
    [unverified: no run shows a confirmed fence older than the committed change] [limit: #1683].
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
