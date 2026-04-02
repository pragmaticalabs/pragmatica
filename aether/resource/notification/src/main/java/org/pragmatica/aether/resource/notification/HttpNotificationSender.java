package org.pragmatica.aether.resource.notification;

import org.pragmatica.email.http.EmailBody;
import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailSender;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.TimeUnit;

import static org.pragmatica.aether.resource.notification.NotificationResult.notificationResult;
import static org.pragmatica.lang.Unit.unit;

/// NotificationSender backed by HTTP vendor API.
final class HttpNotificationSender implements NotificationSender {
    private final HttpEmailSender sender;
    private final RetryConfig retryConfig;

    HttpNotificationSender(HttpEmailSender sender, RetryConfig retryConfig) {
        this.sender = sender;
        this.retryConfig = retryConfig;
    }

    @Override public Promise<NotificationResult> send(Notification notification) {
        return switch (notification) {case Notification.Email email -> sendEmail(email);};
    }

    private Promise<NotificationResult> sendEmail(Notification.Email email) {
        var message = toEmailMessage(email);
        return sendWithRetry(message, 1, retryConfig.initialDelayMs());
    }

    private Promise<NotificationResult> sendWithRetry(EmailMessage message, int attempt, long delayMs) {
        return sender.send(message).map(response -> notificationResult(response, "http"))
                          .fold(result -> result.fold(cause -> {
                                                          if ( attempt >= retryConfig.maxAttempts()) {
        return new NotificationError.DeliveryFailed("HTTP delivery failed after " + attempt + " attempts: " + cause.message()).<NotificationResult>promise();}
                                                          return delayThen(delayMs)
        .flatMap(_ -> sendWithRetry(message,
                                    attempt + 1,
                                    nextDelay(delayMs)));
                                                      },
                                                      Promise::success));
    }

    private long nextDelay(long currentDelayMs) {
        return Math.min((long)(currentDelayMs * retryConfig.backoffMultiplier()), retryConfig.maxDelayMs());
    }

    private static Promise<Unit> delayThen(long delayMs) {
        return Promise.promise(promise -> {
                                   Thread.ofVirtual()
        .start(() -> {
                   try {
                       TimeUnit.MILLISECONDS.sleep(delayMs);
                       promise.succeed(unit());
                   }































        catch (InterruptedException _) {
                       Thread.currentThread().interrupt();
                       promise.succeed(unit());
                   }
               });
                               });
    }

    static EmailMessage toEmailMessage(Notification.Email email) {
        var body = switch (email.body()) {case NotificationBody.Text text -> EmailBody.Text.text(text.content());case NotificationBody.Html html -> html.fallback().map(fallback -> EmailBody.Html.html(html.content(),
                                                                                                                                                                                                        fallback))
                                                                                                                                                                 .or(EmailBody.Html.html(html.content()));};
        var message = EmailMessage.emailMessage(email.from(),
                                                email.to(),
                                                email.subject(),
                                                body).withCc(email.cc())
                                               .withBcc(email.bcc());
        return email.replyTo().map(message::withReplyTo)
                            .or(message);
    }
}
