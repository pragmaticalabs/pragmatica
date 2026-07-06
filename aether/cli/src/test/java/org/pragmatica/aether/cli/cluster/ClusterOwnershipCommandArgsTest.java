// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Picocli argument-parsing harness for `aether cluster ownership <domain>` (#345 item 1f).
/// Validates command shape — the required `<domain>` positional and subcommand registration under
/// `cluster` — without invoking HTTP; `call()` is never reached because `parseArgs` short-circuits
/// before `runLast`.
class ClusterOwnershipCommandArgsTest {
    private static CommandLine commandLine() {
        return new CommandLine(new ClusterCommand());
    }

    @Test
    void ownership_domainArg_parsesSubcommandAndBindsCommand() {
        var parsed = commandLine().parseArgs("ownership", "dht");

        assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("ownership");
        assertThat(parsed.subcommand().commandSpec().userObject()).isInstanceOf(ClusterOwnershipCommand.class);
    }

    @Test
    void ownership_missingDomain_isMissingParameterError() {
        assertThatThrownBy(() -> commandLine().parseArgs("ownership")).isInstanceOf(CommandLine.MissingParameterException.class);
    }

    @Test
    void ownership_clusterTargetOverride_parses() {
        var parsed = commandLine().parseArgs("ownership", "stream", "--cluster", "prod");

        assertThat(parsed.subcommand().commandSpec().name()).isEqualTo("ownership");
    }
}
