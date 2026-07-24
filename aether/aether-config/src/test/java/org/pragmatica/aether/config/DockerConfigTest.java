// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DockerConfigTest {
    @Test
    void defaultImage_usesPublishedNamespace_notPersonalNamespace() {
        assertThat(DockerConfig.DEFAULT_IMAGE).startsWith("ghcr.io/pragmaticalabs/aether-node:");
        assertThat(DockerConfig.DEFAULT_IMAGE).doesNotContain("ghcr.io/siy");
    }

    @Test
    void defaultImage_pinsProjectVersion_notFloatingLatest() {
        assertThat(DockerConfig.DEFAULT_IMAGE).isEqualTo("ghcr.io/pragmaticalabs/aether-node:1.0.0-rc3");
        assertThat(DockerConfig.DEFAULT_IMAGE).doesNotEndWith(":latest");
    }

    @Test
    void dockerConfig_default_appliesDefaultNetworkAndImage() {
        var config = DockerConfig.dockerConfig();

        assertThat(config.network()).isEqualTo(DockerConfig.DEFAULT_NETWORK);
        assertThat(config.image()).isEqualTo(DockerConfig.DEFAULT_IMAGE);
    }

    @Test
    void withImage_overridesImage_preservesNetwork() {
        var config = DockerConfig.dockerConfig().withImage("ghcr.io/pragmaticalabs/aether-node:custom");

        assertThat(config.image()).isEqualTo("ghcr.io/pragmaticalabs/aether-node:custom");
        assertThat(config.network()).isEqualTo(DockerConfig.DEFAULT_NETWORK);
    }
}
