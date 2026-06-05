// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.Codec;

import java.util.Map;


/// Sealed cluster event hierarchy (spec §6.4.1).
///
/// Every framework event variant is a record implementing this interface, plus an
/// {@link ExtendedEvent} non-sealed extension hatch for framework plugins to introduce
/// additional variants without modifying the sealed parent.
///
/// Closed-set count is **30 variants** (25 prior framework events + STREAM_REGISTERED/DELETED +
/// ALERT_INJECTED/TRACE_INJECTED/SELF_DRAIN_INITIATED).
///
/// Consumers exhaust the sealed parent via pattern-matching `switch`; the compiler enforces that
/// every closed variant is handled and that an `ExtendedEvent` arm is present (typically a
/// discriminator-keyed dispatch, structured log, or no-op).
@Codec
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
        ClusterEvent.AlertInjected,
        ClusterEvent.TraceInjected,
        ClusterEvent.SelfDrainInitiated,
        ExtendedEvent {

    /// Restart-safe identity + total cluster ordering: HLC physical micros + logical counter + origin nodeId.
    HlcTimestamp at();

    /// Origin node — derived from {@link #at()}, no separate wire field.
    default NodeId sourceNode() {
        return at().nodeId();
    }

    /// Severity bucket carried by every closed-set variant for management-API JSON.
    Severity severity();

    /// Human-readable single-line summary carried by every closed-set variant.
    String summary();

    /// Free-form key/value payload carried by every closed-set variant.
    Map<String, String> details();

    @Codec
    enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    record NodeJoined(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record NodeLeft(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record NodeFailed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record LeaderElected(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record LeaderLost(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record QuorumEstablished(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record QuorumLost(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record DeploymentStarted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record DeploymentCompleted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record DeploymentFailed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ScaleUp(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ScaleDown(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record SliceFailure(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ConnectionEstablished(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ConnectionFailed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record CommunityScaleRequest(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record CommunityMetricsSnapshot(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record AccessDenied(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record NodeLifecycleChanged(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record ConfigChanged(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BackupCreated(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BackupRestored(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BlueprintDeployed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record BlueprintDeleted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    record GenerationChanged(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    /// Stream lifecycle event: a stream was registered (spec §13.1).
    record StreamRegistered(HlcTimestamp at, Severity severity, String summary, Map<String, String> details,
                            StreamAddress address) implements ClusterEvent {}

    /// Stream lifecycle event: a stream was deleted (spec §13.2).
    record StreamDeleted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details,
                         StreamAddress address) implements ClusterEvent {}

    /// Operator-injected synthetic alert. Replicated cluster-wide via the events stream so peers
    /// surface it on /api/alerts read regardless of which node received the inject POST.
    /// `details` carries `alertId` (monotonic per-node `injected-<ts>-<seq>`), plus optional
    /// `metric` and `value`.
    record AlertInjected(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    /// Operator-injected synthetic invocation trace. Replicated cluster-wide via the events stream
    /// so peers surface it on /api/traces read regardless of which node received the inject POST.
    /// `details` carries `requestId`, `traceId`, `operation`, `durationMs`, `depth`.
    record TraceInjected(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}

    /// Emitted by the draining node itself when its `SelfDrainCoordinator` flips from `ACTIVE`
    /// to `DRAINING` (membership-architecture-spec.md §16.1, S19/S20). The partition victim is
    /// the only source of truth for "I am self-draining"; NOT leader-gated. Severity WARNING.
    /// `details` carries `nodeId`, `reason` (one of `sustained-below-quorum`,
    /// `quorum-disappeared`, `rabia-paused`), and `graceMs`.
    record SelfDrainInitiated(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {}
}
