// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: `[source.<name>.node_config.backup] path` is refused at load when the node could not write it: a relative path renders a
/// mount Docker rejects, and a Docker source's repository lives on a named volume that Docker creates root-owned outside `/data`.
class BackupPathValidationTest {
    private static final String CLOUD = """
            config_version = "1.0.0"

            [cluster]
            name = "c"
            version = "1.0.0"

            [source.s]
            type = "cloud"
            provider = "hetzner"
            region = "eu-central"

            [source.s.core]
            count = 3
            %s
            """;

    private static final String DOCKER = """
            config_version = "1.0.0"

            [cluster]
            name = "c"
            version = "1.0.0"

            [source.s]
            type = "docker"
            %s
            """;

    private static String backup(String enabled, String path) {
        return "[source.s.node_config.backup]\nenabled = " + enabled + "\npath = \"" + path + "\"\n";
    }

    @Test
    void aRelativePath_isRefused_inEverySourceType() {
        for (var template : new String[] {CLOUD, DOCKER}) {
            var result = ClusterBootstrapConfigParser.parse(template.formatted(backup("true", "./aether-backups")));

            assertThat(result.isFailure()).as("relative path accepted for " + template.split("type = \"")[1].split("\"")[0]).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("source.s.node_config.backup.path", "absolute"));
        }
    }

    @Test
    void aDockerSource_refusesAnAbsolutePathOutsideData_andNamesTheRule() {
        var result = ClusterBootstrapConfigParser.parse(DOCKER.formatted(backup("true", "/var/aether/backups")));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("under /data", "named volume"));
    }

    @Test
    void aDockerSource_acceptsAPathUnderData() {
        assertThat(ClusterBootstrapConfigParser.parse(DOCKER.formatted(backup("true", "/data/backups"))).isSuccess()).isTrue();
        assertThat(ClusterBootstrapConfigParser.parse(DOCKER.formatted(backup("true", "/data"))).isSuccess()).isTrue();
        assertThat(ClusterBootstrapConfigParser.parse(DOCKER.formatted(backup("true", "/database/backups"))).isFailure())
            .as("a sibling that merely starts with the characters /data is not under /data").isTrue();
    }

    @Test
    void aCloudSource_acceptsAnyAbsolutePath_becauseItsMountIsAHostDirectoryTheRendererChowns() {
        assertThat(ClusterBootstrapConfigParser.parse(CLOUD.formatted(backup("true", "/var/aether/backups"))).isSuccess()).isTrue();
    }

    @Test
    void aDisabledBackup_isNotValidated() {
        assertThat(ClusterBootstrapConfigParser.parse(DOCKER.formatted(backup("false", "relative/path"))).isSuccess()).isTrue();
    }

    @Test
    void aDockerSource_refusesDotDotEscapesAndSiblingsOfData() {
        for (var bad : new String[] {"/data/../etc", "/database/x"}) {
            assertThat(ClusterBootstrapConfigParser.parse(DOCKER.formatted(backup("true", bad))).isFailure()).as("docker source, path " + bad).isTrue();
        }
    }
}
