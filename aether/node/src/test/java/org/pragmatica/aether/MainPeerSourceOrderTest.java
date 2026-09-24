// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.ClusterConfig;
import org.pragmatica.aether.config.DhtReplicationConfig;
import org.pragmatica.aether.config.Environment;
import org.pragmatica.aether.config.NodeConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// #1475 — [Main#parsePeers] resolution order is explicit peers first, and the later arms must not
/// RUN when an earlier one wins. The discovery arm used to be passed to `Option.orElse(Option)`,
/// which evaluates its argument eagerly, so a CTM replacement carrying `--peers=` still blocked up
/// to 300s in provider discovery and, below a quorum, threw and exited. The provider here fails the
/// test on its first call, so a regression fails immediately instead of waiting out the timeout.
class MainPeerSourceOrderTest {

    private static final NodeId SELF = NodeId.nodeId("node-self").expect("valid id");
    private static final int PORT = 8090;

    @Test
    void parsePeers_neverRunsDiscovery_whenPeersArgumentPresent() {
        var calls = new AtomicInteger();
        var main = new Main(new String[]{"--peers=node-a:10.0.0.1:8090,node-self:10.0.0.9:8090,node-b:10.0.0.2:8090"});

        var peers = main.parsePeers(SELF, PORT, Map.of(), configWithNodes(5), withDiscovery(failingProvider(calls)));

        assertEquals(0, calls.get(), "discovery must not run when --peers= names the peers");
        assertEquals(List.of("node-a", "node-self", "node-b"), ids(peers), "the explicit peer list is the result");
    }

    /// Positive control: with no explicit peers the SAME wiring does reach the provider, so the test
    /// above cannot pass merely because discovery is unreachable from `parsePeers`.
    @Test
    void parsePeers_usesDiscovery_whenNoExplicitPeers() {
        var calls = new AtomicInteger();
        var main = new Main(new String[]{});
        var discovered = List.of(corePeer("node-a", "10.0.0.1"),
                                 corePeer("node-b", "10.0.0.2"),
                                 corePeer("node-self", "10.0.0.9"));

        var peers = main.parsePeers(SELF, PORT, Map.of(), configWithNodes(3), withDiscovery(listingProvider(calls, discovered)));

        assertEquals(1, calls.get(), "with no explicit peers, discovery must be consulted");
        assertEquals(List.of("node-a", "node-b", "node-self"), ids(peers), "the discovered core set is the result");
    }

    private static List<String> ids(List<NodeInfo> peers) {
        return peers.stream().map(p -> p.id().id()).toList();
    }

    private static PeerInfo corePeer(String nodeId, String host) {
        return PeerInfo.peerInfo(host, PORT, Map.of("aether-role", "core", "aether-node-id", nodeId)).unwrap();
    }

    private static Option<EnvironmentIntegration> withDiscovery(DiscoveryProvider provider) {
        return Option.some(EnvironmentIntegration.environmentIntegration(Option.none(),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.some(provider),
                                                                         Option.none(),
                                                                         Option.none(),
                                                                         Option.none()));
    }

    private static DiscoveryProvider failingProvider(AtomicInteger calls) {
        return new StubProvider(() -> {
            calls.incrementAndGet();
            throw new AssertionError("provider discovery ran although --peers= was present");
        });
    }

    private static DiscoveryProvider listingProvider(AtomicInteger calls, List<PeerInfo> peers) {
        return new StubProvider(() -> {
            calls.incrementAndGet();
            return Promise.success(peers);
        });
    }

    private static Option<AetherConfig> configWithNodes(int nodes) {
        return Option.some(AetherConfig.aetherConfig(ClusterConfig.clusterConfig(Environment.DOCKER).withNodes(nodes),
                                                     NodeConfig.nodeConfig(Environment.DOCKER),
                                                     Option.none(),
                                                     Option.none(),
                                                     Option.none(),
                                                     TtmConfig.ttmConfig(),
                                                     SliceConfig.sliceConfig(),
                                                     AppHttpConfig.appHttpConfig(),
                                                     BackupConfig.backupConfig(Environment.DOCKER),
                                                     DhtReplicationConfig.dhtReplicationConfig(),
                                                     TimeoutsConfig.timeoutsConfig()).unwrap());
    }

    private record StubProvider(java.util.function.Supplier<Promise<List<PeerInfo>>> onDiscover) implements DiscoveryProvider {
        @Override
        public Promise<List<PeerInfo>> discoverPeers() {
            return onDiscover.get();
        }

        @Override
        public Promise<Unit> watchPeers(Consumer<List<PeerInfo>> onChange) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stopWatching() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> registerSelf(PeerInfo self) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> deregisterSelf() {
            return Promise.success(Unit.unit());
        }
    }
}
