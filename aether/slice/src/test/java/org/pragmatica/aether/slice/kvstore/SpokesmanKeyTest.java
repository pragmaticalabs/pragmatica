// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class SpokesmanKeyTest {
    @Test
    void spokesmanKey_withNodeId_buildsKey() {
        var nodeId = NodeId.nodeId("node-core-1").unwrap();

        var key = SpokesmanKey.spokesmanKey(nodeId);

        assertThat(key.coreNodeId()).isEqualTo(nodeId);
        assertThat(key.asString()).isEqualTo("spokesman/node-core-1");
    }

    @Test
    void parse_validKey_succeeds() {
        SpokesmanKey.spokesmanKey("spokesman/node-core-1")
                    .onFailureRun(Assertions::fail)
                    .onSuccess(parsed -> assertThat(parsed.coreNodeId().id()).isEqualTo("node-core-1"));
    }

    @Test
    void parse_missingPrefix_fails() {
        var result = SpokesmanKey.spokesmanKey("spokeman/node-core-1");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void parse_emptyNodeId_fails() {
        var result = SpokesmanKey.spokesmanKey("spokesman/");

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void roundTrip_preservesNodeId() {
        var nodeId = NodeId.nodeId("core-abc123").unwrap();
        var key = SpokesmanKey.spokesmanKey(nodeId);

        SpokesmanKey.spokesmanKey(key.asString())
                    .onFailureRun(Assertions::fail)
                    .onSuccess(parsed -> assertThat(parsed).isEqualTo(key));
    }

    @Test
    void toString_matchesAsString() {
        var key = SpokesmanKey.spokesmanKey(NodeId.nodeId("x").unwrap());

        assertThat(key.toString()).isEqualTo(key.asString());
    }
}
