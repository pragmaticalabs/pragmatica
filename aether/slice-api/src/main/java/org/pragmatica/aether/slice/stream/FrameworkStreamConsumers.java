// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Sealed factory for {@link FrameworkStreamConsumer} instances (spec §6.1).
///
/// Only the framework can construct framework consumers because:
///   1. The single permitted implementation ({@link SystemStreamConsumer}) is a package-private
///      class. Application code outside `org.pragmatica.aether.slice.stream` cannot reference,
///      extend, or instantiate it.
///   2. The factory entry point {@link #systemStreamConsumer(StreamAddress, StreamAccess)}
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
    static <T> Result<FrameworkStreamConsumer<T>> systemStreamConsumer(StreamAddress address, StreamAccess<T> transport) {
        if (!address.isSystem()) {
            return FrameworkStreamConsumerError.General.NOT_SYSTEM_NAMESPACE.result();
        }
        return Result.success(new SystemStreamConsumer<>(address, transport));
    }

    /// Failure cases for framework consumer construction.
    sealed interface FrameworkStreamConsumerError extends Cause {
        enum General implements FrameworkStreamConsumerError {
            NOT_SYSTEM_NAMESPACE("FrameworkStreamConsumer requires a system-namespace address");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements FrameworkStreamConsumerError {
            @Override public String message() {
                return "";
            }
        }
    }

    @SuppressWarnings("unused") record unused() implements FrameworkStreamConsumers {}
}
