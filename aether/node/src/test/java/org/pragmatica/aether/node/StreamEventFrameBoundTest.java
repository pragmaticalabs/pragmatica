// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.blueprint.StreamEventLimits;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.OutboundMessageLimit;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #1937: the largest `max-event-size` a stream may declare ([StreamEventLimits#MAX_EVENT_SIZE_BYTES]) is DERIVED from the
/// real messages, not guessed. A single event that large travels ALONE in one `ReplicateEvents` or `CatchupResponse`; this
/// encodes both through the node's own codec registry with worst-case names (a 192-character stream name, a 65-character
/// node id, the largest numeric fields) and a catch-up history, and pins that each still fits the bound every whole message
/// is validated against ([OutboundMessageLimit#MAX_TRANSFER_BYTES]) — and that the reserve covers what is not payload.
class StreamEventFrameBoundTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final String STREAM = "n".repeat(192);
    private static final NodeId NODE = new NodeId("node-".concat("x".repeat(60)));
    private static final int HISTORY_ENTRIES = 20;

    @Test
    void aMaximumSizeEvent_inAReplicateEventsMessage_fitsTheTransferBound() {
        var payload = new byte[(int) StreamEventLimits.MAX_EVENT_SIZE_BYTES];
        var message = ReplicateEvents.replicateEvents(NODE,
                                                      STREAM,
                                                      Integer.MAX_VALUE,
                                                      Long.MAX_VALUE,
                                                      List.of(payload),
                                                      List.of(Long.MAX_VALUE),
                                                      Epoch.ZERO);

        assertThat(encodedSize(message)).as("a ReplicateEvents carrying one maximum-size event")
                                        .isLessThanOrEqualTo(OutboundMessageLimit.MAX_TRANSFER_BYTES);
    }

    @Test
    void aMaximumSizeEvent_inACatchupResponseWithHistory_fitsTheTransferBound() {
        var payload = new byte[(int) StreamEventLimits.MAX_EVENT_SIZE_BYTES];
        var history = new ArrayList<ProvenanceEntry>();

        for (int i = 0; i < HISTORY_ENTRIES; i++) {
            history.add(ProvenanceEntry.provenanceEntry(Epoch.ZERO, Option.some("01ARZ3NDEKTSV4RRFFQ69G5FAV"), Long.MAX_VALUE));
        }

        var message = CatchupResponse.catchupResponse(NODE,
                                                      STREAM,
                                                      Integer.MAX_VALUE,
                                                      Long.MAX_VALUE,
                                                      Long.MAX_VALUE,
                                                      List.of(payload),
                                                      List.of(Long.MAX_VALUE),
                                                      history);

        assertThat(encodedSize(message)).as("a CatchupResponse carrying one maximum-size event and a %d-entry history", HISTORY_ENTRIES)
                                        .isLessThanOrEqualTo(OutboundMessageLimit.MAX_TRANSFER_BYTES);
    }

    @Test
    void theEnvelopeReserve_coversWhatIsNotPayload_withRoomToSpare() {
        var payload = new byte[1024];
        var replicate = ReplicateEvents.replicateEvents(NODE, STREAM, Integer.MAX_VALUE, Long.MAX_VALUE, List.of(payload), List.of(Long.MAX_VALUE), Epoch.ZERO);

        assertThat(encodedSize(replicate) - payload.length).as("measured non-payload bytes of a one-event ReplicateEvents")
                                                           .isLessThan(StreamEventLimits.EVENT_ENVELOPE_RESERVE_BYTES / 4);
    }

    private static long encodedSize(Object message) {
        var buffer = Unpooled.buffer();

        try {
            CODEC.write(buffer, message);

            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }
}
