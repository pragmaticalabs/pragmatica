// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConfig;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakePool;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.aether.resource.NodeShareable.PoolDemand;
import org.pragmatica.aether.resource.NodeShareable.ServerId;
import org.pragmatica.utility.warning.WarningLevel;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;
import static org.pragmatica.aether.resource.SharedPoolFixtures.acquire;
import static org.pragmatica.aether.resource.SharedPoolFixtures.release;

/// #2973 §6: the datasource demand guard, through the real provider, with a recording sink registered as the node's
/// runtime extension. Probes are fakes answering what a PostgreSQL server would.
class PoolDemandGuardTest {
    private static final String DB = "database";

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final List<Promise<ServerProbe>> probes = new CopyOnWriteArrayList<>();

    private SpiResourceProvider provider(Map<String, FakeConfig> configs,
                                         FakeShareableFactory factory,
                                         Function<FakePool, Promise<ServerProbe>> answer) {
        factory.prober = pool -> Option.some(recorded(answer.apply(pool)));

        var provider = SharedPoolFixtures.providerOf(configs, factory);

        provider.registerExtension(OperatorWarningSink.class, OperatorWarningSink.handingOffTo(raised::add));

        return provider;
    }

    private Promise<ServerProbe> recorded(Promise<ServerProbe> probe) {
        probes.add(probe);

        return probe;
    }

    private static Promise<ServerProbe> answers(int available) {
        return Promise.success(ServerProbe.serverProbe(available, Option.some("127.0.0.1"), 5432));
    }

    private static Promise<ServerProbe> serverFull() {
        return DatabaseConnectorError.connectionFailed("too many clients already", Option.none(), Option.some("53300"))
                                     .promise();
    }

    /// Waits for every probe started so far to resolve, then for the hand-off thread to deliver what they raised.
    private List<OperatorWarning> settled() throws InterruptedException {
        probes.forEach(probe -> probe.await(TIMEOUT));
        Thread.sleep(300);

        return List.copyOf(raised);
    }

    private static Map<String, FakeConfig> perSlice(int warm, int max) {
        return Map.of(DB, FakeConfig.perSlice("pg://h/db").withDemand(warm, max));
    }

    private static void openSlices(SpiResourceProvider provider, int count) {
        for (var i = 0; i < count; i++) {
            acquire(provider, DB, "slice-" + i);
        }
    }

    @Test
    void nineteenDistinctPools_overTheLimitAtWarmUp_warnAtWarmUp() throws Exception { // T11a
        var provider = provider(perSlice(32, 32), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 19);

        assertThat(settled()).last()
                             .satisfies(warning -> assertThat(warning.message()).contains("allows at warm-up:", "608", "100")
                                                                              .doesNotContain("allows under load:"));
    }

    @Test
    void nineteenDistinctPools_overTheLimitUnderLoad_warnUnderLoad() throws Exception { // T11b
        var provider = provider(perSlice(4, 32), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 19);

        assertThat(settled()).last()
                             .satisfies(warning -> assertThat(warning.message()).contains("allows under load:", "76", "608", "100")
                                                                              .doesNotContain("allows at warm-up:"));
    }

    @Test
    void threePools_withinTheLimit_raiseNothing() throws Exception { // T11c
        var provider = provider(perSlice(32, 32), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 3);

        assertThat(settled()).isEmpty();
    }

    @Test
    void probeRefusedWith53300_warnsServerRefused_namingTheCachedLimit() throws Exception { // T11d, T11j
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = provider(perSlice(1, 4),
                                new FakeShareableFactory(),
                                _ -> calls.incrementAndGet() == 1
                                     ? answers(100)
                                     : serverFull());

        openSlices(provider, 2);

        assertThat(settled()).singleElement()
                             .satisfies(warning -> {
                                 assertThat(warning.code()).isEqualTo(OperatorWarningCode.DATASOURCE_SERVER_REFUSED_CONNECTION);
                                 assertThat(warning.subject()).isEqualTo("127.0.0.1:5432");
                                 assertThat(warning.message()).contains("53300", "allows 100 ");
                             });
    }

    @Test
    void probeRefusedWith53300_withoutAnyAnswer_saysTheLimitIsUnknown() throws Exception { // T11d
        var provider = provider(perSlice(1, 4), new FakeShareableFactory(), _ -> serverFull());

        openSlices(provider, 1);

        assertThat(settled()).singleElement()
                             .satisfies(warning -> {
                                 assertThat(warning.subject()).isEqualTo("localhost:5432");
                                 assertThat(warning.message()).contains("allows unknown ");
                             });
    }

    @Test
    void probeFailingOtherwise_raisesNothing_andThePoolServes() throws Exception { // T11e
        var provider = provider(perSlice(32, 32),
                                new FakeShareableFactory(),
                                _ -> Causes.cause("network unreachable")
                                           .promise());
        var pool = acquire(provider, DB, "slice-a");

        assertThat(settled()).isEmpty();
        assertThat(pool.ping()
                       .await(TIMEOUT)
                       .isSuccess()).isTrue();
    }

    @Test
    void closedPools_areDeregistered() throws Exception { // T11f
        var provider = provider(perSlice(5, 5), new FakeShareableFactory(), _ -> answers(99));

        openSlices(provider, 19);
        assertThat(settled()).as("control: 95 within 99").isEmpty();

        for (var i = 0; i < 18; i++) {
            release(provider, "slice-" + i);
        }

        acquire(provider, DB, "slice-new");

        assertThat(settled()).as("20 x 5 = 100 > 99 only if closed pools still count").isEmpty();
    }

    @Test
    void twoSharedPoolsOnOneServer_areBothCounted() throws Exception { // T11g
        var configs = Map.of(DB,
                             FakeConfig.shared("pg://h/one")
                                       .withDemand(60, 60),
                             "database.two",
                             FakeConfig.shared("pg://h/two")
                                       .withDemand(60, 60));
        var provider = provider(configs, new FakeShareableFactory(), _ -> answers(100));

        acquire(provider, DB, "slice-a");
        acquire(provider, "database.two", "slice-b");

        assertThat(settled()).last()
                             .satisfies(warning -> assertThat(warning.message()).contains("120", "2 pools"));
    }

    @Test
    void localhostAnd127_answeringOneAddress_meetInOneSum() throws Exception { // T11h
        var configs = new HashMap<String, FakeConfig>();

        configs.put(DB,
                    FakeConfig.perSlice("pg://localhost/db")
                              .withDemand(60, 60)
                              .withServer("localhost", 5432));
        configs.put("database.ip",
                    FakeConfig.perSlice("pg://127.0.0.1/db")
                              .withDemand(60, 60)
                              .withServer("127.0.0.1", 5432));
        var provider = provider(configs, new FakeShareableFactory(), _ -> answers(100));

        acquire(provider, DB, "slice-a");
        acquire(provider, "database.ip", "slice-b");

        assertThat(settled()).last()
                             .satisfies(warning -> {
                                 assertThat(warning.subject()).isEqualTo("127.0.0.1:5432");
                                 assertThat(warning.message()).contains("120");
                             });
    }

    @Test
    void everyPoolOpen_raisesOnce_underOneServerSubject() throws Exception { // T11i
        var provider = provider(perSlice(32, 32), new FakeShareableFactory(), _ -> answers(10));

        openSlices(provider, 19);

        var warnings = settled();

        assertThat(warnings).hasSize(19);
        assertThat(warnings).extracting(OperatorWarning::code)
                            .containsOnly(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT);
        assertThat(warnings).extracting(OperatorWarning::subject)
                            .containsOnly("127.0.0.1:5432");
    }

    @Test
    void demandExactlyAtTheLimit_atWarmUp_raisesNothing_andOneMoreWarns() throws Exception { // g02
        var provider = provider(perSlice(5, 5), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 20);

        assertThat(settled()).as("20 x 5 == 100 available: not over").isEmpty();

        acquire(provider, DB, "slice-21");

        assertThat(settled()).last()
                             .satisfies(warning -> assertThat(warning.message()).contains("allows at warm-up:", "105"));
    }

    @Test
    void demandExactlyAtTheLimit_underLoad_raisesNothing_andOneMoreWarns() throws Exception { // g04
        var provider = provider(perSlice(1, 5), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 20);

        assertThat(settled()).as("20 x 5 == 100 available: not over").isEmpty();

        acquire(provider, DB, "slice-21");

        assertThat(settled()).last()
                             .satisfies(warning -> assertThat(warning.message()).contains("allows under load:", "105"));
    }

    @Test
    void poolsOnTwoServers_areNotSummed() throws Exception { // g13
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var configs = Map.of(DB,
                             FakeConfig.perSlice("pg://h1/db").withDemand(60, 60).withServer("h1", 5432),
                             "database.two",
                             FakeConfig.perSlice("pg://h2/db").withDemand(60, 60).withServer("h2", 5432));
        var provider = provider(configs,
                                new FakeShareableFactory(),
                                _ -> Promise.success(ServerProbe.serverProbe(100,
                                                                             Option.some("10.0.0." + calls.incrementAndGet()),
                                                                             5432)));

        acquire(provider, DB, "slice-a");
        acquire(provider, "database.two", "slice-b");

        assertThat(settled()).as("60 on each of two servers, 120 only if summed across them").isEmpty();
    }

    @Test
    void probeRefusedWithAnotherSqlState_staysSilent() throws Exception { // g05
        var provider = provider(perSlice(1, 4),
                                new FakeShareableFactory(),
                                _ -> DatabaseConnectorError.connectionFailed("password authentication failed",
                                                                             Option.none(),
                                                                             Option.some("28P01"))
                                                           .promise());

        openSlices(provider, 1);

        assertThat(settled()).isEmpty();
    }

    /// A pool whose probe never answers, with a 200 ms probe bound.
    private static final class ShortProbeFactory extends FakeShareableFactory {
        @Override
        public PoolDemand demand(FakeConfig config) {
            return PoolDemand.poolDemand(config.warm(),
                                         config.max(),
                                         ServerId.serverId("POSTGRESQL", config.host(), config.port()),
                                         TimeSpan.timeSpan(200).millis(),
                                         "short");
        }
    }

    @Test
    void probeThatNeverAnswers_isBoundedByTheProbeTimeout_andRaisesNothing() throws Exception { // g12
        var provider = provider(perSlice(1, 4), new ShortProbeFactory(), _ -> Promise.<ServerProbe> promise());
        var pool = acquire(provider, DB, "slice-a");
        var deadline = System.nanoTime() + 3_000_000_000L;

        while (!probes.getFirst().isResolved() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(probes.getFirst().isResolved()).as("the probe bound fired").isTrue();
        assertThat(raised).isEmpty();
        assertThat(pool.ping().await(TIMEOUT).isSuccess()).isTrue();
    }

    @Test
    void demandBackWithinTheLimit_afterPoolsClose_raisesTheInfoRecovery() throws Exception { // E
        var provider = provider(perSlice(60, 60), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 2);
        assertThat(settled()).extracting(OperatorWarning::code)
                             .containsOnly(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT);

        release(provider, "slice-0");

        var all = settled();

        assertThat(all).last().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT);
            assertThat(warning.code().level()).isEqualTo(WarningLevel.INFO);
            assertThat(warning.code().subsystem()).isEqualTo("datasource");
            assertThat(warning.subject()).isEqualTo("127.0.0.1:5432");
            assertThat(warning.message()).contains("60", "100");
        });
        assertThat(all.stream().filter(w -> w.code() == OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT)).hasSize(1);

        release(provider, "slice-1");

        assertThat(settled()).as("a recovery is published once per warning").hasSize(all.size());
    }

    @Test
    void demandBackWithinTheLimit_afterALaterProbeAnswersAHigherLimit_raisesTheInfoRecovery() throws Exception { // E
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = provider(perSlice(40, 40),
                                new FakeShareableFactory(),
                                _ -> calls.incrementAndGet() < 3
                                     ? answers(50)
                                     : answers(1000));

        openSlices(provider, 3);

        assertThat(settled()).extracting(OperatorWarning::code)
                             .contains(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT)
                             .endsWith(OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT);
    }

    @Test
    void neverOverTheLimit_closingPools_raisesNoRecovery() throws Exception { // E control
        var provider = provider(perSlice(5, 5), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 3);
        release(provider, "slice-0");

        assertThat(settled()).isEmpty();
    }

    @Test
    void refusalFollowedByAnAnsweredProbe_raisesTheInfoRecovery_underTheRefusalSubject() throws Exception { // E
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = provider(perSlice(1, 4),
                                new FakeShareableFactory(),
                                _ -> calls.incrementAndGet() == 1
                                     ? serverFull()
                                     : answers(100));

        openSlices(provider, 2);

        var all = settled();

        assertThat(all).extracting(OperatorWarning::code)
                       .containsExactly(OperatorWarningCode.DATASOURCE_SERVER_REFUSED_CONNECTION,
                                        OperatorWarningCode.DATASOURCE_SERVER_ACCEPTING_CONNECTIONS);
        assertThat(all.get(1).code().level()).isEqualTo(WarningLevel.INFO);
        assertThat(all.get(1).code().subsystem()).isEqualTo("datasource");
        assertThat(all.get(1).subject()).as("the recovery closes the refusal's own subject").isEqualTo(all.get(0).subject());
    }

    @Test
    void answeredProbe_withoutAnEarlierRefusal_raisesNoRecovery() throws Exception { // E control
        var provider = provider(perSlice(1, 4), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 2);

        assertThat(settled()).isEmpty();
    }
}
