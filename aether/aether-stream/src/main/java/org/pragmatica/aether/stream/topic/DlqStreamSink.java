// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;


/// The durable dead-letter sink for topic streams (durable-pubsub-spec §9): a retry-exhausted
/// event is re-enveloped as a group-attributed [DlqEnvelope] and appended to the topic's
/// `topic:<address>.dlq` stream through the SAME confirmation barrier the source topic
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
/// `read` serves the source-stream view of dead letters by decoding the DLQ stream's LOCAL partition through the
/// validated tiered read (so dead letters that left the ring come from the sealed tier), starting at the earliest
/// retained offset: sufficient for same-node inspection and tests; the operator-facing paged/routed read is the D3
/// management surface. Its promise carries the failure of a read that could not be served; it is never an empty list.
public record DlqStreamSink(Deserializer deserializer,
                            StreamPartitionManager manager,
                            Function<String, StreamPublisher<DlqEnvelope>> dlqPublisherFor) implements DeadLetterHandler {
    private static final StreamPartitionManager.EpochRead NOTHING_DEAD_LETTERED = new StreamPartitionManager.EpochRead(List.of(),
                                                                                                                       Epoch.ZERO);

    private static final System.Logger LOG = System.getLogger(DlqStreamSink.class.getName());

    /// #1266: the envelope decode is LIFTED. A bare decode threw synchronously out of `append`, before
    /// the runtime could attach its callbacks, so the dead-letter hold was never released and the
    /// partition wedged silently and permanently. An undecodable event is instead quarantined RAW
    /// (FER: degrade forward — the raw bytes and the failure are preserved in the DLQ, and the source
    /// cursor moves past an event no retry could ever decode).
    @Override
    public Promise<Unit> append(String streamName,
                                int partition,
                                long offset,
                                String failingGroup,
                                byte[] payload,
                                String errorMessage,
                                int attemptCount) {
        var entry = Result.lift(() -> deserializer.<TopicEventEnvelope> decode(payload)).fold(cause -> quarantined(streamName,
                                                                                                                   partition,
                                                                                                                   offset,
                                                                                                                   failingGroup,
                                                                                                                   payload,
                                                                                                                   errorMessage,
                                                                                                                   attemptCount,
                                                                                                                   cause),
                                                                                              envelope -> deadLettered(streamName,
                                                                                                                       partition,
                                                                                                                       offset,
                                                                                                                       failingGroup,
                                                                                                                       errorMessage,
                                                                                                                       attemptCount,
                                                                                                                       envelope));

        return dlqPublisherFor.apply(DurableTopicNames.dlqStreamForTopicStream(streamName))
                              .publish(entry);
    }

    private static DlqEnvelope deadLettered(String streamName,
                                            int partition,
                                            long offset,
                                            String failingGroup,
                                            String errorMessage,
                                            int attemptCount,
                                            TopicEventEnvelope envelope) {
        return new DlqEnvelope(envelope.messageId(),
                               DurableTopicNames.topicAddressOf(streamName),
                               partition,
                               offset,
                               failingGroup,
                               attemptCount,
                               errorMessage,
                               envelope.publishedAtMs(),
                               System.currentTimeMillis(),
                               envelope.payload(),
                               false);
    }

    private static DlqEnvelope quarantined(String streamName,
                                           int partition,
                                           long offset,
                                           String failingGroup,
                                           byte[] rawEvent,
                                           String errorMessage,
                                           int attemptCount,
                                           Cause decodeFailure) {
        LOG.log(System.Logger.Level.WARNING,
                "Undecodable topic event {0}[{1}]@{2} for group {3} quarantined raw in the DLQ: {4}",
                streamName,
                partition,
                offset,
                failingGroup,
                decodeFailure.message());

        return new DlqEnvelope("undecodable:" + streamName + ":" + partition + ":" + offset,
                               DurableTopicNames.topicAddressOf(streamName),
                               partition,
                               offset,
                               failingGroup,
                               attemptCount,
                               errorMessage + " (envelope undecodable: " + decodeFailure.message() + ")",
                               0L,
                               System.currentTimeMillis(),
                               rawEvent,
                               true);
    }

    /// #1266 review: each DLQ record is decoded under a lift. A record that does not decode — an entry
    /// in the pre-`rawEvent` wire shape, or a corrupt one — is reported as a typed
    /// [UndecodableDeadLetter] and SKIPPED, never thrown out of `read`, so one bad record cannot make the
    /// whole DLQ unreadable. `read` has no per-entry failure channel (its contract is a `List`), so the
    /// typed failure surfaces in the log. [design intent — unverified: skip-and-log is a judgment call;
    /// an interface change to `Result<List<...>>` was not made.]
    @Override
    public Promise<List<DeadLetterEntry>> read(String streamName, int maxCount) {
        var dlqStream = DurableTopicNames.dlqStreamForTopicStream(streamName);

        return readFrom(dlqStream, 0, maxCount).fold(result -> result.fold(cause -> restartAtEarliest(dlqStream,
                                                                                                      maxCount,
                                                                                                      cause),
                                                                           Promise::success))
                       .map(read -> entriesOf(streamName, read));
    }

    /// The validated tiered read: a dead letter that left the ring (space eviction, or the ring age tick at the DLQ's max
    /// age) is read from the sealed tier, oldest first. Its failures reach the caller as they are: a seal still in
    /// flight, a refused owner gate, an unverified replica. None of them is an empty DLQ.
    private Promise<StreamPartitionManager.EpochRead> readFrom(String dlqStream, long fromOffset, int maxCount) {
        return manager.readServingTiered(dlqStream, 0, fromOffset, maxCount, Epoch.ZERO);
    }

    /// Two outcomes that are not failures of the inspection. FER (degrade forward): retention reclaimed the oldest dead
    /// letters, so a read from offset 0 is refused with the earliest offset still held; the inspection lists what is
    /// retained, from there, and the reclaimed ones are gone by the stream's own max-age rule. Design-out: the DLQ stream
    /// is created with its topic, so a DLQ stream this node does not hold means nothing was dead-lettered here. Every
    /// other failure is passed on.
    private Promise<StreamPartitionManager.EpochRead> restartAtEarliest(String dlqStream, int maxCount, Cause cause) {
        return switch (cause) {
            case StreamError.CursorExpired expired -> readFrom(dlqStream, expired.tailOffset(), maxCount);
            case StreamError.StreamNotFound _ -> Promise.success(NOTHING_DEAD_LETTERED);
            default -> cause.promise();
        };
    }

    private List<DeadLetterEntry> entriesOf(String streamName, StreamPartitionManager.EpochRead read) {
        return read.events()
                   .stream()
                   .flatMap(event -> decodedEntry(streamName, event).stream())
                   .toList();
    }

    private Option<DeadLetterEntry> decodedEntry(String streamName, OffHeapRingBuffer.RawEvent event) {
        return Result.lift(() -> toEntry(streamName,
                                         event.data()))
                     .mapError(cause -> UndecodableDeadLetter.FACTORY.apply(event.offset(),
                                                                            cause.message()))
                     .onFailure(DlqStreamSink::logSkipped)
                     .option();
    }

    private static void logSkipped(Cause cause) {
        LOG.log(System.Logger.Level.WARNING, "Skipping undecodable DLQ record: {0}", cause.message());
    }

    /// A DLQ-stream record that does not decode as a [DlqEnvelope] (#1266 review).
    public record UndecodableDeadLetter(long dlqOffset, String detail, String message) implements Cause {
        static final Fn2<UndecodableDeadLetter, Long, String> FACTORY = Causes.forTwoValues("DLQ record at offset %s does not decode as a DlqEnvelope: %s",
                                                                                            UndecodableDeadLetter::new);
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
                                               envelope.deadLetteredAtMs(),
                                               envelope.rawEvent());
    }
}
