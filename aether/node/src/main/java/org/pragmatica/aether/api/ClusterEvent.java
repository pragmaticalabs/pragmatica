// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.Codec;


/// Sealed cluster event hierarchy (spec §6.4.1).
///
/// Every framework event variant is a record implementing this interface, plus an
/// {@link ExtendedEvent} non-sealed extension hatch for framework plugins to introduce
/// additional variants without modifying the sealed parent.
///
/// Closed-set count is **42 variants** (25 prior framework events + STREAM_REGISTERED/DELETED +
/// ALERT_INJECTED/TRACE_INJECTED/SELF_DRAIN_INITIATED + STREAM_MEMORY_EXCEEDED +
/// DEPARTURE_PUSH_INCOMPLETE + SCALE_CAPPED + THRESHOLD_BREACHED/THRESHOLD_CLEARED +
/// COMMUNITY_MINTED/COMMUNITY_STATE_CHANGED/COMMUNITY_MEMBER_JOINED/COMMUNITY_MEMBER_LEFT + OPERATOR_WARNING +
/// DHT_REPLICATION_UNSETTLED/DHT_REPLICATION_SETTLED).
///
/// Consumers exhaust the sealed parent via pattern-matching `switch`; the compiler enforces that
/// every closed variant is handled and that an `ExtendedEvent` arm is present (typically a
/// discriminator-keyed dispatch, structured log, or no-op).
@Codec
public sealed interface ClusterEvent permits ClusterEvent.NodeJoined, ClusterEvent.NodeLeft, ClusterEvent.NodeFailed, ClusterEvent.LeaderElected, ClusterEvent.LeaderLost, ClusterEvent.QuorumEstablished, ClusterEvent.QuorumLost, ClusterEvent.DeploymentStarted, ClusterEvent.DeploymentCompleted, ClusterEvent.DeploymentFailed, ClusterEvent.ScaleUp, ClusterEvent.ScaleDown, ClusterEvent.SliceFailure, ClusterEvent.AutoRollback, ClusterEvent.ConnectionEstablished, ClusterEvent.ConnectionFailed, ClusterEvent.CommunityScaleRequest, ClusterEvent.CommunityMetricsSnapshot, ClusterEvent.AccessDenied, ClusterEvent.NodeLifecycleChanged, ClusterEvent.ConfigChanged, ClusterEvent.BackupCreated, ClusterEvent.BackupRestored, ClusterEvent.BlueprintDeployed, ClusterEvent.BlueprintDeleted, ClusterEvent.StreamRegistered, ClusterEvent.StreamDeleted, ClusterEvent.AlertInjected, ClusterEvent.TraceInjected, ClusterEvent.SelfDrainInitiated, ClusterEvent.StreamMemoryExceeded, ClusterEvent.DeparturePushIncomplete, ClusterEvent.ScaleCapped, ClusterEvent.ThresholdBreached, ClusterEvent.ThresholdCleared, ClusterEvent.OperatorWarning, ClusterEvent.CommunityMinted, ClusterEvent.CommunityStateChanged, ClusterEvent.CommunityMemberJoined, ClusterEvent.CommunityMemberLeft, ClusterEvent.DhtReplicationUnsettled, ClusterEvent.DhtReplicationSettled, ExtendedEvent {
    /// Restart-safe identity + total cluster ordering: HLC physical micros + logical counter + origin nodeId.
    HlcTimestamp at();

    /// Origin node — derived from {@link #at()}, no separate wire field.
    default NodeId sourceNode() {
        return at().nodeId();
    }

    /// Discriminator string for management-API consumers: SCREAMING_SNAKE_CASE of the implementing
    /// record's simple class name (e.g. `NodeFailed` → `"NODE_FAILED"`). The `@Codec` serializes
    /// record components only, so this default is surfaced on the wire via a response DTO
    /// (`ManagementApiResponses.ClusterEventView`), not by serializing the interface directly.
    default String type() {
        return screamingSnakeCase(getClass().getSimpleName());
    }

    /// Convert a camelCase simple-class-name to SCREAMING_SNAKE_CASE by inserting `_` before each
    /// interior uppercase letter, then upper-casing the whole. `SelfDrainInitiated` →
    /// `SELF_DRAIN_INITIATED`. No nulls: `getSimpleName()` of a non-anonymous record is always present.
    private static String screamingSnakeCase(String simpleName) {
        var builder = new StringBuilder(simpleName.length() + 8);

        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);

            if (i > 0 && Character.isUpperCase(c)) {
                builder.append('_');
            }

            builder.append(Character.toUpperCase(c));
        }

        return builder.toString();
    }

    /// Severity bucket carried by every closed-set variant for management-API JSON.
    Severity severity();
    /// Human-readable single-line summary carried by every closed-set variant.
    String summary();
    /// Free-form key/value payload carried by every closed-set variant.
    Map<String, String> details();
    /// This event with `details[key] = value` (#1653: the aggregator stamps `details.eventId` through it). Each
    /// closed variant implements it with its own canonical constructor, so adding a variant is a compile error until
    /// it does, and no reflection is involved. An `ExtendedEvent` returns itself unless it overrides this.
    ClusterEvent withDetail(String key, String value);

    /// `details` with `key` set to `value`; the helper every variant's [#withDetail] uses.
    static Map<String, String> detailsWith(Map<String, String> details, String key, String value) {
        var enriched = new HashMap<>(details);

        enriched.put(key, value);

        return Map.copyOf(enriched);
    }

    @Codec
    enum Severity {
        INFO,
        WARNING,
        CRITICAL,
        /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
        /// Descriptive only; surfaced verbatim in the management-API JSON.
        /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
        /// by an older node either way.
        UNKNOWN
    }

    record NodeJoined(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new NodeJoined(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record NodeLeft(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new NodeLeft(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record NodeFailed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new NodeFailed(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record LeaderElected(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new LeaderElected(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record LeaderLost(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new LeaderLost(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record QuorumEstablished(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new QuorumEstablished(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record QuorumLost(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new QuorumLost(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record DeploymentStarted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new DeploymentStarted(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record DeploymentCompleted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new DeploymentCompleted(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record DeploymentFailed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new DeploymentFailed(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record ScaleUp(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ScaleUp(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record ScaleDown(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ScaleDown(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record SliceFailure(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new SliceFailure(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// #1573: the leader committed an automatic rollback. Always CRITICAL; `details` name the failed artifact,
    /// the version rolled back to and the evidence (each hosting node's slice defects within the window), so
    /// an operator can always see why.
    record AutoRollback(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new AutoRollback(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record ConnectionEstablished(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ConnectionEstablished(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record ConnectionFailed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ConnectionFailed(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// **Not currently produced** (#927): no production code constructs it. Forward-declared and kept
    /// because wire tag 263 pins the type; retiring a tag is a codec-table change. Do not build a
    /// consumer, dashboard or runbook for it until a producer exists — `ClusterEventProducerCensusTest`
    /// fails the build when one lands, so this note cannot outlive the fact.
    record CommunityScaleRequest(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new CommunityScaleRequest(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record CommunityMetricsSnapshot(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new CommunityMetricsSnapshot(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record AccessDenied(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new AccessDenied(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record NodeLifecycleChanged(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new NodeLifecycleChanged(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record ConfigChanged(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ConfigChanged(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// No producer since the backup API was removed (#676); kept because wire tags 258/259 pin the
    /// types and retiring a tag is a codec-table change, not a route change.
    record BackupCreated(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new BackupCreated(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record BackupRestored(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new BackupRestored(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record BlueprintDeployed(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new BlueprintDeployed(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    record BlueprintDeleted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new BlueprintDeleted(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// Stream lifecycle event: a stream was registered (spec §13.1).
    ///
    /// **Not currently produced** (#927): the spec's emission point was never wired, so no production
    /// code constructs it (feature catalog: deferred). Forward-declared and kept because wire tag 288
    /// pins the type. `ClusterEventProducerCensusTest` fails the build when a producer lands.
    record StreamRegistered(HlcTimestamp at,
                            Severity severity,
                            String summary,
                            Map<String, String> details,
                            ResourceAddress address) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new StreamRegistered(at, severity, summary, ClusterEvent.detailsWith(details, key, value), address);
        }
    }

    /// Stream lifecycle event: a stream was deleted (spec §13.2).
    ///
    /// **Not currently produced** (#927): as [StreamRegistered]; wire tag 286 pins the type.
    record StreamDeleted(HlcTimestamp at,
                         Severity severity,
                         String summary,
                         Map<String, String> details,
                         ResourceAddress address) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new StreamDeleted(at, severity, summary, ClusterEvent.detailsWith(details, key, value), address);
        }
    }

    /// Operator-injected synthetic alert. Replicated cluster-wide via the events stream so peers
    /// surface it on /api/alerts read regardless of which node received the inject POST.
    /// `details` carries `alertId` (monotonic per-node `injected-<ts>-<seq>`), plus optional
    /// `metric` and `value`.
    record AlertInjected(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new AlertInjected(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// Operator-injected synthetic invocation trace. Replicated cluster-wide via the events stream
    /// so peers surface it on /api/traces read regardless of which node received the inject POST.
    /// `details` carries `requestId`, `traceId`, `operation`, `durationMs`, `depth`.
    record TraceInjected(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new TraceInjected(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// Emitted by the draining node itself when its `SelfDrainCoordinator` flips from `ACTIVE`
    /// to `DRAINING` (membership-architecture-spec.md §16.1, S19/S20). The partition victim is
    /// the only source of truth for "I am self-draining"; NOT leader-gated. Severity WARNING.
    /// `details` carries `nodeId`, `reason` (one of `sustained-below-quorum`,
    /// `quorum-disappeared`, `rabia-paused`), and `graceMs`.
    record SelfDrainInitiated(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new SelfDrainInitiated(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// Off-heap stream budget exhausted at stream creation (floor) or growth (elastic pool)
    /// (stream-offheap-budget-spec §4.5c / §7). A per-node fact (each node has its own budget), so —
    /// like {@link SelfDrainInitiated} — it is NOT leader-gated; every node reports its own exhaustion
    /// via the aggregator's `emitLocal` path. Severity WARNING (recoverable: destroying/right-sizing
    /// other streams frees the pool). `details` carries `streamName`, `partitions`, `phase` (one of
    /// `create-floor` | `growth`), `requestedBytes`, `availableBytes`, `maxTotalBytes`,
    /// `consistencyMode`, and `nodeId` (the reporting node).
    record StreamMemoryExceeded(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new StreamMemoryExceeded(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// Emitted by a gracefully-departing node when its bounded departure push (issue #427) could not
    /// confirm — within the drain grace window — that every locally-held DHT chunk reached a surviving
    /// replica. A per-node fact (the leaving node is the only source of truth for its own unpushed
    /// chunks), so — like {@link SelfDrainInitiated} — it is NOT leader-gated; it is emitted through
    /// the aggregator's un-gated `emitLocal` path. Severity WARNING (best-effort push overran; the
    /// keys are named for operator follow-up, never silently lost — principles P3/P4). `details`
    /// carries `nodeId`, `keysAtRisk` (count) and `sampleKeys` (bounded, comma-joined hex sample).
    record DeparturePushIncomplete(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new DeparturePushIncomplete(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// Emitted by the leader control loop when the autoscaler's requested instance count is reduced by
    /// a cap before being applied (#425). A leader-side scaling-attribution signal (the control loop
    /// runs on the leader), surfaced through the aggregator's leader-gated `emit` path. Severity
    /// WARNING (the slice wants more capacity than policy or the cluster currently allows — operators
    /// should notice a slice pinned at its cap). `details` carries `artifact`, `requestedInstances`,
    /// `cappedAtInstances`, and `reason` (one of `max-instances` | `cluster-cap`).
    record ScaleCapped(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ScaleCapped(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// A configured metric threshold was crossed upward on some node (#957).
    ///
    /// **This is a DERIVED fact, not a node-local observation, and that is what decides its gating.**
    /// `ClusterSyncCollector.allMetrics()` returns fresh known observations. Core nodes receive the
    /// cluster-wide feed; workers retain a scoped view and do not publish cluster dashboard metrics. An un-gated emit would therefore write
    /// the same breach once per node. It is emitted through the aggregator's owner-gated
    /// {@link ClusterEventAggregator#emit} path, which is exactly the shape that gate exists for —
    /// deduplication comes free and no per-node partitioning or routing hop is needed.
    ///
    /// **Evaluation still runs on EVERY node; only publication is gated.** Edge-triggering state
    /// (`shouldTrigger`) lives in the evaluator, so an owner that evaluated only while it held the gate
    /// would start with empty state and re-fire every active breach on each ownership change. Because
    /// every node evaluates continuously, an incoming owner already holds the correct edge state and
    /// the ownership change emits nothing.
    ///
    /// **Not the source of truth for "what is firing now."** Stream retention is `RetentionMode.ANY`
    /// (count OR bytes OR age), so a breach older than the age floor has had this event evicted while
    /// still firing — a fold over the log would then report it as clear. The live answer is derived
    /// from current metrics by `AlertManager`'s maintained view; this event is the durable HISTORY.
    ///
    /// `details` carries `metric`, `nodeId` (whose metric breached), `value`, `threshold` and
    /// `alertSeverity` (`WARNING` | `CRITICAL` — the alert's own ladder, distinct from [#severity]).
    record ThresholdBreached(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ThresholdBreached(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// A previously-breached metric threshold returned below its clear point (#957, #969).
    ///
    /// The complement of {@link ThresholdBreached}, and it must be its own event because **an
    /// append-only log cannot represent absence** — nothing can be deleted to signal a clear.
    ///
    /// **The clear point is not the breach point, and it differs by severity.** A hysteresis margin
    /// damps a metric oscillating across the boundary. A CRITICAL alert clears below
    /// `max(critical * (1 - margin), warning)`; a WARNING alert clears below `warning * (1 - margin)`,
    /// with no clamp — there is no lower rung for it to invert into. The clamp on the CRITICAL arm is
    /// what keeps the severity ladder from inverting: without it a CRITICAL clearing below the WARNING
    /// threshold would immediately re-raise as WARNING, manufacturing the flapping the margin exists to
    /// damp. The margin applies to the CLEAR edge only: raising is already edge-triggered, and delaying
    /// it would delay first detection.
    ///
    /// Owner-gated like its breach counterpart, for the same derived-fact reason.
    ///
    /// `details` carries `metric`, `nodeId`, `value`, `clearedFrom` (the severity being left) and
    /// `clearPoint` (the hysteresis-adjusted value the metric fell below).
    record ThresholdCleared(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new ThresholdCleared(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// The leader minted a community (#1652): its committed `CommunityValue` appeared. Derived from the
    /// committed record, so every node observes it and the aggregator's owner-gated
    /// {@link ClusterEventAggregator#emit} path publishes it from the owner only (at-least-once across an
    /// ownership handover, guarantees.md row 14b). Severity INFO. `details` carries `communityId`,
    /// `state` (FORMING for a mint), `targetSize` and `role`.
    record CommunityMinted(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new CommunityMinted(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// A community's committed lifecycle `state` changed (#1652) — one event for every edge, with the
    /// edge in `details` (`communityId`, `from`, `to`, `targetSize`). FORMING→ACTIVE is "formed";
    /// ACTIVE→DEGRADED (severity WARNING, the only non-INFO edge) is live membership falling below the
    /// viability floor; DEGRADED→ACTIVE is recovery; →DISSOLVED is retirement. Owner-gated like
    /// {@link CommunityMinted}.
    ///
    /// **What it does not record.** A worker that loses the core fences itself LOCALLY and writes
    /// nothing, so no DISSOLVED edge exists for that case; the core records only what it observes — the
    /// ACTIVE→DEGRADED edge once the members stop answering.
    record CommunityStateChanged(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new CommunityStateChanged(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// A node was added to a community's committed roster (`GovernorAnnouncementValue.members`, #1652).
    /// Roster membership is ASSIGNMENT, not liveness: a member that stops answering stays on the roster
    /// and shows up as the community's DEGRADED edge instead. Owner-gated. Severity INFO. `details`
    /// carries `communityId`, `nodeId`, `governorId` and `memberCount` (roster size after the change).
    record CommunityMemberJoined(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new CommunityMemberJoined(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// A node was removed from a community's committed roster (#1652) — the complement of
    /// {@link CommunityMemberJoined}, with the same assignment-not-liveness meaning and `details`.
    record CommunityMemberLeft(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new CommunityMemberLeft(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// #1777 (CTO ruling R1b): a live change of the DHT's `[replication]` factors has stayed unsettled for longer than
    /// five minutes, so every node still uses the transitional quorums — W_t = max(W_old, W_new), R_t = max(R_old, R_new).
    /// Reads and writes stay correct; they need more replicas than the new factors do, so they fail sooner when replicas
    /// are down. Usually a member the leader's membership view still counts that is not reporting, or a core whose
    /// catch-up cannot complete. Derived from the committed change record on every node and published through the
    /// cluster-events owner gate: AT MOST ONCE per transition — missed if the owner cannot publish at that moment.
    /// Severity WARNING. `details`: `changeVersion`, `replicationFactor`, `confirmationFactor`, `stage`, `since`,
    /// `reason`.
    record DhtReplicationUnsettled(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new DhtReplicationUnsettled(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// #1777: the complement of {@link DhtReplicationUnsettled} — the overdue change settled, or a newer change
    /// superseded it (`reason`). Severity INFO. `details`: `changeVersion`, `replicationFactor`, `confirmationFactor`,
    /// `since`, `reason`.
    record DhtReplicationSettled(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new DhtReplicationSettled(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }

    /// A condition an operator needs to see, raised through `OperatorWarnings.raise` (#1574).
    ///
    /// This is one generic event rather than a variant per condition. `details.code` is the stable
    /// identifier of the condition, taken from the `OperatorWarningCode` catalogue, so adding a condition
    /// does not add a wire type. Neither `GET /api/events` nor `aether events` can filter on it yet. Every one of these is a per-node fact, reported by the node that saw it, so it goes
    /// through the aggregator's ungated `emitLocal` path, like [SelfDrainInitiated]. The emit is throttled
    /// to one per `(code, subject)` per minute. The log line at the call site is never throttled.
    ///
    /// `severity` is `WARNING` or `CRITICAL`, following the code's level. `summary` is the message the
    /// site logged. `details` carries `code`, `subsystem`, `subject`, `nodeId` and `suppressedSince`,
    /// which is the number of occurrences the throttle held back since the previous emitted event for
    /// the same key.
    record OperatorWarning(HlcTimestamp at, Severity severity, String summary, Map<String, String> details) implements ClusterEvent {
        @Override
        public ClusterEvent withDetail(String key, String value) {
            return new OperatorWarning(at, severity, summary, ClusterEvent.detailsWith(details, key, value));
        }
    }
}
