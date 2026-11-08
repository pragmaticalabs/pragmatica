// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;

/// #2875: the yield path of a drain pass that finds the refusal hold standing, driven deterministically (manual clock,
/// captured scheduler, the private `drainPass` / `yieldToBackoff` reached by reflection). Pins the two lines the stress test in
/// `RefusalBackoffWiringTest` cannot: the yield releases `running` (`finishDrain`) and re-requests the drain (`requestDrain`),
/// so a hold that ended while the yield still owned the loop is not a lost wake-up.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ConsumerHoldYieldTest {
    private static final String STREAM = "orders";
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Long> delays = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Runnable> scheduled = new LinkedBlockingQueue<>();
    private final AtomicInteger reads = new AtomicInteger();
    private StreamPartitionManager manager;
    private ConsumerRuntimeState runtime;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1_000_000, 64 * 1024 * 1024, 60_000), "earliest"));
        runtime = new ConsumerRuntimeState(manager, DeadLetterHandler.deadLetterHandler(), none(), none(),
                                           (_, _, _, _) -> read(),
                                           ConsumerRuntimeState.DEAD_LETTER_APPEND_TIMEOUT,
                                           clock::get,
                                           (task, delay) -> { delays.add(delay.millis()); scheduled.add(task); });
    }

    @AfterEach
    void tearDown() { runtime.close(); manager.close(); }

    private Promise<List<OffHeapRingBuffer.RawEvent>> read() {
        if (reads.incrementAndGet() == 1) {
            return StreamForwardError.ForwardNotSent.FACTORY.apply(new WriteOutcome.BackpressureRefused(new NodeId("owner"))).promise();
        }
        return Promise.success(List.of());
    }

    private Object[] parked() throws Exception {
        runtime.subscribe(STREAM, 0, ConsumerConfig.consumerConfig("group-1"), (_, _, _) -> Promise.unitPromise());
        await(() -> delays.size() >= 1, "first park");
        Thread.sleep(200);
        assertThat(reads.get()).isEqualTo(1);
        var f = ConsumerRuntimeState.class.getDeclaredField("consumers");
        f.setAccessible(true);
        var map = (ConcurrentHashMap<?, ?>) f.get(runtime);
        assertThat(map).hasSize(1);
        var e = map.entrySet().iterator().next();
        return new Object[]{e.getKey(), e.getValue()};
    }

    private Object call(String name, Object key, Object state) throws Exception {
        for (Method m : ConsumerRuntimeState.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 2) {
                m.setAccessible(true);
                return m.invoke(runtime, key, state);
            }
        }
        throw new IllegalStateException(name);
    }

    private boolean tryStart(Object state) throws Exception {
        var m = state.getClass().getDeclaredMethod("tryStartDrain");
        m.setAccessible(true);
        return (boolean) m.invoke(state);
    }

    private void finish(Object state) throws Exception {
        var m = state.getClass().getDeclaredMethod("finishDrain");
        m.setAccessible(true);
        m.invoke(state);
    }

    /// An append inside the park starts nothing; once the hold ends the consumer reads again.
    @Test
    void consumer_appendDuringPark_readsAgainOnceTheHoldEnds() throws Exception {
        parked();
        manager.publishLocal(STREAM, 0, new byte[]{1}, 1L);
        Thread.sleep(200);
        assertThat(reads.get()).as("append inside the hold starts nothing").isEqualTo(1);
        clock.addAndGet(delays.getFirst() + 1);
        scheduled.take().run();
        await(() -> reads.get() >= 2, "drain after the hold");
    }

    /// Red without `finishDrain` in the yield: `running` stays taken and the next `tryStartDrain` fails.
    @Test
    void yield_insideHold_readsNothing_releasesRunning_andHoldEndRedrives() throws Exception {
        var ks = parked();
        assertThat(tryStart(ks[1])).as("running free while parked").isTrue();
        call("drainPass", ks[0], ks[1]);
        assertThat(reads.get()).as("pass yielded: no read inside hold").isEqualTo(1);
        assertThat(tryStart(ks[1])).as("running was released by the yield").isTrue();
        finish(ks[1]);
        clock.addAndGet(delays.getFirst() + 1);
        scheduled.take().run();
        await(() -> reads.get() >= 2, "hold end re-drives after a yield");
    }

    /// Red without `requestDrain` in the yield: the resume found `running` taken and started nothing, so only the yield can restart the loop.
    @Test
    void yield_whenParkEndedMeanwhile_restartsTheLoop() throws Exception {
        var ks = parked();
        assertThat(tryStart(ks[1])).isTrue();
        clock.addAndGet(delays.getFirst() + 1);
        scheduled.take().run();                       // resume: clears the hold, requestDrain cannot start (running taken)
        Thread.sleep(200);
        assertThat(reads.get()).as("resume could not start a pass: running was taken").isEqualTo(1);
        call("yieldToBackoff", ks[0], ks[1]);          // the yield must restart
        await(() -> reads.get() >= 2, "yield restarted the loop");
    }

    private static void await(BooleanSupplier done, String what) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000L;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) { Thread.sleep(5L); }
        assertThat(done.getAsBoolean()).as(what).isTrue();
    }
}
