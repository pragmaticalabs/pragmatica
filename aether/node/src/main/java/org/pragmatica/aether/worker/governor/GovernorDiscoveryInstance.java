// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
        Option.option(knownGovernors.remove(communityId)).onPresent(removed -> LOG.info("Governor departed for community '{}': {}",
                                                                                        communityId,
                                                                                        removed));
    }

    @Override
    public Option<NodeId> currentGovernor(String communityId) {
        return Option.option(knownGovernors.get(communityId));
    }

    @Override
    public Map<String, NodeId> allKnownGovernors() {
        return Map.copyOf(knownGovernors);
    }

    // RET-06: `previous` is the nullable prior value returned by JDK Map.put (absent key → null) —
    // a framework boundary, not a business optional.
    @SuppressWarnings("JBCT-RET-06")
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
