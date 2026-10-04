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
import org.pragmatica.lang.Promise;

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

    /// #1431: a page the source cut at its byte cap is shorter than `batchSize` but is NOT the head. Treating it as
    /// the head ends the pull below the owner's head and promotes the replica CAUGHT_UP there. Red under "ignore
    /// `truncated`": the pull stops after the first page with 2 of 5 events.
    @Test
    void requestCatchup_byteCappedShortPage_keepsPagingToTheHead() {
        // 5 events, batch 4, the source's byte cap fits 2 per page ⇒ cut pages [0,1], [2,3], then [4] uncut.
        var source = new FakeForwardSource(eventsFrom(0, 5), 2);
        var transport = forwardCatchupTransport(source, 4);

        var response = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                                .await()
                                .or((ReplicationMessage.CatchupResponse) null);

        assertThat(response).isNotNull();
        assertThat(response.payloads()).hasSize(5);
        assertThat(response.toOffset()).as("the owner's head, not the first cut page's end").isEqualTo(4L);
        assertThat(source.reads().get()).isEqualTo(3);
    }

    /// #1431: a cut page with no event at all (the event at the cursor alone exceeds the cap) can never progress.
    /// Paging on would re-read the same cursor forever; reporting it as the head would promote below it. Fail.
    @Test
    void requestCatchup_cutPageWithNoEvent_failsInsteadOfLoopingOrPromoting() {
        var source = new FakeForwardSource(eventsFrom(0, 3), 0);
        var transport = forwardCatchupTransport(source, 4);

        var result = transport.requestCatchup(SOURCE, catchupRequest(SOURCE, STREAM, PARTITION, 0L))
                              .await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("maxReadResponseBytes"));
        assertThat(source.reads().get()).isEqualTo(1);
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
        private final int capPerPage;

        private FakeForwardSource(List<RawEventDto> events) {
            this(events, Integer.MAX_VALUE);
        }

        /// `capPerPage` models the owner's byte cap: a page holding more events is cut there and marked truncated.
        private FakeForwardSource(List<RawEventDto> events, int capPerPage) {
            this.events = List.copyOf(events);
            this.capPerPage = capPerPage;
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
            var cut = page.size() > capPerPage;
            return Promise.success(new ReadForwardResult(cut ? page.subList(0, capPerPage) : page, cut));
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
