// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.blueprint.StreamEventLimits;
import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #1549: `CLUSTER_EVENTS_MAX_*` are checked at boot — a value the stream engine would refuse at creation
/// (zero, negative, unparseable, a count past the ring's capacity) refuses the boot, naming the variable.
class ClusterEventsLimitsTest {
    /// #1564 N2 (v1680 W8): the cluster-events config built at node construction carries the committed
    /// `[replication.cluster_events]` confirmation factor and RF = the desired core count, the same factors the
    /// registrar commits — not a hardcoded CF 1.
    @Test
    void streamConfig_committedClusterEventsFactors_areTheConfigsFactors() {
        var committed = org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue.clusterConfigValue("""
                                                                                                             [replication.cluster_events]
                                                                                                             confirmation_factor = 2
                                                                                                             """,
                                                                                                             "c1",
                                                                                                             "1.0.0",
                                                                                                             java.util.List.of(org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry.topologyEntry("local",
                                                                                                                                                                                                   org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry.CORE_ROLE,
                                                                                                                                                                                                   5)),
                                                                                                             5,
                                                                                                             5,
                                                                                                             "embedded",
                                                                                                             1L);
        var config = defaults().streamConfig("system:cluster-events:1.0.0", Option.some(committed));

        assertThat(config.replicationFactor()).isEqualTo(5);
        assertThat(config.confirmationFactor()).isEqualTo(2);
        assertThat(config.name()).isEqualTo("system:cluster-events:1.0.0");
        assertThat(config.partitions()).isEqualTo(1);
    }

    @Test
    void streamConfig_noCommittedConfig_isTheBuiltInClusterEventsFactors() {
        var config = defaults().streamConfig("system:cluster-events:1.0.0", Option.none());

        assertThat(config.replicationFactor()).isEqualTo(3);
        assertThat(config.confirmationFactor()).isEqualTo(1);
    }

    private static ClusterEventsLimits defaults() {
        return ClusterEventsLimits.clusterEventsLimits(_ -> Option.none()).unwrap();
    }


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

    /// #1937: this stream is built directly, so the one-frame ceiling every declared stream is held to is enforced here, at boot.
    /// Red when the maximum is `Long.MAX_VALUE`: a value above the ceiling is accepted and the stream's events cannot replicate.
    @Test
    void clusterEventsLimits_eventSizeAboveTheOneFrameCeiling_refusesTheBoot() {
        assertRefused(ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, String.valueOf(StreamEventLimits.MAX_EVENT_SIZE_BYTES + 1L));
        assertRefused(ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, String.valueOf(Long.MAX_VALUE));
    }

    /// The byte bound is raised with it (#3390 F3): at the 16 MiB default byte bound a ceiling-sized event no longer fits the
    /// ring, which the boot now refuses ([#clusterEventsLimits_maxBytesBelowMaxEventSize_refusesTheBoot_namingBoth]). The byte
    /// bound EQUAL to the event size is the accepted edge.
    @Test
    void clusterEventsLimits_eventSizeAtTheOneFrameCeiling_isAccepted() {
        var ceiling = String.valueOf(StreamEventLimits.MAX_EVENT_SIZE_BYTES);
        var limits = limitsFrom(Map.of(ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, ceiling,
                                       ClusterEventsLimits.MAX_BYTES_VARIABLE, ceiling)).unwrap();

        assertThat(limits.maxEventSizeBytes()).isEqualTo(StreamEventLimits.MAX_EVENT_SIZE_BYTES);
        assertThat(limits.maxBytes()).isEqualTo(StreamEventLimits.MAX_EVENT_SIZE_BYTES);
    }

    /// #3390 F3 (ruling know e8888553b4): every stream ring holds at least one `max_event_size` event, and this stream's ring
    /// is sized from CLUSTER_EVENTS_MAX_BYTES, so a byte bound below the event size refuses the boot, naming both variables.
    /// Mutants: drop the cross-check (accepted); compare with `<=` (the equal edge above is refused).
    @Test
    void clusterEventsLimits_maxBytesBelowMaxEventSize_refusesTheBoot_namingBoth() {
        limitsFrom(Map.of(ClusterEventsLimits.MAX_BYTES_VARIABLE, "65536",
                          ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, "131072"))
                .onSuccess(limits -> fail("expected a refusal, bound " + limits))
                .onFailure(cause -> assertThat(cause.message()).contains(ClusterEventsLimits.MAX_BYTES_VARIABLE + "='65536' is below",
                                                                         ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE + "=131072"));
    }

    @Test
    void clusterEventsLimits_defaultMaxBytesBelowADeclaredEventSize_refusesTheBoot() {
        var eventSize = ClusterEventsLimits.DEFAULT_MAX_BYTES + 1;

        limitsFrom(Map.of(ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, String.valueOf(eventSize)))
                .onSuccess(limits -> fail("expected a refusal, bound " + limits))
                .onFailure(cause -> assertThat(cause.message()).contains(ClusterEventsLimits.MAX_BYTES_VARIABLE + "='"
                                                                         + ClusterEventsLimits.DEFAULT_MAX_BYTES + "' is below"));
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

    /// T-267 (cluster-events arm): the count bound follows the configured event size. At the 64 KiB default a count of
    /// `MAX_CAPACITY` passes the ring bound (the read window refuses it, so the refusal names the window, not the ring bound);
    /// with a 1 MiB event size it is refused naming `maxCapacity(1 MiB)`. Mutant: read the default event size in the bound.
    @Test
    void clusterEventsLimits_countAtTheConstantCapacity_isRefusedNamingTheBoundOfTheConfiguredEventSize() {
        var oneMiB = 1024L * 1024;
        var bound = RingGeometry.maxCapacity(RingGeometry.firstSegmentBytes(ClusterEventsLimits.DEFAULT_MAX_BYTES, oneMiB));

        limitsFrom(Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, String.valueOf(OffHeapRingBuffer.MAX_CAPACITY),
                          ClusterEventsLimits.MAX_EVENT_SIZE_BYTES_VARIABLE, String.valueOf(oneMiB)))
                .onSuccess(limits -> fail("expected a refusal, bound " + limits))
                .onFailure(cause -> assertThat(cause.message()).contains(ClusterEventsLimits.MAX_COUNT_VARIABLE + "='" + OffHeapRingBuffer.MAX_CAPACITY
                                                                         + "' is not a whole number from 1 to " + bound));
        assertThat(bound).isEqualTo(OffHeapRingBuffer.MAX_CAPACITY - 32_768L);
    }

    @Test
    void clusterEventsLimits_countPastTheConstantCapacity_atTheDefaultEventSize_isRefused_boundIsTheConstant() {
        assertRefused(ClusterEventsLimits.MAX_COUNT_VARIABLE, String.valueOf(OffHeapRingBuffer.MAX_CAPACITY + 1));
        assertThat(RingGeometry.maxCapacity(RingGeometry.firstSegmentBytes(ClusterEventsLimits.DEFAULT_MAX_BYTES,
                                                                           ClusterEventsLimits.DEFAULT_MAX_EVENT_SIZE_BYTES)))
                .isEqualTo(OffHeapRingBuffer.MAX_CAPACITY);
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
    /// value, and the window it exceeds. The message is asserted, not the cause type: `Result.all` reports
    /// every limit's refusal inside one composite cause, as the other refusals in this class rely on.
    @Test
    void clusterEventsLimits_countPastTheReadWindow_refusesTheBoot_namingBoth() {
        var value = String.valueOf(AetherNode.CLUSTER_EVENTS_MAX_RETAINED + 1);

        limitsFrom(Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, value))
                .onSuccess(limits -> fail("expected " + ClusterEventsLimits.MAX_COUNT_VARIABLE + "='" + value + "' to be refused, bound " + limits))
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
