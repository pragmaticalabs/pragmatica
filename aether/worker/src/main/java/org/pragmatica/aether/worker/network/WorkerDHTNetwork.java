package org.pragmatica.aether.worker.network;

import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.DHTNetwork;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// DHT network adapter for workers.
/// Phase 2b.5a: routes DHT messages directly via WorkerNetwork (intra-community).
/// Phase 2b.5b: adds cross-community routing through governor mesh relay.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public final class WorkerDHTNetwork implements DHTNetwork {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerDHTNetwork.class);

    private final WorkerNetwork workerNetwork;
    private final Option<GovernorMesh> governorMesh;
    private final Map<String, List<NodeId>> communityMembers;
    private final Option<Serializer> serializer;
    private final Supplier<String> selfCommunityId;

    private WorkerDHTNetwork(WorkerNetwork workerNetwork,
                             Option<GovernorMesh> governorMesh,
                             Map<String, List<NodeId>> communityMembers,
                             Option<Serializer> serializer,
                             Supplier<String> selfCommunityId) {
        this.workerNetwork = workerNetwork;
        this.governorMesh = governorMesh;
        this.communityMembers = communityMembers;
        this.serializer = serializer;
        this.selfCommunityId = selfCommunityId;
    }

    /// Create a simple intra-community-only DHT network (backward compat).
    public static WorkerDHTNetwork workerDHTNetwork(WorkerNetwork workerNetwork) {
        return new WorkerDHTNetwork(workerNetwork, Option.empty(), Map.of(), Option.empty(), () -> "");
    }

    /// Create a cross-community-aware DHT network with governor mesh relay.
    public static WorkerDHTNetwork workerDHTNetwork(WorkerNetwork workerNetwork,
                                                    GovernorMesh governorMesh,
                                                    Map<String, List<NodeId>> communityMembers,
                                                    Serializer serializer,
                                                    Supplier<String> selfCommunityId) {
        return new WorkerDHTNetwork(workerNetwork,
                                    Option.option(governorMesh),
                                    communityMembers,
                                    Option.option(serializer),
                                    selfCommunityId);
    }

    @Override
    public void send(NodeId target, ProtocolMessage message) {
        if (isLocalPeer(target)) {
            workerNetwork.send(target, message);
            return;
        }
        if (tryCrossCommunityRelay(target, message)) {
            return;
        }
        workerNetwork.send(target, message);
    }

    private boolean isLocalPeer(NodeId target) {
        return workerNetwork.connectedPeers()
                            .contains(target);
    }

    private boolean tryCrossCommunityRelay(NodeId target, ProtocolMessage message) {
        return governorMesh.flatMap(mesh -> serializer.map(ser -> relayCrossCommunity(mesh, ser, target, message)))
                           .or(false);
    }

    private boolean relayCrossCommunity(GovernorMesh mesh,
                                        Serializer ser,
                                        NodeId target,
                                        ProtocolMessage message) {
        var targetCommunity = findCommunityFor(target);
        if (targetCommunity.isEmpty()) {
            return false;
        }
        var community = targetCommunity.unwrap();
        var governor = mesh.governorFor(community);
        if (governor.isEmpty()) {
            LOG.warn("No governor for community '{}' to relay DHT message to {}", community, target.id());
            return false;
        }
        var payload = ser.encode(message);
        var relay = DHTRelayMessage.dhtRelayMessage(target, payload);
        workerNetwork.send(governor.unwrap(), relay);
        LOG.debug("Relayed DHT message to {} via governor {} in community '{}'",
                  target.id(),
                  governor.unwrap()
                          .id(),
                  community);
        return true;
    }

    private Option<String> findCommunityFor(NodeId target) {
        for (var entry : communityMembers.entrySet()) {
            if (entry.getValue()
                     .contains(target)) {
                return Option.option(entry.getKey());
            }
        }
        return Option.empty();
    }
}
