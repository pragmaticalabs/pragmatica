package org.pragmatica.aether.resource.notification;

public record NotificationResult(String messageId, String backend) {
    public static NotificationResult notificationResult(String messageId, String backend) {
        return new NotificationResult(messageId, backend);
    }
}
