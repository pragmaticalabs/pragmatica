// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// W-12 (phase-3 design 9.2) and MT-01, MT-13b, MT-18: one consumer, no blocking producer, per-producer FIFO, drop-older coalescing.
class FsmMailboxTest {
    private sealed interface Item {}

    private record Plain(String producer, int seq) implements Item {}

    private record Removed(String node) implements Item {}

    private record Sample(int version) implements Item {}

    private final List<Item> handled = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> handlerThreads = Collections.synchronizedSet(new java.util.HashSet<>());
    private FsmMailbox<Item> mailbox;

    private FsmMailbox<Item> started() {
        mailbox = FsmMailbox.fsmMailbox("deploy-fsm-test", this::record);
        mailbox.start();

        return mailbox;
    }

    private void record(Item item) {
        handlerThreads.add(Thread.currentThread().getName());
        handled.add(item);
    }

    @AfterEach
    void stop() {
        if (mailbox != null) {
            mailbox.close();
        }
    }

    /// Holds the consumer inside the first item so the rest queue up behind it.
    private CountDownLatch blocked(CountDownLatch inside) {
        var release = new CountDownLatch(1);

        mailbox = FsmMailbox.fsmMailbox("deploy-fsm-test", item -> {
            if (item instanceof Plain plain && plain.seq() < 0) {
                inside.countDown();
                await().atMost(Duration.ofSeconds(10)).until(() -> release.getCount() == 0);
            } else {
                record(item);
            }
        });
        mailbox.start();

        return release;
    }

    @Test
    void offer_neverBlocks_whileTheConsumerIsBusy() {
        var inside = new CountDownLatch(1);
        var release = blocked(inside);

        mailbox.offer(new Plain("p", -1));
        await().atMost(Duration.ofSeconds(5)).until(() -> inside.getCount() == 0);

        // MT-18: a bounded put blocks the producer once the bound is reached.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (var i = 0; i < 200_000; i++) {
                mailbox.offer(new Plain("p", i));
            }
        });
        assertThat(mailbox.depth()).as("nothing was dropped").isEqualTo(200_000);

        release.countDown();
        await().atMost(Duration.ofSeconds(20)).until(() -> handled.size() == 200_000);
        assertThat(mailbox.depth()).isZero();
    }

    @Test
    void handler_runsOnlyOnTheOneConsumerThread_neverOnTheOfferingThread() throws Exception {
        var box = started();
        var producers = new ArrayList<Thread>();

        for (var p = 0; p < 4; p++) {
            var name = "producer-" + p;
            var thread = new Thread(() -> {
                for (var i = 0; i < 500; i++) {
                    box.offer(new Plain(name, i));
                }
            }, name);

            producers.add(thread);
            thread.start();
        }

        for (var thread : producers) {
            thread.join();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 2_000);
        assertThat(handlerThreads).as("one consumer, and not a producer").containsExactly("deploy-fsm-test");
        assertThat(box.onConsumerThread()).as("the test thread is not the consumer").isFalse();
    }

    @Test
    void handler_keepsPerProducerFifo() throws Exception {
        var box = started();
        var producers = new ArrayList<Thread>();

        for (var p = 0; p < 3; p++) {
            var name = "producer-" + p;
            var thread = new Thread(() -> {
                for (var i = 0; i < 1_000; i++) {
                    box.offer(new Plain(name, i));
                }
            });

            producers.add(thread);
            thread.start();
        }

        for (var thread : producers) {
            thread.join();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> handled.size() == 3_000);

        for (var p = 0; p < 3; p++) {
            var name = "producer-" + p;
            var seqs = handled.stream()
                              .filter(item -> item instanceof Plain plain && plain.producer().equals(name))
                              .map(item -> ((Plain) item).seq())
                              .toList();

            assertThat(seqs).as("producer %s keeps its order", name).isSorted().hasSize(1_000);
        }
    }

    @Test
    void offerCoalescing_dropsTheOlderSample_andAppendsTheNewerBehindEarlierInputs() {
        var inside = new CountDownLatch(1);
        var release = blocked(inside);

        mailbox.offer(new Plain("p", -1));
        await().atMost(Duration.ofSeconds(5)).until(() -> inside.getCount() == 0);
        mailbox.offerCoalescing(new Sample(1), Sample.class::isInstance);
        mailbox.offer(new Removed("X"));
        mailbox.offerCoalescing(new Sample(2), Sample.class::isInstance);

        assertThat(mailbox.depth()).as("one sample, one removal").isEqualTo(2);

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 2);

        // MT-13b: swapped in place, Sample(2) would take Sample(1)'s slot and overtake Removed("X").
        assertThat(handled).containsExactly(new Removed("X"), new Sample(2));
    }

    @Test
    void depth_countsExactly_whenCoalescingRacesTheConsumer() {
        var inside = new CountDownLatch(1);
        var release = blocked(inside);

        mailbox.offer(new Plain("p", -1));
        await().atMost(Duration.ofSeconds(5)).until(() -> inside.getCount() == 0);

        for (var i = 0; i < 100; i++) {
            mailbox.offerCoalescing(new Sample(i), Sample.class::isInstance);
        }

        assertThat(mailbox.depth()).isEqualTo(1);

        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> mailbox.depth() == 0);
    }

    @Test
    void consumer_survivesAFailingHandler() {
        var seen = new AtomicInteger();

        mailbox = FsmMailbox.fsmMailbox("deploy-fsm-test", item -> {
            seen.incrementAndGet();
            if (item instanceof Removed) {
                throw new IllegalStateException("boom");
            }
            record(item);
        });
        mailbox.start();

        mailbox.offer(new Removed("X"));
        mailbox.offer(new Plain("p", 1));

        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 1);
        assertThat(seen.get()).isEqualTo(2);
    }

    @Test
    void close_stopsTheConsumer_andRefusesFurtherItems() throws Exception {
        var box = started();

        box.offer(new Plain("p", 1));
        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 1);
        var consumer = Thread.getAllStackTraces().keySet().stream()
                             .filter(thread -> thread.getName().equals("deploy-fsm-test"))
                             .findFirst()
                             .orElseThrow();

        box.close();

        assertThat(consumer.isAlive()).as("the consumer thread is gone").isFalse();
        assertThat(box.offer(new Plain("p", 2))).as("an offer after close is refused").isFalse();
        assertThat(consumer.isDaemon()).as("a leaked consumer cannot hold the JVM open").isTrue();
        assertThat(handled).hasSize(1);
    }

    @Test
    void close_calledFromTheHandler_doesNotDeadlock() {
        mailbox = FsmMailbox.fsmMailbox("deploy-fsm-test", item -> mailbox.close());
        mailbox.start();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> mailbox.offer(new Removed("X")));
        await().atMost(Duration.ofSeconds(5)).until(() -> !mailbox.offer(new Removed("Y")));
    }

    @Test
    void start_isIdempotent() {
        var box = started();

        box.start();
        box.offer(new Plain("p", 1));

        await().atMost(Duration.ofSeconds(5)).until(() -> handled.size() == 1);
        assertThat(handlerThreads).hasSize(1);
        assertThat(consumerThreads()).as("one consumer thread, however often start() is called").hasSize(1);
    }

    private static List<Thread> consumerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                     .filter(thread -> thread.getName().startsWith("deploy-fsm-test") && thread.isAlive())
                     .toList();
    }
}
