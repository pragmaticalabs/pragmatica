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

/// #1574 / #1617 wiring pins. The operator-warning sink and the throttle eviction are wired inline in `AetherNode`'s
/// assembly, which no unit test constructs, and the site tests hand each component a test sink, so un-binding a site
/// (or dropping the eviction schedule) left every unit test green. Following `LivePlacementMembersWiringTest`, these
/// read the assembly source with comments stripped and whitespace removed. They pin that the wiring is PRESENT; that
/// the sink and scheduler actually run is shared by every other site and task in the node.
class OperatorWarningWiringTest {
    @Test
    void assembly_bindsOneSinkToThisNodesAggregator() {
        assertThat(assemblyCode()).contains("varoperatorWarningSink=OperatorWarningSink.handingOffTo(eventAggregator::onOperatorWarning);");
    }

    @Test
    void assembly_givesTheSinkToSwimCoreAbsenceAndReplication() {
        var code = assemblyCode();

        assertThat(code).contains("swimHealthDetector.setOperatorWarningSink(operatorWarningSink);");
        assertThat(code).contains("coreAbsenceDetector.setOperatorWarningSink(operatorWarningSink);");
        assertThat(code).contains("streamOwnershipViews.writeAuthority(),operatorWarningSink);");
    }

    /// #1935: a declared stream consumer that cannot be registered reaches this node's sink. The deployment test hands
    /// the context a test sink, so un-binding it here left every unit test green.
    @Test
    void assembly_givesTheSinkToNodeDeployment() {
        assertThat(assemblyCode()).contains("nodeDeploymentManager.setOperatorWarningSink(operatorWarningSink);");
    }

    /// #1564 (R10): the replication warnings raised at resource activation, the deploy warnings and the refused
    /// cluster-events registration all reach this node's sink. Un-binding any of them leaves every unit test green.
    @Test
    void assembly_givesTheSinkToActivationDeployAndClusterEventsRefusal() {
        var code = assemblyCode();

        assertThat(code).contains("spi.registerExtension(OperatorWarningSink.class,operatorWarningSink);");
        assertThat(code).contains("BlueprintService.blueprintService(clusterNode,kvStore,repository,artifactStore,resourceProviderSetup.nodeComposite(),operatorWarningSink);");
        assertThat(code).contains("cause->raiseClusterEventsRefusal(operatorWarningSink,alertManager,cause)");
        // v1680 N-r3-1: the refusal is also a REST-visible alert, resolved when a corrected config commits the stream.
        assertThat(code).contains("()->alertManager.clearInjected(OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED.code())");
        // v1735 B1: local-only, so the recovery clear resolves it completely (a replicated copy would outlive it).
        assertThat(code).contains("alertManager.injectLocal(OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED.code(),\"CRITICAL\",");
    }

    /// #1564 N2: the cluster-events local partition built at construction takes the committed
    /// `[replication.cluster_events]` factors through `ClusterEventsLimits.streamConfig` (pinned by
    /// `ClusterEventsLimitsTest`), never a separately hardcoded config.
    @Test
    void assembly_buildsTheClusterEventsConfigFromTheCommittedFactors() {
        assertThat(assemblyCode()).contains("varclusterEventsStreamConfig=clusterEventsLimits.streamConfig(clusterEventsStreamName,kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,AetherValue.ClusterConfigValue.class));");
    }

    @Test
    void assembly_evictsIdleThrottleKeysOncePerMinute() {
        var code = assemblyCode();

        assertThat(code).contains("periodicTasks.defer(()->SharedScheduler.scheduleAtFixedRate(eventAggregator::evictIdleThrottleWindows,OPERATOR_WARNING_EVICTION_INTERVAL,OPERATOR_WARNING_EVICTION_INTERVAL));");
        assertThat(code).contains("TimeSpanOPERATOR_WARNING_EVICTION_INTERVAL=TimeSpan.timeSpan(60).seconds();");
    }

    /// #1431: the owner gate's block alarm reaches this node's sink, so an oversized peer event is an operator event.
    @Test
    void assembly_givesTheSinkToTheOwnerPromotionBlockAlarm() {
        assertThat(assemblyCode()).contains("block->raiseOwnerPromotionBlock(operatorWarningSink,block),");
    }

    /// `AetherNode.java` with line comments removed and all whitespace stripped. An unreadable file fails loudly,
    /// because a pin that scans nothing always passes.
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
            var testClasses = Path.of(OperatorWarningWiringTest.class.getProtectionDomain()
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
