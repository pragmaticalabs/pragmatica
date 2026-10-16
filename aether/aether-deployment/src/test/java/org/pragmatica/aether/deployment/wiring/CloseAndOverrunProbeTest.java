package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// Close and overrun pins (design 4.3, 7; round 2 of #2200, from the verifier's probes): an input offered after `close()` began is never
/// stepped and is counted `result=closed`, a `close()` whose wait expires still shuts down and says so, and a bounded op also arms the
/// overrun watch.
class CloseAndOverrunProbeTest {
    /// A runtime over the real FsmMailbox/FsmTimers/FsmRuntime that records what it steps and counts.
    static final class Probe extends FsmRuntime<String, String, Unit> {
        final List<String> steps = Collections.synchronizedList(new ArrayList<>());
        final List<String> counts = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch blocking = new CountDownLatch(1);
        volatile boolean closingRan;
        final java.util.concurrent.atomic.AtomicInteger closings = new java.util.concurrent.atomic.AtomicInteger();

        Probe(FsmTimers.TimerService clock) {
            super("deploy-fsm-probe", clock);
        }

        @Override
        Unit step(String input) {
            if (input.equals("block")) {
                blocking.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            steps.add((closingRan ? "after-close:" : "") + input);
            return Unit.unit();
        }

        @Override
        Unit entry(boolean reconcile) {
            return Unit.unit();
        }

        @Override
        Unit fired(String id, Unit attachment) {
            return step("tick:" + id);
        }

        @Override
        Unit count(String input, String result) {
            counts.add(input + ":" + result);
            return Unit.unit();
        }

        @Override
        Unit closing() {
            closingRan = true;
            closings.incrementAndGet();
            return Unit.unit();
        }
    }

    /// Design 7 (2c): Close; any later offer is discarded. An input offered after `close()` began must never be stepped after the
    /// runtime's shutdown (timers cancelled, local runtime closed).
    @RepeatedTest(20)
    void closeWindow_anInputOfferedAfterCloseBegan_isNeverStepped() throws Exception {
        var probe = new Probe(new ManualTimerService());

        probe.start();
        probe.deliver("block");
        assertThat(probe.blocking.await(5, TimeUnit.SECONDS)).isTrue();

        var closer = Thread.ofPlatform().start(probe::close);

        await().atMost(Duration.ofSeconds(5)).until(() -> probe.mailboxDepth() == 1); // the Close marker is queued
        var accepted = probe.deliver("late");
        probe.release.countDown();
        closer.join(15_000);

        assertThat(probe.steps).as("accepted=" + accepted + "; nothing is stepped after the shutdown ran").doesNotContain("after-close:late");
    }

    /// Design 7 (2c): "Any later offer is counted result=closed and discarded."
    @Test
    void offerAfterClose_isCountedClosed() {
        var probe = new Probe(new ManualTimerService());

        probe.start();
        probe.close();
        var accepted = probe.deliver("late");

        assertThat(accepted).isFalse();
        assertThat(probe.counts).as("design 7 2c: result=closed").anyMatch(c -> c.endsWith(":closed"));
    }

    /// Close's own wait is bounded (5 s); when it expires with the consumer busy, the shutdown (timer cancel, local-runtime close)
    /// must still happen, or be reported.
    @Test
    void closeWhoseWaitExpires_stillRunsTheShutdown() throws Exception {
        var clock = new ManualTimerService();
        var probe = new Probe(clock);

        probe.start();
        probe.deliver("block");
        assertThat(probe.blocking.await(5, TimeUnit.SECONDS)).isTrue();

        probe.close(); // waits 5 s for the marker, then 5 s for the join
        probe.release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> probe.closingRan);
        assertThat(probe.closingRan).as("the shutdown ran once the stuck input returned").isTrue();
    }

    /// Design 4.3: "`deployment-localop-overrun` is logged once an op exceeds boundOf(step). That bound applies to timeoutMs = 0 ops
    /// as well" -- i.e. to every op, so a bounded op whose body hangs past its timeout (holding its lane) is still reported.
    @Test
    void aBoundedOp_alsoArmsTheOverrunWatch() {
        var clock = new ManualTimerService();
        var timings = NodeFsmTimings.nodeFsmTimings();
        var started = Collections.synchronizedList(new ArrayList<Promise<LocalOutcome>>());
        var executor = LocalOpExecutor.localOpExecutor(op -> {
            var promise = Promise.<LocalOutcome>promise();
            started.add(promise);
            return promise;
        }, clock, timings, LocalOpExecutor.Counter.NONE);

        executor.run(new NodeFsmEffect.LocalOp(1L, 10L, Option.some(ORDERS), LocalStep.LOAD, 1_000L, Option.none()), () -> 1L, _ -> {});
        await().atMost(Duration.ofSeconds(5)).until(() -> started.size() == 1);

        assertThat(clock.armed().stream().map(entry -> entry.delayMs)).as("timeout 1000 armed AND the overrun watch at boundOf(LOAD)")
                                                                     .contains(1_000L, timings.boundOf(LocalStep.LOAD));
    }

    @Test
    void aTimerTickAfterClose_isCountedClosed() {
        var clock = new ManualTimerService();
        var probe = new Probe(clock);

        probe.start();
        probe.close();
        probe.timers().schedule("late", Unit.unit(), 10);
        clock.advance(10);

        assertThat(probe.counts).as("the tick's refused offer is counted too").contains("Timer:closed");
    }

    @Test
    void aNormalClose_shutsDownExactlyOnce() {
        var probe = new Probe(new ManualTimerService());

        probe.start();
        probe.close();

        assertThat(probe.closings.get()).isEqualTo(1);
    }

    /// F-3: the wait expires with the consumer inside an input; WARN `deployment-fsm-stop-timeout` is logged, the shutdown runs when the input
    /// returns, and it runs once although the Close item and the exit hook both reach it.
    @Test
    void closeWhoseWaitExpires_warns_andShutsDownOnce() throws Exception {
        try (var logs = new LogCapture(FsmRuntime.class.getName())) {
            var probe = new Probe(new ManualTimerService());

            probe.start();
            probe.deliver("block");
            assertThat(probe.blocking.await(5, TimeUnit.SECONDS)).isTrue();

            var closer = Thread.ofPlatform().start(probe::close);

            await().atMost(Duration.ofSeconds(15)).until(() -> logs.lines().stream().anyMatch(line -> line.message().contains("deployment-fsm-stop-timeout")));
            probe.release.countDown();
            closer.join(15_000);
            await().atMost(Duration.ofSeconds(5)).until(() -> probe.closingRan);
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> probe.closings.get() == 1);

            assertThat(logs.lines()).anySatisfy(line -> assertThat(line.level()).isEqualTo(org.apache.logging.log4j.Level.WARN));
        }
    }

    /// F-4: the overrun is reported for a bounded op whose body outlives its timeout, and both clocks are stopped when the body returns.
    @Test
    void aBoundedOpWhoseBodyOutlivesItsTimeout_isReportedOverrunning_andBothClocksStopWhenItReturns() {
        try (var logs = new LogCapture(LocalOpExecutor.class.getName())) {
            var clock = new ManualTimerService();
            var timings = NodeFsmTimings.nodeFsmTimings();
            var started = Collections.synchronizedList(new ArrayList<Promise<LocalOutcome>>());
            var settled = Collections.synchronizedList(new ArrayList<Object>());
            var executor = LocalOpExecutor.localOpExecutor(op -> {
                var promise = Promise.<LocalOutcome>promise();
                started.add(promise);
                return promise;
            }, clock, timings, LocalOpExecutor.Counter.NONE);

            executor.run(new NodeFsmEffect.LocalOp(1L, 10L, Option.some(ORDERS), LocalStep.LOAD, 1_000L, Option.none()), () -> 1L, settled::add);
            await().atMost(Duration.ofSeconds(5)).until(() -> started.size() == 1);

            clock.advance(1_000);
            await().atMost(Duration.ofSeconds(5)).until(() -> settled.size() == 1);
            assertThat(logs.lines()).as("not yet past the step bound").noneMatch(line -> line.message().contains("deployment-localop-overrun"));

            clock.advance(timings.boundOf(LocalStep.LOAD));
            await().atMost(Duration.ofSeconds(5)).until(() -> logs.lines().stream().anyMatch(line -> line.message().contains("deployment-localop-overrun")));

            started.getFirst().succeed(LocalOutcome.done());
            await().atMost(Duration.ofSeconds(5)).until(() -> clock.armedCount() == 0);
        }
    }

    @Test
    void aBoundedOpThatReturnsInTime_stopsBothClocks() {
        var clock = new ManualTimerService();
        var started = Collections.synchronizedList(new ArrayList<Promise<LocalOutcome>>());
        var executor = LocalOpExecutor.localOpExecutor(op -> {
            var promise = Promise.<LocalOutcome>promise();
            started.add(promise);
            return promise;
        }, clock, NodeFsmTimings.nodeFsmTimings(), LocalOpExecutor.Counter.NONE);

        executor.run(new NodeFsmEffect.LocalOp(1L, 10L, Option.some(ORDERS), LocalStep.LOAD, 1_000L, Option.none()), () -> 1L, _ -> {});
        await().atMost(Duration.ofSeconds(5)).until(() -> started.size() == 1);
        assertThat(clock.armedCount()).isEqualTo(2);

        started.getFirst().succeed(LocalOutcome.done());

        await().atMost(Duration.ofSeconds(5)).until(() -> clock.armedCount() == 0);
    }

    /// Design 7 (2c), the KV producer: a KV-sourced offer after Close is counted `result=closed` under its input's name.
    @Test
    void aKvOfferAfterClose_isCountedClosed() {
        var probe = new Probe(new ManualTimerService());

        probe.start();
        probe.close();

        assertThat(probe.deliverKv("late", 5L, false)).isFalse();
        assertThat(probe.counts).contains("String:closed");
    }

    /// Design 7 (2c), the entry driver: an entry request after Close is counted `result=closed`.
    @Test
    void anEntryRequestAfterClose_isCountedClosed() {
        var probe = new Probe(new ManualTimerService());

        probe.start();
        probe.close();

        assertThat(probe.requestEntry(false)).isFalse();
        assertThat(probe.counts).contains("EnterRequested:closed");
    }

    /// An unbounded op that returns stops its overrun watch, so no `deployment-localop-overrun` is logged for an op that finished.
    @Test
    void anUnboundedOpThatReturns_stopsItsOverrunWatch() {
        var clock = new ManualTimerService();
        var started = Collections.synchronizedList(new ArrayList<Promise<LocalOutcome>>());
        var executor = LocalOpExecutor.localOpExecutor(op -> {
            var promise = Promise.<LocalOutcome>promise();
            started.add(promise);
            return promise;
        }, clock, NodeFsmTimings.nodeFsmTimings(), LocalOpExecutor.Counter.NONE);

        executor.run(new NodeFsmEffect.LocalOp(1L, 10L, Option.some(ORDERS), LocalStep.LOAD, 0L, Option.none()), () -> 1L, _ -> {});
        await().atMost(Duration.ofSeconds(5)).until(() -> started.size() == 1);
        assertThat(clock.armedCount()).isEqualTo(1);

        started.getFirst().succeed(LocalOutcome.done());

        await().atMost(Duration.ofSeconds(5)).until(() -> clock.armedCount() == 0);
    }

    /// N-7: a second `close()` after a first whose wait expired must not run the shutdown on its own (caller) thread while the consumer is alive.
    @Test
    void aSecondCloseAfterAnExpiredWait_neverShutsDownOnTheCaller_whileTheConsumerIsAlive() throws Exception {
        var probe = new Probe(new ManualTimerService());

        probe.start();
        probe.deliver("block");
        assertThat(probe.blocking.await(5, TimeUnit.SECONDS)).isTrue();

        var first = Thread.ofPlatform().start(probe::close);

        first.join(20_000);
        assertThat(first.isAlive()).as("the first close returned after its bounded wait").isFalse();
        assertThat(probe.closingRan).as("the consumer is still inside its input").isFalse();

        var second = Thread.ofPlatform().start(probe::close);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> !probe.closingRan);
        assertThat(probe.closings.get()).as("nothing shut down on the second caller").isZero();

        probe.release.countDown();
        second.join(3_000);

        assertThat(second.isAlive()).as("released by the shutdown the exiting consumer ran, not by the timeout").isFalse();
        await().atMost(Duration.ofSeconds(5)).until(() -> probe.closings.get() == 1);
    }

    /// N-8: two concurrent `close()` calls share one completion: both return once the consumer reaches Close, neither waits out the timeout,
    /// and no stop timeout is logged.
    @Test
    void concurrentCloses_shareOneCompletion_andLogNoFalseStopTimeout() throws Exception {
        try (var logs = new LogCapture(FsmRuntime.class.getName())) {
            var probe = new Probe(new ManualTimerService());

            probe.start();
            probe.deliver("block");
            assertThat(probe.blocking.await(5, TimeUnit.SECONDS)).isTrue();

            var first = Thread.ofPlatform().start(probe::close);
            var second = Thread.ofPlatform().start(probe::close);

            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(() -> first.isAlive() && second.isAlive());
            probe.release.countDown();
            first.join(3_000);
            second.join(3_000);

            assertThat(first.isAlive()).as("the first returned without waiting out the timeout").isFalse();
            assertThat(second.isAlive()).as("so did the second").isFalse();
            assertThat(logs.lines()).noneMatch(line -> line.message().contains("deployment-fsm-stop-timeout"));
            assertThat(probe.closings.get()).isEqualTo(1);
        }
    }

    /// N-9: one `close()` of a started runtime is released by the consumer's own shutdown, within its wait, and says nothing about a stop timeout.
    @Test
    void aLoneClose_releasesWithinItsWait_andLogsNoStopTimeout() {
        try (var logs = new LogCapture(FsmRuntime.class.getName())) {
            var probe = new Probe(new ManualTimerService());

            probe.start();
            var started = System.nanoTime();

            probe.close();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("released by the consumer, not by the 5 s wait").isLessThan(Duration.ofSeconds(3));
            assertThat(logs.lines()).noneMatch(line -> line.message().contains("deployment-fsm-stop-timeout"));
            assertThat(probe.closings.get()).isEqualTo(1);
        }
    }

    /// N-10: `close()` decides atomically with `start()` whether a consumer exists. Once it has judged there is none, a later `start()` creates
    /// none, so the shutdown the caller then runs cannot overlap a consumer that appears after the judgement. The interleaving itself (a thread
    /// created but not yet started) cannot be forced from outside; the guard that closes it is the `retired` bar and the shared monitor.
    @Test
    void retireIfConsumerless_barsALaterStart_andReportsALiveConsumerAsPresent() throws Exception {
        var neverStarted = FsmMailbox.<String>fsmMailbox("deploy-fsm-retire", _ -> {});

        assertThat(neverStarted.retireIfConsumerless()).as("no consumer exists").isTrue();
        neverStarted.start();
        assertThat(neverStarted.running()).as("a retired mailbox does not grow a consumer").isFalse();

        var started = FsmMailbox.<String>fsmMailbox("deploy-fsm-retire-alive", _ -> {});

        started.start();
        assertThat(started.retireIfConsumerless()).as("a live consumer is not retired").isFalse();
        started.close();
        assertThat(started.retireIfConsumerless()).as("an exited consumer needs no one").isTrue();
    }
}
