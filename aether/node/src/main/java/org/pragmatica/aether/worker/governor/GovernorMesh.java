// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import java.util.Map;


public interface GovernorMesh {
    @SuppressWarnings("JBCT-RET-01") void registerGovernor(String communityId, NodeId governorId);
    @SuppressWarnings("JBCT-RET-01") void registerGovernor(String communityId, NodeId governorId, String tcpAddress);
    @SuppressWarnings("JBCT-RET-01") void unregisterGovernor(String communityId);
    Option<NodeId> governorFor(String communityId);
    Map<String, NodeId> allGovernors();
    boolean hasGovernor(String communityId);

    static GovernorMesh governorMesh() {
        return new GovernorMeshInstance();
    }

    static GovernorMesh governorMesh(DelegateRouter delegateRouter) {
        return new GovernorMeshInstance(delegateRouter);
    }

    static GovernorMesh governorMesh(DelegateRouter delegateRouter, TopologyObserver topologyObserver) {
        return new GovernorMeshInstance(delegateRouter, topologyObserver);
    }
}
