/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;


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
    void allRoutesHaveUniqueSignature() {
        var seen = new HashMap<String, ManagementRoute>();
        for (var r : ManagementRoute.values()) {
            var key = r.method() + " " + r.prefix() + " #" + r.paramCount();
            var prev = seen.put(key, r);
            assertThat(prev)
                    .as("Duplicate signature %s between %s and %s", key, prev, r)
                    .isNull();
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
}
