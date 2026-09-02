// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.worker.WorkerCodecs;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SystemTags;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Proves that the hand-assigned tag table actually COVERS the system registries, and that the
/// coverage was not recovered by grepping for `@Codec`.
///
/// Grep cannot answer this question. It found 134 annotations against 76 registered types and mixed in
/// test artifacts (`com.example.MyClass`), so a list built that way is both too long and, where it
/// matters, too short. The registries themselves are the only authority on what a system type is, and
/// building one is the only way to ask them.
///
/// Both registries matter and only one of them is otherwise exercised: `WorkerCodecs` has no
/// production caller today, and it registers four sub-registries `NodeCodecs` does not
/// (`MutationCodecsNode`, `BootstrapCodecsNode`, `HeartbeatCodecsNode`, `NetworkCodecsNode`). Without
/// the assertion below, those types would go unpinned and nothing would say so until the registry was
/// wired up.
class SystemCodecPinningTest {

    /// Package prefixes whose traffic the one-byte window was bought for — consensus rounds, membership
    /// gossip, DHT lookups, KV commands, stream replication, and the value objects nested inside them.
    private static final List<String> HOT_PREFIXES = List.of(
        "org.pragmatica.consensus.",
        "org.pragmatica.net.tcp.",
        "org.pragmatica.swim.",
        "org.pragmatica.dht.",
        "org.pragmatica.cluster.state.kvstore.",
        "org.pragmatica.cluster.metrics.",
        "org.pragmatica.aether.stream.",
        "org.pragmatica.lang."
    );

    /// The coverage assertion. [SliceCodec#systemCodec] refuses any type whose tag came back from the
    /// hash, so a framework codec added without a pin fails HERE, naming itself — which is why the
    /// system set never has to be rediscovered by hand.
    @Test
    void nodeCodecs_everySystemType_hasAHandAssignedTag() {
        assertDoesNotThrow(() -> NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
    }

    @Test
    void workerCodecs_everySystemType_hasAHandAssignedTag() {
        assertDoesNotThrow(() -> WorkerCodecs.workerCodecs(FrameworkCodecs.frameworkCodecs()));
    }

    /// The wire win, stated as a property rather than as 89 numbers. Tags are VLQ-encoded, so a hot type
    /// that drifted past 127 would silently start costing a second byte on every message of the
    /// cluster's highest-frequency traffic — a regression with no other symptom.
    @Test
    void hotProtocolTypes_fitInTheOneByteWindow() {
        var hot = SystemTags.TAGS.entrySet()
                                 .stream()
                                 .filter(entry -> HOT_PREFIXES.stream().anyMatch(prefix -> entry.getKey().startsWith(prefix)))
                                 .toList();

        assertTrue(hot.size() > 60,
                   "Only %d hot types matched the prefixes — the prefixes have drifted from the table".formatted(hot.size()));

        hot.forEach(entry -> assertTrue(entry.getValue() <= 127,
                                        "%s is pinned to %d and now costs two wire bytes".formatted(entry.getKey(),
                                                                                                    entry.getValue())));
    }

    /// The system parent must leave the whole user range free: a slice's hashed tag lands there, and an
    /// overlap would let a framework type shadow an application type on the wire.
    @Test
    void systemTags_neverReachIntoTheUserRange() {
        SystemTags.TAGS.forEach((name, tag) -> assertTrue(tag < SliceCodec.USER_TAG_BASE,
                                                          "%s is pinned to %d, inside the user range".formatted(name, tag)));
    }
}
