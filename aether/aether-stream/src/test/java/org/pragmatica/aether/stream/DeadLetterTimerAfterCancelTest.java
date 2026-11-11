// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore.CommitOutcome;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;


/// #2914: the backoff timer of a failed dead-letter append runs under the same cancel-and-claim step as a delivery
/// ([ConsumerRuntimeState.ConsumerState#claimAdvance]). The timer is the runtime's scheduler seam: a test fires it by
/// running the captured task, so nothing here waits for a backoff to elapse.
class DeadLetterTimerAfterCancelTest {
    private static final String STREAM = "orders";

    private final LinkedBlockingQueue<Runnable> timers = new LinkedBlockingQueue<>();
    /// Ordered record of what the sink and the cursor store saw: `append:<group>` and `commit:<group>`.
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicInteger> appends = new ConcurrentHashMap<>();
    private StreamPartitionManager manager;
    private ConsumerRuntimeState runtime;

    private final DeadLetterHandler sink = new DeadLetterHandler() {
        @Override
        public Promise<Unit> append(String streamName,
                                    int partition,
                                    long offset,
                                    String failingGroup,
                                    byte[] payload,
                                    String errorMessage,
                                    int attemptCount) {
            log.add("append:" + failingGroup);

            return appends.computeIfAbsent(failingGroup,
                                           _ -> new AtomicInteger())
                          .incrementAndGet() == 1
                   ? StreamError.General.BUFFER_EMPTY.promise()
                   : Promise.unitPromise();
        }

        @Override
        public Promise<List<DeadLetterEntry>> read(String streamName, int maxCount) {
            return Promise.success(List.of());
        }
    };

    private final ConsumerCursorStore store = new ConsumerCursorStore() {
        @Override
        public Promise<CommitOutcome> commit(String consumerGroup, String streamName, int partition, long offset) {
            log.add("commit:" + consumerGroup);

            return Promise.success(CommitOutcome.persisted());
        }

        @Override
        public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
            return Promise.success(none());
        }
    };

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000),
                                                       "earliest"));
        runtime = new ConsumerRuntimeState(manager,
                                           sink,
                                           some(store),
                                           none(),
                                           StreamConsumerRuntime.localPartitionReader(manager),
                                           ConsumerRuntimeState.DEAD_LETTER_APPEND_TIMEOUT,
                                           System::currentTimeMillis,
                                           (task, _) -> timers.add(task));
        manager.publishLocal(STREAM, 0, "event-0".getBytes(UTF_8), 1000L);
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        manager.close();
    }

    /// The first append fails and arms the backoff timer; the consumer is then unsubscribed; the timer fires. Red under
    /// "the timer appends without the cancel check": the cancelled consumer's append reaches the sink after its detach
    /// flush committed the cursor, and the successor would dead-letter the redelivered event a second time.
    @Test
    void timerFiringAfterUnsubscribe_appendsNoDeadLetter() throws InterruptedException {
        var timer = armedTimer("g");

        runtime.unsubscribe(STREAM, 0, "g");
        timer.run();
        assertThat(log).as("one failed append, then the detach flush; the timer adds nothing")
                  .containsExactly("append:g", "commit:g");
        assertThat(appends.get("g").get()).isEqualTo(1);
    }

    /// Control: with no unsubscribe the same timer appends, so the test above can fail only for the cancel.
    @Test
    void timerFiringWithoutUnsubscribe_appendsTheDeadLetter() throws InterruptedException {
        var timer = armedTimer("g");

        timer.run();
        assertThat(appends.get("g").get()).as("the retried append reached the sink").isEqualTo(2);
    }

    /// The timer fires while the unsubscribe lands, across a swept offset between them. Either the timer's append runs to
    /// completion before the detach flush commits the cursor (the flush waits for it), or it does nothing: no append
    /// follows the first commit. Red under "the timer's claim and the cancel are separate steps" when the interleaving is
    /// reached inside the deadline.
    @Test
    void timerRacingUnsubscribe_neverAppendsAfterTheDetachFlush() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var rounds = 0;
        var late = new CopyOnWriteArrayList<String>();

        while (System.nanoTime() < deadline && late.isEmpty() && rounds < 2_000) {
            var group = "race-" + rounds;
            var timer = armedTimer(group);
            var go = new AtomicBoolean();
            var firing = Thread.ofPlatform().start(() -> fireWhenReleased(go, timer));
            var spins = (rounds * 13) % 600;

            go.set(true);
            for (var s = 0; s < spins; s++) {
                Thread.onSpinWait();
            }

            runtime.unsubscribe(STREAM, 0, group);
            firing.join(5_000);
            judge(group, late);
            rounds++;
        }

        assertThat(late).as("rounds with an append after the detach flush").isEmpty();
        assertThat(rounds).as("rounds run").isGreaterThan(100);
    }

    private static void fireWhenReleased(AtomicBoolean go, Runnable timer) {
        while (!go.get()) {
            Thread.onSpinWait();
        }

        timer.run();
    }

    private void judge(String group, List<String> late) {
        var mine = log.stream().filter(entry -> entry.endsWith(":" + group)).toList();
        var firstCommit = mine.indexOf("commit:" + group);

        if (firstCommit >= 0 && mine.subList(firstCommit, mine.size()).contains("append:" + group)) {
            late.add(group + " " + mine);
        }
    }

    /// Subscribes `group` with a handler that always fails under SKIP, and returns the backoff timer of the first,
    /// failed, dead-letter append.
    private Runnable armedTimer(String group) throws InterruptedException {
        runtime.subscribe(STREAM,
                          0,
                          ConsumerConfig.consumerConfig(group, 1, ProcessingMode.ORDERED, ErrorStrategy.SKIP),
                          (_, _, _) -> StreamError.General.BUFFER_EMPTY.promise());
        var timer = timers.poll(10, TimeUnit.SECONDS);

        assertThat(timer).as("the failed append armed a backoff timer").isNotNull();

        return timer;
    }
}
