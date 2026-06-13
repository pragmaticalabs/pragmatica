// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.Supplier;


/// Sealed factory for {@link FrameworkStreamConsumer} instances (spec §6.1).
///
/// Only the framework can construct framework consumers because:
///   1. The single permitted implementation ({@link SystemStreamConsumer}) is a package-private
///      class. Application code outside `org.pragmatica.aether.slice.stream` cannot reference,
///      extend, or instantiate it.
///   2. The factory entry point {@link #systemStreamConsumer(ResourceAddress, StreamAccess)}
///      validates the supplied address is in the `system` namespace and refuses otherwise.
///
/// Closed-read principle is therefore a compile-time invariant: app code has no expression that
/// yields a `FrameworkStreamConsumer`.
public sealed interface FrameworkStreamConsumers {
    /// Wrap a framework-resolved transport stream-access for the given system-namespace address.
    ///
    /// Validates that `address` falls under `system:*`. Application addresses are rejected with
    /// {@link FrameworkStreamConsumerError.General#NOT_SYSTEM_NAMESPACE} so a misuse from inside
    /// the framework module surfaces immediately rather than silently exposing app-namespace
    /// events through the privileged SPI.
    static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(ResourceAddress address,
                                                                       StreamAccess<T> transport) {
        if (!address.isSystem()) {
            return FrameworkStreamConsumerError.General.NOT_SYSTEM_NAMESPACE.result();
        }

        return Result.success(new SystemStreamConsumer<>(address, transport));
    }

    /// Test-only factory: construct a {@link FrameworkStreamConsumer} that returns the supplied
    /// list of events on every `fetch(...)` call.
    ///
    /// Validates `address` is a system address — same invariant as the production factory. Use in
    /// downstream tests that need to verify framework components consume from a system stream
    /// without spinning up the full transport stack.
    static <T> Result<FrameworkStreamConsumer<T>> testConsumer(ResourceAddress address,
                                                               Supplier<List<StreamEvent<T>>> fetchSupplier) {
        if (!address.isSystem()) {
            return FrameworkStreamConsumerError.General.NOT_SYSTEM_NAMESPACE.result();
        }

        return Result.success(new TestSystemStreamConsumer<>(address, fetchSupplier));
    }

    /// Failure cases for framework consumer construction.
    sealed interface FrameworkStreamConsumerError extends Cause {
        enum General implements FrameworkStreamConsumerError {
            NOT_SYSTEM_NAMESPACE("FrameworkStreamConsumer requires a system-namespace address");
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
        record unused() implements FrameworkStreamConsumerError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    @SuppressWarnings("unused")
    record unused() implements FrameworkStreamConsumers {}
}
