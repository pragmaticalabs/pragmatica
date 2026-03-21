package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Promise;

/// Resource interface for sending notifications.
///
/// Provisioned via Aether's resource framework. Backed by either SMTP or HTTP vendor API
/// depending on configuration.
public interface NotificationSender {
    /// Send a notification and return the delivery result.
    Promise<NotificationResult> send(Notification notification);
}
