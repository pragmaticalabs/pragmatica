// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Single permitted implementation of {@link FrameworkStreamPublisher} (spec §6.1).
///
/// Package-private record — application code outside this package cannot reference or construct
/// this type. Construction flows exclusively through
/// {@link FrameworkStreamPublishers#systemStreamPublisher(StreamAddress, StreamPublisher)} which
/// validates the address is in the `system` namespace.
record SystemStreamPublisher<T>(StreamAddress address, StreamPublisher<T> transport) implements FrameworkStreamPublisher<T> {
    @Override public Promise<Unit> publish(T event) {
        return transport.publish(event);
    }
}
