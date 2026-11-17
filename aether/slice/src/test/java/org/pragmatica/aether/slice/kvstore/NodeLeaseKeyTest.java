// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLeaseKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLeaseValue;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 step 1: the node lease renewal row's key and value.
class NodeLeaseKeyTest {
    @Test
    void nodeLeaseKey_withNodeId_buildsKey() {
        var nodeId = NodeId.nodeId("node-core-1").unwrap();

        var key = NodeLeaseKey.nodeLeaseKey(nodeId);

        assertThat(key.nodeId()).isEqualTo(nodeId);
        assertThat(key.asString()).isEqualTo("node-lease/node-core-1");
        assertThat(key.toString()).isEqualTo(key.asString());
    }

    @Test
    void parse_roundTrip_preservesNodeId() {
        var key = NodeLeaseKey.nodeLeaseKey(NodeId.nodeId("core-abc123").unwrap());

        NodeLeaseKey.nodeLeaseKey(key.asString())
                    .onFailureRun(Assertions::fail)
                    .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void parse_missingPrefix_fails() {
        assertThat(NodeLeaseKey.nodeLeaseKey("node-leases/node-core-1").isFailure()).isTrue();
    }

    @Test
    void parse_emptyNodeId_fails() {
        assertThat(NodeLeaseKey.nodeLeaseKey("node-lease/").isFailure()).isTrue();
    }

    @Test
    void nodeLeaseValue_carriesTheSeq() {
        assertThat(NodeLeaseValue.nodeLeaseValue(42).seq()).isEqualTo(42);
    }

    @Test
    void replicaFilter_includesTheNodeLeaseRow() {
        assertThat(ReplicaFilter.excludesAetherKey(NodeLeaseKey.nodeLeaseKey(NodeId.nodeId("x").unwrap()))).isFalse();
    }
}
