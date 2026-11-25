// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterEventView;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.FrameworkCodecs;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;


/// #1631 through the REAL `GET /api/v1/events` route handler and a REAL aggregator on the replicated cluster-events
/// stream (the same harness shape `ClusterEventAggregatorTest` uses): the `code` and `severity` query parameters select
/// events, an unknown code answers an empty list, an unknown severity answers 400 BEFORE any event is read, and the
/// filter applies to what the `sinceSeq` cursor selected rather than replacing it.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EventsFilterRouteTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final String INSTANCE = "/api/v1/events";

    /// Year 2100 in epoch millis: the legacy `sinceSeq` cursor is read as epoch-millis, so this selects nothing.
    private static final String CURSOR_PAST_EVERY_EVENT = "4102444800000";

    private HlcClock hlc;
    private ClusterEventAggregator aggregator;

    @BeforeEach
    void startAggregatorWithThreeWarnings() {
        hlc = HlcClock.hlcClock(SELF);
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                               1,
                                               RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY),
                                               "earliest",
                                               64L * 1024,
                                               ConsistencyMode.EVENTUAL,
                                               1);
        var publisher = SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS, manager, codec, config).unwrap();
        var consumer = SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS, manager, codec, codec, config).unwrap();

        aggregator = ClusterEventAggregator.clusterEventAggregator(() -> publisher, () -> consumer, () -> true, SELF, hlc, () -> 1, () -> false, () -> true);
        aggregator.emit(warning("swim-kill-gate-held", Severity.WARNING));
        aggregator.emit(warning("core-absence-fence", Severity.CRITICAL));
        aggregator.emit(warning("stream-consumer-state-repaired", Severity.INFO));
    }

    private ClusterEvent warning(String code, Severity severity) {
        return new ClusterEvent.OperatorWarning(hlc.now(), severity, "summary of " + code, Map.of("code", code));
    }

    @Test
    void noFilter_listsEveryEvent() {
        assertThat(codesOf(Map.of())).containsExactlyInAnyOrder("swim-kill-gate-held", "core-absence-fence", "stream-consumer-state-repaired");
    }

    @Test
    void code_listsOnlyThatWarningClass() {
        assertThat(codesOf(Map.of("code", List.of("core-absence-fence")))).containsExactly("core-absence-fence");
    }

    @Test
    void unknownCode_answersAnEmptyList_notAnError() {
        assertThat(viewsOf(Map.of("code", List.of("no-such-code")))).isEmpty();
    }

    @Test
    void severity_isAMinimum() {
        assertThat(codesOf(Map.of("severity", List.of("WARNING")))).containsExactlyInAnyOrder("swim-kill-gate-held", "core-absence-fence");
        assertThat(codesOf(Map.of("severity", List.of("critical")))).containsExactly("core-absence-fence");
    }

    @Test
    void codeAndSeverity_combineWithAnd() {
        assertThat(viewsOf(Map.of("code", List.of("swim-kill-gate-held"), "severity", List.of("CRITICAL")))).isEmpty();
        assertThat(codesOf(Map.of("code", List.of("swim-kill-gate-held"), "severity", List.of("INFO")))).containsExactly("swim-kill-gate-held");
    }

    @Test
    void unknownSeverity_answers400_withoutReadingEvents() {
        var failure = failureOf(untouchableNode(), Map.of("severity", List.of("loud")));

        assertThat(failure).isInstanceOf(ManagementServerError.InvalidRequest.class);
        assertThat(problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void filter_appliesToTheEventsTheCursorSelected_notInsteadOfIt() {
        assertThat(viewsOf(Map.of("sinceSeq", List.of("0"), "code", List.of("core-absence-fence")))).hasSize(1);
        assertThat(viewsOf(Map.of("sinceSeq", List.of(CURSOR_PAST_EVERY_EVENT), "code", List.of("core-absence-fence")))).isEmpty();
    }

    private List<String> codesOf(Map<String, List<String>> query) {
        return viewsOf(query).stream()
                             .map(view -> view.details().get("code"))
                             .toList();
    }

    private List<ClusterEventView> viewsOf(Map<String, List<String>> query) {
        var holder = new AtomicReference<List<ClusterEventView>>();
        var failure = new AtomicReference<Cause>();

        handler(nodeServing(aggregator)).handler().handle(new StubRequestContext(List.of(), query, null, INSTANCE))
                                        .await()
                                        .onSuccess(value -> holder.set(viewsFrom(value)))
                                        .onFailure(failure::set);
        assertThat(failure.get()).as("route failed").isNull();

        return holder.get();
    }

    @SuppressWarnings("unchecked")
    private static List<ClusterEventView> viewsFrom(Object value) {
        return (List<ClusterEventView>) value;
    }

    private static Cause failureOf(ManageableNode node, Map<String, List<String>> query) {
        var holder = new AtomicReference<Cause>();

        handler(node).handler().handle(new StubRequestContext(List.of(), query, null, INSTANCE))
                     .await()
                     .onSuccess(value -> Assertions.fail("must fail, got: " + value))
                     .onFailure(holder::set);

        return holder.get();
    }

    private static Route<?> handler(ManageableNode node) {
        return StatusRoutes.statusRoutes(() -> node, () -> null)
                           .routes()
                           .filter(candidate -> candidate.name().equals(ManagementRoute.EVENTS.name()))
                           .findFirst()
                           .orElseThrow();
    }

    private static ManageableNode nodeServing(ClusterEventAggregator aggregator) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> method.getName().equals("eventAggregator")
                                                                         ? aggregator
                                                                         : unsupported(method.getName()));
    }

    /// A node that fails on ANY call: a request refused at the parse must never get as far as asking it for events.
    private static ManageableNode untouchableNode() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> unsupported("Reached past the parameter parse: " + method.getName()));
    }

    private static Object unsupported(String what) {
        throw new UnsupportedOperationException(what);
    }

    private static HttpStatus problemStatus(Cause cause) {
        var status = new AtomicReference<HttpStatus>();

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus s, byte[] body, ContentType contentType) {
                status.set(s);
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, cause, INSTANCE, "req-1");

        return status.get();
    }

    private record StubRequestContext(List<String> pathParams, Map<String, List<String>> query, Object requestBody, String path) implements RequestContext {
        @Override
        public QueryParams queryParams() {
            return QueryParams.queryParams(query);
        }

        @Override
        public Route<?> route() {
            throw new UnsupportedOperationException("route");
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            throw new UnsupportedOperationException("fromJson");
        }

        @Override
        public HttpHeaders responseHeaders() {
            throw new UnsupportedOperationException("responseHeaders");
        }

        @Override
        public String requestId() {
            return "req-1";
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
}
