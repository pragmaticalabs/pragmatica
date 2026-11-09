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
                             .satisfies(warning -> assertThat(warning.message()).contains("at warm-up", "608", "100"));
    }

    @Test
    void nineteenDistinctPools_overTheLimitUnderLoad_warnUnderLoad() throws Exception { // T11b
        var provider = provider(perSlice(4, 32), new FakeShareableFactory(), _ -> answers(100));

        openSlices(provider, 19);

        assertThat(settled()).last()
                             .satisfies(warning -> assertThat(warning.message()).contains("under load", "76", "608", "100"));
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
}
