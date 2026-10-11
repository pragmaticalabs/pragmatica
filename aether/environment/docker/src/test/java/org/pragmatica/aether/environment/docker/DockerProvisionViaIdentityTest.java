// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1027: `CloudProviderSupport.provisionVia` (the docker path of `WaveExecutor`) named each node `<source>-<role>-<index>` for its caller while the
/// provider, handed no node id, minted a different one for the container. The same node then carried two identities, and the caller's label repeated
/// across calls (index restarts at 0) so two containers shared it. The identity the caller gets back must be the container's name, and unique.
class DockerProvisionViaIdentityTest {
    private static final String RUNNING_INSPECT = "running\t/x\tx\tabc";

    /// Answers every `docker run` with a container id and every `docker inspect` as running; records the `--name` of each run.
    private static final class RecordingRunner implements DockerCommandRunner {
        final List<String> containerNames = new CopyOnWriteArrayList<>();

        @Override
        public Promise<String> execute(List<String> command) {
            if (command.size() > 1 && "run".equals(command.get(1))) {
                containerNames.add(command.get(command.indexOf("--name") + 1));

                return Promise.success("container-" + containerNames.size());
            }

            return Promise.success(RUNNING_INSPECT);
        }
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
        var provider = DockerComputeProvider.dockerComputeProvider(runner, DockerConfig.dockerConfig().unwrap()).unwrap();

        var nodes = provision(provider, 3);

        assertThat(nodes.stream().map(ProvisionedNode::nodeId).toList())
            .as("one identity per node: the caller's id is the container name, not a second scheme")
            .containsExactlyInAnyOrderElementsOf(runner.containerNames);
    }

    @Test
    void twoCalls_neverShareANodeId() {
        var runner = new RecordingRunner();
        var provider = DockerComputeProvider.dockerComputeProvider(runner, DockerConfig.dockerConfig().unwrap()).unwrap();
        var ids = new ArrayList<String>();

        provision(provider, 2).forEach(node -> ids.add(node.nodeId()));
        provision(provider, 2).forEach(node -> ids.add(node.nodeId()));

        assertThat(new HashSet<>(ids)).as("four containers, four distinct ids: " + ids).hasSize(4);
        assertThat(ids).allMatch(id -> id.startsWith("aether-dock-node-"));
    }
}
