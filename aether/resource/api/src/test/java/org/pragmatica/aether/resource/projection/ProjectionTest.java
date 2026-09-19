// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.projection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Pins durable-pubsub-spec §10's facade: keyed fold-and-write on each event, the honest
/// at-least-once single-argument path, the §8 idempotency guard (leased, fenced claims — see
/// [IdempotencyGuard]), and the rebuild order — generation bumped BEFORE the data reset (review
/// finding 3), data cleared with the generation slot preserved (§13 item 6), cursor seam invoked LAST
/// and loudly refused by default until the operator surface exists.
class ProjectionTest {
    private record OrderSeen(String orderId) {}

    private static final Topic<OrderSeen> TOPIC = Topic.of("orders-seen", OrderSeen.class);
    private static final Cause WRITE_FAILED = Causes.cause("staged read-model write failure");
    private static final TimeSpan LEASE = timeSpan(30).seconds();

    /// In-memory [ProjectionStore] honoring the reset contract: data cleared, generation kept.
    private static final class InMemoryStore implements ProjectionStore<Integer> {
        private final Map<String, Integer> data = new ConcurrentHashMap<>();
        private final AtomicLong generation = new AtomicLong();
        private final AtomicInteger resets = new AtomicInteger();
        // Counts generation reads so the per-event read can be pinned; a cached read would leave this
        // flat after construction and every other guard test would still pass.
        private final AtomicInteger generationReads = new AtomicInteger();
        // Writes left to fail, so a failed fold can be staged without a second store type.
        private final AtomicInteger failingWrites = new AtomicInteger();
        // Every write completes only when this does, so a fold can be held past its claim's lease.
        private volatile Promise<Unit> writeGate = Promise.unitPromise();

        @Override
        public Promise<Option<Integer>> read(String key) {
            return Promise.success(Option.option(data.get(key)));
        }

        @Override
        public Promise<Unit> write(String key, Integer state) {
            if (failingWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                return WRITE_FAILED.promise();
            }
            return writeGate.map(_ -> put(key, state));
        }

        private Unit put(String key, Integer state) {
            data.put(key, state);

            return Unit.unit();
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
    /// as an inflated count rather than as a subtle state difference. These tests pin how [Projection]
    /// USES the claims contract; the fakes are atomic by construction, so they cannot show that a real
    /// backing complies with it — that needs a conformance suite run against the backing itself.
    @Nested
    class IdempotencyGuard {
        private static final MessageContext FIRST = MessageContext.messageContext("msg-1", "ns:orders-seen:1.0.0", 0, 10L);
        private static final Projection.ClaimKey CLAIM_KEY = new Projection.ClaimKey("orders-seen", 0L, "msg-1");

        /// THE pin. The same messageId redelivered at a DIFFERENT source position — which is exactly
        /// what a redelivery or a DLQ redrive looks like — must apply ONCE. Keying on the position
        /// instead would treat these as two events and admit the duplicate the guard exists to stop.
        @Test
        void sameMessageId_atADifferentPosition_appliesOnce() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
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
            var projection = countingProjection(store).withClaims(new InMemoryClaims(), LEASE);

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
            var projection = countingProjection(store).withClaims(claims, LEASE);
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
            var projection = countingProjection(store).withClaims(new InMemoryClaims(), LEASE);

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

        /// #1243 — THE concurrency pin. Two attempts on one messageId (a zombie and its retry, §6)
        /// both reach the claim step before either records anything. Exactly one may fold; a
        /// check-then-record guard lets both through and double-counts.
        @Test
        void twoConcurrentAttempts_onOneMessageId_foldExactlyOnce() {
            var store = new InMemoryStore();
            var claims = new LatchedClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
            var event = new OrderSeen("a");

            var first = projection.onEvent(event, FIRST);
            var second = projection.onEvent(event, FIRST);

            first.await().onFailure(cause -> fail(cause.message()));
            second.await()
                  .onSuccess(_ -> fail("the losing attempt must not be acknowledged while the claim is live"))
                  .onFailure(cause -> assertThat(cause.message()).contains("live claim"));

            assertThat(store.data).describedAs("two racing attempts on one messageId must fold once")
                                  .containsEntry("a", 1);

            // The loser's retry now finds the claim DONE: acknowledged, still one apply.
            projection.onEvent(event, FIRST).await().onFailure(cause -> fail(cause.message()));
            assertThat(store.data).containsEntry("a", 1);
        }

        /// A failed fold RELEASES its claim, so the retry applies. Without the release the retry would
        /// meet a live PENDING claim and be refused until the lease expired.
        @Test
        void failedFold_releasesTheClaim_soTheRetryApplies() {
            var store = new InMemoryStore();
            var projection = countingProjection(store).withClaims(new InMemoryClaims(), LEASE);
            var event = new OrderSeen("a");

            store.failingWrites.set(1);
            projection.onEvent(event, FIRST)
                      .await()
                      .onSuccess(_ -> fail("a failed fold must surface, not be acknowledged"))
                      .onFailure(cause -> assertThat(cause).isEqualTo(WRITE_FAILED));
            projection.onEvent(event, FIRST).await().onFailure(cause -> fail(cause.message()));

            assertThat(store.data).containsEntry("a", 1);
        }

        /// A PENDING claim left by a crashed attempt (claimed, never finalized nor released) refuses
        /// while its lease is live and is reclaimable once it expires — the crash-between-fold-and-
        /// finalize re-apply window the class doc names.
        @Test
        void expiredPendingLease_isReclaimable() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
            var event = new OrderSeen("a");
            var crashedAttemptKey = new Projection.ClaimKey("orders-seen", 0L, "msg-1");

            claimToken(claims, crashedAttemptKey);
            projection.onEvent(event, FIRST)
                      .await()
                      .onSuccess(_ -> fail("a live PENDING claim must refuse the attempt"));
            assertThat(store.data).doesNotContainKey("a");

            claims.clock.addAndGet(LEASE.nanos() + 1);
            projection.onEvent(event, FIRST).await().onFailure(cause -> fail(cause.message()));

            assertThat(store.data).containsEntry("a", 1);
        }

        /// An expired holder cannot FINALIZE its successor's claim. A's fold is held past its lease, B
        /// reclaims, then A's late fold completes: B's claim must survive as PENDING — A's late fold
        /// must not produce a DONE that B never earned.
        @Test
        void expiredHolder_lateFinalize_leavesTheSuccessorsClaimPending() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
            var gate = Promise.<Unit> promise();

            store.writeGate = gate;

            var holder = projection.onEvent(new OrderSeen("a"), FIRST);

            claims.clock.addAndGet(LEASE.nanos() + 1);

            var successorToken = claimToken(claims, CLAIM_KEY);

            gate.succeed(Unit.unit());
            holder.await();

            assertThat(claims.claimed.get(CLAIM_KEY)).describedAs("the successor's claim must survive the expired holder's finalize")
                                                     .isNotNull()
                                                     .matches(claim -> !claim.done() && claim.token() == successorToken);
            assertThat(claims.finalizeClaim(CLAIM_KEY, successorToken).await())
                .describedAs("the successor still owns its claim and may finalize it")
                .isEqualTo(Result.success(ProjectionClaims.Settlement.APPLIED));
        }

        /// An expired holder cannot RELEASE its successor's claim. Were it released, a third attempt
        /// could claim and fold alongside the successor.
        @Test
        void expiredHolder_lateRelease_leavesTheSuccessorsClaimHeld() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
            var gate = Promise.<Unit> promise();

            store.writeGate = gate;

            var holder = projection.onEvent(new OrderSeen("a"), FIRST);

            claims.clock.addAndGet(LEASE.nanos() + 1);

            var successorToken = claimToken(claims, CLAIM_KEY);

            gate.fail(WRITE_FAILED);
            holder.await();

            assertThat(claims.claimed.get(CLAIM_KEY)).describedAs("the successor's claim must survive the expired holder's release")
                                                     .isNotNull()
                                                     .matches(claim -> !claim.done() && claim.token() == successorToken);
        }

        /// #1256 B1 — a token must stay unique for the key ACROSS releases. A backing whose counter
        /// lives in the claim record restarts it when a release drops the record and reissues an old
        /// token; the expired holder carrying that token would then release a claim that is not its
        /// own, and a third attempt could fold beside its owner.
        @Test
        void expiredHolder_lateRelease_cannotRemoveAClaimIssuedAfterARelease() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
            var gate = Promise.<Unit> promise();

            store.writeGate = gate;

            var holder = projection.onEvent(new OrderSeen("a"), FIRST);

            claims.clock.addAndGet(LEASE.nanos() + 1);
            claims.releaseClaim(CLAIM_KEY, claimToken(claims, CLAIM_KEY)).await();

            var successorToken = claimToken(claims, CLAIM_KEY);

            gate.fail(WRITE_FAILED);
            holder.await();

            assertThat(claims.claimed.get(CLAIM_KEY)).describedAs("a token reissued after a release must not let the expired holder release the new claim")
                                                     .isNotNull()
                                                     .matches(claim -> !claim.done() && claim.token() == successorToken);
        }

        /// #1256 R2 — a non-positive lease expires at the instant it is taken, so every racing attempt
        /// would find the other's claim already expired and both would fold. The facade must refuse it
        /// before claiming: neither attempt applies, and both fail naming the lease.
        @Test
        void nonPositiveLease_isRefused_soRacingAttemptsDoNotBothApply() {
            var store = new InMemoryStore();
            var projection = countingProjection(store).withClaims(new LatchedClaims(), timeSpan(0).seconds());
            var event = new OrderSeen("a");

            var first = projection.onEvent(event, FIRST);
            var second = projection.onEvent(event, FIRST);

            first.await()
                 .onSuccess(_ -> fail("a zero lease must be refused, not claimed"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(Projection.ProjectionError.NonPositiveLease.class));
            second.await()
                  .onSuccess(_ -> fail("a zero lease must be refused, not claimed"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(Projection.ProjectionError.NonPositiveLease.class));

            assertThat(store.data).describedAs("a refused lease must not let either racing attempt fold")
                                  .doesNotContainKey("a");
        }

        /// #1298 (review R4) — a guarded fold in flight across [Projection#rebuild] must apply exactly
        /// once. Held at its write while the rebuild bumps the generation and resets the model, its late
        /// write would land in the rebuilt model under the OLD generation, and the replay would then
        /// apply the same event again under the new one.
        @Test
        void rebuild_inFlightGuardedFold_appliesExactlyOnce() {
            var store = new InMemoryStore();
            var projection = Projection.of(TOPIC)
                                       .into(store, OrderSeen::orderId)
                                       .apply("orders-seen", (current, event) -> current.or(0) + 1, Promise::unitPromise)
                                       .withClaims(new InMemoryClaims(), LEASE);
            var gate = Promise.<Unit> promise();
            var event = new OrderSeen("a");

            store.writeGate = gate;

            var inFlight = projection.onEvent(event, FIRST);

            projection.rebuild().await().onFailure(cause -> fail(cause.message()));
            gate.succeed(Unit.unit());
            inFlight.await();
            projection.onEvent(event, FIRST).await().onFailure(cause -> fail(cause.message()));

            assertThat(store.data).describedAs("rebuilt model after the replay: the in-flight fold must not count")
                                  .containsEntry("a", 1);
        }

        /// A negative lease is refused the same way — the check is `> 0`, not `!= 0`.
        @Test
        void negativeLease_isRefused() {
            var store = new InMemoryStore();

            countingProjection(store).withClaims(new InMemoryClaims(), timeSpan(-1).seconds())
                                     .onEvent(new OrderSeen("a"), FIRST)
                                     .await()
                                     .onSuccess(_ -> fail("a negative lease must be refused, not claimed"))
                                     .onFailure(cause -> assertThat(cause).isInstanceOf(Projection.ProjectionError.NonPositiveLease.class));

            assertThat(store.data).doesNotContainKey("a");
        }

        /// The contract itself, below the facade: a token that no longer matches the stored claim is
        /// refused as STALE by both finalize and release, and the claim is left untouched.
        @Test
        void staleToken_isRefused_byFinalizeAndRelease() {
            var claims = new InMemoryClaims();
            var expiredToken = claimToken(claims, CLAIM_KEY);

            claims.clock.addAndGet(LEASE.nanos() + 1);

            var successorToken = claimToken(claims, CLAIM_KEY);

            assertThat(successorToken).isNotEqualTo(expiredToken);
            assertThat(claims.finalizeClaim(CLAIM_KEY, expiredToken).await())
                .isEqualTo(Result.success(ProjectionClaims.Settlement.STALE));
            assertThat(claims.releaseClaim(CLAIM_KEY, expiredToken).await())
                .isEqualTo(Result.success(ProjectionClaims.Settlement.STALE));
            assertThat(claims.claimed.get(CLAIM_KEY)).matches(claim -> !claim.done() && claim.token() == successorToken);
        }

        /// A DONE claim suppresses: the attempt is acknowledged and does not fold.
        @Test
        void doneClaim_suppressesTheApply() {
            var store = new InMemoryStore();
            var claims = new InMemoryClaims();
            var projection = countingProjection(store).withClaims(claims, LEASE);
            var doneKey = new Projection.ClaimKey("orders-seen", 0L, "msg-1");

            claims.finalizeClaim(doneKey, claimToken(claims, doneKey)).await();
            projection.onEvent(new OrderSeen("a"), FIRST).await().onFailure(cause -> fail(cause.message()));

            assertThat(store.data).doesNotContainKey("a");
        }

        /// Claim `key` directly, as another attempt would, and return the token it was issued.
        private static long claimToken(InMemoryClaims claims, Projection.ClaimKey key) {
            return claims.claimIfAbsent(key, LEASE)
                         .await()
                         .map(ProjectionClaims.Claimed.class::cast)
                         .map(ProjectionClaims.Claimed::token)
                         .unwrap();
        }

        /// The STALE settlement is claimed to be logged at WARN (javadoc, spec §8, changelog), so it is
        /// pinned here: a log4j2 appender on the [Projection] logger captures what the expired holder's
        /// late finalize or release emits. Removing the WARN reddens both tests.
        @Nested
        class StaleSettlementWarn {
            private static final String LOGGER_NAME = Projection.class.getName();

            private CapturingAppender appender;
            private LoggerConfig loggerConfig;
            private Level originalLevel;

            @BeforeEach
            void captureWarns() {
                var context = (LoggerContext) LogManager.getContext(false);

                appender = CapturingAppender.create("ProjectionStaleWarnCapture");
                appender.start();
                loggerConfig = loggerConfigFor(context.getConfiguration());
                originalLevel = loggerConfig.getLevel();
                loggerConfig.addAppender(appender, Level.WARN, null);
                loggerConfig.setLevel(Level.WARN);
                context.updateLoggers();
            }

            @AfterEach
            void releaseCapture() {
                var context = (LoggerContext) LogManager.getContext(false);

                loggerConfig.removeAppender(appender.getName());
                loggerConfig.setLevel(originalLevel);
                context.updateLoggers();
                appender.stop();
            }

            @Test
            void expiredHolder_lateFinalize_isLoggedAsStaleAtWarn() {
                runExpiredHolder(gate -> gate.succeed(Unit.unit()));

                assertThat(appender.warns()).describedAs("the expired holder's refused finalize must be visible at WARN")
                                            .anyMatch(line -> line.contains("was stale on finalize")
                                                              && line.contains("msg-1"));
            }

            @Test
            void expiredHolder_lateRelease_isLoggedAsStaleAtWarn() {
                runExpiredHolder(gate -> gate.fail(WRITE_FAILED));

                assertThat(appender.warns()).describedAs("the expired holder's refused release must be visible at WARN")
                                            .anyMatch(line -> line.contains("was stale on release")
                                                              && line.contains("msg-1"));
            }

            /// A's fold is held past its lease, B reclaims, then `settle` completes A's fold.
            private void runExpiredHolder(Consumer<Promise<Unit>> settle) {
                var store = new InMemoryStore();
                var claims = new InMemoryClaims();
                var projection = countingProjection(store).withClaims(claims, LEASE);
                var gate = Promise.<Unit> promise();

                store.writeGate = gate;

                var holder = projection.onEvent(new OrderSeen("a"), FIRST);

                claims.clock.addAndGet(LEASE.nanos() + 1);
                claimToken(claims, CLAIM_KEY);
                settle.accept(gate);
                holder.await();
            }

            private static LoggerConfig loggerConfigFor(Configuration configuration) {
                var existing = configuration.getLoggerConfig(LOGGER_NAME);

                if (LOGGER_NAME.equals(existing.getName())) {
                    return existing;
                }

                var fresh = new LoggerConfig(LOGGER_NAME, Level.WARN, false);

                configuration.addLogger(LOGGER_NAME, fresh);

                return fresh;
            }
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

    /// In-memory [ProjectionClaims]. Each operation is atomic (`compute`) but NOT shared — it models
    /// one process; cross-instance suppression needs a backing every instance reads. The clock is
    /// manual so lease expiry is a test decision, not a sleep. Tokens come from a counter that
    /// OUTLIVES every claim record, so a release never lets an old token be reissued.
    private static final class InMemoryClaims implements ProjectionClaims {
        private record Claim(boolean done, long expiresAt, long token) {}

        private final Map<Projection.ClaimKey, Claim> claimed = new ConcurrentHashMap<>();
        private final AtomicLong clock = new AtomicLong();
        private final AtomicLong tokens = new AtomicLong();

        @Override
        public Promise<ClaimOutcome> claimIfAbsent(Projection.ClaimKey key, TimeSpan lease) {
            var outcome = new ClaimOutcome[1];

            claimed.compute(key, (_, existing) -> decide(Option.option(existing), lease, outcome));

            return Promise.success(outcome[0]);
        }

        private Claim decide(Option<Claim> existing, TimeSpan lease, ClaimOutcome[] outcome) {
            var now = clock.get();
            var live = existing.filter(claim -> claim.done() || claim.expiresAt() > now);
            var fresh = new Claim(false, now + lease.nanos(), tokens.incrementAndGet());

            outcome[0] = live.map(InMemoryClaims::outcomeOf)
                             .or(new Claimed(fresh.token()));

            return live.or(fresh);
        }

        private static ClaimOutcome outcomeOf(Claim claim) {
            return claim.done()
                   ? Held.DONE
                   : Held.IN_PROGRESS;
        }

        @Override
        public Promise<Settlement> finalizeClaim(Projection.ClaimKey key, long token) {
            return settle(key, token, new Claim(true, Long.MAX_VALUE, token));
        }

        @Override
        public Promise<Settlement> releaseClaim(Projection.ClaimKey key, long token) {
            return settle(key, token, null);
        }

        /// Replace the claim with `next` (null removes it) only while it is PENDING with `token`.
        private Promise<Settlement> settle(Projection.ClaimKey key, long token, Claim next) {
            var settlement = new Settlement[] {Settlement.STALE};

            claimed.computeIfPresent(key, (_, existing) -> settleIfHeld(existing, token, next, settlement));

            return Promise.success(settlement[0]);
        }

        /// `computeIfPresent` removes the entry on null — the release case.
        @NullReturn
        private static Claim settleIfHeld(Claim existing, long token, Claim next, Settlement[] settlement) {
            if (existing.done() || existing.token() != token) {
                return existing;
            }
            settlement[0] = Settlement.APPLIED;

            return next;
        }
    }

    /// In-memory log4j2 appender keeping WARN-and-above messages for assertions.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> warns() {
            return List.copyOf(messages);
        }
    }

    /// Latched [ProjectionClaims]: each claim is decided ATOMICALLY on arrival, but the answer is
    /// DELIVERED only once two attempts have reached the claim step — so both pass it before either
    /// folds or records. Against a check-then-record guard this is the #1243 race.
    private static final class LatchedClaims implements ProjectionClaims {
        private final InMemoryClaims delegate = new InMemoryClaims();
        private final Promise<Unit> bothArrived = Promise.promise();
        private final AtomicInteger arrivals = new AtomicInteger();

        @Override
        public Promise<ClaimOutcome> claimIfAbsent(Projection.ClaimKey key, TimeSpan lease) {
            var decided = delegate.claimIfAbsent(key, lease);

            arrive();

            return bothArrived.flatMap(_ -> decided);
        }

        @Override
        public Promise<Settlement> finalizeClaim(Projection.ClaimKey key, long token) {
            return delegate.finalizeClaim(key, token);
        }

        @Override
        public Promise<Settlement> releaseClaim(Projection.ClaimKey key, long token) {
            return delegate.releaseClaim(key, token);
        }

        private void arrive() {
            if (arrivals.incrementAndGet() == 2) {
                bothArrived.succeed(Unit.unit());
            }
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

    /// #1298 — a single-argument fold in flight across [Projection#rebuild] read the PRE-reset model;
    /// its late write must not carry that state into the rebuilt one. The replay is the only writer.
    @Test
    void rebuild_inFlightUnguardedFold_doesNotWriteIntoTheResetModel() {
        var store = new InMemoryStore();
        var projection = Projection.of(TOPIC)
                                   .into(store, OrderSeen::orderId)
                                   .apply("orders-seen", (current, event) -> current.or(0) + 1, Promise::unitPromise);
        var gate = Promise.<Unit> promise();

        projection.onEvent(new OrderSeen("a")).await().onFailure(cause -> fail(cause.message()));
        store.writeGate = gate;

        var inFlight = projection.onEvent(new OrderSeen("a"));

        projection.rebuild().await().onFailure(cause -> fail(cause.message()));
        gate.succeed(Unit.unit());
        inFlight.await();

        assertThat(store.data).describedAs("a fold that read the pre-reset model must not write into the rebuilt one")
                              .doesNotContainKey("a");
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
