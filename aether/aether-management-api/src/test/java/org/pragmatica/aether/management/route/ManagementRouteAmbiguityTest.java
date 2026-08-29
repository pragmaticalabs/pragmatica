// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// Verifies the [ManagementRoute] enum loads cleanly (no ambiguous routes)
/// and that the static-init validation in [RouteMatcher] would catch synthetic duplicates.
class ManagementRouteAmbiguityTest {

    @Test
    void enumLoadsWithoutInitializerError() {
        // Forcing class load via values(); if any pair of routes share the same
        // (method, prefix, paramCount), RouteMatcher.shared() throws ExceptionInInitializerError.
        var routes = ManagementRoute.values();
        assertThat(routes).isNotEmpty();
        assertThat(RouteMatcher.shared()).isNotNull();
    }

    @Test
    void allSameShapedRoutesAreUnambiguous() {
        // Construction-based: walks every pair sharing RouteMatcher's own bucket key (method, token
        // count) and asks RouteMatcher.ambiguous() itself, rather than a hand-rolled proxy. A prefix()
        // + paramCount() signature (the prior version of this test) is strictly weaker than the real
        // domination check: it flags legitimate specificity pairs and same-bucket routes that merely
        // share a leading literal run as "duplicates" even when their trailing literals fully
        // disambiguate them (e.g. STREAM_GET's ".../info" vs STREAM_CONSUMERS's ".../consumers" both
        // key to "GET /api/v1/streams #3" but are not ambiguous — RouteMatcher.build() accepts them).
        // This test instead re-derives exactly what RouteMatcher.build() checks, so it stays correct
        // as routes are added/reshaped without needing hand-verification against the real algorithm.
        var routes = ManagementRoute.values();
        for (var i = 0; i < routes.length; i++) {
            for (var j = i + 1; j < routes.length; j++) {
                var a = routes[i];
                var b = routes[j];
                if (a.method() != b.method() || a.tokens().size() != b.tokens().size()) {
                    continue;
                }
                assertThat(RouteMatcher.ambiguous(a.tokens(), b.tokens()))
                        .as("Routes %s and %s share (method=%s, tokenCount=%d) and are ambiguous per RouteMatcher's domination check",
                            a.name(), b.name(), a.method(), a.tokens().size())
                        .isFalse();
            }
        }
    }

    @Test
    void allRouteParamNamesAreUniqueWithinRoute() {
        for (var r : ManagementRoute.values()) {
            assertThat(r.paramNames())
                    .as("Route %s has duplicate parameter names", r.name())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void allRoutePrefixesStartWithSlash() {
        for (var r : ManagementRoute.values()) {
            assertThat(r.prefix())
                    .as("Route %s prefix must start with '/'", r.name())
                    .startsWith("/");
        }
    }

    @Test
    void allRouteTargetsAreSet() {
        for (var r : ManagementRoute.values()) {
            assertThat(r.target())
                    .as("Route %s must have non-null target", r.name())
                    .isNotNull();
        }
    }

    @Test
    void syntheticDuplicateIsRejectedByMatcher() {
        var single = ManagementRoute.values();
        var doubled = new ManagementRoute[single.length * 2];
        System.arraycopy(single, 0, doubled, 0, single.length);
        System.arraycopy(single, 0, doubled, single.length, single.length);
        var result = RouteMatcher.build(doubled);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void interleavedTokens_rejectsBlankSpacer() {
        // Commit 2b will hand-author 16 interleaved token lists; a typo'd Spacer("") would
        // otherwise silently collapse two path segments into one at render/match time. Scoped to
        // the interleaved constructor only -- see the guard's own doc comment for why the ~150
        // existing tail-only routes don't need this (and aren't audited for it).
        var blankMiddle = List.<PathToken>of(PathToken.spacer("streams"), PathToken.spacer(""), PathToken.param("id"));

        assertThatThrownBy(() -> ManagementRoute.interleavedTokens(blankMiddle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Blank Spacer");
    }
}
