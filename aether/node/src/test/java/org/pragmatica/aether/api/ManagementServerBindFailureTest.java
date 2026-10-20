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
import org.pragmatica.aether.http.TlsProbe;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/// #2152 — a management listener whose port is already held. Pins three things the flaky TLS-rotation test needed:
/// `start()` FAILS with a typed bind failure naming the port (so the node's own start chain, which flatMaps on it, refuses to
/// boot rather than run with no management API), the test-side `requireStarted` surfaces that cause AT the start instead of
/// letting a later assertion fail without one, and `boundPort()` reports the port a port-0 listener really bound.
class ManagementServerBindFailureTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(60).seconds();

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

    /// A TCP port this test deliberately HOLDS (taken by someone else, as in the flake), chosen the way the module's other
    /// tests choose theirs. Not `new ServerSocket(0)`: that is the reserve-then-release pattern `ClusterTestPortsTest` forbids.
    private static ServerSocket heldPort() throws IOException {
        return new ServerSocket(ClusterTestPorts.freeTcpAndUdpPort());
    }

    @Test
    @Timeout(120)
    void start_portAlreadyHeld_failsWithBindFailedNamingThePort() throws Exception {
        try (var thief = heldPort()) {
            var port = thief.getLocalPort();
            var server = managementServerOn(port);

            try {
                var outcome = server.start().await(BOUND);

                assertThat(outcome.isFailure()).as("a management listener that could not bind must fail start()").isTrue();
                outcome.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(HttpServerError.BindFailed.class,
                                                                                    failed -> assertThat(failed.port()).isEqualTo(port)));
                assertThat(server.boundPort()).as("nothing is reported as bound").isEqualTo(Option.none());
            } finally {
                server.stop().await(BOUND);
            }
        }
    }

    @Test
    @Timeout(120)
    void requireStarted_portAlreadyHeld_failsAtTheStartNamingTheBindCause() throws Exception {
        try (var thief = heldPort()) {
            var server = managementServerOn(thief.getLocalPort());

            try {
                var outcome = server.start().await(BOUND);

                assertThatThrownBy(() -> TlsProbe.requireStarted(outcome, "management listener"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("management listener did not start")
                    .hasMessageContaining("Failed to bind to port " + thief.getLocalPort())
                    .hasMessageContaining("Address already in use");
            } finally {
                server.stop().await(BOUND);
            }
        }
    }

    @Test
    @Timeout(120)
    void boundPort_portZero_reportsTheRealPortAndClearsOnStop() throws Exception {
        var server = managementServerOn(0);

        try {
            assertThat(server.boundPort()).as("nothing bound before start()").isEqualTo(Option.none());
            TlsProbe.requireStarted(server.start().await(BOUND), "management listener");
            int port = server.boundPort().or(0);

            assertThat(port).as("a port-0 listener reports the port it bound").isPositive();
            assertThat(server.boundPort()).as("reading the port does not consume it").isEqualTo(Option.some(port));
            try (var client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
                assertThat(client.isConnected()).as("the reported port is the one listening").isTrue();
            }
        } finally {
            server.stop().await(BOUND);
        }
        assertThat(server.boundPort()).as("nothing bound after stop()").isEqualTo(Option.none());
    }

    private static ManagementServer managementServerOn(int port) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        // The post-bind metrics registration dereferences the stream partition manager.
        org.mockito.Mockito.when(node.streamPartitionManager())
                           .thenReturn(mock(org.pragmatica.aether.stream.StreamPartitionManager.class, NONE_OR_MOCK));

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
                                                 Option.none(),
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

}
