package org.pragmatica.consensus.net;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens.Admission;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// The lock that orders an offer's count against the node leaving WIPED (#2429, ID-1c), under contention. A registry without a ledger
/// keeps no journal, so a round is pure memory: three seats' offers race three attempts to leave WIPED, released together by a
/// busy-wait barrier on a volatile flag (a blocking barrier would hand the threads out one at a time). After every round the node
/// is not wiped and holds no recorded offer: an offer counted after the node left WIPED would sit there for good.
class OfferLockStressTest {
    private static final int ROUNDS = 150_000;
    private static final int CONTENDERS = 6;
    private static final long DEADLINE_NANOS = TimeSpan.timeSpan(120).seconds().nanos();
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId[] SEATS = {new NodeId("seat-a"), new NodeId("seat-c"), new NodeId("seat-d")};
    private static final IdentityFacts ONE_FACT = IdentityFacts.terminated(new NodeId("gone"), BootTokens.TOKEN_CONFLICT);

    private volatile BootTokens current;
    private volatile int round = -1;
    private final AtomicInteger finished = new AtomicInteger();

    @Test
    void offersRacingTheEndOfTheWipedState_leaveNoRecordedOfferBehind() throws Exception {
        var started = System.nanoTime();
        var workers = new Thread[CONTENDERS];
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        for (var index = 0; index < CONTENDERS; index++) {
            var contender = index;

            workers[index] = new Thread(() -> run(contender, started, failure), "offer-lock-stress-" + index);
            workers[index].setDaemon(true);
            workers[index].start();
        }

        var stale = 0;
        var notReleased = 0;

        for (var current = 0; current < ROUNDS && failure.get() == null; current++) {
            var registry = BootTokens.bootTokens(1L);

            registry.seats(SELF, Set.of(SEATS));
            registry.preload(IdentityFacts.identityFacts(), true);
            this.current = registry;
            finished.set(0);
            round = current;
            spinUntil(() -> finished.get() == CONTENDERS, started, "the contenders of round " + current);
            stale += recordedOffers(registry).isEmpty() ? 0 : 1;
            notReleased += registry.admit(new NodeId("peer"), 7L) == Admission.ADMITTED ? 0 : 1;
        }

        round = ROUNDS;
        for (var worker : workers) {
            worker.join(5_000);
        }

        assertThat(failure.get()).isNull();
        assertThat(notReleased).as("rounds in which the node was still wiped after the attempts to leave it").isZero();
        assertThat(stale).as("rounds that left an offer recorded on a node that is no longer wiped").isZero();
    }

    private void run(int contender, long started, java.util.concurrent.atomic.AtomicReference<Throwable> failure) {
        try {
            for (var expected = 0; expected < ROUNDS; expected++) {
                var target = expected;

                spinUntil(() -> round == target || round >= ROUNDS, started, "round " + expected);
                if (round >= ROUNDS) {
                    return;
                }

                act(contender, current);
                finished.incrementAndGet();
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void act(int contender, BootTokens registry) {
        if (contender < SEATS.length) {
            registry.mergeOffered(SEATS[contender], Option.some(ONE_FACT));
        } else {
            registry.merged(IdentityFacts.identityFacts());
        }
    }

    /// A busy-wait with a wall-clock deadline: a lost wake-up fails the test instead of hanging it.
    private static void spinUntil(java.util.function.BooleanSupplier condition, long started, String what) {
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - started > DEADLINE_NANOS) {
                throw new AssertionError("timed out waiting for " + what);
            }

            Thread.onSpinWait();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<NodeId, IdentityFacts> recordedOffers(BootTokens registry) {
        try {
            Method accessor = registry.getClass().getDeclaredMethod("recordedOffers");

            accessor.setAccessible(true);

            return (Map<NodeId, IdentityFacts>) accessor.invoke(registry);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("the registry has no recordedOffers component", e);
        }
    }
}
