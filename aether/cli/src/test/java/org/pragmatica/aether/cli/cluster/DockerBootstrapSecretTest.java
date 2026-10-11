// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterIdentityEnv;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.docker.DockerCommandRunner;
import org.pragmatica.aether.environment.docker.DockerConfig;
import org.pragmatica.aether.environment.docker.DockerEnvironmentIntegration;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #2089: the cluster secret has ONE source in a docker bootstrap, the one the CLI holds in `ctx.clusterSecret()` and derives its admin key
/// from. The docker provider forwards `AETHER_CLUSTER_SECRET` from its host environment; left at the process environment, the nodes boot
/// with whatever the operator's shell holds (nothing, or something else) and the CLI's key is "Invalid API key" at every node, so quorum
/// is never observed. Run on the real call site, `docker run` commands recorded by a fake runner.
class DockerBootstrapSecretTest {
    private static final String MINTED = "cli-minted-secret-0123456789";
    private final List<List<String>> commands = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> namesByContainerId = new ConcurrentHashMap<>();

    private final DockerCommandRunner runner = command -> {
        commands.add(command);
        if (command.contains("run")) {
            var id = "cid-" + commands.size();

            namesByContainerId.put(id, command.get(command.indexOf("--name") + 1));

            return Promise.success(id);
        }

        if (command.contains("inspect")) {
            var name = namesByContainerId.getOrDefault(command.getLast(), "unknown");

            return Promise.success("running\t/" + name + "\t" + name + "\t" + command.getLast() + "\tdock\tcore\t" + name);
        }

        return Promise.success("");
    };

    @BeforeEach
    void install() {
        DockerHostPorts.override = containerId -> Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return DockerEnvironmentIntegration.dockerEnvironmentIntegration(runner, DockerConfig.dockerConfig().unwrap())
                                                   .map(EnvironmentIntegration.class::cast);
            }
        };
    }

    @AfterEach
    void remove() {
        DockerHostPorts.override = null;
        ProviderResolver.dockerFactoryOverride = null;
    }

    private BootstrapContext context(String secret) {
        var config = ClusterBootstrapConfigParser.parse("""
                                                        config_version = "1.0.0"

                                                        [cluster]
                                                        name = "dock"
                                                        version = "1.0.0"

                                                        [source.d]
                                                        type = "docker"

                                                        [source.d.core]
                                                        count = 3
                                                        """).unwrap();

        return BootstrapContext.bootstrapContext(config,
                                                 BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                 List.of(),
                                                 List.of())
                               .withClusterSecret(secret);
    }

    private List<List<String>> runCommands() {
        return commands.stream().filter(command -> command.contains("run")).toList();
    }

    @Test
    void everyDockerNode_isCreatedWithTheSecretTheCliHolds() {
        var ctx = context(MINTED);

        var result = BootstrapPhaseProvision.provisionSource(ctx,
                                                             sourceNameOrDefault("d"),
                                                             ctx.config().sources().get("d"),
                                                             8080,
                                                             ClusterName.clusterName("dock").unwrap());

        assertThat(result.<String> fold(cause -> cause.message(), nodes -> "ok")).isEqualTo("ok");
        assertThat(runCommands()).hasSize(3);
        for (var command : runCommands()) {
            assertThat(command).as("the CLI's secret, once").filteredOn(arg -> arg.startsWith("AETHER_CLUSTER_SECRET=")).containsExactly("AETHER_CLUSTER_SECRET=" + MINTED);
        }
    }

    /// An ambient value must not win. The test JVM's own environment cannot be set, so the ambient case is the provider's host env being
    /// asked for the secret directly: the resolver's answer is the CLI's secret whatever the process holds.
    @Test
    void theResolvedProvidersHostEnv_answersTheSecretFromTheCli_andDelegatesEveryOtherName() {
        var provider = ProviderResolver.resolveDockerCompute(context(MINTED).config().sources().get("d"), MINTED).unwrap();

        assertThat(provider).isInstanceOf(org.pragmatica.aether.environment.docker.DockerComputeProvider.class);
        var hostEnv = ((org.pragmatica.aether.environment.docker.DockerComputeProvider) provider).hostEnv();

        assertThat(hostEnv.apply(ProviderResolver.CLUSTER_SECRET_VAR)).isEqualTo(MINTED);
        assertThat(hostEnv.apply("PATH")).as("every other name is the process environment").isEqualTo(System.getenv("PATH"));
    }

    @Test
    void theSecretVariableName_isTheOneTheProviderForwards() {
        assertThat(ClusterIdentityEnv.IDENTITY_VARS).contains(ProviderResolver.CLUSTER_SECRET_VAR);
    }
}
