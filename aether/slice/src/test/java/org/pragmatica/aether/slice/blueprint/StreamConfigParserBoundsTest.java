// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.stream.DeclarationBounds.Arm;
import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.StreamConfigParser.parseResources;

/// The hot-window and replay-window-share floors of a stream declaration, refused at parse against the seal and ring
/// constants (#3333, S2a1-6b; stream-storage-design 2.2). Test ids (T-24, ...) are the plan's. Refusals only: no clamp, no WARN (6c).
class StreamConfigParserBoundsTest {
    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;
    private static final long GIB = 1024L * MIB;

    private static String section(String body) {
        return "[streams.orders]\nversion = \"1.0.0\"\n" + body;
    }

    private static StreamConfig parsed(String body) {
        return ((StreamResource.Owned) parseResources(section(body), ReplicationFactors.BUILT_IN).unwrap().get("orders")).config();
    }

    private static Cause refusal(String body) {
        return parseResources(section(body), ReplicationFactors.BUILT_IN).fold(cause -> cause,
                                                                               _ -> fail("expected a refusal for: " + body));
    }

    private static void assertAccepted(String body) {
        assertThat(parseResources(section(body), ReplicationFactors.BUILT_IN).isSuccess())
                .as("accepted: " + body)
                .isTrue();
    }

    private static StreamDeclaration declaration(String body) {
        return parsed(body).declaration().or(() -> fail("a parsed stream must carry its resolved declaration"));
    }

    /// T-24: `hot.max_bytes` below two seal runs of bytes (2 MiB at the default 1 MiB run) is refused, naming the key and 2 MiB.
    @Test
    void hotMaxBytes_belowTwoSealRuns_isRefused_atTheFloorAccepted() {
        assertThat(refusal("[streams.orders.hot]\nmax_bytes = \"" + (2 * MIB - 1) + "\"\n"))
                .isEqualTo(new StreamDeclarationError.HotWindowBelowSealRuns("orders", "hot.max_bytes", 2 * MIB - 1, 2 * MIB));
        assertAccepted("[streams.orders.hot]\nmax_bytes = \"" + 2 * MIB + "\"\n");
    }

    /// T-148: the count dimension, `2 x seal_run_events` = 8 192 at the default 4 096.
    @Test
    void hotMaxCount_belowTwoSealRuns_isRefused_atTheFloorAccepted() {
        assertThat(refusal("[streams.orders.hot]\nmax_count = 8191\n"))
                .isEqualTo(new StreamDeclarationError.HotWindowBelowSealRuns("orders", "hot.max_count", 8191, 8192));
        assertAccepted("[streams.orders.hot]\nmax_count = 8192\n");
    }

    /// T-25: P = 1024, `max_bytes` 1 GiB leaves 1 MiB per partition; refused naming 8 GiB (2 x 4 MiB x 1024), never clamped up.
    @Test
    void byteArm_shareBelowTwoMergedSegments_isRefused_namingTheSmallestMaxBytes() {
        assertThat(refusal("partitions = 1024\n[streams.orders.retention]\nmax_bytes = \"1GB\"\n"))
                .isEqualTo(new StreamDeclarationError.RetentionShareTooSmall("orders",
                                                                             Arm.BYTE,
                                                                             "retention.max_bytes",
                                                                             MIB,
                                                                             8 * MIB,
                                                                             8 * GIB));
        assertAccepted("partitions = 1024\n[streams.orders.retention]\nmax_bytes = \"8GB\"\n");
    }

    /// T-26: a stream declaring only `partitions = 1024` is accepted at the scaled default bound.
    @Test
    void onlyPartitions_isAcceptedAtTheScaledDefault() {
        var retention = declaration("partitions = 1024\n").retention().or(() -> fail("retention resolved"));

        assertThat(retention.maxBytes().or(0L)).isEqualTo(8 * GIB);
    }

    /// T-149: count arm, P = 16: `max_count / 16 < 8 192` is refused naming `max_count` and 131 072.
    @Test
    void countArm_belowTwoSealRunsOfRecords_isRefused_namingTheSmallestMaxCount() {
        var head = "partitions = 16\n[streams.orders.retention]\nmax_bytes = \"1024GB\"\nmax_count = ";

        assertThat(refusal(head + "131056\n"))
                .isEqualTo(new StreamDeclarationError.RetentionShareTooSmall("orders",
                                                                             Arm.COUNT,
                                                                             "retention.max_count",
                                                                             8191,
                                                                             8192,
                                                                             131_072));
        assertAccepted(head + "131072\n");
    }

    /// T-149 (last clause): an age bound alone is not parse-computable and passes.
    @Test
    void ageOnlyRetention_passesTheShareArms() {
        assertAccepted("partitions = 1024\n[streams.orders.retention]\nmax_age = \"1h\"\n");
    }

    /// Under `all` an age bound makes the share not computable, so the byte arm does not fire (the count arm still would).
    @Test
    void modeAllWithAgeBound_skipsTheByteArm() {
        assertAccepted("partitions = 1024\n[streams.orders.retention]\nmax_age = \"1h\"\nmax_bytes = \"1GB\"\nmode = \"all\"\n");
        assertThat(refusal("partitions = 1024\n[streams.orders.retention]\nmax_age = \"1h\"\nmax_bytes = \"1GB\"\n"))
                .isInstanceOf(StreamDeclarationError.RetentionShareTooSmall.class);
    }

    /// T-217: the byte arm takes `max_count` through `max_event_size`: 8 192 x 512 B = 4 MiB per partition is refused naming
    /// `max_count`; at a 1 MiB event the same count is accepted.
    @Test
    void byteArm_takesTheShareOverMaxCountThroughMaxEventSize() {
        var retention = "partitions = 1024\n[streams.orders.retention]\nmax_bytes = \"1024GB\"\nmax_count = " + 1024 * 8192 + "\n";

        assertThat(refusal("max_event_size = \"512\"\n" + retention))
                .isEqualTo(new StreamDeclarationError.RetentionShareTooSmall("orders",
                                                                             Arm.BYTE,
                                                                             "retention.max_count",
                                                                             4 * MIB,
                                                                             8 * MIB,
                                                                             1024L * 16384));
        assertAccepted("max_event_size = \"1MB\"\n" + retention);
    }

    /// T-220: `hot.max_bytes < max_event_size` is refused after the seal-run floor, so 1 MiB reports the earlier check.
    @Test
    void hotMaxBytes_belowMaxEventSize_isRefused_afterTheSealRunCheck() {
        var event = "max_event_size = \"8MB\"\n[streams.orders.hot]\nmax_bytes = ";

        assertThat(refusal(event + "\"4MB\"\n")).isEqualTo(new StreamDeclarationError.HotWindowBelowMaxEvent("orders", 4 * MIB, 8 * MIB));
        assertAccepted(event + "\"8MB\"\n");
        assertThat(refusal(event + "\"1MB\"\n"))
                .isEqualTo(new StreamDeclarationError.HotWindowBelowSealRuns("orders", "hot.max_bytes", MIB, 2 * MIB));
        assertAccepted("max_event_size = \"16MB\"\n");
    }

    /// T-248: event arm, P = 1, 16 MiB events: a share of 8 MiB passes the byte arm and is refused naming `max_event_size`.
    @Test
    void eventArm_shareBelowMaxEventSize_isRefused() {
        var head = "partitions = 1\nmax_event_size = \"16MB\"\n[streams.orders.hot]\nmax_bytes = \"16MB\"\n[streams.orders.retention]\nmax_bytes = ";

        assertThat(refusal(head + "\"8MB\"\n"))
                .isEqualTo(new StreamDeclarationError.RetentionShareTooSmall("orders",
                                                                             Arm.EVENT,
                                                                             "retention.max_bytes",
                                                                             8 * MIB,
                                                                             16 * MIB,
                                                                             16 * MIB));
        assertThat(refusal(head + "\"8MB\"\n").message()).contains("max_event_size");
        assertAccepted(head + "\"16MB\"\n");
    }

    /// T-251: `hot.max_count` above `maxCapacity` of the first region (1 MiB at a 1 MiB event) is refused; a `[retention]`
    /// `max_count` far above it is not what is checked.
    @Test
    void hotMaxCount_aboveTheRingIndexCapacity_isRefused() {
        var capacity = RingGeometry.maxCapacity(RingGeometry.firstSegmentBytes(4 * MIB, MIB));
        var event = "max_event_size = \"1MB\"\n";

        assertThat(refusal(event + "[streams.orders.hot]\nmax_count = " + (capacity + 1) + "\n"))
                .isEqualTo(new StreamDeclarationError.RetentionCountUnindexable("orders", capacity + 1, capacity));
        assertAccepted(event + "[streams.orders.hot]\nmax_count = " + capacity + "\n");
        assertAccepted(event + "[streams.orders.retention]\nmax_bytes = \"1GB\"\nmax_count = " + (capacity + 1) + "\n");
    }

    /// A `max_count` of `Long.MAX_VALUE` never throws: the share product saturates.
    @Test
    void maxCountOfLongMaxValue_saturatesInsteadOfOverflowing() {
        var retention = "[streams.orders.retention]\nmax_bytes = \"1GB\"\nmax_count = " + Long.MAX_VALUE + "\n";

        assertAccepted("partitions = 1\n" + retention);
        assertThat(refusal("partitions = 1024\n" + retention))
                .isInstanceOf(StreamDeclarationError.RetentionShareTooSmall.class);
    }

    /// First failure wins: a well-formed `until_consumed` block with a low hot window reports the floor, and with a sound hot
    /// window reaches the T-146 gate.
    @Test
    void floors_precedeTheNotYetAvailableGate() {
        var untilConsumed = "[streams.orders.retention]\nmax_bytes = \"1GB\"\n[streams.orders.truncation]\npolicy = \"until_consumed\"\n";

        assertThat(refusal(untilConsumed + "[streams.orders.hot]\nmax_bytes = \"1MB\"\n"))
                .isInstanceOf(StreamDeclarationError.HotWindowBelowSealRuns.class);
        assertThat(refusal(untilConsumed)).isInstanceOf(StreamDeclarationError.NotYetAvailable.class);
    }
}
