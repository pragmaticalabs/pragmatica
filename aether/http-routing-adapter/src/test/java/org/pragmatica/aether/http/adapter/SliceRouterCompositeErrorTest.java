// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SliceRouterCompositeErrorTest {
    record InvalidRow(String message) implements Cause {}
    private static final ErrorMapper MAPPER = cause -> cause instanceof InvalidRow
        ? HttpStatus.BAD_REQUEST.with(cause) : HttpStatus.INTERNAL_SERVER_ERROR.with(cause);

    @Test void handle_malformedPrimitivePath_returns400() {
        var route = Route.<String>get("/items/").withPath(PathParameter.aLong()).to(id -> Promise.success("item"))
            .asJson();
        assertThat(send(route, "/items/invalid", Map.of())).isEqualTo(400);
    }

    @Test void handle_malformedPrimitiveQuery_returns400() {
        var route = Route.<String>get("/items").withQuery(QueryParameter.aInteger("limit"))
            .to(_ -> Promise.success("items")).asJson();
        assertThat(send(route, "/items", Map.of("limit", List.of("invalid")))).isEqualTo(400);
    }

    @Test void handle_aggregatedDomainValidation_preservesDeclared400() {
        var route = Route.<String>get("/items").withoutParameters().to(_ -> Result.allOf(List.<Result<String>>of(
            new InvalidRow("row one").result(), new InvalidRow("row two").result())).async().map(_ -> "unused")).asJson();
        assertThat(send(route, "/items", Map.of())).isEqualTo(400);
    }

    @Test void handle_mixedClientAndServerFailures_preserves500() {
        var route = Route.<String>get("/items").withoutParameters().to(_ -> Result.allOf(List.<Result<String>>of(
            new InvalidRow("row one").result(), Causes.cause("storage failure").result())).async().map(_ -> "unused")).asJson();
        assertThat(send(route, "/items", Map.of())).isEqualTo(500);
    }

    private static int send(Route<?> route, String path, Map<String, List<String>> query) {
        var router = SliceRouter.sliceRouter(route, MAPPER, JsonMapper.defaultJsonMapper());
        return router.handle(HttpRequestContext.httpRequestContext(path, "GET", query, Map.of(), "test"))
            .await(timeSpan(5).seconds()).unwrap().statusCode();
    }
}
