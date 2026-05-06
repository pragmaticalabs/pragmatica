// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumers;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;


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
    private SystemStreamFactories() {}

    /// Construct a {@link FrameworkStreamPublisher} for a system address backed by a local
    /// partition managed by `partitionManager`.
    ///
    /// `partitions` controls the partition count of the underlying ring buffer. For low-volume
    /// system streams (cluster-events, etc.) `1` is the natural choice.
    public static <T> Result<FrameworkStreamPublisher<T>> systemStreamPublisher(StreamAddress address,
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
    public static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(StreamAddress address,
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

    /// Idempotent partition creation. `createStream` returns `Result<Unit>` — either fresh creation
    /// success or "already exists" failure. Both are acceptable for our purposes: the stream is
    /// usable in either case. Genuine creation failures (out of memory, bad config) propagate as
    /// failures from the subsequent publish/fetch call rather than from here, mirroring the
    /// app-side `StreamPublisherFactory`/`StreamAccessFactory` pattern (which also calls
    /// `createStream` unconditionally without checking the result).
    private static void ensureLocalPartition(StreamAddress address,
                                             StreamPartitionManager partitionManager,
                                             int partitions,
                                             RetentionPolicy retention) {
        Result<Unit> ignored = partitionManager.createStream(StreamConfig.streamConfig(address.asString(),
                                                                                       partitions,
                                                                                       retention,
                                                                                       "earliest"));
    }
}
