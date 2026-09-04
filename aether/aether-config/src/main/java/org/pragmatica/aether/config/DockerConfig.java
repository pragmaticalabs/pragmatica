// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record DockerConfig(String network, String image) {
    public static final String DEFAULT_NETWORK = "aether-network";
    /// Default node image for management-generated Docker artifacts. Uses the published
    /// `ghcr.io/pragmaticalabs` namespace (matching `DockerComposeTemplate` /
    /// `ClusterConfigGenerator.IMAGE_PREFIX`) and pins the project version rather than a
    /// floating `:latest`. The rest of the codebase versions images at runtime via
    /// `config.cluster().version()`; this low-level module has no compile-time version source,
    /// so the tag is pinned literally and must be bumped with the project version.
    public static final String DEFAULT_IMAGE = "ghcr.io/pragmaticalabs/aether-node:1.0.0-rc3";

    public static Result<DockerConfig> dockerConfig(String network, String image) {
        return success(new DockerConfig(network, image));
    }

    public static DockerConfig dockerConfig() {
        return dockerConfig(DEFAULT_NETWORK, DEFAULT_IMAGE).unwrap();
    }

    public DockerConfig withNetwork(String network) {
        return dockerConfig(network, image).unwrap();
    }

    public DockerConfig withImage(String image) {
        return dockerConfig(network, image).unwrap();
    }
}
