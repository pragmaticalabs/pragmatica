// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.Election;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionStateValue.PartitionState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// #3303: the partition-state row survives the real node codec (the bytes a committed Put is replicated, snapshotted and
/// restored as) with every component set, and its dormant last component `activationGeneration` is part of the value's wire
/// shape from the first line, so the step that starts writing it needs no wire bump.
class StreamPartitionStateValueCodecRoundTripTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final Path BASELINE = Path.of("src", "test", "resources", "wire-assignment-baseline.txt");
    private static final long NEAR_MAX = Long.MAX_VALUE - 1;

    @Test
    void roundTrip_everyComponentSetWithZeroActivationGeneration_decodesEqual() {
        var row = fullRow(0L);

        assertThat(row.activationGeneration()).as("fixture premise").isEqualTo(0L);

        var decoded = (StreamPartitionStateValue) CODEC.decode(CODEC.encode(row));

        assertThat(decoded.activationGeneration()).as("activationGeneration after the codec").isEqualTo(0L);
        assertThat(decoded).isEqualTo(row);
    }

    @Test
    void roundTrip_everyComponentSetWithNearMaxActivationGeneration_decodesEqual() {
        var row = fullRow(NEAR_MAX);

        assertThat(row.activationGeneration()).as("fixture premise: the factory keeps the component")
                                              .isEqualTo(Long.MAX_VALUE - 1);

        var decoded = (StreamPartitionStateValue) CODEC.decode(CODEC.encode(row));

        assertThat(decoded.activationGeneration()).as("activationGeneration after the codec").isEqualTo(Long.MAX_VALUE - 1);
        assertThat(decoded).isEqualTo(row);
    }

    @Test
    void recordComponents_lastComponent_isLongActivationGeneration() {
        var components = StreamPartitionStateValue.class.getRecordComponents();
        var last = components[components.length - 1];

        assertThat(components).as("component count").hasSize(10);
        assertThat(last.getName()).as("last component name").isEqualTo("activationGeneration");
        assertThat(last.getType()).as("last component type").isEqualTo(long.class);
    }

    @Test
    void wireShapeLine_ofTheValue_endsWithActivationGeneration() throws IOException {
        var line = valueShapeLines(WireAssignmentTripwireTest.currentAssignment().stream());

        assertThat(line).as("the value's SHAPE line in the current assignment").hasSize(1);
        assertThat(line.getFirst()).endsWith(",activationGeneration:long)");
        assertThat(valueShapeLines(Files.readAllLines(BASELINE, StandardCharsets.UTF_8).stream()))
            .as("the value's SHAPE line in the recorded baseline")
            .containsExactlyElementsOf(line);
    }

    /// The enum tripwire (`WireAssignmentTripwireTest.everyRegisteredEnum_endsWithTheUnknownSentinel`) walks the same codec set as
    /// the assignment, so the enum's ENUM line in it proves the tripwire runs over the enum; a constant after `UNKNOWN` does
    /// not get that far (`CodecProcessor.validateEnumSentinel` refuses the build).
    @Test
    void partitionState_isRegisteredAndEndsWithUnknown() {
        var constants = PartitionState.values();

        assertThat(constants[constants.length - 1]).isEqualTo(PartitionState.UNKNOWN);
        assertThat(WireAssignmentTripwireTest.currentAssignment())
            .as("the enum is in the codec set the sentinel tripwire walks")
            .contains("ENUM " + PartitionState.class.getName() + " OPEN=0,CLOSED=1,RELEASED=2,UNKNOWN=3");
    }

    private static List<String> valueShapeLines(Stream<String> lines) {
        var prefix = "SHAPE " + StreamPartitionStateValue.class.getName() + " ";

        return lines.filter(line -> line.startsWith(prefix))
                    .toList();
    }

    private static StreamPartitionStateValue fullRow(long activationGeneration) {
        var elected = NodeId.nodeId("node-b").unwrap();
        var election = Election.election(Epoch.epoch(4L, 9L, 2L),
                                         elected,
                                         true,
                                         List.of(NodeId.nodeId("node-a").unwrap(), elected));

        return StreamPartitionStateValue.streamPartitionStateValue(3L,
                                                                   5L,
                                                                   1200L,
                                                                   PartitionState.CLOSED,
                                                                   40L,
                                                                   some(1999L),
                                                                   some(election),
                                                                   List.of(1),
                                                                   List.of(4, 5),
                                                                   activationGeneration);
    }
}
