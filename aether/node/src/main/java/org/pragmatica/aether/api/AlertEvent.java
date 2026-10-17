// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;


public sealed interface AlertEvent {
    enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    String alertId();
    long timestamp();
    Severity severity();

    record ThresholdAlert(String alertId,
                          long timestamp,
                          Severity severity,
                          String metric,
                          NodeId nodeId,
                          double value,
                          double threshold) implements AlertEvent {}

    record SliceFailureAlert(String alertId,
                             long timestamp,
                             Severity severity,
                             Artifact artifact,
                             MethodName method,
                             String requestId,
                             List<NodeId> attemptedNodes,
                             String lastError) implements AlertEvent {
        public static SliceFailureAlert sliceFailureAlert(String alertId,
                                                          Artifact artifact,
                                                          MethodName method,
                                                          String requestId,
                                                          List<NodeId> attemptedNodes,
                                                          String lastError) {
            return new SliceFailureAlert(alertId,
                                         System.currentTimeMillis(),
                                         Severity.CRITICAL,
                                         artifact,
                                         method,
                                         requestId,
                                         attemptedNodes,
                                         lastError);
        }
    }

    record AlertResolved(String alertId, long timestamp, Severity severity, String resolvedBy) implements AlertEvent {
        public static AlertResolved resolved(String alertId, String resolvedBy) {
            return new AlertResolved(alertId, System.currentTimeMillis(), Severity.INFO, resolvedBy);
        }
    }

    /// Node-health alert (#926). Before this variant existed the alert surface had NO node-health path
    /// at all: the only producers were `AlertManager.onAllInstancesFailed` (slice-level) and
    /// `checkThreshold` (metric-level), so a cluster member could be confirmed dead without any alert
    /// being raised anywhere. Measured over ten days on a five-node cluster, three nodes sat unhealthy
    /// for nine days with nothing to notice.
    ///
    /// Raised from the ungated FSM DEAD edge, so it does NOT depend on leader election — the defect
    /// this variant exists to close. Alert state is per-node local memory exposed through that node's
    /// own `/api/alerts`, so unlike the cluster-events stream it needs no leader, quorum, replica or
    /// partition ownership to be raised OR read back.
    ///
    /// [`#alertId`] is derived solely from the failed node, so re-observing the same death is
    /// idempotent (a repeated raise replaces the entry rather than accumulating) and the matching
    /// clear on rejoin can address it without carrying state. Duplicate-free by construction — each
    /// node keeps its own alert map, so there is no cross-node fan-out to deduplicate.
    record NodeHealthAlert(String alertId,
                           long timestamp,
                           Severity severity,
                           NodeId nodeId,
                           NodeId observedBy,
                           String reason) implements AlertEvent {
        public static NodeHealthAlert nodeFailed(NodeId nodeId, NodeId observedBy) {
            return new NodeHealthAlert(alertId(nodeId),
                                       System.currentTimeMillis(),
                                       Severity.CRITICAL,
                                       nodeId,
                                       observedBy,
                                       "Confirmed departure (FSM DEAD edge)");
        }

        public static String alertId(NodeId nodeId) {
            return "node.failed:" + nodeId.id();
        }
    }
}
