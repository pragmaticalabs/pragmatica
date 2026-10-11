// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

/// #2131: `aether cluster bootstrap` on a docker source no longer fails with "network not found". Real `DockerComputeProvider` on a scripted docker CLI
/// that keeps networks with their owner label. An absent network is created (labelled) BEFORE the first container and recorded as ours; a network that
/// exists without our label, or with another cluster's, is reused and never recorded; one carrying OUR label is ours again. Destroy removes exactly the
/// recorded name, only while it carries our label and is empty.
class DockerNetworkBootstrapTest {
    private static final String CLUSTER = "dock";
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

    private static final String SSH_TOML = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [operations.tls]
            auto_generate = false

            [runtime.default]
            type = "container"
            image = "aether-node:1.0.0"

            [source.s]
            type = "ssh"

            [source.s.core]
            hosts = ["10.0.0.1", "10.0.0.2", "10.0.0.3"]
            """;

    private static class ScriptedDocker implements DockerCommandRunner {
        final List<String> commands = new ArrayList<>();
        final Map<String, String> labels = new HashMap<>();
        final Map<String, Integer> attached = new HashMap<>();
        /// The `aether.cluster` label of each container the scripted host holds; a container not listed carries this test's cluster.
        final Map<String, String> containerCluster = new HashMap<>();
        int containers;
        boolean rmFails;

        @Override
        public Promise<String> execute(List<String> command) {
            commands.add(String.join(" ", command));
            if (command.size() > 2 && "network".equals(command.get(1))) {
                return networkCommand(command);
            }

            if ("run".equals(command.get(1))) {
                return Promise.success("container-" + ++containers);
            }

            if (command.size() > 3 && "inspect".equals(command.get(1)) && command.get(3).startsWith("{{with index .Config.Labels \"aether.cluster\"}}")) {
                return Promise.success(containerCluster.getOrDefault(command.getLast(), CLUSTER));
            }

            return Promise.success("running\t/x\tx\tabc");
        }

        private Promise<String> networkCommand(List<String> command) {
            var name = command.getLast();

            return switch (command.get(2)) {
                case "inspect" -> labels.containsKey(name) ? Promise.success(inspectOutput(command, name)) : Causes.cause("No such network").promise();
                case "create" -> {
                    var label = command.get(command.indexOf("--label") + 1);

                    labels.put(name, label.substring(label.indexOf('=') + 1));
                    yield Promise.success("net-id");
                }
                default -> {
                    if (rmFails) {
                        yield Causes.cause("Docker command failed (exit 1): error while removing network: network has active endpoints").promise();
                    }

                    labels.remove(name);
                    yield Promise.success(name);
                }
            };
        }

        private String inspectOutput(List<String> command, String name) {
            var format = command.get(command.indexOf("--format") + 1);

            return format.contains("len .Containers") ? labels.get(name) + "|" + attached.getOrDefault(name, 0) : labels.get(name);
        }

        List<String> networkCommands() {
            return commands.stream().filter(command -> command.startsWith("docker network")).toList();
        }
    }

    private ScriptedDocker docker;
    private DockerComputeProvider provider;
    /// The `cluster_name` of every docker provider the code under test asked the resolver for (null: it asked for none).
    private final List<String> requestedScopes = new ArrayList<>();

    private void useProvider(ScriptedDocker runner) {
        provider = DockerComputeProvider.dockerComputeProvider(runner, DockerConfig.dockerConfig().unwrap().withClusterName(CLUSTER)).unwrap();
        requestedScopes.clear();
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                var scope = config.compute().get("cluster_name");

                requestedScopes.add(scope);

                var scoped = DockerConfig.dockerConfig().unwrap();

                return DockerComputeProvider.dockerComputeProvider(runner, scope == null ? scoped : scoped.withClusterName(scope))
                                            .map(EnvironmentIntegration::withCompute);
            }
        };
    }

    @BeforeEach
    void install() {
        docker = new ScriptedDocker();
        DockerHostPorts.override = containerId -> Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
        useProvider(docker);
    }

    @AfterEach
    void remove() {
        DockerHostPorts.override = null;
        ProviderResolver.dockerFactoryOverride = null;
    }

    private static ClusterBootstrapConfig config(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).flatMap(ClusterBootstrapConfigValidator::validate).unwrap();
    }

    private static BootstrapState initial() {
        return BootstrapState.initialState(ClusterName.clusterName(CLUSTER).unwrap(), "h", "now");
    }

    private Result<BootstrapState> bootstrap(String toml, BootstrapState state) {
        var ctx = ClusterBootstrapOrchestrator.BootstrapContext.bootstrapContext(config(toml), state, List.of(), List.of());

        return BootstrapPhaseProvision.execute(ctx).map(done -> done.state());
    }

    private static List<CreatedResource.DockerNetwork> networks(BootstrapState state) {
        return state.createdResources()
                    .stream()
                    .filter(CreatedResource.DockerNetwork.class::isInstance)
                    .map(CreatedResource.DockerNetwork.class::cast)
                    .toList();
    }

    private static BootstrapState ledgerRecording(String network) {
        return initial().withResource(CreatedResource.DockerNetwork.dockerNetwork(network));
    }

    @Test
    void bootstrapWithoutTheNetwork_createsItLabelled_beforeTheFirstContainer_andRecordsItAsOurs() {
        var result = bootstrap(CLUSTER_TOML, initial());

        assertThat(result.isSuccess()).as("bootstrap succeeds on a host without the network").isTrue();
        assertThat(docker.networkCommands()).contains("docker network create --label aether.created-by-cluster=dock aether-network");
        assertThat(docker.commands.indexOf("docker network create --label aether.created-by-cluster=dock aether-network"))
            .as("created before the first container")
            .isLessThan(docker.commands.indexOf(docker.commands.stream().filter(command -> command.startsWith("docker run")).findFirst().orElseThrow()));
        assertThat(networks(result.unwrap())).extracting(CreatedResource.DockerNetwork::networkName).containsExactly("aether-network");
    }

    @Test
    void bootstrapOnAnUnlabelledNetwork_reusesIt_createsNothing_andRecordsNothing() {
        docker.labels.put("aether-network", "");

        var state = bootstrap(CLUSTER_TOML, initial()).unwrap();

        assertThat(docker.networkCommands()).noneMatch(command -> command.startsWith("docker network create"));
        assertThat(networks(state)).as("a user-provided network is never in the ledger").isEmpty();
    }

    @Test
    void bootstrapOnAnotherClustersNetwork_reusesIt_andRecordsNothing() {
        docker.labels.put("aether-network", "other");

        assertThat(networks(bootstrap(CLUSTER_TOML, initial()).unwrap())).isEmpty();
    }

    /// A re-run over a network an earlier run of this cluster left in place owns it again, so it is recorded and will not leak.
    @Test
    void bootstrapOnANetworkCarryingOurOwnLabel_ownsItAgain_andRecordsIt() {
        docker.labels.put("aether-network", CLUSTER);

        var state = bootstrap(CLUSTER_TOML, initial()).unwrap();

        assertThat(docker.networkCommands()).noneMatch(command -> command.startsWith("docker network create"));
        assertThat(networks(state)).extracting(CreatedResource.DockerNetwork::networkName).containsExactly("aether-network");
    }

    /// The ledger FILE carries the network as soon as it is created: a bootstrap that fails afterwards is cleaned up from the file, which is the only
    /// state that survives the failure.
    @Test
    void theCreatedNetwork_isPersistedToTheLedgerFile_immediately() {
        var name = ClusterName.clusterName("persist-2131-" + Long.toHexString(System.nanoTime())).unwrap();
        var toml = CLUSTER_TOML.replace("name = \"dock\"", "name = \"" + name.value() + "\"");
        var initial = BootstrapState.initialState(name, "h", "now");

        try {
            var _ = BootstrapStatePersistence.save(initial);
            var ctx = ClusterBootstrapOrchestrator.BootstrapContext.bootstrapContext(config(toml), initial, List.of(), List.of());

            var _ = BootstrapPhaseProvision.execute(ctx);

            assertThat(BootstrapStatePersistence.load(name).map(CreatedResourceView::networks).or(List.of()))
                .as("the file holds the network the phase created").containsExactly("aether-network");
        } finally {
            CreatedResourceView.deleteLedger(name);
        }
    }

    @Test
    void aNonDockerBootstrap_issuesNoDockerCommandAtAll() {
        var result = bootstrap(SSH_TOML, initial());

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.commands).as("the docker-source guard: no docker source, no docker network call").isEmpty();
    }

    @Test
    void destroyRemovesTheNetworkBootstrapCreated_whenOwnedAndEmpty() {
        docker.labels.put("aether-network", CLUSTER);

        var result = BootstrapCleanup.cleanup(ledgerRecording("aether-network"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.networkCommands()).contains("docker network rm aether-network");
    }

    /// The ledger names the network; destroy removes exactly that name, never the provider's current one.
    @Test
    void destroyRemovesExactlyTheRecordedName_notTheProvidersCurrentOne() {
        docker.labels.put("foo-net", CLUSTER);
        docker.labels.put("aether-network", CLUSTER);

        var result = BootstrapCleanup.cleanup(ledgerRecording("foo-net"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.networkCommands()).contains("docker network rm foo-net").doesNotContain("docker network rm aether-network");
        assertThat(docker.labels).as("the provider's current network is untouched").containsKey("aether-network");
    }

    /// A `docker network rm` that fails is an unclean cleanup, printed with docker's cause - never "success" over a network that is still there.
    @Test
    void aFailedNetworkRm_isReported_andTheCleanupIsNotClean() {
        docker.labels.put("aether-network", CLUSTER);
        docker.rmFails = true;
        var err = new java.io.ByteArrayOutputStream();
        var originalErr = System.err;

        System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            var result = BootstrapCleanup.cleanup(ledgerRecording("aether-network"));

            assertThat(result.isFailure()).as("cleanup is not clean").isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("aether-network"));
        } finally {
            System.setErr(originalErr);
        }

        assertThat(err.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("Failed to cleanup").contains("network has active endpoints");
        assertThat(docker.labels).as("the network is still there").containsKey("aether-network");
    }

    @Test
    void destroyLeavesAPreExistingNetworkIntact() {
        docker.labels.put("aether-network", "");
        var state = bootstrap(CLUSTER_TOML, initial()).unwrap();
        var before = docker.commands.size();

        var result = BootstrapCleanup.cleanup(state);

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.commands.subList(before, docker.commands.size())).as("cleanup issues no network command for a network we did not create")
                                                                           .noneMatch(command -> command.startsWith("docker network"));
    }

    @Test
    void destroyLeavesARecordedNetworkThatNoLongerCarriesOurLabel() {
        docker.labels.put("aether-network", "someone-else");

        var result = BootstrapCleanup.cleanup(ledgerRecording("aether-network"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(docker.networkCommands()).noneMatch(command -> command.startsWith("docker network rm"));
    }

    @Test
    void destroyLeavesOurNetworkWhenContainersAreStillAttached() {
        docker.labels.put("aether-network", CLUSTER);
        docker.attached.put("aether-network", 2);

        var result = BootstrapCleanup.cleanup(ledgerRecording("aether-network"));

        assertThat(result.isSuccess()).as("a network in use is left in place, not a cleanup failure").isTrue();
        assertThat(docker.networkCommands()).noneMatch(command -> command.startsWith("docker network rm"));
    }

    /// Containers go first, the network after them: removal order is the rank order, pinned on the ranks themselves and on a ledger written network-first.
    @Test
    void theNetworkIsRemovedAfterTheContainers() {
        assertThat(BootstrapCleanup.destructionRank(CreatedResource.DockerNetwork.dockerNetwork("n")))
            .isGreaterThan(BootstrapCleanup.destructionRank(CreatedResource.DockerContainer.dockerContainer("c", "s")))
            .isGreaterThan(BootstrapCleanup.destructionRank(CreatedResource.ProvisionedVm.provisionedVm("docker", "v", "s", "core")));
        docker.labels.put("aether-network", CLUSTER);
        var state = initial().withResource(CreatedResource.DockerNetwork.dockerNetwork("aether-network"))
                             .withResource(CreatedResource.DockerContainer.dockerContainer("the-container", "local"));

        BootstrapCleanup.cleanup(state);

        var removeContainer = docker.commands.indexOf(docker.commands.stream().filter(command -> command.contains("the-container")).findFirst().orElse("<none>"));
        var removeNetwork = docker.commands.indexOf("docker network rm aether-network");

        assertThat(removeContainer).as("the container was removed").isNotNegative();
        assertThat(removeNetwork).as("the network was removed, after the container").isGreaterThan(removeContainer);
    }

    /// The ledger is a file: ownership has to survive the CLI restarting between bootstrap and destroy.
    @Test
    void theOwnershipRecord_survivesTheLedgerFile() {
        var restored = BootstrapStateJson.fromJson(BootstrapStateJson.toJson(ledgerRecording("aether-network"))).unwrap();

        assertThat(networks(restored)).extracting(CreatedResource.DockerNetwork::networkName).containsExactly("aether-network");
    }

    /// Reads the persisted ledger and removes it again.
    private static final class CreatedResourceView {
        static List<String> networks(BootstrapState state) {
            return DockerNetworkBootstrapTest.networks(state).stream().map(CreatedResource.DockerNetwork::networkName).toList();
        }

        @SuppressWarnings("JBCT-EX-01")
        static void deleteLedger(ClusterName name) {
            try {
                var dir = BootstrapStatePersistence.AETHER_DIR.resolve(name.value());

                java.nio.file.Files.deleteIfExists(dir.resolve(BootstrapStatePersistence.STATE_FILE_NAME));
                java.nio.file.Files.deleteIfExists(dir);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /// Cluster scoping is the safety property of docker teardown on a shared host: every resolver call that touches a container or a network
    /// names THIS cluster. The fixture honours `cluster_name`, so a call that names another cluster (or none) is visible here, and the provider
    /// it gets refuses containers of any other cluster.
    @Test
    void everyScopedResolverCall_namesThisCluster() {
        var state = bootstrap(CLUSTER_TOML, initial()).unwrap();

        assertThat(requestedScopes).as("bootstrap asked for the network provider by this cluster").contains(CLUSTER);
        assertThat(requestedScopes).filteredOn(scope -> scope != null).as("and by no other cluster").containsOnly(CLUSTER);

        requestedScopes.clear();
        BootstrapCleanup.cleanup(state);

        assertThat(requestedScopes).as("cleanup asked for a provider for the containers and the network").hasSizeGreaterThanOrEqualTo(2);
        assertThat(requestedScopes).as("every one scoped to this cluster, none unscoped, none another").containsOnly(CLUSTER);
    }

    /// A second cluster shares the host: its network and containers survive this cluster's cleanup, even when a ledger names them.
    @Test
    void anotherClustersNetworkAndContainers_areLeftUntouched() {
        docker.labels.put("other-net", "other");
        docker.containerCluster.put("other-container", "other");
        docker.labels.put("aether-network", CLUSTER);
        var state = initial().withResource(CreatedResource.DockerNetwork.dockerNetwork("aether-network"))
                             .withResource(CreatedResource.DockerNetwork.dockerNetwork("other-net"))
                             .withResource(CreatedResource.DockerContainer.dockerContainer("our-container", "local"))
                             .withResource(CreatedResource.DockerContainer.dockerContainer("other-container", "local"));

        BootstrapCleanup.cleanup(state);

        assertThat(docker.commands).as("our container and network went").anyMatch(command -> command.contains("our-container") && !command.contains("inspect"))
                                   .contains("docker network rm aether-network");
        assertThat(docker.commands).as("the other cluster's container was inspected, then left alone: no stop, no rm")
                                   .noneMatch(command -> command.contains("other-container") && !command.contains("inspect"));
        assertThat(docker.commands).as("the other cluster's network was not removed").doesNotContain("docker network rm other-net");
        assertThat(docker.labels).containsKey("other-net");
    }
}
