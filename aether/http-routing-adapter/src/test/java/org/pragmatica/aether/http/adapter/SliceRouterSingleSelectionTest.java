// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: the router takes its codec from the source once per request, so the request body is decoded and the
/// response encoded by the same codec even when the source switches mid-request.
class SliceRouterSingleSelectionTest {
    record CreateItem(String name) {}

    record ItemCreated(String id) {}

    private static final JsonMapper JACKSON = JsonMapper.defaultJsonMapper();

    private final List<String> calls = new ArrayList<>();

    private JsonCodec tagged(String tag) {
        var jackson = JsonCodecAdapter.forMapper(JACKSON);

        return new JsonCodec() {
            @Override
            public Result<byte[]> serialize(Object value) {
                calls.add(tag + " encode");
                return jackson.serialize(value);
            }

            @Override
            public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) {
                calls.add(tag + " decode");
                return jackson.deserialize(bytes, token);
            }
        };
    }

    @Test
    void oneRequest_decodesAndEncodesWithTheCodecSampledAtItsStart() {
        var a = tagged("A");
        var b = tagged("B");
        var reads = new AtomicInteger();
        // The first read answers A, every later read B: a switch landing between the decode and the encode.
        JsonCodecSource source = () -> reads.getAndIncrement() == 0
                                       ? a
                                       : b;
        var route = Route.<ItemCreated>post("/items")
                         .withBody(CreateItem.class)
                         .to(item -> Promise.success(new ItemCreated("item-" + item.name())))
                         .as(CommonContentType.APPLICATION_JSON);
        RouteSource routes = () -> Stream.of(route);
        var router = SliceRouter.sliceRouter(routes, ErrorMapper.defaultMapper(), JACKSON, RouteMountMode.pathMode(), source);

        var first = router.handle(request("{\"name\":\"x\"}")).await().unwrap();

        assertThat(new String(first.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"item-x\"}");
        assertThat(calls).containsExactly("A decode", "A encode");

        calls.clear();
        router.handle(request("{\"name\":\"y\"}")).await().unwrap();

        assertThat(calls).containsExactly("B decode", "B encode");
    }

    private static HttpRequestContext request(String body) {
        return HttpRequestContext.httpRequestContext("/items", "POST", Map.of(), Map.of(),
                                                     body.getBytes(StandardCharsets.UTF_8), "req_selection");
    }
}
