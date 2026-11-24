// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.config.AlertConfig.EventConfig;
import org.pragmatica.aether.config.AlertConfig.WebhookConfig;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3536: `retry_count` retried only HTTP status responses. A transport failure (connection refused, reset, timeout)
/// failed the send promise before the status was read, so the alert was dropped after one attempt: the common case for a
/// receiver that is restarting. The receiver here is a raw socket that drops the first N connections without a response
/// (a transport failure at the client) and answers 200 to the next, so an attempt is one accepted connection and the count
/// is read from the receiver, not inferred from timing.
class AlertForwarderTransportRetryTest {
    private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";

    private final AtomicInteger accepted = new AtomicInteger();
    private ServerSocket server;
    private AlertForwarder forwarder;

    @BeforeEach
    void startReceiver() throws IOException {
        server = new ServerSocket(0);
    }

    @AfterEach
    void stop() throws IOException {
        if (forwarder != null) {
            forwarder.close().await(timeSpan(10).seconds());
        }
        server.close();
    }

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void forward_retriesTransportFailures_untilTheReceiverAnswers() {
        serveAfterDropping(2);
        forwarder = forwarderWithRetries(3);

        var result = forwarder.forward(AlertEvent.AlertResolved.resolved("a-1", "test")).await(timeSpan(20).seconds());

        assertThat(result.isSuccess()).as("two dropped connections are within retry_count=3: %s", result).isTrue();
        assertThat(accepted.get()).as("two dropped attempts, then the answered one").isEqualTo(3);
    }

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void forward_givesUpAfterRetryCount_whenTheReceiverNeverAnswers() {
        serveAfterDropping(Integer.MAX_VALUE);
        forwarder = forwarderWithRetries(2);

        var result = forwarder.forward(AlertEvent.AlertResolved.resolved("a-2", "test")).await(timeSpan(20).seconds());

        assertThat(result.isFailure()).as("an unreachable receiver is reported, not swallowed").isTrue();
        assertThat(result.fold(Cause::message, _ -> "")).as("the report names the receiver and the transport cause")
                                                         .startsWith("Webhook http://127.0.0.1:" + server.getLocalPort() + "/hook failed: ")
                                                         .doesNotEndWith("failed: ");
        assertThat(accepted.get()).as("one attempt plus retry_count retries").isEqualTo(3);
    }

    @Test
    @Timeout(value = 30, unit = SECONDS)
    void forward_withZeroRetries_triesOnce() {
        serveAfterDropping(Integer.MAX_VALUE);
        forwarder = forwarderWithRetries(0);

        var result = forwarder.forward(AlertEvent.AlertResolved.resolved("a-3", "test")).await(timeSpan(20).seconds());

        assertThat(result.isFailure()).isTrue();
        assertThat(accepted.get()).isEqualTo(1);
    }

    private AlertForwarder forwarderWithRetries(int retries) {
        var url = "http://127.0.0.1:" + server.getLocalPort() + "/hook";
        var webhook = WebhookConfig.webhookConfig(true, List.of(url), retries, timeSpan(5).seconds()).unwrap();
        var config = AlertConfig.alertConfig(true, webhook, EventConfig.eventConfig(true).unwrap(), 0.05).unwrap();

        return AlertForwarder.alertForwarder(config);
    }

    private void serveAfterDropping(int drops) {
        var receiver = new Thread(() -> acceptLoop(drops), "alert-receiver");

        receiver.setDaemon(true);
        receiver.start();
    }

    private void acceptLoop(int drops) {
        try (var listener = server) {
            while (!listener.isClosed()) {
                handle(listener.accept(), accepted.incrementAndGet() > drops);
            }
        } catch (IOException _) {
            // the test closed the listener
        }
    }

    private static void handle(Socket socket, boolean answer) throws IOException {
        try (socket) {
            if (answer) {
                socket.getInputStream().read(new byte[8192]);
                socket.getOutputStream().write(OK.getBytes(StandardCharsets.US_ASCII));
            }
        }
    }
}
