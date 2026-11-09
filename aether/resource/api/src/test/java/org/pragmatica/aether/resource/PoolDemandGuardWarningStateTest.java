// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.NodeShareable.PoolDemand;
import org.pragmatica.aether.resource.NodeShareable.ServerId;
import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;

/// The guard's warning state (#2973 §6), driven directly: a remapped server must not strand its warning, and a raise
/// interleaved with a recovery must not leave a published warning that nothing can clear.
class PoolDemandGuardWarningStateTest {
    private static final ServerId CONFIGURED = ServerId.serverId("POSTGRESQL", "db.local", 5432);
    private static final ServerId OTHER = ServerId.serverId("POSTGRESQL", "other.local", 5432);

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final FakeShareableFactory factory = new FakeShareableFactory();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);

    private PoolDemandGuard guardWith(Supplier<OperatorWarningSink> sinkSupplier) {
        return PoolDemandGuard.poolDemandGuard(sinkSupplier);
    }

    private void open(PoolDemandGuard guard, String poolId, ServerId configured, int max) {
        guard.opened(poolId,
                     (FakeConnector) factory.newPool(),
                     factory,
                     PoolDemand.poolDemand(max, max, configured, TIMEOUT, poolId),
                     poolId);
    }

    private static ServerProbe answer(int available, String addr) {
        return ServerProbe.serverProbe(available, Option.some(addr), 5432);
    }

    private List<OperatorWarningCode> codes() throws InterruptedException {
        Thread.sleep(300);

        return raised.stream().map(OperatorWarning::code).toList();
    }

    private List<String> subjects() {
        return raised.stream().map(OperatorWarning::subject).toList();
    }

    @Test
    void remappedServer_clearsItsDemandWarning_andReevaluatesUnderTheNewId() throws Exception {
        var guard = guardWith(() -> sink);

        open(guard, "p1", CONFIGURED, 60);
        open(guard, "p2", CONFIGURED, 60);
        guard.answered(CONFIGURED, answer(100, "10.0.0.1"));
        guard.answered(CONFIGURED, answer(100, "10.0.0.2"));

        assertThat(codes()).containsExactly(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT,
                                            OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT,
                                            OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT);
        assertThat(subjects()).containsExactly("10.0.0.1:5432", "10.0.0.1:5432", "10.0.0.2:5432");
    }

    @Test
    void remappedServer_clearsItsRefusal_withTheAcceptingEvent() throws Exception {
        var guard = guardWith(() -> sink);

        open(guard, "p1", CONFIGURED, 10);
        guard.answered(CONFIGURED, answer(1000, "10.0.0.1"));
        guard.refused(CONFIGURED, DatabaseConnectorError.connectionFailed("too many clients", Option.none(), Option.some("53300")));
        guard.answered(CONFIGURED, answer(1000, "10.0.0.2"));

        assertThat(codes()).containsExactly(OperatorWarningCode.DATASOURCE_SERVER_REFUSED_CONNECTION,
                                            OperatorWarningCode.DATASOURCE_SERVER_ACCEPTING_CONNECTIONS);
        assertThat(subjects()).containsOnly("10.0.0.1:5432");
    }

    @Test
    void remappedAway_whileAnotherConfiguredServerStillUsesTheOldId_keepsTheWarning() throws Exception {
        var guard = guardWith(() -> sink);

        open(guard, "p1", CONFIGURED, 60);
        open(guard, "p2", OTHER, 60);
        guard.answered(CONFIGURED, answer(100, "10.0.0.1"));
        guard.answered(OTHER, answer(100, "10.0.0.1"));
        guard.answered(CONFIGURED, answer(100, "10.0.0.2"));

        assertThat(codes()).doesNotContain(OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT);
    }

    /// The sink supplier is read inside the raise, after the warning set was updated: pausing the first read lets a
    /// recovery run on another thread in the middle of the raise. Under the guard's lock the recovery waits for the raise;
    /// without it the recovery publishes first, and the raise then publishes a warning no set entry stands for.
    @Test
    void raiseInterleavedWithRecovery_neverLeavesAPublishedWarningNothingCanClear() throws Exception {
        var inRaise = new CountDownLatch(1);
        var recovered = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var guard = guardWith(() -> pausedOnFirstRead(reads, inRaise, recovered));

        open(guard, "p1", CONFIGURED, 10);
        var raising = new Thread(() -> guard.answered(CONFIGURED, answer(5, "127.0.0.1")));
        var recovering = new Thread(() -> recoverAfter(guard, inRaise, recovered));

        raising.start();
        recovering.start();
        raising.join(TIMEOUT.duration().toMillis());
        recovering.join(TIMEOUT.duration().toMillis());
        guard.answered(CONFIGURED, answer(100, "127.0.0.1"));

        assertThat(codes()).as("a published warning must end cleared, whatever the interleaving")
                           .last()
                           .isEqualTo(OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT);
    }

    private OperatorWarningSink pausedOnFirstRead(AtomicInteger reads, CountDownLatch inRaise, CountDownLatch recovered) {
        if (reads.incrementAndGet() == 1) {
            inRaise.countDown();
            await(recovered, 700);
        }

        return sink;
    }

    private static void recoverAfter(PoolDemandGuard guard, CountDownLatch inRaise, CountDownLatch recovered) {
        await(inRaise, 5000);
        guard.answered(CONFIGURED, answer(100, "127.0.0.1"));
        recovered.countDown();
    }

    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
