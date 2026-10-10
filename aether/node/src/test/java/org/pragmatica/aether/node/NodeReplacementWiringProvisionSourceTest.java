// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.ProvisionDisposition;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F2: a node of a cluster bootstrapped from static PEERS is known to its peers with no source label, so its replacement record
/// carries a blank source. The replacement must then ask the topology manager to derive the source from the committed config; passing the
/// blank on as the source "default" named a source no config has, and the first replacement of a freshly bootstrapped cluster was refused.
class NodeReplacementWiringProvisionSourceTest {
    private final List<String> calls = new ArrayList<>();

    private ClusterTopologyManager recordingCtm() {
        return (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                               new Class[]{ClusterTopologyManager.class},
                                                               (_, method, args) -> record(method.getName(), method.getParameterCount(), args));
    }

    private Object record(String name, int parameterCount, Object[] args) {
        calls.add(parameterCount == 5
                  ? "explicit:" + args[4]
                  : name.equals("provisionReplacementWithoutSource")
                    ? "without-source"
                    : "other:" + name);

        return Promise.success(ProvisionDisposition.deferred(ProvisionDisposition.DeferralReason.CIRCUIT_OPEN));
    }

    private static NodeReplacementValue recordWithSource(String source) {
        return new NodeReplacementValue(new NodeId("new-node"), "core", NodeReplacementPhase.PROVISIONING, 1L, source, "1.0.0-rc5", "CTM", 0, "", 0L);
    }

    @Test
    void provisionFor_namedSource_isPassedOnAsIs() {
        NodeReplacementWiring.provisionFor(recordingCtm(), recordWithSource("docker"), Set.of()).await();

        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst()).startsWith("explicit:").contains("docker");
    }

    /// A blank source never reaches the topology manager as "default": it is refused (the reconciler commits a resolved source first).
    @Test
    void provisionFor_blankSource_isRefused_andNothingIsAskedOfTheTopologyManager() {
        var result = NodeReplacementWiring.provisionFor(recordingCtm(), recordWithSource(""), Set.of()).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(calls).isEmpty();
    }
}
