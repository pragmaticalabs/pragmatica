// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1730 phase 2: a partition that restarts as a REPLICA must not expose the tail it recovered until that tail has been
/// verified against the committed owner. The tail may belong to a lineage the owner never had (an ex-owner's
/// unacknowledged records), and `restoreVisible` used to make a replica's whole recovered tail visible at once, so a
/// replica-local read, and a consumer on that node, could return records that were then replaced. A partition that was
/// OWNER with a WAL appended five records and only 0..1 were acknowledged by the peer; it restarts as a REPLICA.
class ReplicaRestartVisibilityTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final int PUBLISHED = 5;
    private static final long ACKED_THROUGH = 1;

    @TempDir
    Path walDir;
    @TempDir
    Path storageDir;

    private MetadataStore metadataStore;
    private StorageInstance storage;
    private StreamPartitionManager manager;
    private ReplicationManager replication;

    @BeforeEach
    void setUp() {
        metadataStore = MetadataStore.inMemoryMetadataStore("p2-leak");
        storage = StorageInstance.storageInstance("p2-leak",
                                                  List.of(MemoryTier.memoryTier(ONE_GB),
                                                          LocalDiskTier.localDiskTier(storageDir, ONE_GB).unwrap()),
                                                  metadataStore);
    }

    @AfterEach
    void tearDown() {
        Option.option(manager).onPresent(StreamPartitionManager::close);
        Option.option(storage).onPresent(StorageInstance::shutdown);
    }

    @Test
    void restartAsReplica_exposesNothingUntilTheTailIsVerified() {
        var before = ownerWithPartiallyAckedTail();

        assertThat(before).as("premise: before the restart only the acked prefix is visible").containsExactly(0L, 1L);

        restart(Role.REPLICA);

        assertThat(offsets()).as("a restarted REPLICA must not expose the ex-owner's never-acked tail 2..4").isEmpty();
    }

    /// Verification (the owner's tail window compared and applied) exposes exactly the verified prefix, never more.
    @Test
    void markVerified_exposesThePrefixItCovers_andNoMore() {
        ownerWithPartiallyAckedTail();
        restart(Role.REPLICA);

        manager.markVerified(STREAM, PARTITION, 2L);

        assertThat(offsets()).containsExactly(0L, 1L, 2L);

        manager.markVerified(STREAM, PARTITION, 99L);

        assertThat(offsets()).as("never above what is durable here").containsExactly(0L, 1L, 2L, 3L, 4L);

        manager.appendRecovered(STREAM, PARTITION, 5L, "owner-5".getBytes(UTF_8), 5L, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        manager.syncReplicated(STREAM, PARTITION).await();

        assertThat(offsets()).as("everything held is verified now: later appends extend visibility as always")
                             .containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
    }

    /// A repair keeps the prefix the copy shares with its sender, which is verified by construction: the owner's
    /// records appended after the cut are visible together with it, without a further verification.
    @Test
    void repairDivergence_ofAnUnverifiedReplica_leavesTheKeptPrefixVerified() {
        ownerWithPartiallyAckedTail();
        restart(Role.REPLICA);
        manager.appendRecovered(STREAM, PARTITION, 3L, "owner-3".getBytes(UTF_8), 3L, org.pragmatica.aether.slice.generation.Epoch.ZERO);
        assertThat(manager.quarantinedAt(STREAM, PARTITION).or(-1L)).as("premise: the different record at 3 quarantines").isEqualTo(3L);

        manager.repairDivergence(STREAM, PARTITION, _ -> true).unwrap();
        manager.appendRecovered(STREAM, PARTITION, 3L, "owner-3".getBytes(UTF_8), 3L, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        manager.syncReplicated(STREAM, PARTITION).await();

        assertThat(offsets()).containsExactly(0L, 1L, 2L, 3L);
    }

    /// While unverified, a record appended from the owner is durable here but does not drag the unverified tail below it
    /// into view; once verified it is visible like any other.
    @Test
    void anAppendWhileUnverified_isNotVisibleUntilVerified() {
        ownerWithPartiallyAckedTail();
        restart(Role.REPLICA);
        manager.appendRecovered(STREAM, PARTITION, 5L, "owner-5".getBytes(UTF_8), 5L, org.pragmatica.aether.slice.generation.Epoch.ZERO).unwrap();
        manager.syncReplicated(STREAM, PARTITION).await();

        assertThat(offsets()).isEmpty();

        manager.markVerified(STREAM, PARTITION, 5L);

        assertThat(offsets()).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
    }

    /// Same, through the client-facing `readServing` (REPLICA role: no owner gate).
    @Test
    void restartAsReplica_readServingReturnsNothingUntilTheTailIsVerified() {
        ownerWithPartiallyAckedTail();
        restart(Role.REPLICA);

        var served = manager.readServing(STREAM, PARTITION, 0, 10)
                            .or(List.of())
                            .stream()
                            .map(OffHeapRingBuffer.RawEvent::offset)
                            .toList();

        assertThat(served).isEmpty();
    }

    /// CONTROL (must be GREEN): identical fixture, restarted as OWNER. Visibility is min(durable, peerAck) and no
    /// ack survives the restart, so nothing above the (here: empty) sealed floor is visible. Shows the harness
    /// can observe the bound and that only the REPLICA branch of `restoreVisible` differs.
    @Test
    void control_restartAsOwner_tailStaysInvisible() {
        ownerWithPartiallyAckedTail();
        restart(Role.OWNER);

        assertThat(offsets()).as("owner restart: no ack survives, nothing above the floor is visible")
                             .allMatch(offset -> offset <= ACKED_THROUGH);
    }

    /// CONTROL 2 (must be GREEN): the replica's WAL tail was really recovered (durable = 4), so an empty read is
    /// visibility, not a lost WAL.
    @Test
    void control_restartAsReplica_walTailIsRecovered() {
        ownerWithPartiallyAckedTail();
        restart(Role.REPLICA);

        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::durableOffset).or(-9L))
            .isEqualTo(PUBLISHED - 1L);
    }

    private List<Long> ownerWithPartiallyAckedTail() {
        start(Role.OWNER);
        for (var i = 0; i < PUBLISHED; i++) {
            manager.publishLocal(STREAM, PARTITION, ("e" + i).getBytes(UTF_8), 1L)
                   .onFailure(cause -> fail("publish failed: " + cause.message()));
        }
        replication.handleAck(replicateAck(PEER, STREAM, PARTITION, ACKED_THROUGH));

        return offsets();
    }

    private List<Long> offsets() {
        return manager.readLocal(STREAM, PARTITION, 0, 10)
                      .or(List.of())
                      .stream()
                      .map(OffHeapRingBuffer.RawEvent::offset)
                      .toList();
    }

    private void restart(Role role) {
        manager.close();
        start(role);
    }

    private void start(Role role) {
        var index = new SegmentIndex();
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);
        replication = replicationManager(SELF, registry);
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         segmentSealer(storageSegmentSink(storage, index)),
                                         replication,
                                         Option.some(walDir),
                                         index::lastSealedOffset);
        manager.placementRoleSupplier((_, _) -> role);
        manager.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(1000, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         3,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
