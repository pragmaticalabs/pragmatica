// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Pins the D4 substrate half of durable-pubsub-spec §10 — the facade WITHOUT the idempotency
/// guard: keyed fold-and-write on each event, the documented at-least-once re-application (the pin
/// that gets REWRITTEN when the guard lands, proving the doc told the truth meanwhile), and the
/// rebuild order — generation bumped BEFORE the data reset (review finding 3), data cleared with
/// the generation slot preserved (§13 item 6), cursor seam invoked LAST and loudly refused by
/// default until the operator surface exists.
class ProjectionTest {
    private record OrderSeen(String orderId) {}

    private static final Topic<OrderSeen> TOPIC = Topic.of("orders-seen", OrderSeen.class);

    /// In-memory [ProjectionStore] honoring the reset contract: data cleared, generation kept.
    private static final class InMemoryStore implements ProjectionStore<Integer> {
        private final Map<String, Integer> data = new ConcurrentHashMap<>();
        private final AtomicLong generation = new AtomicLong();
        private final AtomicInteger resets = new AtomicInteger();

        @Override
        public Promise<Option<Integer>> read(String key) {
            return Promise.success(Option.option(data.get(key)));
        }

        @Override
        public Promise<Unit> write(String key, Integer state) {
            data.put(key, state);

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> reset() {
            data.clear();
            resets.incrementAndGet();

            return Promise.unitPromise();
        }

        @Override
        public Promise<Long> generation() {
            return Promise.success(generation.get());
        }

        @Override
        public Promise<Long> bumpGeneration() {
            return Promise.success(generation.incrementAndGet());
        }
    }

    private static Projection<Integer, OrderSeen> countingProjection(InMemoryStore store) {
        return Projection.of(TOPIC)
                         .into(store, OrderSeen::orderId)
                         .apply((current, event) -> current.or(0) + 1);
    }

    @Test
    void onEvent_foldsIntoKeyedState() {
        var store = new InMemoryStore();
        var projection = countingProjection(store);

        projection.onEvent(new OrderSeen("a")).await().onFailure(cause -> fail(cause.message()));
        projection.onEvent(new OrderSeen("a")).await().onFailure(cause -> fail(cause.message()));
        projection.onEvent(new OrderSeen("b")).await().onFailure(cause -> fail(cause.message()));
        assertThat(store.data).containsEntry("a", 2).containsEntry("b", 1);
    }

    /// The honest CURRENT truth, pinned so the doc cannot drift: without the guard, a redelivered
    /// event RE-APPLIES — a counting fold overcounts. This test is REWRITTEN (not deleted) by the
    /// change that wires the (projectionName, generation, messageId) guard.
    @Test
    void onEvent_reappliesOnRedelivery_atLeastOnceUntilGuardLands() {
        var store = new InMemoryStore();
        var projection = countingProjection(store);
        var event = new OrderSeen("a");

        projection.onEvent(event).await().onFailure(cause -> fail(cause.message()));
        projection.onEvent(event).await().onFailure(cause -> fail(cause.message()));
        assertThat(store.data).containsEntry("a", 2);
    }

    @Test
    void rebuild_bumpsGenerationBeforeReset_preservingTheSlot_andResetsCursorLast() {
        var store = new InMemoryStore();
        var order = new java.util.ArrayList<String>();
        var projection = Projection.of(TOPIC)
                                   .into(store, OrderSeen::orderId)
                                   .apply("orders-proj",
                                          (current, event) -> current.or(0) + 1,
                                          () -> {
                                              order.add("cursor@gen" + store.generation.get()
                                                       + "/resets" + store.resets.get());

                                              return Promise.unitPromise();
                                          });

        projection.onEvent(new OrderSeen("a")).await().onFailure(cause -> fail(cause.message()));
        projection.rebuild().await().onFailure(cause -> fail(cause.message()));
        assertThat(store.generation.get()).isEqualTo(1L);
        assertThat(store.data).isEmpty();
        // Cursor seam ran LAST, observing the bumped generation AND the completed reset.
        assertThat(order).containsExactly("cursor@gen1/resets1");
    }

    @Test
    void rebuild_refusesLoudly_whenCursorResetNotWired() {
        var store = new InMemoryStore();
        var projection = countingProjection(store);

        projection.rebuild()
                  .await()
                  .onSuccess(_ -> fail("rebuild without a cursor reset would clear the model and replay nothing"))
                  .onFailure(cause -> assertThat(cause.message()).contains("D3"));
        // The refusal happens AFTER generation+reset (order is the facade's contract; the cursor
        // step is the one still pending) — the store must reflect the completed halves.
        assertThat(store.generation.get()).isEqualTo(1L);
        assertThat(store.data).isEmpty();
    }

    @Test
    void builder_defaultsProjectionName_toTopicName() {
        var projection = countingProjection(new InMemoryStore());

        assertThat(projection.name()).isEqualTo("orders-seen");
    }
}
