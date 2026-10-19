// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.RoutedEvent;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.schema.SchemaEvent;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.deployment.ReconciliationAdjustment;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// W-17 (event translation, H-3, H-4), W-18 (metrics), and the translator half of W-36 (the two sensor lines and the two metric arms) of the
/// phase-3 design 9.2; MT-17, MT-34, MT-38. The production wiring of the sinks (the router, the warning sink, the registry) is PR C's.
class EffectTranslatorTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final String AUDIT = "org.pragmatica.aether.audit";
    private static final String NODE_LOG = "org.pragmatica.aether.deployment.fsm.node";
    private static final String CLUSTER_LOG = "org.pragmatica.aether.deployment.fsm.cluster";

    private final List<Message> events = Collections.synchronizedList(new ArrayList<>());
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<String> migrations = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private LogCapture logs;
    private EffectTranslator translator;

    @BeforeEach
    void setUp() {
        logs = new LogCapture(AUDIT, NODE_LOG, CLUSTER_LOG, EffectTranslator.class.getName());
        translator = EffectTranslator.effectTranslator(new EffectTranslator.Targets(SELF,
                                                                                    events::add,
                                                                                    () -> OperatorWarningSink.handingOffTo(warnings::add),
                                                                                    registry,
                                                                                    migrations::add));
    }

    @AfterEach
    void tearDown() {
        logs.close();
    }

    private void node(NodeFsmEffect effect) {
        translator.nodeSink().accept(effect);
    }

    private void cluster(ClusterEffect effect) {
        translator.clusterSink().accept(effect);
    }

    // -- node Route (W-17) ----------------------------------------------------------------------------------------------
    @Test
    void aStateTransition_carriesThisNodesId_theMappedStatesInOrder_andTheTimestamp() {
        node(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.StateTransitioned(ORDERS, SliceObservedState.LOADED, SliceObservedState.ACTIVATING, 42L)));

        assertThat(events).containsExactly(DeploymentEvent.StateTransition.stateTransition(ORDERS, SELF, SliceState.LOADED, SliceState.ACTIVATING, 42L));
    }

    @Test
    void everyObservedState_hasAnEventStreamState() {
        var expected = Map.of(SliceObservedState.LOADING, SliceState.LOADING,
                              SliceObservedState.LOADED, SliceState.LOADED,
                              SliceObservedState.ACTIVATING, SliceState.ACTIVATING,
                              SliceObservedState.ACTIVE, SliceState.ACTIVE,
                              SliceObservedState.DEACTIVATING, SliceState.DEACTIVATING,
                              SliceObservedState.FAILED, SliceState.FAILED,
                              SliceObservedState.UNLOADING, SliceState.UNLOADING,
                              SliceObservedState.UNLOADED, SliceState.UNLOADING,
                              SliceObservedState.UNKNOWN, SliceState.UNKNOWN);

        for (var observed : SliceObservedState.values()) {
            assertThat(EffectTranslator.state(observed)).as(observed.name()).isEqualTo(expected.get(observed));
        }
    }

    @Test
    void aNodeDeploymentFailure_carriesTheRealReason_andTheStateItFailedAt() {
        node(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.DeploymentFailed(ORDERS, SliceObservedState.ACTIVATING, "boom", 7L)));

        assertThat(events).containsExactly(DeploymentEvent.DeploymentFailed.deploymentFailed(ORDERS, SELF, SliceState.ACTIVATING, "boom", 7L));
    }

    @Test
    void aCompletedDeployment_isRoutedWithThisNodesId() {
        node(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.DeploymentCompleted(ORDERS, 9L)));

        assertThat(events).containsExactly(DeploymentEvent.DeploymentCompleted.deploymentCompleted(ORDERS, SELF, 9L));
    }

    // -- cluster Route (W-17, H-3, H-4) ---------------------------------------------------------------------------------
    @Test
    void aStartedDeployment_isRoutedWithTheNodeTheGoalWasCommittedFor() {
        cluster(new ClusterEffect.Route(new RoutedEvent.DeploymentStarted(ORDERS, PEER, 5L)));

        assertThat(events).containsExactly(DeploymentEvent.DeploymentStarted.deploymentStarted(ORDERS, PEER, 5L));
    }

    @Test
    void aClusterDeploymentFailure_isRoutedAsFailed_forTheNodeNamed() {
        cluster(new ClusterEffect.Route(new RoutedEvent.DeploymentFailed(ORDERS, PEER, "gave up", 6L)));

        assertThat(events).containsExactly(DeploymentEvent.DeploymentFailed.deploymentFailed(ORDERS, PEER, SliceState.FAILED, "gave up", 6L));
    }

    /// W-17: it must reach the subscriber `abTestManager::onDeploymentFailed` is bound by: a route on the event's class.
    @Test
    void aDeploymentFailure_reachesARouteOnTheFailureEventClass() {
        var router = MessageRouter.mutable();
        var seen = new CopyOnWriteArrayList<DeploymentEvent.DeploymentFailed>();
        var viaRouter = EffectTranslator.effectTranslator(new EffectTranslator.Targets(SELF, router::route, () -> OperatorWarningSink.logOnly(), registry, migrations::add));

        router.addRoute(DeploymentEvent.DeploymentFailed.class, seen::add);
        viaRouter.clusterSink().accept(new ClusterEffect.Route(new RoutedEvent.DeploymentFailed(ORDERS, PEER, "gave up", 6L)));
        viaRouter.nodeSink().accept(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.DeploymentFailed(ORDERS, SliceObservedState.FAILED, "boom", 7L)));

        assertThat(seen).hasSize(2);
    }

    /// H-3: the neutral record, not the one inside `ClusterDeploymentManager`.
    @Test
    void aReconciliationAdjustment_isRoutedAsTheNeutralRecord() {
        cluster(new ClusterEffect.Route(new RoutedEvent.ReconciliationAdjustment(ORDERS, 3, 5)));

        assertThat(events).containsExactly(ReconciliationAdjustment.reconciliationAdjustment(ORDERS, 3, 5));
    }

    @Test
    void theTranslatorSource_doesNotReferenceClusterDeploymentManager() throws IOException {
        var source = Files.readString(Path.of("src/main/java/org/pragmatica/aether/node/deployment/EffectTranslator.java"));

        assertThat(source).as("MT-17 family: H-3 moved the record out of CDM so the FSM2 path does not depend on the legacy manager")
                          .doesNotContain("ClusterDeploymentManager");
    }

    /// H-4 (a): no field the indexed `SchemaVersionValue` knows is dropped.
    @Test
    void anActivationBlocked_carriesTheOwningBlueprint_theCoordinates_theAttemptCount_andTheBlockedSlices() {
        var blueprint = BlueprintId.blueprintId(Artifact.artifact("org.example:app:1.0.0").unwrap());

        cluster(new ClusterEffect.Route(new RoutedEvent.ActivationBlocked("main", blueprint, List.of("org.example:orders:1.0.0"), "org.example:schema:2.0.0", 3)));

        assertThat(events).hasSize(1);
        var event = (SchemaEvent.ActivationBlocked) events.getFirst();

        assertThat(event.datasource()).isEqualTo("main");
        assertThat(event.owningBlueprint()).as("MT-34: an enriched field is lost").isEqualTo(blueprint);
        assertThat(event.artifactCoords()).isEqualTo("org.example:schema:2.0.0");
        assertThat(event.attemptCount()).isEqualTo(3);
        assertThat(event.blockedSlices()).containsExactly("org.example:orders:1.0.0");
    }

    // -- Audit (W-17, H-4 (b)) -------------------------------------------------------------------------------------------
    /// The line is written by the typed method: it equals what the typed method writes when called directly.
    @Test
    void aScaleUpAudit_writesTheTypedAuditLogLine() {
        cluster(new ClusterEffect.Audit("reconciliation-scale-up", ORDERS.asString() + " 3 -> 5"));
        var viaTranslator = logs.on(AUDIT);

        org.pragmatica.aether.deployment.AuditLog.reconciliationScaleUp(ORDERS.asString(), 3, 5);
        var direct = logs.on(AUDIT).subList(viaTranslator.size(), logs.on(AUDIT).size());

        assertThat(viaTranslator).hasSize(1);
        assertThat(viaTranslator.getFirst().message()).isEqualTo("RECONCILIATION_SCALE_UP artifact=org.example:orders:1.0.0 currentInstances=3 desiredInstances=5");
        assertThat(viaTranslator).isEqualTo(direct);
    }

    @Test
    void aScaleDownAudit_writesTheTypedAuditLogLine() {
        cluster(new ClusterEffect.Audit("reconciliation-scale-down", ORDERS.asString() + " 5 -> 2"));

        assertThat(logs.on(AUDIT)).extracting(LogCapture.Line::message)
                                  .containsExactly("RECONCILIATION_SCALE_DOWN artifact=org.example:orders:1.0.0 currentInstances=5 desiredInstances=2");
    }

    @Test
    void aSchemaBlockedAudit_writesTheTypedLine_withTheSlicesOfTheRouteThatPrecededIt() {
        var blueprint = BlueprintId.blueprintId(Artifact.artifact("org.example:app:1.0.0").unwrap());

        cluster(new ClusterEffect.Route(new RoutedEvent.ActivationBlocked("main", blueprint, List.of("a:b:1.0.0", "c:d:2.0.0"), "org.example:schema:2.0.0", 1)));
        cluster(new ClusterEffect.Audit("schema-activation-blocked", "main owner=" + blueprint.asString()));

        assertThat(logs.on(AUDIT)).extracting(LogCapture.Line::message)
                                  .containsExactly("SCHEMA_ACTIVATION_BLOCKED datasource=main owningBlueprint=" + blueprint.asString() + " blockedSlices=[a:b:1.0.0, c:d:2.0.0]");
    }

    @Test
    void anAuditActionWithoutATypedMethod_writesNoLine_andIsReported() {
        cluster(new ClusterEffect.Audit("something-new", "detail"));

        assertThat(logs.on(AUDIT)).as("MT-34: never a free-text audit line").isEmpty();
        assertThat(logs.on(EffectTranslator.class.getName())).extracting(LogCapture.Line::level).contains(Level.ERROR);
    }

    @Test
    void aMalformedScaleDetail_writesNoLine() {
        cluster(new ClusterEffect.Audit("reconciliation-scale-up", "not the shape"));

        assertThat(logs.on(AUDIT)).isEmpty();
    }

    // -- warnings ------------------------------------------------------------------------------------------------------
    @Test
    void aCataloguedNodeWarning_reachesTheOperatorSink() {
        node(new NodeFsmEffect.Warn(OperatorWarningCode.SLICE_START_AWAITING_TARGET.code(), "org.example:orders:1.0.0", "waiting for a committed target"));

        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings.getFirst()).isEqualTo(OperatorWarning.operatorWarning(OperatorWarningCode.SLICE_START_AWAITING_TARGET, "org.example:orders:1.0.0", "waiting for a committed target"));
    }

    @Test
    void aResolvedWarning_reachesTheOperatorSink_underItsOwnCode() {
        node(new NodeFsmEffect.WarnResolved(OperatorWarningCode.SLICE_START_DEFERRAL_ENDED.code(), "org.example:orders:1.0.0", "the target arrived"));

        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.SLICE_START_DEFERRAL_ENDED);
    }

    @Test
    void aMessageWithBraces_isDeliveredVerbatim() {
        node(new NodeFsmEffect.Warn(OperatorWarningCode.SLICE_START_AWAITING_TARGET.code(), "s", "a {} b {0}"));

        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings.getFirst().message()).isEqualTo("a {} b {0}");
    }

    @Test
    void anUncataloguedClusterWarning_isLoggedAndCounted_notPublished() {
        cluster(new ClusterEffect.Warn("a-future-fsm-warning", "org.example:orders:1.0.0@core-2", "no desired row"));

        assertThat(warnings).isEmpty();
        assertThat(registry.counter("aether_deployment_fsm_warnings_unmapped_total", "code", "a-future-fsm-warning").count()).isEqualTo(1.0);
        assertThat(logs.on(EffectTranslator.class.getName())).anySatisfy(line -> {
            assertThat(line.level()).isEqualTo(Level.WARN);
            assertThat(line.message()).contains("a-future-fsm-warning").contains("org.example:orders:1.0.0@core-2").contains("no desired row");
        });
    }

    /// Obligation 4: a code the FSMs raise is now in the catalogue, so it reaches the cluster event log as an operator warning (and is not counted
    /// as unmapped); the unmapped path above stays for a code that is not.
    @Test
    void aCataloguedClusterWarning_isPublishedAsAnOperatorWarning_notCountedAsUnmapped() {
        cluster(new ClusterEffect.Warn("stray-instance", "org.example:orders:1.0.0@core-2", "no desired row"));

        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.STRAY_INSTANCE);
        assertThat(warnings.getFirst().subject()).isEqualTo("org.example:orders:1.0.0@core-2");
        assertThat(registry.counter("aether_deployment_fsm_warnings_unmapped_total", "code", "stray-instance").count()).isZero();
    }

    /// N-5: the backlog sampler's alarm is the catalogued warning `deployment-fsm-backlog` on the subject `<node>/<fsm>`, naming the depth and the threshold.
    @Test
    void theBacklogAlarm_raised_isTheCataloguedWarning_onTheNodeAndFsmSubject() {
        translator.backlogAlarm().raised("node", 10_001);

        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG);
        assertThat(warnings.getFirst().subject()).isEqualTo("core-1/node");
        assertThat(warnings.getFirst().message()).contains("node FSM").contains("10001").contains("10000");
        assertThat(registry.counter("aether_deployment_fsm_warnings_unmapped_total", "code", "deployment-fsm-backlog").count()).isZero();
    }

    @Test
    void theBacklogAlarm_cleared_isTheRecovery_onTheSameSubject() {
        translator.backlogAlarm().cleared("cluster", 12);

        await().atMost(Duration.ofSeconds(5)).until(() -> warnings.size() == 1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG_CLEARED);
        assertThat(warnings.getFirst().subject()).isEqualTo("core-1/cluster");
        assertThat(warnings.getFirst().message()).contains("cluster FSM").contains("12").contains("1000");
    }

    // -- metrics (W-18) ---------------------------------------------------------------------------------------------------
    @Test
    void aNodeMetric_isAMicrometerCounterOfThatNameAndTags() {
        node(new NodeFsmEffect.Metric("aether_deployment_fsm_inputs_total", Map.of("fsm", "node", "input", "ObservedPut", "result", "applied"), 1L));
        node(new NodeFsmEffect.Metric("aether_deployment_fsm_inputs_total", Map.of("fsm", "node", "input", "ObservedPut", "result", "applied"), 2L));

        assertThat(registry.counter("aether_deployment_fsm_inputs_total", "fsm", "node", "input", "ObservedPut", "result", "applied").count()).isEqualTo(3.0);
    }

    @Test
    void aClusterMetric_isAMicrometerCounter() {
        cluster(new ClusterEffect.Metric("aether_deployment_fsm_scans_total", Map.of("fsm", "cluster"), 4L));

        assertThat(registry.counter("aether_deployment_fsm_scans_total", "fsm", "cluster").count()).isEqualTo(4.0);
    }

    @Test
    void aClusterGauge_isSetToItsLatestValue() {
        cluster(new ClusterEffect.Gauge("unknown_process_nodes", Map.of("fsm", "cluster"), 3L));
        cluster(new ClusterEffect.Gauge("unknown_process_nodes", Map.of("fsm", "cluster"), 1L));

        assertThat(registry.get("unknown_process_nodes").tag("fsm", "cluster").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void theStepObserver_recordsAStepSecondsTimerPerFsmAndInput() {
        translator.stepObserver().stepped("node", "ObservedPut", 2_000_000L);
        translator.stepObserver().stepped("node", "ObservedPut", 4_000_000L);

        var timer = registry.get("aether_deployment_fsm_step_seconds").tag("fsm", "node").tag("input", "ObservedPut").timer();

        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(6.0);
    }

    @Test
    void theLocalOpCounter_countsPerStepAndResult() {
        translator.localOpCounter().counted("PUBLISH_ROUTES", "dropped_stale");

        assertThat(registry.counter("aether_deployment_fsm_localops_total", "step", "PUBLISH_ROUTES", "result", "dropped_stale").count()).isEqualTo(1.0);
    }

    @Test
    void theMailboxDepthGauge_readsTheInterpretersMailbox() {
        var depth = new AtomicInteger(5);

        translator.bindMailboxDepth("node", depth::get);
        depth.set(12);

        assertThat(registry.get("aether_deployment_fsm_mailbox_depth").tag("fsm", "node").gauge().value()).isEqualTo(12.0);
    }

    // -- log lines and the write-probe sensors (W-36, translator half) ---------------------------------------------------------
    @Test
    void theWriteProbeSensorLine_reachesTheNodeLoggerAtInfo() {
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.INFO, "write-probe-resubmit node=core-1 artifact=org.example:orders:1.0.0 probe 1"));

        assertThat(logs.on(NODE_LOG)).extracting(LogCapture.Line::level, LogCapture.Line::message)
                                     .containsExactly(org.assertj.core.groups.Tuple.tuple(Level.INFO, "write-probe-resubmit node=core-1 artifact=org.example:orders:1.0.0 probe 1"));
    }

    @Test
    void theWriteConfirmTimeoutSensorLine_reachesTheNodeLoggerAtWarn() {
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN, "write-confirm-timeout node=core-1 artifact=org.example:orders:1.0.0"));

        assertThat(logs.on(NODE_LOG)).extracting(LogCapture.Line::level).containsExactly(Level.WARN);
    }

    @Test
    void theTwoSensorMetricArms_reachMicrometer() {
        node(new NodeFsmEffect.Metric("aether_deployment_fsm_submissions_total", Map.of("fsm", "node", "kind", "observed", "outcome", "probe_resubmit"), 1L));
        node(new NodeFsmEffect.Metric("aether_deployment_fsm_submissions_total", Map.of("fsm", "node", "kind", "observed", "outcome", "confirm_timeout"), 1L));

        assertThat(registry.counter("aether_deployment_fsm_submissions_total", "fsm", "node", "kind", "observed", "outcome", "probe_resubmit").count()).isEqualTo(1.0);
        assertThat(registry.counter("aether_deployment_fsm_submissions_total", "fsm", "node", "kind", "observed", "outcome", "confirm_timeout").count()).isEqualTo(1.0);
    }

    @Test
    void everyNodeLogLevel_isWrittenAtItsLevel() {
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.DEBUG, "d"));
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.INFO, "i"));
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN, "w"));
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.ERROR, "e"));
        node(new NodeFsmEffect.Log(NodeFsmEffect.Level.CRITICAL, "c"));

        assertThat(logs.on(NODE_LOG)).extracting(LogCapture.Line::level, LogCapture.Line::message)
                                     .containsExactly(org.assertj.core.groups.Tuple.tuple(Level.DEBUG, "d"),
                                                      org.assertj.core.groups.Tuple.tuple(Level.INFO, "i"),
                                                      org.assertj.core.groups.Tuple.tuple(Level.WARN, "w"),
                                                      org.assertj.core.groups.Tuple.tuple(Level.ERROR, "e"),
                                                      org.assertj.core.groups.Tuple.tuple(Level.ERROR, "CRITICAL: c"));
    }

    @Test
    void aClusterLogLine_reachesTheClusterLogger() {
        cluster(new ClusterEffect.Log("reconcile took 3 ms"));

        assertThat(logs.on(CLUSTER_LOG)).extracting(LogCapture.Line::message).containsExactly("reconcile took 3 ms");
    }

    // -- migrations and misrouted effects -------------------------------------------------------------------------------------
    @Test
    void aMigrationDispatch_reachesTheSchemaOrchestrator() {
        cluster(new ClusterEffect.DispatchMigration("main"));

        assertThat(migrations).containsExactly("main");
    }

    @Test
    void anEffectTheInterpreterExecutesItself_isReportedNotTranslated() {
        node(new NodeFsmEffect.SelfReady());
        cluster(new ClusterEffect.RequestRebuild(1L));

        assertThat(events).isEmpty();
        assertThat(logs.on(EffectTranslator.class.getName())).extracting(LogCapture.Line::level).containsExactly(Level.ERROR, Level.ERROR);
    }
}
