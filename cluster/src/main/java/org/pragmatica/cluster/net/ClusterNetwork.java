package org.pragmatica.cluster.net;

import org.pragmatica.cluster.consensus.ProtocolMessage;

import java.util.function.Consumer;

/// Generalized Network API
public interface ClusterNetwork<T extends ProtocolMessage> {
    /// Broadcast a message to all nodes in the cluster
    /// Note that actual implementation may just send messages directly, not necessarily use broadcasting in
    /// underlying protocol.
    <M extends ProtocolMessage> void broadcast(M message);

    /// Send a message to a specific node
    <M extends ProtocolMessage> void send(NodeId nodeId, M message);

    /// Current state of the network from the point of view of the current node.
    /// If there are enough nodes connected, then this method returns `true`.
    /// Connected nodes are not necessarily participating in the consensus.
    boolean quorumConnected();

    /// Trigger attempt to connect to a specific node.
    void connect(NodeId nodeId);

    /// Disconnect from the specific node
    void disconnect(NodeId nodeId);

    /// Install observer which will receive notifications when topology changes
    void observeViewChanges(Consumer<TopologyChange> observer);

    /// Install the listener for the messages received from the network.
    /// In typical configuration the listener is the protocol engine.
    void listen(Consumer<T> listener);

    /// Start the network.
    void start();

    /// Stop the network.
    void stop();

    /// Listen for the notifications generated for quorum appearance and disappearance.
    /// Note that there is exactly one notification for each state change, i.e., quorum appears when enough
    /// nodes are connected, but further connections do not trigger the notification. The same is true for
    /// the disappearance of the quorum: notification is triggered when the number of connected nodes falls below
    /// the threshold, but further disconnections do not trigger the notification.
    void observeQuorumState(Consumer<QuorumState> quorumObserver);
}
