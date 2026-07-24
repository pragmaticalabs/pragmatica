// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.DockerConfig;
import org.pragmatica.aether.config.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/// The generated Kubernetes StatefulSet must pull the node image from the single source of truth
/// (`DockerConfig.DEFAULT_IMAGE`, on the published `ghcr.io/pragmaticalabs` namespace with a pinned
/// tag) rather than the stale hard-coded `ghcr.io/siy/aether-node:latest` it emitted before — the
/// K8s sibling of the S4 default-image fix.
class KubernetesGeneratorTest {
    @Test
    void generateStatefulSet_usesSharedDefaultImage_notStalePersonalNamespace() {
        var config = AetherConfig.aetherConfig(Environment.KUBERNETES);

        var statefulSet = new KubernetesGenerator().generateStatefulSet(config);

        assertThat(statefulSet).contains("image: " + DockerConfig.DEFAULT_IMAGE);
        assertThat(statefulSet).doesNotContain("ghcr.io/siy");
    }
}
