// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2124 — the docker bootstrap records a container's name BEFORE it creates the container, so the ledger can hold a name whose container
/// never came to exist (the create failed, or the process died first). Destroy and cleanup-on-failure must treat that name as already gone,
/// and only that: a container that IS on the host and cannot be removed is still a failure.
class DockerIntentRowCleanupTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("intent-2124").unwrap();
    private static final String PRESENT = "aether-intent-2124-node-present";
    private static final String ABSENT = "aether-intent-2124-node-absent";

    private final Set<String> host = new LinkedHashSet<>();
    private final List<String> terminateCalls = new ArrayList<>();
    private boolean removalRefused;
    private boolean listFails;

    private final ComputeProvider docker = new ComputeProvider() {
        @Override
        public ProviderDefaults providerDefaults() {
            return ProviderDefaults.providerDefaults("docker", "", "", "", Option.empty(), false);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Promise.failure(EnvironmentError.operationNotSupported("create"));
        }

        /// Docker reports an absent container as a plain terminate failure, not as InstanceNotFound.
        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            terminateCalls.add(instanceId.value());

            if (removalRefused || !host.contains(instanceId.value())) {
                return EnvironmentError.terminateFailed(instanceId, new RuntimeException("No such object: " + instanceId.value())).promise();
            }

            host.remove(instanceId.value());

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            if (listFails) {
                return EnvironmentError.listInstancesFailed(new RuntimeException("daemon unreachable")).promise();
            }

            return Promise.success(host.stream()
                                       .map(name -> new InstanceInfo(new InstanceId(name),
                                                                     InstanceStatus.RUNNING,
                                                                     List.of(),
                                                                     InstanceType.ON_DEMAND,
                                                                     Map.of(),
                                                                     Option.some(name),
                                                                     Option.none()))
                                       .toList());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.failure(EnvironmentError.operationNotSupported("status"));
        }
    };

    @BeforeEach
    void install() {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withCompute(docker));
            }
        };
    }

    @AfterEach
    void remove() {
        ProviderResolver.dockerFactoryOverride = null;
    }

    private static BootstrapState ledgerNaming(String name) {
        return BootstrapState.initialState(CLUSTER, "h", "now")
                             .withResource(CreatedResource.ProvisionedVm.provisionedVm("docker", name, "local", "core"));
    }

    @Test
    void cleanup_ofANameWhoseContainerNeverCameToExist_isDestroyed_notFailed() {
        host.add(PRESENT);

        var result = BootstrapCleanup.cleanup(ledgerNaming(ABSENT));

        assertThat(result.isSuccess()).as("an absent container is already gone").isTrue();
        assertThat(host).as("and nothing else on the host was touched").containsExactly(PRESENT);
    }

    @Test
    void cleanup_ofAContainerThatIsThere_removesIt() {
        host.add(PRESENT);

        var result = BootstrapCleanup.cleanup(ledgerNaming(PRESENT));

        assertThat(result.isSuccess()).isTrue();
        assertThat(host).isEmpty();
    }

    @Test
    void cleanup_ofAContainerThatIsThereButCannotBeRemoved_isStillAFailure() {
        host.add(PRESENT);
        removalRefused = true;

        var result = BootstrapCleanup.cleanup(ledgerNaming(PRESENT));

        assertThat(result.isFailure()).as("the container is on the host: not gone").isTrue();
        assertThat(host).containsExactly(PRESENT);
    }

    @Test
    void cleanup_whenTheHostCannotBeListed_keepsTheTerminateFailure() {
        listFails = true;

        var result = BootstrapCleanup.cleanup(ledgerNaming(ABSENT));

        assertThat(result.isFailure()).as("could not look is not proof of absence").isTrue();
    }
}
