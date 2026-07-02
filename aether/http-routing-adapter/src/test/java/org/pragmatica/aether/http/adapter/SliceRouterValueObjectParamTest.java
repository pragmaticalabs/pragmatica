// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end proof that a value-object HTTP path segment (#397) yields a real HTTP 400 through the
/// full `SliceRouter` dispatch when it cannot be lifted, and a 200 with the lifted value object when
/// it can. `matchPath` wraps a single path-param failure in a `Causes.CompositeCause` via
/// `Result.all`, so the framework `String -> P` parser's typed [org.pragmatica.http.HttpError] 400 is
/// buried inside a composite; `SliceRouter.resolveHttpError` unwraps it via [Cause#stream] so the
/// status is honored even under `ErrorMapper.defaultMapper()` (which would otherwise collapse an
/// unrecognized cause to 500). This is the runtime counterpart to the codegen assertion in
/// slice-processor-tests' `GeneratedVoRouteTest`.
class SliceRouterValueObjectParamTest {

    private static final UUID VALID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NIL = new UUID(0L, 0L);

    /// A value object parsed from a `UUID`, rejecting the nil UUID to exercise the lift-failure path.
    record SeatId(UUID value) {
        static Result<SeatId> seatId(UUID raw) {
            return raw.equals(NIL)
                   ? Causes.cause("seat id must not be the nil UUID").result()
                   : Result.success(new SeatId(raw));
        }
    }

    private SliceRouter router(AtomicBoolean invoked) {
        var route = Route.<String>get("/seats/")
                         .withPath(PathParameter.aUuid().mapped(SeatId::seatId))
                         .to(seatId -> {
                             invoked.set(true);
                             return Promise.success("seat-" + seatId.value());
                         })
                         .as(CommonContentType.APPLICATION_JSON);
        RouteSource source = () -> Stream.of(route);
        return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());
    }

    private HttpResponseData send(SliceRouter router, String path) {
        var request = HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "req_test");
        return router.handle(request)
                     .await()
                     .unwrap();
    }

    @Test
    void getSeat_returns200_andInvokesHandlerWithLiftedValueObject_forValidUuid() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "/seats/" + VALID);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body())).contains("seat-" + VALID);
        assertThat(invoked.get()).isTrue();
    }

    @Test
    void getSeat_returns400_andSkipsHandler_forMalformedUuid() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "/seats/not-a-uuid");

        assertThat(response.statusCode()).as("malformed value-object segment must be a typed 400, not a 500").isEqualTo(400);
        assertThat(invoked.get()).as("handler must not run when the segment cannot be parsed").isFalse();
    }

    @Test
    void getSeat_returns400_andSkipsHandler_whenLiftRejects() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "/seats/" + NIL);

        assertThat(response.statusCode()).as("rejected lift must be a typed 400, not a 500").isEqualTo(400);
        assertThat(invoked.get()).as("handler must not run when the value object cannot be lifted").isFalse();
    }
}
