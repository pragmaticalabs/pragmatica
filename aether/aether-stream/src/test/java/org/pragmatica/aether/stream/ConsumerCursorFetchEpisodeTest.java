// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.stream.ConsumerRuntimeState.ConsumerKey;
import org.pragmatica.aether.stream.ConsumerRuntimeState.ConsumerState;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_CURSOR_READABLE;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_CURSOR_UNREADABLE;

/// #3283 round 2: the unreadable-cursor episode of a consumer key, driven state by state with no scheduler and no store, so
/// every interleaving is fixed and the "reported nothing" cases are exact, not timed. The sink is a single-thread hand-off,
/// so each test ends by opening an episode of ANOTHER key: its raise is the last entry, and everything the operation under
/// test reported (or did not) stands before it.
class ConsumerCursorFetchEpisodeTest {
    private static final ConsumerKey KEY = ConsumerKey.consumerKey("orders", 0, "group-1");
    private static final ConsumerKey BARRIER = ConsumerKey.consumerKey("orders", 1, "group-1");
    private static final Duration DEADLINE = Duration.ofSeconds(5);

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;
    private ConsumerRuntimeState runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        runtime = new ConsumerRuntimeState(manager, DeadLetterHandler.deadLetterHandler());
        runtime.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    /// A state that starts while no episode is open closes nothing: there is nothing to report as readable again.
    @Test
    void startWithNoOpenEpisode_reportsNothing() {
        var started = initialized(state());

        runtime.endCursorFetchEpisode(KEY, started);

        assertThat(afterBarrier(1)).containsExactly(STREAM_CONSUMER_CURSOR_UNREADABLE);
    }

    /// The start removes the episode it closes: a later fetch failure of the same key is a new episode, raised again.
    @Test
    void startClosesTheEpisode_aLaterFailureOfTheKeyRaisesAgain() {
        var first = state();

        runtime.openCursorFetchEpisode(KEY, first, failure());
        runtime.endCursorFetchEpisode(KEY, initialized(first));
        runtime.openCursorFetchEpisode(KEY, state(), failure());

        assertThat(afterBarrier(4)).containsExactly(STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                    STREAM_CONSUMER_CURSOR_READABLE,
                                                    STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                    STREAM_CONSUMER_CURSOR_UNREADABLE);
    }

    /// A state cancelled between its fetch and its start did not start: while a successor holds the episode it closes
    /// nothing, and does not report a start.
    @Test
    void cancelledBetweenFetchAndStart_whileASuccessorHoldsTheEpisode_closesNothing() {
        var predecessor = state();
        var successor = state();

        runtime.openCursorFetchEpisode(KEY, predecessor, failure());
        runtime.openCursorFetchEpisode(KEY, successor, failure());
        predecessor.cancel();
        runtime.endCursorFetchEpisode(KEY, predecessor);

        assertThat(afterBarrier(2)).containsExactly(STREAM_CONSUMER_CURSOR_UNREADABLE, STREAM_CONSUMER_CURSOR_UNREADABLE);

        runtime.endCursorFetchEpisode(KEY, initialized(successor));

        assertThat(afterBarrier(4)).describedAs("the successor's start closes the episode, once")
                                   .containsExactly(STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                    STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                    STREAM_CONSUMER_CURSOR_READABLE,
                                                    STREAM_CONSUMER_CURSOR_UNREADABLE);
    }

    /// Without a successor, the same cancelled state closes its own episode, and says it was cancelled.
    @Test
    void cancelledBetweenFetchAndStart_holdingTheEpisode_closesItAsCancelled() {
        var only = state();

        runtime.openCursorFetchEpisode(KEY, only, failure());
        only.cancel();
        runtime.endCursorFetchEpisode(KEY, only);

        assertThat(afterBarrier(3)).containsExactly(STREAM_CONSUMER_CURSOR_UNREADABLE,
                                                    STREAM_CONSUMER_CURSOR_READABLE,
                                                    STREAM_CONSUMER_CURSOR_UNREADABLE);
        assertThat(warnings.get(1).message()).contains("cancelled")
                                             .doesNotContain("started");
    }

    private List<OperatorWarningCode> afterBarrier(int expectedCount) {
        runtime.openCursorFetchEpisode(BARRIER, state(), failure());
        awaitCount(expectedCount);

        return warnings.stream()
                       .map(OperatorWarning::code)
                       .toList();
    }

    private void awaitCount(int count) {
        var deadline = System.nanoTime() + DEADLINE.toNanos();
        BooleanSupplier reached = () -> warnings.size() >= count;

        while (!reached.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static ConsumerState initialized(ConsumerState state) {
        state.markCursorInitialized();

        return state;
    }

    private static ConsumerState state() {
        return ConsumerState.consumerState(ConsumerConfig.consumerConfig("group-1"),
                                           (offset, payload, timestamp) -> Promise.unitPromise(),
                                           0L,
                                           IdlePolicy.REAP_WHEN_IDLE);
    }

    private static Cause failure() {
        return Causes.cause("no core peers available");
    }
}
