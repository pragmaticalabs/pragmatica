// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Cause;


public sealed interface NotificationError extends Cause {
    record BackendNotConfigured(String message) implements NotificationError {}

    record UnsupportedChannel(String message) implements NotificationError {}

    record DeliveryFailed(String message) implements NotificationError {}
}
