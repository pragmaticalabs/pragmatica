// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1455 at the node/worker/cluster config level: every bespoke parser here reads through
/// `TomlDocument`'s typed getters, whose `.or(default)` applied the default to a value the operator
/// wrote but mistyped (`port = "80x"`). Each load must refuse naming the key and the raw value.
class MalformedValueLoadRefusalTest {
    private static void assertRefused(Result<?> result, String key, String raw) {
        assertThat(result.isFailure()).describedAs("%s = \"%s\" must refuse the load, loaded %s", key, raw, result)
                                      .isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains(key)
                                                             .contains(raw));
    }

    @Test
    void configLoader_malformedAppHttpPort_refusesNamingKeyAndValue() {
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [app-http]
            enabled = "true"
            port = "80x"
            """), "app-http.port", "80x");
    }

    @Test
    void configLoader_wellFormedAppHttpPort_loads() {
        var config = ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [app-http]
            enabled = "true"
            port = 8081
            """);

        assertThat(config.isSuccess()).describedAs("control: %s", config).isTrue();
    }

    @Test
    void workerConfigLoader_malformedClusterPort_refusesNamingKeyAndValue() {
        assertRefused(WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            cluster_port = "6000x"
            """), "worker.cluster_port", "6000x");
    }

    @Test
    void workerConfigLoader_wellFormedClusterPort_loads() {
        assertThat(WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            cluster_port = 6001
            """).isSuccess()).isTrue();
    }

    @Test
    void clusterBootstrapConfigParser_malformedCoreMin_refusesNamingKeyAndValue() {
        assertRefused(ClusterBootstrapConfigParser.parse("""
            config_version = "1.0.0"

            [cluster]
            name = "dev-local"
            version = "1.0.0"

            [cluster.core]
            min = "3x"

            [source.local]
            type = "forge"

            [source.local.core]
            count = 3
            """), "cluster.core.min", "3x");
    }

    @Test
    void clusterBootstrapConfigParser_wellFormedCoreMin_parses() {
        assertThat(ClusterBootstrapConfigParser.parse("""
            config_version = "1.0.0"

            [cluster]
            name = "dev-local"
            version = "1.0.0"

            [cluster.core]
            min = 3

            [source.local]
            type = "forge"

            [source.local.core]
            count = 3
            """).isSuccess()).isTrue();
    }

    private static String http(String extra) {
        return """
            [cluster]
            environment = "docker"
            nodes = 3

            [app-http]
            enabled = true
            """ + extra;
    }

    private static void assertRefusedWithAllowed(Result<?> result, String key, String raw, String allowed) {
        assertRefused(result, key, raw);
        result.onFailure(cause -> assertThat(cause.message()).contains(allowed));
    }

    /// Invalid VALUES are refused like invalid types (#1455): an enumeration token, a boolean, a
    /// duration or a size that does not parse used to take the reader's default.
    @Test
    void configLoader_unknownSecurityMode_refusesNamingKeyValueAndAllowed() {
        assertRefusedWithAllowed(ConfigLoader.loadFromString(http("security_mode = \"apikeys\"\n")),
                                 "app-http.security_mode",
                                 "apikeys",
                                 "one of: none, api-key, jwt");
        assertThat(ConfigLoader.loadFromString(http("security_mode = \"api-key\"\n")).isSuccess()).isTrue();
    }

    @Test
    void configLoader_unknownHttpProtocol_refusesNamingKeyValueAndAllowed() {
        assertRefusedWithAllowed(ConfigLoader.loadFromString(http("protocol = \"h2\"\n")),
                                 "app-http.protocol",
                                 "h2",
                                 "one of: h1, h3, both");
        assertThat(ConfigLoader.loadFromString(http("protocol = \"h1\"\n")).isSuccess()).isTrue();
    }

    @Test
    void configLoader_unknownManagementProtocol_refusesNamingKeyValueAndAllowed() {
        assertRefusedWithAllowed(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [cluster.ports]
            management_protocol = "quic"
            """), "cluster.ports.management_protocol", "quic", "one of: h1, h3, both");
    }

    @Test
    void configLoader_unknownApiVersioningDetection_refusesNamingKeyValueAndAllowed() {
        assertRefusedWithAllowed(ConfigLoader.loadFromString(http("api_versioning_detection = \"cookie\"\n")),
                                 "app-http.api_versioning_detection",
                                 "cookie",
                                 "one of: path, header");
        assertThat(ConfigLoader.loadFromString(http("api_versioning_detection = \"header\"\n")).isSuccess()).isTrue();
    }

    @Test
    void configLoader_nonBooleanTlsAndEnabled_areRefused() {
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3
            tls = "yes"
            """), "cluster.tls", "yes");
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [app-http]
            enabled = "yes"
            """), "app-http.enabled", "yes");
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [ttm]
            enabled = "yes"
            """), "ttm.enabled", "yes");
    }

    @Test
    void configLoader_unparseableSizeAndDuration_areRefusedNamingWhatIsValid() {
        assertRefusedWithAllowed(ConfigLoader.loadFromString(http("max_request_size = \"huge\"\n")),
                                 "app-http.max_request_size",
                                 "huge",
                                 "a size such as 64MB");
        assertRefusedWithAllowed(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [timeouts.invocation]
            timeout = "soon"
            """), "timeouts.invocation.timeout", "soon", "a duration such as 30s");
        assertThat(ConfigLoader.loadFromString(http("max_request_size = \"10MB\"\n")).isSuccess()).isTrue();
    }

    @Test
    void clusterBootstrapConfigParser_unknownLoadBalancerMode_refusesNamingKeyValueAndAllowed() {
        var base = """
            config_version = "1.0.0"

            [cluster]
            name = "dev-local"
            version = "1.0.0"

            [source.local]
            type = "forge"
            load_balancer = "%s"

            [source.local.core]
            count = 3
            """;

        assertRefusedWithAllowed(ClusterBootstrapConfigParser.parse(base.formatted("roundrobin")),
                                 "source.local.load_balancer",
                                 "roundrobin",
                                 "one of: none, external, elected");
        assertThat(ClusterBootstrapConfigParser.parse(base.formatted("elected")).isSuccess()).isTrue();
    }

    @Test
    void configLoader_nonBooleanBackupEnabled_isRefusedNamingKeyAndValue() {
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [backup]
            enabled = "yes"
            path = "/var/backups"
            """), "[backup] enabled", "yes");
    }
}
