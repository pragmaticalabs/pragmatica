// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// NT-204 (#263, overhaul 14.3 item 7): the replication manager the node assembles batches `ReplicateEvents` per peer,
/// sized by `[streaming] replication_batch_*`. Frames are counted at the transport. With a window that cannot close
/// during the test, 101 single events leave as two frames per peer: the idle partition's first event at once, then one
/// full batch of 100. Before #263 the node built the direct manager: one frame per event per peer (101). Red when
/// `StreamAssembly.streamReplicationManager` builds the direct manager or ignores the configured batch size.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StreamAssemblyReplicationBatchingTest {
    private static final NodeId SELF = new NodeId("owner");
    private static final NodeId PEER = new NodeId("peer");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int BATCH = 100;
    private static final int EVENTS = BATCH + 1;

    @Test
    void productionWiring_batchesReplicateEvents_perPeer() throws InterruptedException {
        var frames = new AtomicInteger();
        var events = new AtomicInteger();
        var allEvents = new CountDownLatch(1);
        ReplicationTransport counting = (target, message) -> counted(frames, events, allEvents, target, message);
        var registry = ReplicaRegistry.replicaRegistry();
        var streaming = StreamingConfig.streamingConfig().withReplicationBatch(BATCH, TimeSpan.timeSpan(1).hours());

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.registerReplica(STREAM, PARTITION, PEER);

        var manager = StreamAssembly.streamReplicationManager(SELF, registry, counting, (_, _) -> -1L, streaming, Long.MAX_VALUE);

        for (long offset = 0; offset < EVENTS; offset++) {
            manager.replicateEvent(STREAM, PARTITION, offset, new byte[]{(byte) offset}, offset, Epoch.ZERO);
        }

        assertThat(allEvents.await(10, TimeUnit.SECONDS)).as("all %d events reached the peer", EVENTS).isTrue();
        assertThat(frames.get()).as("ReplicateEvents frames to the peer for %d single events", EVENTS).isEqualTo(2);

        manager.close();
    }

    /// #263 condition 1: the production wiring hands the send budget to the batcher, so a reservation past it is refused.
    @Test
    void productionWiring_refusesAReservationPastTheSendBudget() {
        var manager = StreamAssembly.streamReplicationManager(SELF,
                                                              ReplicaRegistry.replicaRegistry(),
                                                              (target, _) -> Promise.success(new WriteOutcome.Sent(target)),
                                                              (_, _) -> -1L,
                                                              StreamingConfig.streamingConfig(),
                                                              100);

        assertThat(manager.reserveSend(60).isSuccess()).isTrue();
        var refused = manager.reserveSend(60);

        assertThat(refused.isFailure()).as("60 + 60 bytes pass the budget of 100").isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.REPLICATION_BEHIND));

        manager.close();
    }

    private static Promise<WriteOutcome> counted(AtomicInteger frames,
                                                 AtomicInteger events,
                                                 CountDownLatch allEvents,
                                                 NodeId target,
                                                 ReplicationMessage message) {
        frames.incrementAndGet();
        if (events.addAndGet(((ReplicationMessage.ReplicateEvents) message).payloads().size()) == EVENTS) {
            allEvents.countDown();
        }

        return Promise.success(new WriteOutcome.Sent(target));
    }
}
