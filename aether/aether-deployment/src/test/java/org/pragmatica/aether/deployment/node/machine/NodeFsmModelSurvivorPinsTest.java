// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;

/// Pins for `NodeFsmModel.permits`. Each test names the mutation that must turn it red.
class NodeFsmModelSurvivorPinsTest {
    private static final Version OTHER = Version.version("7.0.0").unwrap();
    private static final Version NEXT = Version.version("8.0.0").unwrap();

    /// Red when: `routings.containsKey(base) && routings.get(base).names(version)` becomes `||` (machine-47): with no routing
    /// for the base the lookup dereferences an absent entry; with a routing that names other versions it answers true.
    @Test
    @DisplayName("B-154: a version that is neither the target nor named by a routing is not permitted")
    void permits_refuses_aVersionNeitherTargetedNorRouted() {
        var targeted = NodeFsmModel.nodeFsmModel().withTarget(ARTIFACT.base(), OTHER);
        var routed = targeted.withRouting(ARTIFACT.base(), RoutingEntry.routingEntry(OTHER, NEXT));

        assertThat(targeted.permits(ARTIFACT)).as("no routing entry").isFalse();
        assertThat(routed.permits(ARTIFACT)).as("a routing entry that names other versions").isFalse();
    }

    /// Control: the target and a routed old or new version are permitted.
    @Test
    @DisplayName("B-154: the target, and a version a routing names, are permitted")
    void permits_allows_theTargetAndRoutedVersions() {
        var onTarget = NodeFsmModel.nodeFsmModel().withTarget(ARTIFACT.base(), ARTIFACT.version());
        var routed = NodeFsmModel.nodeFsmModel()
                                 .withTarget(ARTIFACT.base(), OTHER)
                                 .withRouting(ARTIFACT.base(), RoutingEntry.routingEntry(ARTIFACT.version(), OTHER));

        assertThat(onTarget.permits(ARTIFACT)).isTrue();
        assertThat(routed.permits(ARTIFACT)).isTrue();
        assertThat(NodeFsmModel.nodeFsmModel().permits(ARTIFACT)).as("an absent target permits nothing").isFalse();
    }
}
