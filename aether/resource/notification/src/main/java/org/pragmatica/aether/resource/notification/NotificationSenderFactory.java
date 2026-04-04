package org.pragmatica.aether.resource.notification;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.email.http.HttpEmailSender;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.smtp.SmtpClient;


/// ResourceFactory for provisioning NotificationSender instances.
///
/// Routes to SMTP or HTTP backend based on configuration.
public final class NotificationSenderFactory implements ResourceFactory<NotificationSender, NotificationConfig> {
    @Override public Class<NotificationSender> resourceType() {
        return NotificationSender.class;
    }

    @Override public Class<NotificationConfig> configType() {
        return NotificationConfig.class;
    }

    @Override public Promise<NotificationSender> provision(NotificationConfig config) {
        return switch (config.backend()){
            case "smtp" -> provisionSmtp(config);
            case "http" -> provisionHttp(config);
            default -> new NotificationError.BackendNotConfigured("Unknown notification backend: " + config.backend()).<NotificationSender>promise();
        };
    }

    @Override public Promise<Unit> close(NotificationSender resource) {
        return Promise.unitPromise();
    }

    private static Promise<NotificationSender> provisionSmtp(NotificationConfig config) {
        return config.smtpConfig().map(smtpConfig -> {
                                           var client = SmtpClient.smtpClient(smtpConfig);
                                           NotificationSender sender = new SmtpNotificationSender(client,
                                                                                                  config.effectiveRetryConfig());
                                           return Promise.success(sender);
                                       })
                                .or(new NotificationError.BackendNotConfigured("SMTP backend selected but no SMTP configuration provided").<NotificationSender>promise());
    }

    private static Promise<NotificationSender> provisionHttp(NotificationConfig config) {
        return config.httpConfig().map(httpConfig -> {
                                           var sender = HttpEmailSender.httpEmailSender(httpConfig);
                                           NotificationSender notificationSender = new HttpNotificationSender(sender,
                                                                                                              config.effectiveRetryConfig());
                                           return Promise.success(notificationSender);
                                       })
                                .or(new NotificationError.BackendNotConfigured("HTTP backend selected but no HTTP configuration provided").<NotificationSender>promise());
    }
}
