// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.List;

import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.lang.Option;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;
import static org.assertj.core.api.Assertions.assertThat;


/// #1235 added `catchup` to `ReadForward`, the wire message both consumer reads and replica catch-up
/// travel in. The generated codec is positional, so a component written but not read desynchronises
/// every component after it — each pin is whole-record equality, for both values of the new flag, and
/// the two values must not collapse into one another across the wire.
class ReadForwardCodecTest {
    /// Mirrors production (`NodeCodecs`): the stream-forward codecs layered over the framework registry.
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(),
                                                                  ForwardCodecsStream.CODECS);

    private static final NodeId SENDER = new NodeId("node-1");

    @Test
    void readForward_catchupRead_roundTripsWithEveryComponent() {
        var original = readForward(SENDER, "corr-1", "orders", 3, 42L, 100, false, true);

        assertThat(roundTrip(original)).isEqualTo(original);
        assertThat(roundTrip(original).catchup()).isTrue();
    }

    @Test
    void readForward_consumerRead_roundTripsWithEveryComponent() {
        var original = readForward(SENDER, "corr-2", "orders", 3, 42L, 100, true, false);

        assertThat(roundTrip(original)).isEqualTo(original);
        assertThat(roundTrip(original).catchup()).isFalse();
        assertThat(roundTrip(original).linearizable()).as("the flag before `catchup` must not be displaced").isTrue();
    }

    @Test
    void readForward_roundTrip_distinguishesTheCatchupFlag() {
        var consumer = readForward(SENDER, "corr-3", "orders", 3, 42L, 100, false, false);
        var catchup = readForward(SENDER, "corr-3", "orders", 3, 42L, 100, false, true);

        assertThat(roundTrip(consumer)).isNotEqualTo(roundTrip(catchup));
    }

    /// #1333: the response grew two longs (the serving node's visible bounds). The rewound-looking values
    /// are the discriminating ones — a codec that dropped both components would still round-trip the
    /// `-1/-1` absent form to an equal record.
    @Test
    void readForwardResponse_roundTrips_withTheVisibleBounds() {
        var original = ReadForwardResponse.successResponse(SENDER,
                                                           "corr-4",
                                                           List.of(new RawEventDto(41L,
                                                                                   1_700_000_000_000L,
                                                                                   new byte[]{1, 2})),
                                                           VisibleBounds.visibleBounds(3L, 41L));
        var decoded = roundTripResponse(original);

        assertThat(decoded.earliestRetained()).isEqualTo(3L);
        assertThat(decoded.visibleHead()).isEqualTo(41L);
        assertThat(decoded.bounds()).isEqualTo(Option.some(VisibleBounds.visibleBounds(3L, 41L)));
        assertThat(decoded.events()).hasSize(1);
        assertThat(decoded.success()).isTrue();
    }

    @Test
    void readForwardResponse_absentBounds_decodeToNone() {
        var failure = roundTripResponse(ReadForwardResponse.failureResponse(SENDER, "corr-5", "nope"));
        var noRing = roundTripResponse(ReadForwardResponse.successResponse(SENDER, "corr-6", List.of()));

        assertThat(failure.bounds()).isEqualTo(Option.none());
        assertThat(noRing.bounds()).isEqualTo(Option.none());
        assertThat(roundTripResponse(ReadForwardResponse.successResponse(SENDER,
                                                                         "corr-7",
                                                                         List.of(),
                                                                         VisibleBounds.visibleBounds(0L, -1L))).bounds()).as("an empty ring (tail 0, nothing visible) is a real answer, not absence")
                  .isEqualTo(Option.some(VisibleBounds.visibleBounds(0L, -1L)));
    }

    private static ReadForwardResponse roundTripResponse(ReadForwardResponse original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }

    private static ReadForward roundTrip(ReadForward original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
