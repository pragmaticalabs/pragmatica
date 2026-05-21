// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;


/// User-facing DTO surfaced via `/api/events`.
///
/// RC1 Step 1: the canonical persisted form is `ClusterEventValue` in `aether/slice`
/// (replicated via Rabia). `ClusterEvent` is the dashboard-shaped projection: wall-clock
/// `Instant timestamp` instead of HLC, originator nodeId folded into `details`.
///
/// `EventType` and `Severity` are aliases of the canonical enums on `ClusterEventValue`,
/// re-exported here so existing callers (AlertEvent, tests, etc.) keep using
/// `ClusterEvent.EventType.NODE_FAILED` / `ClusterEvent.Severity.CRITICAL` unchanged.
public record ClusterEvent(Instant timestamp,
                           ClusterEventValue.EventType type,
                           ClusterEventValue.Severity severity,
                           String summary,
                           Map<String, String> details) {
    /// Alias of `ClusterEventValue.EventType`.
    public static final class EventType {
        private EventType() {}

        public static final ClusterEventValue.EventType NODE_JOINED = ClusterEventValue.EventType.NODE_JOINED;
        public static final ClusterEventValue.EventType NODE_LEFT = ClusterEventValue.EventType.NODE_LEFT;
        public static final ClusterEventValue.EventType NODE_FAILED = ClusterEventValue.EventType.NODE_FAILED;
        public static final ClusterEventValue.EventType LEADER_ELECTED = ClusterEventValue.EventType.LEADER_ELECTED;
        public static final ClusterEventValue.EventType LEADER_LOST = ClusterEventValue.EventType.LEADER_LOST;

        public static final ClusterEventValue.EventType QUORUM_ESTABLISHED = ClusterEventValue.EventType.QUORUM_ESTABLISHED;

        public static final ClusterEventValue.EventType QUORUM_LOST = ClusterEventValue.EventType.QUORUM_LOST;

        public static final ClusterEventValue.EventType DEPLOYMENT_STARTED = ClusterEventValue.EventType.DEPLOYMENT_STARTED;

        public static final ClusterEventValue.EventType DEPLOYMENT_COMPLETED = ClusterEventValue.EventType.DEPLOYMENT_COMPLETED;

        public static final ClusterEventValue.EventType DEPLOYMENT_FAILED = ClusterEventValue.EventType.DEPLOYMENT_FAILED;

        public static final ClusterEventValue.EventType SCALE_UP = ClusterEventValue.EventType.SCALE_UP;
        public static final ClusterEventValue.EventType SCALE_DOWN = ClusterEventValue.EventType.SCALE_DOWN;
        public static final ClusterEventValue.EventType SLICE_FAILURE = ClusterEventValue.EventType.SLICE_FAILURE;

        public static final ClusterEventValue.EventType CONNECTION_ESTABLISHED = ClusterEventValue.EventType.CONNECTION_ESTABLISHED;

        public static final ClusterEventValue.EventType CONNECTION_FAILED = ClusterEventValue.EventType.CONNECTION_FAILED;

        public static final ClusterEventValue.EventType COMMUNITY_SCALE_REQUEST = ClusterEventValue.EventType.COMMUNITY_SCALE_REQUEST;

        public static final ClusterEventValue.EventType COMMUNITY_METRICS_SNAPSHOT = ClusterEventValue.EventType.COMMUNITY_METRICS_SNAPSHOT;

        public static final ClusterEventValue.EventType ACCESS_DENIED = ClusterEventValue.EventType.ACCESS_DENIED;

        public static final ClusterEventValue.EventType NODE_LIFECYCLE_CHANGED = ClusterEventValue.EventType.NODE_LIFECYCLE_CHANGED;

        public static final ClusterEventValue.EventType CONFIG_CHANGED = ClusterEventValue.EventType.CONFIG_CHANGED;
        public static final ClusterEventValue.EventType BACKUP_CREATED = ClusterEventValue.EventType.BACKUP_CREATED;
        public static final ClusterEventValue.EventType BACKUP_RESTORED = ClusterEventValue.EventType.BACKUP_RESTORED;

        public static final ClusterEventValue.EventType BLUEPRINT_DEPLOYED = ClusterEventValue.EventType.BLUEPRINT_DEPLOYED;

        public static final ClusterEventValue.EventType BLUEPRINT_DELETED = ClusterEventValue.EventType.BLUEPRINT_DELETED;

        public static final ClusterEventValue.EventType GENERATION_CHANGED = ClusterEventValue.EventType.GENERATION_CHANGED;
    }

    /// Alias of `ClusterEventValue.Severity`.
    public static final class Severity {
        private Severity() {}

        public static final ClusterEventValue.Severity INFO = ClusterEventValue.Severity.INFO;
        public static final ClusterEventValue.Severity WARNING = ClusterEventValue.Severity.WARNING;
        public static final ClusterEventValue.Severity CRITICAL = ClusterEventValue.Severity.CRITICAL;
    }

    public static ClusterEvent clusterEvent(ClusterEventValue.EventType type,
                                            ClusterEventValue.Severity severity,
                                            String summary,
                                            Map<String, String> details) {
        return new ClusterEvent(Instant.now(), type, severity, summary, details);
    }

    /// Project a replicated `ClusterEventValue` onto the dashboard-facing record. Wall-clock
    /// `timestamp` is reconstructed from the HLC's physical-microseconds half — sufficient
    /// for human-readable timeline display; total ordering is still established by the
    /// `(epoch, seq)` key, not this `Instant`.
    public static ClusterEvent fromValue(ClusterEventValue value) {
        var details = new HashMap<>(value.metadata());

        if (!value.nodeId().isEmpty()) {details.put("originNodeId", value.nodeId());}
        // Wall-clock for human/legacy queries; HLC pair retained in details/metadata for total ordering.
        details.put("origin_hlc",
                    value.at().toString());

        return new ClusterEvent(Instant.now(), value.type(), value.severity(), value.message(), Map.copyOf(details));
    }
}
