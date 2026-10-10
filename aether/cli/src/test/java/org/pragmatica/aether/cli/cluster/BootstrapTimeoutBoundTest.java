// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// `aether cluster bootstrap --wait --timeout N`: an operator reads it as "give up after N seconds", but N only bounded the final status poll while
/// node health and quorum formation ran on the config's own 300 s and 600 s. An explicit `--timeout` now caps those waits too (never raising them);
/// without it the config's timeouts stand.
class BootstrapTimeoutBoundTest {
    private static final String CONFIG = """
            config_version = "1.0.0"

            [cluster]
            name = "dock"
            version = "1.0.0"

            [operations.tls]
            auto_generate = false

            [runtime.default]
            type = "container"
            image = "aether-node:1.0.0"

            [source.d]
            type = "docker"

            [source.d.core]
            count = 3
            """;

    private static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig config() {
        return ClusterBootstrapConfigParser.parse(CONFIG).unwrap();
    }

    @Test
    void boundedBy_capsBothFormationWaits_atTheGivenSeconds() {
        var bounded = ClusterBootstrapCommand.boundedBy(config(), 60);

        assertThat(bounded.operations().timeouts().healthCheck()).isEqualTo("60s");
        assertThat(bounded.operations().timeouts().quorumFormation()).isEqualTo("60s");
    }

    @Test
    void boundedBy_neverRaisesAWaitAboveTheConfig() {
        var bounded = ClusterBootstrapCommand.boundedBy(config(), 5000);

        assertThat(bounded.operations().timeouts().healthCheck()).isEqualTo("300s");
        assertThat(bounded.operations().timeouts().quorumFormation()).isEqualTo("600s");
    }

    @Test
    void timeoutGiven_isTrueOnlyForAnExplicitTimeoutOnAWaitBootstrap() {
        assertThat(parsed("--wait", "--timeout", "60").timeoutGiven()).isTrue();
        assertThat(parsed("--wait").timeoutGiven()).as("the 300 default is not a request").isFalse();
        assertThat(parsed("--timeout", "60").timeoutGiven()).as("without --wait there is no wait to bound").isFalse();
    }

    private static ClusterBootstrapCommand parsed(String... args) {
        var command = new ClusterBootstrapCommand();
        var all = new String[args.length + 2];

        all[0] = "cluster.toml";
        all[1] = "--yes";
        System.arraycopy(args, 0, all, 2, args.length);
        new CommandLine(command).parseArgs(all);

        return command;
    }
}
