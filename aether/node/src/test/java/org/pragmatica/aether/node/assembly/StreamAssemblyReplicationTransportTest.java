// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2583 P1: the network-backed replication transport reports the cluster network's own outcome, and its caller-wait
/// overload reaches the network's TTL-bounded send with that wait.
class StreamAssemblyReplicationTransportTest {
    private static final NodeId TARGET = new NodeId("replica-target");
    private static final TimeSpan CALLER_WAIT = TimeSpan.timeSpan(5).seconds();
    private static final ReplicationMessage MESSAGE = replicateAck(new NodeId("replica-self"), "events", 0, 7);

    @Test
    void send_returnsTheNetworksOutcome() {
        var network = mock(ClusterNetwork.class);
        var refused = Promise.<WriteOutcome>success(new WriteOutcome.BackpressureRefused(TARGET));

        when(network.send(TARGET, MESSAGE)).thenReturn(refused);

        assertThat(StreamAssembly.replicationTransport(network).send(TARGET, MESSAGE)).isSameAs(refused);
    }

    @Test
    void sendWithCallerWait_reachesTheTtlBoundedSend_andReturnsItsOutcome() {
        var network = mock(ClusterNetwork.class);
        var dead = Promise.<WriteOutcome>success(new WriteOutcome.ConnectionDead(TARGET));

        when(network.send(TARGET, MESSAGE, CALLER_WAIT)).thenReturn(dead);

        assertThat(StreamAssembly.replicationTransport(network).send(TARGET, MESSAGE, CALLER_WAIT)).isSameAs(dead);
        verify(network).send(TARGET, MESSAGE, CALLER_WAIT);
        verify(network, never()).send(any(NodeId.class), any(ReplicationMessage.class));
    }
}
