// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.labels;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class ContainerLabelInspectorTest {
    @Test
    void parseLabels_extractsAetherClusterAndNodeId() {
        var body = """
                   {"Id":"abc123","Config":{"Image":"aether:latest","Labels":{"aether.cluster":"prod-us","aether.node-id":"node-1","aether.role":"core"}}}""";
        var labels = ContainerLabelInspector.parseLabels(body);
        assertThat(labels).containsEntry("aether.cluster", "prod-us")
                          .containsEntry("aether.node-id", "node-1")
                          .containsEntry("aether.role", "core");
    }

    @Test
    void parseLabels_emptyLabelsBlock_returnsEmptyMap() {
        var body = """
                   {"Id":"abc","Config":{"Labels":{}}}""";
        assertThat(ContainerLabelInspector.parseLabels(body)).isEmpty();
    }

    @Test
    void parseLabels_noLabelsField_returnsEmptyMap() {
        var body = """
                   {"Id":"abc","Config":{}}""";
        assertThat(ContainerLabelInspector.parseLabels(body)).isEmpty();
    }

    @Test
    void compareWithConfigured_matchingNames_succeeds() {
        var labels = Map.of("aether.cluster", "us-prod", "aether.node-id", "node-1");
        var result = ContainerLabelInspector.compareWithConfigured("us-prod", labels);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void compareWithConfigured_mismatchedNames_fails() {
        var labels = Map.of("aether.cluster", "us-prod", "aether.node-id", "node-1");
        var result = ContainerLabelInspector.compareWithConfigured("eu-prod", labels);
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ContainerLabelInspector.ContainerLabelMismatch.class));
    }

    @Test
    void compareWithConfigured_emptyConfigured_skips() {
        var labels = Map.of("aether.cluster", "us-prod");
        var result = ContainerLabelInspector.compareWithConfigured("", labels);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void compareWithConfigured_emptyLabelValue_skips() {
        var result = ContainerLabelInspector.compareWithConfigured("us-prod", Map.of());
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void mismatchError_messageNamesBothSides() {
        var error = new ContainerLabelInspector.ContainerLabelMismatch("eu-prod", "us-prod");
        assertThat(error.message()).contains("'eu-prod'")
                                                .contains("'us-prod'")
                                                .contains("aborting startup");
    }

    @Test
    void inspectSelfLabels_notInContainer_returnsEmpty() {
        // Test environment is not a Docker container, so /.dockerenv doesn't exist.
        // Detection should short-circuit and return empty (DEBUG-logged skip).
        var result = ContainerLabelInspector.inspectSelfLabels();
        assertThat(result.isEmpty()).isTrue();
    }
}
