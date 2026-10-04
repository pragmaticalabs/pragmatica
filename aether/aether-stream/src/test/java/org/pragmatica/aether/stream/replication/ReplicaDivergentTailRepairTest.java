// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

/// #1730 phase 2 (KIP-101): a replica whose log holds records the committed owner never had, or holds different ones
/// at the same offsets, is repaired by cutting its tail back to the last offset it shares with the owner and fetching
/// the owner's records, instead of being quarantined for good. Runs the real orchestrator against a real replica
/// manager with a WAL; the owner is a fake catch-up transport serving a fixed log.
///
/// Before the repair, a replica that had completed backfill ABOVE the owner's head was marked CAUGHT_UP at its own
/// higher offset without a single comparison, and the owner then credited that offset as a confirmation for records
/// the replica never received ([DefaultReplicationManager#peersAtOrAbove] reads the registry).
class ReplicaDivergentTailRepairTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
    private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
    private static final List<NodeId> MEMBERS = List.of(NODE_AA, NODE_BB, NODE_CC);
    private static final NodeId OWNER = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getFirst();
    private static final NodeId REPLICA = ReplicaPlacement.rank(STREAM, PARTITION, MEMBERS).getLast();
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 2L, 2L);

    @TempDir
    Path walDir;

    /// The committed owner's epoch: later than every epoch the replica's records were written under unless a test says otherwise.
    private Epoch committedEpoch = Epoch.epoch(1L, 9L, 9L);

    private ReplicaRegistry registry;
    private StreamPartitionManager manager;
    private final List<ReplicationMessage> sentToOwner = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// The replica holds 0..9, the owner 0..4 and they agree on those: the replica is CAUGHT_UP at the OWNER's head,
    /// and tells the owner 4, not 9. Offsets 5..9 stay in its log until the owner's own records replace them.
    @Test
    void replicaAboveTheOwner_agreeingOnTheOverlap_isCaughtUpAtTheOwnersHead_andAcksOnlyThat() {
        seedReplica(10, 10);
        var requestedFrom = new AtomicLong(-1L);

        backfill(owner(5, 5, requestedFrom));

        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).as("what the replica verified against the owner, not its own head").isEqualTo(4L);
        assertThat(acksSent()).as("the owner is told the verified offset only").containsExactly(4L);
        assertThat(requestedFrom.get()).as("the replica asked for the owner's tail window to compare it").isZero();
    }

    /// The replica agrees with the owner up to offset 2 and holds its own records at 3..9: the first run finds the
    /// divergence, cuts the tail back to offset 2 and the next run fetches the owner's 3 and 4.
    @Test
    void replicaAboveTheOwner_divergingAtOffset3_isCutBackToOffset2_andRefetchesTheOwnersRecords() {
        seedReplica(10, 3);

        backfill(owner(5, 3, new AtomicLong()));
        backfill(owner(5, 3, new AtomicLong()));

        assertThat(texts(0, 20)).containsExactly("c0", "c1", "c2", "owner-3", "owner-4");
        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).isEqualTo(4L);
        assertThat(manager.quarantinedAt(STREAM, PARTITION).isEmpty()).as("the divergence is repaired, not remembered").isTrue();
    }

    /// Same head, different records from offset 3: nothing is above the owner, and the replica is still not trusted
    /// until its records are compared.
    /// #1890, at the replication-manager level (the real manager reading the registry the real backfill wrote): the replica
    /// held offsets 0..9 of a lineage the owner does not share and the owner's head is 4. The owner then appends offset 5,
    /// which the replica never received. Its await for 5 must stay pending: before the fix the replica was promoted at its
    /// OWN head (9) and the await resolved from that row, counting a confirmation of a record that replica never held.
    @Test
    void ownerAwaitingOffset5_isNotResolvedFromAReplicaRowAt9ThatNeverReceived5() {
        seedReplica(10, 3);

        backfill(owner(5, 3, new AtomicLong()));
        backfill(owner(5, 3, new AtomicLong()));
        var ownerSide = org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager(OWNER, registry);
        var await = ownerSide.awaitReplication(STREAM, PARTITION, 5L, 1);

        assertThat(descriptor().confirmedOffset()).as("the row the await seeds from").isEqualTo(4L);
        assertThat(await.isResolved()).as("offset 5 was never received by the only replica").isFalse();
    }

    /// B9 (v1890), through the real backfill: at confirmation_factor 1 the stepped-back repair (three runs here) tells the operator
    /// ONCE, when the backfill completes, with the final range from the first divergent offset to the old local head.
    @Test
    void divergenceOlderThanTheWindow_atCf1_isReportedOnceWithTheFinalRange_whenTheBackfillCompletes() throws InterruptedException {
        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.createStream(StreamConfig.streamConfig(STREAM).withReplication(org.pragmatica.aether.slice.ReplicationFactors.replicationFactors(1, 1).unwrap()))
               .onFailure(cause -> fail(cause.message()));
        var warnings = new java.util.concurrent.CopyOnWriteArrayList<org.pragmatica.utility.warning.OperatorWarning>();

        manager.operatorWarnings(org.pragmatica.utility.warning.OperatorWarningSink.handingOffTo(warnings::add));
        seedReplica(2000, 100);

        backfill(owner(1500, 100, new AtomicLong()));
        backfill(owner(1500, 100, new AtomicLong()));
        Thread.sleep(300);
        assertThat(warnings).as("repair still in progress: nothing reported").isEmpty();

        backfill(owner(1500, 100, new AtomicLong()));

        var deadline = System.nanoTime() + 5_000_000_000L;

        while (warnings.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        Thread.sleep(300);
        assertThat(warnings).as("one event for the whole truncation").singleElement()
                            .satisfies(warning -> assertThat(warning.message()).contains("[100, 1999]").contains("ackedAtOwner=true"));
    }

    /// The verification window is 1024 records. A divergence OLDER than the window (here at offset 100, owner head 1499,
    /// window starts at 476) must not leave the records between it and the window's start in place: after the repair the
    /// replica's log is the owner's from the first divergent offset on.
    @Test
    void divergenceOlderThanTheVerificationWindow_isCutBackToItsFirstOffset() {
        seedReplica(2000, 100);

        backfill(owner(1500, 100, new AtomicLong()));
        backfill(owner(1500, 100, new AtomicLong()));
        backfill(owner(1500, 100, new AtomicLong()));

        assertThat(texts(95, 10)).containsExactly("c95", "c96", "c97", "c98", "c99", "owner-100", "owner-101", "owner-102", "owner-103", "owner-104");
        assertThat(texts(470, 10)).as("between the divergence and the window start").doesNotContain("replica-470", "replica-475");
        assertThat(descriptor().confirmedOffset()).isEqualTo(1499L);
    }

    @Test
    void replicaAtTheOwnersHead_divergingAtOffset3_isRepaired() {
        seedReplica(5, 3);

        backfill(owner(5, 3, new AtomicLong()));
        backfill(owner(5, 3, new AtomicLong()));

        assertThat(texts(0, 20)).containsExactly("c0", "c1", "c2", "owner-3", "owner-4");
        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
    }

    /// With a WAL the divergence is found by provenance (the replica's records 3..9 belong to epoch E1, the owner's 3..4
    /// to E2) and the cut is durable: a restart recovers the kept records and the owner's, not the divergent tail.
    @Test
    void walReplica_provenanceDivergence_isCutDurably_andTheCutSurvivesARestart() {
        useWal();
        for (var i = 0; i < 10; i++) {
            var text = i < 3 ? "c" + i : "replica-" + i;

            manager.appendRecovered(STREAM, PARTITION, i, text.getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        var history = List.of(ProvenanceEntry.provenanceEntry(E1, 0L), ProvenanceEntry.provenanceEntry(E2, 3L));

        backfill(owner(5, 3, new AtomicLong(), history));
        backfill(owner(5, 3, new AtomicLong(), history));

        assertThat(texts(0, 20)).containsExactly("c0", "c1", "c2", "owner-3", "owner-4");
        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        manager.close();

        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        assertThat(texts(0, 20)).as("a restart recovers the cut log, not the divergent tail").containsExactly("c0", "c1", "c2", "owner-3", "owner-4");
    }

    /// The owner is never repaired by cutting: it is the lineage, and nothing in this node's view outranks it. A
    /// quarantined partition on the node that is the owner stays quarantined and keeps its records.
    @Test
    void quarantinedOwner_isNotCut_itStaysQuarantinedWithItsRecords() {
        seedReplica(10, 10);
        manager.appendRecovered(STREAM, PARTITION, 5, "someone-else".getBytes(UTF_8), 1005L).onSuccess(_ -> fail("a different record at a held offset must be refused"));
        assertThat(manager.quarantinedAt(STREAM, PARTITION).or(-1L)).isEqualTo(5L);

        backfillAs(OWNER, owner(5, 5, new AtomicLong()));

        assertThat(manager.quarantinedAt(STREAM, PARTITION).or(-1L)).as("still quarantined").isEqualTo(5L);
        assertThat(texts(0, 20)).hasSize(10);
    }

    /// A WAL copy whose own history cannot vouch for its records (it holds records and no owner-epoch history: the
    /// `HISTORY_MISSING` shape, which a catch-up written without attribution produces) compares unequal with ANY owner at
    /// offset 0. That is not a divergence, only the absence of evidence, so nothing is cut: the copy stays quarantined
    /// exactly as before. Cutting it would discard acknowledged records the copy holds because its bookkeeping is thin.
    @Test
    void walReplicaWhoseHistoryCannotVouchForItsRecords_isNotCut() {
        useWal();
        for (var i = 0; i < 5; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("c" + i).getBytes(UTF_8), 1000L + i).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        var history = List.of(ProvenanceEntry.provenanceEntry(E1, 0L));

        backfill(owner(5, 5, new AtomicLong(), history));
        backfill(owner(5, 5, new AtomicLong(), history));

        assertThat(manager.quarantinedAt(STREAM, PARTITION).isPresent()).as("quarantined, as before: no evidence of a divergence").isTrue();
        assertThat(texts(0, 20)).as("nothing was cut").containsExactly("c0", "c1", "c2", "c3", "c4");
    }

    /// A WAL replica that restarted with a recovered tail shows readers nothing of it until its backfill has compared it
    /// with the owner's; completing that comparison exposes it. (Before, a restarted REPLICA exposed its whole recovered
    /// tail at once, including an ex-owner's unacknowledged records.)
    @Test
    void walReplicaRestartedWithATail_isVisibleOnlyAfterItsBackfillVerifiesIt() {
        useWal();
        for (var i = 0; i < 5; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("c" + i).getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.placementRoleSupplier((_, _) -> org.pragmatica.aether.stream.replication.ReplicaSetController.Role.REPLICA);
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        assertThat(manager.readLocal(STREAM, PARTITION, 0, 20).unwrap()).as("recovered, durable, not yet verified: invisible").isEmpty();

        backfill(owner(5, 5, new AtomicLong(), List.of(ProvenanceEntry.provenanceEntry(E1, 0L))));

        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(manager.readLocal(STREAM, PARTITION, 0, 20).unwrap()).as("verified through the owner's head").hasSize(5);
    }

    /// Control: a replica exactly at the owner's head with the owner's records is CAUGHT_UP at that offset, no repair.
    @Test
    void control_replicaAtTheOwnersHead_agreeing_isCaughtUpWithoutARepair() {
        seedReplica(5, 5);

        backfill(owner(5, 5, new AtomicLong()));

        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).isEqualTo(4L);
        assertThat(texts(0, 20)).containsExactly("c0", "c1", "c2", "c3", "c4");
    }

    /// Control: a replica below the owner still pulls the missing suffix.
    @Test
    void control_replicaBelowTheOwner_pullsTheSuffix() {
        seedReplica(3, 3);

        backfill(owner(8, 8, new AtomicLong()));

        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).isEqualTo(7L);
    }

    private void useWal() {
        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    private void backfill(CatchupTransport owner) {
        backfillAs(REPLICA, owner);
    }

    private void backfillAs(NodeId self, CatchupTransport owner) {
        registry.registerReplica(STREAM, PARTITION, self);
        SelfWatermark local = (stream, partition) -> manager.partitionInfo(stream, partition)
                                                            .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                            .or(-1L);
        ReplicaWatermarkProbe probe = (_, _, _) -> Promise.success(ownerHead(owner));
        CommittedStreamOwnerSource committed = (_, _) -> Option.some(new CommittedOwner(OWNER, committedEpoch));
        ReplicationTransport toOwner = (_, message) -> sentToOwner.add(message);
        var orchestrator = partitionBackfill(registry,
                                             manager.alignedRecovery(),
                                             owner,
                                             toOwner,
                                             probe,
                                             local,
                                             self,
                                             TimeSpan.timeSpan(10).seconds(),
                                             () -> MEMBERS,
                                             committed,
                                             manager::syncReplicated,
                                             manager.quarantineView());

        orchestrator.backfill(STREAM, PARTITION).await();
    }

    /// The owner's head is whatever its fake log holds.
    private long ownerHead(CatchupTransport owner) {
        return owner instanceof FakeOwner fake
               ? fake.count - 1L
               : -1L;
    }

    /// The replica holds `count` records: the first `agreeing` are "c<i>" (what the owner holds), the rest "replica-<i>".
    private void seedReplica(int count, int agreeing) {
        for (var i = 0; i < count; i++) {
            var text = i < agreeing
                       ? "c" + i
                       : "replica-" + i;

            manager.appendRecovered(STREAM, PARTITION, text.getBytes(UTF_8), 1000L + i).unwrap();
        }

        manager.syncReplicated(STREAM, PARTITION).await();
    }

    /// The owner holds `count` records: the first `agreeing` are "c<i>", the rest "owner-<i>".
    private static FakeOwner owner(int count, int agreeing, AtomicLong requestedFrom) {
        return new FakeOwner(count, agreeing, requestedFrom, List.of());
    }

    private static FakeOwner owner(int count, int agreeing, AtomicLong requestedFrom, List<ProvenanceEntry> history) {
        return new FakeOwner(count, agreeing, requestedFrom, history);
    }

    private static final class FakeOwner implements CatchupTransport {
        private final int count;
        private final int agreeing;
        private final AtomicLong requestedFrom;
        private final List<ProvenanceEntry> history;

        private FakeOwner(int count, int agreeing, AtomicLong requestedFrom, List<ProvenanceEntry> history) {
            this.count = count;
            this.agreeing = agreeing;
            this.requestedFrom = requestedFrom;
            this.history = history;
        }

        @Override
        public Promise<ReplicationMessage.CatchupResponse> requestCatchup(NodeId target,
                                                                         ReplicationMessage.CatchupRequest request) {
            requestedFrom.set(request.fromOffset());
            var payloads = new ArrayList<byte[]>();
            var stamps = new ArrayList<Long>();

            for (var offset = request.fromOffset(); offset < count; offset++) {
                payloads.add(((offset < agreeing ? "c" : "owner-") + offset).getBytes(UTF_8));
                stamps.add(1000L + offset);
            }

            var to = payloads.isEmpty()
                     ? request.fromOffset() - 1
                     : count - 1L;

            return Promise.success(catchupResponse(target,
                                                   request.streamName(),
                                                   request.partition(),
                                                   request.fromOffset(),
                                                   to,
                                                   payloads,
                                                   stamps,
                                                   history));
        }
    }

    private List<Long> acksSent() {
        return sentToOwner.stream()
                          .filter(ReplicationMessage.ReplicateAck.class::isInstance)
                          .map(message -> ((ReplicationMessage.ReplicateAck) message).confirmedOffset())
                          .toList();
    }

    private List<String> texts(long from, int max) {
        return manager.readAppended(STREAM, PARTITION, from, max)
                      .unwrap()
                      .stream()
                      .map(event -> new String(event.data(), UTF_8))
                      .toList();
    }

    private ReplicaDescriptor descriptor() {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(REPLICA))
                       .findFirst()
                       .orElseThrow();
    }
}
