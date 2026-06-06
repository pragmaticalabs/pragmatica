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
        var transport = DefaultStreamPublisher.<T>streamPublisher(partitionManager,
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
        var transport = PartitionedStreamAccess.<T>streamAccess(partitionManager,
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
    /// on a non-replica node. Fail-soft: until the replica registry reports a caught-up replica
    /// (bootstrap window) the read lands locally rather than failing. The config's `name` must equal
    /// `address.asString()`.
    public static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(ResourceAddress address,
                                                                              StreamPartitionManager partitionManager,
                                                                              Serializer serializer,
                                                                              Deserializer deserializer,
                                                                              StreamConfig config,
                                                                              ReplicaRegistry replicaRegistry,
                                                                              Option<StreamForwardClient> forwardClient,
                                                                              NodeId self,
                                                                              StreamReadForwardMetrics metrics) {
        ensureLocalPartition(partitionManager, config);
        var transport = PartitionedStreamAccess.<T>streamAccess(partitionManager,
                                                                serializer,
                                                                deserializer,
                                                                config.name(),
                                                                config.partitions(),
                                                                Option.none(),
                                                                ReadPreference.ANY_REPLICA,
                                                                replicaRegistry,
                                                                forwardClient,
                                                                self,
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
        var transport = DefaultStreamPublisher.<T>streamPublisher(partitionManager,
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
        var transport = PartitionedStreamAccess.<T>streamAccess(partitionManager,
                                                                serializer,
                                                                deserializer,
                                                                address.asString(),
                                                                partitions,
                                                                Option.none());
        return FrameworkStreamConsumers.systemStreamConsumer(address, transport);
    }

    /// Idempotent partition creation from a fully-specified {@link StreamConfig}. `createStream`
    /// returns `Result<Unit>` — fresh-creation success or "already exists" failure; both are
    /// acceptable (the stream is usable in either case). Because `createStream` also publishes the
    /// `StreamConfigKey` into the cluster KV-store, the stream appears in
    /// {@link StreamPartitionManager#replicaCatalog()}, which is what the `ReplicaSetController`
    /// reconciles against — so a system stream created here becomes replicated.
    private static void ensureLocalPartition(StreamPartitionManager partitionManager, StreamConfig config) {
        partitionManager.createStream(config)
                        .onFailure(cause -> LOG.debug("system stream {} local-partition create deferred to publish/fetch: {}",
                                                      config.name(),
                                                      cause.message()));
    }

    /// Idempotent partition creation. `createStream` returns `Result<Unit>` — either fresh creation
    /// success or "already exists" failure. Both are acceptable for our purposes: the stream is
    /// usable in either case. Genuine creation failures (out of memory, bad config) propagate as
    /// failures from the subsequent publish/fetch call rather than from here, mirroring the
    /// app-side `StreamPublisherFactory`/`StreamAccessFactory` pattern (which also calls
    /// `createStream` unconditionally without checking the result).
    private static void ensureLocalPartition(ResourceAddress address,
                                             StreamPartitionManager partitionManager,
                                             int partitions,
                                             RetentionPolicy retention) {
        partitionManager.createStream(StreamConfig.streamConfig(address.asString(), partitions, retention, "earliest"))
                        .onFailure(cause -> LOG.debug("system stream {} local-partition create deferred to publish/fetch: {}",
                                                      address.asString(),
                                                      cause.message()));
    }
}
