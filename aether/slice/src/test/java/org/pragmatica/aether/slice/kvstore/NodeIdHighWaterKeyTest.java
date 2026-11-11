// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdHighWaterKey;

import static org.assertj.core.api.Assertions.assertThat;

class NodeIdHighWaterKeyTest {
    @Test
    void asString_isPrefixedSourceThenRole() {
        var key = NodeIdHighWaterKey.nodeIdHighWaterKey("hetzner-eu", "core");

        assertThat(key.asString()).isEqualTo("node-id-high-water/hetzner-eu/core");
    }

    @Test
    void parse_roundTripsAsString() {
        NodeIdHighWaterKey.nodeIdHighWaterKey("node-id-high-water/hetzner-eu/worker")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(parsed -> assertThat(parsed).isEqualTo(NodeIdHighWaterKey.nodeIdHighWaterKey("hetzner-eu",
                                                                                                                 "worker")));
    }

    @Test
    void parse_missingPrefix_fails() {
        assertThat(NodeIdHighWaterKey.nodeIdHighWaterKey("other/hetzner-eu/core").isFailure()).isTrue();
    }

    @Test
    void parse_missingRole_fails() {
        assertThat(NodeIdHighWaterKey.nodeIdHighWaterKey("node-id-high-water/hetzner-eu/").isFailure()).isTrue();
        assertThat(NodeIdHighWaterKey.nodeIdHighWaterKey("node-id-high-water/hetzner-eu").isFailure()).isTrue();
    }

    @Test
    void parse_missingSource_fails() {
        assertThat(NodeIdHighWaterKey.nodeIdHighWaterKey("node-id-high-water//core").isFailure()).isTrue();
    }

    @Test
    void parse_wrongPrefixOfTheSameLength_fails() {
        assertThat(NodeIdHighWaterKey.nodeIdHighWaterKey("xxxxxxxxxxxxxxxxxxx/hetzner-eu/core").isFailure()).isTrue();
    }

    @Test
    void parse_sourceContainingSlash_splitsAtTheLastSlash() {
        NodeIdHighWaterKey.nodeIdHighWaterKey("node-id-high-water/a/b/core")
                          .onFailureRun(Assertions::fail)
                          .onSuccess(parsed -> assertThat(parsed).isEqualTo(NodeIdHighWaterKey.nodeIdHighWaterKey("a/b", "core")));
    }

    @Test
    void highWater_isClusterStateAndBackedUp() {
        var key = NodeIdHighWaterKey.nodeIdHighWaterKey("hetzner-eu", "core");

        assertThat(key).isInstanceOf(ClusterStateKey.class);
        assertThat(((ClusterStateKey) key).isBackedUp()).isTrue();
    }
}
