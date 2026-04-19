// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;

import static org.assertj.core.api.Assertions.assertThat;

class DhtPartitionOwnershipKeyTest {
    @Test
    void dhtPartitionOwnershipKey_withPartitionId_buildsKey() {
        var key = DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("core");

        assertThat(key.partitionId()).isEqualTo("core");
        assertThat(key.asString()).isEqualTo("dht-partition-ownership/core");
    }

    @Test
    void asString_prefixesWithSection() {
        var key = DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("worker-pool-a");

        assertThat(key.asString()).isEqualTo("dht-partition-ownership/worker-pool-a");
    }

    @Test
    void parse_validKey_succeeds() {
        DhtPartitionOwnershipKey.parseDhtPartitionOwnershipKey("dht-partition-ownership/core")
                                .onFailureRun(Assertions::fail)
                                .onSuccess(parsed -> assertThat(parsed.partitionId()).isEqualTo("core"));
    }

    @Test
    void parse_missingPrefix_fails() {
        DhtPartitionOwnershipKey.parseDhtPartitionOwnershipKey("invalid/foo")
                                .onSuccess(_ -> Assertions.fail("Expected failure"));
    }

    @Test
    void parse_emptyPartition_fails() {
        DhtPartitionOwnershipKey.parseDhtPartitionOwnershipKey("dht-partition-ownership/")
                                .onSuccess(_ -> Assertions.fail("Expected failure for empty partition"));
    }

    @Test
    void roundTrip_preservesPartitionId() {
        var key = DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("shard-42");

        DhtPartitionOwnershipKey.parseDhtPartitionOwnershipKey(key.asString())
                                .onFailureRun(Assertions::fail)
                                .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void toString_matchesAsString() {
        var key = DhtPartitionOwnershipKey.dhtPartitionOwnershipKey("core");

        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
