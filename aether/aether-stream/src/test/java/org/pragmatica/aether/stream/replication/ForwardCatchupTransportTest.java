// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.ForwardCatchupTransport.forwardCatchupTransport;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest.catchupRequest;

class ForwardCatchupTransportTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SOURCE = NodeId.randomNodeId();

    @Test
    void requestCatchup_pagesThroughSource_returnsAllEvents_withPartialLastPage() {
        // 5 events, batch size 2 ⇒ pages of [0,1], [2,3], [4] (partial last page terminates the loop).
        var source = new FakeForwardSource(eventsFrom(0, 5));
        var transport = forwardCatchupTransport(source, 2);

        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).hasSize(5);
        assertThat(response.timestamps()).hasSize(5);
        assertThat(response.fromOffset()).isEqualTo(0L);
        assertThat(response.toOffset()).isEqualTo(4L);
        assertThat(new String(response.payloads().getFirst())).isEqualTo("event-0");
        assertThat(new String(response.payloads().getLast())).isEqualTo("event-4");
        // 3 forward reads: 2 full pages + 1 short page.
        assertThat(source.reads().get()).isEqualTo(3);
    }

    /// #1638 B2: every page received is reported as progress, so a long catch-up is seen as moving. Red under "pages
    /// are not reported".
    @Test
    void requestCatchup_reportsEachPageReceived() {
        var source = new FakeForwardSource(eventsFrom(0, 5));
        var transport = forwardCatchupTransport(source, 2);
        var pages = new AtomicInteger();

        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L), pages::incrementAndGet).await();

        assertThat(pages.get()).as("one report per forward read").isEqualTo(source.reads().get()).isEqualTo(3);
    }

    @Test
    void requestCatchup_exactMultipleOfBatch_drainsWithTrailingEmptyPage() {
        // 4 events, batch 2 ⇒ [0,1],[2,3] are both full pages, so a 3rd (empty) read is needed.
        var source = new FakeForwardSource(eventsFrom(0, 4));
        var transport = forwardCatchupTransport(source, 2);

        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).hasSize(4);
        assertThat(response.toOffset()).isEqualTo(3L);
        assertThat(source.reads().get()).isEqualTo(3);
    }

    @Test
    void requestCatchup_emptySource_returnsEmptyResponse() {
        var source = new FakeForwardSource(List.of());
        var transport = forwardCatchupTransport(source, 4);

        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 7L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).isEmpty();
        assertThat(response.toOffset()).isEqualTo(6L); // fromOffset - 1
        assertThat(source.reads().get()).isEqualTo(1);
    }

    /// #1235: catch-up pages are REPLICATION reads, answered up to the source's appended head. A plain
    /// read would be bounded by the source's visible position and could starve the very replica whose
    /// ack makes the missing events visible.
    @Test
    void requestCatchup_pagesThroughTheReplicationRead_neverTheConsumerRead() {
        var source = new FakeForwardSource(eventsFrom(0, 3));
        var transport = forwardCatchupTransport(source, 2);

        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await();

        assertThat(source.catchupReads().get()).isEqualTo(2);
        assertThat(source.reads().get()).isEqualTo(2);
    }

    /// #1730 phase 2 (the ordinary-failover false flag): a source that does not yet list the puller as a replica answers
    /// as a consumer read -- records, no history, NOT vouched. The catch-up fails, so nothing is applied unattributed.
    /// Red under "accept an unvouched page": the records would reach the apply with an empty slice.
    @Test
    void requestCatchup_pageNotVouchedByTheSource_failsInsteadOfDeliveringUnattributedRecords() {
        var transport = forwardCatchupTransport(new FakeForwardSource(eventsFrom(0, 3), false), 4);

        var outcome = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await();

        assertThat(outcome.isFailure()).isTrue();
    }

    /// The other half, so the guard is not a livelock: a vouched answer with an EMPTY history is a source that keeps no
    /// log (non-durable or log-less owner), and its records are delivered as before. Red under "refuse every empty slice".
    @Test
    void requestCatchup_vouchedPageWithEmptyHistory_isDelivered() {
        var transport = forwardCatchupTransport(new FakeForwardSource(eventsFrom(0, 3), true), 4);

        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).hasSize(3);
        assertThat(response.history()).isEmpty();
    }

    /// An unvouched answer with no records applies nothing, so it is not refused (a caught-up replica probing the owner).
    @Test
    void requestCatchup_unvouchedPageWithNoRecords_isNotRefused() {
        var transport = forwardCatchupTransport(new FakeForwardSource(List.of(), false), 4);

        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 5L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).isEmpty();
    }

    /// Operator-facing: a source that keeps answering as a consumer read is a placement disagreement, so after the bound it
    /// is reported ONCE per partition, and a vouched page ends the episode. Red under "no report" and "report every pull".
    @Test
    void requestCatchup_notVouchedForAMinute_reportsOnce_andAVouchedPageEndsTheEpisode() {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<OperatorWarning>();
        var now = new java.util.concurrent.atomic.AtomicLong(1_000L);
        var unvouched = new FakeForwardSource(eventsFrom(0, 3), false);
        var transport = forwardCatchupTransport(unvouched, 4, OperatorWarningSink.handingOffTo(raised::add), now::get);
        var request = catchupRequest(SOURCE, STREAM, PARTITION, 0L);

        transport.requestCatchup(SOURCE, request).await();
        now.addAndGet(ForwardCatchupTransport.NOT_ANSWERED_REPORT_AFTER_MS - 1L);
        transport.requestCatchup(SOURCE, request).await();

        settle();
        assertThat(raised).as("inside the bound: log only").isEmpty();

        now.addAndGet(1L);
        transport.requestCatchup(SOURCE, request).await();
        transport.requestCatchup(SOURCE, request).await();

        for (var i = 0; i < 100 && raised.isEmpty(); i++) {
            settle();
        }
        settle();
        assertThat(raised).hasSize(1);
        assertThat(raised.getFirst().code()).isEqualTo(OperatorWarningCode.STREAM_CATCHUP_SOURCE_NOT_ANSWERING);
        assertThat(raised.getFirst().subject()).isEqualTo(STREAM + "[" + PARTITION + "]@" + SOURCE.id());

        unvouched.vouched(true);
        transport.requestCatchup(SOURCE, request).await();
        for (var i = 0; i < 100 && raised.size() < 2; i++) {
            settle();
        }
        unvouched.vouched(false);
        raised.clear();
        now.addAndGet(ForwardCatchupTransport.NOT_ANSWERED_REPORT_AFTER_MS);
        transport.requestCatchup(SOURCE, request).await();

        settle();
        assertThat(raised).as("a new episode starts its own bound at its first refusal").isEmpty();
    }

    /// The operator sink hands off on another thread; give a wrongly raised warning time to arrive before asserting none did.
    private static void settle() {
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Probe K (v1890): an episode ends at ANY vouched answer, records or not; a refusal after one starts a new episode
    /// instead of inheriting a stale start (an alert that names hours not spent refused). Red under "only a vouched page
    /// with records ends an episode".
    @Test
    void requestCatchup_anEmptyVouchedAnswerEndsTheEpisode_soALaterRefusalIsNotReportedAtOnce() {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<OperatorWarning>();
        var now = new java.util.concurrent.atomic.AtomicLong(1_000L);
        var source = new FakeForwardSource(eventsFrom(0, 3), false);
        var transport = forwardCatchupTransport(source, 4, OperatorWarningSink.handingOffTo(raised::add), now::get);

        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await();
        source.vouched(true);
        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 3L)).await();
        source.vouched(false);
        now.addAndGet(2L * 3_600_000L);
        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await();
        settle();

        assertThat(raised).as("a refusal two hours after the last one is a new episode").isEmpty();
    }

    /// The vouched answer ends the episode on its own, before any staleness rule could: refusals 60 s apart in total (the bound, so the staleness rule does not apply) but
    /// split by an empty vouched answer are two short episodes, not one reported minute. Red under "only a vouched page with
    /// records ends an episode".
    @Test
    void requestCatchup_anEmptyVouchedAnswerBetweenRefusals_splitsTheEpisode() {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<OperatorWarning>();
        var now = new java.util.concurrent.atomic.AtomicLong(1_000L);
        var source = new FakeForwardSource(eventsFrom(0, 3), false);
        var transport = forwardCatchupTransport(source, 4, OperatorWarningSink.handingOffTo(raised::add), now::get);

        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await();
        now.addAndGet(30_000L);
        source.vouched(true);
        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 3L)).await();
        source.vouched(false);
        now.addAndGet(30_000L);
        transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await();
        settle();

        assertThat(raised).isEmpty();
    }

    /// An episode is a run of refusals no further apart than the bound: a refusal after a longer silence with NO vouched
    /// answer between (the redrive stopped asking) does not inherit the old start either.
    @Test
    void requestCatchup_aRefusalAfterALongSilence_startsANewEpisode() {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<OperatorWarning>();
        var now = new java.util.concurrent.atomic.AtomicLong(1_000L);
        var transport = forwardCatchupTransport(new FakeForwardSource(eventsFrom(0, 3), false),
                                                4,
                                                OperatorWarningSink.handingOffTo(raised::add),
                                                now::get);
        var request = catchupRequest(SOURCE, STREAM, PARTITION, 0L);

        transport.requestCatchup(SOURCE, request).await();
        now.addAndGet(2L * 3_600_000L);
        transport.requestCatchup(SOURCE, request).await();
        settle();

        assertThat(raised).isEmpty();
    }

    /// Owner rule: the transition back is an event too, raised only if the episode was reported.
    @Test
    void requestCatchup_aVouchedAnswerAfterAReportedEpisode_raisesTheRestoredEvent_once() {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<OperatorWarning>();
        var now = new java.util.concurrent.atomic.AtomicLong(1_000L);
        var source = new FakeForwardSource(eventsFrom(0, 3), false);
        var transport = forwardCatchupTransport(source, 4, OperatorWarningSink.handingOffTo(raised::add), now::get);
        var request = catchupRequest(SOURCE, STREAM, PARTITION, 0L);

        transport.requestCatchup(SOURCE, request).await();
        now.addAndGet(ForwardCatchupTransport.NOT_ANSWERED_REPORT_AFTER_MS);
        transport.requestCatchup(SOURCE, request).await();
        for (var i = 0; i < 100 && raised.isEmpty(); i++) {
            settle();
        }
        source.vouched(true);
        transport.requestCatchup(SOURCE, request).await();
        transport.requestCatchup(SOURCE, request).await();
        for (var i = 0; i < 100 && raised.size() < 2; i++) {
            settle();
        }
        settle();

        assertThat(raised).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.STREAM_CATCHUP_SOURCE_NOT_ANSWERING,
                                           OperatorWarningCode.STREAM_CATCHUP_SOURCE_ANSWERING_RESTORED);
    }

    /// An episode that was never reported ends silently: no restored event for an alert nobody got.
    @Test
    void requestCatchup_aVouchedAnswerAfterAnUnreportedEpisode_raisesNothing() {
        var raised = new java.util.concurrent.CopyOnWriteArrayList<OperatorWarning>();
        var source = new FakeForwardSource(eventsFrom(0, 3), false);
        var transport = forwardCatchupTransport(source, 4, OperatorWarningSink.handingOffTo(raised::add), () -> 1_000L);
        var request = catchupRequest(SOURCE, STREAM, PARTITION, 0L);

        transport.requestCatchup(SOURCE, request).await();
        source.vouched(true);
        transport.requestCatchup(SOURCE, request).await();
        settle();

        assertThat(raised).isEmpty();
    }

    /// Multi-page: EVERY page that carries records must be vouched, not only the last -- a catch-up whose first page was a
    /// consumer-read answer and whose last page was vouched is refused whole (the last page's history is the source's log
    /// at that moment, but the first page's records were never answered as a replica's). Red under "only the last page
    /// decides".
    @Test
    void requestCatchup_firstPageUnvouched_lastPageVouched_isRefusedWhole() {
        var source = new PerCursorSource(eventsFrom(0, 3), cursor -> cursor >= 2L);
        var transport = forwardCatchupTransport(source, 2);

        assertThat(transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await().isFailure()).isTrue();
    }

    /// The opposite mix (first vouched, last unvouched with records) is refused too: records of the last page would be
    /// applied with no provenance.
    @Test
    void requestCatchup_firstPageVouched_lastPageUnvouched_isRefusedWhole() {
        var source = new PerCursorSource(eventsFrom(0, 3), cursor -> cursor < 2L);
        var transport = forwardCatchupTransport(source, 2);

        assertThat(transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L)).await().isFailure()).isTrue();
    }

    /// Both pages vouched: delivered whole.
    @Test
    void requestCatchup_everyPageVouched_isDelivered() {
        var transport = forwardCatchupTransport(new PerCursorSource(eventsFrom(0, 3), cursor -> true), 2);
        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).hasSize(3);
    }

    /// A source whose vouched flag depends on the page's cursor, to model a catch-up whose pages were answered differently.
    private static final class PerCursorSource implements StreamForwardClient {
        private final List<RawEventDto> events;
        private final java.util.function.LongPredicate vouchedAt;

        private PerCursorSource(List<RawEventDto> events, java.util.function.LongPredicate vouchedAt) {
            this.events = List.copyOf(events);
            this.vouchedAt = vouchedAt;
        }

        @Override public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
            return Promise.success(0L);
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                               String streamName,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
            var page = events.stream().filter(event -> event.offset() >= fromOffset).limit(maxEvents).toList();

            return Promise.success(new ReadForwardResult(page, false, Option.none(), List.of(), vouchedAt.test(fromOffset)));
        }

        @Override public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override public void onReadForwardResponse(ReadForwardResponse response) {}
    }

    @Test
    void requestCatchup_sourceUnreachable_failsWithoutCorruption() {
        var transport = forwardCatchupTransport(new UnreachableForwardSource(), 4);

        var result = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                              .await();

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void requestCatchup_nonContiguousPage_doesNotStartAtCursor_failsCatchup() {
        // Requested cursor is 0, but the source's first returned event is at offset 3 — a gap. Applying
        // it would leave a holey replica, so the catch-up must FAIL rather than return a partial page
        // (M3).
        var source = new GappyForwardSource(eventsFrom(3, 4));
        var transport = forwardCatchupTransport(source, 2);

        var result = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                              .await();

        assertThat(result.isFailure()).isTrue();
    }

    private static List<RawEventDto> eventsFrom(long startOffset, int count) {
        var events = new ArrayList<RawEventDto>(count);
        for (var i = 0; i < count; i++) {
            var offset = startOffset + i;
            events.add(new RawEventDto(offset, 1000L + offset, ("event-" + offset).getBytes()));
        }
        return events;
    }

    /// Minimal {@link StreamForwardClient} serving a fixed event list paged by `(fromOffset, maxEvents)`.
    private static final class FakeForwardSource implements StreamForwardClient {
        private final List<RawEventDto> events;
        private final AtomicInteger reads = new AtomicInteger(0);
        private final AtomicInteger catchupReads = new AtomicInteger(0);
        private volatile boolean vouched;

        private FakeForwardSource(List<RawEventDto> events) {
            this(events, true);
        }

        private FakeForwardSource(List<RawEventDto> events, boolean vouched) {
            this.events = List.copyOf(events);
            this.vouched = vouched;
        }

        void vouched(boolean value) {
            this.vouched = value;
        }

        AtomicInteger reads() {
            return reads;
        }

        AtomicInteger catchupReads() {
            return catchupReads;
        }

        @Override public Promise<ReadForwardResult> readRemoteCatchup(NodeId sourceId,
                                                                      String streamName,
                                                                      int partition,
                                                                      long fromOffset,
                                                                      int maxEvents) {
            catchupReads.incrementAndGet();
            return readRemote(sourceId, streamName, partition, fromOffset, maxEvents);
        }

        @Override public Promise<Long> publishRemote(NodeId governorId,
                                                     String streamName,
                                                     int partition,
                                                     byte[] payload,
                                                     long timestamp) {
            return Promise.success(0L);
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                               String streamName,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
            reads.incrementAndGet();
            var page = events.stream()
                             .filter(event -> event.offset() >= fromOffset)
                             .limit(maxEvents)
                             .toList();
            return Promise.success(new ReadForwardResult(page, false, Option.none(), List.of(), vouched));
        }

        @Override public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override public void onReadForwardResponse(ReadForwardResponse response) {}
    }

    /// Source that always returns its events starting from their own first offset, ignoring the
    /// requested `fromOffset` — models a source that hands back a page not aligned to the cursor (gap).
    private static final class GappyForwardSource implements StreamForwardClient {
        private final List<RawEventDto> events;

        private GappyForwardSource(List<RawEventDto> events) {
            this.events = List.copyOf(events);
        }

        @Override public Promise<Long> publishRemote(NodeId governorId,
                                                     String streamName,
                                                     int partition,
                                                     byte[] payload,
                                                     long timestamp) {
            return Promise.success(0L);
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                               String streamName,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
            var page = events.stream().limit(maxEvents).toList();
            return Promise.success(new ReadForwardResult(page, false));
        }

        @Override public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override public void onReadForwardResponse(ReadForwardResponse response) {}
    }

    /// Source whose reads always fail, modelling an unreachable owner.
    private static final class UnreachableForwardSource implements StreamForwardClient {
        @Override public Promise<Long> publishRemote(NodeId governorId,
                                                     String streamName,
                                                     int partition,
                                                     byte[] payload,
                                                     long timestamp) {
            return StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE.promise();
        }

        @Override public Promise<ReadForwardResult> readRemote(NodeId replicaId,
                                                               String streamName,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
            return StreamForwardError.General.STREAM_FORWARD_UNAVAILABLE.promise();
        }

        @Override public void onPublishForwardResponse(PublishForwardResponse response) {}

        @Override public void onReadForwardResponse(ReadForwardResponse response) {}
    }
}
