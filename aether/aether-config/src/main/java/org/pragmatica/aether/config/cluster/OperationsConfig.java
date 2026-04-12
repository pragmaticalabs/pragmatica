package org.pragmatica.aether.config.cluster;

/// Operations configuration. S7
public record OperationsConfig(AutoHealSpec autoHeal, TlsDeploymentConfig tls, TimeoutsConfig timeouts, PortMapping ports) {
    public static OperationsConfig operationsConfig(AutoHealSpec autoHeal,
                                                    TlsDeploymentConfig tls,
                                                    TimeoutsConfig timeouts,
                                                    PortMapping ports) {
        return new OperationsConfig(autoHeal, tls, timeouts, ports);
    }

    public static OperationsConfig defaultOperationsConfig() {
        return new OperationsConfig(
            AutoHealSpec.defaultAutoHealSpec(),
            TlsDeploymentConfig.defaultTlsConfig(),
            TimeoutsConfig.defaultTimeoutsConfig(),
            PortMapping.defaultPortMapping()
        );
    }
}
