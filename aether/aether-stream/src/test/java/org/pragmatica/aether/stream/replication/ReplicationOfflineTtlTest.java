// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationBatcher.replicationBatcher;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

/// #1996: a replicate frame must not outlive the owner's ack wait in a replica's offline buffer. Replication does not
/// retransmit, so that wait is the only deadline the frame has: the owner has by then told its writer the replication
/// failed, and a replica that applied the chunk afterwards would hold an event the owner's writer was told did not
/// replicate. The transport drops the frame at the flush once the wait it was handed has passed, so what is pinned here
/// is that BOTH send sites (the manager's direct path and the batcher's flush) hand over the ack timeout.
class ReplicationOfflineTtlTest {
    private static final NodeId GOVERNOR = NodeId.randomNodeId();
    private static final NodeId REPLICA = NodeId.randomNodeId();
    private static final String STREAM = "events";

    private final List<TimeSpan> lifetimes = new CopyOnWriteArrayList<>();
    private final List<ReplicationMessage> plain = new CopyOnWriteArrayList<>();

    private final ReplicationTransport transport = new ReplicationTransport() {
        @Override
        public void send(NodeId target, ReplicationMessage message) {
            plain.add(message);
        }

        @Override
        public void send(NodeId target, ReplicationMessage message, TimeSpan callerWait) {
            lifetimes.add(callerWait);
        }
    };

    @Test
    void managerDirectPath_handsTheTransportTheAckTimeoutAsTheFrameLifetime() {
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, 0, REPLICA);
        replicationManager(GOVERNOR, registry, transport).replicateEvent(STREAM, 0, 0L, new byte[]{1}, 1L, Epoch.ZERO);

        assertThat(plain).as("a replicate frame is never sent without a frame lifetime").isEmpty();
        assertThat(lifetimes).containsExactly(DefaultReplicationManager.DEFAULT_ACK_TIMEOUT);
    }

    @Test
    void batcherFlush_handsTheTransportTheAckTimeoutAsTheFrameLifetime() {
        var registry = replicaRegistry();

        registry.registerReplica(STREAM, 0, REPLICA);
        var batcher = replicationBatcher(transport, registry, GOVERNOR, 1, TimeSpan.timeSpan(10).seconds());

        try {
            batcher.add(STREAM, 0, 0L, new byte[]{1}, 1L, Epoch.ZERO);

            assertThat(plain).isEmpty();
            assertThat(lifetimes).containsExactly(DefaultReplicationManager.DEFAULT_ACK_TIMEOUT);
        } finally {
            batcher.close();
        }
    }
}
