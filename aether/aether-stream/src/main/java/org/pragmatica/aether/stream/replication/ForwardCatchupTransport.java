// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;


/// Production {@link CatchupTransport} that delegates catch-up reads to the existing forward path (the
/// same transport the read router uses to serve cross-node reads), marked as replication reads via
/// {@link StreamForwardClient#readRemoteCatchup} so the source answers up to its APPENDED head rather
/// than its consumer-visible position (#1235). Replaces {@link CatchupTransport#NOOP}.
///
/// ## Paging
/// A single {@link ReplicationMessage.CatchupRequest} can span an arbitrary number of events, but a
/// forward read response is capped (`maxReadResponseBytes` on the owner side, `maxEvents` here). The
/// adapter therefore loops: it issues `readRemoteCatchup(from = cursor, maxEvents = batchSize)`, appends the
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
    private static final Logger log = LoggerFactory.getLogger(ForwardCatchupTransport.class);
    /// How long a partition's catch-up may be answered as a consumer read, without one vouched page in between, before
    /// the operator is told: twelve backfill redrive ticks (5 s each), the bound the truncation report uses.
    static final long NOT_ANSWERED_REPORT_AFTER_MS = 60_000L;

    private final StreamForwardClient forwardClient;
    private final int batchSize;
    private final OperatorWarningSink warnings;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> firstUnvouchedAt = new ConcurrentHashMap<>();
    private final Set<String> reportedUnvouched = ConcurrentHashMap.newKeySet();

    ForwardCatchupTransport(StreamForwardClient forwardClient,
                            int batchSize,
                            OperatorWarningSink warnings,
                            LongSupplier clock) {
        this.forwardClient = forwardClient;
        this.batchSize = batchSize;
        this.warnings = warnings;
        this.clock = clock;
    }

    public static ForwardCatchupTransport forwardCatchupTransport(StreamForwardClient forwardClient, int batchSize) {
        return forwardCatchupTransport(forwardClient,
                                       batchSize,
                                       OperatorWarningSink.logOnly(),
                                       System::currentTimeMillis);
    }

    /// `warnings` receives `stream-catchup-source-not-answering` once per partition whose catch-up has been answered as a
    /// consumer read for [#NOT_ANSWERED_REPORT_AFTER_MS] (a source that never lists this node as a replica); `clock` is
    /// the time source of that bound, in milliseconds.
    public static ForwardCatchupTransport forwardCatchupTransport(StreamForwardClient forwardClient,
                                                                  int batchSize,
                                                                  OperatorWarningSink warnings,
                                                                  LongSupplier clock) {
        return new ForwardCatchupTransport(forwardClient, Math.max(1, batchSize), warnings, clock);
    }

    @Override
    public Promise<CatchupResponse> requestCatchup(NodeId target, ReplicationMessage.CatchupRequest request) {
        return requestCatchup(target,
                              request,
                              () -> {});
    }

    /// Each page received is reported through `onPage` before the next is requested (#1638 B2).
    @Override
    public Promise<CatchupResponse> requestCatchup(NodeId target,
                                                   ReplicationMessage.CatchupRequest request,
                                                   Runnable onPage) {
        return page(target, request, request.fromOffset(), new ArrayList<>(), onPage);
    }

    private Promise<CatchupResponse> page(NodeId target,
                                          ReplicationMessage.CatchupRequest request,
                                          long cursor,
                                          List<RawEventDto> accumulated,
                                          Runnable onPage) {
        return forwardClient.readRemoteCatchup(target,
                                               request.streamName(),
                                               request.partition(),
                                               cursor,
                                               batchSize)
                            .flatMap(result -> continueOrFinish(target, request, cursor, accumulated, result, onPage));
    }

    private Promise<CatchupResponse> continueOrFinish(NodeId target,
                                                      ReplicationMessage.CatchupRequest request,
                                                      long cursor,
                                                      List<RawEventDto> accumulated,
                                                      ReadForwardResult result,
                                                      Runnable onPage) {
        var events = result.events();

        if (!events.isEmpty() && events.getFirst().offset() != cursor) {
            // Non-contiguous page: the source returned events that do not start at the requested
            // cursor. Applying them would leave a hole in the replica (M3), so fail the whole
            // catch-up rather than produce a holey replica — the caller stays SYNCING and retries.
            return CatchupError.NON_CONTIGUOUS_PAGE.promise();
        }

        if (!result.historyVouched() && !events.isEmpty()) {
            // The source answered as a consumer read, not as a replica catch-up (it does not yet list this node as a
            // replica, #1235): its page carries no owner-epoch history. Applying the records would leave this copy
            // holding records with no provenance, which the next live append flags HISTORY_MISSING. Fail the catch-up;
            // the caller stays SYNCING and the next pull -- a live-batch gap or the redrive -- asks again.
            noteUnvouched(target, request);

            return CatchupError.SOURCE_NOT_YET_REPLICA_ANSWER.promise();
        }

        if (!events.isEmpty()) {
            forgetUnvouched(request);
        }

        accumulated.addAll(events);
        onPage.run();
        if (events.size() >= batchSize) {
            var nextCursor = events.getLast().offset() + 1;

            return page(target, request, nextCursor, accumulated, onPage);
        }

        return Promise.success(toResponse(target, request, accumulated, result.history()));
    }

    private static String partitionKey(ReplicationMessage.CatchupRequest request) {
        return request.streamName() + "[" + request.partition() + "]";
    }

    private void forgetUnvouched(ReplicationMessage.CatchupRequest request) {
        var key = partitionKey(request);

        firstUnvouchedAt.remove(key);
        reportedUnvouched.remove(key);
    }

    /// Tells the operator once, when this partition's catch-up has been answered as a consumer read for
    /// [#NOT_ANSWERED_REPORT_AFTER_MS] with no vouched page since: a source that does not list this node as a replica of
    /// the partition for that long is a placement disagreement, and the replica stays out of the in-sync set until it ends.
    private void noteUnvouched(NodeId target, ReplicationMessage.CatchupRequest request) {
        var key = partitionKey(request);
        var now = clock.getAsLong();
        var since = firstUnvouchedAt.computeIfAbsent(key, _ -> now);

        if (now - since >= NOT_ANSWERED_REPORT_AFTER_MS && reportedUnvouched.add(key)) {
            OperatorWarnings.raise(log,
                                   warnings,
                                   OperatorWarningCode.STREAM_CATCHUP_SOURCE_NOT_ANSWERING,
                                   key + "@" + target.id(),
                                   "Replica {} cannot catch up from {}: for {} s its catch-up has been answered as a consumer read, "
                                  + "because the source does not list this node as a replica of the partition. No records are "
                                  + "applied and the replica stays out of the in-sync set until the source's placement view "
                                  + "agrees; it retries every backfill redrive.",
                                   key,
                                   target.id(),
                                   (now - since) / 1_000L);
        }
    }

    private enum CatchupError implements Cause {
        NON_CONTIGUOUS_PAGE("Catch-up page does not start at the requested cursor — gap detected"),
        SOURCE_NOT_YET_REPLICA_ANSWER("Catch-up page was answered as a consumer read (source does not yet list this node as a replica) — carries no owner-epoch history, not applied");
        private final String message;
        CatchupError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// `history` is the LAST page's (#1596): the source read it after that page's events, so it covers every event
    /// accumulated here.
    private static CatchupResponse toResponse(NodeId target,
                                              ReplicationMessage.CatchupRequest request,
                                              List<RawEventDto> events,
                                              List<ProvenanceEntry> history) {
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
                               timestamps,
                               history);
    }
}
