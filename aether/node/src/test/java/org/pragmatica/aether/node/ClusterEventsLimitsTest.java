// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #1549: `CLUSTER_EVENTS_MAX_*` are checked at boot — a value the stream engine would refuse at creation
/// (zero, negative, unparseable, a count past the ring's capacity) refuses the boot, naming the variable.
class ClusterEventsLimitsTest {

    @Test
    void clusterEventsLimits_unsetOrBlank_usesTheDefaults() {
        var limits = limitsFrom(Map.of(ClusterEventsLimits.MAX_BYTES_VARIABLE, "  ")).unwrap();

        assertThat(limits).isEqualTo(new ClusterEventsLimits(ClusterEventsLimits.DEFAULT_MAX_COUNT,
                                                             ClusterEventsLimits.DEFAULT_MAX_BYTES,
                                                             ClusterEventsLimits.DEFAULT_MAX_AGE_MS,
                                                             ClusterEventsLimits.DEFAULT_MAX_EVENT_SIZE_BYTES));
    }

    @Test
    void clusterEventsLimits_bindsEveryDeclaredValue() {
        var limits = limitsFrom(Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, "500",
                                       ClusterEventsLimits.MAX_BYTES_VARIABLE, " 1048576 ",
                                       ClusterEventsLimits.MAX_AGE_MS_VARIABLE, "60000",
                                       ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, "1")).unwrap();

        assertThat(limits.retention()).isEqualTo(RetentionPolicy.retentionPolicy(500, 1_048_576, 60_000, RetentionMode.ANY));
        assertThat(limits.maxEventSizeBytes()).isEqualTo(1L);
    }

    @Test
    void clusterEventsLimits_zeroCount_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_COUNT_VARIABLE, "0");
    }

    @Test
    void clusterEventsLimits_zeroBytes_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_BYTES_VARIABLE, "0");
    }

    @Test
    void clusterEventsLimits_negativeAge_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_AGE_MS_VARIABLE, "-1");
    }

    @Test
    void clusterEventsLimits_zeroEventSize_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, "0");
    }

    @Test
    void clusterEventsLimits_unparseableValue_refusesTheBoot_insteadOfDefaulting() {
        assertRefused(ClusterEventsLimits.MAX_BYTES_VARIABLE, "16MB");
    }

    @Test
    void clusterEventsLimits_valueBeyondTheLongRange_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_AGE_MS_VARIABLE, "99999999999999999999");
    }

    @Test
    void clusterEventsLimits_countPastTheIndexableCapacity_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_COUNT_VARIABLE, String.valueOf(OffHeapRingBuffer.MAX_CAPACITY + 1));
    }

    /// #1571 — this used to assert that a count AT the ring's indexable capacity was accepted. It was
    /// correct about the code and wrong about the requirement: the aggregator reads at most
    /// `CLUSTER_EVENTS_MAX_RETAINED` events, so any count above that window retained events no read could
    /// return. The ceiling for the count is now the read window, which is far below the ring capacity.
    @Test
    void clusterEventsLimits_countAtTheReadWindow_isAccepted() {
        var limits = limitsFrom(Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, String.valueOf(AetherNode.CLUSTER_EVENTS_MAX_RETAINED))).unwrap();

        assertThat(limits.maxCount()).isEqualTo(AetherNode.CLUSTER_EVENTS_MAX_RETAINED);
    }

    /// #1571 — a count one past the read window refuses the boot, and the refusal names the variable, its
    /// value, and the window it exceeds.
    @Test
    void clusterEventsLimits_countPastTheReadWindow_refusesTheBoot_namingBoth() {
        var value = String.valueOf(AetherNode.CLUSTER_EVENTS_MAX_RETAINED + 1);

        limitsFrom(Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, value))
                .onSuccess(limits -> fail("expected " + ClusterEventsLimits.MAX_COUNT_VARIABLE + "='" + value + "' to be refused, bound " + limits))
                .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterEventsLimits.InvalidLimit.class))
                .onFailure(cause -> assertThat(cause.message()).contains(ClusterEventsLimits.MAX_COUNT_VARIABLE + "='" + value + "'",
                                                                         "CLUSTER_EVENTS_MAX_RETAINED=" + AetherNode.CLUSTER_EVENTS_MAX_RETAINED));
    }

    @Test
    void clusterEventsLimits_everyInvalidVariable_isNamed() {
        limitsFrom(Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, "0",
                          ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, "x"))
                .onSuccess(limits -> fail("expected a refusal, bound " + limits))
                .onFailure(cause -> assertThat(cause.message()).contains(ClusterEventsLimits.MAX_COUNT_VARIABLE + "='0'",
                                                                         ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE + "='x'"));
    }

    private static void assertRefused(String variable, String value) {
        limitsFrom(Map.of(variable, value))
                .onSuccess(limits -> fail("expected " + variable + "='" + value + "' to be refused, bound " + limits))
                .onFailure(cause -> assertThat(cause.message()).contains(variable + "='" + value + "' is not a whole number from 1 to"));
    }

    private static Result<ClusterEventsLimits> limitsFrom(Map<String, String> environment) {
        return ClusterEventsLimits.clusterEventsLimits(variable -> Option.option(environment.get(variable)));
    }
}
