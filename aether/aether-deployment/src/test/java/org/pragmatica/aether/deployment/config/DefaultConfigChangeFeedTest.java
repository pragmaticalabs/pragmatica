// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.Watched;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.config.DynamicConfigurationProvider.dynamicConfigurationProvider;

/// The delivery engine of `Watched`: registration, serial delivery, failure containment, release.
class DefaultConfigChangeFeedTest {
    private static final String OWNER = "org.example:orders:1.0.0";
    private static final String SECTION = "app.orders";
    private static final String KEY_A = SECTION + ".a";
    private static final String KEY_B = SECTION + ".b";

    /// The record a binder builds: two keys read together from one consistent overlay state.
    record Pair(String a, String b) {}

    private final DynamicConfigurationProvider overlay = dynamicConfigurationProvider(ConfigurationProvider.builder().build());
    private final AtomicLong revision = new AtomicLong(5);
    private final List<OperatorWarning> events = new CopyOnWriteArrayList<>();
    private final DefaultConfigChangeFeed feed = newFeed(TimeSpan.timeSpan(2).seconds());

    private DefaultConfigChangeFeed newFeed(TimeSpan callbackTimeout) {
        var created = DefaultConfigChangeFeed.defaultConfigChangeFeed(revision::get, overlay::atomically, callbackTimeout);

        created.setOperatorWarningSink(OperatorWarningSink.handingOffTo(events::add));
        return created;
    }

    private Result<Pair> bindPair() {
        return Result.success(new Pair(overlay.getString(KEY_A).or("-"), overlay.getString(KEY_B).or("-")));
    }

    private void change(String key, String value) {
        overlay.put(key, value);
        feed.publish(key);
    }

    private static Fn1<Promise<Unit>, Pair> recordingTo(List<Pair> into) {
        return pair -> {
            into.add(pair);
            return Promise.unitPromise();
        };
    }

    private Result<Watched.Subscription> subscribe(Fn1<Promise<Unit>, Pair> callback) {
        return feed.subscribe(OWNER, SECTION, this::bindPair, callback);
    }

    // ---------------------------------------------------------------- registration

    @Test
    void subscribe_deliversTheCurrentValue_onTheCallingThread_beforeReturning() {
        overlay.put(KEY_A, "1");
        overlay.put(KEY_B, "2");
        var seen = new CopyOnWriteArrayList<Pair>();
        var threads = new CopyOnWriteArrayList<Thread>();

        var result = subscribe(pair -> {
            threads.add(Thread.currentThread());
            return recordingTo(seen).apply(pair);
        });

        assertThat(result.isSuccess()).isTrue();
        assertThat(seen).containsExactly(new Pair("1", "2"));
        assertThat(threads).containsExactly(Thread.currentThread());
        assertThat(feed.subscriberCount(OWNER)).isEqualTo(1);
    }

    @Test
    void subscribe_fails_andRegistersNothing_whenTheValueCannotBeBound() {
        var calls = new AtomicInteger();

        var result = feed.subscribe(OWNER, SECTION, () -> Causes.cause("bad config").<Pair> result(), pair -> {
            calls.incrementAndGet();
            return Promise.unitPromise();
        });

        assertThat(result.isFailure()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(feed.subscriberCount(OWNER)).isZero();
    }

    @Test
    void subscribe_fails_andRegistersNothing_whenTheRegistrationCallbackThrows() {
        var result = subscribe(_ -> {
            throw new IllegalStateException("callback failed");
        });

        assertThat(result.isFailure()).isTrue();
        assertThat(feed.subscriberCount(OWNER)).isZero();
        assertThat(events).as("the slice's load failure is the report, not an operator event").isEmpty();
    }

    @Test
    void subscribe_fails_andRegistersNothing_whenTheRegistrationCallbackReturnsAFailedPromise() {
        var result = subscribe(_ -> Causes.cause("refused").promise());

        assertThat(result.isFailure()).isTrue();
        assertThat(feed.subscriberCount(OWNER)).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS) // a callback wait with no bound would hang here, not fail
    void subscribe_fails_andRegistersNothing_whenTheRegistrationCallbackTimesOut() {
        var quick = newFeed(TimeSpan.timeSpan(150).millis());

        var result = quick.subscribe(OWNER, SECTION, this::bindPair, _ -> Promise.<Unit> promise());

        assertThat(result.isFailure()).isTrue();
        assertThat(quick.subscriberCount(OWNER)).isZero();
        assertThat(events).isEmpty();
    }

    // ---------------------------------------------------------------- deliveries

    @Test
    void change_isDelivered_asTheWholeRecord() {
        overlay.put(KEY_A, "1");
        var seen = new CopyOnWriteArrayList<Pair>();
        subscribe(recordingTo(seen));

        overlay.put(KEY_B, "9");
        feed.publish(KEY_B);

        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 2);
        assertThat(seen.getLast()).isEqualTo(new Pair("1", "9"));
    }

    @Test
    void change_leavingTheRecordEqual_deliversNothing() {
        overlay.put(KEY_A, "1");
        var seen = new CopyOnWriteArrayList<Pair>();
        var subscription = (ConfigSubscription<?>) subscribe(recordingTo(seen)).unwrap();

        feed.publish(KEY_A);
        feed.publish(SECTION + ".other");
        await().atMost(Duration.ofSeconds(5)).until(() -> subscription.deliveredRev() == 2);

        assertThat(seen).as("only the registration delivery").hasSize(1);
    }

    @Test
    void deliveries_areSerial_andOrdered_underABurst() {
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var seen = new CopyOnWriteArrayList<Pair>();
        subscribe(pair -> slowRecording(pair, seen, inFlight, maxInFlight));

        for (int i = 1; i <= 50; i++) {
            change(KEY_A, Integer.toString(i));
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> seen.getLast().a().equals("50"));
        assertThat(maxInFlight).as("never two callbacks at once").hasValue(1);
        assertThat(seen.stream().map(Pair::a).filter(a -> !a.equals("-")).map(Integer::parseInt).toList())
            .as("coalesced, but never out of order").isSorted();
    }

    private static Promise<Unit> slowRecording(Pair pair, List<Pair> seen, AtomicInteger inFlight, AtomicInteger max) {
        max.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        sleep(3);
        seen.add(pair);
        inFlight.decrementAndGet();
        return Promise.unitPromise();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void trigger_withASequenceAtOrBelowTheDeliveredOne_isDroppedAndCounted() {
        overlay.put(KEY_A, "1");
        var subscription = (ConfigSubscription<?>) subscribe(recordingTo(new CopyOnWriteArrayList<>())).unwrap();

        subscription.trigger(7, 0);
        await().atMost(Duration.ofSeconds(5)).until(() -> subscription.deliveredRev() == 7);
        subscription.trigger(5, 0);
        subscription.trigger(7, 0);

        assertThat(subscription.staleDropped()).as("5 is below and 7 equals the delivered sequence").isEqualTo(2);
        assertThat(subscription.deliveredRev()).isEqualTo(7);
    }

    @Test
    void sameBatchTie_twoChangesWithEqualRevision_areBothDelivered_withDistinctSequences() {
        var seen = new CopyOnWriteArrayList<Pair>();
        var subscription = (ConfigSubscription<?>) subscribe(recordingTo(seen)).unwrap();

        change(KEY_A, "1");
        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 2);
        var first = subscription.deliveredRev();
        change(KEY_A, "2");
        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 3);

        assertThat(revision).as("both changes carry the same committed revision").hasValue(5);
        assertThat(subscription.deliveredRev()).isGreaterThan(first);
        assertThat(subscription.staleDropped()).isZero();
    }

    @Test
    void sectionPrefix_exactKeyAndDottedChildren_trigger_siblingsDoNot() {
        var subscription = (ConfigSubscription<?>) subscribe(recordingTo(new CopyOnWriteArrayList<>())).unwrap();

        feed.publish(SECTION);
        await().atMost(Duration.ofSeconds(5)).until(() -> subscription.deliveredRev() == 1);
        feed.publish(SECTION + ".a");
        await().atMost(Duration.ofSeconds(5)).until(() -> subscription.deliveredRev() == 2);
        feed.publish(SECTION + "x.a");
        feed.publish("app.order.a");
        feed.publish("other.orders.a");
        sleep(150);

        assertThat(subscription.deliveredRev()).as("keys outside the section are not triggers").isEqualTo(2);
    }

    // ---------------------------------------------------------------- failure containment

    @Test
    void callbackThrowing_raisesOneEvent_onTheTransition_thenRecoveryOnFirstSuccess() {
        var invocations = new ConcurrentHashMap<String, AtomicInteger>();
        var failValues = List.of("2", "3");
        subscribe(pair -> flaky(pair, invocations, failValues));

        change(KEY_A, "2");
        awaitInvocations(invocations, "2");
        change(KEY_A, "3");
        awaitInvocations(invocations, "3");
        change(KEY_A, "4");
        awaitInvocations(invocations, "4");
        await().atMost(Duration.ofSeconds(5)).until(() -> countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED) == 1);

        assertThat(countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED)).as("one event for the whole failure run").isEqualTo(1);
        assertThat(events.getFirst().subject()).isEqualTo(OWNER + "/" + SECTION);
        assertThat(invocations.get("2")).as("a failed value is not retried").hasValue(1);

        feed.publish(KEY_A);
        sleep(150);
        assertThat(invocations.get("4")).as("an equal bind after success calls nothing").hasValue(1);
    }

    @Test
    void equalBindAfterACallbackFailure_neitherRetriesNorAnnouncesRecovery() {
        var invocations = new ConcurrentHashMap<String, AtomicInteger>();
        subscribe(pair -> flaky(pair, invocations, List.of("2")));

        change(KEY_A, "2");
        awaitInvocations(invocations, "2");
        feed.publish(KEY_A);
        feed.publish(KEY_B);
        sleep(200);

        assertThat(invocations.get("2")).hasValue(1);
        assertThat(countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED)).isZero();
        assertThat(countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED)).isEqualTo(1);
    }

    @Test
    void bindFailure_keepsThePreviousRecord_raisesOneEvent_andRecoversOnTheNextGoodBind() {
        var seen = new CopyOnWriteArrayList<Pair>();
        var broken = new AtomicBoolean();
        feed.subscribe(OWNER, SECTION, () -> broken.get() ? Causes.cause("invalid").<Pair> result() : bindPair(), recordingTo(seen));

        broken.set(true);
        change(KEY_A, "x");
        await().atMost(Duration.ofSeconds(5)).until(() -> countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED) == 1);
        broken.set(false);
        change(KEY_A, "x");
        await().atMost(Duration.ofSeconds(5)).until(() -> countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED) == 1);

        assertThat(seen).as("the bind failure delivered nothing; the good bind delivered the new record")
                        .containsExactly(new Pair("-", "-"), new Pair("x", "-"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS) // a callback wait with no bound would hang here, not fail
    void hungCallback_isAbandonedAfterTheTimeout_andTheNextDeliveryProceeds() {
        var quick = newFeed(TimeSpan.timeSpan(200).millis());
        var seen = new CopyOnWriteArrayList<Pair>();
        var hang = new AtomicBoolean();

        quick.subscribe(OWNER, SECTION, this::bindPair, pair -> hanging(pair, seen, hang));
        hang.set(true);
        overlay.put(KEY_A, "1");
        quick.publish(KEY_A);
        await().atMost(Duration.ofSeconds(5)).until(() -> countOf(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED) == 1);
        hang.set(false);
        overlay.put(KEY_A, "2");
        quick.publish(KEY_A);

        await().atMost(Duration.ofSeconds(5)).until(() -> seen.contains(new Pair("2", "-")));
    }

    private static Promise<Unit> hanging(Pair pair, List<Pair> seen, AtomicBoolean hang) {
        seen.add(pair);
        return hang.get() ? Promise.<Unit> promise() : Promise.unitPromise();
    }

    private Promise<Unit> flaky(Pair pair, ConcurrentHashMap<String, AtomicInteger> invocations, List<String> failing) {
        invocations.computeIfAbsent(pair.a(), _ -> new AtomicInteger()).incrementAndGet();
        if (failing.contains(pair.a())) {
            throw new IllegalStateException("callback rejects " + pair.a());
        }
        return Promise.unitPromise();
    }

    private void awaitInvocations(ConcurrentHashMap<String, AtomicInteger> invocations, String value) {
        await().atMost(Duration.ofSeconds(5)).until(() -> invocations.containsKey(value));
        sleep(50);
    }

    private long countOf(OperatorWarningCode code) {
        return events.stream().filter(event -> event.code() == code).count();
    }

    // ---------------------------------------------------------------- no lost update around registration

    @Test
    void registrationRacingAWriter_alwaysEndsOnTheFinalValue() {
        var deadline = System.nanoTime() + 60_000_000_000L;
        var iterations = 0;

        for (int i = 1; i <= 2000 && System.nanoTime() < deadline; i++) {
            raceOnce(i);
            iterations++;
        }

        assertThat(iterations).as("race iterations run before the deadline").isGreaterThan(1000);
    }

    private void raceOnce(int round) {
        var owner = OWNER + "-race-" + round;
        var value = Integer.toString(round);
        var barrier = new AtomicInteger(2);
        var last = new AtomicReference<Pair>();
        var writer = Thread.ofPlatform().start(() -> spinThen(barrier, () -> change(KEY_B, value)));

        spinThen(barrier, () -> feed.subscribe(owner, SECTION, this::bindPair, pair -> remember(last, pair)));
        join(writer);

        await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5)).until(() -> last.get() != null && last.get().b().equals(value));
        feed.releaseAll(owner).await();
    }

    private static Promise<Unit> remember(AtomicReference<Pair> last, Pair pair) {
        last.set(pair);
        return Promise.unitPromise();
    }

    private static void spinThen(AtomicInteger barrier, Runnable action) {
        barrier.decrementAndGet();
        while (barrier.get() > 0) {
            Thread.onSpinWait();
        }
        action.run();
    }

    private static void join(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void changeDuringTheRegistrationCallback_isDeliveredOnceRegistrationCompletes() {
        var last = new AtomicReference<Pair>();
        var calls = new AtomicInteger();

        subscribe(pair -> changeDuringRegistrationCallback(calls, last, pair));

        await().atMost(Duration.ofSeconds(5)).until(() -> last.get() != null && "during".equals(last.get().a()));
    }

    /// The change commits while the registration callback is running: the subscription is not ACTIVE yet, so the
    /// trigger only records it, and registration must schedule the catch-up itself when it goes ACTIVE.
    private Promise<Unit> changeDuringRegistrationCallback(AtomicInteger calls, AtomicReference<Pair> last, Pair pair) {
        if (calls.incrementAndGet() == 1) {
            change(KEY_A, "during");
        }
        return remember(last, pair);
    }

    @Test
    void registrationBeforeReplayCompletes_thenReplayedPuts_convergeOnTheFinalValue() {
        overlay.put(KEY_A, "1");
        var last = new AtomicReference<Pair>();
        subscribe(pair -> remember(last, pair));

        change(KEY_B, "2");
        change(KEY_A, "3");
        change(KEY_B, "4");

        await().atMost(Duration.ofSeconds(5)).until(() -> new Pair("3", "4").equals(last.get()));
    }

    // ---------------------------------------------------------------- locks

    @Test
    void publish_neverWaitsForABinderOrACallback() throws Exception {
        var inBinder = new CountDownLatch(1);
        var releaseBinder = new CountDownLatch(1);
        var binds = new AtomicInteger();
        feed.subscribe(OWNER, SECTION, () -> parkAfterRegistration(binds, inBinder, releaseBinder), _ -> Promise.unitPromise());

        change(KEY_A, "1");
        assertThat(inBinder.await(5, TimeUnit.SECONDS)).isTrue();
        var started = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            feed.publish(KEY_A);
        }
        var other = feed.subscribe(OWNER + "-other", SECTION, this::bindPair, _ -> Promise.unitPromise());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        releaseBinder.countDown();

        assertThat(other.isSuccess()).as("registration is not blocked by a parked binder").isTrue();
        assertThat(elapsedMillis).as("publish and a new registration while a binder is parked").isLessThan(2_000);
        assertThat(feed.holdsFeedLock()).as("the calling thread holds no feed lock afterwards").isFalse();
    }

    private Result<Pair> parkAfterRegistration(AtomicInteger binds, CountDownLatch inBinder, CountDownLatch release) {
        if (binds.incrementAndGet() > 1) {
            inBinder.countDown();
            awaitLatch(release);
        }
        return bindPair();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- release

    @Test
    void releaseAll_stopsDeliveries_dropsTheCount_andIsIdempotent() {
        var seen = new CopyOnWriteArrayList<Pair>();
        subscribe(recordingTo(seen));

        feed.releaseAll(OWNER).await();
        feed.releaseAll(OWNER).await();
        change(KEY_A, "1");
        sleep(200);

        assertThat(feed.subscriberCount(OWNER)).isZero();
        assertThat(seen).as("only the registration delivery").hasSize(1);
    }

    @Test
    void releasedSubscription_isNeverDeliveredTo_evenWhenTriggeredDirectly() {
        var seen = new CopyOnWriteArrayList<Pair>();
        var subscription = (ConfigSubscription<?>) subscribe(recordingTo(seen)).unwrap();

        feed.releaseAll(OWNER).await();
        overlay.put(KEY_A, "after-release");
        subscription.trigger(99, 0);
        sleep(200);

        assertThat(seen).as("a released subscription starts no delivery, whatever still holds a reference to it").hasSize(1);
        assertThat(subscription.deliveredRev()).isLessThan(99);
    }

    @Test
    void releaseDuringADelivery_stopsTheDrainBeforeTheNextDeliveryStarts() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var seen = new CopyOnWriteArrayList<Pair>();
        var calls = new AtomicInteger();
        subscribe(pair -> blockingSecondDelivery(calls, started, finish, seen, pair));

        change(KEY_A, "2");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        change(KEY_A, "3");
        var released = feed.releaseAll(OWNER);
        finish.countDown();
        released.await();
        sleep(200);

        assertThat(seen.stream().map(Pair::a).toList()).as("the pending change behind the running delivery never starts once release has been called")
                                                       .containsExactly("-", "2");
    }

    private Promise<Unit> blockingSecondDelivery(AtomicInteger calls, CountDownLatch started, CountDownLatch finish, List<Pair> seen, Pair pair) {
        seen.add(pair);
        if (calls.incrementAndGet() == 2) {
            started.countDown();
            awaitLatch(finish);
        }
        return Promise.unitPromise();
    }

    @Test
    void releaseAll_awaitsAnInFlightCallback() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        var calls = new AtomicInteger();
        subscribe(pair -> blockingAfterRegistration(calls, started, finish, finished));

        change(KEY_A, "1");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var released = feed.releaseAll(OWNER);

        assertThat(released.isResolved()).as("release waits for the running callback").isFalse();
        finish.countDown();
        released.await();
        assertThat(finished).isTrue();
    }

    private Promise<Unit> blockingAfterRegistration(AtomicInteger calls, CountDownLatch started, CountDownLatch finish, AtomicBoolean finished) {
        if (calls.incrementAndGet() > 1) {
            started.countDown();
            awaitLatch(finish);
            finished.set(true);
        }
        return Promise.unitPromise();
    }

    @Test
    void releaseAll_releasesOnlyTheNamedOwner() {
        subscribe(recordingTo(new CopyOnWriteArrayList<>()));
        feed.subscribe(OWNER + "-other", SECTION, this::bindPair, _ -> Promise.unitPromise());

        feed.releaseAll(OWNER).await();

        assertThat(feed.subscriberCount(OWNER)).isZero();
        assertThat(feed.subscriberCount(OWNER + "-other")).isEqualTo(1);
    }

    @Test
    void shutdown_releasesEverything_andRefusesLaterSubscriptions() {
        subscribe(recordingTo(new CopyOnWriteArrayList<>()));

        feed.shutdown().await();
        var late = subscribe(recordingTo(new CopyOnWriteArrayList<>()));

        assertThat(feed.subscriberCount(OWNER)).isZero();
        assertThat(late.isFailure()).isTrue();
    }

    @Test
    void callbackTimeoutFrom_readsSeconds_andFallsBackToThirty() {
        assertThat(DefaultConfigChangeFeed.callbackTimeoutFrom(Option.none()).duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(DefaultConfigChangeFeed.callbackTimeoutFrom(Option.some(provider("5"))).duration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(DefaultConfigChangeFeed.callbackTimeoutFrom(Option.some(provider("0"))).duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(DefaultConfigChangeFeed.callbackTimeoutFrom(Option.some(provider("soon"))).duration()).isEqualTo(Duration.ofSeconds(30));
    }

    private static ConfigurationProvider provider(String seconds) {
        return IntrinsicConfigProvider.intrinsicConfigProvider("node", Map.of("config.watch.callback.timeout", seconds));
    }
}
