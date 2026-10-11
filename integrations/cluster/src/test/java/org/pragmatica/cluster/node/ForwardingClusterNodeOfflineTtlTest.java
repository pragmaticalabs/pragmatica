// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.cluster.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.node.forward.ForwardApplyRequest;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #1996: a `ForwardApplyRequest` must not outlive, in a core peer's offline buffer, the 30s the worker waits for its answer.
/// A worker that was told the apply timed out would otherwise see the commands applied on the core minutes later, on
/// reattach. The transport drops the frame at the flush once the wait it was handed has passed, so what is pinned here is
/// the wait `apply` hands over: the same 30s that arms its timeout.
class ForwardingClusterNodeOfflineTtlTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId CORE = NodeId.randomNodeId();

    private record Sent(ProtocolMessage message, TimeSpan lifetime) {}

    private record Cmd(String name) implements Command {}

    @Test
    void apply_handsTheTransportItsOwnThirtySecondWaitAsTheFrameLifetime() {
        var bounded = new CopyOnWriteArrayList<Sent>();
        var plain = new CopyOnWriteArrayList<ProtocolMessage>();
        var underlying = mock(ClusterNode.class);
        when(underlying.self()).thenReturn(SELF);
        @SuppressWarnings("unchecked")
        var node = ForwardingClusterNode.<Cmd>forwardingClusterNode(underlying, recording(bounded, plain), Set.of(CORE));

        var pending = node.apply(List.of(new Cmd("put")));

        assertThat(plain).as("a forwarded apply is never sent without a frame lifetime").isEmpty();
        assertThat(bounded).hasSize(1);
        assertThat(bounded.getFirst().message()).isInstanceOf(ForwardApplyRequest.class);
        assertThat(bounded.getFirst().lifetime().millis()).isEqualTo(TimeSpan.timeSpan(30).seconds().millis());
        assertThat(pending.isResolved()).as("the caller is still waiting: the lifetime is its wait, not an early failure").isFalse();
    }

    private static ClusterNetwork recording(List<Sent> bounded, List<ProtocolMessage> plain) {
        return new ClusterNetwork() {
            @Override
            public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
                plain.add(message);

                return Unit.unit();
            }

            @Override
            public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message, TimeSpan offlineTtl) {
                bounded.add(new Sent(message, offlineTtl));

                return Unit.unit();
            }

            @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return Unit.unit();}
            @Override public void connect(org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode connectNode) {}
            @Override public void disconnect(org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode disconnectNode) {}
            @Override public void listNodes(org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}
            @Override public void handleSend(org.pragmatica.consensus.net.NetworkServiceMessage.Send send) {}
            @Override public void handleBroadcast(org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast broadcast) {}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public int connectedNodeCount() {return 0;}
            @Override public Set<NodeId> connectedPeers() {return Set.of();}
            @Override public Option<Server> server() {return Option.none();}
        };
    }
}
