// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1098 at the node/worker/cluster config level: every bespoke parser here reads through
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
}
