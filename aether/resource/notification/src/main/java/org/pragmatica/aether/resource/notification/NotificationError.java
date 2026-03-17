package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Cause;

/// Notification delivery errors.
public sealed interface NotificationError extends Cause {
    record BackendNotConfigured(String message) implements NotificationError {}

    record UnsupportedChannel(String message) implements NotificationError {}

    record DeliveryFailed(String message) implements NotificationError {}
}
