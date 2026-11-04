// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.LoadBalancerState;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.utility.react.ReactExecutor;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// D1-react: pins the `AetherNode.assembleNode` lines that install `reactors.executor("<Component>")` into each gated
/// C9 component. A component whose install line is missing keeps its default inline executor, so an engine stop
/// would not gate its side effects (§3.5 R5); the per-component `*EngineStopGateTest`s run on a hand-installed
/// executor and cannot see that. The node's executor registry creates an executor on first use, so a booted node
/// lists exactly the components whose install line ran.
class AetherNodeReactExecutorWiringBootTest {
    static final Set<String> GATED_COMPONENTS = Set.of("LeaderManager",
                                                       "LoadBalancerManager",
                                                       "ControlLoop",
                                                       "RollbackManager",
                                                       "ScheduledTaskRegistry",
                                                       "WorkerActivation",
                                                       "ClusterTopologyManager",
                                                       "SpokesmanPingLoop",
                                                       "BootstrapModule");

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void assembleNode_installsAnExecutorForEveryGatedComponent() {
        node = AetherNode.aetherNode(minimalConfig(tempDir),
                                     MessageRouter.DelegateRouter.delegate(),
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();

        var installed = node.reactExecutors()
                            .stream()
                            .map(ReactExecutor::component)
                            .collect(Collectors.toSet());

        assertThat(installed).as("every gated component has its react executor installed by assembleNode")
                             .containsAll(GATED_COMPONENTS);
    }

    /// Present only so the node builds its `LoadBalancerManager` (an environment with a load-balancer provider is
    /// the only way the node assembles one); nothing is ever routed through it.
    private record IdleLoadBalancer() implements LoadBalancerProvider {
        @Override
        public Promise<Unit> onRouteChanged(RouteChange routeChange) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> onNodeRemoved(String nodeIp) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> reconcile(LoadBalancerState state) {
            return Promise.success(Unit.unit());
        }
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("react-executor-wiring-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.DEFAULT)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.some(EnvironmentIntegration.environmentIntegration(Option.none(),
                                                                                         Option.none(),
                                                                                         Option.some(new IdleLoadBalancer()))))
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }
}
