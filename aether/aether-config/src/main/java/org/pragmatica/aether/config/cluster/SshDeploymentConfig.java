// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;


public record SshDeploymentConfig(List<String> publicKeyFiles) {
    public SshDeploymentConfig {
        publicKeyFiles = List.copyOf(publicKeyFiles);
    }

    public static SshDeploymentConfig sshDeploymentConfig(List<String> publicKeyFiles) {
        return new SshDeploymentConfig(publicKeyFiles);
    }

    public static SshDeploymentConfig empty() {
        return new SshDeploymentConfig(List.of());
    }
}
