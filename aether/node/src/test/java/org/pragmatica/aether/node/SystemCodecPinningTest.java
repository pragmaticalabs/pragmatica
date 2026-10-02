// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SystemTags;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/// Proves that the hand-assigned tag table actually COVERS the system registries, and that the
/// coverage was not recovered by grepping for `@Codec`.
///
/// Grep cannot answer this question. It found 134 annotations against 76 registered types and mixed in
/// test artifacts (`com.example.MyClass`), so a list built that way is both too long and, where it
/// matters, too short. The registries themselves are the only authority on what a system type is, and
/// building one is the only way to ask them.
///
/// `NodeCodecs` is the one registry: the orphaned `WorkerCodecs` was deleted in #503 (no production
/// caller; the four sub-registries only it composed — `MutationCodecsNode`, `BootstrapCodecsNode`,
/// `HeartbeatCodecsNode`, `NetworkCodecsNode` — hold wire types no live path sends).
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

    /// Retired pins that sit under a hot prefix. Their types were deleted, so no message carries them; the
    /// pins stay only so the tag is never reused (SystemTags rules). A retired type is not hot.
    private static final Set<String> RETIRED_IN_HOT_PACKAGES = Set.of("org.pragmatica.consensus.rabia.VotingJournalCheckpoint",
                                                                      "org.pragmatica.consensus.rabia.VotingJournalRecord");

    /// The coverage assertion. [SliceCodec#systemCodec] refuses any type whose tag came back from the
    /// hash, so a framework codec added without a pin fails HERE, naming itself — which is why the
    /// system set never has to be rediscovered by hand.
    @Test
    void nodeCodecs_everySystemType_hasAHandAssignedTag() {
        assertDoesNotThrow(() -> NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
    }

    /// The wire win, stated as a property rather than as 89 numbers. Tags are VLQ-encoded, so a hot type
    /// that drifted past 127 would silently start costing a second byte on every message of the
    /// cluster's highest-frequency traffic — a regression with no other symptom.
    ///
    /// The leader pre-vote pair (#1748) is exempt like the refusals: a handful of messages per leader-loss
    /// suspicion, none in steady state, and no one-byte slot is free to give it.
    ///
    /// The `provenance` package (#1596: `ProvenanceEntry` and its `ProvenanceEpoch` kinds) is exempt like the
    /// refusals: history entries, carried only by a replica catch-up answer (a handful per catch-up, none on any other read, and an empty list encodes no element tag),
    /// so it is not the per-message traffic the window was bought for -- and no one-byte slot is free to give it.
    @Test
    void hotProtocolTypes_fitInTheOneByteWindow() {
        var hot = SystemTags.TAGS.entrySet()
                                 .stream()
                                 .filter(entry -> !entry.getKey().endsWith(".SyncRejected") && !entry.getKey().endsWith(".HelloRefused")
                                                  && !entry.getKey().endsWith(".IdentityRefused")
                                                  && !entry.getKey().endsWith(".LeaderPreVoteRequest")
                                                  && !entry.getKey().endsWith(".LeaderPreVoteResponse")
                                                  && !entry.getKey().startsWith("org.pragmatica.aether.stream.provenance."))
                                 .filter(entry -> !RETIRED_IN_HOT_PACKAGES.contains(entry.getKey()))
                                 .filter(entry -> HOT_PREFIXES.stream().anyMatch(prefix -> entry.getKey().startsWith(prefix)))
                                 .toList();

        assertTrue(hot.size() > 60,
                   "Only %d hot types matched the prefixes — the prefixes have drifted from the table".formatted(hot.size()));

        hot.forEach(entry -> assertTrue(entry.getValue() <= 127,
                                        "%s is pinned to %d and now costs two wire bytes".formatted(entry.getKey(),
                                                                                                    entry.getValue())));
    }

    /// The exemption above must not be able to hide a LIVE hot type: every entry has to be pinned, and has to
    /// name a class that no longer exists. A pin name spells nested types with dots, so each binary-name
    /// spelling (`a.b.Outer$Inner`, …) is tried before the name counts as dead.
    @Test
    void retiredHotExemptions_arePinnedAndNoLongerExist() {
        assertTrue(RETIRED_IN_HOT_PACKAGES.stream().allMatch(SystemTags.TAGS::containsKey),
                   "every retired exemption must still be pinned, or it exempts nothing: " + RETIRED_IN_HOT_PACKAGES);
        RETIRED_IN_HOT_PACKAGES.forEach(name -> assertFalse(resolves(name),
                                                            "%s resolves to a live class — it is not retired, and exempting it would hide a hot type".formatted(name)));
    }

    private static boolean resolves(String pinName) {
        var candidate = pinName;

        while (true) {
            if (loads(candidate)) {
                return true;
            }

            var lastDot = candidate.lastIndexOf('.');

            if (lastDot < 0) {
                return false;
            }

            candidate = candidate.substring(0, lastDot) + "$" + candidate.substring(lastDot + 1);
        }
    }

    private static boolean loads(String binaryName) {
        try {
            Class.forName(binaryName, false, SystemCodecPinningTest.class.getClassLoader());

            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /// The system parent must leave the whole user range free: a slice's hashed tag lands there, and an
    /// overlap would let a framework type shadow an application type on the wire.
    @Test
    void systemTags_neverReachIntoTheUserRange() {
        SystemTags.TAGS.forEach((name, tag) -> assertTrue(tag < SliceCodec.USER_TAG_BASE,
                                                          "%s is pinned to %d, inside the user range".formatted(name, tag)));
    }
}
