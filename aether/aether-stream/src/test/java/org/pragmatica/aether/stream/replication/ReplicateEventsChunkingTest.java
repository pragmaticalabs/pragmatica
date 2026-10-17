// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.QuicClusterServer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #1287 review nit (a): a `ReplicateEvents` chunk is capped by its ENCODED size, not by payload bytes
/// alone. Each event adds framing (a varint type tag and a varint length for the payload, a tag and eight
/// bytes for the timestamp), so millions of tiny events could exceed the cluster frame limit while their
/// payload bytes alone look small.
class ReplicateEventsChunkingTest {
    /// Worst-case framing the generic codec writes per event: a payload tag and length (varints, at most
    /// five bytes each) plus a timestamp tag (at most five) and its eight bytes.
    private static final long WORST_CASE_FRAMING_PER_EVENT = 5 + 5 + 5 + 8;

    @Test
    void replicateEvents_manyTinyEvents_splitsByEncodedSize_underTheFrameLimit() {
        var self = NodeId.nodeId("self").unwrap();
        var replica = NodeId.nodeId("replica").unwrap();
        var registry = replicaRegistry();
        var sent = new ConcurrentLinkedQueue<ReplicationMessage.ReplicateEvents>();

        registry.registerReplica("s", 0, self);
        registry.registerReplica("s", 0, replica);
        var manager = ReplicationManager.replicationManager(self, registry, (_, message) -> record(sent, message));
        // Payload bytes alone (1.6 MB) fit one chunk many times over, but one message carrying all of them
        // would encode to more than the frame limit once each event's framing is counted.
        var count = QuicClusterServer.MAX_FRAME_LENGTH / 20;
        var payloads = Collections.nCopies(count, new byte[]{1});
        var timestamps = Collections.nCopies(count, 1L);

        manager.replicateEvents("s", 0, 0L, payloads, timestamps, Epoch.ZERO);

        assertThat(sent).as("ReplicateEvents messages").hasSizeGreaterThanOrEqualTo(2);
        assertThat(sent.stream().mapToInt(message -> message.payloads().size()).sum()).as("every event is sent")
                                                                                    .isEqualTo(count);
        sent.forEach(message -> assertThat(encodedUpperBound(message)).as("worst-case encoded size of one message")
                                                                        .isLessThan(QuicClusterServer.MAX_FRAME_LENGTH));
        assertThat(List.copyOf(sent).getLast().fromOffset() + List.copyOf(sent).getLast().payloads().size())
            .as("chunks are contiguous from offset 0")
            .isEqualTo(count);
    }

    private static long encodedUpperBound(ReplicationMessage.ReplicateEvents message) {
        return message.payloads().stream().mapToLong(payload -> payload.length + WORST_CASE_FRAMING_PER_EVENT).sum();
    }

    private static void record(ConcurrentLinkedQueue<ReplicationMessage.ReplicateEvents> sent, ReplicationMessage message) {
        if (message instanceof ReplicationMessage.ReplicateEvents events) {
            sent.add(events);
        }
    }
}
