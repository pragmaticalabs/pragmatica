// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Write-ahead recording of the VMs a wave creates (cluster-apply spec P2).
class WaveExecutorLedgerTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("prod-cluster").unwrap();
    private static final String CONFIG = """
            config_version = "1.0.0"
            [cluster]
            name = "prod-cluster"
            version = "1.0.0"
            [source.eu-1]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"
            credentials = "hcloud-token"
            [source.eu-1.worker]
            count = 2
            """;
    private static final WaveNodeProvisioning.JoinInputs INPUTS = new WaveNodeProvisioning.JoinInputs("wave-secret",
                                                                                                        List.of("aether-prod-cluster-node-01k0peer000000000000000000:10.0.0.1:8090"));

    @TempDir
    Path stateRoot;

    private Function<ClusterName, Path> originalResolver;
    private final ApplyChaosHarness harness = new ApplyChaosHarness();

    @BeforeEach
    void redirect() {
        originalResolver = ApplyState.stateDirResolver;
        ApplyState.stateDirResolver = name -> stateRoot.resolve(name.value());
    }

    @AfterEach
    void restore() {
        ApplyState.stateDirResolver = originalResolver;
    }

    /// Creates the VM at the harness provider, then the process dies before anything can be written: the shape of
    /// "create then crash" (probe H2b of the P1 verification).
    private final class CrashAfterCreate implements ComputeProvider {
        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            harness.provider.provision(spec);

            throw new ApplyChaosHarness.Killed(ApplyChaosHarness.Step.S2);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return harness.provider.terminate(instanceId);
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return harness.provider.listInstances();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return harness.provider.instanceStatus(instanceId);
        }
    }

    /// H2b: the process is killed right after the provider answered. The VM exists and is billed; the invariant of
    /// spec 4.2 is that its row (INTENDED) was written BEFORE the provider was called. At the P1 head nothing writes a
    /// row at all, so this fails there.
    @Test
    void createThenKill_everyVmTheProviderHoldsHasALedgerRow() {
        var desired = ClusterBootstrapConfigParser.parse(CONFIG).unwrap();
        var source = desired.sources().get("eu-1");

        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).unwrap();

        assertThatThrownBy(() -> WaveExecutor.provisionCloudNodes(new CrashAfterCreate(), desired, source, NodeRole.WORKER, 1, INPUTS))
                .isInstanceOf(ApplyChaosHarness.Killed.class);

        assertThat(harness.provider.totalCreates()).as("the create reached the provider").isEqualTo(1);
        harness.assertEveryVmHasARow(ApplyState.read(CLUSTER).unwrap().unwrap());
    }
}
