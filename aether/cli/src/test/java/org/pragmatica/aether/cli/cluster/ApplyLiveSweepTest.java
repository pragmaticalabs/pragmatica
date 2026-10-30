// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// The paid run's `finally` sweep, driven offline: it deletes a server only when BOTH labels of the run are on the server as
/// the provider reports it, whatever the selector returned.
class ApplyLiveSweepTest {
    private static final String CLUSTER = "p3live20261030abcdef1234";
    private static final String APPLY = "0123456789abcdefghjkmnpqrs";

    private final ApplyChaosHarness harness = new ApplyChaosHarness();
    private final HookedProvider provider = new HookedProvider(harness);
    private final List<String> report = new ArrayList<>();

    private String server(String nodeId, Map<String, String> labels) {
        return harness.provider.create(nodeId, labels).id().value();
    }

    @Test
    void deletesOnlyTheServersCarryingBothLabelsOfTheRun() {
        var ours = server("ours", Map.of("aether-cluster", CLUSTER, "aether-apply", APPLY));
        var noApply = server("no-apply", Map.of("aether-cluster", CLUSTER));
        var blankApply = server("blank-apply", Map.of("aether-cluster", CLUSTER, "aether-apply", " "));
        var anotherRun = server("another-run", Map.of("aether-cluster", CLUSTER, "aether-apply", "zzzzzzzzzzzzzzzzzzzzzzzzzz"));

        // a provider that ignores the selector hands back everything it has
        provider.onFilteredList = _ -> harness.provider.listInstances();
        harness.provider.create("foreign-cluster", Map.of("aether-cluster", "somebody-else", "aether-apply", APPLY));

        var remaining = ApplyHetznerLiveTest.sweep(provider, CLUSTER, APPLY, report);

        assertThat(provider.terminated).containsExactly(ours);
        assertThat(report).anyMatch(line -> line.contains("REFUSED") && line.contains(noApply))
                          .anyMatch(line -> line.contains("REFUSED") && line.contains(blankApply))
                          .anyMatch(line -> line.contains("REFUSED") && line.contains(anotherRun))
                          .anyMatch(line -> line.contains("REFUSED") && line.contains("somebody-else"));
        assertThat(remaining).as("what is still listed under the cluster label afterwards").isNotEmpty();
    }

    @Test
    void whenTheRunDiedBeforeItHadAnApplyId_anyNonBlankApplyLabelOnTheClusterLabelQualifies() {
        var ours = server("ours", Map.of("aether-cluster", CLUSTER, "aether-apply", APPLY));
        var noApply = server("no-apply", Map.of("aether-cluster", CLUSTER));

        ApplyHetznerLiveTest.sweep(provider, CLUSTER, "", report);

        assertThat(provider.terminated).containsExactly(ours);
        assertThat(harness.provider.nodeIdsByServer()).containsKey(noApply);
    }

    /// The test timeout interrupts the thread; every `await()` then fails before the first delete unless the flag is cleared.
    @Test
    void aThreadInterruptedByTheTimeout_stillSweeps() {
        var ours = server("ours", Map.of("aether-cluster", CLUSTER, "aether-apply", APPLY));

        provider.onFilteredList = filter -> {
            Promise<List<InstanceInfo>> later = Promise.promise();

            new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                later.succeed(harness.provider.listInstances(filter).await().unwrap());
            }).start();

            return later;
        };

        Thread.currentThread().interrupt();
        try {
            ApplyHetznerLiveTest.sweep(provider, CLUSTER, APPLY, report);
        } finally {
            Thread.interrupted();
        }

        assertThat(provider.terminated).containsExactly(ours);
        assertThat(report).anyMatch(line -> line.contains("interrupted"));
    }

    @Test
    void aFailedListing_deletesNothing_andIsReportedAsLeftover() {
        server("ours", Map.of("aether-cluster", CLUSTER, "aether-apply", APPLY));
        provider.onFilteredList = _ -> org.pragmatica.lang.utils.Causes.cause("HTTP 503 (test cause)").promise();

        var remaining = ApplyHetznerLiveTest.sweep(provider, CLUSTER, APPLY, report);

        assertThat(provider.terminated).isEmpty();
        assertThat(remaining).singleElement().asString().contains("LISTING FAILED");
    }
}
