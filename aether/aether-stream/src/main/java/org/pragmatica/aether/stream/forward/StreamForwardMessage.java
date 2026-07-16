// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


@Codec
public sealed interface StreamForwardMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.FORWARD;
    }

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

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof PublishForward other
                   && sender.equals(other.sender)
                   && correlationId.equals(other.correlationId)
                   && streamName.equals(other.streamName)
                   && partition == other.partition
                   && Arrays.equals(payload, other.payload)
                   && timestamp == other.timestamp;
        }

        @Override
        public int hashCode() {
            int result = sender.hashCode();

            result = 31 * result + correlationId.hashCode();
            result = 31 * result + streamName.hashCode();
            result = 31 * result + Integer.hashCode(partition);
            result = 31 * result + Arrays.hashCode(payload);
            result = 31 * result + Long.hashCode(timestamp);

            return result;
        }
    }

    /// A forwarded-publish response. `retryable` (write-forward race fix) marks a failure the OWNER
    /// classified as transient — its committed config was not yet visible when the forward arrived, or the
    /// partition was capacity-deferred — so the forwarder re-attempts a BOUNDED number of times with short
    /// backoff instead of surfacing it as permanent. A `false` value (the default `failureResponse`
    /// factory) means a permanent failure, served exactly as before.
    record PublishForwardResponse(NodeId sender,
                                  String correlationId,
                                  boolean success,
                                  long offset,
                                  String errorMessage,
                                  boolean retryable) implements StreamForwardMessage {
        public static PublishForwardResponse successResponse(NodeId sender, String correlationId, long offset) {
            return new PublishForwardResponse(sender, correlationId, true, offset, "", false);
        }

        public static PublishForwardResponse failureResponse(NodeId sender, String correlationId, String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, -1L, errorMessage, false);
        }

        public static PublishForwardResponse retryableResponse(NodeId sender,
                                                               String correlationId,
                                                               String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, -1L, errorMessage, true);
        }
    }

    /// A forwarded read. `linearizable` (#345 item 1e-a) marks a `LINEARIZABLE`-class read so the
    /// forwarded-to node re-runs the SAME owner-side serve pipeline the local path uses (committed-owner
    /// check + epoch fence + no-op round + catch-up gate) instead of an unguarded local read — closing
    /// the forward-guard asymmetry. A `false` value (the default factory) means a replica-class read
    /// served by a plain local read, exactly as before.
    record ReadForward(NodeId sender,
                       String correlationId,
                       String streamName,
                       int partition,
                       long fromOffset,
                       int maxEvents,
                       boolean linearizable) implements StreamForwardMessage {
        public static ReadForward readForward(NodeId sender,
                                              String correlationId,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents) {
            return new ReadForward(sender, correlationId, streamName, partition, fromOffset, maxEvents, false);
        }

        public static ReadForward readForward(NodeId sender,
                                              String correlationId,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents,
                                              boolean linearizable) {
            return new ReadForward(sender, correlationId, streamName, partition, fromOffset, maxEvents, linearizable);
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
