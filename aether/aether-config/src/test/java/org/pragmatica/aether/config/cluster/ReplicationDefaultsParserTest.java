// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationDefaultsParserTest {
    @Test
    void fromClusterToml_blankSeed_isTheBuiltInDefault() {
        assertThat(ReplicationDefaultsParser.fromClusterToml("").unwrap()).isEqualTo(ReplicationDefaultsConfig.BUILT_IN);
    }

    @Test
    void parse_absentSection_isTheBuiltInDefault() {
        assertThat(parse("[cluster]\nname = \"x\"\n").unwrap()).isEqualTo(ReplicationDefaultsConfig.BUILT_IN);
    }

    @Test
    void parse_declaredDefaults_areRead() {
        var toml = """
            [replication]
            replication_factor = 5
            confirmation_factor = 3

            [replication.cluster_events]
            confirmation_factor = 2
            """;

        assertThat(parse(toml).unwrap()).isEqualTo(new ReplicationDefaultsConfig(5, 3, 2));
    }

    @Test
    void parse_factorBelowThree_refusesTheApply() {
        assertThat(parse("[replication]\nreplication_factor = 2\nconfirmation_factor = 1\n").isFailure()).isTrue();
    }

    @Test
    void parse_confirmationAboveFactor_refusesTheApply() {
        assertThat(parse("[replication]\nreplication_factor = 3\nconfirmation_factor = 4\n").isFailure()).isTrue();
    }

    @Test
    void parse_confirmationZero_refusesTheApply() {
        assertThat(parse("[replication]\nconfirmation_factor = 0\n").isFailure()).isTrue();
    }

    @Test
    void parse_clusterEventsConfirmationZero_refusesTheApply() {
        assertThat(parse("[replication.cluster_events]\nconfirmation_factor = 0\n").isFailure()).isTrue();
    }

    @Test
    void parse_unknownKey_refusesTheApplyNamingIt() {
        var refused = parse("[replication]\nmin_sync_replicas = 2\n");

        String message = refused.fold(cause -> cause.message(), _ -> "");

        assertThat(message).contains("min_sync_replicas");
    }

    @Test
    void parse_nonIntegerFactor_refusesTheApply() {
        assertThat(parse("[replication]\nreplication_factor = \"three\"\n").isFailure()).isTrue();
    }

    private static Result<ReplicationDefaultsConfig> parse(String toml) {
        return TomlParser.parse(toml).flatMap(ReplicationDefaultsParser::parse);
    }
}
