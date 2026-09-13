// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.smtp.SmtpClient;
import org.pragmatica.net.smtp.SmtpMessage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #271 R8: `NotificationSenderFactory.close` was a no-op override, so the `SmtpClient` — which owns
/// a Netty `EventLoopGroup` it shuts down in its own `close()` — was never closed on release. The pin
/// COUNTS the client closes rather than asserting the factory's promise succeeded, which it always
/// did.
class NotificationSenderFactoryCloseTest {
    private final NotificationSenderFactory factory = new NotificationSenderFactory();

    @Test
    void close_smtpSender_closesTheSmtpClientExactlyOnce() {
        var closes = new AtomicInteger();
        var sender = new SmtpNotificationSender(countingClient(closes), RetryConfig.DEFAULT);

        factory.close(sender).await().onFailure(cause -> fail("close should succeed: " + cause.message()));
        assertThat(closes.get()).as("factory.close must reach SmtpClient.close(), which releases the event loop")
                  .isEqualTo(1);
    }

    private static SmtpClient countingClient(AtomicInteger closes) {
        return new SmtpClient() {
            @Override
            public Promise<String> send(SmtpMessage message) {
                return Promise.success("250 OK");
            }

            @Override
            public Promise<Unit> close() {
                closes.incrementAndGet();

                return Promise.unitPromise();
            }
        };
    }
}
