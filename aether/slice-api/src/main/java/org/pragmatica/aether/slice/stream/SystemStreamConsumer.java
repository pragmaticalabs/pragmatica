// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Single permitted implementation of {@link FrameworkStreamConsumer} (spec §6.1).
///
/// Package-private record — application code outside this package cannot reference or construct
/// this type. Construction flows exclusively through
/// {@link FrameworkStreamConsumers#systemStreamConsumer(StreamAddress, StreamAccess)} which
/// validates the address is in the `system` namespace.
record SystemStreamConsumer<T>(StreamAddress address, StreamAccess<T> transport) implements FrameworkStreamConsumer<T> {
    @Override public Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents) {
        return transport.fetch(fromOffset, maxEvents);
    }

    @Override public Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents) {
        return transport.fetch(partition, fromOffset, maxEvents);
    }

    @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
        return transport.commit(consumerGroup, partition, offset);
    }

    @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
        return transport.committedOffset(consumerGroup, partition);
    }

    @Override public Promise<StreamMetadata> metadata() {
        return transport.metadata();
    }
}
