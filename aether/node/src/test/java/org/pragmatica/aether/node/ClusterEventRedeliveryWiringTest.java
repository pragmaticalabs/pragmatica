// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1640 / #1653 wiring pins for the two redelivery drivers. Both are wired inline inside `AetherNode`'s node
/// assembly, which no unit test can construct, and the unit tests call the aggregator's methods directly, so removing
/// either wiring leaves every behavioural test in this module green (measured: 1,965 testcases, 0 red, for the
/// ownership-put route). Following `LivePlacementMembersWiringTest`, these pins read the assembly source with comments
/// stripped and whitespace removed. They pin that the wiring expression is present in the assembly; that the router
/// and the periodic tasks it feeds are started is shared by every route and task in the node.
class ClusterEventRedeliveryWiringTest {
    /// A committed ownership put for any stream partition reaches the aggregator, which re-sends every waiting event
    /// at once when it is the cluster-events partition (`ClusterEventAggregator.onStreamPartitionOwnershipPut`).
    @Test
    void assembly_routesStreamPartitionOwnershipPutsToTheEventAggregator() {
        assertThat(assemblyCode()).contains("kvRouterBuilder.onPut(AetherKey.StreamPartitionOwnershipKey.class,eventAggregator::onStreamPartitionOwnershipPut);");
    }

    /// Held events are re-sent on a 1 s tick when no ownership change arrives.
    @Test
    void assembly_schedulesTheRedeliveryTickEverySecond() {
        var code = assemblyCode();

        assertThat(code).contains("periodicTasks.defer(()->SharedScheduler.scheduleAtFixedRate(eventAggregator::redeliverDue,CLUSTER_EVENT_REDELIVERY_INTERVAL,CLUSTER_EVENT_REDELIVERY_INTERVAL));");
        assertThat(code).contains("TimeSpanCLUSTER_EVENT_REDELIVERY_INTERVAL=TimeSpan.timeSpan(1).seconds();");
    }

    /// `AetherNode.java` with line comments removed and all whitespace stripped, so a pin matches the wiring
    /// expression regardless of formatter line breaks. An unreadable file fails loudly, because a pin that scans
    /// nothing always passes.
    private static String assemblyCode() {
        var file = sourceRoot().resolve("org/pragmatica/aether/node/AetherNode.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(ClusterEventRedeliveryWiringTest.class.getProtectionDomain()
                                                                             .getCodeSource()
                                                                             .getLocation()
                                                                             .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
