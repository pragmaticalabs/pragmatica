// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

class DhtPartitionOwnershipValueTest {
    @Test
    void dhtPartitionOwnershipValue_withAllFields_populatesRecord() {
        var owner = NodeId.nodeId("core-1").unwrap();
        var epoch = Epoch.epoch(5L, 12L);
        var hlc = new HlcTimestamp(200L, new NodeId("core-1"));

        var v = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(owner, "core", epoch, 7L, hlc);

        assertThat(v.ownerNodeId()).isEqualTo(owner);
        assertThat(v.ownerCommunityId()).isEqualTo("core");
        assertThat(v.ownerEpoch()).isEqualTo(epoch);
        assertThat(v.ownershipTerm()).isEqualTo(7L);
        assertThat(v.transferredAt()).isEqualTo(hlc);
    }

    @Test
    void construct_nullCommunityId_normalizesToEmpty() {
        var owner = NodeId.nodeId("core-1").unwrap();

        var v = new DhtPartitionOwnershipValue(owner, null, Epoch.ZERO, 0L, HlcTimestamp.ZERO);

        assertThat(v.ownerCommunityId()).isEmpty();
    }

    @Test
    void construct_nullEpoch_normalizesToZero() {
        var owner = NodeId.nodeId("core-1").unwrap();

        var v = new DhtPartitionOwnershipValue(owner, "core", null, 0L, HlcTimestamp.ZERO);

        assertThat(v.ownerEpoch()).isEqualTo(Epoch.ZERO);
    }

    @Test
    void construct_nullTransferredAt_normalizesToZero() {
        var owner = NodeId.nodeId("core-1").unwrap();

        var v = new DhtPartitionOwnershipValue(owner, "core", Epoch.ZERO, 0L, null);

        assertThat(v.transferredAt()).isEqualTo(HlcTimestamp.ZERO);
    }
}
