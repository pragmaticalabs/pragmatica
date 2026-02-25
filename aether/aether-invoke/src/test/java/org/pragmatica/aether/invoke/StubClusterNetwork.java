package org.pragmatica.aether.invoke;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.Server;

import java.util.Set;

import static org.pragmatica.lang.Unit.unit;

/// Minimal stub for ClusterNetwork used in unit tests.
@SuppressWarnings("JBCT-RET-01")
class StubClusterNetwork implements ClusterNetwork {
    @Override
    public <M extends ProtocolMessage> Unit broadcast(M message) {
        return unit();
    }

    @Override
    public void connect(NetworkServiceMessage.ConnectNode connectNode) {}

    @Override
    public void disconnect(NetworkServiceMessage.DisconnectNode disconnectNode) {}

    @Override
    public void listNodes(NetworkServiceMessage.ListConnectedNodes listConnectedNodes) {}

    @Override
    public void handleSend(NetworkServiceMessage.Send send) {}

    @Override
    public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {}

    @Override
    public void handlePing(NetworkMessage.Ping ping) {}

    @Override
    public void handlePong(NetworkMessage.Pong pong) {}

    @Override
    public <M extends ProtocolMessage> Unit send(NodeId nodeId, M message) {
        return unit();
    }

    @Override
    public Promise<Unit> start() {
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.success(unit());
    }

    @Override
    public int connectedNodeCount() {
        return 0;
    }

    @Override
    public Set<NodeId> connectedPeers() {
        return Set.of();
    }

    @Override
    public Option<Server> server() {
        return Option.none();
    }
}
