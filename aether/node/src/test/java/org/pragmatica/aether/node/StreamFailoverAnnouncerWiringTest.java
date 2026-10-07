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

/// #1730 owner ruling: the failover-refusal event reaches the stream only if EVERY node's KV router feeds committed
/// ownership Puts to the announcer, and the announcer routes into the operational-event path whose aggregator handlers
/// are registered. The announcer's behaviour is pinned by `StreamFailoverAnnouncementTest` and the owner gate by
/// `ClusterEventAggregatorTest`; this pins the production wiring that connects them, which neither can reach.
class StreamFailoverAnnouncerWiringTest {
    /// [unverified: wiring order pinned by text only] The announcer-before-manager order and the route registrations are
    /// source-text matches on `AetherNode`; the behaviour of the derivation is pinned by `StreamIsrAnnouncerTest` and the
    /// manager's prediction by `StreamLifeAuthorityTest`, neither of which can reach the assembly.
    @Test
    void everyNodeFeedsCommittedOwnershipPutsToTheAnnouncer_andTheEventsReachTheAggregator() {
        var code = assemblyCode();

        assertThat(code).contains("StreamFailoverAnnouncer.streamFailoverAnnouncer(()->streamIsrInputs(clusterEventsControllerRef).liveMembers(),delegateRouter::route);");
        assertThat(code).contains(".onPut(AetherKey.StreamPartitionOwnershipKey.class,streamFailoverAnnouncer::onOwnershipPut)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.StreamFailoverRefused.class,eventAggregator::onStreamFailoverRefused)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.StreamFailoverResolved.class,eventAggregator::onStreamFailoverResolved)");
        // #1883: the in-sync-set announcer is fed by the same committed Puts, takes the factor from the stream's committed
        // config, and its events reach the aggregator.
        assertThat(code).contains("StreamIsrAnnouncer.streamIsrAnnouncer(streamPartitionManager::confirmationFactorFor,streamPartitionManager::confirmationFactorAfter,streamPartitionManager::enforcedConfig,");
        // A committed config Put that moves the factor is announced too, and it is announced BEFORE the manager installs
        // that config: the factor it enforces before the Put is the baseline.
        assertThat(code).contains(".onPut(AetherKey.StreamConfigKey.class,streamIsrAnnouncer::onConfigPut).onPut(AetherKey.StreamConfigKey.class,streamPartitionManager::onStreamConfigPut)");
        assertThat(code).contains(".onPut(AetherKey.StreamPartitionOwnershipKey.class,streamIsrAnnouncer::onOwnershipPut)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.StreamIsrBelowMinimum.class,eventAggregator::onStreamIsrBelowMinimum)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.StreamIsrRestored.class,eventAggregator::onStreamIsrRestored)");
        // #1873: and the lineage-restart announcer, fed by the same Puts.
        assertThat(code).contains("StreamLineageAnnouncer.streamLineageAnnouncer(delegateRouter::route);");
        assertThat(code).contains(".onPut(AetherKey.StreamPartitionOwnershipKey.class,streamLineageAnnouncer::onOwnershipPut)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.StreamLineageRestarted.class,eventAggregator::onStreamLineageRestarted)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.StreamConfigChangeNotApplied.class,eventAggregator::onStreamConfigChangeNotApplied)");
        // #1723: a scheduled task's unknown fire outcome and its late resolution are derived from the committed task state
        // on every node and reach the aggregator.
        assertThat(code).contains("ScheduledTaskOutcomeAnnouncer.scheduledTaskOutcomeAnnouncer(delegateRouter::route);");
        assertThat(code).contains(".onPut(AetherKey.ScheduledTaskStateKey.class,scheduledTaskOutcomeAnnouncer::onStatePut)");
        assertThat(code).contains("MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,scheduledTaskManager::onNodeRemoved)");
        assertThat(code).contains("MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,scheduledTaskManager::onNodeDecommissioned)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.ScheduledTaskOutcomeUnknown.class,eventAggregator::onScheduledTaskOutcomeUnknown)");
        assertThat(code).contains("MessageRouter.Entry.route(OperationalEvent.ScheduledTaskOutcomeRestored.class,eventAggregator::onScheduledTaskOutcomeRestored)");
    }

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
            var testClasses = Path.of(StreamFailoverAnnouncerWiringTest.class.getProtectionDomain()
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
