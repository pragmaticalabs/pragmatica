// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;


class SourceProfileTest {

    private static SourceProfile cloudSource(Option<String> zone, List<String> zones) {
        return SourceProfile.sourceProfile(sourceNameOrDefault("hetzner-eu"),
                                           SourceType.CLOUD,
                                           some(CloudProviderName.HETZNER),
                                           empty(),
                                           some("nbg1"),
                                           zone,
                                           zones,
                                           empty(),
                                           empty(),
                                           empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           empty(),
                                           Map.of(),
                                           Map.of(),
                                           List.of(),
                                           Option.empty());
    }

    @Test
    void effectiveZones_returnsZonesList_whenZonesPresent() {
        var source = cloudSource(some("ignored"), List.of("fsn1", "nbg1", "hel1"));

        assertThat(source.effectiveZones())
                .as("explicit zones array supersedes the single zone")
                .containsExactly("fsn1", "nbg1", "hel1");
    }

    @Test
    void effectiveZones_returnsSingleZone_whenOnlyZonePresent() {
        var source = cloudSource(some("nbg1"), List.of());

        assertThat(source.effectiveZones())
                .as("single zone wraps into a one-element list (backward-compatible)")
                .containsExactly("nbg1");
    }

    @Test
    void effectiveZones_returnsEmpty_whenNeitherZoneNorZones() {
        var source = cloudSource(empty(), List.of());

        assertThat(source.effectiveZones())
                .as("no zone configuration → empty list → caller uses provider default region, single attempt")
                .isEmpty();
    }
}
