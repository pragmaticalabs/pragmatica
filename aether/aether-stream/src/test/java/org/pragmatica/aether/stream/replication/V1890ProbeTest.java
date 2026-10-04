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
class V1890ProbeTest {
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

    private final List<String> flagRaises = new CopyOnWriteArrayList<>();

    /// PROBE A (v1890): a no-WAL replica BELOW the owner whose middle diverges (c0..c4 shared, replica-5..9 its own,
    /// the owner holds owner-5..owner-20) pulls from its head + 1 and is never compared over 5..9. Expected by the
    /// ruling (compare before CAUGHT_UP): it must not end CAUGHT_UP holding replica-5..9 under the owner's head.
    @Test
    void probeA_noWalReplicaBelowOwner_divergentMiddle_mustNotBeCaughtUpWithItsOwnRecords() {
        seedReplica(10, 5);

        backfill(owner(21, 5, new AtomicLong()));

        assertThat(acksSent()).as("acks to the owner while offsets 5..9 hold replica-5..9, never compared").allMatch(offset -> offset < 5L);
        var caughtUpOverDivergence = descriptor().state() == ReplicationState.CAUGHT_UP
                                     && texts(5, 1).contains("replica-5");
        assertThat(caughtUpOverDivergence).as("CAUGHT_UP at %d with %s at offset 5", descriptor().confirmedOffset(), texts(5, 1)).isFalse();
    }

    /// PROBE A control: same shape, the replica's 5..9 agree with the owner: CAUGHT_UP at the owner's head.
    @Test
    void probeA_control_noWalReplicaBelowOwner_agreeing_isCaughtUp() {
        seedReplica(5, 5);

        backfill(owner(21, 5, new AtomicLong()));

        assertThat(descriptor().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).isEqualTo(20L);
    }

    /// PROBE B (v1890): the ordinary CF>=2 repair of a WAL replica (provenance divergence, the author's own
    /// walReplica_provenanceDivergence scenario) with a durable partition flag wired. The repair succeeds; the flag the
    /// detection raised (MARKED_DIVERGED, "no owner, no reads until an operator resolves") is never cleared.
    @Test
    void probeB_walReplicaRepairedAtCf2_leavesNoDurableDivergenceFlagRaised() {
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

    /// PROBE C (v1890): KIP-101 truncates only to a leader of a LATER epoch. The replica's records 3..9 are attributed
    /// to E2; the node it backfills from serves records at 3.. under the OLDER E1 (a deposed owner seen through a stale
    /// committed-owner view, or an HRW fallback). The newer-epoch records must not be cut in favour of the older ones.
    @Test
    void probeC_senderOfAnOlderEpoch_doesNotCutTheReplicasNewerEpochRecords() {
        useWal();
        for (var i = 0; i < 10; i++) {
            var text = i < 3 ? "c" + i : "replica-" + i;

            manager.appendRecovered(STREAM, PARTITION, i, text.getBytes(UTF_8), 1000L + i, i < 3 ? E1 : E2).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
        var olderHistory = List.of(ProvenanceEntry.provenanceEntry(E1, 0L));

        backfill(owner(5, 3, new AtomicLong(), olderHistory));
        backfill(owner(5, 3, new AtomicLong(), olderHistory));

        assertThat(texts(3, 1)).as("E2 record at 3 replaced by an E1 sender's").containsExactly("replica-3");
    }

    /// PROBE D (v1890, #1890 by the re-verify path): the replica is CAUGHT_UP at 9 under owner lineage L1 (0..9). The
    /// committed owner changes to one whose head is 6 (it never had 7..9). The periodic re-verify of a CAUGHT_UP row
    /// probes the owner's head (6 <= 9) and takes the no-op arm, which re-acks the replica's OWN row (9) without any
    /// comparison. The owner would then count this replica for its own 7, 8, 9, which the replica does not hold. The
    /// author's harness re-registers the replica (row reset to -1 SYNCING) on every backfill, so it never reaches this
    /// arm; this probe keeps the registry between the two runs.
    @Test
    void probeD_reverifyNoOp_neverAcksAboveTheNewOwnersHead() {
        seedReplica(10, 10);
        backfill(owner(10, 10, new AtomicLong()));
        assertThat(descriptor().state()).as("premise: CAUGHT_UP under the first owner").isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(descriptor().confirmedOffset()).as("premise").isEqualTo(9L);
        sentToOwner.clear();

        runBackfill(REPLICA, owner(7, 7, new AtomicLong()));

        assertThat(acksSent()).as("acks sent to an owner whose head is 6").allMatch(offset -> offset <= 6L);
    }

    /// PROBE D control: the same re-verify against an owner whose head equals the row (9) acks 9, so the probe's
    /// assertion is reachable and its arm is the one exercised.
    @Test
    void probeD_control_reverifyNoOp_acksTheRowWhenTheOwnerHoldsIt() {
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
        SelfWatermark local = (stream, partition) -> manager.partitionInfo(stream, partition)
                                                            .map(StreamPartitionManager.PartitionInfo::headOffset)
                                                            .or(-1L);
        ReplicaWatermarkProbe probe = (_, _, _) -> Promise.success(ownerHead(owner));
        CommittedStreamOwnerSource committed = (_, _) -> Option.some(new CommittedOwner(OWNER, Epoch.ZERO));
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
