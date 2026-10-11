// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.pragmatica.aether.api.routes.ProblemResponses;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// #2101: client bodies render causes through ONE renderer ([HttpError#clientMessage(Cause)]); the chain goes to the
/// server log only. Every Cause shape that can carry a chain -- a plain chained cause, an HttpError over an HttpError,
/// a composite of chained HttpErrors, a composite inside an HttpError, an HttpError inside a composite -- is driven
/// through the real [SliceRouter] and through [ProblemResponses]. Each shape has a visible top-level token and hidden
/// sentinels; a control per shape proves the hidden ones ARE in the chain-walking `message()` (otherwise a pass would
/// mean nothing).
class CauseShapeClientBodyMatrixTest {
    record Chained(String message, Cause origin) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(origin);
        }
    }

    private record Shape(String name, Cause failure, List<String> visible, List<String> hidden) {}

    private static HttpError chainedHttp(HttpStatus status, String visible, String hidden) {
        return HttpError.httpError(status, new Chained(visible, Causes.cause(hidden)));
    }

    private static List<Shape> shapes() {
        return List.of(new Shape("plain chained",
                                 new Chained("V1-top", Causes.cause("H1-deep")),
                                 List.of("V1-top"),
                                 List.of("H1-deep")),
                       new Shape("nested HttpError",
                                 HttpError.httpError(HttpStatus.BAD_GATEWAY, chainedHttp(HttpStatus.CONFLICT, "V2-top", "H2-deep")),
                                 List.of("V2-top"),
                                 List.of("H2-deep")),
                       new Shape("composite of chained HttpErrors",
                                 Causes.composite(chainedHttp(HttpStatus.CONFLICT, "V3a-top", "H3a-deep").result(),
                                                  chainedHttp(HttpStatus.CONFLICT, "V3b-top", "H3b-deep").result()),
                                 List.of("V3a-top", "V3b-top"),
                                 List.of("H3a-deep", "H3b-deep")),
                       new Shape("composite inside HttpError",
                                 HttpError.httpError(HttpStatus.SERVICE_UNAVAILABLE,
                                                     Causes.composite(chainedHttp(HttpStatus.CONFLICT, "V4a-top", "H4a-deep").result(),
                                                                      chainedHttp(HttpStatus.BAD_GATEWAY, "V4b-top", "H4b-deep").result())),
                                 List.of("V4a-top", "V4b-top"),
                                 List.of("H4a-deep", "H4b-deep")),
                       new Shape("HttpError inside composite",
                                 Causes.composite(Causes.cause("V5a-plain").result(),
                                                  chainedHttp(HttpStatus.BAD_GATEWAY, "V5b-top", "H5b-deep").result()),
                                 List.of("V5a-plain", "V5b-top"),
                                 List.of("H5b-deep")));
    }

    private static String chainWalkingMessage(Cause failure) {
        return failure instanceof HttpError ? failure.message() : HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, failure).message();
    }

    private static final ErrorMapper GENERATED_STYLE_MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    private static String viaSliceRouter(Cause failure) {
        Route<String> route = Route.route(HttpMethod.GET,
                                          "/shape",
                                          ctx -> failure.<String> promise(),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "shape");
        RouteSource source = () -> Stream.of(route);
        var request = HttpRequestContext.httpRequestContext("/shape", "GET", Map.of(), Map.of(), "req_2101");
        var response = SliceRouter.sliceRouter(source, GENERATED_STYLE_MAPPER, JsonMapper.defaultJsonMapper())
                                  .handle(request)
                                  .await()
                                  .unwrap();

        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String viaProblemResponses(Cause failure) {
        var body = new AtomicReference<String>();

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus status, byte[] bytes, ContentType contentType) {
                body.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, failure, "/shape", "req_2101");

        return body.get();
    }

    @TestFactory
    Stream<DynamicTest> everyShape_sentinelsAreInTheChainWalkingMessage_control() {
        return shapes().stream()
                       .map(shape -> DynamicTest.dynamicTest(shape.name(),
                                                             () -> assertThat(chainWalkingMessage(shape.failure())).contains(shape.hidden())));
    }

    @TestFactory
    Stream<DynamicTest> everyShape_sliceRouterBodyOmitsHiddenAndKeepsVisible() {
        return shapes().stream().map(shape -> DynamicTest.dynamicTest(shape.name(), () -> {
            var body = viaSliceRouter(shape.failure());

            assertThat(body).contains(shape.visible());
            shape.hidden().forEach(hidden -> assertThat(body).doesNotContain(hidden));
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyShape_problemResponsesBodyOmitsHiddenAndKeepsVisible() {
        return shapes().stream().map(shape -> DynamicTest.dynamicTest(shape.name(), () -> {
            var body = viaProblemResponses(shape.failure());

            assertThat(body).contains(shape.visible());
            shape.hidden().forEach(hidden -> assertThat(body).doesNotContain(hidden));
        }));
    }
}
