// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// A cluster name whose minted node ids cannot be provider labels (63 characters; `aether-<cluster>-node-<ulid>` is 39 plus the
/// name) refuses the WHOLE apply at plan time: typed, naming the limit, zero creates, no ledger.
class WaveExecutorLongClusterNameTest {
    private static final String THIRTY = "abcdefghijklmnopqrstuvwxyz0123";
    private static final String TWENTY_FOUR = "abcdefghijklmnopqrstuvwx";

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final ApplyChaosHarness harness = new ApplyChaosHarness();
    private final HookedProvider provider = new HookedProvider(harness);
    private final AtomicInteger providerResolutions = new AtomicInteger();

    @AfterEach
    void restore() {
        ProviderResolver.dockerFactoryOverride = null;
    }

    private ClusterBootstrapConfig dockerConfig(String clusterName) {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                providerResolutions.incrementAndGet();

                return Result.success(EnvironmentIntegration.withLocalCompute(provider));
            }
        };

        return ClusterBootstrapConfigParser.parse("""
                config_version = "1.0.0"
                [cluster]
                name = "%s"
                version = "1.0.0"
                [source.local]
                type = "docker"
                [source.local.core]
                count = 1
                """.formatted(clusterName)).unwrap();
    }

    private static DiffPlan plan() {
        return DiffPlan.diffPlan(List.of(new DiffAction.AddRole(sourceNameOrDefault("local"), NodeRole.CORE, 2)), List.of(), List.of(), List.of());
    }

    @Test
    void aThirtyCharacterClusterName_refusesTheWaveBeforeTheFirstCreate() {
        var desired = dockerConfig(THIRTY);
        var recorder = ledger.recorder(ClusterName.clusterName(THIRTY).unwrap());

        WaveExecutor.execute(plan(), desired, desired, recorder).onSuccess(_ -> fail("nodes whose ids cannot be labels must not be created"))
                    .onFailure(cause -> assertThat(cause).isInstanceOf(ApplyLabels.NodeIdTooLong.class));

        assertThat(harness.provider.totalCreates()).isZero();
        assertThat(providerResolutions.get()).as("no provider was resolved").isZero();
        assertThat(recorder.snapshot().resources()).isEmpty();
    }

    @Test
    void theWholeApply_isRefusedBeforeTheLedgerIsWritten_andBeforeTheExecutorRuns() {
        var desired = dockerConfig(THIRTY);
        var executed = new AtomicInteger();
        var plan = ClusterBootstrapConfigDiff.diff(dockerConfig(THIRTY), ClusterBootstrapConfigParser.parse("""
                config_version = "1.0.0"
                [cluster]
                name = "%s"
                version = "1.0.0"
                [source.local]
                type = "docker"
                [source.local.core]
                count = 3
                """.formatted(THIRTY)).unwrap());

        ApplyOrchestrator.executeAndPersist(plan, desired, desired, (p, stored, wanted, recorder) -> {
            executed.incrementAndGet();

            return Result.success(ApplyResult.applyResult(p, 0, 0, 0));
        }).onSuccess(_ -> fail("refused up front"))
          .onFailure(cause -> assertThat(cause.message()).contains("too long").contains("63-character").contains("at most 24 characters").contains("Nothing was created"));

        assertThat(executed.get()).isZero();
        assertThat(ApplyState.read(ClusterName.clusterName(THIRTY).unwrap()).unwrap().isPresent()).as("no ledger was written").isFalse();
        assertThat(harness.provider.totalCreates()).isZero();
    }

    @Test
    void aTwentyFourCharacterClusterName_isNotRefused() {
        var desired = dockerConfig(TWENTY_FOUR);
        var recorder = ledger.recorder(ClusterName.clusterName(TWENTY_FOUR).unwrap());

        WaveExecutor.execute(plan(), desired, desired, recorder).onFailure(cause -> fail(cause.message()));

        assertThat(harness.provider.totalCreates()).isEqualTo(2);
    }
}
