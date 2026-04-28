// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Codec;

import java.util.Arrays;
import java.util.List;


@Codec public sealed interface StreamForwardMessage extends ProtocolMessage {
    record PublishForward(NodeId sender,
                          String correlationId,
                          String streamName,
                          int partition,
                          byte[] payload,
                          long timestamp) implements StreamForwardMessage {
        public PublishForward {
            payload = payload.clone();
        }

        public static PublishForward publishForward(NodeId sender,
                                                    String correlationId,
                                                    String streamName,
                                                    int partition,
                                                    byte[] payload,
                                                    long timestamp) {
            return new PublishForward(sender, correlationId, streamName, partition, payload, timestamp);
        }

        @Override public byte[] payload() {
            return payload.clone();
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof PublishForward other && sender.equals(other.sender) && correlationId.equals(other.correlationId) && streamName.equals(other.streamName) && partition == other.partition && Arrays.equals(payload,
                                                                                                                                                                                                                           other.payload) && timestamp == other.timestamp;
        }

        @Override public int hashCode() {
            int result = sender.hashCode();
            result = 31 * result + correlationId.hashCode();
            result = 31 * result + streamName.hashCode();
            result = 31 * result + Integer.hashCode(partition);
            result = 31 * result + Arrays.hashCode(payload);
            result = 31 * result + Long.hashCode(timestamp);
            return result;
        }
    }

    record PublishForwardResponse(NodeId sender,
                                  String correlationId,
                                  boolean success,
                                  long offset,
                                  String errorMessage) implements StreamForwardMessage {
        public static PublishForwardResponse successResponse(NodeId sender, String correlationId, long offset) {
            return new PublishForwardResponse(sender, correlationId, true, offset, "");
        }

        public static PublishForwardResponse failureResponse(NodeId sender, String correlationId, String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, - 1L, errorMessage);
        }
    }

    record ReadForward(NodeId sender,
                       String correlationId,
                       String streamName,
                       int partition,
                       long fromOffset,
                       int maxEvents) implements StreamForwardMessage {
        public static ReadForward readForward(NodeId sender,
                                              String correlationId,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents) {
            return new ReadForward(sender, correlationId, streamName, partition, fromOffset, maxEvents);
        }
    }

    record ReadForwardResponse(NodeId sender,
                               String correlationId,
                               boolean success,
                               List<RawEventDto> events,
                               boolean truncated,
                               String errorMessage) implements StreamForwardMessage {
        public ReadForwardResponse {
            events = List.copyOf(events);
        }

        public static ReadForwardResponse successResponse(NodeId sender,
                                                          String correlationId,
                                                          List<RawEventDto> events) {
            return new ReadForwardResponse(sender, correlationId, true, events, false, "");
        }

        public static ReadForwardResponse truncatedResponse(NodeId sender,
                                                            String correlationId,
                                                            List<RawEventDto> events) {
            return new ReadForwardResponse(sender, correlationId, true, events, true, "");
        }

        public static ReadForwardResponse failureResponse(NodeId sender, String correlationId, String errorMessage) {
            return new ReadForwardResponse(sender, correlationId, false, List.of(), false, errorMessage);
        }
    }
}
