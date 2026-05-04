// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.consensus.NodeId;

import java.time.Instant;
import java.util.Map;


/// Sealed cluster event hierarchy (spec §6.4.1).
///
/// Replaces the prior enum-tagged `record ClusterEvent(EventType type, ...)`. Every framework
/// event variant is a record implementing this interface, plus an {@link ExtendedEvent}
/// non-sealed extension hatch for framework plugins to introduce additional variants without
/// modifying the sealed parent.
///
/// Closed-set count is **27 variants** (25 existing framework events + `STREAM_REGISTERED` +
/// `STREAM_DELETED`). Spec §6.4 cites "26 variants" reflecting an earlier count of 24 framework
/// events; the spec note will be updated separately.
///
/// Consumers exhaust the sealed parent via pattern-matching `switch`; the compiler enforces that
/// every closed variant is handled and that an `ExtendedEvent` arm is present (typically a
/// discriminator-keyed dispatch, structured log, or no-op).
///
/// Per-variant payload preserves the legacy uniform shape `(severity, summary, details)` for
/// backward compatibility with the management-API JSON envelope. Per-variant strongly-typed
/// payload schemas are an orthogonal future refactor.
public sealed interface ClusterEvent permits
        ClusterEvent.NodeJoined,
        ClusterEvent.NodeLeft,
        ClusterEvent.NodeFailed,
        ClusterEvent.LeaderElected,
        ClusterEvent.LeaderLost,
        ClusterEvent.QuorumEstablished,
        ClusterEvent.QuorumLost,
        ClusterEvent.DeploymentStarted,
        ClusterEvent.DeploymentCompleted,
        ClusterEvent.DeploymentFailed,
        ClusterEvent.ScaleUp,
        ClusterEvent.ScaleDown,
        ClusterEvent.SliceFailure,
        ClusterEvent.ConnectionEstablished,
        ClusterEvent.ConnectionFailed,
        ClusterEvent.CommunityScaleRequest,
        ClusterEvent.CommunityMetricsSnapshot,
        ClusterEvent.AccessDenied,
        ClusterEvent.NodeLifecycleChanged,
        ClusterEvent.ConfigChanged,
        ClusterEvent.BackupCreated,
        ClusterEvent.BackupRestored,
        ClusterEvent.BlueprintDeployed,
        ClusterEvent.BlueprintDeleted,
        ClusterEvent.GenerationChanged,
        ClusterEvent.StreamRegistered,
        ClusterEvent.StreamDeleted,
        ExtendedEvent {

    EventId id();
    Instant timestamp();
    NodeId sourceNode();

    /// Severity bucket carried by every closed-set variant for management-API JSON.
    Severity severity();

    /// Human-readable single-line summary carried by every closed-set variant.
    String summary();

    /// Free-form key/value payload carried by every closed-set variant.
    Map<String, String> details();

    enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    record NodeJoined(EventId id, Instant timestamp, NodeId sourceNode,
                      Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record NodeLeft(EventId id, Instant timestamp, NodeId sourceNode,
                    Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record NodeFailed(EventId id, Instant timestamp, NodeId sourceNode,
                      Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record LeaderElected(EventId id, Instant timestamp, NodeId sourceNode,
                         Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record LeaderLost(EventId id, Instant timestamp, NodeId sourceNode,
                      Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record QuorumEstablished(EventId id, Instant timestamp, NodeId sourceNode,
                             Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record QuorumLost(EventId id, Instant timestamp, NodeId sourceNode,
                      Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record DeploymentStarted(EventId id, Instant timestamp, NodeId sourceNode,
                             Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record DeploymentCompleted(EventId id, Instant timestamp, NodeId sourceNode,
                               Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record DeploymentFailed(EventId id, Instant timestamp, NodeId sourceNode,
                            Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ScaleUp(EventId id, Instant timestamp, NodeId sourceNode,
                   Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ScaleDown(EventId id, Instant timestamp, NodeId sourceNode,
                     Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record SliceFailure(EventId id, Instant timestamp, NodeId sourceNode,
                        Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ConnectionEstablished(EventId id, Instant timestamp, NodeId sourceNode,
                                 Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ConnectionFailed(EventId id, Instant timestamp, NodeId sourceNode,
                            Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record CommunityScaleRequest(EventId id, Instant timestamp, NodeId sourceNode,
                                 Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record CommunityMetricsSnapshot(EventId id, Instant timestamp, NodeId sourceNode,
                                    Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record AccessDenied(EventId id, Instant timestamp, NodeId sourceNode,
                        Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record NodeLifecycleChanged(EventId id, Instant timestamp, NodeId sourceNode,
                                Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ConfigChanged(EventId id, Instant timestamp, NodeId sourceNode,
                         Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BackupCreated(EventId id, Instant timestamp, NodeId sourceNode,
                         Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BackupRestored(EventId id, Instant timestamp, NodeId sourceNode,
                          Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BlueprintDeployed(EventId id, Instant timestamp, NodeId sourceNode,
                             Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BlueprintDeleted(EventId id, Instant timestamp, NodeId sourceNode,
                            Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record GenerationChanged(EventId id, Instant timestamp, NodeId sourceNode,
                             Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    /// Stream lifecycle event: a stream was registered (spec §13.1).
    record StreamRegistered(EventId id, Instant timestamp, NodeId sourceNode,
                            Severity severity, String summary, Map<String, String> details,
                            StreamAddress address) implements ClusterEvent {}

    /// Stream lifecycle event: a stream was deleted (spec §13.2).
    record StreamDeleted(EventId id, Instant timestamp, NodeId sourceNode,
                         Severity severity, String summary, Map<String, String> details,
                         StreamAddress address) implements ClusterEvent {}
}
