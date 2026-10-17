// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.routing.ParameterError;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;


/// #1921 (a): a malformed integer path or query parameter (`partition=abc`, `max=abc`) fails in the routing layer with a
/// `ParameterError` before any handler runs. That cause carries no status, so the funnel answered 500 for the caller's own
/// typo. The table below drives the REAL route handlers over every integer-typed parameter in the management route table,
/// then the exact `ProblemResponses.writeProblem` call the router makes.
class ManagementParameterErrorStatusTest {
    private static final String INSTANCE = "/api/v1/management";

    private static final List<String> ADDRESS = List.of("ns", "orders", "1.0.0");

    private record Case(ManagementRoute route, List<String> pathParams, Map<String, List<String>> query) {
        static Case of(ManagementRoute route, List<String> pathParams, Map<String, List<String>> query) {
            return new Case(route, pathParams, query);
        }
    }

    private static Stream<Case> malformedParameters() {
        return Stream.of(Case.of(ManagementRoute.STREAM_PARTITION, path(ADDRESS, "partitions", "abc"), Map.of()),
                         Case.of(ManagementRoute.STREAM_REPLICAS, path(ADDRESS, "replicas", "abc"), Map.of()),
                         Case.of(ManagementRoute.STREAM_READ, path(ADDRESS, "read", "abc"), Map.of()),
                         Case.of(ManagementRoute.STREAM_READ, path(ADDRESS, "read", "0"), Map.of("from", List.of("abc"))),
                         Case.of(ManagementRoute.STREAM_READ, path(ADDRESS, "read", "0"), Map.of("max", List.of("abc"))),
                         Case.of(ManagementRoute.STREAMS_EVENTS, path(ADDRESS, "events"), Map.of("fromOffset", List.of("abc"))),
                         Case.of(ManagementRoute.STREAMS_EVENTS, path(ADDRESS, "events"), Map.of("maxEvents", List.of("abc"))),
                         Case.of(ManagementRoute.STREAMS_LIST, List.of(), Map.of("limit", List.of("abc"))),
                         Case.of(ManagementRoute.STREAM_REPLICAS_LOCAL, List.of("orders", "abc"), Map.of()),
                         Case.of(ManagementRoute.EVENTS, List.of(), Map.of("sinceEpoch", List.of("abc"))),
                         Case.of(ManagementRoute.EVENTS, List.of(), Map.of("sinceSeq", List.of("abc"))));
    }

    @TestFactory
    Stream<DynamicTest> malformedIntegerParameter_answers400() {
        return malformedParameters().map(c -> DynamicTest.dynamicTest(c.route() + " " + c.pathParams() + " " + c.query(),
                                                                      () -> {
                                                                          var failure = failureOf(c.route(), c.pathParams(), c.query());

                                                                          assertThat(members(failure)).isNotEmpty().allSatisfy(member -> assertThat(member).isInstanceOf(ParameterError.class));
                                                                          assertThat(problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
                                                                      }));
    }

    @Test
    void parameterError_ofEveryVariant_answers400() {
        assertThat(problemStatus(new ParameterError.InvalidParameter("x"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problemStatus(new ParameterError.MissingParameter("x"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problemStatus(new ParameterError.PathMismatch("a", "b"))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void composite_ofParameterErrors_answers400() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(),
                                         new ParameterError.InvalidParameter("b").result());

        assertThat(problemStatus(composite)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// Control: the mapping is keyed on the parameter cause, not on "anything in a composite" -- a composite that also
    /// carries an untyped member, or a typed member of another status, keeps the answer it had before.
    @Test
    void composite_ofAParameterErrorAndAnUntypedMember_staysInternalServerError() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(), Causes.cause("untyped").result());

        assertThat(problemStatus(composite)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void composite_ofAParameterErrorAndAConflict_staysInternalServerError() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(),
                                         new ManagementServerError.NotLeader("").result());

        assertThat(problemStatus(composite)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void untypedCause_staysInternalServerError() {
        assertThat(problemStatus(Causes.cause("boom"))).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /// The routing layer hands a malformed parameter back wrapped in a composite (`Result.all` over the parameter parses).
    private static List<Cause> members(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? composite.stream().toList()
               : List.of(cause);
    }

    private static List<String> path(List<String> address, String... rest) {
        return Stream.concat(address.stream(), Stream.of(rest)).toList();
    }

    private static Cause failureOf(ManagementRoute which, List<String> pathParams, Map<String, List<String>> query) {
        var node = (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                           new Class[]{ManageableNode.class},
                                                           (_, method, _) -> {
                                                               throw new UnsupportedOperationException("Reached past the parameter parse: "
                                                                                                       + method.getName());
                                                           });
        var route = Stream.of(StreamApiRoutes.streamApiRoutes(() -> node, null, ConsumerGroupCoordinator.noOp(), null).routes(),
                              StreamRoutes.streamRoutes(() -> node, ConsumerGroupCoordinator.noOp(), null).routes(),
                              StatusRoutes.statusRoutes(() -> node, () -> null).routes())
                          .flatMap(routes -> routes)
                          .filter(candidate -> candidate.name().equals(which.name()))
                          .findFirst()
                          .orElseThrow();
        var holder = new AtomicReference<Cause>();

        route.handler()
             .handle(new StubRequestContext(pathParams, query, null, INSTANCE))
             .await()
             .onSuccess(value -> org.junit.jupiter.api.Assertions.fail("Route " + which.name() + " must fail, got: " + value))
             .onFailure(holder::set);

        return holder.get();
    }

    private static HttpStatus problemStatus(Cause cause) {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, cause, INSTANCE, "req-1");

        return recorder.status.get();
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
    }
}
