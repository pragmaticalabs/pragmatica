// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.DeclarationDefaults;
import org.pragmatica.aether.stream.StreamPartitionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// T-16, the config arm (#3334): an `entity:` log gets NO user retention bound. The user-stream declaration default
/// (`max_age` 24 h, `max_bytes = max(1 GiB, ...)`) applies to streams created without TOML; an entity log is reclaimed
/// only up to its checkpoint pointer, so its config declares no dimension and carries no `max_bytes` of the default.
///
/// [unverified: the 2 GiB written arm (records above the checkpoint pointer stay readable): it needs a 2 GiB write and
/// the checkpoint harness, and is covered here only by the config it would rest on.] The route arm is the
/// reserved-prefix refusal: an `entity:` stream cannot be API-created, so no user default can reach it that way (pinned
/// by `StreamRoutesReservedPrefixTest`).
class StreamEntityLogRetentionDefaultTest {
    private static final String KEYSPACE = "orders";

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(64L * 1024 * 1024);
        streamEntityLogSubstrate(manager, null, null, null, null, null, null, null).ensureLog(KEYSPACE,
                                                                                               1,
                                                                                               new ReplicationFactors(1, 1))
                                                                                   .unwrap();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void entityLog_declaresNoUserRetentionBound() {
        var retention = retention();

        assertThat(retention.declared()).isEqualTo(RetentionDimensions.NONE);
    }

    @Test
    void entityLog_doesNotCarryTheUserStreamDefaults() {
        var retention = retention();

        assertThat(retention.maxBytes()).isNotEqualTo(DeclarationDefaults.defaultMaxBytes(1));
        assertThat(retention.maxAgeMs()).isNotEqualTo(DeclarationDefaults.DEFAULT_MAX_AGE_MS);
    }

    private RetentionPolicy retention() {
        return manager.enforcedConfig(EntityPartitionArc.arcName(KEYSPACE))
                      .map(config -> config.retention())
                      .or(() -> fail("the entity log is not held by the manager: no config to read"));
    }
}
