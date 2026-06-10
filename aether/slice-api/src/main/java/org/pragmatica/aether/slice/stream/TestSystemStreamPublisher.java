// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.function.Consumer;


/// Test-only permitted impl of {@link FrameworkStreamPublisher} (Wave 5B-ii).
///
/// Package-private record — application code cannot reference or construct this type. Constructed
/// exclusively via {@link FrameworkStreamPublishers#testPublisher(ResourceAddress, Consumer)} so the
/// system-namespace invariant is preserved.
///
/// Tests in downstream modules (e.g. `aether/node`) use this impl to verify that components which
/// depend on `FrameworkStreamPublisher<T>` actually publish. The capture callback receives every
/// published event synchronously; the returned `Promise<Unit>` always succeeds.
record TestSystemStreamPublisher<T>(ResourceAddress address, Consumer<T> capture) implements FrameworkStreamPublisher<T> {
    @Override
    public Promise<Unit> publish(T event) {
        capture.accept(event);

        return Promise.unitPromise();
    }
}
