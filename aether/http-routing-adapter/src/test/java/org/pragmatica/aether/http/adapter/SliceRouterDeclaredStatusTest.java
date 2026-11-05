// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.Route;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #868 — a cause declaring its own status ([HttpStatusAware]) is honoured anywhere in the cause chain, and a
/// [Cause.Validation] answers 422; everything else stays 500.
class SliceRouterDeclaredStatusTest {
    record Conflict(String message) implements HttpStatusAware {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record Wrapper(String message, Cause origin) implements Cause.Wrapped {}

    private static final ErrorMapper UNMAPPED = cause -> HttpStatus.INTERNAL_SERVER_ERROR.with(cause);

    @Test void handle_statusAwareCause_returnsDeclaredStatus() {
        assertThat(send(new Conflict("dup"))).isEqualTo(409);
    }

    @Test void handle_statusAwareCauseDeepInSourceChain_returnsDeclaredStatus() {
        assertThat(send(new Wrapper("outer", new Wrapper("middle", new Conflict("dup"))))).isEqualTo(409);
    }

    @Test void handle_statusAwareCauseInCauseWithSource_returnsDeclaredStatus() {
        assertThat(send(Causes.cause("outer", Option.some(new Conflict("dup"))))).isEqualTo(409);
    }

    @Test void handle_wrappedUnmarkedCause_returns500() {
        assertThat(send(new Wrapper("outer", Causes.cause("plain")))).isEqualTo(500);
    }

    @Test void handle_validationCause_returns422() {
        assertThat(send(Causes.validation("bad input"))).isEqualTo(422);
    }

    @Test void handle_unmarkedCause_returns500() {
        assertThat(send(Causes.cause("bad input"))).isEqualTo(500);
    }

    @Test void handle_allValidationComposite_returns422() {
        var composite = Causes.composite(Causes.validation("a").result(), Causes.validation("b").result());

        assertThat(send(composite)).isEqualTo(422);
    }

    @Test void handle_validationNextToServerFault_returns500() {
        var composite = Causes.composite(Causes.validation("a").result(), Causes.cause("storage").result());

        assertThat(send(composite)).isEqualTo(500);
    }

    record AwareValidation(String message) implements HttpStatusAware, Cause.Validation {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    record AwareWrapper(String message, Cause origin) implements HttpStatusAware, Cause.Wrapped {
        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }

    @Test void handle_wrappedValidationCause_returns422() {
        assertThat(send(new Wrapper("outer", Causes.validation("bad input")))).isEqualTo(422);
    }

    @Test void handle_validationAsCauseSource_returns422() {
        assertThat(send(Causes.cause("outer", Option.some(Causes.validation("bad input"))))).isEqualTo(422);
    }

    @Test void handle_statusAwareAboveValidation_returnsDeclaredStatus() {
        assertThat(send(new AwareWrapper("outer", Causes.validation("bad input")))).isEqualTo(409);
    }

    @Test void handle_statusAwareValidation_returnsDeclaredStatus() {
        assertThat(send(new AwareValidation("both"))).isEqualTo(409);
    }

    @Test void handle_wrappedValidationWithExplicitMapperStatus_keepsMapperStatus() {
        var mapper = (ErrorMapper) cause -> HttpStatus.FORBIDDEN.with(cause);

        assertThat(send(new Wrapper("outer", Causes.validation("bad input")), mapper)).isEqualTo(403);
    }

    private static int send(Cause failure) {
        return send(failure, UNMAPPED);
    }

    private static int send(Cause failure, ErrorMapper mapper) {
        var route = Route.<String>get("/items").withoutParameters().to(_ -> Promise.<String>failure(failure)).asJson();
        var router = SliceRouter.sliceRouter(route, mapper, JsonMapper.defaultJsonMapper());

        return router.handle(HttpRequestContext.httpRequestContext("/items", "GET", Map.<String, List<String>>of(), Map.of(), "test"))
                     .await(timeSpan(5).seconds()).unwrap().statusCode();
    }
}
