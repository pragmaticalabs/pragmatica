// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbodysnake;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.slice.JsonCodecSelection;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.aether.slice.JsonImplementation;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.json.JsonOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2957 under `[json] naming = "snake_case"`: the bound set names Java components (`articleSlug`) while the body
/// carries wire names (`article_slug`), so the skip and the not-required mask must tell the two apart. Under the
/// deploy-time `fail_on_unknown` option a skipped path-bound body member is still not an unknown member.
class GeneratedPathBodySnakeRouteTest {
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final String PATH = "/api/pathbodysnake/articles/s1";
    private static final JsonOptions FAIL_ON_UNKNOWN = JsonOptions.jsonOptions(JsonOptions.NullInclusion.EXCLUDE,
                                                                               true,
                                                                               false,
                                                                               1000,
                                                                               Integer.MAX_VALUE,
                                                                               Integer.MAX_VALUE,
                                                                               Integer.MAX_VALUE)
                                                                  .unwrap();

    /// A settings handle fixed to one selection.
    record Fixed(JsonCodecSelection current) implements JsonCodecSettings {
        @Override
        public Unit refresh() {
            return Unit.unit();
        }
    }

    /// What the node hands the route publisher: the slice, which is also a `Slice` with its settings.
    record Deployed(SnakeSlice delegate, JsonCodecSettings jsonCodecSettings) implements SnakeSlice, Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        @Override
        public Promise<SnakeEcho> rename(SnakeRename request) {
            return delegate.rename(request);
        }
    }

    @Test
    void bodyWithoutTheBoundWireName_takesTheComponentFromThePath() {
        assertThat(send(plain(), "{\"body_text\":\"t\"}")).isEqualTo("200 {\"detail\":\"slug=s1,text=t\"}");
    }

    @Test
    void boundWireNameInTheBody_isSkippedUnread_evenWhenItsTypeIsWrong() {
        assertThat(send(plain(), "{\"article_slug\":{\"not\":[\"a string\"]},\"body_text\":\"t\"}"))
            .isEqualTo("200 {\"detail\":\"slug=s1,text=t\"}");
    }

    @Test
    void bodyComponent_staysRequiredUnderItsWireName() {
        assertThat(send(plain(), "{}")).startsWith("400 ")
                                       .contains("body_text");
    }

    @Test
    void failOnUnknown_doesNotCountASkippedBoundMember_butStillRefusesAnUnknownOne() {
        var strict = strict();

        assertThat(send(strict, "{\"article_slug\":\"other\",\"body_text\":\"t\"}"))
            .isEqualTo("200 {\"detail\":\"slug=s1,text=t\"}");
        assertThat(send(strict, "{\"body_text\":\"t\",\"extra\":1}")).startsWith("400 ");
    }

    private static SliceRouter plain() {
        return new SnakeSliceRoutes().create(SnakeSlice.snakeSlice(), PRODUCTION);
    }

    private static SliceRouter strict() {
        var selection = JsonCodecSelection.jsonCodecSelection("strict", JsonImplementation.GENERATED, FAIL_ON_UNKNOWN);

        return new SnakeSliceRoutes().create(new Deployed(SnakeSlice.snakeSlice(), new Fixed(selection)), PRODUCTION);
    }

    private static String send(SliceRouter router, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(PATH, "PUT", Map.of(), Map.of(),
                                                                   body.getBytes(StandardCharsets.UTF_8), "req_snake"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()),
                           response -> response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8));
    }
}
