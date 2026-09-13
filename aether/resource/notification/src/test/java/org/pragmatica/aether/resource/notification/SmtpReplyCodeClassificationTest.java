// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.smtp.SmtpAuth;
import org.pragmatica.net.smtp.SmtpClient;
import org.pragmatica.net.smtp.SmtpConfig;
import org.pragmatica.net.smtp.SmtpTlsMode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #271 (review of PR #1075, BLOCKING-1): permanence must come from the REPLY CODE, on every
/// command, not from which command drew the refusal. The producer is the real `SmtpClient` and
/// `SmtpSession` talking to a scripted loopback server; attempts are the connections the server
/// accepts, with `maxAttempts = 3`. RFC 5321 §4.2.1: 4yz transient, 5yz permanent — at the
/// greeting, EHLO, STARTTLS, AUTH and MAIL FROM alike. Shape adopted from the reviewer's probe
/// `ProbeSmtp4yzOnEhloAuthTest`.
class SmtpReplyCodeClassificationTest {
    private static final RetryConfig THREE_QUICK = RetryConfig.retryConfig(3, 1, 2, 1.0);

    private static final Notification.Email EMAIL = Notification.Email.email("a@example.com",
                                                                             List.of("b@example.com"),
                                                                             "s",
                                                                             NotificationBody.Text.text("b"));

    enum Stage {
        GREETING,
        EHLO,
        STARTTLS,
        AUTH,
        MAIL_FROM
    }

    @Test
    void auth454_isTransient_soThreeAttempts() {
        assertThat(attempts("454 4.7.0 Temporary authentication failure", Stage.AUTH)).isEqualTo(3);
    }

    @Test
    void ehlo421_isTransient_soThreeAttempts() {
        assertThat(attempts("421 4.3.2 Service not available, closing transmission channel", Stage.EHLO)).isEqualTo(3);
    }

    @Test
    void startTls454_isTransient_soThreeAttempts() {
        assertThat(attempts("454 4.7.0 TLS not available due to temporary reason", Stage.STARTTLS)).isEqualTo(3);
    }

    @Test
    void greeting554_isPermanent_soOneAttempt() {
        assertThat(attempts("554 No SMTP service here", Stage.GREETING)).isEqualTo(1);
    }

    @Test
    void greeting421_isTransient_soThreeAttempts() {
        assertThat(attempts("421 4.3.2 Service not available", Stage.GREETING)).isEqualTo(3);
    }

    @Test
    void auth535_isPermanent_soOneAttempt() {
        assertThat(attempts("535 5.7.8 bad credentials", Stage.AUTH)).isEqualTo(1);
    }

    @Test
    void startTls554_isPermanent_soOneAttempt() {
        assertThat(attempts("554 5.7.3 TLS not supported", Stage.STARTTLS)).isEqualTo(1);
    }

    @Test
    void mailFrom550_isPermanent_soOneAttempt() {
        assertThat(attempts("550 5.7.1 rejected", Stage.MAIL_FROM)).isEqualTo(1);
    }

    @Test
    void mailFrom451_isTransient_soThreeAttempts() {
        assertThat(attempts("451 4.7.1 try again later", Stage.MAIL_FROM)).isEqualTo(3);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static int attempts(String failReply, Stage failAt) {
        try {
            return attemptsAgainstScriptedServer(failReply, failAt);
        } catch (IOException e) {
            return fail("scripted server failed: " + e);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static int attemptsAgainstScriptedServer(String failReply, Stage failAt) throws IOException {
        var connections = new AtomicInteger();

        try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon().start(() -> serve(server, connections, failReply, failAt));
            var tlsMode = failAt == Stage.STARTTLS
                          ? SmtpTlsMode.STARTTLS
                          : SmtpTlsMode.NONE;
            var config = SmtpConfig.smtpConfig("127.0.0.1",
                                               server.getLocalPort(),
                                               tlsMode,
                                               SmtpAuth.smtpAuth("u", "p"))
                                   .withCommandTimeout(TimeSpan.timeSpan(5).seconds());
            var client = SmtpClient.smtpClient(config);
            var sender = new SmtpNotificationSender(client, THREE_QUICK);
            var result = sender.send(EMAIL).await();

            assertThat(result.isFailure()).as("delivery must fail: " + result).isTrue();
            client.close().await();

            return connections.get();
        }
    }

    @SuppressWarnings({"JBCT-EX-01", "JBCT-LAM-01"})
    private static void serve(ServerSocket server, AtomicInteger connections, String failReply, Stage failAt) {
        try {
            while (true) {
                try (Socket socket = server.accept()) {
                    connections.incrementAndGet();
                    session(socket, failReply, failAt);
                }
            }
        } catch (IOException _) {
        // server closed
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void session(Socket socket, String failReply, Stage failAt) throws IOException {
        var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var out = socket.getOutputStream();

        if (failAt == Stage.GREETING) {
            reply(out, failReply);

            return;
        }

        reply(out, "220 probe ESMTP");
        in.readLine();  // EHLO
        if (failAt == Stage.EHLO) {
            reply(out, failReply);

            return;
        }

        reply(out, "250-probe");
        reply(out, "250-STARTTLS");
        reply(out, "250 AUTH PLAIN");
        if (failAt == Stage.STARTTLS) {
            in.readLine();  // STARTTLS
            reply(out, failReply);

            return;
        }

        in.readLine();  // AUTH PLAIN
        if (failAt == Stage.AUTH) {
            reply(out, failReply);

            return;
        }

        reply(out, "235 2.7.0 ok");
        in.readLine();  // MAIL FROM
        reply(out, failReply);
        in.readLine();  // QUIT or close
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }
}
