// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;


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

    record StreamEvent<T>(long offset, long timestamp, int partition, T payload){}

    record StreamMetadata(String streamName, int partitionCount, List<PartitionInfo> partitions){}

    record PartitionInfo(int partition, long headOffset, long tailOffset, long eventCount){}

    /// Failure cases for app stream-access resolution.
    sealed interface StreamAccessError extends Cause {
        enum General implements StreamAccessError {
            SYSTEM_ADDRESS_REFUSED("StreamAccess cannot be bound to a system-namespace address; use FrameworkStreamConsumer/FrameworkStreamPublisher");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements StreamAccessError {
            @Override public String message() {
                return "";
            }
        }
    }
}
