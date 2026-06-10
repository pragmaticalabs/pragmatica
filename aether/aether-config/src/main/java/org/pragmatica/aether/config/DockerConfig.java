// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record DockerConfig(String network, String image) {
    public static final String DEFAULT_NETWORK = "aether-network";
    public static final String DEFAULT_IMAGE = "ghcr.io/siy/aether-node:latest";

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
