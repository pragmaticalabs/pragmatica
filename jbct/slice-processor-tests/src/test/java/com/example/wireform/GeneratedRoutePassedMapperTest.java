// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.wireform;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: a slice built with names as declared, under default options, keeps the mapper passed to `create()` as the
/// fallback of its routes. (Under a build naming or non-default options the generated routes build their own.)
class GeneratedRoutePassedMapperTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    /// The production slice-route mapper, counting the writes that reach it.
    static final class CountingMapper implements JsonMapper {
        private final JsonMapper delegate = JsonMapper.valueObjectJsonMapper();
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public <T> Result<String> writeAsString(T value) {
            writes.incrementAndGet();
            return delegate.writeAsString(value);
        }

        @Override
        public <T> Result<byte[]> writeAsBytes(T value) {
            writes.incrementAndGet();
            return delegate.writeAsBytes(value);
        }

        @Override
        public <T> Result<T> readString(String json, Class<T> type) {
            return delegate.readString(json, type);
        }

        @Override
        public <T> Result<T> readBytes(byte[] json, Class<T> type) {
            return delegate.readBytes(json, type);
        }

        @Override
        public <T> Result<T> readString(String json, TypeToken<T> typeToken) {
            return delegate.readString(json, typeToken);
        }

        @Override
        public <T> Result<T> readBytes(byte[] json, TypeToken<T> typeToken) {
            return delegate.readBytes(json, typeToken);
        }

        @Override
        public Result<JsonNode> readTree(String json) {
            return delegate.readTree(json);
        }

        @Override
        public Result<String> prettyPrint(String json) {
            return delegate.prettyPrint(json);
        }
    }

    @Test
    void asDeclaredNaming_andDefaultOptions_keepThePassedMapperAsTheFallback() {
        var mapper = new CountingMapper();
        var router = new WireFormSliceRoutes().create(WireFormSlice.wireFormSlice(), mapper);

        // Drawing holds a Jackson-annotated hierarchy, so it has no generated codec: the fallback writes it.
        var response = router.handle(HttpRequestContext.httpRequestContext("/api/wire/drawing", "POST", Map.of(), Map.of(),
                                                                           "{\"r\":3}".getBytes(StandardCharsets.UTF_8), "req_mapper"))
                             .await(AWAIT)
                             .unwrap();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"main\":{\"kind\":\"circle\",\"r\":3}}");
        assertThat(mapper.writes.get()).isEqualTo(1);
    }
}
