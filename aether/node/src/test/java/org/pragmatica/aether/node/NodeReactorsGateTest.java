// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.utility.react.ReactExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// v-2653 (verifier): the node's gate predicate (KVStore dispatcher design §3.5.1 "Gate predicate": engine running
/// AND the node not stopping). The component tests inject their own gate, so they cannot see this composition.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NodeReactorsGateTest {
    private final NodeReactors reactors = NodeReactors.nodeReactors(DispatcherFailureSink.loggingOnly());
    private final AtomicInteger ran = new AtomicInteger();

    @AfterEach
    void tearDown() {
        reactors.close();
    }

    @Test
    void unbound_engineReadsAlive_reactionRuns() throws InterruptedException {
        var executor = reactors.executor("C");

        executor.react("r", ran::incrementAndGet);
        drain(executor);

        assertThat(ran.get()).as("boot reactions run before an engine is bound").isEqualTo(1);
    }

    @Test
    void boundEngineStopRequested_dropsReaction() throws InterruptedException {
        var stopRequested = new AtomicBoolean(false);
        var executor = reactors.executor("C");

        reactors.bindEngine(stopRequested::get);
        stopRequested.set(true);
        executor.react("r", ran::incrementAndGet);
        drain(executor);

        assertThat(ran.get()).as("engine half: a stop-requested engine closes the gate").isZero();
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    @Test
    void nodeStopping_engineAlive_dropsReaction() throws InterruptedException {
        var executor = reactors.executor("C");

        reactors.bindEngine(() -> false);
        reactors.markStopping();
        executor.react("r", ran::incrementAndGet);
        drain(executor);

        assertThat(ran.get()).as("node-stopping half: closes the gate while the engine is still alive").isZero();
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    private static void drain(ReactExecutor executor) throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
