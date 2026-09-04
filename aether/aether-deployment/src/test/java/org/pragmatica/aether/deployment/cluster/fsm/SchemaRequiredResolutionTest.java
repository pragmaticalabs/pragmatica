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
import java.util.function.LongSupplier;
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
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.SliceTargetPutReceived;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
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

/// #555 — `schemaRequired` was silently reverted to `true` at three of the four call sites of
/// `ClusterDeploymentManager.Blueprint.blueprint(...)` in this class, because only the 4-arg
/// overload (which hardcodes `schemaRequired=true`) was used instead of resolving it from the
/// owning blueprint's `resources.toml` the way `handleAppBlueprintChange` already does.
///
/// Two of the three sites are leader-restore paths (`restoreAppBlueprint`, `restoreSliceTarget`,
/// both driven from `rebuildStateFromKVStore` on FSM activation). The third —
/// `handleSliceTargetChange` — is the live reactive path that fires on every `SliceTargetValue`
/// KV `Put` for an owned slice; it is not a restore path at all, so the flag reverted on the
/// first such event after deploy, not only on leader failover. That live-path case is the novel
/// finding — nothing pinned it before this test.
///
/// **Scope of what this fix actually resolves in production.** Manual scale
/// (`SliceRoutes.java`, `RollbackManager.java`) correctly preserves the owner via
/// `AetherValue.withInstances(...)` before persisting the `SliceTargetValue`, so
/// `handleSliceTargetChange` now resolves `schemaRequired` correctly for a manual scale event
/// `[verified: LiveReactivePath tests in this class]`. The in-process autoscaler
/// (`ControlLoopContext.applyScaling`, module `aether-control`) currently constructs a *fresh*
/// `SliceTargetValue` with `Option.none()` for the owner instead of `withInstances(...)`
/// `[mechanism: ControlLoopContext.applyScaling, aether-control, ~line 516]` — so a genuine
/// autoscale event still reaches `handleSliceTargetChange` with no owner and falls through to the
/// unowned-slice historical default (`true`). **`schemaRequired` still reverts to `true` on a real
/// autoscale event until that separate owner-erasure defect is fixed**; an identical pattern
/// exists in `AbTestManager.targetPreservingOverrides` (module `aether-invoke`). Both are outside
/// this class's module and out of scope for #555; tracked as a follow-up.
///
/// Unowned slices (no owning blueprint) are deliberately left at the historical default (`true`)
/// at all three sites — every site hardcoded `true` before this fix, so an unowned slice keeps
/// exactly its current behavior. Only blueprint-owned slices change, by gaining the same
/// blueprint-consulting resolution `handleAppBlueprintChange` already performs.
class SchemaRequiredResolutionTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final String ACTIVE_LOGGER_NAME = "org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState$Active";

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    private void newHarness() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        LongSupplier clock = () -> 10_000_000L;

        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory =
                fsm -> new ClusterDeploymentContext(fsm,
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
                                                    clock).dormant();
        harness = FsmTestHarness.harness("schema-required-test-" + System.nanoTime(), factory);
    }

    private ClusterDeploymentState.Active activeState() {
        return (ClusterDeploymentState.Active) harness.state();
    }

    private void assertSchemaRequired(Artifact artifact, boolean expected) {
        var blueprint = activeState().blueprints().get(artifact);

        assertThat(blueprint).as("expected a blueprint entry for %s", artifact).isNotNull();
        assertThat(blueprint.schemaRequired()).isEqualTo(expected);
    }

    private static BlueprintId ownerId(String name) {
        return BlueprintId.blueprintId("org.example:" + name + ":1.0.0").unwrap();
    }

    private static Artifact artifact(String name) {
        return Artifact.artifact("org.example:" + name + ":1.0.0").unwrap();
    }

    /// A minimal but complete blueprint document. `id` is required for `BlueprintParser.parse` to
    /// succeed at all; a non-empty `[[slices]]` is required because `Blueprint.blueprint(...)`
    /// rejects an empty slice list (`EMPTY_SLICES`), which would otherwise make `parse(...).option()`
    /// collapse to `none()` regardless of `schema_required`; and `[deployment].strategy` is required
    /// for `schema_required` to be read at all (an absent `strategy` short-circuits
    /// `parseDeploymentConfig` to `none()` before `schema_required` is ever consulted).
    private static String resourcesToml(BlueprintId owner, boolean schemaRequired) {
        return """
               id = "%s"

               [[slices]]
               artifact = "org.example:seed-slice:1.0.0"

               [deployment]
               strategy = "rolling"
               schema_required = %s
               """.formatted(owner.asString(), schemaRequired);
    }

    private void seedOwningBlueprint(BlueprintId owner, boolean schemaRequired) {
        // Empty loadOrder: this entry exists only so resolveSchemaRequired(owner) has a
        // resources.toml to read via a direct KV lookup — it is not meant to drive
        // handleAppBlueprintChange (this harness never bridges KV Put notifications to FSM
        // events; see dispatchSliceTargetPut for why the live-path tests dispatch directly).
        var expanded = ExpandedBlueprint.expandedBlueprint(owner, List.of(), Option.some(resourcesToml(owner, schemaRequired)));
        kvStore.put(AppBlueprintKey.appBlueprintKey(owner), AppBlueprintValue.appBlueprintValue(expanded));
    }

    /// `ClusterDeploymentManager` normally bridges a KV store's `ValuePut` notification (published
    /// on the message router) into this exact FSM event — that bridge lives outside this class and
    /// is out of scope for #555, so the event is dispatched directly here to exercise
    /// `handleSliceTargetChange`, the method the fix and this test target. The KV store is still
    /// written first so the value is visible to any direct KV read the handler performs.
    private void dispatchSliceTargetPut(SliceTargetKey key, SliceTargetValue value) {
        kvStore.put(key, value);
        harness.dispatch(new SliceTargetPutReceived(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none())));
    }

    @Nested
    class LiveReactivePath {
        /// The novel finding from #555's scope-correction: `handleSliceTargetChange` fires on
        /// every owner-bearing `SliceTargetValue` Put, not only on leader restore. Before the fix,
        /// this reverted schemaRequired=false to true on the very next such event. "Owner-bearing"
        /// matters: these tests construct the `SliceTargetValue` the way manual scale
        /// (`SliceRoutes.java`, `RollbackManager.java`) actually produces it, via
        /// `AetherValue.withInstances(...)`. The in-process autoscaler does not currently produce
        /// an owner-bearing Put at all (see the class javadoc above) — that gap is a separate,
        /// out-of-scope defect, not something these tests claim to cover.
        @Test
        void handleSliceTargetChange_scaleEventOnOwnedSlice_preservesSchemaRequiredFalse() {
            newHarness();
            harness.dispatch(new Activate());

            var owner = ownerId("orders-app");
            seedOwningBlueprint(owner, false);

            var target = artifact("orders-api");
            dispatchSliceTargetPut(SliceTargetKey.sliceTargetKey(target.base()),
                                   SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.some(owner)));

            assertSchemaRequired(target, false);
        }

        @Test
        void handleSliceTargetChange_scaleEventOnOwnedSlice_resolvesSchemaRequiredTrue() {
            newHarness();
            harness.dispatch(new Activate());

            var owner = ownerId("billing-app");
            seedOwningBlueprint(owner, true);

            var target = artifact("billing-api");
            dispatchSliceTargetPut(SliceTargetKey.sliceTargetKey(target.base()),
                                   SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.some(owner)));

            assertSchemaRequired(target, true);
        }

        /// Behavior-preservation guarantee: an unowned slice (no owning blueprint) has nothing to
        /// resolve schemaRequired against, so it keeps the historical default of `true` — exactly
        /// what every call site did before this fix. This is not a new policy call; a real
        /// standalone-slice schema policy is out of scope for #555.
        @Test
        void handleSliceTargetChange_scaleEventOnUnownedSlice_keepsHistoricalDefaultTrue() {
            newHarness();
            harness.dispatch(new Activate());

            var target = artifact("standalone-slice");
            dispatchSliceTargetPut(SliceTargetKey.sliceTargetKey(target.base()),
                                   SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.none()));

            assertSchemaRequired(target, true);
        }
    }

    @Nested
    class LeaderRestorePath {
        /// `restoreAppBlueprint` restores from `rebuildStateFromKVStore`, called on Active onEntry.
        /// The blueprint's own resources.toml — the very entry being restored — is what
        /// `resolveSchemaRequired` re-reads, so restoring a schema_required=false blueprint must
        /// not upgrade it to true on leader failover.
        @Test
        void restoreAppBlueprint_leaderRestore_preservesSchemaRequiredFalse() {
            newHarness();

            var owner = ownerId("orders-app");
            var sliceArtifact = artifact("orders-api");
            var slice = ResolvedSlice.resolvedSlice(sliceArtifact, 3, 2, false, Set.of())
                                     .unwrap();
            var expanded = ExpandedBlueprint.expandedBlueprint(owner, List.of(slice), Option.some(resourcesToml(owner, false)));
            kvStore.put(AppBlueprintKey.appBlueprintKey(owner), AppBlueprintValue.appBlueprintValue(expanded));

            harness.dispatch(new Activate());

            assertSchemaRequired(sliceArtifact, false);
        }

        /// Same restore path, opposite polarity: a schema_required=true blueprint must resolve to
        /// `true` on restore, not fall back to `true` by coincidence of the historical default —
        /// distinguishing an actually-resolved `true` from the unresolved fallback requires this
        /// counterpart of the `false` case above.
        @Test
        void restoreAppBlueprint_leaderRestore_resolvesSchemaRequiredTrue() {
            newHarness();

            var owner = ownerId("billing-app");
            var sliceArtifact = artifact("billing-api");
            var slice = ResolvedSlice.resolvedSlice(sliceArtifact, 3, 2, false, Set.of())
                                     .unwrap();
            var expanded = ExpandedBlueprint.expandedBlueprint(owner, List.of(slice), Option.some(resourcesToml(owner, true)));
            kvStore.put(AppBlueprintKey.appBlueprintKey(owner), AppBlueprintValue.appBlueprintValue(expanded));

            harness.dispatch(new Activate());

            assertSchemaRequired(sliceArtifact, true);
        }

        /// `restoreSliceTarget` only has an `Option<BlueprintId>` owner (from the persisted
        /// `SliceTargetValue`), so it must resolve schemaRequired via that owner's own
        /// AppBlueprintValue — a second KV entry, seeded independently here.
        @Test
        void restoreSliceTarget_leaderRestore_resolvesSchemaRequiredFromOwningBlueprint() {
            newHarness();

            var owner = ownerId("orders-app");
            seedOwningBlueprint(owner, false);

            var target = artifact("orders-api");
            kvStore.put(SliceTargetKey.sliceTargetKey(target.base()),
                        SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.some(owner)));

            harness.dispatch(new Activate());

            assertSchemaRequired(target, false);
        }

        /// Same as above, opposite polarity: confirms `restoreSliceTarget` actually resolves
        /// `true` from the owning blueprint rather than merely landing on `true` via the
        /// unowned-slice historical-default fallback below.
        @Test
        void restoreSliceTarget_leaderRestore_resolvesSchemaRequiredTrue() {
            newHarness();

            var owner = ownerId("billing-app");
            seedOwningBlueprint(owner, true);

            var target = artifact("billing-api");
            kvStore.put(SliceTargetKey.sliceTargetKey(target.base()),
                        SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.some(owner)));

            harness.dispatch(new Activate());

            assertSchemaRequired(target, true);
        }

        /// Behavior-preservation guarantee on the restore path: an unowned slice target keeps the
        /// historical default of true, same as the live path.
        @Test
        void restoreSliceTarget_unownedSlice_keepsHistoricalDefaultTrue() {
            newHarness();

            var target = artifact("standalone-slice");
            kvStore.put(SliceTargetKey.sliceTargetKey(target.base()),
                        SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.none()));

            harness.dispatch(new Activate());

            assertSchemaRequired(target, true);
        }
    }

    /// #760 review TEST GAP 4: `resolveSchemaRequired` (`ClusterDeploymentState.java:1215-1236`) logs
    /// at two different levels depending on whether it actually resolved a value or fell through to
    /// the historical default — WARN when unresolved (missing blueprint entry, unparsable TOML, or
    /// no declared `deploymentConfig`), DEBUG when a declared `schema_required` was found. Neither
    /// branch had a log-level test before this class; every existing `SchemaRequiredResolutionTest`
    /// case above asserts only the resolved boolean, not which level reported how it got there. An
    /// operator relying on WARN visibility to notice a misconfigured blueprint would see nothing if
    /// this split silently inverted or collapsed to a single level.
    @Nested
    class LogLevelSplit {
        private CapturingAppender appender;
        private LoggerConfig loggerConfig;
        private Level originalLevel;

        @BeforeEach
        void captureActiveLogger() {
            appender = CapturingAppender.create("SchemaRequiredResolutionCapture");
            appender.start();
            var ctx = (LoggerContext) LogManager.getContext(false);
            var configuration = ctx.getConfiguration();
            loggerConfig = getOrCreateLoggerConfig(configuration);
            originalLevel = loggerConfig.getLevel();
            loggerConfig.addAppender(appender, Level.DEBUG, null);
            loggerConfig.setLevel(Level.DEBUG);
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
        void handleSliceTargetChange_logsAtDebug_whenSchemaRequiredResolvesFromDeclaredBlueprint() {
            newHarness();
            harness.dispatch(new Activate());

            var owner = ownerId("orders-app");
            seedOwningBlueprint(owner, false);

            var target = artifact("orders-api");
            dispatchSliceTargetPut(SliceTargetKey.sliceTargetKey(target.base()),
                                   SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.some(owner)));

            assertThat(appender.capturedAt(Level.DEBUG)).as("a resolved schemaRequired must be reported at DEBUG, naming the owning blueprint")
                                                         .anyMatch(msg -> msg.contains("schemaRequired resolved to")
                                                                          && msg.contains(owner.asString()));
            assertThat(appender.capturedAt(Level.WARN)).as("a successfully resolved schemaRequired must not also warn about being unresolved")
                                                        .noneMatch(msg -> msg.contains("schemaRequired unresolved"));
        }

        @Test
        void handleSliceTargetChange_logsAtWarn_whenSchemaRequiredDefaultsForMissingBlueprintEntry() {
            newHarness();
            harness.dispatch(new Activate());

            var owner = ownerId("untracked-app");
            var target = artifact("untracked-api");
            // Deliberately no seedOwningBlueprint(owner, ...) call: the SliceTargetValue names this
            // owner, but no AppBlueprintValue for it exists in the KV store, so resolveSchemaRequired's
            // direct KV read comes back empty and the WARN/default-true branch fires.
            dispatchSliceTargetPut(SliceTargetKey.sliceTargetKey(target.base()),
                                   SliceTargetValue.sliceTargetValue(target.version(), 3, 2, Option.some(owner)));

            assertThat(appender.capturedAt(Level.WARN)).as("an unresolvable schemaRequired must warn and name the defaulting blueprint")
                                                        .anyMatch(msg -> msg.contains("schemaRequired unresolved")
                                                                         && msg.contains(owner.asString()));
            assertThat(appender.capturedAt(Level.DEBUG)).as("a defaulted schemaRequired must not also log a resolved-DEBUG message")
                                                         .noneMatch(msg -> msg.contains("schemaRequired resolved to"));
            assertSchemaRequired(target, true);
        }

        private LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
            var existing = configuration.getLoggerConfig(ACTIVE_LOGGER_NAME);
            if (ACTIVE_LOGGER_NAME.equals(existing.getName())) {return existing;}
            var fresh = new LoggerConfig(ACTIVE_LOGGER_NAME, Level.DEBUG, false);
            configuration.addLogger(ACTIVE_LOGGER_NAME, fresh);
            return fresh;
        }
    }

    // --- test fixtures ---

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
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

    /// In-memory log4j2 appender capturing DEBUG-and-above messages, split back out by level.
    /// Mirrors `SchemaActivationGateTest.CapturingAppender` — kept self-contained here rather than
    /// shared, matching that precedent's own per-file scope — but that copy only captures WARN and
    /// above, which cannot see the DEBUG side of #760 review TEST GAP 4's resolved/defaulted split.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            var layout = PatternLayout.createDefaultLayout();
            return new CapturingAppender(name, layout);
        }

        @Override public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.DEBUG)) {
                events.add(event.toImmutable());
            }
        }

        List<String> capturedAt(Level level) {
            return events.stream()
                         .filter(event -> event.getLevel().equals(level))
                         .map(event -> event.getMessage().getFormattedMessage())
                         .collect(Collectors.toUnmodifiableList());
        }
    }
}
