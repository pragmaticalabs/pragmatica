// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #884: every local route lookup compares a normalized request path against `pathPrefix` with
/// `startsWith`. That is a SEGMENT-BOUNDARY test only while the stored prefix ends in a slash --
/// otherwise `/api/pricing` swallows `/api/pricing-admin/report`, which is the #866 review G4 hole.
///
/// Both production producers reached a factory that normalized (`RouteMetadataExtractor`,
/// `SecurityOverrideApplier`), but nothing REFUSED a definition built any other way: the canonical
/// constructor and the `Result`-returning factory stored whatever they were handed. The invariant
/// now lives in the compact constructor, so it holds for every construction path and the selection
/// rule in `HttpRoutePublisher` can rely on it locally instead of on a convention held elsewhere.
class HttpRouteDefinitionPrefixTest {
    private static final String COORD = "org.example:svc:1.0.0";

    @Test
    void canonicalConstructor_normalizesThePrefix() {
        var route = new HttpRouteDefinition("GET", "/api/pricing", COORD, "handle", SecurityPolicy.publicRoute());

        assertThat(route.pathPrefix()).isEqualTo("/api/pricing/");
    }

    @Test
    void resultFactory_normalizesThePrefix() {
        var route = HttpRouteDefinition.httpRouteDefinition(Result.success("GET"),
                                                            Result.success("/api/pricing"),
                                                            Result.success(COORD),
                                                            Result.success("handle"),
                                                            Result.success(SecurityPolicy.publicRoute()));

        assertThat(route.map(HttpRouteDefinition::pathPrefix)
                        .or("<failed>")).isEqualTo("/api/pricing/");
    }

    @Test
    void stringFactory_normalizesThePrefix() {
        var route = HttpRouteDefinition.httpRouteDefinition("GET", "/api/pricing", COORD, "handle");

        assertThat(route.pathPrefix()).isEqualTo("/api/pricing/");
    }

    @Test
    void aMissingLeadingSlash_andABlankPrefix_areNormalizedToo() {
        assertThat(new HttpRouteDefinition("GET", "api/pricing", COORD, "handle", SecurityPolicy.publicRoute()).pathPrefix())
                .isEqualTo("/api/pricing/");
        assertThat(new HttpRouteDefinition("GET", "  ", COORD, "handle", SecurityPolicy.publicRoute()).pathPrefix())
                .isEqualTo("/");
    }

    @Test
    void normalizationIsIdempotent() {
        var once = new HttpRouteDefinition("GET", "/api/pricing", COORD, "handle", SecurityPolicy.publicRoute());
        var twice = new HttpRouteDefinition("GET", once.pathPrefix(), COORD, "handle", SecurityPolicy.publicRoute());

        assertThat(twice.pathPrefix()).isEqualTo(once.pathPrefix());
    }

    /// The consequence the invariant exists for, stated as the comparison the publisher performs.
    /// The positive control is the sibling assertion: the same comparison DOES match a path that
    /// genuinely lies under the prefix, so a zero here is a boundary, not a broken instrument.
    @Test
    void aStoredPrefix_doesNotSwallowALongerSiblingSegment() {
        var pricing = new HttpRouteDefinition("GET", "/api/pricing", COORD, "handle", SecurityPolicy.publicRoute());

        assertThat("/api/pricing-admin/report/".startsWith(pricing.pathPrefix()))
                .as("a sibling segment must not match the prefix of another route")
                .isFalse();
        assertThat("/api/pricing/report/".startsWith(pricing.pathPrefix()))
                .as("positive control: a path genuinely under the prefix must match")
                .isTrue();
    }
}
