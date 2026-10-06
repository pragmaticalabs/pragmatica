// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #1921: two defects of one class. (1) `ProblemResponses.resolveStatus` did not look inside a composite cause, which
/// `Result.all` / `Result.allOf` hand back when several typed failures funnel together, so a request refused for several
/// client-side reasons answered 500. (2) A caller-supplied id (blueprint id, artifact coordinates, version, node id) was parsed
/// by a domain parser whose failure carries no status, so a malformed id answered 500. Same method as the siblings of #954: the
/// real route handler, then the exact `ProblemResponses.writeProblem` call the router makes.
class ManagementCompositeAndIdParseStatusTest {
    private static final String REQUEST_ID = "req-1";
    private static final String INSTANCE = "/api/v1/management";

    // ---- (1) the composite ----

    @Test
    void composite_ofMembersThatAgree_answersTheirStatus() {
        var composite = Causes.composite(new ManagementServerError.InvalidRequest("a").result(),
                                         new ManagementServerError.InvalidRequest("b").result());

        assertThat(problemStatus(composite)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void composite_ofMembersThatDisagree_staysInternalServerError() {
        var composite = Causes.composite(new ManagementServerError.InvalidRequest("a").result(),
                                         new ManagementServerError.NotLeader("").result());

        assertThat(problemStatus(composite)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void composite_withAnUntypedMember_staysInternalServerError() {
        var composite = Causes.composite(new ManagementServerError.InvalidRequest("a").result(),
                                         Causes.cause("untyped").result());

        assertThat(problemStatus(composite)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void composite_thatIsEmpty_staysInternalServerError() {
        assertThat(problemStatus(Causes.composite())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---- (3) the consumer-group coordinator's not-leader refusal (an untyped CoordinatorError until now) ----

    @Test
    void streamsGroupCreate_answers409_whenTheCoordinatorIsNotTheLeader() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of("self", NodeId.nodeId("node-1").unwrap())),
                                                     null,
                                                     ConsumerGroupCoordinator.noOp(),
                                                     null);
        var body = new StreamApiRoutes.GroupCreateRequest("g1", null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAMS_GROUP_CREATE, List.of("ns", "orders", "1.0.0", "groups"), body))
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void consumerGroupJoin_answers409_whenTheCoordinatorIsNotTheLeader() {
        var routes = StreamRoutes.streamRoutes(() -> node(Map.of("self", NodeId.nodeId("node-1").unwrap())),
                                               ConsumerGroupCoordinator.noOp(),
                                               null);
        var body = new StreamRoutes.JoinGroupRequest("g1", "orders", 4, "c1");

        assertThat(statusOf(routes.routes(), ManagementRoute.CONSUMER_GROUP_JOIN, List.of(), body)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void consumerGroupLeave_answers409_whenTheCoordinatorIsNotTheLeader() {
        var routes = StreamRoutes.streamRoutes(() -> node(Map.of()), ConsumerGroupCoordinator.noOp(), null);
        var body = new StreamRoutes.LeaveGroupRequest("g1", "orders", "c1");

        assertThat(statusOf(routes.routes(), ManagementRoute.CONSUMER_GROUP_LEAVE, List.of(), body)).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- (4) stream and topic addresses (namespace / name / version) and their not-found refusals ----

    @Test
    void streamsMetadata_answers400_whenTheVersionIsMalformed() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of()), null, ConsumerGroupCoordinator.noOp(), null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAMS_METADATA, List.of("ns", "orders", "not-a-version")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void streamGet_answers400_whenTheAddressIsMalformed() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of()), null, ConsumerGroupCoordinator.noOp(), null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAM_GET, List.of("ns", "orders", "not-a-version", "info")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void streamsGroupsList_answers400_whenTheAddressIsMalformed() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of()), null, ConsumerGroupCoordinator.noOp(), null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAMS_GROUPS_LIST, List.of("ns", "orders", "not-a-version", "groups")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void topicsGroups_answers400_whenTheTopicAddressIsMalformed() {
        var routes = TopicRoutes.topicRoutes(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.TOPICS_GROUPS, List.of("ns", "events", "not-a-version", "groups")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void streamGet_answers404_whenTheStreamIsUnknown() {
        var manager = mock(StreamPartitionManager.class);

        when(manager.streamInfo(org.mockito.ArgumentMatchers.anyString())).thenReturn(Option.none());

        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of("streamPartitionManager", manager)),
                                                     null,
                                                     ConsumerGroupCoordinator.noOp(),
                                                     null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAM_GET, List.of("ns", "orders", "1.0.0", "info")))
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamsGroupDelete_answers404_whenTheGroupIsUnknown() {
        var routes = StreamApiRoutes.streamApiRoutes(() -> node(Map.of()), null, ConsumerGroupCoordinator.noOp(), null);

        assertThat(statusOf(routes.routes(), ManagementRoute.STREAMS_GROUP_DELETE, List.of("ns", "orders", "1.0.0", "groups", "g1")))
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- (2) caller-supplied ids ----

    @Test
    void blueprintGet_answers400_whenTheIdIsNotACoordinate() {
        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.BLUEPRINT_GET, List.of("not-a-coordinate")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blueprintStatus_answers400_whenTheIdIsNotACoordinate() {
        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.BLUEPRINT_STATUS, List.of("not-a-coordinate")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blueprintDelete_answers400_whenTheIdIsNotACoordinate() {
        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.BLUEPRINT_DELETE, List.of("not-a-coordinate")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sliceConfig_answers400_whenTheIdIsNotAnArtifactCoordinate() {
        var routes = SliceRoutes.sliceRoutes(() -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.SLICE_CONFIG, List.of("garbage")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deployStart_answers400_whenTheBlueprintVersionIsMalformed() {
        var routes = DeployRoutes.deployRoutes(() -> node(Map.of()));
        var body = new DeployRoutes.DeployRequest("org.example:app:not-a-version", "rolling", null, null, null, null, null, null);

        assertThat(statusOf(routes.routes(), ManagementRoute.DEPLOY_START, List.of(), body)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void configNodeDelete_answers400_whenTheNodeIdIsBlank() {
        var routes = ConfigRoutes.configRoutes(mock(DynamicConfigManager.class), () -> node(Map.of()));

        assertThat(statusOf(routes.routes(), ManagementRoute.CONFIG_NODE_DELETE, List.of("  ", "some.key")))
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static HttpStatus problemStatus(Cause cause) {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, cause, INSTANCE, REQUEST_ID);

        return recorder.status();
    }

    private static HttpStatus statusOf(java.util.stream.Stream<Route<?>> routes, ManagementRoute which, List<String> pathParams) {
        return statusOf(routes, which, pathParams, null);
    }

    private static HttpStatus statusOf(java.util.stream.Stream<Route<?>> routes,
                                       ManagementRoute which,
                                       List<String> pathParams,
                                       Object body) {
        var route = routes.filter(candidate -> candidate.name().equals(which.name())).findFirst().orElseThrow();
        var holder = new AtomicReference<Cause>();

        route.handler()
             .handle(new StubRequestContext(pathParams, body, INSTANCE))
             .await()
             .onSuccess(value -> org.junit.jupiter.api.Assertions.fail("Route " + which.name() + " must fail, got: " + value))
             .onFailure(holder::set);

        return problemStatus(holder.get());
    }

    /// Answers only what the test names; any other call means the handler got past the refusal under test.
    private static ManageableNode node(Map<String, Object> stubs) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> {
                                                           if (stubs.containsKey(method.getName())) {
                                                               return stubs.get(method.getName());
                                                           }
                                                           throw new UnsupportedOperationException(
                                                               "Reached past the refusal under test: " + method.getName());
                                                       });
    }

    private record StubRequestContext(List<String> pathParams, Object requestBody, String path) implements RequestContext {
        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(Map.of());
        }

        @Override
        public Route<?> route() {
            throw new UnsupportedOperationException("route");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return Result.success((T) requestBody);
        }

        @Override
        public HttpHeaders responseHeaders() {
            throw new UnsupportedOperationException("responseHeaders");
        }

        @Override
        public String requestId() {
            return REQUEST_ID;
        }

        @Override
        public HttpMethod method() {
            throw new UnsupportedOperationException("method");
        }

        @Override
        public Headers headers() {
            throw new UnsupportedOperationException("headers");
        }

        @Override
        public byte[] body() {
            throw new UnsupportedOperationException("body");
        }
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }

        HttpStatus status() {
            return status.get();
        }
    }
}
