// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
