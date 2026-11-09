// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2957 end to end, through the generated router and the routes holder's generated codecs: a route that merges path
/// parameters with a JSON body fills the path-bound components from the path and never requires them in the body.
/// When the body also carries a member of the same name, the path wins: the member is skipped unread. A record whose
/// path-bound component another decode needs (a plain body route of the same record, or a nesting body) keeps it
/// required, for every decode of that record.
class GeneratedPathBodyRouteTest {
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();

    private SliceRouter router;

    @BeforeEach
    void setUp() {
        router = new PathBodySliceRoutes().create(PathBodySlice.pathBodySlice(), PRODUCTION);
    }

    @Test
    void registry_decodesTheMergedBodiesWithGeneratedCodecs() {
        assertThat(PathBodySliceRoutesJsonCodecs.CODECS).containsKeys(AddComment.class,
                                                                      UpdateArticle.class,
                                                                      RenameArticle.class);
    }

    @Nested
    class PathBoundComponents {
        @Test
        void addComment_bodyWithoutSlug_takesTheSlugFromThePath() {
            assertThat(send("POST", "/api/pathbody/articles/s1/comments", "{\"comment\":{\"body\":\"hi\"}}"))
                .isEqualTo("200 {\"detail\":\"slug=s1,body=hi\"}");
        }

        @Test
        void updateArticle_bodyWithoutSlug_takesTheSlugFromThePath() {
            assertThat(send("PUT", "/api/pathbody/articles/s1", "{\"article\":{\"title\":\"T\"}}"))
                .isEqualTo("200 {\"detail\":\"slug=s1,title=T\"}");
        }

        @Test
        void validatingFactory_judgesTheMergedValue_notTheBodyAlone() {
            assertThat(send("PUT", "/api/pathbody/renamed/s1", "{\"title\":\"t\"}"))
                .isEqualTo("200 {\"detail\":\"slug=s1,title=t\"}");
        }

        @Test
        void validatingFactory_stillRefusesAnInvalidBodyComponent() {
            assertThat(send("PUT", "/api/pathbody/renamed/s1", "{\"title\":\" \"}")).startsWith("400 ");
        }

        @Test
        void bodyComponents_stayRequired() {
            assertThat(send("POST", "/api/pathbody/articles/s1/comments", "{}"))
                .startsWith("400 ")
                .contains("comment");
        }
    }

    @Nested
    class SameNamedBodyMember {
        @Test
        void pathWins_overASameNamedBodyMember() {
            assertThat(send("POST", "/api/pathbody/articles/s1/comments", "{\"slug\":\"other\",\"comment\":{\"body\":\"hi\"}}"))
                .isEqualTo("200 {\"detail\":\"slug=s1,body=hi\"}");
        }

        @Test
        void sameNamedBodyMember_isSkippedUnread_evenWhenItsTypeIsWrong() {
            assertThat(send("PUT", "/api/pathbody/articles/s1", "{\"slug\":{\"not\":[\"a string\"]},\"article\":{\"title\":\"T\"}}"))
                .isEqualTo("200 {\"detail\":\"slug=s1,title=T\"}");
        }
    }

    // One codec per record serves every route of the slice, so a record decoded whole anywhere keeps every component
    // required, whatever order the routes are declared in. The merged route then needs the member in its body too.
    @Nested
    class StrictWhereAnotherDecodeNeedsTheComponent {
        @Test
        void plainBodyRoute_declaredAfterTheMergedOne_stillRequiresTheComponent() {
            assertThat(send("POST", "/api/pathbody/merged-first", "{\"text\":\"x\"}")).startsWith("400 ")
                                                                                     .contains("id");
            assertThat(send("PUT", "/api/pathbody/merged-first/m1", "{\"text\":\"x\"}")).startsWith("400 ");
        }

        @Test
        void plainBodyRoute_declaredBeforeTheMergedOne_stillRequiresTheComponent() {
            assertThat(send("POST", "/api/pathbody/plain-first", "{\"text\":\"x\"}")).startsWith("400 ")
                                                                                    .contains("id");
            assertThat(send("PUT", "/api/pathbody/plain-first/p1", "{\"text\":\"x\"}")).startsWith("400 ");
        }

        @Test
        void recordNestedInAnotherBody_stillRequiresTheComponent() {
            assertThat(send("POST", "/api/pathbody/envelope", "{\"inner\":{\"text\":\"x\"}}")).startsWith("400 ")
                                                                                             .contains("id");
            assertThat(send("PUT", "/api/pathbody/nested/n1", "{\"text\":\"x\"}")).startsWith("400 ");
        }

        @Test
        void strictRecords_stillServeACompleteBody() {
            assertThat(send("PUT", "/api/pathbody/merged-first/m1", "{\"id\":\"body\",\"text\":\"x\"}"))
                .isEqualTo("200 {\"detail\":\"id=m1,text=x\"}");
            assertThat(send("POST", "/api/pathbody/envelope", "{\"inner\":{\"id\":\"e1\",\"text\":\"x\"}}"))
                .isEqualTo("200 {\"detail\":\"id=e1,text=x\"}");
        }
    }

    private String send(String method, String path, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(path, method, Map.of(), Map.of(),
                                                                   body.getBytes(StandardCharsets.UTF_8), "req_pathbody"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()),
                           response -> response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8));
    }
}
