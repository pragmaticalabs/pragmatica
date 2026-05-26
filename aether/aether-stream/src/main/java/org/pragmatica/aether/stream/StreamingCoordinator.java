// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.StreamPartitionManager.StreamInfo;
import org.pragmatica.aether.stream.replication.GovernorFailoverHandler;
import org.pragmatica.aether.stream.replication.WatermarkTracker;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


public final class StreamingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(StreamingCoordinator.class);

    private final GovernorFailoverHandler failoverHandler;
    private final RetentionEnforcer retentionEnforcer;
    private final StreamPartitionManager partitionManager;
    private final WatermarkTracker watermarkTracker;
    private final SegmentIndex segmentIndex;
    private final SegmentReader segmentReader;

    private final AtomicBoolean active = new AtomicBoolean(false);

    private StreamingCoordinator(GovernorFailoverHandler failoverHandler,
                                 RetentionEnforcer retentionEnforcer,
                                 StreamPartitionManager partitionManager,
                                 WatermarkTracker watermarkTracker,
                                 SegmentIndex segmentIndex,
                                 SegmentReader segmentReader) {
        this.failoverHandler = failoverHandler;
        this.retentionEnforcer = retentionEnforcer;
        this.partitionManager = partitionManager;
        this.watermarkTracker = watermarkTracker;
        this.segmentIndex = segmentIndex;
        this.segmentReader = segmentReader;
    }

    public static StreamingCoordinator streamingCoordinator(GovernorFailoverHandler failoverHandler,
                                                            RetentionEnforcer retentionEnforcer,
                                                            StreamPartitionManager partitionManager,
                                                            WatermarkTracker watermarkTracker,
                                                            SegmentIndex segmentIndex,
                                                            SegmentReader segmentReader) {
        return new StreamingCoordinator(failoverHandler,
                                        retentionEnforcer,
                                        partitionManager,
                                        watermarkTracker,
                                        segmentIndex,
                                        segmentReader);
    }

    public static NoOpStreamingComponent noOp() {
        return new NoOpStreamingComponent();
    }

    public Promise<Unit> activate() {
        if (!active.compareAndSet(false, true)) {return Promise.success(unit());}
        retentionEnforcer.start();
        log.info("STREAMING delegation group activated");
        return runFailoverRecovery();
    }

    public Promise<Unit> deactivate() {
        if (active.compareAndSet(true, false)) {
            retentionEnforcer.close();
            log.info("STREAMING delegation group deactivated");
        }
        return Promise.success(unit());
    }

    public boolean isActive() {
        return active.get();
    }

    public GovernorFailoverHandler failoverHandler() {
        return failoverHandler;
    }

    private Promise<Unit> runFailoverRecovery() {
        var streams = partitionManager.listStreams();
        if (streams.isEmpty()) {
            log.info("Failover recovery: no streams to recover");
            return Promise.success(unit());
        }
        log.info("Failover recovery: recovering {} stream(s)", streams.size());
        var recoveryPromises = streams.stream().flatMap(this::recoverStream)
                                             .toList();
        if (recoveryPromises.isEmpty()) {return Promise.success(unit());}
        return Promise.allOf(recoveryPromises).map(results -> logRecoveryResults(results, streams))
                            .mapToUnit();
    }

    private Stream<Promise<Unit>> recoverStream(StreamInfo info) {
        return IntStream.range(0, info.partitions()).mapToObj(partition -> recoverPartition(info.name(), partition));
    }

    private Promise<Unit> recoverPartition(String streamName, int partition) {
        return failoverHandler.handleFailover(streamName, partition, watermarkTracker, segmentIndex, segmentReader)
                                             .onFailure(cause -> log.warn("Failover recovery failed for {}/{}: {}",
                                                                          streamName,
                                                                          partition,
                                                                          cause.message()));
    }

    private static long logRecoveryResults(List<Result<Unit>> results, List<StreamInfo> streams) {
        var failures = results.stream().filter(Result::isFailure)
                                     .count();
        if (failures > 0) {log.warn("Failover recovery completed with {} failure(s) across {} stream(s)",
                                    failures,
                                    streams.size());} else {log.info("Failover recovery completed successfully for {} stream(s)",
                                                                     streams.size());}
        return failures;
    }
}

final class NoOpStreamingComponent {
    private final AtomicBoolean active = new AtomicBoolean(false);

    public Promise<Unit> activate() {
        active.set(true);
        return Promise.success(unit());
    }

    public Promise<Unit> deactivate() {
        active.set(false);
        return Promise.success(unit());
    }

    public boolean isActive() {
        return active.get();
    }
}
