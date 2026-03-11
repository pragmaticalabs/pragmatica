package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default implementation of governor discovery registry.
final class GovernorDiscoveryInstance implements GovernorDiscovery {
    private static final Logger LOG = LoggerFactory.getLogger(GovernorDiscoveryInstance.class);

    private final Map<String, NodeId> knownGovernors = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onGovernorAnnounced(String communityId, NodeId governorId) {
        var previous = knownGovernors.put(communityId, governorId);
        logGovernorChange(communityId, governorId, previous);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void onGovernorDeparted(String communityId) {
        Option.option(knownGovernors.remove(communityId))
              .onPresent(removed -> LOG.info("Governor departed for community '{}': {}", communityId, removed));
    }

    @Override
    public Option<NodeId> currentGovernor(String communityId) {
        return Option.option(knownGovernors.get(communityId));
    }

    @Override
    public Map<String, NodeId> allKnownGovernors() {
        return Map.copyOf(knownGovernors);
    }

    private static void logGovernorChange(String communityId, NodeId governorId, NodeId previous) {
        if (previous != null && !previous.equals(governorId)) {
            LOG.info("Governor changed for community '{}': {} -> {}",
                     communityId,
                     previous,
                     governorId);
        } else if (previous == null) {
            LOG.info("New governor discovered for community '{}': {}", communityId, governorId);
        }
    }
}
