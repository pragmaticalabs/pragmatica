// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.extension.ExtendWith;
import org.pastalab.fray.core.scheduler.PCTScheduler;
import org.pastalab.fray.junit.junit5.FrayTestExtension;
import org.pastalab.fray.junit.junit5.annotations.FrayTest;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeView;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.SpiResourceProvider.Provisioned;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// `closeAll` (node stop) racing `acquire` and the view's `stop` (release), explored by Fray on the real code (#3162).
///
/// Runs only in the `fray` profile (`mvn test -Pfray -pl aether/resource/api`): Fray needs its instrumented JDK and
/// agent, which the profile prepares; the default build excludes the `FrayTest` tag. The interleavings that the
/// map-seam tests ([SharedPoolsAdmissionRaceTest], [SharedPoolsCloseAllTest]) force by hand are here left to Fray's PCT
/// scheduler, so the test pins the invariants rather than one schedule.
///
/// The invariants, taken from the class contract: an acquire that sees the node-stop flag after its own admission
/// "fails with `ProviderClosed`, never a view" of a pool that is closed at once; "each pool closes exactly once"; an
/// acquire started after node stop returned is refused.
@ExtendWith(FrayTestExtension.class)
class SharedPoolsFrayTest {
    static final int ITERATIONS = 300;

    /// One iteration's world. Fray reloads the classes per iteration, and everything is local to the call.
    private static final class Race {
        private final FakeShareableFactory factory = new FakeShareableFactory();
        private final AtomicInteger viewsOfClosedPool = new AtomicInteger();
        private final AtomicInteger servedAfterStop = new AtomicInteger();
        private final AtomicBoolean closeAllReturned = new AtomicBoolean();
        private final List<Promise<Unit>> reported = new CopyOnWriteArrayList<>();
        private final SharedPools pools = SharedPools.sharedPools(this::closePool);

        /// A view handed to a caller was made from a pool that was already closed: the caller got a dead pool.
        private void auditView(FakeConnector view) {
            if (((FakeView) view).poolClosedAtIssue) {
                viewsOfClosedPool.incrementAndGet();
            }
        }

        private Promise<Unit> closePool(Provisioned<?> provisioned) {
            return ((FakeConnector) provisioned.resource()).underlying().close();
        }

        private Promise<Provisioned<?>> provision() {
            return Promise.<Provisioned<?>> success(Provisioned.provisioned((FakeConnector) factory.newPool(), factory));
        }

        private void acquireThenStop(String scope) {
            var afterStop = closeAllReturned.get();
            var acquired = pools.<FakeConnector> acquire("k", scope, factory, this::provision, "label").await();

            if (afterStop && acquired.isSuccess()) {
                servedAfterStop.incrementAndGet();
            }

            acquired.onSuccess(this::auditView)
                    .onSuccess(view -> view.stop().await());
        }

        private void stopNode() {
            reported.addAll(pools.closeAll());
            closeAllReturned.set(true);
        }

        /// Allocates the entries map's table before the race starts: two threads racing `ConcurrentHashMap.initTable`'s
        /// `Thread.yield` spin livelock Fray's own scheduler (a hang in Fray, not in SharedPools, seen in about one run in
        /// five). The racing key is a different one, so the scenario under test is unchanged.
        private void warmUpEntriesTable() {
            pools.<FakeConnector> acquire("warm", "warm-scope", factory, this::provision, "label")
                 .await()
                 .onSuccess(view -> view.stop().await());
        }

        private void run() throws InterruptedException {
            warmUpEntriesTable();

            var first = new Thread(() -> acquireThenStop("slice-a"));
            var second = new Thread(() -> acquireThenStop("slice-b"));
            var stopper = new Thread(this::stopNode);

            first.start();
            second.start();
            stopper.start();
            first.join();
            second.join();
            stopper.join();
            reported.forEach(Promise::await);
        }

        private void assertInvariants() {
            assertThat(viewsOfClosedPool.get()).as("a view was handed out for a pool that was already closed").isZero();
            assertThat(servedAfterStop.get()).as("an acquire that started after node stop returned was served").isZero();
            assertThat(factory.provisioned).as("every pool that was opened is closed exactly once")
                                           .allSatisfy(pool -> assertThat(pool.closeCount()).isEqualTo(1));
            assertThat(pools.size()).as("node stop leaves no entry").isZero();
        }
    }

    @FrayTest(iterations = ITERATIONS, scheduler = PCTScheduler.class,
               resetClassLoaderPerIteration = false, abortThreadExecutionAfterMainExit = true)
    void closeAllRacingAcquireAndRelease_issuesNoViewOfAClosedPool_andClosesEveryPoolOnce() throws InterruptedException {
        var race = new Race();

        race.run();
        race.assertInvariants();
    }
}
