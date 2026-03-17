package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.smtp.SmtpClient;
import org.pragmatica.net.smtp.SmtpMessage;

import java.util.concurrent.TimeUnit;

import static org.pragmatica.aether.resource.notification.NotificationResult.notificationResult;
import static org.pragmatica.lang.Unit.unit;

/// NotificationSender backed by async SMTP.
final class SmtpNotificationSender implements NotificationSender {
    private final SmtpClient client;
    private final RetryConfig retryConfig;

    SmtpNotificationSender(SmtpClient client, RetryConfig retryConfig) {
        this.client = client;
        this.retryConfig = retryConfig;
    }

    @Override
    public Promise<NotificationResult> send(Notification notification) {
        return switch (notification) {
            case Notification.Email email -> sendEmail(email);
        };
    }

    private Promise<NotificationResult> sendEmail(Notification.Email email) {
        var message = toSmtpMessage(email);
        return sendWithRetry(message, 1, retryConfig.initialDelayMs());
    }

    private Promise<NotificationResult> sendWithRetry(SmtpMessage message, int attempt, long delayMs) {
        return client.send(message)
                     .map(response -> notificationResult(response, "smtp"))
                     .fold(result -> result.fold(
                         cause -> {
                             if (attempt >= retryConfig.maxAttempts()) {
                                 return new NotificationError.DeliveryFailed(
                                     "SMTP delivery failed after " + attempt + " attempts: " + cause.message())
                                     .<NotificationResult>promise();
                             }
                             return delayThen(delayMs)
                                 .flatMap(_ -> sendWithRetry(message, attempt + 1, nextDelay(delayMs)));
                         },
                         Promise::success
                     ));
    }

    private long nextDelay(long currentDelayMs) {
        return Math.min((long) (currentDelayMs * retryConfig.backoffMultiplier()), retryConfig.maxDelayMs());
    }

    private static Promise<Unit> delayThen(long delayMs) {
        return Promise.promise(promise -> {
            Thread.ofVirtual().start(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(delayMs);
                    promise.succeed(unit());
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    promise.succeed(unit());
                }
            });
        });
    }

    static SmtpMessage toSmtpMessage(Notification.Email email) {
        var body = switch (email.body()) {
            case NotificationBody.Text text -> text.content();
            case NotificationBody.Html html -> html.fallback().or(html.content());
        };

        return SmtpMessage.smtpMessage(email.from(), email.to(), email.subject(), body)
                          .withCc(email.cc())
                          .withBcc(email.bcc());
    }
}
