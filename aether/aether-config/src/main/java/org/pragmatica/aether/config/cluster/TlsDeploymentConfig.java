// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


/// TLS configuration for cluster deployment.
///
/// @param autoGenerate whether to auto-generate TLS certificates
/// @param clusterSecret secret reference for CA generation
/// @param certTtl certificate time-to-live duration string
public record TlsDeploymentConfig(boolean autoGenerate, Option<String> clusterSecret, String certTtl) {
    public static TlsDeploymentConfig tlsDeploymentConfig(boolean autoGenerate,
                                                          Option<String> clusterSecret,
                                                          String certTtl) {
        return new TlsDeploymentConfig(autoGenerate, clusterSecret, certTtl);
    }

    public static TlsDeploymentConfig defaultTlsConfig() {
        return new TlsDeploymentConfig(true, Option.none(), "720h");
    }
}
