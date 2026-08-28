// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.TimeSpan;

import static org.pragmatica.lang.Option.none;


/// Activation of a durable topic's backing streams (durable-pubsub-spec §3/§9).
///
/// `topic:<address>` and `topic:<address>.dlq` are created EAGERLY, in the same activation step,
/// through the committed-config path (`StreamPartitionManager.createStream` — the #410 machinery:
/// consensus-committed `StreamConfigKey`, reconcile-on-config-Put placement, epoch-fenced
/// recovery). Lazy DLQ creation was rejected by the spec (§13 item 3) because it recreates the
/// #262 first-publish/config-adoption race class; eager-in-one-step is the resolution.
///
/// Activation is idempotent: `STREAM_ALREADY_EXISTS` from either create is success (the committed
/// config is the goal state, not the act of creating it), so every slice that declares the topic —
/// publisher or subscriber, first or fifth — activates it with the same call.
///
/// Config mapping decisions, recorded because each could silently weaken the §4 guarantee table:
/// - **Declared retention maps to the TIME dimension only** (`maxAgeMs`); `maxCount`/`maxBytes`
///   stay at the platform's hot-window defaults because they SIZE the per-partition ring
///   allocation (`StreamEntry.buildRing` hands them to `OffHeapRingBuffer` as index capacity and
///   data-region size) — an unbounded value there is an allocation request, not a policy. The
///   effective replay window is therefore min(declared time, count cap, bytes cap) until the
///   hierarchical-storage tier (#349 — named by the spec as this feature's persistence
///   dependency) extends the floor beyond the ring; a consumer falling below the floor surfaces
///   as the §7 `CURSOR_GAP` path, never silently.
/// - **DLQ inherits `replicas`/`min-sync`** from the source topic (an event that survived
///   replication must not die in a weaker DLQ, §9); DLQ retention defaults to 14d (§9), its
///   per-topic override arrives with the D3 operator surface.
/// - **DLQ has one partition**: dead letters carry their source partition in the envelope, redrive
///   is per-entry and group-targeted, so cross-entry ordering buys nothing — and one partition
///   keeps the inspect/page surface trivial. Poison throughput is failure-bounded.
/// - **`ConsistencyMode.EVENTUAL`**: the durability floor is the two-knob synchronous-replication
///   barrier (`min-sync == replicas >= 2`, enforced at parse), not the STRONG consensus publish
///   path, which remains unwired (guarantees.md §4 known-gaps).
public interface DurableTopicSubstrate {
    /// Create the topic stream and its DLQ stream, both or neither observable as success: a failed
    /// DLQ create fails activation even when the topic stream succeeded (the partial state is
    /// harmless — creates are idempotent, the next activation attempt converges).
    Result<Unit> activateTopic(String topicAddress, DurableTopicSpec spec);

    static DurableTopicSubstrate durableTopicSubstrate(StreamPartitionManager partitionManager) {
        return (topicAddress, spec) -> activate(partitionManager, topicAddress, spec);
    }

    TimeSpan DLQ_RETENTION_DEFAULT = TimeSpan.timeSpan("14d").unwrap();

    private static Result<Unit> activate(StreamPartitionManager manager, String topicAddress, DurableTopicSpec spec) {
        return tolerateExisting(manager.createStream(topicStreamConfig(topicAddress, spec))).flatMap(_ -> tolerateExisting(manager.createStream(dlqStreamConfig(topicAddress,
                                                                                                                                                                spec))));
    }

    private static Result<Unit> tolerateExisting(Result<Unit> created) {
        return created.fold(DurableTopicSubstrate::recoverExisting, Result::success);
    }

    private static Result<Unit> recoverExisting(Cause cause) {
        return cause == StreamError.General.STREAM_ALREADY_EXISTS
               ? Result.unitResult()
               : cause.result();
    }

    static StreamConfig topicStreamConfig(String topicAddress, DurableTopicSpec spec) {
        return StreamConfig.streamConfig(DurableTopicNames.topicStream(topicAddress),
                                         spec.partitions(),
                                         timeBoundedRetention(spec.retention()),
                                         "earliest",
                                         StreamConfig.DEFAULT.maxEventSizeBytes(),
                                         StreamConfig.DEFAULT.consistencyMode(),
                                         spec.replicas(),
                                         spec.minSyncReplicas(),
                                         StreamCompression.NONE,
                                         none());
    }

    static StreamConfig dlqStreamConfig(String topicAddress, DurableTopicSpec spec) {
        return StreamConfig.streamConfig(DurableTopicNames.dlqStream(topicAddress),
                                         1,
                                         timeBoundedRetention(DLQ_RETENTION_DEFAULT),
                                         "earliest",
                                         StreamConfig.DEFAULT.maxEventSizeBytes(),
                                         StreamConfig.DEFAULT.consistencyMode(),
                                         spec.replicas(),
                                         spec.minSyncReplicas(),
                                         StreamCompression.NONE,
                                         none());
    }

    private static RetentionPolicy timeBoundedRetention(TimeSpan retention) {
        var sizingDefaults = RetentionPolicy.retentionPolicy();

        return RetentionPolicy.retentionPolicy(sizingDefaults.maxCount(),
                                               sizingDefaults.maxBytes(),
                                               retention.toMillis(),
                                               RetentionMode.ANY);
    }
}
