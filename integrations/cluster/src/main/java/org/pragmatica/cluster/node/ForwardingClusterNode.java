package org.pragmatica.cluster.node;

import org.pragmatica.cluster.node.forward.ForwardApplyRequest;
import org.pragmatica.cluster.node.forward.ForwardApplyResponse;
import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A ClusterNode that forwards apply() calls to a core peer via TCP.
/// Used by worker nodes that cannot participate in consensus directly.
public final class ForwardingClusterNode<C extends Command> implements ClusterNode<C> {
    private static final Logger log = LoggerFactory.getLogger(ForwardingClusterNode.class);

    private final ClusterNode<C> underlying;
    private final ClusterNetwork network;
    private final AtomicLong correlationCounter = new AtomicLong();
    @SuppressWarnings("rawtypes")
    private final Map<Long, Promise> pendingRequests = new ConcurrentHashMap<>();
    private volatile NodeId[] peerArray;
    private final AtomicLong roundRobinIndex = new AtomicLong();

    private ForwardingClusterNode(ClusterNode<C> underlying,
                                  ClusterNetwork network,
                                  Set<NodeId> corePeers) {
        this.underlying = underlying;
        this.network = network;
        this.peerArray = corePeers.toArray(new NodeId[0]);
    }

    public static <C extends Command> ForwardingClusterNode<C> forwardingClusterNode(
        ClusterNode<C> underlying,
        ClusterNetwork network,
        Set<NodeId> corePeers) {
        return new ForwardingClusterNode<>(underlying, network, corePeers);
    }

    /// Update the set of core peers for forwarding.
    public void updateCorePeers(Set<NodeId> newCorePeers) {
        peerArray = newCorePeers.toArray(new NodeId[0]);
    }

    /// Handle a response from a core node. Called by the message router.
    @SuppressWarnings("unchecked")
    public void onForwardApplyResponse(ForwardApplyResponse<?> response) {
        var promise = pendingRequests.remove(response.correlationId());

        if (promise == null) {
            log.warn("Received ForwardApplyResponse for unknown correlation {}", response.correlationId());
            return;
        }

        response.error()
                .onPresent(errorMessage -> promise.fail(Causes.cause(errorMessage)))
                .onEmpty(() -> promise.succeed(response.results()));
    }

    @Override
    public NodeId self() {
        return underlying.self();
    }

    @Override
    public TopologyManager topologyManager() {
        return underlying.topologyManager();
    }

    @Override
    public Promise<Unit> start() {
        return underlying.start();
    }

    @Override
    public Promise<Unit> stop() {
        pendingRequests.forEach((_, promise) -> promise.fail(Causes.cause("ForwardingClusterNode stopped")));
        pendingRequests.clear();
        return underlying.stop();
    }

    @Override
    public <R> Promise<List<R>> apply(List<C> commands) {
        var peers = peerArray;

        if (peers.length == 0) {
            return Causes.cause("No core peers available for forwarding").promise();
        }

        var correlationId = correlationCounter.incrementAndGet();
        var target = selectPeer(peers);
        var promise = Promise.<List<R>>promise();

        pendingRequests.put(correlationId, promise);
        network.send(target, new ForwardApplyRequest<>(self(), correlationId, commands));

        return promise.timeout(timeSpan(30).seconds())
                      .onResultRun(() -> pendingRequests.remove(correlationId));
    }

    private NodeId selectPeer(NodeId[] peers) {
        var index = (int) (roundRobinIndex.getAndIncrement() % peers.length);
        return peers[Math.abs(index)];
    }
}
