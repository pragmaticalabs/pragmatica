package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Map;


/// Watches for governor announcements and maintains community-to-governor mapping.
/// In Phase 2a, this is a simple registry. In Phase 2b, it will watch consensus
/// for community governor announcements.
public interface GovernorDiscovery {
    @SuppressWarnings("JBCT-RET-01") void onGovernorAnnounced(String communityId, NodeId governorId);
    @SuppressWarnings("JBCT-RET-01") void onGovernorDeparted(String communityId);
    Option<NodeId> currentGovernor(String communityId);
    Map<String, NodeId> allKnownGovernors();

    static GovernorDiscovery governorDiscovery() {
        return new GovernorDiscoveryInstance();
    }
}
