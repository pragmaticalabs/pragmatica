// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

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
        assertThat(assemblyCode()).containsOnlyOnce("varoperatorWarningSink=OperatorWarningSink.handingOffTo(eventAggregator::onOperatorWarning);");
    }

    @Test
    void assembly_givesTheSinkToSwimCoreAbsenceAndReplication() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("swimHealthDetector.setOperatorWarningSink(operatorWarningSink);");
        assertThat(code).containsOnlyOnce("coreAbsenceDetector.setOperatorWarningSink(operatorWarningSink);");
        assertThat(code).containsOnlyOnce("streamOwnershipViews.writeAuthority(),operatorWarningSink);");
    }

    /// #752: the declarative consumer manager reports a divergence from its consumer runtime to this node's sink.
    /// A storage instance whose promotion keeps failing without copying anything reaches this node's sink through the stall
    /// watch the maintenance driver runs after every promotion cycle. The watch and the driver are tested with their own sink
    /// and managers, so un-binding either here, or handing the watch a different sink or a partial setup map, left them green.
    @Test
    void assembly_givesTheMaintenanceDriverAStallWatchOverEverySetupOnThisNodesSink() {
        assertThat(assemblyCode()).containsOnlyOnce("StorageMaintenanceDriver.storageMaintenanceDriver(compositeDemotionManager,compositePromotionManager,PromotionStallWatch.promotionStallWatch(storageSetups,operatorWarningSink),compositeGarbageCollector);");
    }

    /// The ready signal can fire again after a drain began; the node tells it whether it is draining from the drain procedure's own
    /// state, for storage maintenance and for prefetching alike. `StoragePromotionWiringTest` and `AetherNodeReadySignalPrefetchTest`
    /// test the guards with a supplied predicate, so binding a constant here left them green.
    @Test
    void assembly_tellsTheStorageReadySignalWhetherTheDrainProcedureHasStarted() {
        assertThat(assemblyCode()).containsOnlyOnce("BooleanSuppliernodeDraining=()->drainProcedure.state()!=DrainProcedure.DrainState.INACTIVE;");
        assertThat(assemblyCode()).containsOnlyOnce("signalReadyActivatingStorage(()->signalReadyAndWakePrefetch(nodeLifecycle::signalReady,storageSetups,nodeDraining),storageSetups,nodeDraining)");
    }

    @Test
    void assembly_givesTheSinkToTheStreamConsumerManager() {
        assertThat(assemblyCode()).containsOnlyOnce("streamConsumerManager.setOperatorWarningSink(operatorWarningSink);");
    }

    /// #1935: a declared stream consumer that cannot be registered reaches this node's sink. The deployment test hands
    /// the context a test sink, so un-binding it here left every unit test green.
    @Test
    void assembly_givesTheSinkToNodeDeployment() {
        assertThat(assemblyCode()).containsOnlyOnce("nodeDeploymentManager.setOperatorWarningSink(operatorWarningSink);");
    }

    /// #1996: request frames the transport drops at an offline-buffer flush reach the event stream only if the assembly routes
    /// the transport's notification to this node's aggregator, and the transport honours the operator's cap only if the assembly
    /// hands it over. The aggregator and transport tests call their halves directly, so un-binding either left them green.
    @Test
    void assembly_routesDroppedOfflineFramesToTheEventAggregator() {
        assertThat(assemblyCode()).containsOnlyOnce("MessageRouter.Entry.route(NetworkServiceMessage.OfflineFramesExpired.class,eventAggregator::onOfflineFramesExpired)");
    }

    @Test
    void assembly_givesTheTransportTheClusterOfflineBufferCap() {
        assertThat(assemblyCode()).containsOnlyOnce("clusterNode.network().setOfflineBufferCap(config.timeouts().cluster().offlineBufferCap());");
    }

    /// The stream-forward and replication transports are adapters assembled here; each must pass the caller's wait on to the
    /// network, or the per-path tests (which hand the client a recording transport) stay green while the frame goes plain.
    @Test
    void assembly_streamForwardAndReplicationAdaptersPassTheCallersWaitToTheNetwork() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("network.send(target,message,callerWait);");
        assertThat(code).containsOnlyOnce("replicationNetwork.send(target,message,callerWait);");
    }

    /// #1734: the entity-ownership reconciler's first-mint ceiling warning reaches this node's sink only if the assembly hands
    /// it over; the reconciler tests pass their own sink, so un-binding it here left them green.
    @Test
    void assembly_givesTheSinkToTheEntityOwnershipReconciler() {
        assertThat(assemblyCode()).containsOnlyOnce("EntityOwnershipReconciler.SETTLE_TICKS,EntityOwnershipReconciler.FIRST_MINT_CEILING_TICKS,ENTITY_OWNERSHIP_RECONCILE_INTERVAL,System::nanoTime,operatorWarningSink);");
    }

    /// A certificate rotation the HTTP listeners refuse is an operator event; un-binding the sink here would leave the
    /// listener tests (which hand in their own sink) green while the node reported nothing.
    @Test
    void assembly_givesTheSinkToTheHttpListenersForCertificateRotationRefusals() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("appHttpServer.setOperatorWarningSink(operatorWarningSink);");
        assertThat(code).containsOnlyOnce("managementServer.setOperatorWarningSink(operatorWarningSink);");
    }

    /// #1564 (R10): the replication warnings raised at resource activation, the deploy warnings and the refused
    /// cluster-events registration all reach this node's sink. Un-binding any of them leaves every unit test green.
    @Test
    void assembly_givesTheSinkToActivationDeployAndClusterEventsRefusal() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("spi.registerExtension(OperatorWarningSink.class,operatorWarningSink);");
        // The node's operator-warning sink is the sixth argument; what follows it (the HEADER-mode flag admission needs, #1206) is pinned on its own.
        assertThat(code).containsOnlyOnce("BlueprintService.blueprintService(clusterNode,kvStore,repository,artifactStore,resourceProviderSetup.nodeComposite(),operatorWarningSink,");
        assertThat(code).containsOnlyOnce("operatorWarningSink,config.appHttp().apiVersioningDetection().isHeaderMode());");
        assertThat(code).containsOnlyOnce("cause->raiseClusterEventsRefusal(operatorWarningSink,alertManager,cause)");
        // v1680 N-r3-1: the refusal is also a REST-visible alert, resolved when a corrected config commits the stream.
        assertThat(code).containsOnlyOnce("()->alertManager.clearInjected(OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED.code())");
        // v1735 B1: local-only, so the recovery clear resolves it completely (a replicated copy would outlive it).
        assertThat(code).containsOnlyOnce("alertManager.injectLocal(OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED.code(),\"CRITICAL\",");
    }

    /// #1934: the stream consumer runtime reports a consumer whose delivery passes keep throwing to this node's sink.
    @Test
    void assembly_givesTheSinkToTheStreamConsumerRuntime() {
        assertThat(assemblyCode()).containsOnlyOnce("streamConsumerRuntime.operatorWarnings(operatorWarningSink);");
    }

    /// #1564 N2: the cluster-events local partition built at construction takes the committed
    /// `[replication.cluster_events]` factors through `ClusterEventsLimits.streamConfig` (pinned by
    /// `ClusterEventsLimitsTest`), never a separately hardcoded config.
    @Test
    void assembly_buildsTheClusterEventsConfigFromTheCommittedFactors() {
        assertThat(assemblyCode()).containsOnlyOnce("varclusterEventsStreamConfig=clusterEventsLimits.streamConfig(clusterEventsStreamName,kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,AetherValue.ClusterConfigValue.class));");
    }

    @Test
    void assembly_evictsIdleThrottleKeysOncePerMinute() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("defer.accept(()->SharedScheduler.scheduleAtFixedRate(eventAggregator::evictIdleThrottleWindows,OPERATOR_WARNING_EVICTION_INTERVAL,OPERATOR_WARNING_EVICTION_INTERVAL));");
        assertThat(code).containsOnlyOnce("TimeSpanOPERATOR_WARNING_EVICTION_INTERVAL=TimeSpan.timeSpan(60).seconds();");
    }

    /// #1431: the owner gate's block alarm reaches this node's sink, so an oversized peer event is an operator event.
    @Test
    void assembly_givesTheSinkToTheOwnerPromotionBlockAlarm() {
        assertThat(assemblyCode()).containsOnlyOnce("ownerPromotionAlarm(operatorWarningSink),");
    }

    /// #1937: the promoted owner's BACKFILL reports a peer's oversized event through the same sink, once per transition. Red
    /// without the wiring: the backfill's alarm stays the no-op default and the refusal is only a log line.
    @Test
    void assembly_givesTheSinkToTheBackfillBlockAlarm() {
        // #2080: both bindings (the alarm and the escape's reader and bound) live in one helper the wiring test drives; the assembly
        // must call it with this node's sink.
        assertThat(assemblyCode()).containsOnlyOnce("backfill.blockAlarm(ownerPromotionAlarm(sink));")
                                  .containsOnlyOnce("bindPromotionAlarm(streamPartitionBackfill,operatorWarningSink,kvStore,streamingConfig.promotionEscapeAfter());");
    }

    /// A0b: storage is built before this node's aggregator exists, so its degrade reporters reach the sink only through this
    /// call; the instance tests hand the reporter their own sink, so un-binding it here left them green.
    @Test
    void assembly_bindsEveryStorageInstancesDegradeReporterToThisNodesSink() {
        assertThat(assemblyCode()).containsOnlyOnce("StorageFactory.bindOperatorWarnings(storageSetups,operatorWarningSink);");
    }

    /// A0b (SD-01/SB-11): the readiness gates learn about consensus at the node's ready signal. The gate tests call
    /// `signalConsensusSynced` directly, so dropping the call from the signal left them green.
    @Test
    void assembly_signalsConsensusSyncedAtTheStorageReadySignal() {
        assertThat(assemblyCode()).containsOnlyOnce("StorageFactory.signalConsensusSynced(storageSetups);");
    }

    /// A0b: the gauge a degraded instance is seen through is registered per instance by the management server.
    @Test
    void managementServer_registersTheDiskDegradedGaugePerInstance() throws Exception {
        var source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/org/pragmatica/aether/api/ManagementServer.java"))
                                         .replaceAll("\\s+", "");

        assertThat(source).containsOnlyOnce("observability.gauge(\"aether.storage.disk.degraded\",degraded,\"instance\",instance);");
    }

    /// The assembly source (`AetherNode.java` plus `node/assembly/*.java`) with line comments removed and all whitespace
    /// stripped; each pin must match exactly once across that set. See [AssemblySource].
    private static String assemblyCode() {
        return AssemblySource.stripped();
    }
}
