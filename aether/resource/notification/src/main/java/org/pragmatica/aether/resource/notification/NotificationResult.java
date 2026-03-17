package org.pragmatica.aether.resource.notification;

/// Result of a notification delivery.
///
/// @param messageId Identifier assigned by the backend (SMTP queue ID or HTTP API response ID)
/// @param backend Backend that delivered the notification ("smtp" or "http")
public record NotificationResult(String messageId, String backend) {
    public static NotificationResult notificationResult(String messageId, String backend) {
        return new NotificationResult(messageId, backend);
    }
}
