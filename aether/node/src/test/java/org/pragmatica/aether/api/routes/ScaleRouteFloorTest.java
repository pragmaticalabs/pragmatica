// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.management.route.ManagementRoute;
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

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #1495 (owner ruling): the instance floor holds at runtime, so `POST /api/v1/scale` refuses a count below
/// three with a 400 that names the floor. The refusal comes before any cluster lookup, so the node is never
/// touched: a request below the floor is malformed whatever the slice's state.
class ScaleRouteFloorTest {
    private static final String ARTIFACT = "org.example:floor-slice:1.0.0";

    @Test
    void scaleRoute_twoInstances_refusedBadRequestNamingTheFloor() {
        var cause = scaleCause(2);
        var recorder = writeProblem(cause);

        assertThat(recorder.status.get()).as("a count below the floor is a malformed request, not a server fault")
                                         .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(recorder.body()).contains("Requested 2 instances")
                                   .contains("must run at least 3 instances");
        assertThat(cause).isInstanceOfSatisfying(ScaleRouteError.InstancesBelowFloor.class,
                                                 refused -> assertThat(refused.requested()).isEqualTo(2));
    }

    @Test
    void scaleRoute_zeroInstances_refusedBadRequest() {
        assertThat(scaleCause(0)).isInstanceOf(ScaleRouteError.InstancesBelowFloor.class);
    }

    private static Cause scaleCause(int instances) {
        var holder = new AtomicReference<Cause>();

        scaleRoute().handler()
                    .handle(new ScaleRequestContext(new SliceRoutes.ScaleRequest(ARTIFACT, instances, null)))
                    .await()
                    .onSuccess(value -> fail("Scale below the floor must be refused, got: " + value))
                    .onFailure(holder::set);

        return holder.get();
    }

    private static Route<?> scaleRoute() {
        var routes = SliceRoutes.sliceRoutes(() -> fail("a request below the floor must be refused before the node is read"))
                                .routes()
                                .filter(candidate -> candidate.name()
                                                              .equals(ManagementRoute.SLICE_SCALE.name()))
                                .toList();

        return routes.isEmpty()
               ? fail("SLICE_SCALE route not registered")
               : routes.getFirst();
    }

    private static RecordingResponseWriter writeProblem(Cause cause) {
        var recorder = new RecordingResponseWriter();

        ProblemResponses.writeProblem(recorder, cause, "/api/v1/scale", "req-1");

        return recorder;
    }

    private record ScaleRequestContext(SliceRoutes.ScaleRequest request) implements RequestContext {
        @Override
        public List<String> pathParams() {
            return List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return Result.success((T) request);
        }

        @Override
        public Route<?> route() {
            return unsupported("route");
        }

        @Override
        public HttpHeaders responseHeaders() {
            return unsupported("responseHeaders");
        }

        @Override
        public String requestId() {
            return unsupported("requestId");
        }

        @Override
        public HttpMethod method() {
            return unsupported("method");
        }

        @Override
        public String path() {
            return unsupported("path");
        }

        @Override
        public Headers headers() {
            return unsupported("headers");
        }

        @Override
        public QueryParams queryParams() {
            return unsupported("queryParams");
        }
    }

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();
        private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
            this.body.set(body);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }

        String body() {
            return new String(body.get(), StandardCharsets.UTF_8);
        }
    }

    private static <T> T unsupported(String methodName) {
        return fail("Not touched by the scale route handler: " + methodName);
    }
}
