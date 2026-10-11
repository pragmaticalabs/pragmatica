// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.docker.DockerCommandRunner;
import org.pragmatica.aether.environment.docker.DockerComputeProvider;
import org.pragmatica.aether.environment.docker.DockerConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2131: `aether cluster bootstrap` on a docker source no longer fails with "network not found". Real `DockerComputeProvider` on a scripted docker CLI.
/// An absent network is created BEFORE the first container and recorded as ours; a network that already exists is reused and never recorded, so
/// destroy cannot remove one the user provided.
class DockerNetworkBootstrapTest {
    private static final String CLUSTER_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [source.local]
            type = "docker"

            [source.local.core]
            count = 3
            """;

    private static class ScriptedDocker implements DockerCommandRunner {
        final List<String> commands = new ArrayList<>();
        boolean networkExists;
        int containers;

        @Override
        public Promise<String> execute(List<String> command) {
            commands.add(String.join(" ", command));
            if (command.size() > 2 && "network".equals(command.get(1))) {
                return networkCommand(command);
            }

            if ("run".equals(command.get(1))) {
                return Promise.success("container-" + ++containers);
            }

            return Promise.success("running\t/x\tx\tabc");
        }

        private Promise<String> networkCommand(List<String> command) {
            return switch (command.get(2)) {
                case "inspect" -> command.contains("--format")
                                  ? Promise.success("0")
                                  : networkExists ? Promise.success("[{}]") : Causes.cause("No such network").promise();
                case "create" -> {
                    networkExists = true;
                    yield Promise.success("net-id");
                }
                default -> Promise.success("net");
            };
        }

        List<String> networkCommands() {
            return commands.stream().filter(command -> command.startsWith("docker network")).toList();
        }
    }

    private ScriptedDocker docker;

    @BeforeEach
    void install() {
        docker = new ScriptedDocker();
        DockerHostPorts.override = containerId -> Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
        var provider = DockerComputeProvider.dockerComputeProvider(docker, DockerConfig.dockerConfig().unwrap()).unwrap();

        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withCompute(provider));
            }
        };
    }

    @AfterEach
    void remove() {
        DockerHostPorts.override = null;
        ProviderResolver.dockerFactoryOverride = null;
    }

    private static ClusterBootstrapConfig config() {
        return ClusterBootstrapConfigParser.parse(CLUSTER_TOML).flatMap(ClusterBootstrapConfigValidator::validate).unwrap();
    }

    private Result<BootstrapState> bootstrap() {
        var ctx = ClusterBootstrapOrchestrator.BootstrapContext.bootstrapContext(config(),
                                                                                 BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now"),
                                                                                 List.of(),
                                                                                 List.of());

        return BootstrapPhaseProvision.execute(ctx).map(done -> done.state());
    }

    private static List<CreatedResource.DockerNetwork> networks(BootstrapState state) {
        return state.createdResources()
                    .stream()
                    .filter(CreatedResource.DockerNetwork.class::isInstance)
                    .map(CreatedResource.DockerNetwork.class::cast)
                    .toList();
    }

    @Test
    void bootstrapWithoutTheNetwork_createsItBeforeTheFirstContainer_andRecordsItAsOurs() {
        var result = bootstrap();

        assertThat(result.isSuccess()).as("bootstrap succeeds on a host without the network").isTrue();
        var state = result.unwrap();

        assertThat(docker.networkCommands()).contains("docker network create aether-network");
        assertThat(docker.commands.indexOf("docker network create aether-network"))
            .as("created before the first container")
            .isLessThan(docker.commands.indexOf(docker.commands.stream().filter(command -> command.startsWith("docker run")).findFirst().orElseThrow()));
        assertThat(networks(state)).extracting(CreatedResource.DockerNetwork::networkName).containsExactly("aether-network");
    }

    @Test
    void bootstrapOnAnExistingNetwork_reusesIt_createsNothing_andRecordsNothing() {
        docker.networkExists = true;

        var state = bootstrap().unwrap();

        assertThat(docker.networkCommands()).containsExactly("docker network inspect aether-network");
        assertThat(networks(state)).as("a user-provided network is never in the ledger").isEmpty();
    }

    @Test
    void destroyRemovesTheNetworkBootstrapCreated_whenEmpty() {
        var state = BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now")
                                  .withResource(CreatedResource.DockerNetwork.dockerNetwork("aether-network"));

        var result = BootstrapCleanup.cleanup(state);

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.networkCommands()).contains("docker network rm aether-network");
    }

    @Test
    void destroyLeavesAPreExistingNetworkIntact() {
        docker.networkExists = true;
        var state = bootstrap().unwrap();
        var before = docker.commands.size();

        var result = BootstrapCleanup.cleanup(state);

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.commands.subList(before, docker.commands.size())).as("cleanup issues no network command for a network we did not create")
                                                                           .noneMatch(command -> command.startsWith("docker network"));
    }

    @Test
    void destroyLeavesOurNetworkWhenContainersAreStillAttached() {
        var state = BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now")
                                  .withResource(CreatedResource.DockerNetwork.dockerNetwork("aether-network"));
        var attached = new ScriptedDocker() {
            @Override
            public Promise<String> execute(List<String> command) {
                commands.add(String.join(" ", command));

                return command.contains("--format") ? Promise.success("2") : Promise.success("ok");
            }
        };
        var provider = DockerComputeProvider.dockerComputeProvider(attached, DockerConfig.dockerConfig().unwrap()).unwrap();

        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withCompute(provider));
            }
        };

        var result = BootstrapCleanup.cleanup(state);

        assertThat(result.isSuccess()).as("a network in use is left in place, not a cleanup failure").isTrue();
        assertThat(attached.commands).noneMatch(command -> command.startsWith("docker network rm"));
    }

    /// The ledger is a file: ownership has to survive the CLI restarting between bootstrap and destroy.
    @Test
    void theOwnershipRecord_survivesTheLedgerFile() {
        var state = BootstrapState.initialState(ClusterName.clusterName("dock").unwrap(), "h", "now")
                                  .withResource(CreatedResource.DockerNetwork.dockerNetwork("aether-network"));

        var restored = BootstrapStateJson.fromJson(BootstrapStateJson.toJson(state)).unwrap();

        assertThat(networks(restored)).extracting(CreatedResource.DockerNetwork::networkName).containsExactly("aether-network");
    }
}
