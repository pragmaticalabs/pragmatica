/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.lang.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1456 — the slot's whole point is that a publish racing a close hands the resource to EXACTLY ONE
/// of the two callers, so the loser of the race is the one that releases it. Every assertion below
/// is about who is handed the value, never about who wins.
class PublishSlotTest {
    private static final String SERVER = "server";
    private static final String REPLACEMENT = "replacement";

    @Test
    void publishOrReclaim_keepsTheValue_whenTheSlotIsOpen() {
        var slot = PublishSlot.<String> publishSlot();

        assertThat(slot.publishOrReclaim(SERVER).isEmpty())
            .as("an open slot takes ownership, so the publisher is handed nothing to release")
            .isTrue();
        assertThat(slot.current().or("absent")).isEqualTo(SERVER);
    }

    /// The defect this class exists for: a bind landing after stop() must come back to its publisher.
    @Test
    void publishOrReclaim_handsTheValueBack_whenTheSlotIsAlreadyClosed() {
        var slot = PublishSlot.<String> publishSlot();

        slot.close();

        assertThat(slot.publishOrReclaim(SERVER).or("taken"))
            .as("a publish after close must hand the resource back — nobody else will release it")
            .isEqualTo(SERVER);
        assertThat(slot.current().isEmpty()).isTrue();
        assertThat(slot.isClosed()).isTrue();
    }

    @Test
    void close_yieldsThePublishedValue_onceOnly() {
        var slot = PublishSlot.<String> publishSlot();

        slot.publishOrReclaim(SERVER);

        assertThat(slot.close().or("absent")).isEqualTo(SERVER);
        assertThat(slot.close().isEmpty())
            .as("a second stop() must not be handed the same resource to release twice")
            .isTrue();
    }

    @Test
    void close_yieldsNothing_whenNothingWasPublished() {
        assertThat(PublishSlot.<String> publishSlot().close().isEmpty()).isTrue();
    }

    @Test
    void publishOrReclaim_handsThePreviousValueBack_whenOneIsStillHeld() {
        var slot = PublishSlot.<String> publishSlot();

        slot.publishOrReclaim(SERVER);

        assertThat(slot.publishOrReclaim(REPLACEMENT).or("absent"))
            .as("no value is ever dropped: the displaced one goes back to the publisher")
            .isEqualTo(SERVER);
        assertThat(slot.current().or("absent")).isEqualTo(REPLACEMENT);
    }

    /// Certificate rotation stops the old listener and publishes a new one into the same slot.
    @Test
    void take_emptiesTheSlot_withoutClosingIt() {
        var slot = PublishSlot.<String> publishSlot();

        slot.publishOrReclaim(SERVER);

        assertThat(slot.take().or("absent")).isEqualTo(SERVER);
        assertThat(slot.isClosed()).isFalse();
        assertThat(slot.publishOrReclaim(REPLACEMENT).isEmpty())
            .as("take() must leave the slot re-publishable — rotation depends on it")
            .isTrue();
        assertThat(slot.current().or("absent")).isEqualTo(REPLACEMENT);
    }

    @Test
    void take_yieldsNothingAndKeepsTheSlotClosed_afterClose() {
        var slot = PublishSlot.<String> publishSlot();

        slot.publishOrReclaim(SERVER);
        slot.close();

        assertThat(slot.take().isEmpty()).isTrue();
        assertThat(slot.isClosed())
            .as("a rotation racing a stop must not reopen the slot")
            .isTrue();
        assertThat(slot.publishOrReclaim(REPLACEMENT).or("absent")).isEqualTo(REPLACEMENT);
    }

    /// The race itself, 2000 rounds with the two callers released from one barrier. WHICH caller is
    /// handed the value is deliberately not asserted — that would be a timing claim, and the two
    /// directions are already pinned deterministically by the two tests above. What IS asserted is
    /// the invariant that makes the fix work: the two sides sum to exactly one release per round, so
    /// no round released twice and — the defect — no round released zero times. The observed split is
    /// carried in the failure description so a red run says which interleaving dominated.
    @Test
    void publishOrReclaim_andClose_handTheValueToExactlyOneCaller_underContention() throws Exception {
        var rounds = 2_000;
        var barrier = new CyclicBarrier(2);
        var releasedByPublisher = new AtomicInteger();
        var releasedByCloser = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            for (var round = 0; round < rounds; round++) {
                var slot = PublishSlot.<String> publishSlot();
                var done = new CountDownLatch(2);

                pool.execute(() -> {
                    awaitBarrier(barrier);
                    slot.publishOrReclaim(SERVER).onPresent(_ -> releasedByPublisher.incrementAndGet());
                    done.countDown();
                });
                pool.execute(() -> {
                    awaitBarrier(barrier);
                    slot.close().onPresent(_ -> releasedByCloser.incrementAndGet());
                    done.countDown();
                });

                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        assertThat(releasedByPublisher.get() + releasedByCloser.get())
            .as("publisher released %d, closer released %d — every round must release exactly once",
                releasedByPublisher.get(),
                releasedByCloser.get())
            .isEqualTo(rounds);
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("barrier await failed", e);
        }
    }
}
