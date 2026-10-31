// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
package org.pragmatica.aether.node.assembly;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The node ids a replacement is minted with: a CLOUD core carries no cluster (the server name `<cluster>-<nodeId>` does), a Docker core keeps it
/// (container names are unique per daemon and several clusters share one).
class TopologyAssemblyIdScopeTest {
    private static final Supplier<Option<ClusterName>> PROD = () -> ClusterName.maybeClusterName("prod");

    private static ClusterTopologyManager manager(boolean cloudBacked) {
        return (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                               new Class<?>[]{ClusterTopologyManager.class},
                                                               (proxy, method, args) -> {
                                                                   if (method.getName().equals("coresAreCloudBacked")) {
                                                                       return cloudBacked;
                                                                   }

                                                                   throw new UnsupportedOperationException(method.getName());
                                                               });
    }

    @Test
    void cloudBackedCore_replacementIdsCarryNoCluster() {
        var scope = TopologyAssembly.idScope(manager(true), PROD);

        assertThat(scope.get()).isEqualTo(Option.none());
        assertThat(ProvisionContext.coreNodeNamePrefix(scope.get())).isEqualTo("aether-node");
    }

    @Test
    void dockerCore_replacementIdsKeepTheCluster() {
        var scope = TopologyAssembly.idScope(manager(false), PROD);

        assertThat(ProvisionContext.coreNodeNamePrefix(scope.get())).isEqualTo("aether-prod-node");
    }
}
