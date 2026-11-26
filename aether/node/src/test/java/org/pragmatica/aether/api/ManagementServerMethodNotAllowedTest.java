// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.HttpForwarder;
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
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// A path the server knows under another method is `405` with the `Allow` list, not `404`. The probe of #3796 found every
/// refused write verb on `/engine-streams/{key}/events|consumers` answering `404 File not found`: a client cannot tell a
/// refused verb on a known path from a mistyped path, and a `404` reads as "unknown stream" for the stream routes. The
/// control is a path no method owns, which stays `404` with no `Allow`.
class ManagementServerMethodNotAllowedTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final String ENGINE_KEY = "topic:com.example.app:orders:1.0.0";

    private enum Verb {
        POST(HttpMethod.POST),
        PUT(HttpMethod.PUT),
        DELETE(HttpMethod.DELETE),
        PATCH(HttpMethod.PATCH);

        private final HttpMethod method;

        Verb(HttpMethod method) {
            this.method = method;
        }
    }

    private final RecordingResponseWriter writer = new RecordingResponseWriter();
    private final StreamPartitionManager partitionManager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

    @AfterEach
    void tearDown() {
        partitionManager.close();
    }

    @ParameterizedTest
    @EnumSource(Verb.class)
    void writeVerbOnEngineKeyEvents_isRefused405_withAllowGet(Verb verb) {
        dispatch(verb.method, "/api/v1/engine-streams/" + ENGINE_KEY + "/events");

        assertThat(writer.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(writer.headers()).containsEntry("Allow", "GET");
    }

    @ParameterizedTest
    @EnumSource(Verb.class)
    void writeVerbOnEngineKeyConsumers_isRefused405_withAllowGet(Verb verb) {
        dispatch(verb.method, "/api/v1/engine-streams/" + ENGINE_KEY + "/consumers");

        assertThat(writer.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(writer.headers()).containsEntry("Allow", "GET");
    }

    /// A bucket several routes share: the list names every method the path answers, in `HttpMethod` order, and only those.
    @Test
    void pathWithSeveralMethods_allowListsEachOfThem() {
        dispatch(HttpMethod.PUT, "/api/v1/streams/com.example.app/orders/1.0.0");

        assertThat(writer.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(writer.headers()).containsEntry("Allow", "GET, POST, DELETE");
    }

    /// Control: a path no method owns is still `404`, with no `Allow`, so the 405 above is the path's and not the fixture's.
    @Test
    void pathNoMethodOwns_isStill404_withoutAllow() {
        dispatch(HttpMethod.POST, "/api/v1/engine-streams/" + ENGINE_KEY + "/no-such-verb");

        assertThat(writer.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(writer.headers()).doesNotContainKey("Allow");
    }

    private void dispatch(HttpMethod method, String path) {
        server().dispatchManagementRequest(new Request(method, path),
                                           InstrumentedResponseWriter.instrumentedResponseWriter(writer),
                                           method.name(),
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
                                        NodeLifecycleRoutes.DrainPort.none(),
                                        NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> List.of(),
                                                                                                                   OperatorWarningSink.logOnly()));
    }

    private record Request(HttpMethod method, String path) implements HttpRequest {
        @Override public String requestId() {return "req-405";}
        @Override public Headers headers() {return Headers.empty();}
        @Override public QueryParams queryParams() {return QueryParams.empty();}
        @Override public byte[] body() {return new byte[0];}
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private HttpStatus status;

        synchronized HttpStatus status() {
            return status;
        }

        synchronized Map<String, String> headers() {
            return Map.copyOf(headers);
        }

        @Override public synchronized void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status = status;
        }

        @Override public synchronized ResponseWriter header(String name, String value) {
            headers.put(name, value);

            return this;
        }
    }
}
