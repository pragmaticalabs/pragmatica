// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2089: a bootstrap of docker sources polled `https://` (the TLS default) at nodes that serve plain HTTP, and reported "Quorum not
/// established: 0/1 nodes healthy" with the cluster up.
class BootstrapSchemeTest {
    private static final String HEADER = """
            config_version = "1.0.0"

            [cluster]
            name = "sch"
            version = "1.0.0"
            """;
    private static final String DOCKER_CORES = """

            [source.d]
            type = "docker"

            [source.d.core]
            count = 3
            """;
    private static final String SSH_CORES = """

            [source.s]
            type = "ssh"

            [source.s.core]
            hosts = ["10.0.0.1", "10.0.0.2", "10.0.0.3"]
            """;
    private static final String TLS_OFF = """

            [operations.tls]
            auto_generate = false
            """;

    private static String schemeOf(String... parts) {
        return BootstrapScheme.of(ClusterBootstrapConfigParser.parse(HEADER + String.join("", parts)).unwrap());
    }

    @Test
    void of_dockerOnly_defaultTls_isPlainHttp() {
        assertThat(schemeOf(DOCKER_CORES)).as("docker nodes serve plain http").isEqualTo("http");
    }

    @Test
    void of_sshOnly_defaultTls_staysHttps() {
        assertThat(schemeOf(SSH_CORES)).isEqualTo("https");
    }

    @Test
    void of_dockerMixedWithAnotherSource_defaultTls_staysHttps() {
        assertThat(schemeOf(DOCKER_CORES, SSH_CORES)).isEqualTo("https");
    }

    @Test
    void of_tlsOff_isHttp_whateverTheSources() {
        assertThat(schemeOf(SSH_CORES, TLS_OFF)).isEqualTo("http");
    }

    private static ClusterBootstrapOrchestrator.BootstrapContext contextOf(String... parts) {
        var config = ClusterBootstrapConfigParser.parse(HEADER + String.join("", parts)).unwrap();

        return ClusterBootstrapOrchestrator.BootstrapContext.bootstrapContext(config,
                                                                              BootstrapState.initialState(ClusterName.clusterName("sch").unwrap(), "h", "now"),
                                                                              List.of(),
                                                                              List.of())
                                                             .withAddresses(List.of(NodeAddress.nodeAddress("n-0", "10.9.9.9", Option.none())));
    }

    /// The call sites, not only the helper: formation polls health and quorum with `managementScheme`, and the endpoint it stores
    /// (read later by `cluster destroy`) is built by `buildManagementEndpoint`; the post phase registers the cluster with its own.
    @Test
    void formationAndPost_dockerOnly_speakPlainHttp() {
        var ctx = contextOf(DOCKER_CORES);

        assertThat(BootstrapPhaseFormation.managementScheme(ctx)).isEqualTo("http");
        assertThat(BootstrapPhaseFormation.buildManagementEndpoint(ctx)).startsWith("http://10.9.9.9:");
        assertThat(BootstrapPhasePost.managementScheme(ctx)).isEqualTo("http");
    }

    @Test
    void formationAndPost_sshOnly_keepHttps() {
        var ctx = contextOf(SSH_CORES);

        assertThat(BootstrapPhaseFormation.managementScheme(ctx)).isEqualTo("https");
        assertThat(BootstrapPhaseFormation.buildManagementEndpoint(ctx)).startsWith("https://10.9.9.9:");
        assertThat(BootstrapPhasePost.managementScheme(ctx)).isEqualTo("https");
    }
}
