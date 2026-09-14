// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.net.smtp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #271 (review of #1075, BLOCKING-1 and SF-2), against the REAL client and a scripted loopback
/// server that records every command it receives and whether the client closed the socket.
///
/// - A TLS context that cannot be built used to be logged and dropped; in STARTTLS mode the session
///   then skipped STARTTLS and sent AUTH PLAIN in cleartext, and the delivery succeeded. The send
///   must now fail closed — terminal `TlsSetupFailed`, no connection, no AUTH on the wire.
/// - A malformed reply or a command timeout failed the promise but left the channel open; under
///   retry the sockets stacked. Both must close the channel.
class SmtpClientFailClosedTest {
    private static final TimeSpan SHORT = TimeSpan.timeSpan(1).seconds();

    private EventLoopGroup eventLoop;

    @BeforeEach
    void setUp() {
        eventLoop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterEach
    void tearDown() {
        eventLoop.shutdownGracefully();
    }

    @Test
    void startTlsMode_tlsContextCannotBeBuilt_failsTerminal_beforeAnyConnection() {
        var server = new ScriptedServer(List.of(List.of("220 probe ESMTP"),
                                                List.of("250-probe", "250-STARTTLS", "250 AUTH PLAIN"),
                                                List.of("220 go ahead")));

        try (server) {
            var config = SmtpConfig.smtpConfig("127.0.0.1",
                                               server.port(),
                                               SmtpTlsMode.STARTTLS,
                                               SmtpAuth.smtpAuth("u", "p"))
                                   .withCommandTimeout(SHORT);
            var client = SmtpClient.smtpClient(config,
                                               eventLoop,
                                               () -> new SmtpError.TlsSetupFailed("no trust store").result());
            var result = client.send(SmtpMessage.smtpMessage("a@example.com",
                                                             List.of("b@example.com"),
                                                             "s",
                                                             "b"))
                               .await(SHORT);

            assertThat(result.isFailure()).as("a TLS setup failure must fail the send, never fall back to cleartext")
                      .isTrue();
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(SmtpError.TlsSetupFailed.class);
                assertThat(cause.isTerminal()).isTrue();
            });
            assertThat(server.connections.get()).as("no connection is opened without the TLS the mode requires")
                      .isZero();
            assertThat(server.commands).as("AUTH PLAIN must never reach the wire in cleartext")
                      .noneMatch(line -> line.startsWith("AUTH"));
        }
    }

    /// Control: with a buildable context the same script gets STARTTLS, not AUTH, after EHLO.
    @Test
    void startTlsMode_withAContext_sendsStartTlsAfterEhlo() {
        var server = new ScriptedServer(List.of(List.of("220 probe ESMTP"),
                                                List.of("250-probe", "250-STARTTLS", "250 AUTH PLAIN"),
                                                List.of("554 TLS refused")));

        try (server) {
            var config = SmtpConfig.smtpConfig("127.0.0.1",
                                               server.port(),
                                               SmtpTlsMode.STARTTLS,
                                               SmtpAuth.smtpAuth("u", "p"))
                                   .withCommandTimeout(SHORT);
            var client = SmtpClient.smtpClient(config, eventLoop, SmtpClientImpl::buildInsecureSslContext);

            client.send(SmtpMessage.smtpMessage("a@example.com", List.of("b@example.com"), "s", "b")).await(SHORT);
            assertThat(server.commands).anyMatch(line -> line.startsWith("STARTTLS"));
            assertThat(server.commands).noneMatch(line -> line.startsWith("AUTH"));
        }
    }

    @Test
    void malformedReply_failsThePromise_andClosesTheChannel() {
        var server = new ScriptedServer(List.of(List.of("hello there no code")));

        try (server) {
            var client = SmtpClient.smtpClient(plain(server.port()), eventLoop, SmtpClientImpl::buildInsecureSslContext);
            var result = client.send(SmtpMessage.smtpMessage("a@example.com",
                                                             List.of("b@example.com"),
                                                             "s",
                                                             "b"))
                               .await(SHORT);

            assertThat(result.isFailure()).isTrue();
            assertThat(server.awaitClientClosed(3_000)).as("the session must close the socket it cannot parse").isTrue();
        }
    }

    @Test
    void commandTimeout_failsThePromise_andClosesTheChannel() {
        var server = new ScriptedServer(List.of());

        try (server) {
            var client = SmtpClient.smtpClient(plain(server.port()).withCommandTimeout(TimeSpan.timeSpan(300).millis()),
                                               eventLoop,
                                               SmtpClientImpl::buildInsecureSslContext);
            var result = client.send(SmtpMessage.smtpMessage("a@example.com",
                                                             List.of("b@example.com"),
                                                             "s",
                                                             "b"))
                               .await(SHORT);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(SmtpError.Timeout.class));
            assertThat(server.awaitClientClosed(3_000)).as("a timed-out session must close its socket").isTrue();
        }
    }

    /// NIT-1: a 3yz where a completion is expected is refused, not treated as success.
    @Test
    void threeYzWhereCompletionExpected_isRefused_terminal() {
        var server = new ScriptedServer(List.of(List.of("220 probe ESMTP"),
                                                List.of("250 probe"),
                                                List.of("354 unexpected")));

        try (server) {
            var client = SmtpClient.smtpClient(plain(server.port()), eventLoop, SmtpClientImpl::buildInsecureSslContext);
            var result = client.send(SmtpMessage.smtpMessage("a@example.com",
                                                             List.of("b@example.com"),
                                                             "s",
                                                             "b"))
                               .await(SHORT);

            assertThat(result.isFailure()).as("354 at MAIL FROM is not a completion").isTrue();
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(SmtpError.Rejected.class);
                assertThat(cause.isTerminal()).as("a challenge this client cannot answer is not a passing condition")
                          .isTrue();
                assertThat(cause.isTransient()).isFalse();
            });
        }
    }

    private static SmtpConfig plain(int port) {
        return SmtpConfig.smtpConfig("127.0.0.1", port)
                         .withTlsMode(SmtpTlsMode.NONE)
                         .withCommandTimeout(SHORT);
    }

    /// One reply GROUP per client line (the greeting group first, unprompted); records commands;
    /// after the script is exhausted holds the socket and reports whether the client closed it.
    @SuppressWarnings({"JBCT-EX-01", "JBCT-LAM-01"})
    private static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket socket;
        private final List<List<String>> replies;
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final AtomicInteger connections = new AtomicInteger();
        private volatile boolean clientClosed;

        ScriptedServer(List<List<String>> replies) {
            this.replies = replies;
            this.socket = open();
            Thread.ofPlatform().daemon().start(this::serve);
        }

        int port() {
            return socket.getLocalPort();
        }

        boolean awaitClientClosed(long millis) {
            var deadline = System.currentTimeMillis() + millis;

            while (!clientClosed && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }

            return clientClosed;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException _) {
            // closing
            }
        }

        private static ServerSocket open() {
            try {
                return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                return fail("cannot open a loopback server: " + e);
            }
        }

        private void serve() {
            try {
                while (true) {
                    try (Socket client = socket.accept()) {
                        connections.incrementAndGet();
                        session(client);
                    }
                }
            } catch (IOException _) {
            // server closed
            }
        }

        private void session(Socket client) throws IOException {
            var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = client.getOutputStream();
            var index = 0;

            if (!replies.isEmpty()) {
                reply(out, replies.get(index++));
            }

            while (index < replies.size()) {
                var line = in.readLine();

                if (line == null) {
                    clientClosed = true;

                    return;
                }

                commands.add(line);
                reply(out, replies.get(index++));
            }
            // script exhausted: hold the socket and watch for the client closing it
            clientClosed = in.readLine() == null;
        }

        private static void reply(OutputStream out, List<String> lines) throws IOException {
            for (var line : lines) {
                out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }

            out.flush();
        }
    }
}
