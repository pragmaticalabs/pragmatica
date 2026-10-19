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
    void configLoader_nonBooleanAutoGenerate_isRefused() {
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3
            tls = true

            [tls]
            auto_generate = "yes"
            """), "tls.auto_generate", "yes");
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

    @Test
    void unparseableDurationWithAMillisecondsSibling_isRefusedNotDefaulted() {
        assertRefusedWithAllowed(WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            heartbeat_interval = "soon"
            """), "worker.heartbeat_interval", "soon", "a duration such as 30s");
        assertRefusedWithAllowed(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [dht.replication]
            cooldown_delay = "soon"
            """), "dht.replication.cooldown_delay", "soon", "a duration such as 30s");
        assertThat(WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            heartbeat_interval = "5s"
            """).isSuccess()).isTrue();
    }

    @Test
    void unparseableByteSize_isRefusedNamingKeyAndValue() {
        assertRefused(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [streaming]
            max_read_response_bytes = "huge"
            """), "streaming.max_read_response_bytes", "huge");
        assertThat(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3

            [streaming]
            max_read_response_bytes = "8MB"
            """).isSuccess()).isTrue();
    }

    @Test
    void loadWithOverrides_nonBooleanTlsAndNonNumericNodes_areRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("aether.toml");

        java.nio.file.Files.writeString(file, "[cluster]\nenvironment = \"docker\"\nnodes = 3\n");

        assertRefused(ConfigLoader.loadWithOverrides(file, java.util.Map.of("tls", "yes")), "--tls", "yes");
        assertRefused(ConfigLoader.loadWithOverrides(file, java.util.Map.of("nodes", "abc")), "--nodes", "abc");
        assertThat(ConfigLoader.loadWithOverrides(file, java.util.Map.of("tls", "false")).unwrap().cluster().tls()).isFalse();
        assertThat(ConfigLoader.loadWithOverrides(file, java.util.Map.of("tls", "TRUE")).isSuccess()).isTrue();
        assertThat(ConfigLoader.loadWithOverrides(file, java.util.Map.of("nodes", "5")).unwrap().cluster().nodes()).isEqualTo(5);
    }

    @Test
    void operationsTimeouts_unparseable_areRefusedNamingKeyAndWhatIsValid() {
        var base = """
            config_version = "1.0.0"

            [cluster]
            name = "dev-local"
            version = "1.0.0"

            [source.local]
            type = "forge"

            [source.local.core]
            count = 3

            [operations.timeouts]
            %s = "%s"
            """;

        assertRefusedWithAllowed(ClusterBootstrapConfigParser.parse(base.formatted("health_check", "soon")),
                                 "operations.timeouts.health_check", "soon", "an s, m or h suffix");
        assertRefusedWithAllowed(ClusterBootstrapConfigParser.parse(base.formatted("quorum_formation", "10d")),
                                 "operations.timeouts.quorum_formation", "10d", "an s, m or h suffix");

        for (var ok : java.util.List.of("300s", "5m", "1h")) {
            assertThat(ClusterBootstrapConfigParser.parse(base.formatted("health_check", ok)).isSuccess()).describedAs(ok).isTrue();
        }
    }

    /// The accept side: a boolean read through the TOML string accepts any case of true/false.
    @Test
    void booleans_acceptAnyCaseOfTrueAndFalse() {
        assertThat(ConfigLoader.loadFromString(http("").replace("enabled = true", "enabled = \"True\"")).isSuccess()).isTrue();
        assertThat(ConfigLoader.loadFromString("""
            [cluster]
            environment = "docker"
            nodes = 3
            tls = "FALSE"
            """).isSuccess()).isTrue();
    }
}
