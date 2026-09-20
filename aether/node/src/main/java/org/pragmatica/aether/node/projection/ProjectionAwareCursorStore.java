// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The commit hook (#1333): decorates the node's cluster cursor store so that, once a commit has
/// RESOLVED, the group's projection — if this node hosts one — learns the committed cursor, stamped with
/// the epoch THE COMMITTING CONSUMER runs under (never the epoch current at report time — a zombie's
/// report must carry the zombie's epoch, which is how the store recognises and ignores it; #1304 X6).
///
/// The report is issued for a `Persisted` AND a `LocalOnly` outcome: the in-memory cursor has moved past
/// the acknowledged or dead-lettered event either way, which is the fact the skip signal states, and the
/// cluster checkpoint's retry is the runtime's business. It is never issued for a FAILED commit.
///
/// The hook never fails, delays or reorders the commit: the report hangs off the commit's own promise,
/// its failure is absorbed — logged once per `(group, partition)`, counted on [#reportFailures] — and the
/// commit's outcome is returned unchanged. A projection that throws does not break its group's cursor.
public record ProjectionAwareCursorStore(ConsumerCursorStore delegate,
                                         ProjectionRegistry registry,
                                         AtomicLong reportFailures,
                                         Set<String> reported) implements ConsumerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ProjectionAwareCursorStore.class);

    public static ProjectionAwareCursorStore projectionAwareCursorStore(ConsumerCursorStore delegate,
                                                                        ProjectionRegistry registry) {
        return new ProjectionAwareCursorStore(delegate, registry, new AtomicLong(), ConcurrentHashMap.newKeySet());
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
        return commit(consumerGroup, streamName, partition, offset, RewindEpoch.NONE);
    }

    @Override
    public Promise<CommitOutcome> commit(String consumerGroup,
                                         String streamName,
                                         int partition,
                                         long offset,
                                         RewindEpoch epoch) {
        return delegate.commit(consumerGroup, streamName, partition, offset, epoch)
                       .onSuccess(_ -> report(consumerGroup, streamName, partition, offset, epoch));
    }

    @Override
    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return delegate.fetch(consumerGroup, streamName, partition);
    }

    @Override
    public Promise<Option<Cursor>> fetchCursor(String consumerGroup, String streamName, int partition) {
        return delegate.fetchCursor(consumerGroup, streamName, partition);
    }

    @Contract
    private void report(String consumerGroup, String streamName, int partition, long offset, RewindEpoch epoch) {
        registry.lookup(streamName, consumerGroup)
                .onPresent(handle -> Result.lift(() -> handle.onCursorCommitted(NodeReplayCursor.tokenOf(epoch),
                                                                                partition,
                                                                                offset))
                                           .async()
                                           .flatMap(promise -> promise)
                                           .onFailure(cause -> reportFailed(handle, consumerGroup, partition, cause)));
    }

    /// The failure is counted every time and logged once per `(group, partition)`: a projection that
    /// fails every report would otherwise log on every 500ms checkpoint.
    private void reportFailed(ProjectionHandle handle, String consumerGroup, int partition, Cause cause) {
        reportFailures.incrementAndGet();
        if (reported.add(consumerGroup + "[" + partition + "]")) {
            log.warn("Projection {} refused the committed-cursor report for group {} partition {} (logged once; failures counted): {}",
                     handle.projectionName(),
                     consumerGroup,
                     partition,
                     cause.message());
        }
    }

    public long reportFailureCount() {
        return reportFailures.get();
    }
}
