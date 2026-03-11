package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default implementation of the governor mesh registry.
final class GovernorMeshInstance implements GovernorMesh {
    private static final Logger LOG = LoggerFactory.getLogger(GovernorMeshInstance.class);

    private final Map<String, NodeId> governors = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void registerGovernor(String communityId, NodeId governorId) {
        governors.put(communityId, governorId);
        LOG.debug("Registered governor {} for community '{}'", governorId, communityId);
    }

    @Override
    @SuppressWarnings("JBCT-RET-01")
    public void unregisterGovernor(String communityId) {
        Option.option(governors.remove(communityId))
              .onPresent(removed -> LOG.debug("Unregistered governor {} for community '{}'", removed, communityId));
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
}
