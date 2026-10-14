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
}
