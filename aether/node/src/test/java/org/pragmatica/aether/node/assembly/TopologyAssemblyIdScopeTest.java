// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
package org.pragmatica.aether.node.assembly;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
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

    /// The two call sites that use the scope (the reconciler's cluster supplier, and the replacement wiring's id prefix) cannot be driven without
    /// building the whole node assembly, so they are pinned by reading the source: the plain cluster supplier is read exactly once, inside `idScope`,
    /// and both sites use the scoped value. Replacing either with the plain supplier changes these counts.
    @Test
    void bothReplacementIdSites_useTheScopedSupplier_neverThePlainOne() throws IOException {
        var source = Files.readString(Path.of("src/main/java/org/pragmatica/aether/node/assembly/TopologyAssembly.java"))
                                        .replaceAll("\\s+", "");

        assertThat(count(source, "in.clusterNameSupplier()")).as("the plain supplier is read once").isEqualTo(1);
        assertThat(source).contains("replacementIdScope=idScope(clusterTopologyManager,in.clusterNameSupplier())");
        assertThat(source).as("the reconciler's cluster supplier").contains("clusterTopologyManager,replacementIdScope,TimeSource.system()");
        assertThat(source).as("the replacement wiring's id prefix").contains("coreNodeNamePrefix(replacementIdScope.get())");
        assertThat(count(source, "replacementIdScope")).as("the binding and the two sites").isEqualTo(3);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
