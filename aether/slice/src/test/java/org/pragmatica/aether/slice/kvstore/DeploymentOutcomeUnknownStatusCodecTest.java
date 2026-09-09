// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.UnknownTypeTagException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #964 on the EXACT record #963 is changing.
///
/// "Nested" is TWO cases with OPPOSITE recoverability, and collapsing them is the mistake this class
/// exists to make impossible to repeat:
///
/// - **A nested unknown ENUM ORDINAL on an already-pinned type** — #963's shape, appending a constant
///   to `DeploymentOutcomeStatus`. The sentinel is constructible, so the ordinal surfaces as `UNKNOWN`
///   and **the surrounding `DeploymentOutcomeValue` survives intact**: `failingSlices`, `cause`,
///   `timestampMs` and `outcomeVersion` all still arrive. #963's change is therefore RECOVERABLE on an
///   un-upgraded node.
/// - **A nested unknown TYPE TAG** — a new record, or a new `AetherValue` variant. There is nothing to
///   construct, so the **whole outer message is lost**. Loudly and countably after this fix, but lost.
///
/// Both are asserted here, with opposite expected outcomes, against the real generated codecs and real
/// bytes. If a future change ever made these two behave the same, exactly one of these tests goes red.
class DeploymentOutcomeUnknownStatusCodecTest {
    /// Layered over `frameworkCodecs()` because `DeploymentOutcomeValue.failingSlices` is a
    /// DISPATCHED `List` field — the registry must know `List` or the fixture fails before it can say
    /// anything about ordinals. Built the same way the node builds it, so the bytes here are the bytes
    /// production reads.
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                  KvstoreCodecsSlice.CODECS);
    private static final String CAUSE = "slice activation timed out";
    private static final long TIMESTAMP = 1_700_000_000_000L;
    private static final long VERSION = 4L;

    /// Frames a `DeploymentOutcomeValue` exactly as the generated `writeBody` does, except the status
    /// ordinal is supplied by the caller — the only way to obtain the bytes a node with an extra
    /// constant emits. The ordinal is computed from `values().length`, never written as a literal, so
    /// it stays genuinely-unknown as constants are added.
    private static ByteBuf frameWithStatusOrdinal(int statusOrdinal) {
        var buf = Unpooled.buffer();

        SliceCodec.writeCompact(buf, AetherValue_DeploymentOutcomeValueCodec.TAG);
        SliceCodec.writeCompact(buf, AetherValue_DeploymentOutcomeStatusCodec.TAG);
        SliceCodec.writeCompact(buf, statusOrdinal);
        CODEC.write(buf, List.of("org.example:orders:1.0.0"));
        buf.writeByte(SliceCodec.TAG_STRING);
        SliceCodec.writeString(buf, CAUSE);
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(TIMESTAMP);
        buf.writeByte(SliceCodec.TAG_LONG);
        buf.writeLong(VERSION);

        return buf;
    }

    @Nested
    class NestedUnknownOrdinalIsRecoverable {
        /// The control: the same frame with a known ordinal decodes to that constant, so the test below
        /// varies only the ordinal.
        @Test
        void knownOrdinal_decodesToThatConstant() {
            DeploymentOutcomeValue decoded = CODEC.read(frameWithStatusOrdinal(DeploymentOutcomeStatus.FAILED.ordinal()));

            assertThat(decoded.status()).isEqualTo(DeploymentOutcomeStatus.FAILED);
        }

        /// #963 appending a constant: an un-upgraded node reads it as UNKNOWN, not AIOOBE.
        @Test
        void ordinalBeyondThisNode_surfacesAsUnknown() {
            DeploymentOutcomeValue decoded = CODEC.read(frameWithStatusOrdinal(DeploymentOutcomeStatus.values().length));

            assertThat(decoded.status()).isEqualTo(DeploymentOutcomeStatus.UNKNOWN);
        }

        /// THE assertion #963 depends on: the outer record is intact. If this ever fails, appending a
        /// constant becomes permanently lossy rather than merely unreadable-in-one-field.
        @Test
        void surroundingRecordSurvivesIntact() {
            DeploymentOutcomeValue decoded = CODEC.read(frameWithStatusOrdinal(DeploymentOutcomeStatus.values().length));

            assertThat(decoded.failingSlices()).containsExactly("org.example:orders:1.0.0");
            assertThat(decoded.cause()).isEqualTo(CAUSE);
            assertThat(decoded.timestampMs()).isEqualTo(TIMESTAMP);
            assertThat(decoded.outcomeVersion()).isEqualTo(VERSION);
        }
    }

    @Nested
    class NestedUnknownTypeIsNot {
        /// The opposite outcome, on the same record, one field along: `failingSlices` is a DISPATCHED
        /// field read via `codec.read(buf)`, so an unknown tag there kills the whole outer value. This
        /// is what a NEW record or a new `AetherValue` variant would do to an un-upgraded node — and
        /// why "use a new type instead" is not an escape hatch from the enum question.
        @Test
        void unknownTypeTagInADispatchedField_losesTheWholeOuterRecord() {
            var absentTag = SliceCodec.USER_TAG_BASE + 6_310;
            var buf = Unpooled.buffer();

            SliceCodec.writeCompact(buf, AetherValue_DeploymentOutcomeValueCodec.TAG);
            SliceCodec.writeCompact(buf, AetherValue_DeploymentOutcomeStatusCodec.TAG);
            SliceCodec.writeCompact(buf, DeploymentOutcomeStatus.SUCCEEDED.ordinal());
            SliceCodec.writeCompact(buf, absentTag);
            buf.writeLong(0L);

            assertThatThrownBy(() -> CODEC.read(buf))
                .as("a readable status must not rescue the record when a nested TYPE is unknown")
                .isInstanceOf(UnknownTypeTagException.class);
        }

        /// The discriminator, stated as the contrast that matters: the unknown ORDINAL path returns a
        /// value, the unknown TYPE path throws. Same record, same node, opposite recoverability.
        @Test
        void theTwoNestedShapesDoNotBehaveTheSame() {
            DeploymentOutcomeValue fromUnknownOrdinal =
                CODEC.read(frameWithStatusOrdinal(DeploymentOutcomeStatus.values().length));

            assertThat(fromUnknownOrdinal).isNotNull();
            assertThat(fromUnknownOrdinal.cause()).isEqualTo(CAUSE);

            var buf = Unpooled.buffer();

            SliceCodec.writeCompact(buf, SliceCodec.USER_TAG_BASE + 6_311);
            buf.writeLong(0L);

            assertThatThrownBy(() -> CODEC.read(buf)).isInstanceOf(UnknownTypeTagException.class);
        }
    }
}
