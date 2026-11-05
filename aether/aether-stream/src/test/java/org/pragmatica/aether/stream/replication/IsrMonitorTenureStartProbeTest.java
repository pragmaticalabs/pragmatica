// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #2694 probe: at the start of an owner tenure, ISR member P has not confirmed to the new owner yet (registered, no
/// row), and X — registered, outside the ISR, holding nothing — must not be admitted while the owner's head is 10:
/// the records acknowledged in earlier tenures are on P, not on X.
class IsrMonitorTenureStartProbeTest {
    private static final NodeId OWNER = new NodeId("owner");
    private static final NodeId P = new NodeId("node-p");
    private static final NodeId X = new NodeId("node-x");
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long HEAD = 10L;

    @Test
    void tenureStart_memberWithNoRow_registeredReplicaAtMinusOne_isNotAdmitted() {
        var registry = replicaRegistry();

        List.of(OWNER, P, X).forEach(node -> registry.registerReplica(STREAM, PARTITION, node));
        var record = StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER,
                                                                                 Epoch.epoch(1L, 2L, 1L),
                                                                                 1L,
                                                                                 HlcTimestamp.ZERO,
                                                                                 List.of(OWNER, P),
                                                                                 1L);
        var monitor = IsrMonitor.isrMonitor(OWNER,
                                            List::of,
                                            registry,
                                            () -> Option.some(new LeaderValue(OWNER, 1L)),
                                            _ -> Promise.success(List.of()),
                                            TimeSpan.timeSpan(30).seconds(),
                                            () -> 0L);

        assertThat(monitor.nextIsr(new IsrMonitor.Owned(STREAM, PARTITION, record, HEAD), 0L).isEmpty())
            .as("X holds none of the records acknowledged before this tenure")
            .isTrue();
    }
}
