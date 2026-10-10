// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2101: a failure wrapper keeps its origin chain (JBCT M7). The chain is for the server log; no client-facing error
/// body contains anything below the top cause's message -- on the normal problem path and on the fallback taken when
/// the problem body cannot be serialized.
class SliceRouterCauseChainLeakTest {
    private static final String SENTINEL = "SENTINEL-origin-detail";
    private static final String TOP = "top-level-failure";

    record Wrapped(String message, Cause origin) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(origin);
        }
    }

    private static final ErrorMapper GENERATED_STYLE_MAPPER = cause -> switch (cause) {
        case HttpError he -> he;
        default -> HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, cause);
    };

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger routerLog = (Logger) LoggerFactory.getLogger(SliceRouter.class);

    @BeforeEach
    void attach() {
        appender.start();
        routerLog.addAppender(appender);
        routerLog.setLevel(Level.WARN);
    }

    @AfterEach
    void detach() {
        routerLog.detachAppender(appender);
    }

    private static JsonMapper failingSerializer() {
        var real = JsonMapper.defaultJsonMapper();

        return (JsonMapper) Proxy.newProxyInstance(JsonMapper.class.getClassLoader(),
                                                   new Class[]{JsonMapper.class},
                                                   (_, method, args) -> {
                                                       if (method.getName().equals("writeAsBytes")) {
                                                           return Result.failure(Causes.cause("serializer down"));
                                                       }
                                                       return method.invoke(real, args);
                                                   });
    }

    private HttpResponseData respond(JsonMapper mapper) {
        var failure = new Wrapped(TOP, Causes.cause(SENTINEL));
        Route<String> route = Route.route(HttpMethod.GET,
                                          "/boom",
                                          ctx -> failure.<String> promise(),
                                          CommonContentType.APPLICATION_JSON,
                                          List.of(),
                                          "boom");
        RouteSource source = () -> Stream.of(route);
        var request = HttpRequestContext.httpRequestContext("/boom", "GET", Map.of(), Map.of(), "req_2101");

        return SliceRouter.sliceRouter(source, GENERATED_STYLE_MAPPER, mapper).handle(request).await().unwrap();
    }

    private static String body(HttpResponseData response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private String serverLog() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void serializationFallback_omitsOriginChainFromClientBody_andLogsIt() {
        var response = respond(failingSerializer());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(body(response)).doesNotContain(SENTINEL);
        assertThat(serverLog()).contains(SENTINEL);
    }

    @Test
    void problemBody_omitsOriginChain_andSliceErrorLogCarriesIt() {
        var response = respond(JsonMapper.defaultJsonMapper());

        assertThat(body(response)).contains(TOP).doesNotContain(SENTINEL);
        assertThat(serverLog()).contains("req_2101").contains(TOP).contains(SENTINEL);
    }
}
