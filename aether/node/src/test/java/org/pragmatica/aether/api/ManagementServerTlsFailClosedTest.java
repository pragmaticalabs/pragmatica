// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/// A management listener whose TLS configuration cannot be built refuses to start. Before this the HTTP/1.1
/// server swallowed the TLS failure and served the management API (API keys, cluster control) in PLAIN TEXT.
class ManagementServerTlsFailClosedTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(30).seconds();

    private static final Answer<Object> NONE_OR_MOCK = new Answer<>() {
        @Override
        public Object answer(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
            var type = invocation.getMethod().getReturnType();

            if (type == Option.class) {
                return Option.none();
            }
            var fallback = RETURNS_DEFAULTS.answer(invocation);

            if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
                return fallback;
            }

            return mock(type, this);
        }
    };

    @Test
    @Timeout(60)
    void start_unbuildableTls_failsAndOpensNoListener() throws IOException {
        var port = freeTcpPort();
        var server = managementServerWithMissingCertificate(port);

        var outcome = server.start().await(START_BOUND);

        server.stop().await(START_BOUND);
        assertThat(outcome.isFailure()).as("start must fail, not serve plain HTTP").isTrue();
        outcome.onFailure(cause -> {
            assertThat(cause).isInstanceOf(HttpServerError.TlsFailed.class);
            assertThat(cause.message()).contains("TLS").contains("management");
        });
        assertThat(connects(port)).as("nothing listens on the management port after the refusal").isFalse();
    }

    private static ManagementServer managementServerWithMissingCertificate(int port) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        return ManagementServer.managementServer(port,
                                                 () -> node,
                                                 mock(EntityCheckpointDriver.class, NONE_OR_MOCK),
                                                 mock(AlertManager.class, NONE_OR_MOCK),
                                                 mock(ObservabilityConfigRegistry.class, NONE_OR_MOCK),
                                                 mock(InvocationTraceStore.class, NONE_OR_MOCK),
                                                 mock(LogLevelRegistry.class, NONE_OR_MOCK),
                                                 Option.none(),
                                                 mock(ScheduledTaskRegistry.class, NONE_OR_MOCK),
                                                 mock(ScheduledTaskManager.class, NONE_OR_MOCK),
                                                 mock(SliceInvoker.class, NONE_OR_MOCK),
                                                 mock(ScheduledTaskStateRegistry.class, NONE_OR_MOCK),
                                                 Option.some(TlsConfig.server(java.nio.file.Path.of("/missing/node-cert.pem"),
                                                                              java.nio.file.Path.of("/missing/node-key.pem"))),
                                                 mock(SecurityValidator.class, NONE_OR_MOCK),
                                                 false,
                                                 Map::of,
                                                 Option.none(),
                                                 Option.none(),
                                                 HttpProtocol.H1,
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 _ -> {},
                                                 Set::of,
                                                 org.pragmatica.aether.api.routes.NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> java.util.List.of(),
                                                                                                                         org.pragmatica.utility.warning.OperatorWarningSink.logOnly()));
    }

    private static boolean connects(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static int freeTcpPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
