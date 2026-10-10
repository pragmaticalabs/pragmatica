// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2089: a docker node boots from the image's plain-HTTP configuration and has no channel to receive TLS material, so a docker source
/// whose config declares management TLS (`[operations.tls] auto_generate = true`, the default) is refused up front, with a typed error
/// naming the fix. HTTP is used only when the config EXPLICITLY disables TLS; it is never inferred from the source type.
class DockerTlsRefusalTest {
    private static final String HEADER = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"
            """;
    private static final String DOCKER = """

            [source.d]
            type = "docker"

            [source.d.core]
            count = 3
            """;
    private static final String SSH = """

            [source.s]
            type = "ssh"

            [source.s.core]
            hosts = ["10.0.0.1", "10.0.0.2", "10.0.0.3"]
            """;
    private static final String TLS_FALSE = """

            [operations.tls]
            auto_generate = false
            """;
    private static final String TLS_TRUE = """

            [operations.tls]
            auto_generate = true
            """;

    private final List<CloudConfig> providerResolutions = new ArrayList<>();

    @BeforeEach
    void install() {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                providerResolutions.add(config);
                return Causes.cause("recorded, not provisioning").result();
            }
        };
    }

    @AfterEach
    void remove() {
        ProviderResolver.dockerFactoryOverride = null;
    }

    private static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig parse(String... parts) {
        return ClusterBootstrapConfigParser.parse(HEADER + String.join("", parts)).unwrap();
    }

    @Test
    void validate_dockerWithDefaultTls_isRefused_namingTheFix() {
        var result = BootstrapPhaseValidate.refuseDockerWithDeclaredTls(parse(DOCKER));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(BootstrapError.DockerSourceDeclaresTls.class);
            assertThat(cause.message()).contains("'d'").contains("auto_generate = false");
        });
    }

    @Test
    void validate_dockerWithExplicitTlsTrue_isRefused() {
        assertThat(BootstrapPhaseValidate.refuseDockerWithDeclaredTls(parse(DOCKER, TLS_TRUE)).isFailure()).isTrue();
    }

    @Test
    void validate_dockerWithExplicitTlsFalse_proceeds_overHttp() {
        var config = parse(DOCKER, TLS_FALSE);

        assertThat(BootstrapPhaseValidate.refuseDockerWithDeclaredTls(config).isSuccess()).isTrue();
        assertThat(config.operations().tls().autoGenerate()).as("explicit false is what the CLI's http choice rests on").isFalse();
    }

    @Test
    void validate_nonDockerConfigs_areUnchanged() {
        assertThat(BootstrapPhaseValidate.refuseDockerWithDeclaredTls(parse(SSH)).isSuccess()).as("ssh, default TLS").isTrue();
        assertThat(BootstrapPhaseValidate.refuseDockerWithDeclaredTls(parse(SSH, TLS_FALSE)).isSuccess()).as("ssh, TLS off").isTrue();
    }

    @Test
    void validate_dockerMixedWithAnotherSource_declaringTls_isRefused() {
        assertThat(BootstrapPhaseValidate.refuseDockerWithDeclaredTls(parse(DOCKER, SSH)).isFailure()).isTrue();
    }

    /// No container is created: the refusal happens in the VALIDATE phase, so the Docker provider is never even resolved.
    @Test
    void validatePhase_dockerWithDefaultTls_failsBeforeAnyProviderIsResolved() {
        var result = BootstrapPhaseValidate.execute(parse(DOCKER));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(BootstrapError.DockerSourceDeclaresTls.class));
        assertThat(providerResolutions).as("the docker provider was never created").isEmpty();
    }
}
