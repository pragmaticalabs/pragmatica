// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// What counts as a declared limit decides whether the generator emits `withUpload` for a route that is not an upload
/// route: either key alone is enough, neither is not.
class RouteLimitsTest {
    @Test
    void declared_isTrueForEitherKeyAlone_andFalseForNeither() {
        assertThat(RouteLimits.NONE.declared()).isFalse();
        assertThat(RouteLimits.routeLimits(1, 0).declared()).as("max_size alone").isTrue();
        assertThat(RouteLimits.routeLimits(0, 1).declared()).as("max_duration alone").isTrue();
        assertThat(RouteLimits.routeLimits(1, 1).declared()).isTrue();
    }

    @Test
    void declared_isTrueForEachPartLimitAlone() {
        assertThat(RouteLimits.routeLimits(0, 0, 1, 0, 0, 0).declared()).as("max_files alone").isTrue();
        assertThat(RouteLimits.routeLimits(0, 0, 0, 1, 0, 0).declared()).as("max_file_size alone").isTrue();
        assertThat(RouteLimits.routeLimits(0, 0, 0, 0, 1, 0).declared()).as("max_fields alone").isTrue();
        assertThat(RouteLimits.routeLimits(0, 0, 0, 0, 0, 1).declared()).as("max_field_size alone").isTrue();
    }

    @Test
    void hasPartLimits_isTrueForEachPartLimitAlone_andFalseForTheBodyLimitsAlone() {
        assertThat(RouteLimits.NONE.hasPartLimits()).isFalse();
        assertThat(RouteLimits.routeLimits(5, 5).hasPartLimits()).as("max_size and max_duration are not part limits").isFalse();
        assertThat(RouteLimits.routeLimits(0, 0, 1, 0, 0, 0).hasPartLimits()).isTrue();
        assertThat(RouteLimits.routeLimits(0, 0, 0, 1, 0, 0).hasPartLimits()).isTrue();
        assertThat(RouteLimits.routeLimits(0, 0, 0, 0, 1, 0).hasPartLimits()).isTrue();
        assertThat(RouteLimits.routeLimits(0, 0, 0, 0, 0, 1).hasPartLimits()).isTrue();
    }
}
