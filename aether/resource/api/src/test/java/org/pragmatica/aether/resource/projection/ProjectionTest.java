// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Nested;
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
        // Counts generation reads so the per-event read can be pinned; a cached read would leave this
        // flat after construction and every other guard test would still pass.
        private final AtomicInteger generationReads = new AtomicInteger();

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
            generationReads.incrementAndGet();

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

    /// The single-argument path is DELIBERATELY unguarded and stays that way: without a
    /// [org.pragmatica.aether.slice.topic.MessageContext] there is no messageId to key by, so
    /// at-least-once is the honest contract rather than a limitation. A counting fold overcounts here,
    /// which is why the guarded shape exists — see [IdempotencyGuard].
    ///
    /// (Rewritten, not deleted, by the change that wired the guard: the behaviour it pins is still
    /// true, but its old name claimed the guard did not exist yet.)
    @Test
    void onEvent_withoutContext_reappliesOnRedelivery_theHonestAtLeastOncePath() {
        var store = new InMemoryStore();
        var projection = countingProjection(store);
        var event = new OrderSeen("a");

        projection.onEvent(event).await().onFailure(cause -> fail(cause.message()));
        projection.onEvent(event).await().onFailure(cause -> fail(cause.message()));
        assertThat(store.data).containsEntry("a", 2);
    }

    /// durable-pubsub-spec §8 — the guard keyed `(projectionName, generation, messageId)`.
    ///
    /// The fold COUNTS, which makes it the right probe: a guard that fails open shows up immediately
    /// as an inflated count rather than as a subtle state difference. Every assertion below would pass
    /// against a no-op guard EXCEPT the dedup one, which is why that one is mutation-verified.
    @Nested
    class IdempotencyGuard {
        private static final MessageContext FIRST = MessageContext.messageContext("msg-1", "ns:orders-seen:1.0.0", 0, 10L);

        /// THE pin. The same messageId redelivered at a DIFFERENT source position — which is exactly
        /// what a redelivery or a DLQ redrive looks like — must apply ONCE. Keying on the position
        /// instead would treat these as two events and admit the duplicate the guard exists to stop.
        @Test
        void sameMessageId_atADifferentPosition_appliesOnce() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims);
            var event = new OrderSeen("a");

            projection.onEvent(event, FIRST).await().onFailure(cause -> fail(cause.message()));
            projection.onEvent(event, MessageContext.messageContext("msg-1", "ns:orders-seen:1.0.0", 3, 99L))
                      .await()
                      .onFailure(cause -> fail(cause.message()));

            assertThat(store.data).describedAs("one event, one apply — the second delivery carries the same"
                                               + " messageId at a new position, which is what a redrive looks like")
                                  .containsEntry("a", 1);
        }

        @Test
        void differentMessageIds_bothApply() {
            var store = new InMemoryStore();
            var projection = countingProjection(store).withClaims(new InMemoryClaims());

            projection.onEvent(new OrderSeen("a"), FIRST).await();
            projection.onEvent(new OrderSeen("a"), MessageContext.messageContext("msg-2", "ns:orders-seen:1.0.0", 0, 11L))
                      .await();

            assertThat(store.data).containsEntry("a", 2);
        }

        /// The generation component earning its place: after a rebuild bumps it, the SAME messageId
        /// must apply again, because the replay has to be able to rebuild the model. Without
        /// generation in the key the prior pass's claims would match and dedup the entire replay into
        /// a no-op — spec review finding 3.
        @Test
        void sameMessageId_appliesAgain_afterAGenerationBump() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims);
            var event = new OrderSeen("a");

            projection.onEvent(event, FIRST).await();
            store.bumpGeneration().await();
            projection.onEvent(event, FIRST).await();

            assertThat(store.data).describedAs("a rebuild must be able to replay the same events; the"
                                               + " generation moves them to fresh claim keys")
                                  .containsEntry("a", 2);
        }

        /// The generation is read PER EVENT, never cached on the projection instance. Pinned because a
        /// cached read passes every other test here and fails only in the rebuild case above — and a
        /// rebuild can be performed on ANOTHER node, so no local invalidation would save it.
        @Test
        void generationIsReadPerEvent_notCachedAtConstruction() {
            var store = new InMemoryStore();
            var projection = countingProjection(store).withClaims(new InMemoryClaims());

            projection.onEvent(new OrderSeen("a"), FIRST).await();

            var readsAfterFirst = store.generationReads.get();

            projection.onEvent(new OrderSeen("b"), MessageContext.messageContext("msg-2", "ns:orders-seen:1.0.0", 0, 11L))
                      .await();

            assertThat(store.generationReads.get()).describedAs("each guarded apply must consult the store's"
                                                               + " generation, or a rebuild elsewhere goes unnoticed")
                                                   .isGreaterThan(readsAfterFirst);
        }

        /// Fail-closed: a context-carrying event with no claims backing REFUSES. Applying unguarded
        /// would produce a projection that looks guarded and is not, which is worse than one that
        /// plainly is not.
        @Test
        void contextCarryingEvent_refusesLoudly_whenNoClaimsBackingIsWired() {
            var projection = countingProjection(new InMemoryStore());

            projection.onEvent(new OrderSeen("a"), FIRST)
                      .await()
                      .onSuccess(_ -> fail("an unguarded apply must be refused, not performed silently"))
                      .onFailure(cause -> assertThat(cause.message()).contains("ProjectionClaims"));
        }

        @Test
        void claimKey_separatesProjections_generations_andMessages() {
            var base = new Projection.ClaimKey("orders", 0L, "msg-1");

            assertThat(base).isEqualTo(new Projection.ClaimKey("orders", 0L, "msg-1"))
                            .isNotEqualTo(new Projection.ClaimKey("other", 0L, "msg-1"))
                            .isNotEqualTo(new Projection.ClaimKey("orders", 1L, "msg-1"))
                            .isNotEqualTo(new Projection.ClaimKey("orders", 0L, "msg-2"));
        }
    }

    /// In-memory [ProjectionClaims]. Not atomic and not shared — which is exactly exception (i) of the
    /// bound stated on [Projection], so this fake models the DEFAULT deployment rather than the
    /// strongest one.
    private static final class InMemoryClaims implements ProjectionClaims {
        private final Map<Object, Object> claimed = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<Object>> get(Object key) {
            return Promise.success(Option.option(claimed.get(key)));
        }

        @Override
        public Promise<Unit> put(Object key, Object value) {
            claimed.put(key, value);

            return Promise.unitPromise();
        }
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
