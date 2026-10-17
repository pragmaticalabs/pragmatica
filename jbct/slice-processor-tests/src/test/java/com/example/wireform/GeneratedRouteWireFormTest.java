// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.wireform;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// The generated router of a real slice, built with the production slice-route mapper, answers every route
/// with the bytes that mapper writes: Jackson-annotated types (a `@JsonTypeInfo` hierarchy, a `@JsonProperty`
/// rename) never get a generated codec, an empty Option is written as Jackson writes it, and value objects
/// travel bare on generated and fallback bodies alike.
class GeneratedRouteWireFormTest {
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private SliceRouter router;

    @BeforeEach
    void setUp() {
        router = new WireFormSliceRoutes().create(WireFormSlice.wireFormSlice(), PRODUCTION);
    }

    @Test
    void registry_holdsNoCodecForJacksonAnnotatedTypes_orTypesReachingThem() {
        assertThat(WireFormSliceRoutesJsonCodecs.CODECS).containsKeys(Signup.class, Email.class, Accounts.class, ShapeRequest.class, Note.class);
        assertThat(WireFormSliceRoutesJsonCodecs.CODECS).doesNotContainKeys(Circle.class, Drawing.class, Renamed.class);
    }

    @Test
    void polymorphicResponse_keepsJacksonDiscriminator() {
        assertThat(post("/api/wire/shape", "{\"r\":3}")).isEqualTo("200 {\"kind\":\"circle\",\"r\":3}")
                                                      .isEqualTo("200 " + jackson(new Circle(3)));
        assertThat(post("/api/wire/drawing", "{\"r\":3}")).isEqualTo("200 {\"main\":{\"kind\":\"circle\",\"r\":3}}")
                                                        .isEqualTo("200 " + jackson(new Drawing(new Circle(3))));
    }

    @Test
    void jsonPropertyRename_isHonoured() {
        assertThat(post("/api/wire/renamed", "{\"r\":1}")).isEqualTo("200 {\"user_name\":\"bob\"}")
                                                        .isEqualTo("200 " + jackson(new Renamed("bob")));
    }

    @Test
    void emptyOptionInResponse_equalsProductionMapperBytes() {
        assertThat(post("/api/wire/note", "{\"r\":1}")).isEqualTo("200 {\"id\":\"1\",\"text\":null}")
                                                     .isEqualTo("200 " + jackson(new Note("1", Option.none())));
    }

    @Test
    void valueObjectBareForm_acceptedOnGeneratedAndFallbackBodies() {
        assertThat(post("/api/wire/signup", "{\"email\":\"a@b.c\"}")).isEqualTo("200 {\"emails\":[\"a@b.c\"]}");
        assertThat(post("/api/wire/signup-all", "[{\"email\":\"a@b.c\"},{\"email\":\"d@e.f\"}]"))
            .isEqualTo("200 {\"emails\":[\"a@b.c\",\"d@e.f\"]}")
            .isEqualTo("200 " + jackson(new Accounts(List.of(new Email("a@b.c"), new Email("d@e.f")))));
    }

    @Test
    void refusedValueObject_is400_onGeneratedAndFallbackBodies() {
        assertThat(post("/api/wire/signup", "{\"email\":\"nope\"}")).startsWith("400 ").contains("not an email: nope");
        assertThat(post("/api/wire/signup-all", "[{\"email\":\"nope\"}]")).startsWith("400 ").contains("not an email: nope");
    }

    private String post(String path, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(path, "POST", Map.of(), Map.of(),
                                                                   body.getBytes(StandardCharsets.UTF_8), "req_wire"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()),
                           response -> response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8));
    }

    private static String jackson(Object value) {
        return PRODUCTION.writeAsString(value).unwrap();
    }
}
