// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// Exercises [Main#assembleSelfPeers] — the pure peer-assembly seam that decides whether a node
/// must synthesize its own `NodeInfo` (and with which resolved host) when it is absent from its own
/// seed set. The host resolver is injected so these tests are deterministic without env or sockets.
class MainPeerAssemblyTest {

    private static final NodeId SELF = NodeId.nodeId("node-self").expect("valid id");
    private static final int SELF_PORT = 8090;
    private static final Map<String, String> LABELS = Map.of(NodeInfo.LABEL_HOSTNAME, "self-host");

    private static NodeInfo node(String id, String host, int port) {
        return NodeInfo.nodeInfo(NodeId.nodeId(id).expect("valid id"), nodeAddress(host, port).expect("valid address"));
    }

    @Nested
    class SelfPresent {

        @Test
        void assembleSelfPeers_returnsListUnchanged_whenSelfPresent() {
            var resolverInvoked = new AtomicBoolean(false);
            var peers = List.of(node("node-a", "10.0.0.1", 8090),
                                node("node-self", "10.0.0.9", 8090),
                                node("node-b", "10.0.0.2", 8090));

            var result = Main.assembleSelfPeers(peers, SELF, SELF_PORT, LABELS, seeds -> {
                resolverInvoked.set(true);
                return "should.not.be.used";
            });

            assertSame(peers, result, "bootstrap (self present) must return the parsed list unchanged");
            assertFalse(resolverInvoked.get(), "no host resolution runs when self is already present");
        }
    }

    @Nested
    class SelfAbsent {

        @Test
        void assembleSelfPeers_appendsResolvedSelf_whenSelfAbsent() {
            var peers = List.of(node("node-a", "10.0.0.1", 8090), node("node-b", "10.0.0.2", 8090));

            var result = Main.assembleSelfPeers(peers, SELF, SELF_PORT, LABELS, seeds -> "203.0.113.7");

            assertEquals(3, result.size(), "self must be appended");
            var self = result.stream().filter(n -> n.id().equals(SELF)).findFirst().orElseThrow();
            assertEquals("203.0.113.7", self.address().host(), "self advertises the resolved (override) host");
            assertEquals(SELF_PORT, self.address().port());
            assertEquals("self-host", self.labels().get(NodeInfo.LABEL_HOSTNAME), "labels are carried onto self");
        }

        @Test
        void assembleSelfPeers_usesReflectedHost_whenResolverReturnsObservedIp() {
            var peers = List.of(node("node-a", "10.0.0.1", 8090));

            // The injected resolver stands in for SWIM WhoAmI reflection returning the observed IP.
            var result = Main.assembleSelfPeers(peers, SELF, SELF_PORT, LABELS, seeds -> "198.51.100.42");

            var self = result.stream().filter(n -> n.id().equals(SELF)).findFirst().orElseThrow();
            assertEquals("198.51.100.42", self.address().host());
        }

        @Test
        void assembleSelfPeers_passesSeedsToResolver_whenSelfAbsent() {
            var seedsSeen = new AtomicBoolean(false);
            var peers = List.of(node("node-a", "10.0.0.1", 8090), node("node-b", "10.0.0.2", 8090));

            Main.assembleSelfPeers(peers, SELF, SELF_PORT, LABELS, seeds -> {
                seedsSeen.set(seeds.size() == 2 && seeds.stream().noneMatch(n -> n.id().equals(SELF)));
                return "203.0.113.7";
            });

            assertTrue(seedsSeen.get(), "the resolver receives the parsed seeds (self absent)");
        }

        @Test
        void assembleSelfPeers_preservesExistingPeers_whenAppendingSelf() {
            var peers = List.of(node("node-a", "10.0.0.1", 8090), node("node-b", "10.0.0.2", 8090));

            var result = Main.assembleSelfPeers(peers, SELF, SELF_PORT, LABELS, seeds -> "203.0.113.7");

            assertTrue(result.containsAll(peers), "appending self must not drop any parsed peer");
        }
    }
}
