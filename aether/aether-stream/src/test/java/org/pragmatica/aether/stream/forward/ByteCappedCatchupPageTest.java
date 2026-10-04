// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;
import static org.pragmatica.aether.stream.replication.ForwardCatchupTransport.forwardCatchupTransport;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

/// #1431: the owner's handler cuts every catch-up page at `maxReadResponseBytes` and marks it truncated. The replica's
/// pull, through the real handler, client, transport and backfill, must end CAUGHT_UP at the owner's head — never
/// CAUGHT_UP at the first cut page's last offset, which is a false-ready row below head.
class ByteCappedCatchupPageTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId OWNER = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final NodeId NEW_PEER = NodeId.randomNodeId();
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    /// Envelope 64 + one 1-byte event 25 = 89 fits; a second event (114) does not, so every page carries ONE event.
    private static final long ONE_EVENT_PER_PAGE_BYTES = 100L;

    private StorageInstance ownerStorage;
    private StorageInstance replicaStorage;
    private ReplicaRegistry registry;
    private ReplicationManager replication;
    private StreamPartitionManager owner;
    private StreamPartitionManager replica;
    private StreamForwardHandler handler;
    private StreamForwardClient client;

    @BeforeEach
    void setUp() {
        ownerStorage = StorageInstance.storageInstance("owner", List.of(MemoryTier.memoryTier(ONE_GB)));
        replicaStorage = StorageInstance.storageInstance("replica", List.of(MemoryTier.memoryTier(ONE_GB)));
        registry = replicaRegistry();
        registry.registerReplica(STREAM, PARTITION, OWNER);
        registry.registerReplica(STREAM, PARTITION, PEER);
        registry.registerReplica(STREAM, PARTITION, NEW_PEER);
        replication = replicationManager(OWNER, registry);
        owner = streamPartitionManager(Long.MAX_VALUE,
                                       segmentSealer(storageSegmentSink(ownerStorage, new SegmentIndex())),
                                       replication);
        replica = streamPartitionManager(Long.MAX_VALUE,
                                         segmentSealer(storageSegmentSink(replicaStorage, new SegmentIndex())));
        handler = streamForwardHandler(OWNER,
                                       owner,
                                       (_, message) -> client.onReadForwardResponse((ReadForwardResponse) message),
                                       ONE_EVENT_PER_PAGE_BYTES,
                                       StreamReadForwardMetrics.NOOP);
        client = streamForwardClient(NEW_PEER, (_, message) -> handler.onReadForward((ReadForward) message));
    }

    @AfterEach
    void tearDown() {
        owner.close();
        replica.close();
        ownerStorage.shutdown();
        replicaStorage.shutdown();
    }

    @Test
    void byteCappedPages_replicaEndsCaughtUpAtTheOwnersHead_neverBelowIt() {
        owner.createStream(config()).onFailure(cause -> fail(cause.message()));
        replica.createStream(config()).onFailure(cause -> fail(cause.message()));
        publish(3);
        var head = ownerRing().headOffset();
        assertThat(head).isEqualTo(2L);
        registry.updateWatermark(STREAM, PARTITION, OWNER, head, ReplicationState.CAUGHT_UP);

        var outcome = backfill().backfill(STREAM, PARTITION).await();

        assertThat(outcome.isSuccess()).as("backfill outcome %s", outcome).isTrue();
        assertThat(replica.nextExpectedOffset(STREAM, PARTITION)).as("replica holds every event").isEqualTo(head + 1);
        assertThat(newPeerRow().state()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(newPeerRow().confirmedOffset())
                .as("CAUGHT_UP at the owner's head, not at the first cut page's last offset (0)")
                .isEqualTo(head);
    }

    /// Production shape: HRW owner known (members = [OWNER]), a probe answering the owner's real head, the self
    /// watermark from the replica's own ring, acks fed to the owner's replication manager as the wire would.
    private PartitionBackfill backfill() {
        return partitionBackfill(registry,
                                 replica.alignedRecovery(),
                                 forwardCatchupTransport(client, 100),
                                 (_, message) -> replication.handleAck((ReplicateAck) message),
                                 (_, _, _) -> Promise.success(ownerRing().headOffset()),
                                 (s, p) -> replica.nextExpectedOffset(s, p) - 1,
                                 NEW_PEER,
                                 TimeSpan.timeSpan(0).millis(),
                                 () -> List.of(OWNER),
                                 CommittedStreamOwnerSource.none());
    }

    private OffHeapRingBuffer ownerRing() {
        return owner.partitionBuffer(STREAM, PARTITION).or(() -> fail("owner ring"));
    }

    private ReplicaDescriptor newPeerRow() {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(NEW_PEER))
                       .findFirst()
                       .orElseThrow();
    }

    private void publish(int count) {
        for (var i = 0; i < count; i++) {
            owner.publishLocal(STREAM, PARTITION, "e".getBytes(UTF_8), 1L)
                 .onFailure(cause -> fail("publish failed: " + cause.message()));
        }
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM,
                                         1,
                                         RetentionPolicy.retentionPolicy(10, 1_048_576L, 60_000L),
                                         "earliest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         2,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }
}
