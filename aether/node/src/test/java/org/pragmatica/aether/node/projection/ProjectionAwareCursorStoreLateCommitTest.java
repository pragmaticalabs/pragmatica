// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayStatus;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Async-callback probe for the commit hook. The projection's report is a side effect the committing
/// consumer depends on ("once a commit has RESOLVED, the projection learns the cursor"), so it must be
/// issued before the commit's own continuation runs. The delegate here resolves LATER, on another thread,
/// and the projection's `onCursorCommitted` is slow: a hook that reports from a detached callback lets the
/// continuation run first. A delegate returning an already-resolved promise would hide the race.
class ProjectionAwareCursorStoreLateCommitTest {
    private static final String GROUP = "slice#method";
    private static final String STREAM = "topic";
    private static final long RESOLVE_DELAY_MS = 5;
    private static final long REPORT_DELAY_MS = 30;

    @Test
    void commit_reportsToProjectionBeforeContinuation_whenDelegateResolvesLaterOnAnotherThread() {
        var handle = new SlowHandle();
        var store = ProjectionAwareCursorStore.projectionAwareCursorStore(lateDelegate(new CommitOutcome.Persisted()),
                                                                          registryOf(handle));

        store.commit(GROUP, STREAM, 0, 7L, Epoch.ZERO, RewindEpoch.NONE, Epoch.ZERO)
             .await();

        assertThat(handle.reports.get()).as("the committing consumer continued before the projection learned the cursor")
                                        .isEqualTo(1);
    }

    @Test
    void commit_doesNotReport_whenLateOutcomeIsFenced() {
        var handle = new SlowHandle();
        var store = ProjectionAwareCursorStore.projectionAwareCursorStore(lateDelegate(new CommitOutcome.Fenced("moved")),
                                                                          registryOf(handle));

        store.commit(GROUP, STREAM, 0, 7L, Epoch.ZERO, RewindEpoch.NONE, Epoch.ZERO)
             .await();

        assertThat(handle.reports.get()).isZero();
    }

    private static ConsumerCursorStore lateDelegate(CommitOutcome outcome) {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
                var promise = Promise.<CommitOutcome> promise();

                Thread.ofVirtual()
                      .start(() -> resolveAfterDelay(promise, outcome));

                return promise;
            }

            @Override
            public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private static void resolveAfterDelay(Promise<CommitOutcome> promise, CommitOutcome outcome) {
        sleep(RESOLVE_DELAY_MS);
        promise.resolve(Result.success(outcome));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProjectionRegistry registryOf(ProjectionHandle handle) {
        return new ProjectionRegistry() {
            @Override
            public Result<Unit> register(Registration registration) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void unregister(ArtifactBase slice, String topicStream) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Option<ProjectionHandle> lookup(String topicStream, String groupId) {
                return Option.some(handle);
            }

            @Override
            public Result<String> groupIdOf(ArtifactBase slice, String topicStream, Option<String> method) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Registration> registrations(String topicStream) {
                return List.of();
            }
        };
    }

    private static final class SlowHandle implements ProjectionHandle {
        final AtomicInteger reports = new AtomicInteger();

        @Override
        public String projectionName() {
            return "slow";
        }

        @Override
        public Promise<Unit> onCursorCommitted(RewindToken token, int partition, long committedCursor) {
            sleep(REPORT_DELAY_MS);
            reports.incrementAndGet();

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> rebuild() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<ReplayStatus> replayStatus() {
            throw new UnsupportedOperationException();
        }
    }
}
