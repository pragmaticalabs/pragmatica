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

        assertThat(parse(toml).unwrap()).isEqualTo(new ReplicationDefaultsConfig(5, 3, 2, 1, 1, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION));
    }

    @Test
    void parse_declaredTombstoneRetention_isRead() {
        var parsed = parse("[replication]\ntombstone_retention = \"2h\"\n").unwrap();

        assertThat(parsed.tombstoneRetention()).isEqualTo(org.pragmatica.lang.io.TimeSpan.timeSpan(2).hours());
    }

    @Test
    void builtIn_tombstoneRetentionIsOneHour() {
        assertThat(ReplicationDefaultsConfig.BUILT_IN.tombstoneRetention()).isEqualTo(org.pragmatica.lang.io.TimeSpan.timeSpan(1).hours());
    }

    @Test
    void parse_tombstoneRetentionBelowTheFloor_refusesTheApply() {
        var message = parse("[replication]\ntombstone_retention = \"5m\"\n").fold(cause -> cause.message(), _ -> "");

        assertThat(message).contains("tombstone_retention");
        assertThat(parse("[replication]\ntombstone_retention = \"6m30s\"\n").isSuccess()).as("the floor itself is accepted")
                                                                                         .isTrue();
    }

    @Test
    void parse_tombstoneRetentionNotADuration_refusesTheApply() {
        assertThat(parse("[replication]\ntombstone_retention = \"soon\"\n").isFailure()).isTrue();
    }

    /// The shared duration parser reads a bare number as seconds; the floor still applies to it.
    @Test
    void parse_tombstoneRetentionBareNumber_isSeconds_andFloorChecked() {
        assertThat(parse("[replication]\ntombstone_retention = 3600\n").unwrap().tombstoneRetention())
            .isEqualTo(org.pragmatica.lang.io.TimeSpan.timeSpan(1).hours());
        assertThat(parse("[replication]\ntombstone_retention = 60\n").isFailure()).isTrue();
    }

    @Test
    void builtIn_cacheIsSingleCopy() {
        assertThat(ReplicationDefaultsConfig.BUILT_IN.cacheReplicationFactor()).isEqualTo(1);
        assertThat(ReplicationDefaultsConfig.BUILT_IN.cacheConfirmationFactor()).isEqualTo(1);
    }

    @Test
    void parse_declaredCacheFactors_areRead() {
        var toml = """
            [cache]
            replication_factor = 3
            confirmation_factor = 2
            """;

        assertThat(parse(toml).unwrap()).isEqualTo(new ReplicationDefaultsConfig(3, 2, 1, 3, 2, ReplicationDefaultsConfig.DEFAULT_TOMBSTONE_RETENTION));
    }

    @Test
    void parse_cacheConfirmationAboveFactor_refusesTheApply() {
        var message = parse("[cache]\nreplication_factor = 1\nconfirmation_factor = 2\n").fold(cause -> cause.message(), _ -> "");

        assertThat(message).contains("[cache] confirmation_factor");
    }

    @Test
    void parse_cacheFactorZero_refusesTheApply() {
        assertThat(parse("[cache]\nreplication_factor = 0\nconfirmation_factor = 0\n").isFailure()).isTrue();
    }

    @Test
    void parse_cacheUnknownKey_refusesTheApplyNamingIt() {
        var message = parse("[cache]\nttl = 5\n").fold(cause -> cause.message(), _ -> "");

        assertThat(message).contains("ttl");
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
