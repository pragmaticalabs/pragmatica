// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.email.http.EmailMessage;
import org.pragmatica.email.http.HttpEmailError;
import org.pragmatica.email.http.HttpEmailSender;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.smtp.SmtpClient;
import org.pragmatica.net.smtp.SmtpError;
import org.pragmatica.net.smtp.SmtpMessage;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #271 R9: retry was indiscriminate — a permanent failure (bad credentials, a 5xx SMTP rejection,
/// an HTTP 4xx the vendor will answer identically next time) got the full exponential schedule.
/// Each test counts ATTEMPTS at the backend, with `maxAttempts = 3` and millisecond delays: a
/// permanent cause must be tried once, a transient one three times. The transient cases are the
/// control — they pass before and after the fix — so the permanent cases are the pins.
class NotificationRetryClassificationTest {
    private static final RetryConfig THREE_QUICK_ATTEMPTS = RetryConfig.retryConfig(3, 1, 2, 1.0);
    private static final Notification.Email EMAIL = Notification.Email.email("from@example.com",
                                                                            List.of("to@example.com"),
                                                                            "Subject",
                                                                            NotificationBody.Text.text("Body"));

    @Nested
    class Smtp {
        @Test
        void authFailed_isPermanent_soOneAttempt() {
            assertThat(smtpAttempts(new SmtpError.AuthFailed("535 bad credentials"))).isEqualTo(1);
        }

        @Test
        void tlsFailed_isPermanent_soOneAttempt() {
            assertThat(smtpAttempts(new SmtpError.TlsFailed("STARTTLS rejected"))).isEqualTo(1);
        }

        @Test
        void connectionFailed_isTransient_soAllAttempts() {
            assertThat(smtpAttempts(new SmtpError.ConnectionFailed("refused"))).isEqualTo(3);
        }

        @Test
        void timeout_isTransient_soAllAttempts() {
            assertThat(smtpAttempts(new SmtpError.Timeout("no banner"))).isEqualTo(3);
        }

        @Test
        void exhaustedTransientFailure_surfacesAsDeliveryFailed() {
            var attempts = new AtomicInteger();
            var sender = new SmtpNotificationSender(failingClient(attempts, new SmtpError.Timeout("no banner")),
                                                    THREE_QUICK_ATTEMPTS);

            sender.send(EMAIL)
                  .await()
                  .onSuccess(_ -> fail("delivery must fail"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(NotificationError.DeliveryFailed.class));
        }
    }

    @Nested
    class Http {
        @Test
        void authError_isPermanent_soOneAttempt() {
            assertThat(httpAttempts(new HttpEmailError.AuthError("HTTP 401"))).isEqualTo(1);
        }

        @Test
        void clientError_isPermanent_soOneAttempt() {
            assertThat(httpAttempts(new HttpEmailError.RequestFailed(400, "bad request"))).isEqualTo(1);
        }

        @Test
        void vendorNotFound_isPermanent_soOneAttempt() {
            assertThat(httpAttempts(new HttpEmailError.VendorNotFound("nope"))).isEqualTo(1);
        }

        @Test
        void serverError_isTransient_soAllAttempts() {
            assertThat(httpAttempts(new HttpEmailError.RequestFailed(503, "unavailable"))).isEqualTo(3);
        }

        @Test
        void tooManyRequests_isTransient_soAllAttempts() {
            assertThat(httpAttempts(new HttpEmailError.RequestFailed(429, "slow down"))).isEqualTo(3);
        }

        @Test
        void requestTimeout_isTransient_soAllAttempts() {
            assertThat(httpAttempts(new HttpEmailError.RequestFailed(408, "timeout"))).isEqualTo(3);
        }
    }

    private static int smtpAttempts(Cause cause) {
        var attempts = new AtomicInteger();
        var sender = new SmtpNotificationSender(failingClient(attempts, cause), THREE_QUICK_ATTEMPTS);

        sender.send(EMAIL)
              .await()
              .onSuccess(_ -> fail("delivery must fail"));

        return attempts.get();
    }

    private static int httpAttempts(Cause cause) {
        var attempts = new AtomicInteger();
        HttpEmailSender failing = _ -> {
            attempts.incrementAndGet();

            return cause.promise();
        };
        var sender = new HttpNotificationSender(failing, THREE_QUICK_ATTEMPTS);

        sender.send(EMAIL)
              .await()
              .onSuccess(_ -> fail("delivery must fail"));

        return attempts.get();
    }

    private static SmtpClient failingClient(AtomicInteger attempts, Cause cause) {
        return new SmtpClient() {
            @Override
            public Promise<String> send(SmtpMessage message) {
                attempts.incrementAndGet();

                return cause.promise();
            }

            @Override
            public Promise<Unit> close() {
                return Promise.unitPromise();
            }
        };
    }
}
