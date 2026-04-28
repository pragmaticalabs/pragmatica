// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.topology;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;


public record ClusterTopology(int totalNodes,
                              int healthyNodes,
                              int quorumSize,
                              boolean hasQuorum,
                              Option<String> leaderId,
                              List<NodeInfo> nodes,
                              Map<String, SliceInfo> slices) {
    public static final ClusterTopology EMPTY = new ClusterTopology(0, 0, 0, false, Option.empty(), List.of(), Map.of());

    public record NodeInfo(String nodeId, String address, String status, boolean isLeader, long lastSeen) {
        public static NodeInfo nodeInfo(String nodeId, String address, boolean isLeader) {
            return new NodeInfo(nodeId, address, "HEALTHY", isLeader, System.currentTimeMillis());
        }

        public static NodeInfo suspectedNodeInfo(String nodeId, String address) {
            return new NodeInfo(nodeId, address, "SUSPECTED", false, System.currentTimeMillis());
        }

        public static NodeInfo downNodeInfo(String nodeId, String address, long lastSeen) {
            return new NodeInfo(nodeId, address, "DOWN", false, lastSeen);
        }
    }

    public record SliceInfo(String artifact,
                            int desiredInstances,
                            int activeInstances,
                            Map<String, Integer> nodeDistribution) {
        public boolean isHealthy() {
            return activeInstances >= desiredInstances;
        }

        public double availability() {
            if (desiredInstances <= 0) {return 1.0;}
            return Math.min(1.0, (double) activeInstances / desiredInstances);
        }
    }

    public double healthScore() {
        if (totalNodes == 0) {return 0.0;}
        double nodeHealth = (double) healthyNodes / totalNodes;
        double quorumHealth = hasQuorum
                             ? 1.0
                             : 0.0;
        double leaderHealth = leaderId.isPresent()
                             ? 1.0
                             : 0.0;
        return (nodeHealth * 0.4 + quorumHealth * 0.4 + leaderHealth * 0.2);
    }

    public boolean healthy() {
        return hasQuorum && leaderId.isPresent() && healthyNodes == totalNodes;
    }

    public boolean degraded() {
        return hasQuorum && (healthyNodes <totalNodes || leaderId.isEmpty());
    }

    public boolean critical() {
        return ! hasQuorum;
    }
}
