// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Cause;


public sealed interface NotificationError extends Cause {
    record BackendNotConfigured(String message) implements NotificationError {}

    record UnsupportedChannel(String message) implements NotificationError {}

    /// Delivery failed after the sender's own retry; `origin` is the backend's last cause, and the
    /// classification travels with it — a terminal `550` and an exhausted `4yz` schedule are not
    /// the same verdict to a caller's retry policy (review of #1075, NIT-2).
    record DeliveryFailed(String message, Cause origin) implements NotificationError, Cause.Wrapped {
        @Override
        public boolean isTerminal() {
            return origin.isTerminal();
        }

        @Override
        public boolean isTransient() {
            return origin.isTransient();
        }
    }
}
