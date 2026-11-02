package org.pragmatica.consensus.net;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens.Admission;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// The lock that orders an offer's count against the node leaving WIPED (#2429, ID-1c), under contention. A registry without a ledger
/// keeps no journal, so a round is pure memory: offers of configured seats race attempts to leave WIPED, released together by a
/// busy-wait barrier on a volatile flag (a blocking barrier would hand the threads out one at a time). After every round the node
/// is not wiped and holds no recorded offer: an offer counted after the node left WIPED would sit there for good.
///
/// Sized for the machine it runs on: as many contenders as there are processors (at most six, at least two), so that spinning
/// threads never starve each other on a small CI runner, and as many rounds as fit a time budget. Reaching the budget ends the run
/// and is not a failure; only a recorded stale offer is. Every spin has a wall-clock deadline, so a lost wake-up fails instead of hanging.
class OfferLockStressTest {
    private static final int MAX_ROUNDS = 150_000;
    private static final int MIN_ROUNDS = 100;
    private static final long BUDGET_NANOS = TimeSpan.timeSpan(10).seconds().nanos();
    private static final long SPIN_DEADLINE_NANOS = TimeSpan.timeSpan(30).seconds().nanos();
    private static final int SPINS_BEFORE_YIELD = 20_000;
    private static final NodeId SELF = new NodeId("self");
    private static final NodeId[] SEATS = {new NodeId("seat-a"), new NodeId("seat-c"), new NodeId("seat-d")};
    private static final IdentityFacts ONE_FACT = IdentityFacts.terminated(new NodeId("gone"), BootTokens.TOKEN_CONFLICT);

    private final int contenders = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
    private final int offering = Math.min(SEATS.length, Math.max(1, contenders / 2));
    private volatile BootTokens current;
    private volatile int round = -1;
    private volatile boolean stopped;
    private final AtomicInteger arrived = new AtomicInteger();
    private volatile int stale;
    private volatile int notReleased;
    private volatile int rounds;

    @Test
    void offersRacingTheEndOfTheWipedState_leaveNoRecordedOfferBehind() throws Exception {
        var started = System.nanoTime();
        var workers = new Thread[contenders];
        var failure = new AtomicReference<Throwable>();

        current = freshWipedRegistry();
        round = 0;
        for (var index = 0; index < contenders; index++) {
            var contender = index;

            workers[index] = new Thread(() -> run(contender, started, failure), "offer-lock-stress-" + index);
            workers[index].setDaemon(true);
            workers[index].start();
        }

        for (var worker : workers) {
            worker.join(SPIN_DEADLINE_NANOS / 1_000_000 + BUDGET_NANOS / 1_000_000);
        }

        System.out.println("OfferLockStressTest: " + contenders + " contenders, " + rounds + " rounds in " + (System.nanoTime() - started) / 1_000_000 + " ms");
        assertThat(failure.get()).isNull();
        assertThat(rounds).as("the budget must have bought at least a few rounds").isGreaterThanOrEqualTo(MIN_ROUNDS);
        assertThat(notReleased).as("rounds in which the node was still wiped after the attempts to leave it").isZero();
        assertThat(stale).as("rounds that left an offer recorded on a node that is no longer wiped").isZero();
    }

    private static BootTokens freshWipedRegistry() {
        var registry = BootTokens.bootTokens(1L);

        registry.seats(SELF, Set.of(SEATS));
        registry.preload(IdentityFacts.identityFacts(), true);

        return registry;
    }

    /// Every contender is a racer; contender 0 also closes each round (it waits for the others, checks, and publishes the next
    /// registry), so no extra thread spins and the racers can have the processors to themselves.
    private void run(int contender, long started, AtomicReference<Throwable> failure) {
        try {
            for (var expected = 0; !stopped; expected++) {
                var target = expected;

                spinUntil(() -> round == target || stopped, "round " + expected);
                if (stopped) {
                    return;
                }

                act(contender, current);
                if (contender == 0) {
                    closeRound(expected, started);
                } else {
                    arrived.incrementAndGet();
                }
            }
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
            stopped = true;
        }
    }

    private void closeRound(int closing, long started) {
        spinUntil(() -> arrived.get() == contenders - 1 || stopped, "the contenders of round " + closing);

        var registry = current;

        stale += recordedOffers(registry).isEmpty() ? 0 : 1;
        notReleased += registry.admit(new NodeId("peer"), 7L) == Admission.ADMITTED ? 0 : 1;
        rounds = closing + 1;
        if (closing + 1 >= MAX_ROUNDS || System.nanoTime() - started > BUDGET_NANOS) {
            stopped = true;

            return;
        }

        arrived.set(0);
        current = freshWipedRegistry();
        round = closing + 1;
    }

    private void act(int contender, BootTokens registry) {
        if (contender < offering) {
            registry.mergeOffered(SEATS[contender], Option.some(ONE_FACT));
        } else {
            registry.merged(IdentityFacts.identityFacts());
        }
    }

    /// A busy-wait with a wall-clock deadline, yielding now and then so that more spinners than processors cannot starve the one
    /// that has the work (only when the processors are oversubscribed: the contenders are sized to the processor count).
    private static void spinUntil(BooleanSupplier condition, String what) {
        var waitStarted = System.nanoTime();
        var spins = 0;

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - waitStarted > SPIN_DEADLINE_NANOS) {
                throw new AssertionError("timed out waiting for " + what);
            }

            if (++spins % SPINS_BEFORE_YIELD == 0) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
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
