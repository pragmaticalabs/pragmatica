// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
        assertThat(DockerConfig.DEFAULT_IMAGE).isEqualTo("ghcr.io/pragmaticalabs/aether-node:" + BuildInfo.version());
        assertThat(DockerConfig.DEFAULT_IMAGE).doesNotContain("1.0.0-rc");
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
