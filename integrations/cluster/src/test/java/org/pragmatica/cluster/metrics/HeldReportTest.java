// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The defaults and the cap the compact constructor imposes on a report: they bound what a pong carries, so they are pinned
/// by literals (not by the constant) and a change of either is a wire-visible decision.
class HeldReportTest {
    private static final int WIRE_SAMPLE_CAP = 16;

    @Test
    void sampleKeys_areCappedAtSixteen() {
        var keys = IntStream.range(0, 40)
                            .mapToObj(i -> "k" + i)
                            .toList();

        var report = HeldReport.heldReport("e@1", 40, 0, "d", 1L, keys, HeldEnd.NONE);

        assertThat(HeldReport.MAX_SAMPLE_KEYS).isEqualTo(WIRE_SAMPLE_CAP);
        assertThat(report.sampleKeys()).hasSize(WIRE_SAMPLE_CAP)
                                       .isEqualTo(keys.subList(0, WIRE_SAMPLE_CAP));
    }

    @Test
    void sampleKeys_atTheCapAreKeptWhole() {
        var keys = IntStream.range(0, WIRE_SAMPLE_CAP)
                            .mapToObj(i -> "k" + i)
                            .toList();

        assertThat(HeldReport.heldReport("e@1", 16, 0, "d", 1L, keys, HeldEnd.NONE)
                             .sampleKeys()).isEqualTo(keys);
    }

    @Test
    void nullComponents_takeTheirDefaults() {
        var report = HeldReport.heldReport(null, -1, 0, null, 0L, null, null);

        assertThat(report.episode()).isEmpty();
        assertThat(report.owedDigest()).isEmpty();
        assertThat(report.sampleKeys()).isEqualTo(List.of());
        assertThat(report.ended()).isSameAs(HeldEnd.NONE);
    }

    @Test
    void ended_isKeptWhenGiven() {
        assertThat(HeldReport.heldReport("e@1", 0, 0, "d", 1L, List.of(), HeldEnd.LOSS_ACCEPTED)
                             .ended()).isSameAs(HeldEnd.LOSS_ACCEPTED);
        assertThat(HeldReport.heldReport("e@1", 0, 0, "d", 1L, List.of(), HeldEnd.DELIVERED)
                             .ended()).isSameAs(HeldEnd.DELIVERED);
    }
}
