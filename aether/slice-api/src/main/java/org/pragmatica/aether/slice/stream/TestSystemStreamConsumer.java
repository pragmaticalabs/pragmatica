// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.function.Supplier;


/// Test-only permitted impl of {@link FrameworkStreamConsumer} (Wave 5B-ii).
///
/// Package-private record — application code cannot reference or construct this type. Constructed
/// exclusively via {@link FrameworkStreamConsumers#testConsumer(ResourceAddress, Supplier)} so the
/// system-namespace invariant is preserved.
///
/// The single `fetchSupplier` is invoked by both `fetch(long, int)` and `fetch(int, long, int)` —
/// tests typically only need a single fixed list of events without partition semantics. `metadata`
/// returns a single-partition `StreamMetadata` describing the test stream. `commit` and
/// `committedOffset` are no-ops returning success / `Option.none()`.
record TestSystemStreamConsumer<T>(ResourceAddress address,
                                   Supplier<List<StreamEvent<T>>> fetchSupplier) implements FrameworkStreamConsumer<T> {
    @Override public Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents) {
        return Promise.success(fetchSupplier.get());
    }

    @Override public Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents) {
        return Promise.success(fetchSupplier.get());
    }

    @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
        return Promise.unitPromise();
    }

    @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
        return Promise.success(Option.none());
    }

    @Override public Promise<StreamMetadata> metadata() {
        return Promise.success(new StreamMetadata(address.asString(), 1, List.of()));
    }
}
