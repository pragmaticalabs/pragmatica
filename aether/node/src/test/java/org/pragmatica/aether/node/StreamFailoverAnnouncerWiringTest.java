// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

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

        assertThat(code).containsOnlyOnce("StreamFailoverAnnouncer.streamFailoverAnnouncer(()->streamIsrInputs(clusterEventsControllerRef).liveMembers(),delegateRouter::route);");
        assertThat(code).containsOnlyOnce(".onPut(AetherKey.StreamPartitionOwnershipKey.class,streamFailoverAnnouncer::onOwnershipPut)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.StreamFailoverRefused.class,eventAggregator::onStreamFailoverRefused)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.StreamFailoverResolved.class,eventAggregator::onStreamFailoverResolved)");
        // #1883: the in-sync-set announcer is fed by the same committed Puts, takes the factor from the stream's committed
        // config, and its events reach the aggregator.
        assertThat(code).containsOnlyOnce("StreamIsrAnnouncer.streamIsrAnnouncer(streamPartitionManager::confirmationFactorFor,streamPartitionManager::confirmationFactorAfter,streamPartitionManager::enforcedConfig,");
        // A committed config Put that moves the factor is announced too, and it is announced BEFORE the manager installs
        // that config: the factor it enforces before the Put is the baseline.
        assertThat(code).containsOnlyOnce(".onPut(AetherKey.StreamConfigKey.class,streamIsrAnnouncer::onConfigPut).onPut(AetherKey.StreamConfigKey.class,streamPartitionManager::onStreamConfigPut)");
        assertThat(code).containsOnlyOnce(".onPut(AetherKey.StreamPartitionOwnershipKey.class,streamIsrAnnouncer::onOwnershipPut)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.StreamIsrBelowMinimum.class,eventAggregator::onStreamIsrBelowMinimum)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.StreamIsrRestored.class,eventAggregator::onStreamIsrRestored)");
        // #1873: and the lineage-restart announcer, fed by the same Puts.
        assertThat(code).containsOnlyOnce("StreamLineageAnnouncer.streamLineageAnnouncer(delegateRouter::route);");
        assertThat(code).containsOnlyOnce(".onPut(AetherKey.StreamPartitionOwnershipKey.class,streamLineageAnnouncer::onOwnershipPut)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.StreamLineageRestarted.class,eventAggregator::onStreamLineageRestarted)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.StreamConfigChangeNotApplied.class,eventAggregator::onStreamConfigChangeNotApplied)");
        // #1723: a scheduled task's unknown fire outcome and its late resolution are derived from the committed task state
        // on every node and reach the aggregator.
        assertThat(code).containsOnlyOnce("ScheduledTaskOutcomeAnnouncer.scheduledTaskOutcomeAnnouncer(delegateRouter::route);");
        assertThat(code).containsOnlyOnce(".onPut(AetherKey.ScheduledTaskStateKey.class,scheduledTaskOutcomeAnnouncer::onStatePut)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(MembershipDecision.NodeRemoved.class,scheduledTaskManager::onNodeRemoved)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(MembershipDecision.NodeDecommissioned.class,scheduledTaskManager::onNodeDecommissioned)");
        // #1206: two artifacts serving one route, derived from the committed route table on every node.
        assertThat(code).containsOnlyOnce("RouteCollisionAnnouncer.routeCollisionAnnouncer(delegateRouter::route);");
        assertThat(code).containsOnlyOnce(".onPut(AetherKey.NodeRoutesKey.class,routeCollisionAnnouncer::onRoutesPut).onRemove(AetherKey.NodeRoutesKey.class,routeCollisionAnnouncer::onRoutesRemove)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.RoutePrefixCollision.class,eventAggregator::onRoutePrefixCollision)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.RoutePrefixCollisionCleared.class,eventAggregator::onRoutePrefixCollisionCleared)");
        // #1930: the scheduler's in-flight observer feeds the aggregator through the operational-event path.
        assertThat(code).containsOnlyOnce("ScheduledFireAnnouncer.scheduledFireAnnouncer(config.self(),delegateRouter::route)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.ScheduledTaskFireHeld.class,eventAggregator::onScheduledTaskFireHeld)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.ScheduledTaskFireReleased.class,eventAggregator::onScheduledTaskFireReleased)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.ScheduledTaskOutcomeUnknown.class,eventAggregator::onScheduledTaskOutcomeUnknown)");
        assertThat(code).containsOnlyOnce("MessageRouter.Entry.route(OperationalEvent.ScheduledTaskOutcomeRestored.class,eventAggregator::onScheduledTaskOutcomeRestored)");
    }

    private static String assemblyCode() {
        return AssemblySource.stripped();
    }
}
