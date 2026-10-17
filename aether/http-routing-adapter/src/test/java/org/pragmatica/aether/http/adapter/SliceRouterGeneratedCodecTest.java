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
import org.pragmatica.http.ProblemDetailJson;
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
import org.pragmatica.serialization.json.spi.JsonReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        codecs.putAll(SliceRouterGeneratedCodecTest_CreateItemJson.CODECS);
        codecs.putAll(SliceRouterGeneratedCodecTest_ItemCreatedJson.CODECS);
        return SliceRouter.sliceRouter(source,
                                       ErrorMapper.defaultMapper(),
                                       JACKSON,
                                       RouteMountMode.pathMode(),
                                       GeneratedJsonCodec.generatedJsonCodec(codecs, recordingFallback()));
    }

    private HttpResponseData send(SliceRouter router, byte[] body) {
        return send(router, "/items", body);
    }

    private HttpResponseData send(SliceRouter router, String path, byte[] body) {
        var request = HttpRequestContext.httpRequestContext(path, "POST", Map.of(), Map.of(), body, "req_test");
        return router.handle(request).await().unwrap();
    }

    // Collection bodies: the element type has a generated codec, so the body is decoded by the List/Set combinator.
    private SliceRouter collectionRouter() {
        var list = Route.<ItemCreated>post("/batch")
                        .withBody(new TypeToken<List<CreateItem>>() {})
                        .to(items -> Promise.success(new ItemCreated(items.size() + ":" + items.getLast().name())))
                        .as(CommonContentType.APPLICATION_JSON);
        var set = Route.<ItemCreated>post("/unique")
                       .withBody(new TypeToken<Set<CreateItem>>() {})
                       .to(items -> Promise.success(new ItemCreated("unique-" + items.size())))
                       .as(CommonContentType.APPLICATION_JSON);
        RouteSource source = () -> Stream.of(list, set);
        var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>();

        codecs.putAll(SliceRouterGeneratedCodecTest_CreateItemJson.CODECS);
        codecs.putAll(SliceRouterGeneratedCodecTest_ItemCreatedJson.CODECS);
        return SliceRouter.sliceRouter(source,
                                       ErrorMapper.defaultMapper(),
                                       JACKSON,
                                       RouteMountMode.pathMode(),
                                       GeneratedJsonCodec.generatedJsonCodec(codecs, recordingFallback()));
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
    void listBody_isDecodedByGeneratedCodec_andNeverTouchesFallback() {
        var response = send(collectionRouter(), "/batch", "[{\"name\":\"a\"},{\"name\":\"b\"}]".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"2:b\"}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(fallbackCalls).isEmpty();
    }

    @Test
    void setBody_isDecodedByGeneratedCodec_keepingOneOfEachElement_andNeverTouchesFallback() {
        var response = send(collectionRouter(), "/unique", "[{\"name\":\"a\"},{\"name\":\"a\"},{\"name\":\"b\"}]".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"unique-2\"}");
        assertThat(fallbackCalls).isEmpty();
    }

    @Test
    void listBody_returns400_namingTheCollectionType_whenAnElementIsRefused() {
        var response = send(collectionRouter(), "/batch", "[{\"name\":\"a\"},{}]".getBytes(StandardCharsets.UTF_8));
        var body = new String(response.body(), StandardCharsets.UTF_8);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body).contains("Invalid JSON body for List<CreateItem>: Missing required field: name");
        assertThat(fallbackCalls).isEmpty();
    }

    // A generated container codec for the body type wins over the combinator: the marker codec's value comes back.
    @Test
    void containerCodec_isUsedBeforeTheCombinator_andNeverTouchesFallback() {
        var listType = new TypeToken<List<CreateItem>>() {};
        var codec = GeneratedJsonCodec.generatedJsonCodec(SliceRouterGeneratedCodecTest_CreateItemJson.CODECS,
                                                          Map.of(listType.token(), MARKER_LIST),
                                                          recordingFallback());

        assertThat(codec.decoderFor(listType.token()).unwrap()).isSameAs(MARKER_LIST);
        assertThat(codec.deserialize("[{\"name\":\"a\"}]".getBytes(StandardCharsets.UTF_8), listType).unwrap())
            .containsExactly(new CreateItem("marker"));
        assertThat(fallbackCalls).isEmpty();
    }

    // The combinator for a collection body is built on the first request of that type and reused after.
    @Test
    void combinatorCodec_isBuiltOncePerBodyType() {
        var setType = new TypeToken<Set<CreateItem>>() {}.token();
        var codec = GeneratedJsonCodec.generatedJsonCodec(SliceRouterGeneratedCodecTest_CreateItemJson.CODECS, recordingFallback());
        var first = codec.decoderFor(setType).unwrap();

        assertThat(codec.decoderFor(setType).unwrap()).isSameAs(first);
        assertThat(codec.decoderFor(new TypeToken<Set<CreateItem>>() {}.token()).unwrap()).isSameAs(first);
        assertThat(codec.decoderFor(new TypeToken<List<ItemCreated>>() {}.token()).isEmpty()).isTrue();
    }

    private static final JsonTypeCodec<List<CreateItem>> MARKER_LIST = JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeNull(),
                                                                                                    SliceRouterGeneratedCodecTest::readMarker);

    private static List<CreateItem> readMarker(JsonReader in) {
        in.skipValue();
        return List.of(new CreateItem("marker"));
    }

    @Test
    void unknownTypes_goToFallback() {
        var codec = GeneratedJsonCodec.generatedJsonCodec(SliceRouterGeneratedCodecTest_CreateItemJson.CODECS, recordingFallback());

        assertThat(codec.serialize(Map.of("k", 1)).isSuccess()).isTrue();
        assertThat(codec.deserialize("{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8), new TypeToken<ItemCreated>() {}).isSuccess()).isTrue();
        assertThat(fallbackCalls).hasSize(2);
    }

    @Test
    void listOfGeneratedType_isEncodedWithoutFallback_andMatchesJackson() {
        var codec = GeneratedJsonCodec.generatedJsonCodec(SliceRouterGeneratedCodecTest_ItemCreatedJson.CODECS, recordingFallback());
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
            assertThat(new String(ProblemDetailJson.CODEC.encode(detail), StandardCharsets.UTF_8))
                .isEqualTo(new String(JACKSON.writeAsBytes(detail).unwrap(), StandardCharsets.UTF_8));
        }
    }
}
