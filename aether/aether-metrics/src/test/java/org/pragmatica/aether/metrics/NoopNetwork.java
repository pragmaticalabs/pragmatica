// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import java.util.Set;


/// No-op `ClusterNetwork` for unit tests that don't care about wire traffic.
public class NoopNetwork implements ClusterNetwork {
    @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return Unit.unit();}
    @Override public void connect(ConnectNode connectNode) {}
    @Override public void disconnect(DisconnectNode disconnectNode) {}
    @Override public void listNodes(ListConnectedNodes listConnectedNodes) {}
    @Override public void handleSend(Send send) {}
    @Override public void handleBroadcast(Broadcast broadcast) {}
    @Override public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {return Unit.unit();}
    @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
    @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
    @Override public int connectedNodeCount() {return 0;}
    @Override public Set<NodeId> connectedPeers() {return Set.of();}
    @Override public Option<Server> server() {return Option.none();}
}
