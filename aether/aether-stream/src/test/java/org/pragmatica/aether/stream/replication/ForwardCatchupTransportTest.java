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

    @Test
    void requestCatchup_sourceUnreachable_failsWithoutCorruption() {
        var transport = forwardCatchupTransport(new UnreachableForwardSource(), 4);

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

        private FakeForwardSource(List<RawEventDto> events) {
            this.events = List.copyOf(events);
        }

        AtomicInteger reads() {
            return reads;
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
