// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;


/// Production {@link CatchupTransport} that delegates catch-up reads to the existing
/// {@link StreamForwardClient#readRemote} forward path (the same transport the read router uses to
/// serve cross-node reads). Replaces {@link CatchupTransport#NOOP}.
///
/// ## Paging
/// A single {@link ReplicationMessage.CatchupRequest} can span an arbitrary number of events, but a
/// forward read response is capped (`maxReadResponseBytes` on the owner side, `maxEvents` here). The
/// adapter therefore loops: it issues `readRemote(from = cursor, maxEvents = batchSize)`, appends the
/// returned events, advances the cursor past the last returned offset, and repeats while the source
/// keeps returning a full page (`size == batchSize`) — i.e. there may be more. A short page (or an
/// empty page) means the source has no more events and the loop terminates. All accumulated events
/// are packed into one {@link CatchupResponse}, offset-preserving (`toOffset` = last event offset, or
/// `fromOffset - 1` when nothing came back).
///
/// Any forward-read failure (timeout, source unreachable) short-circuits the whole catch-up as a
/// failed {@link Promise}; the caller (backfill orchestrator) treats that as "stay SYNCING" and does
/// not flip the local descriptor to CAUGHT_UP.
public final class ForwardCatchupTransport implements CatchupTransport {
    private final StreamForwardClient forwardClient;
    private final int batchSize;

    ForwardCatchupTransport(StreamForwardClient forwardClient, int batchSize) {
        this.forwardClient = forwardClient;
        this.batchSize = batchSize;
    }

    public static ForwardCatchupTransport forwardCatchupTransport(StreamForwardClient forwardClient, int batchSize) {
        return new ForwardCatchupTransport(forwardClient, Math.max(1, batchSize));
    }

    @Override
    public Promise<CatchupResponse> requestCatchup(NodeId target, ReplicationMessage.CatchupRequest request) {
        return page(target, request, request.fromOffset(), new ArrayList<>());
    }

    private Promise<CatchupResponse> page(NodeId target,
                                          ReplicationMessage.CatchupRequest request,
                                          long cursor,
                                          List<RawEventDto> accumulated) {
        return forwardClient.readRemote(target,
                                        request.streamName(),
                                        request.partition(),
                                        cursor,
                                        batchSize)
                            .flatMap(result -> continueOrFinish(target, request, cursor, accumulated, result));
    }

    private Promise<CatchupResponse> continueOrFinish(NodeId target,
                                                      ReplicationMessage.CatchupRequest request,
                                                      long cursor,
                                                      List<RawEventDto> accumulated,
                                                      ReadForwardResult result) {
        var events = result.events();

        if (!events.isEmpty() && events.getFirst().offset() != cursor) {
            // Non-contiguous page: the source returned events that do not start at the requested
            // cursor. Applying them would leave a hole in the replica (M3), so fail the whole
            // catch-up rather than produce a holey replica — the caller stays SYNCING and retries.
            return CatchupError.NON_CONTIGUOUS_PAGE.promise();
        }

        accumulated.addAll(events);
        if (events.size() >= batchSize) {
            var nextCursor = events.getLast().offset() + 1;

            return page(target, request, nextCursor, accumulated);
        }

        return Promise.success(toResponse(target, request, accumulated));
    }

    private enum CatchupError implements Cause {
        NON_CONTIGUOUS_PAGE("Catch-up page does not start at the requested cursor — gap detected");
        private final String message;
        CatchupError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    private static CatchupResponse toResponse(NodeId target,
                                              ReplicationMessage.CatchupRequest request,
                                              List<RawEventDto> events) {
        var payloads = new ArrayList<byte[]>(events.size());
        var timestamps = new ArrayList<Long>(events.size());

        events.forEach(event -> {
            payloads.add(event.data());
            timestamps.add(event.timestamp());
        });
        var toOffset = events.isEmpty()
                       ? request.fromOffset() - 1
                       : events.getLast().offset();

        return catchupResponse(target,
                               request.streamName(),
                               request.partition(),
                               request.fromOffset(),
                               toOffset,
                               payloads,
                               timestamps);
    }
}
