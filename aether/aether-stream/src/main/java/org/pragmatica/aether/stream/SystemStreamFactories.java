// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumers;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Production convenience factories for binding system-namespace streams to the local
/// {@link StreamPartitionManager} (Wave 5B-ii).
///
/// Lives in `aether-stream` rather than `slice-api` so it can reference `StreamPartitionManager`
/// (a runtime concern) without inverting the slice-api → aether-stream dependency direction.
///
/// Each factory:
///   1. Ensures the underlying partitioned ring buffer exists via
///      {@link StreamPartitionManager#createStream(StreamConfig)} (idempotent — failures from a
///      pre-existing stream are tolerated).
///   2. Constructs a transport ({@link DefaultStreamPublisher} / {@link PartitionedStreamAccess})
///      bound to that stream name.
///   3. Wraps the transport with the slice-api SPI factory
///      ({@link FrameworkStreamPublishers#systemStreamPublisher} /
///      {@link FrameworkStreamConsumers#systemStreamConsumer}) which validates the address is in
///      the `system:*` namespace.
///
/// Callers must guarantee the address has already been registered in the
/// `StreamRegistry` (via `SystemStreamBootstrap`) before invoking these factories — registry
/// metadata is the canonical source of truth for stream existence; partition creation here is the
/// local materialization step.
public final class SystemStreamFactories {
    private static final Logger LOG = LoggerFactory.getLogger(SystemStreamFactories.class);

    private SystemStreamFactories() {}

    /// Construct a {@link FrameworkStreamPublisher} for a system address backed by a local
    /// partition materialized from `config`. Use this overload when the stream needs the full set of
    /// production knobs — `maxEventSizeBytes`, `consistencyMode`, `minSyncReplicas` — carried on the
    /// {@link StreamConfig} (e.g. `system:cluster-events`, B5b). The config's `name` must equal
    /// `address.asString()`.
    public static <T> Result<FrameworkStreamPublisher<T>> systemStreamPublisher(ResourceAddress address,
                                                                                StreamPartitionManager partitionManager,
                                                                                Serializer serializer,
                                                                                StreamConfig config) {
        ensureLocalPartition(partitionManager, config);
        var transport = DefaultStreamPublisher.<T> streamPublisher(partitionManager,
                                                                   serializer,
                                                                   config.name(),
                                                                   config.partitions(),
                                                                   Option.none());

        return FrameworkStreamPublishers.systemStreamPublisher(address, transport);
    }

    /// Construct a {@link FrameworkStreamConsumer} for a system address backed by the same local
    /// partition materialized from `config`. Read path uses {@link PartitionedStreamAccess}: once the
    /// `ReplicaSetController` has populated the replica registry, a replica reads locally and a
    /// non-replica read-forwards automatically. The config's `name` must equal `address.asString()`.
    public static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(ResourceAddress address,
                                                                              StreamPartitionManager partitionManager,
                                                                              Serializer serializer,
                                                                              Deserializer deserializer,
                                                                              StreamConfig config) {
        ensureLocalPartition(partitionManager, config);
        var transport = PartitionedStreamAccess.<T> streamAccess(partitionManager,
                                                                 serializer,
                                                                 deserializer,
                                                                 config.name(),
                                                                 config.partitions(),
                                                                 Option.none());

        return FrameworkStreamConsumers.systemStreamConsumer(address, transport);
    }

    /// Fix #3: construct a forward-capable {@link FrameworkStreamConsumer} for a REPLICATED system
    /// address. Unlike the {@link #systemStreamConsumer(ResourceAddress, StreamPartitionManager,
    /// Serializer, Deserializer, StreamConfig)} overload — which wires a local-only
    /// {@link PartitionedStreamAccess} — this one threads the `replicaRegistry`, `forwardClient` and
    /// `self` node id and pins a replica-routed read preference, so a node OUTSIDE the stream's replica
    /// set forwards reads to a caught-up replica (the owner) instead of reading its own empty local
    /// partition (returning `200 []`). Required for `system:cluster-events` observability reads to work
    /// on a non-replica node. When no locally-known caught-up replica is visible yet (bootstrap window)
    /// the read forwards to the deterministic HRW owner via `ownerResolver`, failing soft to the local
    /// partition only when the owner is unknown/self. The config's `name` must equal `address.asString()`.
    public static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(ResourceAddress address,
                                                                              StreamPartitionManager partitionManager,
                                                                              Serializer serializer,
                                                                              Deserializer deserializer,
                                                                              StreamConfig config,
                                                                              ReplicaRegistry replicaRegistry,
                                                                              Option<StreamForwardClient> forwardClient,
                                                                              NodeId self,
                                                                              Option<Fn0<Option<NodeId>>> ownerResolver,
                                                                              StreamReadForwardMetrics metrics) {
        ensureLocalPartition(partitionManager, config);
        var transport = PartitionedStreamAccess.<T> streamAccess(partitionManager,
                                                                 serializer,
                                                                 deserializer,
                                                                 config.name(),
                                                                 config.partitions(),
                                                                 Option.none(),
                                                                 ReadPreference.ANY_REPLICA,
                                                                 replicaRegistry,
                                                                 forwardClient,
                                                                 self,
                                                                 ownerResolver,
                                                                 metrics);

        return FrameworkStreamConsumers.systemStreamConsumer(address, transport);
    }

    /// Construct a {@link FrameworkStreamPublisher} for a system address backed by a local
    /// partition managed by `partitionManager`.
    ///
    /// `partitions` controls the partition count of the underlying ring buffer. For low-volume
    /// system streams (cluster-events, etc.) `1` is the natural choice.
    public static <T> Result<FrameworkStreamPublisher<T>> systemStreamPublisher(ResourceAddress address,
                                                                                StreamPartitionManager partitionManager,
                                                                                Serializer serializer,
                                                                                int partitions,
                                                                                RetentionPolicy retention) {
        ensureLocalPartition(address, partitionManager, partitions, retention);
        var transport = DefaultStreamPublisher.<T> streamPublisher(partitionManager,
                                                                   serializer,
                                                                   address.asString(),
                                                                   partitions,
                                                                   Option.none());

        return FrameworkStreamPublishers.systemStreamPublisher(address, transport);
    }

    /// Construct a {@link FrameworkStreamConsumer} for a system address backed by the same local
    /// partition. Read path uses {@link PartitionedStreamAccess} with default (governor) read
    /// preference and no replica/forward-client wiring — system streams in RC1 read from the local
    /// owner.
    public static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(ResourceAddress address,
                                                                              StreamPartitionManager partitionManager,
                                                                              Serializer serializer,
                                                                              Deserializer deserializer,
                                                                              int partitions,
                                                                              RetentionPolicy retention) {
        ensureLocalPartition(address, partitionManager, partitions, retention);
        var transport = PartitionedStreamAccess.<T> streamAccess(partitionManager,
                                                                 serializer,
                                                                 deserializer,
                                                                 address.asString(),
                                                                 partitions,
                                                                 Option.none());

        return FrameworkStreamConsumers.systemStreamConsumer(address, transport);
    }

    /// Idempotent partition creation from a fully-specified {@link StreamConfig}. FAIL-SOFT for the
    /// system bootstrap path: a node must still boot and `SystemStreamRegistrar` owns the leader-pinned
    /// retry, so a create failure here does NOT propagate (spec §5.4 / reconciliation #17). But it is no
    /// longer DEBUG-buried — a genuine memory failure logs WARN (a benign `STREAM_ALREADY_EXISTS` stays
    /// quiet) and the budget-exhaustion cluster event is emitted by the manager's own exhaustion sink
    /// inside `createStream` (per-node `StreamMemoryExceeded`), so the soft failure is visible.
    private static void ensureLocalPartition(StreamPartitionManager partitionManager, StreamConfig config) {
        partitionManager.createStream(config).onFailure(cause -> logSystemStreamCreateFailure(config.name(), cause));
    }

    /// WARN for genuine create failures (memory, bad config); DEBUG for the benign idempotent
    /// `STREAM_ALREADY_EXISTS`. The `StreamMemoryExceeded` cluster event is emitted by the manager's
    /// exhaustion sink, not here — this is the human-visible log leg of the fail-soft path (§5.4).
    private static void logSystemStreamCreateFailure(String streamName, Cause cause) {
        if (cause == StreamError.General.STREAM_ALREADY_EXISTS) {
            LOG.debug("system stream {} already exists — reusing", streamName);

            return;
        }

        LOG.warn("system stream {} local-partition create FAILED (fail-soft; SystemStreamRegistrar will retry): {}",
                 streamName,
                 cause.message());
    }

    /// Idempotent partition creation. FAIL-SOFT (spec §5.4 / reconciliation #17): create failures do not
    /// propagate from the system bootstrap path — `SystemStreamRegistrar` owns retry and the node must
    /// still boot — but a genuine memory failure now logs WARN (not DEBUG) and the manager's exhaustion
    /// sink emits the `StreamMemoryExceeded` cluster event, so the soft failure is visible rather than
    /// buried.
    private static void ensureLocalPartition(ResourceAddress address,
                                             StreamPartitionManager partitionManager,
                                             int partitions,
                                             RetentionPolicy retention) {
        partitionManager.createStream(StreamConfig.streamConfig(address.asString(),
                                                                partitions,
                                                                retention,
                                                                "earliest"))
                        .onFailure(cause -> logSystemStreamCreateFailure(address.asString(),
                                                                         cause));
    }
}
