// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Sealed factory for {@link FrameworkStreamPublisher} instances (spec §6.1).
///
/// Only the framework can construct framework publishers because:
///   1. The single permitted implementation ({@link SystemStreamPublisher}) is a package-private
///      class. Application code outside `org.pragmatica.aether.slice.stream` cannot reference,
///      extend, or instantiate it.
///   2. The factory entry point {@link #systemStreamPublisher(StreamAddress, StreamPublisher)}
///      validates the supplied address is in the `system` namespace and refuses otherwise.
///
/// Closed-write principle is therefore a compile-time invariant: app code has no expression that
/// yields a `FrameworkStreamPublisher`.
public sealed interface FrameworkStreamPublishers {
    /// Wrap a framework-resolved transport publisher for the given system-namespace address.
    ///
    /// Validates that `address` falls under `system:*`. Application addresses are rejected with
    /// {@link FrameworkStreamPublisherError.General#NOT_SYSTEM_NAMESPACE} so a misuse from inside
    /// the framework module surfaces immediately rather than silently delivering app-namespace
    /// events through the privileged SPI.
    static <T> Result<FrameworkStreamPublisher<T>> systemStreamPublisher(StreamAddress address, StreamPublisher<T> transport) {
        if (!address.isSystem()) {
            return FrameworkStreamPublisherError.General.NOT_SYSTEM_NAMESPACE.result();
        }
        return Result.success(new SystemStreamPublisher<>(address, transport));
    }

    /// Failure cases for framework publisher construction.
    sealed interface FrameworkStreamPublisherError extends Cause {
        enum General implements FrameworkStreamPublisherError {
            NOT_SYSTEM_NAMESPACE("FrameworkStreamPublisher requires a system-namespace address");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements FrameworkStreamPublisherError {
            @Override public String message() {
                return "";
            }
        }
    }

    @SuppressWarnings("unused") record unused() implements FrameworkStreamPublishers {}
}
