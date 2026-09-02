// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Application-facing combined stream access SPI (read + write).
///
/// Apps obtain a `StreamAccess<T>` for application-namespace streams via constructor injection
/// against a `[streams.X]` resource declaration. The slice-runtime resolver MUST refuse to bind a
/// `StreamAccess<T>` for any `system:*` address — apps cannot read or write system streams. See
/// {@link FrameworkStreamConsumer} for the framework-only system-namespace read SPI and
/// {@link StreamPublisher} / `FrameworkStreamPublisher` for the write split.
///
/// Resolver-level enforcement uses {@link #ensureAppAddress(ResourceAddress)} as the canonical
/// belt-and-suspenders check. Once {@link ResourceAddress} is plumbed into the access factory
/// (separate wave), the factory's provision path invokes this check before constructing access.
public interface StreamAccess<T> {
    Promise<Long> publish(T event);
    Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents);
    Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents);
    Promise<Unit> commit(String consumerGroup, int partition, long offset);

    Promise<Option<Long>> committedOffset(String consumerGroup, int partition);

    /// Framework-driven auto-resume (#478): the first read after a consumer (re)attaches. Resolves the
    /// committed cursor for `(consumerGroup, partition)` and reads from it, so a consumer picks up where
    /// it left off without an explicit app re-seek. For the durable app access the committed offset is
    /// served from the disk-backed cursor store (via {@link #committedOffset}), so resume survives a
    /// same-node restart with a writable data dir. With no committed cursor the read starts at offset 0
    /// (create-from-earliest), preserving prior behavior; an explicit {@link #fetch(int, long, int)}
    /// still overrides with a caller-chosen offset. Cursors are per-partition, so this is a
    /// single-partition read. The no-cursor default is always offset 0 (earliest): it does not honor a
    /// stream `latest` start-policy, so a never-committed consumer replays from the beginning (per the
    /// #478 ruling).
    default Promise<List<StreamEvent<T>>> fetchFromCommitted(String consumerGroup, int partition, int maxEvents) {
        return committedOffset(consumerGroup, partition).flatMap(committed -> fetch(partition,
                                                                                    committed.or(0L),
                                                                                    maxEvents));
    }

    Promise<StreamMetadata> metadata();

    /// Resolver-side fail-safe: refuse to bind app `StreamAccess` for a system address.
    ///
    /// Spec §6.1: the framework-vs-app boundary is a compile-time invariant via the sealed-SPI
    /// split ({@link FrameworkStreamConsumer} for reads, `FrameworkStreamPublisher` for writes).
    /// This runtime check is the second layer of defense for paths that bypass normal resolution
    /// (reflection, hand-edited blueprints, test harnesses).
    static Result<ResourceAddress> ensureAppAddress(ResourceAddress address) {
        if (address.isSystem()) {
            return StreamAccessError.General.SYSTEM_ADDRESS_REFUSED.result();
        }

        return Result.success(address);
    }

    record StreamEvent<T>(long offset, long timestamp, int partition, T payload) {}

    record StreamMetadata(String streamName, int partitionCount, List<PartitionInfo> partitions) {}

    record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount) {}

    /// Failure cases for app stream-access resolution.
    sealed interface StreamAccessError extends Cause {
        enum General implements StreamAccessError {
            SYSTEM_ADDRESS_REFUSED("StreamAccess cannot be bound to a system-namespace address; use FrameworkStreamConsumer/FrameworkStreamPublisher");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused")
        record unused() implements StreamAccessError {
            @Override
            public String message() {
                return "";
            }
        }
    }
}
