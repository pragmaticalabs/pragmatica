package org.pragmatica.aether.worker.governor;

import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.parse.Number;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default implementation of the governor mesh registry.
/// Optionally registers peer addresses in WorkerNetwork for TCP connectivity.
@SuppressWarnings("JBCT-RET-01")
final class GovernorMeshInstance implements GovernorMesh {
    private static final Logger LOG = LoggerFactory.getLogger(GovernorMeshInstance.class);

    private final Map<String, NodeId> governors = new ConcurrentHashMap<>();
    private final Option<WorkerNetwork> workerNetwork;

    GovernorMeshInstance() {
        this.workerNetwork = Option.empty();
    }

    GovernorMeshInstance(WorkerNetwork workerNetwork) {
        this.workerNetwork = Option.option(workerNetwork);
    }

    @Override
    public void registerGovernor(String communityId, NodeId governorId) {
        registerGovernor(communityId, governorId, "");
    }

    @Override
    public void registerGovernor(String communityId, NodeId governorId, String tcpAddress) {
        governors.put(communityId, governorId);
        registerPeerAddress(governorId, tcpAddress);
        LOG.debug("Registered governor {} for community '{}' at {}", governorId, communityId, tcpAddress);
    }

    @Override
    public void unregisterGovernor(String communityId) {
        Option.option(governors.remove(communityId))
              .onPresent(removed -> handleGovernorRemoval(removed, communityId));
    }

    private void handleGovernorRemoval(NodeId removed, String communityId) {
        workerNetwork.onPresent(net -> net.removePeer(removed));
        LOG.debug("Unregistered governor {} for community '{}'", removed, communityId);
    }

    @Override
    public Option<NodeId> governorFor(String communityId) {
        return Option.option(governors.get(communityId));
    }

    @Override
    public Map<String, NodeId> allGovernors() {
        return Map.copyOf(governors);
    }

    @Override
    public boolean hasGovernor(String communityId) {
        return governors.containsKey(communityId);
    }

    private void registerPeerAddress(NodeId governorId, String tcpAddress) {
        if (tcpAddress == null || tcpAddress.isEmpty()) {
            return;
        }
        workerNetwork.onPresent(net -> parseTcpAddress(tcpAddress)
        .onPresent(addr -> net.registerPeer(governorId, addr)));
    }

    private static Option<InetSocketAddress> parseTcpAddress(String tcpAddress) {
        var parts = tcpAddress.split(":");
        if (parts.length != 2) {
            return Option.empty();
        }
        return Number.parseInt(parts[1])
                     .option()
                     .map(port -> new InetSocketAddress(parts[0], port));
    }
}
