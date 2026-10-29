// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageGarbageCollector;
import org.pragmatica.storage.StorageGarbageCollector.SharedDeleteLimits;
import org.pragmatica.storage.StorageGarbageCollector.SharedDeleteRun;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;

/// The `streams` instance's own run that deletes released segments from every tier, the cluster-shared tier included
/// (#2258, spec 3.2 item 6): [StorageGarbageCollector#collectSharedDeletes] every [#INTERVAL], bounded by
/// [SharedDeleteLimits#DEFAULT]. It is separate from the node's 5-minute storage maintenance tick, which demotes and
/// collects every instance in one call, so a DHT outage (each remove can wait out its 30 s timeout) stalls only this run.
/// Its delete failures are announced: [OperatorWarningCode#STREAM_SEGMENT_DELETE_FAILING] after [#FAILING_RUNS]
/// consecutive runs with a failed delete, closed by [OperatorWarningCode#STREAM_SEGMENT_DELETE_RECOVERED] at the first run
/// without one.
public final class SharedSegmentCollection implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SharedSegmentCollection.class);
    static final TimeSpan INTERVAL = TimeSpan.timeSpan(60).seconds();
    static final int FAILING_RUNS = 3;

    private final StorageGarbageCollector collector;
    private final SnapshotManager snapshots;
    private final SharedDeleteLimits limits;
    private final AtomicInteger failingRuns = new AtomicInteger();
    private final AtomicBoolean raised = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile OperatorWarningSink operatorWarnings = OperatorWarningSink.logOnly();
    private volatile String nodeId = "local";
    private volatile ScheduledFuture<?> scheduled;

    private SharedSegmentCollection(StorageGarbageCollector collector, SnapshotManager snapshots, SharedDeleteLimits limits) {
        this.collector = collector;
        this.snapshots = snapshots;
        this.limits = limits;
    }

    public static SharedSegmentCollection sharedSegmentCollection(StorageGarbageCollector collector,
                                                                  SnapshotManager snapshots,
                                                                  SharedDeleteLimits limits) {
        return new SharedSegmentCollection(collector, snapshots, limits);
    }

    @Contract
    public void operatorWarnings(OperatorWarningSink sink, String nodeId) {
        this.operatorWarnings = sink;
        this.nodeId = nodeId;
    }

    @Contract
    public void start() {
        if (!closed.get()) {
            scheduled = SharedScheduler.scheduleAtFixedRate(this::runOnce, INTERVAL);
        }
    }

    public boolean isScheduled() {
        return !closed.get() && option(scheduled).filter(future -> !future.isCancelled())
                                                 .isPresent();
    }

    @Contract
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            option(scheduled).onPresent(future -> future.cancel(false));
        }
    }

    /// One run, and the announcement of its failures.
    SharedDeleteRun runOnce() {
        if (closed.get()) {
            return SharedDeleteRun.NOTHING;
        }

        var run = collector.collectSharedDeletes(snapshots, limits);

        if (run.deleted() > 0 || run.failed() > 0) {
            log.debug("Shared-tier segment collection: deleted={} failed={} deferred={}", run.deleted(), run.failed(), run.deferred());
        }

        return run;
    }

    @Contract
    private void report(SharedDeleteRun run) {
        if (run.failed() == 0) {
            failingRuns.set(0);
            if (raised.compareAndSet(true, false)) {
                OperatorWarnings.raise(log,
                                       operatorWarnings,
                                       OperatorWarningCode.STREAM_SEGMENT_DELETE_RECOVERED,
                                       subject(),
                                       "Node {} deletes released stream segments from every tier again ({} deleted this run)",
                                       nodeId,
                                       run.deleted());
            }

            return;
        }

        if (failingRuns.incrementAndGet() == FAILING_RUNS && raised.compareAndSet(false, true)) {
            OperatorWarnings.raise(log,
                                   operatorWarnings,
                                   OperatorWarningCode.STREAM_SEGMENT_DELETE_FAILING,
                                   subject(),
                                   "Node {} has failed to delete released stream segments from the cluster-shared tier for {} "
                                  + "consecutive runs ({} failed this run): the DHT write quorum is unreachable or its removes "
                                  + "fail. The segments are already out of this node's index; their blocks stay on disk and in "
                                  + "the DHT until a run succeeds",
                                   nodeId,
                                   FAILING_RUNS,
                                   run.failed());
        }
    }

    private String subject() {
        return "node " + nodeId + " storage streams";
    }
}
