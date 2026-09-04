// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #542 — the schema gate that decides whether a LOADED slice may activate.
///
/// Two independent defects are pinned here. The first is the status set: the pre-fix gate blocked on
/// PENDING/MIGRATING only, so a permanently FAILED migration RELEASED the slice while a recoverable
/// retry (which `SchemaOrchestratorService.scheduleRetry` writes back as PENDING) held it — exactly
/// inverted. The second is scope: the gate scanned EVERY `SchemaVersionKey` record regardless of
/// which blueprint wrote it, and datasource names are cluster-global, so one blueprint's failed
/// `"database"` migration froze every unrelated blueprint in the cluster.
class SchemaActivationGateTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final Artifact SLICE = Artifact.artifact("org.example:orders-api:1.0.0").unwrap();
    private static final ArtifactBase SLICE_BASE = ArtifactBase.artifactBase("org.example:orders-api").unwrap();
    private static final Version SLICE_VERSION = Version.version("1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final BlueprintId OWNER_UPGRADED = BlueprintId.blueprintId("org.example:orders-app:2.0.0").unwrap();
    private static final BlueprintId OTHER_OWNER = BlueprintId.blueprintId("org.example:billing-app:1.0.0").unwrap();
    private static final String OWNED_DATASOURCE = "database";
    private static final String OTHER_DATASOURCE = "database.billing";
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final SliceNodeKey SLICE_KEY = SliceNodeKey.sliceNodeKey(SLICE, NODE_A);
    private static final String ACTIVE_LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState$Active";

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;
    private List<String> migrateCalls;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        migrateCalls = new ArrayList<>();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory = fsm -> new ClusterDeploymentContext(fsm,
                                                                                                                                     SELF,
                                                                                                                                     cluster,
                                                                                                                                     kvStore,
                                                                                                                                     router,
                                                                                                                                     stubTopologyManager(SELF),
                                                                                                                                     stubSchemaOrchestrator(),
                                                                                                                                     () -> Set.of(SELF, NODE_A),
                                                                                                                                     () -> Set.of(SELF, NODE_A),
                                                                                                                                     Set::of,
                                                                                                                                     Set.of(SELF, NODE_A),
                                                                                                                                     DeploymentAtomicity.ALL_OR_NOTHING,
                                                                                                                                     3,
                                                                                                                                     timeSpan(300).seconds(),
                                                                                                                                     System::currentTimeMillis).dormant();

        harness = FsmTestHarness.harness("schema-gate-" + System.nanoTime(), factory);
    }

    /// The gate matrix, read directly off `areSchemasReady`. `blueprints` is populated by hand so the
    /// slice's owner and its `schemaRequired` flag are stated explicitly per case rather than
    /// inferred from a restore path that always defaults `schemaRequired` to `true`.
    @Nested
    class GateMatrix {
        @Test
        void areSchemasReady_blocks_whenOwnBlueprintDatasourceFailed() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.FAILED, OWNER);

            assertThat(schemasReady()).as("a FAILED migration of the slice's OWN blueprint must hold activation")
                                      .isFalse();
        }

        @Test
        void areSchemasReady_allows_whenAnotherBlueprintDatasourceFailed() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OTHER_DATASOURCE, SchemaStatus.FAILED, OTHER_OWNER);

            assertThat(schemasReady()).as("another blueprint's FAILED migration must NOT hold this slice")
                                      .isTrue();
        }

        @Test
        void areSchemasReady_blocks_whenOwnBlueprintDatasourcePending() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.PENDING, OWNER);

            assertThat(schemasReady()).isFalse();
        }

        @Test
        void areSchemasReady_blocks_whenOwnBlueprintDatasourceMigrating() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.MIGRATING, OWNER);

            assertThat(schemasReady()).isFalse();
        }

        @Test
        void areSchemasReady_allows_whenAnotherBlueprintDatasourcePending() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OTHER_DATASOURCE, SchemaStatus.PENDING, OTHER_OWNER);

            assertThat(schemasReady()).as("another blueprint's in-flight migration must NOT hold this slice")
                                      .isTrue();
        }

        @Test
        void areSchemasReady_allows_whenOwnBlueprintDatasourceCompleted() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.COMPLETED, OWNER);

            assertThat(schemasReady()).isTrue();
        }

        @Test
        void areSchemasReady_blocks_whenOnlyOneOfSeveralOwnedDatasourcesFailed() {
            registerSlice(Option.some(OWNER), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.COMPLETED, OWNER);
            seedSchema("database.orders", SchemaStatus.FAILED, OWNER);

            assertThat(schemasReady()).as("any single blocking record of the owning blueprint holds the slice")
                                      .isFalse();
        }

        /// Ownership matches on `ArtifactBase`, so records written by `orders-app:1.0.0` still belong
        /// to `orders-app:2.0.0` — a version upgrade is the same owner advancing its own schema.
        @Test
        void areSchemasReady_blocks_whenOwningBlueprintVersionAdvancedPastTheRecord() {
            registerSlice(Option.some(OWNER_UPGRADED), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.FAILED, OWNER);

            assertThat(schemasReady()).isFalse();
        }
    }

    @Nested
    class ShortCircuits {
        @Test
        void areSchemasReady_allows_whenSchemaNotRequired() {
            registerSlice(Option.some(OWNER), false);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.FAILED, OWNER);

            assertThat(schemasReady()).as("schemaRequired=false short-circuits regardless of record status")
                                      .isTrue();
        }

        /// No `Blueprint` entry means no owner to match records against. Blocking would be an
        /// unclearable hold — nothing that ever completes could be attributed to this slice.
        @Test
        void areSchemasReady_allows_whenSliceHasNoBlueprintEntry() {
            seedSchema(OWNED_DATASOURCE, SchemaStatus.FAILED, OTHER_OWNER);

            assertThat(schemasReady()).isTrue();
        }

        @Test
        void areSchemasReady_allows_whenBlueprintCarriesNoOwner() {
            registerSlice(Option.none(), true);
            seedSchema(OWNED_DATASOURCE, SchemaStatus.FAILED, OWNER);

            assertThat(schemasReady()).isTrue();
        }
    }

    /// The gate is only worth anything if it reaches the ACTIVATE write. These drive the whole
    /// rebuild path — KV atoms in, recorded consensus commands out — so a gate that returns the
    /// right boolean but is no longer consulted still fails here.
    @Nested
    class ActivationWiring {
        @Test
        void activate_isWithheld_whenOwnBlueprintSchemaFailed() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.FAILED, OWNER);

            harness.dispatch(new Activate());

            assertThat(activateWrites()).as("a slice held by its own FAILED migration must never be issued ACTIVATE")
                                        .isEmpty();
        }

        @Test
        void activate_isIssued_whenAnotherBlueprintSchemaFailed() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OTHER_DATASOURCE, SchemaStatus.FAILED, OTHER_OWNER);

            harness.dispatch(new Activate());

            assertThat(activateWrites()).as("another blueprint's failure must not reach this slice's activation")
                                        .isNotEmpty();
        }

        @Test
        void activate_isIssued_whenOwnBlueprintSchemaCompleted() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.COMPLETED, OWNER);

            harness.dispatch(new Activate());

            assertThat(activateWrites()).as("a completed migration releases the slice")
                                        .isNotEmpty();
        }
    }

    /// Rebuild-time recovery of a migration that never started.
    ///
    /// `processSchemaVersionPut` is the ONLY caller of `handleSchemaPending`, so a PENDING Put that
    /// lands before this FSM reaches Active is lost, and no later Put is ever issued for a record that
    /// is already PENDING. Before the fix `recoverStalledSchemaMigrations` collected MIGRATING only,
    /// so nothing retried it: the record sat PENDING forever, `areSchemasReady` stayed false, and every
    /// slice of the owning blueprint was stranded in LOADED — a state with no timeout, reported above
    /// DEBUG only when FAILED. Observed live on 2026-08-30: schema status PENDING on a fully migrated
    /// database, zero ACTIVE slices, zero published routes, and `/api/v1/health` still reporting
    /// `healthy, ready, quorum`.
    @Nested
    class PendingMigrationRecovery {
        @Test
        void rebuild_reDispatchesToOrchestrator_whenRecordIsStillPending() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.PENDING, OWNER);

            harness.dispatch(new Activate());

            assertThat(migrateCalls).as("a PENDING record present at rebuild must be re-dispatched, or "
                                        + "nothing ever retries it and the blueprint is stranded forever")
                                    .contains(OWNED_DATASOURCE);
        }

        @Test
        void rebuild_doesNotDispatch_whenRecordIsCompleted() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.COMPLETED, OWNER);

            harness.dispatch(new Activate());

            assertThat(migrateCalls).as("a completed migration must not be re-run at every rebuild")
                                    .isEmpty();
        }

        /// Reconcile-time re-dispatch was tried and REVERTED (2026-08-31). Sweeping PENDING records on
        /// every reconcile re-dispatched migrations that were already running — three dispatches inside
        /// two seconds — and `acquireLock` is check-then-act, not atomic across nodes, so a second
        /// runner reached `aether_schema_history` and failed on `23505 duplicate key`. That marked the
        /// datasource FAILED and held every slice in the blueprint: the outage the sweep was meant to
        /// prevent, caused by the sweep.
        ///
        /// The gap is still real — a PENDING Put lost AFTER activation is unreachable by the
        /// rebuild-time sweep — but closing it needs a stalled-record test (record age, or an atomic
        /// compare-and-set on the lock) rather than firing on every pass. No test is pinned here
        /// deliberately: a test for behaviour that was deliberately removed would fail, and one written
        /// against the intended future design would pass without any code implementing it.
        @Test
        void rebuild_stillResetsStalledMigrating_soTheExistingRecoveryIsNotRegressed() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.MIGRATING, OWNER);

            harness.dispatch(new Activate());

            assertThat(schemaResetWrites()).as("MIGRATING with an expired lock must still be reset to PENDING")
                                           .isNotEmpty();
        }

    }

    /// #760: the hold was previously reported at DEBUG only — "Slice {} waiting for schema
    /// migrations to complete", naming neither the blocking datasource nor its status. An operator
    /// watching an ordinary log level saw nothing distinguishing a stuck slice from routine startup.
    /// Raised to WARN and widened to name every blocking record, so the hold is visible without
    /// reaching for DEBUG. This drives the real gate through the FSM harness (not a wire-check
    /// against a hand-written message) — the CAS-loss test above documents why a wire-check is
    /// sometimes the right tradeoff; here the existing `ActivationWiring` harness already reaches
    /// the log statement at no extra setup cost, so there is no such tradeoff to make.
    @Nested
    class HoldVisibility {
        private CapturingAppender appender;
        private LoggerConfig loggerConfig;
        private Level originalLevel;

        @BeforeEach
        void captureActiveLogger() {
            appender = CapturingAppender.create("SchemaHoldCapture");
            appender.start();
            var ctx = (LoggerContext) LogManager.getContext(false);
            var configuration = ctx.getConfiguration();
            loggerConfig = getOrCreateLoggerConfig(configuration);
            originalLevel = loggerConfig.getLevel();
            loggerConfig.addAppender(appender, Level.WARN, null);
            loggerConfig.setLevel(Level.WARN);
            ctx.updateLoggers();
        }

        @AfterEach
        void releaseActiveLogger() {
            var ctx = (LoggerContext) LogManager.getContext(false);
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.setLevel(originalLevel);
            ctx.updateLoggers();
            appender.stop();
        }

        @Test
        void activate_logsAtWarn_namingBlockingDatasourceAndStatus_whenSliceIsHeld() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.PENDING, OWNER);

            harness.dispatch(new Activate());

            assertThat(appender.capturedWarns()).as("a held slice must be reported at WARN, naming the blocking datasource and its status")
                                                .anyMatch(msg -> msg.contains("held in LOADED")
                                                                 && msg.contains(OWNED_DATASOURCE)
                                                                 && msg.contains("PENDING"));
        }

        @Test
        void activate_doesNotWarn_whenNoRecordBlocks() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.COMPLETED, OWNER);

            harness.dispatch(new Activate());

            assertThat(appender.capturedWarns()).as("a released slice must not be reported as held")
                                                .noneMatch(msg -> msg.contains("held in LOADED"));
        }

        /// #760 follow-up: the hold WARN is event-driven, not tick-driven — it fires from
        /// [`ClusterDeploymentState.Active#tryActivateIfDependenciesReady`], reached from the
        /// slice's own LOAD, from ANY schema record completing, from a sibling dependency
        /// activating, and once per blueprint at leader rebuild. A long hold can therefore be
        /// re-observed many times with nothing about THIS slice's hold having changed. This test
        /// drives a second, independent `NodeArtifactPutReceived` notification for the SAME slice
        /// — the exact event production dispatches on every KVStore put
        /// (`ClusterDeploymentManager.onNodeArtifactPut`) — while the blocking schema record stays
        /// PENDING on the same datasource, and pins that the operator sees exactly one WARN, not
        /// one per tick.
        @Test
        void activate_warnsOnce_acrossTwoEvaluationTicksAgainstAnUnchangedHold() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.PENDING, OWNER);

            harness.dispatch(new Activate());

            var artifactKey = NodeArtifactKey.nodeArtifactKey(NODE_A, SLICE);
            var value = NodeArtifactValue.nodeArtifactValue(SliceState.LOADED, System.currentTimeMillis());
            var put = new KVCommand.Put<NodeArtifactKey, NodeArtifactValue>(artifactKey, value);
            var secondTick = new ValuePut<>(put, Option.some(value));

            harness.dispatch(new NodeArtifactPutReceived(secondTick));

            var heldWarns = appender.capturedWarns()
                                    .stream()
                                    .filter(msg -> msg.contains("held in LOADED"))
                                    .toList();

            assertThat(heldWarns).as("a second evaluation tick against the SAME unchanged hold must not repeat the WARN")
                                 .hasSize(1);
        }

        /// #760 review TEST GAP 3: pins the dedup signature as a stable function of the blocking
        /// SET rather than of `ConcurrentHashMap` iteration order — see
        /// [ClusterDeploymentState.Active#reportSchemaHold(SliceNodeKey, Artifact, List)]'s
        /// `Comparator.comparing(SchemaVersionValue::datasourceName)` sort. Two datasources block
        /// the SAME slice simultaneously, and the second evaluation tick (the same
        /// `NodeArtifactPutReceived` re-dispatch the single-datasource `warnsOnce` test above uses)
        /// writes an unrelated `NodeArtifactKey` entry into the same store, which is exactly the
        /// kind of intervening mutation the unsorted join could have let flip the signature between
        /// otherwise-equivalent observations. An unchanged two-datasource hold must still WARN
        /// exactly once, not once per datasource and not once per tick.
        @Test
        void activate_warnsOnce_whenTwoDatasourcesBlockTheSameSliceAcrossTwoEvaluationTicks() {
            seedSliceTarget(Option.some(OWNER));
            seedLoadedSlice();
            seedSchema(OWNED_DATASOURCE, SchemaStatus.PENDING, OWNER);
            seedSchema(OTHER_DATASOURCE, SchemaStatus.PENDING, OWNER);

            harness.dispatch(new Activate());

            var artifactKey = NodeArtifactKey.nodeArtifactKey(NODE_A, SLICE);
            var value = NodeArtifactValue.nodeArtifactValue(SliceState.LOADED, System.currentTimeMillis());
            var put = new KVCommand.Put<NodeArtifactKey, NodeArtifactValue>(artifactKey, value);
            var secondTick = new ValuePut<>(put, Option.some(value));

            harness.dispatch(new NodeArtifactPutReceived(secondTick));

            var heldWarns = appender.capturedWarns()
                                    .stream()
                                    .filter(msg -> msg.contains("held in LOADED"))
                                    .toList();

            assertThat(heldWarns).as("an unchanged two-datasource hold observed across two ticks must WARN exactly once, naming both datasources")
                                 .hasSize(1);
            assertThat(heldWarns.getFirst()).contains(OWNED_DATASOURCE)
                                            .contains(OTHER_DATASOURCE);
        }

        /// #760/#724 review round 2 item d: pins
        /// [ClusterDeploymentState.Active#schemaHoldSignature(List)] directly rather than through the
        /// WARN-dedup side effect above — that test can only ever observe ONE `ConcurrentHashMap`
        /// iteration order per run, so it cannot by itself prove the join is order-independent. Feed
        /// the exact same two records in both orders and require byte-identical signatures.
        @Test
        void schemaHoldSignature_sameRecordsInEitherOrder_producesIdenticalSignature() {
            var first = SchemaVersionValue.schemaVersionValue(OWNED_DATASOURCE,
                                                              1,
                                                              "V001__init.sql",
                                                              SchemaStatus.PENDING,
                                                              COORDS,
                                                              OWNER);
            var second = SchemaVersionValue.schemaVersionValue(OTHER_DATASOURCE,
                                                               1,
                                                               "V001__init.sql",
                                                               SchemaStatus.PENDING,
                                                               COORDS,
                                                               OWNER);

            var forward = ClusterDeploymentState.Active.schemaHoldSignature(List.of(first, second));
            var reversed = ClusterDeploymentState.Active.schemaHoldSignature(List.of(second, first));

            assertThat(forward).isEqualTo(reversed);
        }

        private LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
            var existing = configuration.getLoggerConfig(ACTIVE_LOGGER_NAME);
            if (ACTIVE_LOGGER_NAME.equals(existing.getName())) {return existing;}
            var fresh = new LoggerConfig(ACTIVE_LOGGER_NAME, Level.WARN, false);
            configuration.addLogger(ACTIVE_LOGGER_NAME, fresh);
            return fresh;
        }
    }

    // --- helpers ---

    private ClusterDeploymentState.Active activeState() {
        if (harness.state() instanceof ClusterDeploymentState.Dormant) {
            harness.dispatch(new Activate());
        }

        return (ClusterDeploymentState.Active) harness.state();
    }

    private boolean schemasReady() {
        return activeState().areSchemasReady(SLICE_KEY);
    }

    private void registerSlice(Option<BlueprintId> owner, boolean schemaRequired) {
        activeState().blueprints()
                     .put(SLICE, Blueprint.blueprint(SLICE, 1, 1, owner, schemaRequired));
    }

    private void seedSchema(String datasource, SchemaStatus status, BlueprintId owner) {
        kvStore.put(SchemaVersionKey.schemaVersionKey(datasource),
                    SchemaVersionValue.schemaVersionValue(datasource,
                                                          1,
                                                          "V001__init.sql",
                                                          status,
                                                          COORDS,
                                                          owner));
    }

    private void seedSliceTarget(Option<BlueprintId> owner) {
        kvStore.put(SliceTargetKey.sliceTargetKey(SLICE_BASE),
                    SliceTargetValue.sliceTargetValue(SLICE_VERSION, 1, owner));
    }

    private void seedLoadedSlice() {
        kvStore.put(NodeArtifactKey.nodeArtifactKey(NODE_A, SLICE),
                    NodeArtifactValue.nodeArtifactValue(SliceState.LOADED, System.currentTimeMillis()));
    }

    private List<KVCommand<AetherKey>> activateWrites() {
        var artifactKey = NodeArtifactKey.nodeArtifactKey(NODE_A, SLICE);

        synchronized (cluster.commands) {
            return cluster.commands.stream()
                                   .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                      && put.key().equals(artifactKey)
                                                      && put.value() instanceof NodeArtifactValue value
                                                      && value.state() == SliceState.ACTIVATE)
                                   .toList();
        }
    }

    /// Reset-to-PENDING writes land on the cluster, not on `kvStore` — `submitStalledMigrationReset`
    /// goes through `cluster.apply`, which `RecordingClusterNode` records without applying. Reading
    /// `kvStore` here would report the seeded value unchanged and pass or fail for the wrong reason.
    private List<KVCommand<AetherKey>> schemaResetWrites() {
        var versionKey = SchemaVersionKey.schemaVersionKey(OWNED_DATASOURCE);

        synchronized (cluster.commands) {
            return cluster.commands.stream()
                                   .filter(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                                      && put.key().equals(versionKey)
                                                      && put.value() instanceof SchemaVersionValue value
                                                      && value.status() == SchemaStatus.PENDING)
                                   .toList();
        }
    }

    // --- test fixtures ---

    private SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                migrateCalls.add(datasourceName);

                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    /// In-memory log4j2 appender capturing WARN-and-above messages for assertions. Mirrors
    /// `ClusterTopologyManagerCasLossLoggingTest.CapturingAppender` — kept self-contained here
    /// rather than shared, matching that precedent's own per-file scope.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            var layout = PatternLayout.createDefaultLayout();
            return new CapturingAppender(name, layout);
        }

        @Override public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> capturedWarns() {
            return messages.stream().collect(Collectors.toUnmodifiableList());
        }
    }
}
