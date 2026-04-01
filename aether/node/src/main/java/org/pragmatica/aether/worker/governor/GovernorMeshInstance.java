package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default implementation of the governor mesh registry.
/// Optionally registers peer addresses in cluster network for QUIC connectivity.
@SuppressWarnings("JBCT-RET-01")
final class GovernorMeshInstance implements GovernorMesh {
    private static final Logger LOG = LoggerFactory.getLogger(GovernorMeshInstance.class);

    private final Map<String, NodeId> governors = new ConcurrentHashMap<>();
    private final Option<DelegateRouter> delegateRouter;

    GovernorMeshInstance() {
        this.delegateRouter = Option.empty();
    }

    GovernorMeshInstance(DelegateRouter delegateRouter) {
        this.delegateRouter = Option.option(delegateRouter);
    }

    @Override public void registerGovernor(String communityId, NodeId governorId) {
        registerGovernor(communityId, governorId, "");
    }

    @Override public void registerGovernor(String communityId, NodeId governorId, String tcpAddress) {
        governors.put(communityId, governorId);
        registerPeerAddress(governorId, tcpAddress);
        LOG.debug("Registered governor {} for community '{}' at {}", governorId, communityId, tcpAddress);
    }

    @Override public void unregisterGovernor(String communityId) {
        Option.option(governors.remove(communityId)).onPresent(removed -> handleGovernorRemoval(removed, communityId));
    }

    private void handleGovernorRemoval(NodeId removed, String communityId) {
        delegateRouter.onPresent(router -> router.route(new NetworkServiceMessage.DisconnectNode(removed)));
        LOG.debug("Unregistered governor {} for community '{}'", removed, communityId);
    }

    @Override public Option<NodeId> governorFor(String communityId) {
        return Option.option(governors.get(communityId));
    }

    @Override public Map<String, NodeId> allGovernors() {
        return Map.copyOf(governors);
    }

    @Override public boolean hasGovernor(String communityId) {
        return governors.containsKey(communityId);
    }

    private void registerPeerAddress(NodeId governorId, String tcpAddress) {
        if ( tcpAddress == null || tcpAddress.isEmpty()) {
        return;}
        delegateRouter.onPresent(router -> parseTcpAddress(tcpAddress)
        .onPresent(addr -> registerInTopology(router, governorId, addr)));
    }

    private static void registerInTopology(DelegateRouter router, NodeId governorId, NodeAddress addr) {
        router.route(new TopologyManagementMessage.AddNode(NodeInfo.nodeInfo(governorId, addr, NodeRole.PASSIVE)));
        router.route(new NetworkServiceMessage.ConnectNode(governorId));
    }

    private static Option<NodeAddress> parseTcpAddress(String tcpAddress) {
        var parts = tcpAddress.split(":");
        if ( parts.length != 2) {
        return Option.empty();}
        return Number.parseInt(parts[1]).option()
                              .flatMap(port -> NodeAddress.nodeAddress(parts[0], port).option());
    }
}
