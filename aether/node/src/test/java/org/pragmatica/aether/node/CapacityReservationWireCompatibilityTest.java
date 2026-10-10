// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #2062: a rolling upgrade runs a mixed-version cluster, and the generated codecs are positional: a field added to `CapacityReservationValue` would make an older
/// writer's bytes unreadable. The admission marker therefore lives in its own value (`CapacityAdmissionValue`), and this record keeps the wire shape it has on rc4
/// (`abca7ec9e`: tag 1691, four fields). This pins the bytes of that shape, both ways.
class CapacityReservationWireCompatibilityTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final CapacityReservationValue VALUE = new CapacityReservationValue("west", "", "core", CapacityReservationPhase.OBSERVED);
    /// The encoding of [#VALUE] under the rc4 shape: tag 1691, "west", "", "core", then the phase (tag 1692, ordinal 1 = OBSERVED). The shape and the tag are
    /// the rc4 baseline's (`WireAssignmentTripwireTest` pins them), and the codec is generated from the shape.
    private static final String RC4_BYTES = "9b0d11047765737411001104636f72659c0d01";

    @Test
    void theRc4EncodingOfAReservation_stillDecodes() {
        var decoded = (CapacityReservationValue) CODEC.decode(HexFormat.of().parseHex(RC4_BYTES));

        assertThat(decoded).isEqualTo(VALUE);
    }

    @Test
    void aReservation_encodesToTheRc4Bytes() {
        assertThat(HexFormat.of().formatHex(CODEC.encode(VALUE))).isEqualTo(RC4_BYTES);
    }
}
