// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.assertj.core.api.Assertions.assertThat;


class DockerComposeTemplateTest {
    @Test
    void renderEmitsClusterLabelOnEveryNode() {
        var rendered = DockerComposeTemplate.render(clusterName("us-prod").unwrap(), 5, "aether-node:latest", 5150, 8070, 6000, "docker");
        assertThat(rendered).contains("aether.cluster: \"us-prod\"");
        // One occurrence per node.
        var clusterLabelOccurrences = rendered.split("aether.cluster:", - 1).length - 1;
        assertThat(clusterLabelOccurrences).isEqualTo(5);
    }

    @Test
    void renderEmitsNodeIdLabelForEveryNode() {
        var rendered = DockerComposeTemplate.render(clusterName("staging").unwrap(), 3, "aether-node:latest", 5150, 8070, 6000, "docker");
        assertThat(rendered).contains("aether.node-id: \"node-1\"")
                                                .contains("aether.node-id: \"node-2\"")
                                                .contains("aether.node-id: \"node-3\"");
    }

    @Test
    void renderEmitsClusterScopedNetwork() {
        var rendered = DockerComposeTemplate.render(clusterName("us-east").unwrap(), 3, "aether-node:latest", 5150, 8070, 6000, "docker");
        assertThat(rendered).contains("aether-us-east-network");
    }

    @Test
    void renderEmitsPeerListUsingClusterName() {
        var rendered = DockerComposeTemplate.render(clusterName("eu").unwrap(), 3, "aether-node:latest", 5150, 8070, 6000, "docker");
        assertThat(rendered).contains("PEERS: \"node-1:aether-eu-node-1:6000,node-2:aether-eu-node-2:6000,node-3:aether-eu-node-3:6000\"");
    }

    @Test
    void renderHonoursPortBases() {
        var rendered = DockerComposeTemplate.render(clusterName("c").unwrap(), 3, "aether-node:latest", 6000, 9000, 7000, "docker");
        assertThat(rendered).contains("\"6000:8080\"")
                                                .contains("\"6001:8080\"")
                                                .contains("\"6002:8080\"")
                                                .contains("\"9000:8070\"");
    }

    @Test
    void renderEmitsRestartNoComment() {
        var rendered = DockerComposeTemplate.render(clusterName("c").unwrap(), 3, "aether-node:latest", 5150, 8070, 6000, "docker");
        assertThat(rendered).contains("restart: \"no\"")
                                                .contains("CTM auto-heal owns failure recovery");
    }

    /// #1543 F2: a node learns its source ONLY from AETHER_SOURCE; without it the label is `default`, no config names that, and an
    /// upgrade's or an auto-heal's replacement is refused for want of a source. Every node inherits the common env.
    @Test
    void renderEmitsAetherSourceOnEveryNode_equalToTheGivenSource() {
        var rendered = DockerComposeTemplate.render(clusterName("c").unwrap(), 5, "aether-node:latest", 5150, 8070, 6000, "primary");
        var common = rendered.substring(rendered.indexOf("x-node-common"), rendered.indexOf("services:"));

        assertThat(common).contains("AETHER_SOURCE: \"primary\"");
        assertThat(rendered.split("AETHER_SOURCE:", -1).length - 1).as("one definition, inherited by every node through *node-env").isEqualTo(1);
        assertThat(rendered.lines().filter(line -> line.trim().equals("<<: *node-env")).count()).as("CONTROL: all five nodes inherit the common env").isEqualTo(5);
        assertThat(rendered).contains("`[source.primary]`");
    }
}
