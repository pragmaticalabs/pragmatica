// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue.AssignmentToken;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SystemTags;
import org.pragmatica.serialization.UnknownTypeTagException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3334 S1a-1a, the flag day: `ConsumerAssignmentValue`, its `AssignmentToken` and `StreamCursorCheckpointValue` moved from
/// 1650, 1651 and 1648 to 2928, 2929 and 2930 when the token gained `member`. A node of an older build must fail a new frame on
/// an unknown tag, and this build must fail an old frame the same way, instead of either one misparsing the other's shape.
///
/// The last test pins the SHIM: the 2-arg `assignmentToken` and 4-arg `consumerAssignmentValue` factories stay, passing
/// `none()`, until S1a-4d. It is an ENABLED tripwire on current behaviour: when S1a-4d removes the shim, it fails and says so.
/// Callers of the old arities are pinned by [OldArityCallerTripwireTest], from compiled code.
class AssignmentFlagDayWireTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final String VALUES = "org.pragmatica.aether.slice.kvstore.AetherValue.";
    private static final String ASSIGNMENT = VALUES + "ConsumerAssignmentValue";
    private static final String TOKEN = ASSIGNMENT + ".AssignmentToken";
    private static final String CHECKPOINT = VALUES + "StreamCursorCheckpointValue";
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(0L, 1L, 1L);

    @Test
    void liveNames_areTaggedAt2928_2929_2930() {
        assertThat(SystemTags.tagFor(ASSIGNMENT)).isEqualTo(2928);
        assertThat(SystemTags.tagFor(TOKEN)).isEqualTo(2929);
        assertThat(SystemTags.tagFor(CHECKPOINT)).isEqualTo(2930);
    }

    @Test
    void liveValues_areWrittenUnderTheirNewTags() {
        var token = AssignmentToken.assignmentToken(SELF, EPOCH, some("m-a"));
        var assignment = ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, 1L, HlcTimestamp.ZERO, some("m-a"));
        var checkpoint = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, token);

        assertThat(CODEC.encode(assignment)).startsWith(vlq(2928));
        assertThat(CODEC.encode(token)).startsWith(vlq(2929));
        assertThat(CODEC.encode(checkpoint)).startsWith(vlq(2930));
    }

    @Test
    void framesUnderTheRetiredTags_areRefusedAsUnknown_namingTheTag() {
        for (var retired : new int[] {1648, 1650, 1651}) {
            var oldFrame = concat(vlq(retired), new byte[] {0x01, 0x02, 0x03, 0x04});

            assertThatThrownBy(() -> CODEC.decode(oldFrame)).isInstanceOf(UnknownTypeTagException.class)
                                                            .hasMessageContaining(Integer.toString(retired))
                                                            .extracting(e -> ((UnknownTypeTagException) e).tag())
                                                            .isEqualTo(retired);
        }
    }

    @Test
    void retiredTags_stayPinnedToPlaceholders_andNeverToLiveNames() {
        assertThat(SystemTags.TAGS.get(VALUES + "StreamCursorCheckpointValueRetiredRc6")).isEqualTo(1648);
        assertThat(SystemTags.TAGS.get(VALUES + "ConsumerAssignmentValueRetiredRc6")).isEqualTo(1650);
        assertThat(SystemTags.TAGS.get(TOKEN + "RetiredRc6")).isEqualTo(1651);
        assertThat(SystemTags.TAGS.values()).as("1648, 1650 and 1651 are held by the placeholders alone")
                                            .filteredOn(tag -> tag == 1648 || tag == 1650 || tag == 1651)
                                            .hasSize(3);
    }

    @Test
    void memberRoundTrips_someAndNone_onBothValuesAndTheCheckpoint() {
        for (var member : List.<Option<String>>of(some("m-a"), none())) {
            var token = AssignmentToken.assignmentToken(SELF, EPOCH, member);
            var assignment = ConsumerAssignmentValue.consumerAssignmentValue(SELF, EPOCH, 5L, HlcTimestamp.ZERO, member);
            var checkpoint = StreamCursorCheckpointValue.streamCursorCheckpointValue(7L, token);
            var decoded = (StreamCursorCheckpointValue) CODEC.decode(CODEC.encode(checkpoint));

            assertThat(CODEC.<Object>decode(CODEC.encode(token))).isEqualTo(token);
            assertThat(CODEC.<Object>decode(CODEC.encode(assignment))).isEqualTo(assignment);
            assertThat(decoded.token().member()).isEqualTo(member);
        }
    }

    /// Removal tripwire (pinned to S1a-4d): fails the moment the shim factories are deleted.
    @Test
    void oldArityFactories_stillExist_untilS1a4d() {
        assertThat(declares(AssignmentToken.class, "assignmentToken", NodeId.class, Epoch.class))
            .as("the 2-arg assignmentToken shim was removed: that is S1a-4d's job; update this test to say the shim is gone")
            .isTrue();
        assertThat(declares(ConsumerAssignmentValue.class, "consumerAssignmentValue", NodeId.class, Epoch.class, long.class, HlcTimestamp.class))
            .as("the 4-arg consumerAssignmentValue shim was removed: that is S1a-4d's job; update this test to say the shim is gone")
            .isTrue();
    }

    private static boolean declares(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters) != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static byte[] vlq(int tag) {
        var bytes = new ByteArrayOutputStream();
        var rest = tag;

        while (rest >= 0x80) {
            bytes.write((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        bytes.write(rest);
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        var joined = new byte[head.length + tail.length];

        System.arraycopy(head, 0, joined, 0, head.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }
}
