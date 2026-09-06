// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #772 — a route requiring a JSON body must reject a missing or malformed body as a 400 naming
/// what was expected, through the real `SliceRouter` dispatch, instead of the framework's
/// `ErrorMapper#defaultMapper` 500 fallback. Pinned by [org.pragmatica.http.routing.RequestContext#jsonBody]
/// wrapping [org.pragmatica.http.routing.RequestContext#fromJson] failures as a typed
/// [org.pragmatica.http.HttpError], exactly mirroring `PathParameter#mapped` (#397).
///
/// A body that PARSES successfully but fails domain validation performed by the handler itself is
/// NOT touched by this fix — the handler's own `Cause` is never an `HttpError`, so it still falls
/// through `ErrorMapper#defaultMapper` to 500 today: pinned by
/// [#postItem_returns500_whenBodyParsesButHandlerRejectsDomainValidation] `[verified:]`.
class SliceRouterJsonBodyTest {
    record CreateItem(String name) {}

    record ItemCreated(String id) {}

    private SliceRouter router(AtomicBoolean invoked) {
        var route = Route.<ItemCreated>post("/items")
                         .withBody(CreateItem.class)
                         .to(item -> {
                             invoked.set(true);
                             return Promise.success(new ItemCreated("item-" + item.name()));
                         })
                         .as(CommonContentType.APPLICATION_JSON);
        RouteSource source = () -> Stream.of(route);
        return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());
    }

    private HttpResponseData send(SliceRouter router, byte[] body) {
        var request = HttpRequestContext.httpRequestContext("/items", "POST", Map.of(), Map.of(), body, "req_test");
        return router.handle(request).await().unwrap();
    }

    @Test
    void postItem_returns400_namingExpectedType_whenBodyMissing() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), new byte[0]);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(invoked).isFalse();

        var body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"status\":400");
        assertThat(body).contains("CreateItem");
        // #772 cosmetic fix (JsonError.TypeMismatch): a root-level/pathless mismatch (Jackson's
        // getPathReference() returns "", not null) must not leave a dangling "... at " suffix.
        assertThat(body).doesNotContain(" at \"");
    }

    @Test
    void postItem_returns400_whenBodyMalformed() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "{not json".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(invoked).isFalse();
    }

    @Test
    void postItem_returns200_andInvokesHandler_whenBodyValid() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "{\"name\":\"widget\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(invoked).isTrue();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("item-widget");
    }

    @Test
    void postItem_returns500_whenBodyParsesButHandlerRejectsDomainValidation() {
        // #772/C4 [verified:]: a body that PARSES but fails domain validation performed by the
        // handler itself is unaffected by this fix — the handler's plain Cause is never an
        // HttpError, so it still falls through ErrorMapper#defaultMapper to 500 today.
        var route = Route.<ItemCreated>post("/items")
                         .withBody(CreateItem.class)
                         .to(item -> Promise.<ItemCreated>failure(Causes.cause("name must not be blank")))
                         .as(CommonContentType.APPLICATION_JSON);
        RouteSource source = () -> Stream.of(route);
        var router = SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());

        var response = send(router, "{\"name\":\"\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(500);
    }
}
