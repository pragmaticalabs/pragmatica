// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwarder;
import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #1715 WIRED: that the assembled [ManagementServerImpl] serves `POST /api/v1/cluster/node-ids/reserve`.
///
/// [NodeIdRoutesTest] drives the routes class directly, and `ManagementRouteCoverageTest` scans route literals, not
/// registration, so a deleted `routeSources.add(NodeIdRoutes...)` in the server left both green while every
/// `aether cluster node-ids reserve` answered 404. The observable is the status the real dispatch writes for a request
/// the handler refuses on its own validation (a blank source -> 400): only a registered route can produce it, while an
/// unregistered path produces the server's 404 (the control below).
class ManagementServerNodeIdRouteWiringTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final String RESERVE_PATH = "/api/v1/cluster/node-ids/reserve";
    private static final String BLANK_SOURCE_BODY = "{\"source\":\"\",\"role\":\"core\",\"count\":1}";

    private final RecordingResponseWriter writer = new RecordingResponseWriter();
    private final StreamPartitionManager partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @Test
    void dispatchManagementRequest_servesTheReserveRoute_whenTheRequestReachesTheLeader() {
        dispatch(RESERVE_PATH, BLANK_SOURCE_BODY);

        assertThat(writer.writes())
                .as("the reserve route must be registered in the assembled server: 400 is the handler refusing a blank source, 404 is no route")
                .containsExactly(HttpStatus.BAD_REQUEST);
    }

    /// Control: the same fixture and body on a path no route owns reaches the 404, so the 400 above is the route's and not the fixture's.
    @Test
    void dispatchManagementRequest_answers404_forAPathNoRouteOwns() {
        dispatch("/api/v1/cluster/node-ids/no-such-action", BLANK_SOURCE_BODY);

        assertThat(writer.writes()).containsExactly(HttpStatus.NOT_FOUND);
    }

    private void dispatch(String path, String body) {
        server().dispatchManagementRequest(new PostRequest(path, body),
                                           InstrumentedResponseWriter.instrumentedResponseWriter(writer),
                                           "POST",
                                           System.nanoTime());
    }

    private ManagementServerImpl server() {
        var node = mock(ManageableNode.class);
        var appHttpServer = mock(AppHttpServer.class);

        when(appHttpServer.httpRoutePublisher()).thenReturn(Option.none());
        when(node.self()).thenReturn(SELF);
        when(node.isLeader()).thenReturn(true);
        when(node.leader()).thenReturn(Option.some(SELF));
        when(node.appHttpServer()).thenReturn(appHttpServer);
        when(node.httpRouteRegistry()).thenReturn(HttpRouteRegistry.httpRouteRegistry());
        when(node.taskGroupOwnerResolver()).thenReturn(HttpForwarder.UNASSIGNED_RESOLVER);
        when(node.clusterTopologyManager()).thenReturn(Option.none());
        when(node.consumerGroupCoordinator()).thenReturn(ConsumerGroupCoordinator.noOp());
        when(node.consumerGroupRegistry()).thenReturn(ConsumerGroupRegistry.consumerGroupRegistry());
        when(node.streamNamespacesService()).thenReturn(mock(StreamNamespacesService.class));
        when(node.streamReadRouter()).thenReturn(StreamReadRouter.streamReadRouter(partitionManager,
                                                                                   Option.none(),
                                                                                   Option.none(),
                                                                                   SELF,
                                                                                   (_, _) -> Option.none(),
                                                                                   StreamReadForwardMetrics.NOOP));

        return new ManagementServerImpl(0,
                                        () -> node,
                                        mock(EntityCheckpointDriver.class),
                                        mock(AlertManager.class),
                                        mock(ObservabilityConfigRegistry.class),
                                        mock(InvocationTraceStore.class),
                                        mock(LogLevelRegistry.class),
                                        Option.none(),
                                        mock(ScheduledTaskRegistry.class),
                                        mock(ScheduledTaskManager.class),
                                        mock(SliceInvoker.class),
                                        mock(ScheduledTaskStateRegistry.class),
                                        Option.none(),
                                        mock(SecurityValidator.class),
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

    private record PostRequest(String path, String text) implements HttpRequest {
        @Override public String requestId() {return "req-node-ids";}
        @Override public HttpMethod method() {return HttpMethod.POST;}
        @Override public Headers headers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return text.getBytes(StandardCharsets.UTF_8);}
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final List<HttpStatus> writes = new ArrayList<>();

        synchronized List<HttpStatus> writes() {
            return List.copyOf(writes);
        }

        @Override public synchronized void write(HttpStatus status, byte[] body, ContentType contentType) {
            writes.add(status);
        }

        @Override public ResponseWriter header(String name, String value) {return this;}
    }
}
