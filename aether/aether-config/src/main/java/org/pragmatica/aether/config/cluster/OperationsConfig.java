// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record OperationsConfig(AutoHealSpec autoHeal,
                               TlsDeploymentConfig tls,
                               TimeoutsConfig timeouts,
                               PortMapping ports) {
    public static OperationsConfig operationsConfig(AutoHealSpec autoHeal,
                                                    TlsDeploymentConfig tls,
                                                    TimeoutsConfig timeouts,
                                                    PortMapping ports) {
        return new OperationsConfig(autoHeal, tls, timeouts, ports);
    }

    public static OperationsConfig defaultOperationsConfig() {
        return new OperationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                    TlsDeploymentConfig.defaultTlsConfig(),
                                    TimeoutsConfig.defaultTimeoutsConfig(),
                                    PortMapping.defaultPortMapping());
    }
}
