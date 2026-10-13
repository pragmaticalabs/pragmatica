// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes.SliceFloor;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2184: the management server's WebSocket feeds, driven through a real socket by the JDK client. The
/// server registers `/ws/dashboard`, `/ws/status` and `/ws/events` in that order; before #2184 the second and
/// third never completed the handshake, and no endpoint's handler ever saw `Open`. The existing WebSocket
/// tests in this module call `handle(...)` directly and could not see either defect.
///
/// The harness is the mocked-collaborator `ManagementServer` of `ManagementServerDhtCatchUpGaugeTest`: the
/// lightest one that runs the real `buildServerConfig()` endpoint registration and the real publishers on a
/// real `NettyHttpServer`. A Forge or Ember cluster would add consensus and slice deployment that the feeds
/// do not need. The port comes from [ClusterTestPorts], so nothing collides under `-T 1C` (#939).
class ManagementServerWebSocketFeedsTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final long WAIT_SECONDS = 10;

    /// As in `ManagementServerDhtCatchUpGaugeTest`: unstubbed collaborators answer `Option.none()` for
    /// optional parts and a nested mock for the rest.
    private static final Answer<Object> NONE_OR_MOCK = invocation -> {
        var type = invocation.getMethod().getReturnType();

        if (type == Option.class) {
            return Option.none();
        }

        var fallback = RETURNS_DEFAULTS.answer(invocation);

        if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
            return fallback;
        }

        return mock(type, ManagementServerWebSocketFeedsTest.NONE_OR_MOCK);
    };

    private final int port = ClusterTestPorts.freeTcpAndUdpPort();
    private ManagementServer server;
    private HttpClient client;

    @BeforeEach
    void createClient() {
        client = HttpClient.newBuilder()
                           .connectTimeout(Duration.ofSeconds(WAIT_SECONDS))
                           .build();
    }

    @AfterEach
    void tearDown() {
        client.shutdownNow();
        if (server != null) {
            server.stop().await(START_BOUND);
        }
    }

    /// The status publisher broadcasts the node's status every second to connected, authenticated clients.
    /// With security off, a client on `/ws/status` receives that JSON.
    @Test
    @Timeout(60)
    void statusFeed_deliversTheStatusBroadcast_overARealSocket() throws Exception {
        start(false);

        var listener = new FeedListener();

        connect("/ws/status", listener);

        assertThat(listener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).as("a status broadcast on /ws/status")
                                                                      .isNotNull()
                                                                      .startsWith("{\"uptimeSeconds\":");
    }

    /// With security on, `Open` on `/ws/events` makes the authenticator ask the client to authenticate: a
    /// message that only exists if the handler saw the session open.
    @Test
    @Timeout(60)
    void eventsFeed_deliversAuthRequiredOnOpen_overARealSocket() throws Exception {
        start(true);

        var listener = new FeedListener();

        connect("/ws/events", listener);

        assertThat(listener.texts.poll(WAIT_SECONDS, TimeUnit.SECONDS)).as("the authenticator's prompt on /ws/events")
                                                                      .isEqualTo("{\"type\":\"AUTH_REQUIRED\"}");
    }

    private void start(boolean securityEnabled) {
        server = managementServer(securityEnabled);

        var started = server.start().await(START_BOUND);

        assertThat(started.isSuccess()).as("control: the server started: %s", started).isTrue();
    }

    private void connect(String path, FeedListener listener) throws Exception {
        client.newWebSocketBuilder()
              .buildAsync(URI.create("ws://127.0.0.1:" + port + path), listener)
              .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private ManagementServer managementServer(boolean securityEnabled) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        // Registered at start; a final type the default answer leaves null.
        when(node.streamPartitionManager()).thenReturn(mock(StreamPartitionManager.class));
        // Read by every status broadcast; a sealed type the default answer leaves null.
        when(node.deploymentMap()).thenReturn(DeploymentMap.deploymentMap());

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
                                                 securityEnabled,
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
                                                 SliceFloor.sliceFloor((_, _) -> List.of(), OperatorWarningSink.logOnly()));
    }

    private static final class FeedListener implements WebSocket.Listener {
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                texts.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);

            return null;
        }
    }
}
