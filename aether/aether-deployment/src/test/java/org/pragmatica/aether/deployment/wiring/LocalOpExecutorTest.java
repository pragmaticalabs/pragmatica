// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// W-09 (a)-(f) of the phase-3 design 9.2 and MT-09a/b/c/d: the executor runs each artifact's ops in order, one at a time; discards a queued
/// chain op of a dropped incarnation; starts an op's timeout clock when the op starts; and undoes a stale publisher at the head of the lane.
class LocalOpExecutorTest {
    private record Pending(NodeFsmEffect.LocalOp op, Promise<LocalOutcome> promise) {}

    private final ManualTimerService clock = new ManualTimerService();
    private final List<Pending> started = Collections.synchronizedList(new ArrayList<>());
    private final List<NodeFsmInput.LocalOpSettled> settlements = Collections.synchronizedList(new ArrayList<>());
    private final List<String> counted = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong incarnation = new AtomicLong(1);
    private final NodeFsmTimings timings = NodeFsmTimings.nodeFsmTimings();
    private final LocalOpExecutor executor = LocalOpExecutor.localOpExecutor(op -> {
        var promise = Promise.<LocalOutcome>promise();

        started.add(new Pending(op, promise));

        return promise;
    }, clock, timings, (step, result) -> counted.add(step + ":" + result));
    private long nextToken = 10;

    private NodeFsmEffect.LocalOp op(Artifact artifact, LocalStep step, long inc, long timeoutMs) {
        return new NodeFsmEffect.LocalOp(inc, step == LocalStep.SUSPEND || step == LocalStep.UNPUBLISH || step == LocalStep.EVICT ? 0L : nextToken++, Option.some(artifact), step, timeoutMs, Option.none());
    }

    private void run(NodeFsmEffect.LocalOp op) {
        executor.run(op, incarnation::get, settlements::add);
    }

    private List<String> startedSteps() {
        synchronized (started) {
            return started.stream().map(p -> p.op().step() + "@" + p.op().incarnation()).toList();
        }
    }

    private void awaitStarted(int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> started.size() >= count);
    }

    private void complete(int index) {
        started.get(index).promise().succeed(LocalOutcome.done());
    }

    @Test
    void opsOfOneArtifact_runOneAtATime_inOrder() {
        run(op(ORDERS, LocalStep.LOAD, 1, 0));
        run(op(ORDERS, LocalStep.ACTIVATE, 1, 0));
        run(op(ORDERS, LocalStep.REGISTER_BRIDGE, 1, 0));
        awaitStarted(1);

        assertThat(startedSteps()).as("MT-09a: unserialised, all three would be running").containsExactly("LOAD@1");

        complete(0);
        awaitStarted(2);
        assertThat(startedSteps()).containsExactly("LOAD@1", "ACTIVATE@1");
        complete(1);
        awaitStarted(3);
        assertThat(startedSteps()).containsExactly("LOAD@1", "ACTIVATE@1", "REGISTER_BRIDGE@1");
    }

    @Test
    void opsOfDifferentArtifacts_runConcurrently() {
        run(op(ORDERS, LocalStep.LOAD, 1, 0));
        run(op(BILLING, LocalStep.LOAD, 1, 0));

        awaitStarted(2);

        assertThat(startedSteps()).as("a hung op stalls only its own artifact").hasSize(2);
    }

    /// H3b: a quick flap. The old incarnation's SUSPEND is still running when the new incarnation's reactivation op is queued.
    @Test
    void aReactivationOp_waitsForTheOldIncarnationsSuspend() {
        run(op(ORDERS, LocalStep.SUSPEND, 1, 0));
        awaitStarted(1);
        incarnation.set(2);
        run(op(ORDERS, LocalStep.REGISTER_BRIDGE, 2, 0));

        assertThat(startedSteps()).containsExactly("SUSPEND@1");

        complete(0);
        awaitStarted(2);

        assertThat(startedSteps()).containsExactly("SUSPEND@1", "REGISTER_BRIDGE@2");
    }

    /// H3c, MT-09b: a queued chain op of a dropped incarnation is discarded unrun; a token-0 teardown op still runs.
    @Test
    void aQueuedStaleChainOp_isDroppedUnrun_aTeardownOpStillRuns() {
        run(op(ORDERS, LocalStep.LOAD, 1, 0));
        awaitStarted(1);
        run(op(ORDERS, LocalStep.ACTIVATE, 1, 0));
        run(op(ORDERS, LocalStep.EVICT, 1, 0));
        incarnation.set(2);

        complete(0);
        awaitStarted(2);

        assertThat(startedSteps()).as("ACTIVATE of incarnation 1 never ran; EVICT did").containsExactly("LOAD@1", "EVICT@1");
        assertThat(counted).contains("ACTIVATE:dropped_stale");
    }

    /// H3c, MT-09c: the timeout of an op is measured from its start.
    @Test
    void theTimeoutClock_startsWhenTheOpStarts_notWhenItIsQueued() {
        run(op(ORDERS, LocalStep.LOAD, 1, 0));
        awaitStarted(1);
        run(op(ORDERS, LocalStep.ACTIVATE, 1, 100));

        assertThat(clock.armed().stream().map(entry -> entry.delayMs)).as("no timeout is armed for an op that has not started").doesNotContain(100L);

        clock.advance(10_000);
        assertThat(settlements).as("the queued op did not time out while it waited").isEmpty();

        complete(0);
        awaitStarted(2);
        assertThat(clock.armed().stream().map(entry -> entry.delayMs)).contains(100L);

        clock.advance(100);
        await().atMost(Duration.ofSeconds(5)).until(() -> settlements.stream().anyMatch(s -> s.outcome() instanceof LocalOutcome.TimedOut));
        assertThat(settlements.stream().filter(s -> s.outcome() instanceof LocalOutcome.TimedOut)).hasSize(1);
    }

    @Test
    void anOpThatTimedOut_keepsItsLaneBusyUntilItsBodyReturns_andSettlesOnce() {
        run(op(ORDERS, LocalStep.LOAD, 1, 50));
        awaitStarted(1);
        run(op(ORDERS, LocalStep.ACTIVATE, 1, 0));

        clock.advance(50);
        await().atMost(Duration.ofSeconds(5)).until(() -> settlements.size() == 1);

        assertThat(startedSteps()).as("the next op waits for the hung body").containsExactly("LOAD@1");

        complete(0);
        awaitStarted(2);

        assertThat(settlements).as("the late body does not settle a second time").hasSize(1);
        assertThat(settlements.getFirst().outcome()).isInstanceOf(LocalOutcome.TimedOut.class);
    }

    /// MQ-3 and D-2, W-09 (f): k is running PUBLISH_ROUTES; the node flaps; k+1 queues its own PUBLISH_ROUTES; k's op completes.
    @Test
    void aStalePublisher_isUnpublishedAtTheHeadOfTheLane_beforeTheNewIncarnationsPublish() {
        run(op(ORDERS, LocalStep.PUBLISH_ROUTES, 1, 0));
        awaitStarted(1);
        incarnation.set(2);
        run(op(ORDERS, LocalStep.PUBLISH_ROUTES, 2, 0));

        complete(0);
        awaitStarted(2);

        assertThat(startedSteps()).as("MT-09d: appended at the tail it would run after k+1's publish").containsExactly("PUBLISH_ROUTES@1", "UNPUBLISH@1");
        assertThat(counted).contains("PUBLISH_ROUTES:compensated");

        complete(1);
        awaitStarted(3);
        assertThat(startedSteps()).containsExactly("PUBLISH_ROUTES@1", "UNPUBLISH@1", "PUBLISH_ROUTES@2");
    }

    @Test
    void everyPublishingStep_isCompensated_whenStale_andNoOtherStepIs() {
        for (var step : List.of(LocalStep.REGISTER_BRIDGE, LocalStep.REGISTER_CONFIG, LocalStep.PUBLISH_ROUTES)) {
            started.clear();
            incarnation.set(1);
            run(op(AUDIT, step, 1, 0));
            awaitStarted(1);
            incarnation.set(2);
            complete(0);
            awaitStarted(2);

            assertThat(startedSteps()).as(step.name()).containsExactly(step + "@1", "UNPUBLISH@1");
            complete(1);
            await().atMost(Duration.ofSeconds(5)).until(() -> counted.stream().filter(c -> c.equals("UNPUBLISH:done")).count() >= 1);
            counted.clear();
        }

        started.clear();
        incarnation.set(1);
        run(op(AUDIT, LocalStep.LOAD, 1, 0));
        awaitStarted(1);
        incarnation.set(2);
        complete(0);

        await().atMost(Duration.ofSeconds(5)).until(() -> counted.contains("LOAD:done"));
        assertThat(startedSteps()).as("a stale LOAD publishes nothing, so nothing is undone").containsExactly("LOAD@1");
    }

    @Test
    void aCurrentPublisher_isNotCompensated() {
        run(op(ORDERS, LocalStep.PUBLISH_ROUTES, 1, 0));
        awaitStarted(1);

        complete(0);
        await().atMost(Duration.ofSeconds(5)).until(() -> counted.contains("PUBLISH_ROUTES:done"));

        assertThat(startedSteps()).containsExactly("PUBLISH_ROUTES@1");
    }

    @Test
    void aSettlement_echoesIncarnationArtifactAndToken_andATokenZeroOpSettlesNothing() {
        var chain = op(ORDERS, LocalStep.LOAD, 1, 0);

        run(chain);
        run(op(BILLING, LocalStep.SUSPEND, 1, 0));
        awaitStarted(2);
        complete(0);
        complete(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> settlements.size() >= 1);
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> true);

        assertThat(settlements).hasSize(1);
        assertThat(settlements.getFirst().incarnation()).isEqualTo(1);
        assertThat(settlements.getFirst().artifact()).isEqualTo(ORDERS);
        assertThat(settlements.getFirst().token()).isEqualTo(chain.token());
    }

    @Test
    void aFailingStep_isReportedFailedWithItsCause_andTheLaneContinues() {
        var failing = LocalOpExecutor.localOpExecutor(op -> op.step() == LocalStep.LOAD
                                                             ? Causes.cause("disk full").promise()
                                                             : Promise.success(LocalOutcome.done()),
                                                      clock,
                                                      timings,
                                                      LocalOpExecutor.Counter.NONE);

        failing.run(op(ORDERS, LocalStep.LOAD, 1, 0), incarnation::get, settlements::add);
        failing.run(op(ORDERS, LocalStep.ACTIVATE, 1, 0), incarnation::get, settlements::add);

        await().atMost(Duration.ofSeconds(5)).until(() -> settlements.size() == 2);
        assertThat(((LocalOutcome.Failed) settlements.get(0).outcome()).cause().message()).isEqualTo("disk full");
        assertThat(settlements.get(1).outcome()).isInstanceOf(LocalOutcome.Done.class);
    }

    @Test
    void aStepThatThrows_isReportedFailed() {
        var throwing = LocalOpExecutor.localOpExecutor(op -> {
            throw new IllegalStateException("boom");
        }, clock, timings, LocalOpExecutor.Counter.NONE);

        throwing.run(op(ORDERS, LocalStep.LOAD, 1, 0), incarnation::get, settlements::add);

        await().atMost(Duration.ofSeconds(5)).until(() -> settlements.size() == 1);
        assertThat(settlements.getFirst().outcome()).isInstanceOf(LocalOutcome.Failed.class);
    }

    @Test
    void anUnboundedOp_armsTheOverrunWatch_atItsStepBound() {
        run(op(ORDERS, LocalStep.LOAD, 1, 0));
        awaitStarted(1);

        assertThat(clock.armed().stream().map(entry -> entry.delayMs)).contains(timings.boundOf(LocalStep.LOAD));
    }

    @Test
    void close_dropsQueuedOps_andDeliversNothingAfterwards() {
        run(op(ORDERS, LocalStep.LOAD, 1, 0));
        awaitStarted(1);
        run(op(ORDERS, LocalStep.ACTIVATE, 1, 0));

        executor.close();
        complete(0);
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> true);

        assertThat(startedSteps()).containsExactly("LOAD@1");
        assertThat(settlements).isEmpty();
        run(op(BILLING, LocalStep.LOAD, 1, 0));
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(() -> true);
        assertThat(startedSteps()).as("a closed executor accepts nothing").containsExactly("LOAD@1");
    }

    // -- idle (design 7, 2a: the drain of a node's stop waits for the SUSPENDs) ---------------------------------------------------------
    @Test
    void idle_isResolvedAtOnce_whenNothingIsQueuedOrRunning() {
        assertThat(executor.idle().isResolved()).isTrue();
    }

    @Test
    void idle_staysPending_whileAnOpRuns_andResolvesWhenItEnds() {
        run(op(ORDERS, LocalStep.SUSPEND, 1, 0));
        awaitStarted(1);

        var idle = executor.idle();

        assertThat(idle.isResolved()).as("MT-15: a drain that does not wait for the running SUSPEND").isFalse();

        complete(0);

        await().atMost(Duration.ofSeconds(5)).until(idle::isResolved);
    }

    @Test
    void idle_coversTheOpsQueuedBehindTheRunningOne_notOnlyTheRunningOne() {
        run(op(ORDERS, LocalStep.SUSPEND, 1, 0));
        awaitStarted(1);
        run(op(ORDERS, LocalStep.EVICT, 1, 0));
        run(op(BILLING, LocalStep.SUSPEND, 1, 0));
        awaitStarted(2);

        var idle = executor.idle();

        complete(0);
        awaitStarted(3);
        assertThat(idle.isResolved()).as("the lane still holds a queued op, and BILLING's SUSPEND is running").isFalse();

        complete(1);
        assertThat(idle.isResolved()).as("ORDERS' EVICT is running").isFalse();

        complete(2);
        await().atMost(Duration.ofSeconds(5)).until(idle::isResolved);
    }

    /// MQ-3: the compensating UNPUBLISH of a stale publisher is part of what the drain waits for.
    @Test
    void idle_coversTheCompensatingUnpublishOfAStalePublisher() {
        run(op(ORDERS, LocalStep.PUBLISH_ROUTES, 1, 0));
        awaitStarted(1);
        incarnation.set(2);

        var idle = executor.idle();

        complete(0);
        awaitStarted(2);

        assertThat(startedSteps()).containsExactly("PUBLISH_ROUTES@1", "UNPUBLISH@1");
        assertThat(idle.isResolved()).as("the compensation runs after the stale publisher").isFalse();

        complete(1);
        await().atMost(Duration.ofSeconds(5)).until(idle::isResolved);
    }

    @Test
    void idle_isReleasedByClose_andARunningOpDoesNotHoldItAfterwards() {
        run(op(ORDERS, LocalStep.SUSPEND, 1, 0));
        awaitStarted(1);

        var idle = executor.idle();

        executor.close();

        await().atMost(Duration.ofSeconds(5)).until(idle::isResolved);
        assertThat(executor.idle().isResolved()).isTrue();
    }

    @Test
    void idle_waiters_areAllReleased_whenTheLastOpEnds() {
        run(op(ORDERS, LocalStep.SUSPEND, 1, 0));
        awaitStarted(1);

        var first = executor.idle();
        var second = executor.idle();

        complete(0);

        await().atMost(Duration.ofSeconds(5)).until(() -> first.isResolved() && second.isResolved());
    }
}
