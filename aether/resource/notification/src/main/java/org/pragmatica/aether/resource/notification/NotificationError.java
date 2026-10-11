// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
