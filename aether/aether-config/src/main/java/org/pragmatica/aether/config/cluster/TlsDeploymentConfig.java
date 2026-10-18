// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record TlsDeploymentConfig(boolean autoGenerate) {
    public static TlsDeploymentConfig tlsDeploymentConfig(boolean autoGenerate) {
        return new TlsDeploymentConfig(autoGenerate);
    }

    public static TlsDeploymentConfig defaultTlsConfig() {
        return new TlsDeploymentConfig(true);
    }
}
