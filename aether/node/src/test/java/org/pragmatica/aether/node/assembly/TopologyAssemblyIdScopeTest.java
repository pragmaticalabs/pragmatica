// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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

    /// The scope serves the reconciler, which replaces CORE nodes only. The replacement wiring chooses the shape per node itself (by the source of the
    /// node being replaced: `NodeReplacementServiceTest`), so it takes the plain cluster supplier. The reconciler's site cannot be driven without
    /// building the whole node assembly, so it is pinned by reading the source: the plain supplier is read twice (inside `idScope`, and by the wiring),
    /// and the reconciler uses the scoped value. Replacing either changes these counts.
    @Test
    void theReconcilerSite_usesTheScopedSupplier_neverThePlainOne() throws IOException {
        var source = Files.readString(Path.of("src/main/java/org/pragmatica/aether/node/assembly/TopologyAssembly.java"))
                                        .replaceAll("\\s+", "");

        assertThat(count(source, "in.clusterNameSupplier()")).as("the plain supplier: the scope's, and the wiring's").isEqualTo(2);
        assertThat(source).contains("replacementIdScope=idScope(clusterTopologyManager,in.clusterNameSupplier())");
        assertThat(source).as("the reconciler's cluster supplier").contains("clusterTopologyManager,replacementIdScope,TimeSource.system()");
        assertThat(count(source, "replacementIdScope")).as("the binding and the reconciler's site").isEqualTo(2);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
