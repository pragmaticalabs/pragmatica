// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1236: `outcomeUnknown` is a wire field the sender relies on to tell "not in the log" from "may be in
/// the log". The codec is generated positionally, so this round-trips every response shape through the
/// SAME codec set the forward transport registers ([ForwardCodecsStream]) and checks both flag values
/// survive — including that a clean failure does not decode as outcome-unknown or vice versa.
class PublishForwardResponseCodecTest {
    private static final NodeId OWNER = new NodeId("owner-1");
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), ForwardCodecsStream.CODECS);

    @Test
    void roundTrip_preservesOutcomeUnknownTrue_forOutcomeUnknownResponse() {
        var decoded = roundTrip(PublishForwardResponse.outcomeUnknownResponse(OWNER, "c-1", "acks timed out"));

        assertThat(decoded.outcomeUnknown()).isTrue();
        assertThat(decoded.retryable()).isFalse();
        assertThat(decoded.success()).isFalse();
        assertThat(decoded.errorMessage()).isEqualTo("acks timed out");
    }

    @Test
    void roundTrip_preservesOutcomeUnknownFalse_forCleanFailureResponse() {
        var decoded = roundTrip(PublishForwardResponse.failureResponse(OWNER, "c-2", "not enough replicas"));

        assertThat(decoded.outcomeUnknown()).isFalse();
        assertThat(decoded.retryable()).isFalse();
        assertThat(decoded.success()).isFalse();
    }

    @Test
    void roundTrip_isIdentity_forEveryResponseShape() {
        var shapes = new PublishForwardResponse[]{
            PublishForwardResponse.successResponse(OWNER, "c-3", 42L),
            PublishForwardResponse.failureResponse(OWNER, "c-4", "boom"),
            PublishForwardResponse.retryableResponse(OWNER, "c-5", "config not yet visible"),
            PublishForwardResponse.outcomeUnknownResponse(OWNER, "c-6", "acks timed out")
        };

        for (var shape : shapes) {
            assertThat(roundTrip(shape)).isEqualTo(shape);
        }
    }

    private static PublishForwardResponse roundTrip(PublishForwardResponse response) {
        return CODEC.decode(CODEC.encode(response));
    }
}
