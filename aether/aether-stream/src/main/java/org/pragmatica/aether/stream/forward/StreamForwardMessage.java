// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.aether.stream.VisibleBounds;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
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
    ///
    /// `outcomeUnknown` (#1236) marks a failure the owner reported AFTER appending — its min-sync barrier
    /// did not confirm — so the event may be in the log. Without it the sender could only rebuild a
    /// permanent failure and would report "not in the log" for an event that is.
    record PublishForwardResponse(NodeId sender,
                                  String correlationId,
                                  boolean success,
                                  long offset,
                                  String errorMessage,
                                  boolean retryable,
                                  boolean outcomeUnknown) implements StreamForwardMessage {
        public static PublishForwardResponse successResponse(NodeId sender, String correlationId, long offset) {
            return new PublishForwardResponse(sender, correlationId, true, offset, "", false, false);
        }

        public static PublishForwardResponse failureResponse(NodeId sender, String correlationId, String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, -1L, errorMessage, false, false);
        }

        public static PublishForwardResponse retryableResponse(NodeId sender,
                                                               String correlationId,
                                                               String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, -1L, errorMessage, true, false);
        }

        public static PublishForwardResponse outcomeUnknownResponse(NodeId sender,
                                                                    String correlationId,
                                                                    String errorMessage) {
            return new PublishForwardResponse(sender, correlationId, false, -1L, errorMessage, false, true);
        }
    }

    /// A forwarded read. `linearizable` (#345 item 1e-a) marks a `LINEARIZABLE`-class read so the
    /// forwarded-to node re-runs the SAME owner-side serve pipeline the local path uses (committed-owner
    /// check + epoch fence + no-op round + catch-up gate) instead of an unguarded local read — closing
    /// the forward-guard asymmetry. A `false` value (the default factory) means a replica-class read
    /// served by a plain local read, exactly as before.
    ///
    /// `catchup` (#1235) marks a replication read — a replica backfilling, or a new owner pulling from a
    /// survivor — which the serving node answers up to its APPENDED head. Every other forwarded read is a
    /// consumer read and is answered only up to the VISIBLE position. A catch-up bounded by visibility
    /// could deadlock: the events it cannot fetch are the ones only its own ack would make visible.
    record ReadForward(NodeId sender,
                       String correlationId,
                       String streamName,
                       int partition,
                       long fromOffset,
                       int maxEvents,
                       boolean linearizable,
                       boolean catchup) implements StreamForwardMessage {
        public static ReadForward readForward(NodeId sender,
                                              String correlationId,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents) {
            return new ReadForward(sender, correlationId, streamName, partition, fromOffset, maxEvents, false, false);
        }

        public static ReadForward readForward(NodeId sender,
                                              String correlationId,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents,
                                              boolean linearizable) {
            return new ReadForward(sender,
                                   correlationId,
                                   streamName,
                                   partition,
                                   fromOffset,
                                   maxEvents,
                                   linearizable,
                                   false);
        }

        public static ReadForward readForward(NodeId sender,
                                              String correlationId,
                                              String streamName,
                                              int partition,
                                              long fromOffset,
                                              int maxEvents,
                                              boolean linearizable,
                                              boolean catchup) {
            return new ReadForward(sender,
                                   correlationId,
                                   streamName,
                                   partition,
                                   fromOffset,
                                   maxEvents,
                                   linearizable,
                                   catchup);
        }
    }

    /// `earliestRetained` / `visibleHead` (#1333): the serving node's consumer-visible span of the partition
    /// at answer time — the ring tail and the VISIBLE position — on every successful response, `-1/-1` when
    /// the serving node holds no ring and on a failure. They let a node that holds no ring learn the bounds
    /// a projection rebuild must capture through the same forward a consumer read takes (CTO ruling 4 (a),
    /// know `99b1a8d58`): a layout widening of this pinned record, no new message type and no KV record.
    record ReadForwardResponse(NodeId sender,
                               String correlationId,
                               boolean success,
                               List<RawEventDto> events,
                               boolean truncated,
                               String errorMessage,
                               long earliestRetained,
                               long visibleHead) implements StreamForwardMessage {
        public ReadForwardResponse {
            events = List.copyOf(events);
        }

        public static ReadForwardResponse successResponse(NodeId sender,
                                                          String correlationId,
                                                          List<RawEventDto> events) {
            return successResponse(sender, correlationId, events, VisibleBounds.absent());
        }

        public static ReadForwardResponse successResponse(NodeId sender,
                                                          String correlationId,
                                                          List<RawEventDto> events,
                                                          VisibleBounds bounds) {
            return new ReadForwardResponse(sender,
                                           correlationId,
                                           true,
                                           events,
                                           false,
                                           "",
                                           bounds.earliestRetained(),
                                           bounds.visibleHead());
        }

        public static ReadForwardResponse truncatedResponse(NodeId sender,
                                                            String correlationId,
                                                            List<RawEventDto> events) {
            return truncatedResponse(sender, correlationId, events, VisibleBounds.absent());
        }

        public static ReadForwardResponse truncatedResponse(NodeId sender,
                                                            String correlationId,
                                                            List<RawEventDto> events,
                                                            VisibleBounds bounds) {
            return new ReadForwardResponse(sender,
                                           correlationId,
                                           true,
                                           events,
                                           true,
                                           "",
                                           bounds.earliestRetained(),
                                           bounds.visibleHead());
        }

        public static ReadForwardResponse failureResponse(NodeId sender, String correlationId, String errorMessage) {
            return new ReadForwardResponse(sender,
                                           correlationId,
                                           false,
                                           List.of(),
                                           false,
                                           errorMessage,
                                           VisibleBounds.NONE,
                                           VisibleBounds.NONE);
        }

        /// The serving node's bounds, or none when it held no ring (or the read failed).
        public Option<VisibleBounds> bounds() {
            return VisibleBounds.of(earliestRetained, visibleHead);
        }
    }
}
