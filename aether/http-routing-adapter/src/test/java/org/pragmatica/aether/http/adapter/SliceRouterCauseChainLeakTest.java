// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
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

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private final List<String> events = new ArrayList<>();
    private final AbstractAppender appender = new AbstractAppender("cause-chain-capture", null, null, true, Property.EMPTY_ARRAY) {
        @Override
        public void append(LogEvent event) {
            if (event.getLoggerName().equals(SliceRouter.class.getName())) {
                synchronized (events) {
                    events.add(event.getMessage().getFormattedMessage());
                }
            }
        }
    };

    @BeforeEach
    void attach() {
        var context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);

        appender.start();
        context.getConfiguration().getRootLogger().addAppender(appender, Level.ALL, null);
        context.updateLoggers();
    }

    @AfterEach
    void detach() {
        var context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);

        context.getConfiguration().getRootLogger().removeAppender(appender.getName());
        context.updateLoggers();
        appender.stop();
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
        return respond(mapper, new Wrapped(TOP, Causes.cause(SENTINEL)));
    }

    private HttpResponseData respond(JsonMapper mapper, Cause failure) {
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
        synchronized (events) {
            return String.join("\n", events);
        }
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

    /// A chain of three-plus links with an [HttpError] in the middle, each link carrying its own sentinel. The origin
    /// of the outer error is itself an [HttpError], so a detail built from the origin's `message()` walks the chain.
    private static HttpError nestedChain() {
        var plain = Causes.cause("S3-plain-deep");
        var mid = new Wrapped("S2-mid", plain);
        var inner = HttpError.httpError(HttpStatus.CONFLICT, new Wrapped("S1-top", mid));

        return HttpError.httpError(HttpStatus.BAD_GATEWAY, inner);
    }

    @Test
    void nestedHttpErrorOrigin_control_chainWalkerSeesEverySentinel() {
        assertThat(nestedChain().message()).contains("S1-top", "S2-mid", "S3-plain-deep");
    }

    @Test
    void nestedHttpErrorOrigin_problemBodyKeepsTopCauseOnly() {
        var response = respond(JsonMapper.defaultJsonMapper(), nestedChain());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(body(response)).contains("S1-top").doesNotContain("S2-mid").doesNotContain("S3-plain-deep");
    }

    @Test
    void nestedHttpErrorOrigin_serverLogCarriesEveryLinkInOrder() {
        respond(JsonMapper.defaultJsonMapper(), nestedChain());

        assertThat(serverLog()).contains("(cause chain: Bad Gateway <- Conflict <- S1-top <- S2-mid <- S3-plain-deep)");
    }
}
