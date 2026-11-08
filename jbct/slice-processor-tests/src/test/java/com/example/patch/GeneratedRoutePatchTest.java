// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.patch;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2884 end to end: the generated router of a real slice, built with the production slice-route mapper, applies a
/// PUT-style partial update whose body tells a left-out field (keep), an explicit `null` (clear) and a value (replace)
/// apart, both when the body is decoded by the routes holder's generated codec and when it falls back to Jackson; a
/// `Patch` in a response is written as the body reads it; and a `Patch` outside a record component is refused.
class GeneratedRoutePatchTest {
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final String UNCHANGED = "200 {\"name\":\"ann\",\"nickname\":\"annie\",\"age\":30}";

    private SliceRouter router;

    @BeforeEach
    void setUp() {
        router = new PatchSliceRoutes().create(PatchSlice.patchSlice(), PRODUCTION);
    }

    // The two bodies take different decoders: ProfileUpdate a generated one, JacksonProfileUpdate the mapper.
    @Test
    void registry_holdsACodecForTheGeneratedBody_andNoneForTheJacksonOne() {
        assertThat(PatchSliceRoutesJsonCodecs.CODECS).containsKeys(ProfileUpdate.class, Profile.class);
        assertThat(PatchSliceRoutesJsonCodecs.CODECS).doesNotContainKeys(JacksonProfileUpdate.class, TagsUpdate.class);
    }

    @Test
    void put_leftOutFields_keepTheirValues_onBothPaths() {
        assertThat(put("/api/patch/profile", "{}")).isEqualTo(UNCHANGED);
        assertThat(put("/api/patch/profile-jackson", "{}")).isEqualTo(UNCHANGED);
    }

    @Test
    void put_explicitNulls_clearTheFields_onBothPaths() {
        var cleared = "200 {\"name\":\"ann\",\"nickname\":null,\"age\":null}";

        assertThat(put("/api/patch/profile", "{\"nickname\":null,\"age\":null}")).isEqualTo(cleared);
        assertThat(put("/api/patch/profile-jackson", "{\"nickname\":null,\"age\":null}")).isEqualTo(cleared);
    }

    @Test
    void put_values_replaceTheFields_onBothPaths() {
        var replaced = "200 {\"name\":\"ann\",\"nickname\":\"nan\",\"age\":31}";

        assertThat(put("/api/patch/profile", "{\"nickname\":\"nan\",\"age\":31}")).isEqualTo(replaced);
        assertThat(put("/api/patch/profile-jackson", "{\"nickname\":\"nan\",\"age\":31}")).isEqualTo(replaced);
    }

    // One body, three states: nickname cleared, age left out (kept).
    @Test
    void put_mixedStates_eachFieldFollowsItsOwnState_onBothPaths() {
        var mixed = "200 {\"name\":\"ann\",\"nickname\":null,\"age\":30}";

        assertThat(put("/api/patch/profile", "{\"nickname\":null}")).isEqualTo(mixed);
        assertThat(put("/api/patch/profile-jackson", "{\"nickname\":null}")).isEqualTo(mixed);
    }

    @Test
    void echo_patchInAResponse_absentLeftOut_nullWritten() {
        assertThat(send("POST", "/api/patch/echo", "{}")).isEqualTo("200 {}");
        assertThat(send("POST", "/api/patch/echo", "{\"age\":null}")).isEqualTo("200 {\"age\":null}");
        assertThat(send("POST", "/api/patch/echo", "{\"nickname\":\"n\",\"age\":null}")).isEqualTo("200 {\"nickname\":\"n\",\"age\":null}");
    }

    @Test
    void put_patchAsAListElement_isRefused() {
        var response = put("/api/patch/tags", "{\"tags\":[\"a\",null]}");

        assertThat(response).doesNotStartWith("200 ")
                            .contains("a Patch is supported only as a record component");
    }

    private String put(String path, String body) {
        return send("PUT", path, body);
    }

    private String send(String method, String path, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(path, method, Map.of(), Map.of(),
                                                                   body.getBytes(StandardCharsets.UTF_8), "req_patch"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()),
                           response -> response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8));
    }
}
