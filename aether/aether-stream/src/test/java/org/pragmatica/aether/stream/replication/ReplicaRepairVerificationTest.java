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
class ReplicaRepairVerificationTest {
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
    private NodeId committedOwnerNode = OWNER;

    private ReplicaRegistry registry;
    private StreamPartitionManager manager;
    private final List<ReplicationMessage> sentToOwner = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.ownerEpochSource((_, _) -> committedEpoch);
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    private final List<String> flagRaises = new CopyOnWriteArrayList<>();

    /// Below the owner, divergent middle (v1890 A): a no-WAL replica BELOW the owner whose middle diverges (c0..c4 shared, replica-5..9 its own,
    /// the owner holds owner-5..owner-20) pulls from its head + 1 and is never compared over 5..9. Expected by the
    /// ruling (compare before CAUGHT_UP): it must not end CAUGHT_UP holding replica-5..9 under the owner's head.
    @Test
    void belowOwner_noWalReplicaBelowOwner_divergentMiddle_mustNotBeCaughtUpWithItsOwnRecords() {
        seedReplica(10, 5);

        backfill(owner(21, 5, new AtomicLong()));

        assertThat(acksSent()).as("acks to the owner while offsets 5..9 hold replica-5..9, never compared").allMatch(offset -> offset < 5L);
        var caughtUpOverDivergence = descriptor().state() == ReplicationState.CAUGHT_UP
                                     && texts(5, 1).contains("replica-5");
        assertThat(caughtUpOverDivergence).as("CAUGHT_UP at %d with %s at offset 5", descriptor().confirmedOffset(), texts(5, 1)).isFalse();
    }

    /// Control for the above: same shape, the replica's 5..9 agree with the owner: CAUGHT_UP at the owner's head.
    @Test
    void belowOwner_control_noWalReplicaBelowOwner_agreeing_isCaughtUp() {
        seedReplica(5, 5);

        backfill(owner(21, 5, new AtomicLong()));

        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).isEqualTo(20L);
    }

    /// Ordinary CF>=2 repair raises no durable flag (v1890 B1): the ordinary CF>=2 repair of a WAL replica (provenance divergence, the author's own
    /// walReplica_provenanceDivergence scenario) with a durable partition flag wired. The repair succeeds; the flag the
    /// detection raised (MARKED_DIVERGED, "no owner, no reads until an operator resolves") is never cleared.
    @Test
    void ordinaryRepair_walReplicaRepairedAtCf2_leavesNoDurableDivergenceFlagRaised() {
        useWal();
        manager.partitionFlags(recordingFlags());
        for (var i = 0; i < 10; i++) {
            var text = i < 3 ? "c" + i : "replica-" + i;

            manager.appendRecovered(STREAM, PARTITION, i, text.getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        var history = List.of(ProvenanceEntry.provenanceEntry(E1, 0L), ProvenanceEntry.provenanceEntry(E2, 3L));

        backfill(owner(5, 3, new AtomicLong(), history));
        backfill(owner(5, 3, new AtomicLong(), history));

        assertThat(texts(0, 20)).as("premise: repaired").containsExactly("c0", "c1", "c2", "owner-3", "owner-4");
        assertThat(flagRaises).as("durable flags raised and left standing by an ordinary, self-repaired CF2 failover").isEmpty();
    }

    /// Authority (v1890 B2): KIP-101 truncates only to a leader of a LATER epoch. The replica's records 3..9 are attributed
    /// to E2; the node it backfills from serves records at 3.. under the OLDER E1 (a deposed owner seen through a stale
    /// committed-owner view, or an HRW fallback). The newer-epoch records must not be cut in favour of the older ones.
    @Test
    void authority_senderOfAnOlderEpoch_doesNotCutTheReplicasNewerEpochRecords() {
        useWal();
        for (var i = 0; i < 10; i++) {
            var text = i < 3 ? "c" + i : "replica-" + i;

            manager.appendRecovered(STREAM, PARTITION, i, text.getBytes(UTF_8), 1000L + i, i < 3 ? E1 : E2).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        var olderHistory = List.of(ProvenanceEntry.provenanceEntry(E1, 0L));
        committedEpoch = E1;

        backfill(owner(5, 3, new AtomicLong(), olderHistory));
        backfill(owner(5, 3, new AtomicLong(), olderHistory));

        assertThat(texts(3, 1)).as("E2 record at 3 replaced by an E1 sender's").containsExactly("replica-3");
    }

    /// #1890 by the re-verify path (v1890 B3): the replica is CAUGHT_UP at 9 under owner lineage L1 (0..9). The
    /// committed owner changes to one whose head is 6 (it never had 7..9). The periodic re-verify of a CAUGHT_UP row
    /// probes the owner's head (6 <= 9) and takes the no-op arm, which re-acks the replica's OWN row (9) without any
    /// comparison. The owner would then count this replica for its own 7, 8, 9, which the replica does not hold. The
    /// author's harness re-registers the replica (row reset to -1 SYNCING) on every backfill, so it never reaches this
    /// arm; this probe keeps the registry between the two runs.
    @Test
    void reverify_reverifyNoOp_neverAcksAboveTheNewOwnersHead() {
        seedReplica(10, 10);
        backfill(owner(10, 10, new AtomicLong()));
        assertThat(descriptor().state()).as("premise: CAUGHT_UP under the first owner").isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).as("premise").isEqualTo(9L);
        sentToOwner.clear();

        runBackfill(REPLICA, owner(7, 7, new AtomicLong()));

        assertThat(acksSent()).as("acks sent to an owner whose head is 6").allMatch(offset -> offset <= 6L);
    }

    /// B7 (v1890 hole): the replica is CAUGHT_UP at 9 under owner A (records 0..9). A failover commits a NEW epoch; the new owner's
    /// head is also 9 but its records at 5..9 differ. The replica's row EQUALS the owner's head, which used to take the #333
    /// no-compare shortcut and re-ack 9 for records the replica holds in another version. Without resetting the registry
    /// (the real situation), it must compare first, and ack nothing it holds divergently.
    @Test
    void reverify_equalLengthDivergentTail_afterAnEpochAdvance_isComparedBeforeAnyAck() {
        committedEpoch = Epoch.epoch(1L, 5L, 5L);
        seedReplica(10, 10);
        backfill(owner(10, 10, new AtomicLong()));
        assertThat(descriptor().confirmedOffset()).as("premise: CAUGHT_UP at 9 under the first owner").isEqualTo(9L);
        sentToOwner.clear();

        committedEpoch = Epoch.epoch(1L, 6L, 6L);
        var requested = new AtomicLong(-1L);

        runBackfill(REPLICA, owner(10, 5, requested));

        assertThat(requested.get()).as("the new epoch's owner was asked for the overlap window, not skipped").isGreaterThanOrEqualTo(0L);
        assertThat(acksSent()).as("nothing is acked for 5..9 while they hold the old lineage").allMatch(offset -> offset < 5L);
    }

    /// B7 control: the same epoch advance, the replica's records agree with the new owner's: it compares, is verified for the
    /// new epoch, and acks 9. And WITHIN a verified epoch the #333 shortcut still skips the compare.
    @Test
    void reverify_control_afterAnEpochAdvance_agreeingCopyIsVerifiedAndAcks_andWithinTheEpochTheShortcutHolds() {
        committedEpoch = Epoch.epoch(1L, 5L, 5L);
        seedReplica(10, 10);
        backfill(owner(10, 10, new AtomicLong()));
        sentToOwner.clear();

        committedEpoch = Epoch.epoch(1L, 6L, 6L);
        var afterAdvance = new AtomicLong(-1L);

        runBackfill(REPLICA, owner(10, 10, afterAdvance));

        assertThat(afterAdvance.get()).as("compared").isGreaterThanOrEqualTo(0L);
        assertThat(acksSent()).containsExactly(9L);
        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();

        sentToOwner.clear();
        var withinEpoch = new AtomicLong(-1L);

        runBackfill(REPLICA, owner(10, 10, withinEpoch));

        assertThat(withinEpoch.get()).as("verified for this epoch: the no-compare shortcut skips the fetch").isEqualTo(-1L);
        assertThat(acksSent()).containsExactly(9L);
    }

    /// B5: the redrive picks a CAUGHT_UP replica that is not verified for the current epoch at once.
    @Test
    void redrive_aCaughtUpReplicaNotVerifiedForTheCurrentEpoch_isACandidate() {
        committedEpoch = Epoch.epoch(1L, 5L, 5L);
        seedReplica(10, 10);
        var orchestrator = orchestrator(REPLICA, owner(10, 10, new AtomicLong()));
        registry.registerReplica(STREAM, PARTITION, REPLICA);
        orchestrator.backfill(STREAM, PARTITION).await();
        assertThat(orchestrator.redriveCandidates()).as("verified for the epoch it was compared under").isEmpty();

        committedEpoch = Epoch.epoch(1L, 6L, 6L);

        assertThat(orchestrator.redriveCandidates()).as("the epoch advanced: not verified for it").hasSize(1);
    }

    /// B1 control: the flag stays for the REFUSED case. The committed owner is of an older epoch than the replica's records, so
    /// the repair is refused, the quarantine stands and the durable flag is raised, once.
    @Test
    void refusedRepair_raisesMarkedDivergedOnce() {
        useWal();
        manager.partitionFlags(recordingFlags());
        for (var i = 0; i < 10; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, (i < 3 ? "c" + i : "replica-" + i).getBytes(UTF_8), 1000L + i, i < 3 ? E1 : E2).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        committedEpoch = E1;
        var older = List.of(ProvenanceEntry.provenanceEntry(E1, 0L));

        backfill(owner(5, 3, new AtomicLong(), older));
        backfill(owner(5, 3, new AtomicLong(), older));
        backfill(owner(5, 3, new AtomicLong(), older));

        assertThat(flagRaises).hasSize(1);
        assertThat(flagRaises.getFirst()).startsWith("MARKED_DIVERGED");
        assertThat(manager.quarantinedAt(STREAM, PARTITION).isPresent()).isTrue();
    }

    /// B2: a node never repairs itself as the committed owner, and never against a sender that is not the committed owner.
    @Test
    void authority_committedOwnerIsThisNode_orNotTheSender_isNotRepaired() {
        useWal();
        for (var i = 0; i < 10; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, (i < 3 ? "c" + i : "replica-" + i).getBytes(UTF_8), 1000L + i, i < 3 ? E1 : E2).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        var history = List.of(ProvenanceEntry.provenanceEntry(E1, 0L), ProvenanceEntry.provenanceEntry(E2, 3L));

        committedOwnerNode = REPLICA;
        backfill(owner(5, 3, new AtomicLong(), history));
        backfill(owner(5, 3, new AtomicLong(), history));
        assertThat(texts(3, 1)).as("this node is the committed owner: its own records are never cut").containsExactly("replica-3");

        committedOwnerNode = NODE_BB.equals(OWNER) ? NODE_CC : NODE_BB;
        backfill(owner(5, 3, new AtomicLong(), history));
        assertThat(texts(3, 1)).as("the committed owner is not the node it backfills from").containsExactly("replica-3");
    }

    /// Control for the above: the same re-verify against an owner whose head equals the row (9) acks 9, so the probe's
    /// assertion is reachable and its arm is the one exercised.
    @Test
    void reverify_control_reverifyNoOp_acksTheRowWhenTheOwnerHoldsIt() {
        seedReplica(10, 10);
        backfill(owner(10, 10, new AtomicLong()));
        sentToOwner.clear();

        runBackfill(REPLICA, owner(10, 10, new AtomicLong()));

        assertThat(acksSent()).containsExactly(9L);
    }

    private org.pragmatica.aether.stream.provenance.PartitionFlags recordingFlags() {
        return new org.pragmatica.aether.stream.provenance.PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason reason) {
                flagRaises.add(reason.kind() + ":" + reason.evidence());
                return Promise.success(new PartitionFlag(org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionRecoveryValue.streamPartitionRecoveryValue(org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryState.FLAGGED, java.util.Set.of(reason)), "probe"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return Option.none();
            }

            @Override
            public org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason local(org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind kind, String evidence) {
                return org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason.partitionRecoveryReason(kind, Option.some("probe"), evidence);
            }
        };
    }

    private void useWal() {
        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.ownerEpochSource((_, _) -> committedEpoch);
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    private void backfill(CatchupTransport owner) {
        backfillAs(REPLICA, owner);
    }

    private void backfillAs(NodeId self, CatchupTransport owner) {
        registry.registerReplica(STREAM, PARTITION, self);
        runBackfill(self, owner);
    }

    private void runBackfill(NodeId self, CatchupTransport owner) {
        orchestrator(self, owner).backfill(STREAM, PARTITION).await();
    }

    private PartitionBackfill orchestrator(NodeId self, CatchupTransport owner) {
        SelfWatermark local = (stream, partition) -> manager.partitionInfo(stream, partition)
                                                            .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                            .or(-1L);
        ReplicaWatermarkProbe probe = (_, _, _) -> Promise.success(ownerHead(owner));
        CommittedStreamOwnerSource committed = (_, _) -> Option.some(new CommittedOwner(committedOwnerNode, committedEpoch));
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

        return orchestrator;
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
