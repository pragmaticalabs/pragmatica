// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;


/// The durable dead-letter sink for topic streams (durable-pubsub-spec §9): a retry-exhausted
/// event is re-enveloped as a group-attributed [DlqEnvelope] and appended to the topic's
/// `topic:<address>.dlq` stream through the SAME min-sync replication barrier the source topic
/// uses (the DLQ inherits the source's floor — an event that survived replication must not die in
/// a weaker DLQ). The consumer runtime's cursor-hold contract does the rest: the source cursor
/// does not advance until this append resolves, and a failed append retries with backoff there —
/// so cluster states that stall DLQ appends stall the partition VISIBLY rather than dropping the
/// event (the `DLQ_STALL` alarm surface arrives with the D3 batch).
///
/// `dlqPublisherFor` maps a DLQ STREAM name to its replication-barrier publisher; the wiring
/// supplies a memoized assembly over the topic's declared spec (it holds both at group-activation
/// time). This sink serves ONLY topic streams — it is the dead-letter handler of the dispatch
/// runtime, whose every consumer is a durable-topic group.
///
/// `read` serves the source-stream view of dead letters by decoding the DLQ stream's LOCAL
/// partition (owner-local read from offset 0): sufficient for same-node inspection and tests; the
/// operator-facing paged/routed read is the D3 management surface.
public record DlqStreamSink(Deserializer deserializer,
                            StreamPartitionManager manager,
                            Function<String, StreamPublisher<DlqEnvelope>> dlqPublisherFor) implements DeadLetterHandler {
    @Override
    public Promise<Unit> append(String streamName,
                                int partition,
                                long offset,
                                String failingGroup,
                                byte[] payload,
                                String errorMessage,
                                int attemptCount) {
        TopicEventEnvelope envelope = deserializer.decode(payload);
        var entry = new DlqEnvelope(envelope.messageId(),
                                    DurableTopicNames.topicAddressOf(streamName),
                                    partition,
                                    offset,
                                    failingGroup,
                                    attemptCount,
                                    errorMessage,
                                    envelope.publishedAtMs(),
                                    System.currentTimeMillis(),
                                    envelope.payload());

        return dlqPublisherFor.apply(DurableTopicNames.dlqStreamForTopicStream(streamName))
                              .publish(entry);
    }

    @Override
    public List<DeadLetterEntry> read(String streamName, int maxCount) {
        return manager.readLocal(DurableTopicNames.dlqStreamForTopicStream(streamName),
                                 0,
                                 0,
                                 maxCount)
                      .map(events -> events.stream()
                                           .map(event -> toEntry(streamName,
                                                                 event.data()))
                                           .toList())
                      .or(List.of());
    }

    private DeadLetterEntry toEntry(String streamName, byte[] rawDlqEvent) {
        DlqEnvelope envelope = deserializer.decode(rawDlqEvent);

        return DeadLetterEntry.deadLetterEntry(streamName,
                                               envelope.sourcePartition(),
                                               envelope.sourceOffset(),
                                               envelope.failingGroup(),
                                               envelope.payload(),
                                               envelope.lastFailureCause(),
                                               envelope.attemptCount(),
                                               envelope.deadLetteredAtMs());
    }
}
