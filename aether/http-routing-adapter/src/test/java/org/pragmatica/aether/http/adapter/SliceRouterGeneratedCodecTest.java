// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.JsonCodec;
import org.pragmatica.http.ProblemDetail;
import org.pragmatica.http.ProblemDetailJsonCodecs;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonTypeCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The generated-codec path through SliceRouter keeps the contract SliceRouterJsonBodyTest pins for the
/// Jackson path (400 naming the target type, no dangling location text), actually serves the request
/// without Jackson, and writes error bodies byte-identical to what Jackson wrote.
class SliceRouterGeneratedCodecTest {
    @Json
    record CreateItem(String name) {}

    @Json
    record ItemCreated(String id) {}

    private static final JsonMapper JACKSON = JsonMapper.defaultJsonMapper();

    // Records which types reached the fallback: the generated codec must have served CreateItem/ItemCreated.
    private final List<String> fallbackCalls = new ArrayList<>();

    private JsonCodec recordingFallback() {
        var jackson = JsonCodecAdapter.forMapper(JACKSON);

        return new JsonCodec() {
            @Override
            public Result<byte[]> serialize(Object value) {
                fallbackCalls.add("serialize " + value.getClass().getSimpleName());
                return jackson.serialize(value);
            }

            @Override
            public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) {
                fallbackCalls.add("deserialize " + token.token().getTypeName());
                return jackson.deserialize(bytes, token);
            }
        };
    }

    private SliceRouter router(AtomicBoolean invoked) {
        var route = Route.<ItemCreated>post("/items")
                         .withBody(CreateItem.class)
                         .to(item -> {
                             invoked.set(true);
                             return Promise.success(new ItemCreated("item-" + item.name()));
                         })
                         .as(CommonContentType.APPLICATION_JSON);
        RouteSource source = () -> Stream.of(route);
        var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>();

        codecs.putAll(SliceRouterGeneratedCodecTest_CreateItemJsonCodecs.CODECS);
        codecs.putAll(SliceRouterGeneratedCodecTest_ItemCreatedJsonCodecs.CODECS);
        return SliceRouter.sliceRouter(source,
                                       ErrorMapper.defaultMapper(),
                                       JACKSON,
                                       RouteMountMode.pathMode(),
                                       GeneratedJsonCodec.generatedJsonCodec(codecs, recordingFallback()));
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
        assertThat(body).doesNotContain(" at \"");
        assertThat(fallbackCalls).isEmpty();
    }

    @Test
    void postItem_returns400_whenBodyMalformed() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "{not json".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(invoked).isFalse();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("CreateItem");
        assertThat(fallbackCalls).isEmpty();
    }

    @Test
    void postItem_returns200_withGeneratedCodecs_andNeverTouchesFallback() {
        var invoked = new AtomicBoolean(false);
        var response = send(router(invoked), "{\"name\":\"widget\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(invoked).isTrue();
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"item-widget\"}");
        assertThat(fallbackCalls).isEmpty();
    }

    @Test
    void unknownTypes_goToFallback() {
        var codec = GeneratedJsonCodec.generatedJsonCodec(SliceRouterGeneratedCodecTest_CreateItemJsonCodecs.CODECS, recordingFallback());

        assertThat(codec.serialize(Map.of("k", 1)).isSuccess()).isTrue();
        assertThat(codec.deserialize("{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8), new TypeToken<ItemCreated>() {}).isSuccess()).isTrue();
        assertThat(fallbackCalls).hasSize(2);
    }

    @Test
    void listOfGeneratedType_isEncodedWithoutFallback_andMatchesJackson() {
        var codec = GeneratedJsonCodec.generatedJsonCodec(SliceRouterGeneratedCodecTest_ItemCreatedJsonCodecs.CODECS, recordingFallback());
        var items = List.of(new ItemCreated("a"), new ItemCreated("b\"c"));

        assertThat(new String(codec.serialize(items).unwrap(), StandardCharsets.UTF_8))
            .isEqualTo(new String(JACKSON.writeAsBytes(items).unwrap(), StandardCharsets.UTF_8));
        assertThat(fallbackCalls).isEmpty();
    }

    // Error bodies moved from Jackson to the generated ProblemDetail codec: the bytes must not change.
    @Test
    void problemDetail_generatedBytes_equalJackson() {
        var details = List.of(ProblemDetail.problemDetail(HttpStatus.BAD_REQUEST, "Invalid JSON body for CreateItem: \"x\" é", "/items", "req_1"),
                              ProblemDetail.problemDetail(HttpStatus.NOT_FOUND, "No route", "/missing?q=1", "req_2"),
                              new ProblemDetail("about:blank", "Server Error", 500, Option.none(), Option.none(), "req_3"),
                              new ProblemDetail("t", "T", 409, Option.some("line\nbreak\ttab"), Option.none(), "r"));

        for (var detail : details) {
            assertThat(new String(ProblemDetailJsonCodecs.ProblemDetailJson.CODEC.encode(detail), StandardCharsets.UTF_8))
                .isEqualTo(new String(JACKSON.writeAsBytes(detail).unwrap(), StandardCharsets.UTF_8));
        }
    }
}
