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
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.Blueprint;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
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

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private FsmTestHarness<ClusterDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        kvStore = new InMemoryKvStore(router);
        cluster = new RecordingClusterNode(SELF);
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory = fsm -> new ClusterDeploymentContext(fsm,
                                                                                                                                     SELF,
                                                                                                                                     cluster,
                                                                                                                                     kvStore,
                                                                                                                                     router,
                                                                                                                                     stubTopologyManager(SELF),
                                                                                                                                     stubSchemaOrchestrator(),
                                                                                                                                     HealthSignalSink.noop(),
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
}
