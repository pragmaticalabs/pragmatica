// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1027: `CloudProviderSupport.provisionVia` (the docker path of `WaveExecutor`) named each node `<source>-<role>-<index>` for its caller while the
/// provider, handed no node id, minted a different one for the container. The same node then carried two identities, and the caller's label repeated
/// across calls (index restarts at 0) so two containers shared it. The identity the caller gets back must be the container's name, and unique.
class DockerProvisionViaIdentityTest {
    private static final String RUNNING_INSPECT = "running\t/x\tx\tabc";

    /// Answers every `docker run` with a container id and every `docker inspect` as running; records the `--name` of each run.
    private static final class RecordingRunner implements DockerCommandRunner {
        final List<String> containerNames = new CopyOnWriteArrayList<>();
        final List<List<String>> runCommands = new CopyOnWriteArrayList<>();

        @Override
        public Promise<String> execute(List<String> command) {
            if (command.size() > 1 && "run".equals(command.get(1))) {
                runCommands.add(List.copyOf(command));
                containerNames.add(command.get(command.indexOf("--name") + 1));

                return Promise.success("container-" + containerNames.size());
            }

            return Promise.success(RUNNING_INSPECT);
        }
    }

    /// #2149: the host environment is injected EMPTY. The two-argument factory reads the real one, and a developer machine that
    /// exports `AETHER_CLUSTER_SECRET` / `AETHER_API_KEY` made provisioning refuse before any test assertion ran.
    private static DockerComputeProvider providerOn(RecordingRunner runner) {
        return providerOn(runner, _ -> null);
    }

    private static DockerComputeProvider providerOn(RecordingRunner runner, org.pragmatica.lang.Functions.Fn1<String, String> hostEnv) {
        return DockerComputeProvider.dockerComputeProvider(runner, DockerConfig.dockerConfig().unwrap(), hostEnv).unwrap();
    }

    /// One node provisioned with NO cluster tag, so the cluster can only come from the host environment fallback.
    private static void provisionWithoutClusterTag(DockerComputeProvider provider) {
        var group = NodeGroupConfig.nodeGroupConfig(sourceNameOrDefault("primary"),
                                                    "core",
                                                    1,
                                                    "default",
                                                    "default",
                                                    Map.of("aether-source", "primary", "aether-role", "core"));

        CloudProviderSupport.provisionVia(provider, group).await().unwrap();
    }

    private static List<ProvisionedNode> provision(DockerComputeProvider provider, int count) {
        var group = NodeGroupConfig.nodeGroupConfig(sourceNameOrDefault("primary"),
                                                    "core",
                                                    count,
                                                    "default",
                                                    "default",
                                                    Map.of("aether-cluster", "dock", "aether-source", "primary", "aether-role", "core"));

        return CloudProviderSupport.provisionVia(provider, group).await().unwrap();
    }

    @Test
    void theNodeIdTheCallerGetsIsTheContainersName() {
        var runner = new RecordingRunner();
        var provider = providerOn(runner);

        var nodes = provision(provider, 3);

        assertThat(nodes.stream().map(ProvisionedNode::nodeId).toList())
            .as("one identity per node: the caller's id is the container name, not a second scheme")
            .containsExactlyInAnyOrderElementsOf(runner.containerNames);
    }

    @Test
    void twoCalls_neverShareANodeId() {
        var runner = new RecordingRunner();
        var provider = providerOn(runner);
        var ids = new ArrayList<String>();

        provision(provider, 2).forEach(node -> ids.add(node.nodeId()));
        provision(provider, 2).forEach(node -> ids.add(node.nodeId()));

        assertThat(new HashSet<>(ids)).as("four containers, four distinct ids: " + ids).hasSize(4);
        assertThat(ids).allMatch(id -> id.startsWith("aether-dock-node-"));
    }

    /// Scale-down finds containers by label; the source is one of them (#1027).
    @Test
    void theContainerCarriesItsSourceAsALabel() {
        var runner = new RecordingRunner();
        var provider = providerOn(runner);

        provision(provider, 1);

        assertThat(runner.runCommands.getFirst()).contains("aether.source=primary");
    }

    /// #2149: the cluster-name fallback reads the PROVIDER'S host environment, not the process one. With an empty injected
    /// environment the label stays empty whatever the machine running the test exports (red before the fix when
    /// AETHER_CLUSTER_NAME is exported).
    @Test
    void ambientClusterNameIsIgnored_whenTheInjectedHostEnvironmentHasNone() {
        var runner = new RecordingRunner();

        provisionWithoutClusterTag(providerOn(runner));

        assertThat(runner.runCommands.getFirst()).contains("aether.cluster=");
        assertThat(runner.runCommands.getFirst()).noneMatch(arg -> arg.startsWith("aether.cluster=") && arg.length() > "aether.cluster=".length());
    }

    @Test
    void injectedHostEnvironmentSuppliesTheClusterName_whenNoTagDoes() {
        var runner = new RecordingRunner();

        provisionWithoutClusterTag(providerOn(runner, name -> "AETHER_CLUSTER_NAME".equals(name) ? "injected-cluster" : null));

        assertThat(runner.runCommands.getFirst()).contains("aether.cluster=injected-cluster");
    }

    /// The instance-tags path (what CTM bookkeeping reads back) resolves the cluster too: it must use the injected host
    /// environment, not the process one. Called directly because the InstanceInfo `provision` returns is re-stamped from
    /// `docker inspect`.
    private static String instanceClusterTag(org.pragmatica.lang.Functions.Fn1<String, String> hostEnv) {
        var ctx = ProvisionContext.provisionContext(maybeClusterName(""), "core", sourceNameOrDefault("primary"), ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
        var request = new ProvisionRequest(InstanceType.ON_DEMAND, "docker", "", "", Option.empty(), MarketOptions.ON_DEMAND, ctx);

        return DockerComputeProvider.buildInstanceTags(request, "c", hostEnv)
                                    .get("aether.cluster");
    }

    @Test
    void instanceTags_ignoreAmbientClusterName_whenTheInjectedHostEnvironmentHasNone() {
        assertThat(instanceClusterTag(_ -> null)).isEqualTo("");
    }

    @Test
    void instanceTags_useTheInjectedClusterName() {
        assertThat(instanceClusterTag(name -> "AETHER_CLUSTER_NAME".equals(name) ? "injected-cluster" : null)).isEqualTo("injected-cluster");
    }
}
