// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for ConsensusDrainCoordinator covering the happy path (inflight drains to
/// zero within budget), step-by-step verification (DRAINING then DECOMMISSIONED written),
/// and timeout (budget exceeded → failure).
class ConsensusDrainCoordinatorTest {
    private static final NodeId TARGET = nodeId("target-node").unwrap();

    private RecordingLifecycleWriter lifecycleWriter;
    private AtomicReference<NodeLifecycleValue> lifecycleAtom;
    private AtomicInteger inflightCounter;

    @BeforeEach
    void setUp() {
        lifecycleAtom = new AtomicReference<>(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
        lifecycleWriter = new RecordingLifecycleWriter(lifecycleAtom);
        inflightCounter = new AtomicInteger(0);
    }

    private ConsensusDrainCoordinator coordinator() {
        return ConsensusDrainCoordinator.consensusDrainCoordinator(
                lifecycleWriter,
                nodeId -> Option.some(lifecycleAtom.get()),
                nodeId -> Promise.success(inflightCounter.get()));
    }

    @Nested class PrepareDrain {
        @Test
        void prepareDrain_writesDrainingAtom_viaLifecycleWriter() {
            var result = coordinator().prepareDrain(TARGET, DrainReason.OPERATOR_DRAIN).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(lifecycleWriter.drainCount.get()).isEqualTo(1);
            assertThat(lifecycleAtom.get().state()).isEqualTo(NodeLifecycleState.DRAINING);
        }
    }

    @Nested class AwaitDrainAck {
        @Test
        void awaitDrainAck_succeeds_whenInflightZeroAndDraining() {
            // Pre-condition: lifecycle already DRAINING, inflight=0
            lifecycleAtom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING));
            inflightCounter.set(0);

            var result = coordinator().awaitDrainAck(TARGET, timeSpan(2).seconds()).await(timeSpan(3).seconds());

            assertThat(result.isSuccess())
                    .as("await must succeed when target is draining and inflight is 0")
                    .isTrue();
        }

        @Test
        void awaitDrainAck_blocksUntilInflightReachesZero() throws InterruptedException {
            lifecycleAtom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING));
            inflightCounter.set(3);

            var promise = coordinator().awaitDrainAck(TARGET, timeSpan(3).seconds());

            // Drain to zero after a short delay
            new Thread(() -> {
                sleepQuietly(400L);
                inflightCounter.set(0);
            }).start();

            var result = promise.await(timeSpan(3).seconds());
            assertThat(result.isSuccess())
                    .as("await must complete once inflight reaches 0")
                    .isTrue();
        }

        @Test
        void awaitDrainAck_failsWithTimeout_whenInflightNeverDrains() {
            lifecycleAtom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING));
            inflightCounter.set(5); // never drains

            var result = coordinator().awaitDrainAck(TARGET, timeSpan(500).millis()).await(timeSpan(2).seconds());

            assertThat(result.isFailure())
                    .as("await must fail when inflight never drains within budget")
                    .isTrue();
        }

        @Test
        void awaitDrainAck_failsWithTimeout_whenLifecycleNeverDraining() {
            // Lifecycle stays ON_DUTY (never gets to DRAINING) — quiescence never reached
            lifecycleAtom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
            inflightCounter.set(0);

            var result = coordinator().awaitDrainAck(TARGET, timeSpan(400).millis()).await(timeSpan(2).seconds());

            assertThat(result.isFailure())
                    .as("await must fail if lifecycle is not observably DRAINING within budget")
                    .isTrue();
        }
    }

    @Nested class MarkDrainComplete {
        @Test
        void markDrainComplete_writesDecommissioned_viaLifecycleWriter() {
            lifecycleAtom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING));

            coordinator().markDrainComplete(TARGET);

            // Allow the async write to settle
            sleepQuietly(200L);
            assertThat(lifecycleWriter.decommissionCount.get()).isEqualTo(1);
            assertThat(lifecycleAtom.get().state()).isEqualTo(NodeLifecycleState.STOPPED);
        }
    }

    @Nested class HappyPath {
        @Test
        void fullProtocol_drainSucceeds_thenMarkComplete_writesAllAtomsInOrder() {
            inflightCounter.set(0);
            var c = coordinator();

            var prepareResult = c.prepareDrain(TARGET, DrainReason.SCALE_DOWN).await(timeSpan(1).seconds());
            assertThat(prepareResult.isSuccess()).isTrue();
            assertThat(lifecycleAtom.get().state()).isEqualTo(NodeLifecycleState.DRAINING);

            var ackResult = c.awaitDrainAck(TARGET, timeSpan(2).seconds()).await(timeSpan(3).seconds());
            assertThat(ackResult.isSuccess()).isTrue();

            c.markDrainComplete(TARGET);
            sleepQuietly(200L);
            assertThat(lifecycleAtom.get().state()).isEqualTo(NodeLifecycleState.STOPPED);
            assertThat(lifecycleWriter.drainCount.get()).isEqualTo(1);
            assertThat(lifecycleWriter.decommissionCount.get()).isEqualTo(1);
        }
    }

    @Nested class TimeoutPath {
        @Test
        void fullProtocol_drainAckTimeout_lifecycleStillDraining() {
            inflightCounter.set(10); // never drains

            var c = coordinator();
            c.prepareDrain(TARGET, DrainReason.SCALE_DOWN).await(timeSpan(1).seconds())
                                                          .onFailure(_ -> Assertions.fail("prepareDrain should succeed"));

            var ackResult = c.awaitDrainAck(TARGET, timeSpan(400).millis()).await(timeSpan(2).seconds());
            assertThat(ackResult.isFailure())
                    .as("awaitDrainAck must fail when inflight does not drain")
                    .isTrue();
            // markDrainComplete should NOT be called by the caller on timeout — they record FAILED_DRAIN instead.
            assertThat(lifecycleWriter.decommissionCount.get())
                    .as("DECOMMISSIONED must not be written on timeout")
                    .isZero();
            assertThat(lifecycleAtom.get().state())
                    .as("Lifecycle remains DRAINING on timeout (operator records FAILED_DRAIN out-of-band)")
                    .isEqualTo(NodeLifecycleState.DRAINING);
        }
    }

    private static void sleepQuietly(long millis) {
        try {Thread.sleep(millis);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    }

    /// Test-only LifecycleWriter that mirrors the production MembershipFsm atom
    /// transitions into a single AtomicReference for assertions.
    private static final class RecordingLifecycleWriter implements LifecycleWriter {
        final AtomicInteger drainCount = new AtomicInteger();
        final AtomicInteger decommissionCount = new AtomicInteger();
        final AtomicInteger activateCount = new AtomicInteger();
        final AtomicInteger failedDrainCount = new AtomicInteger();
        private final AtomicReference<NodeLifecycleValue> atom;

        RecordingLifecycleWriter(AtomicReference<NodeLifecycleValue> atom) {
            this.atom = atom;
        }

        @Override public Promise<Unit> requestDrain(NodeId target) {
            drainCount.incrementAndGet();
            atom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING));
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> requestDecommission(NodeId target) {
            decommissionCount.incrementAndGet();
            atom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED));
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> requestActivate(NodeId target) {
            activateCount.incrementAndGet();
            atom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY));
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> requestFailedDrain(NodeId target) {
            failedDrainCount.incrementAndGet();
            atom.set(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED));
            return Promise.unitPromise();
        }
    }
}
