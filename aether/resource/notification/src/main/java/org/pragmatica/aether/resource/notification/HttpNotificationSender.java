// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.email.http.EmailBody;
import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailSender;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Retry;

import static org.pragmatica.aether.resource.notification.NotificationResult.notificationResult;


final class HttpNotificationSender implements NotificationSender {
    private final HttpEmailSender sender;
    private final Retry retry;

    HttpNotificationSender(HttpEmailSender sender, RetryConfig retryConfig) {
        this.sender = sender;
        this.retry = retryConfig.retry();
    }

    @Override
    public Promise<NotificationResult> send(Notification notification) {
        return switch (notification) {
            case Notification.Email email -> sendEmail(email);
        };
    }

    private Promise<NotificationResult> sendEmail(Notification.Email email) {
        var message = toEmailMessage(email);

        return retry.execute(() -> sender.send(message)
                                         .map(response -> notificationResult(response, "http")))
                    .mapError(cause -> new NotificationError.DeliveryFailed("HTTP delivery failed: " + cause.message(),
                                                                            cause));
    }

    static EmailMessage toEmailMessage(Notification.Email email) {
        var body = switch (email.body()) {
            case NotificationBody.Text text -> EmailBody.Text.text(text.content());
            case NotificationBody.Html html -> html.fallback().map(fallback -> EmailBody.Html.html(html.content(),
                                                                                                   fallback)).or(EmailBody.Html.html(html.content()));
        };
        var message = EmailMessage.emailMessage(email.from(),
                                                email.to(),
                                                email.subject(),
                                                body)
                                  .withCc(email.cc())
                                  .withBcc(email.bcc());

        return email.replyTo()
                    .map(message::withReplyTo)
                    .or(message);
    }
}
