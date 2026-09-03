// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ClusterSizeGate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/// #782 fix round — [Main#expectedClusterSize] is the call site [ClusterSizeGate#enforce] is fed
/// from; a bug here (gating on RESOLVED peers instead of the CONFIGURED topology) is exactly what
/// let a cloud-discovery majority-at-timeout boot (`awaitDiscoveredCorePeers`, Main:611-663) refuse
/// a healthy three-node cluster because one VM was slow. These tests pin the arithmetic at THAT call
/// site, not only `ClusterSizeGate#enforce` in isolation (already covered by ClusterSizeGateTest).
class MainClusterSizeTest {

    @Nested
    class DiscoveryArm {

        @Test
        void expectedClusterSize_usesConfiguredNodes_notFound_whenMajorityAtTimeout() {
            // awaitDiscoveredCorePeers: expected=3, one VM slow, found=2 at the deadline.
            var size = Main.expectedClusterSize(false, 3, 2);

            assertEquals(3, size, "must use the discovery arm's target, not the majority-timeout shortfall");
            ClusterSizeGate.enforce(size)
                           .onFailureRun(() -> fail("a healthy 2-of-3 majority boot must not be refused"));
        }

        @Test
        void expectedClusterSize_refusesConfiguredTwo_regardlessOfFound() {
            ClusterSizeGate.enforce(Main.expectedClusterSize(false, 2, 2))
                           .onSuccessRun(() -> fail("a 2-node configured topology is below the supported minimum"));
            ClusterSizeGate.enforce(Main.expectedClusterSize(false, 2, 1))
                           .onSuccessRun(() -> fail("a 2-node configured topology is below the supported minimum, even with fewer found"));
        }
    }

    @Nested
    class StaticPeersArm {

        @Test
        void expectedClusterSize_passes_whenTwoPeersPlusSelf() {
            // CLUSTER_PEERS=a,b + self=c: assembleSelfPeers has no found/expected split (MainPeerAssemblyTest).
            var size = Main.expectedClusterSize(true, 0, 3);

            assertEquals(3, size);
            ClusterSizeGate.enforce(size).onFailureRun(() -> fail("2 static peers + self is the supported minimum"));
        }

        @Test
        void expectedClusterSize_refuses_whenOnePeerPlusSelf() {
            // CLUSTER_PEERS=a + self=b.
            var size = Main.expectedClusterSize(true, 0, 2);

            assertEquals(2, size);
            ClusterSizeGate.enforce(size).onSuccessRun(() -> fail("1 static peer + self is below the supported minimum"));
        }

        @Test
        void expectedClusterSize_ignoresConfiguredNodes_whenStaticPeersWin() {
            // Static peers take precedence in parsePeers' own resolution order (--peers=/CLUSTER_PEERS
            // before cloud discovery/config) — a stale or mismatched [cluster] nodes value in a TOML
            // alongside an explicit peer list must not override that list.
            var size = Main.expectedClusterSize(true, 5, 3);

            assertEquals(3, size, "the static arm short-circuits on resolvedPeerCount before configuredNodes is consulted");
        }
    }
}
