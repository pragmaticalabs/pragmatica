// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.net.smtp.SmtpClient;
import org.pragmatica.net.smtp.SmtpMessage;

import static org.pragmatica.aether.resource.notification.NotificationResult.notificationResult;


/// `AsyncCloseable` because the client it holds owns a Netty event loop that only `SmtpClient.close()`
/// shuts down; the factory's default close dispatch reaches it through this interface (#271 R8).
final class SmtpNotificationSender implements NotificationSender, AsyncCloseable {
    private final SmtpClient client;
    private final Retry retry;

    SmtpNotificationSender(SmtpClient client, RetryConfig retryConfig) {
        this.client = client;
        this.retry = retryConfig.retry();
    }

    @Override
    public Promise<NotificationResult> send(Notification notification) {
        return switch (notification) {
            case Notification.Email email -> sendEmail(email);
        };
    }

    @Override
    public Promise<Unit> close() {
        return client.close();
    }

    private Promise<NotificationResult> sendEmail(Notification.Email email) {
        var message = toSmtpMessage(email);

        return retry.execute(() -> client.send(message).map(response -> notificationResult(response, "smtp")))
                    .mapError(cause -> new NotificationError.DeliveryFailed("SMTP delivery failed: " + cause.message()));
    }

    static SmtpMessage toSmtpMessage(Notification.Email email) {
        var body = switch (email.body()) {
            case NotificationBody.Text text -> text.content();
            case NotificationBody.Html html -> html.fallback().or(html.content());
        };

        return SmtpMessage.smtpMessage(email.from(),
                                       email.to(),
                                       email.subject(),
                                       body)
                          .withCc(email.cc())
                          .withBcc(email.bcc());
    }
}
