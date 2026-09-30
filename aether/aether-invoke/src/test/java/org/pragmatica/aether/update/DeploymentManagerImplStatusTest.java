// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// `GET /deploy/{id}` answered 404 for a `ROLLED_BACK` (or `COMPLETED`) deployment after any STRATEGIES
/// reassignment: the terminal `DeploymentKey` stays committed in the KV, but `activate()` skips terminal
/// states when it rebuilds the map and `status` read only the map. `status` now falls back to the
/// committed record (read-only), while every other reader keeps filtering `isActive`.
///
/// The fixture's consensus stub COMMITS what the manager applies into the same `KVStore` the manager reads,
/// so the rollback/complete write is the record the post-`activate()` fallback finds. Starting from a
/// seeded `DEPLOYED` record instead of `start(...)` avoids fabricating a blueprint; `start` only adds the
/// `DEPLOYED` record and the manager's own transition writes the terminal one under test.
class DeploymentManagerImplStatusTest {
    static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:2.0.0").unwrap().base();
    static final Version V1 = Version.version("1.0.0").unwrap();
    static final Version V2 = Version.version("2.0.0").unwrap();
    static final String DEPLOYMENT_ID = "deployment-1";

    private KVStore<AetherKey, AetherValue> kvStore;
    private DeploymentManager manager;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                          stubSerializer(),
                                                          stubDeserializer());

        commit(kvStore, List.of(new KVCommand.Put<AetherKey, AetherValue>(DeploymentKey.deploymentKey(DEPLOYMENT_ID),
                                                                          deployedDeploymentValue())));
        manager = DeploymentManager.deploymentManager(committingNode(kvStore), kvStore);
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Nested
    class TerminalDeploymentSurvivesReassignment {
        @Test
        void status_isPresentWithRolledBack_afterRollbackAndLeaderBlip() {
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

            blip();

            assertThat(manager.status(DEPLOYMENT_ID).isPresent()).as("a rolled-back deployment must stay addressable after activate()").isTrue();
            assertThat(manager.status(DEPLOYMENT_ID).unwrap().state()).isEqualTo(DeploymentState.ROLLED_BACK);
        }

        @Test
        void status_isPresentWithCompleted_afterCompleteAndLeaderBlip() {
            manager.complete(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

            blip();

            assertThat(manager.status(DEPLOYMENT_ID).isPresent()).as("a completed deployment must stay addressable after activate()").isTrue();
            assertThat(manager.status(DEPLOYMENT_ID).unwrap().state()).isEqualTo(DeploymentState.COMPLETED);
        }

        @Test
        void status_isPresent_onPassiveManager_whenRecordIsCommitted() {
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));
            manager.deactivate().await().onFailure(cause -> Assertions.fail(cause.message()));

            assertThat(manager.status(DEPLOYMENT_ID).unwrap().state()).isEqualTo(DeploymentState.ROLLED_BACK);
        }
    }

    /// Controls: the fallback is a read-only lookup by id, not a resurrection of terminal entries.
    @Nested
    class OtherReadersStayActiveOnly {
        @Test
        void list_excludesTheTerminalDeployment_afterLeaderBlip() {
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

            blip();

            assertThat(manager.list()).as("list() must keep filtering terminal deployments").isEmpty();
        }

        @Test
        void status_isAbsent_forAnIdNeverCommitted() {
            blip();

            assertThat(manager.status("no-such-deployment").isEmpty()).as("the fallback must not fabricate a deployment").isTrue();
        }

        /// A corrupt committed enum name must read as absent (404 at the route), never throw out of
        /// `status`, and must not abort `activate()` for the records around it.
        @Test
        void status_isAbsentAndDoesNotThrow_forACorruptStoredState() {
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));
            commit(kvStore, List.of(new KVCommand.Put<AetherKey, AetherValue>(DeploymentKey.deploymentKey("corrupt-1"),
                                                                              corruptDeploymentValue())));

            blip();

            assertThat(manager.status("corrupt-1").isEmpty()).as("a corrupt record is absent, not an exception").isTrue();
            assertThat(manager.status(DEPLOYMENT_ID).isPresent()).as("its neighbour survives the restore").isTrue();
        }

        @Test
        void list_keepsTheNonTerminalDeployment_afterLeaderBlip() {
            blip();

            assertThat(manager.list()).extracting(Deployment::deploymentId).containsExactly(DEPLOYMENT_ID);
            assertThat(manager.status(DEPLOYMENT_ID).unwrap().state()).isEqualTo(DeploymentState.DEPLOYED);
        }
    }

    private void blip() {
        manager.deactivate().await().onFailure(cause -> Assertions.fail(cause.message()));
        manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    private static DeploymentValue deployedDeploymentValue() {
        var now = System.currentTimeMillis();

        return DeploymentValue.deploymentValue(DEPLOYMENT_ID,
                                               "org.test:my-slice:2.0.0",
                                               V1.bareVersion(),
                                               V2.bareVersion(),
                                               DeploymentStrategy.BLUE_GREEN.name(),
                                               DeploymentState.DEPLOYED.name(),
                                               VersionRouting.ALL_OLD.toString(),
                                               "",
                                               "",
                                               CleanupPolicy.GRACE_PERIOD.name(),
                                               BASE.asString(),
                                               3,
                                               now,
                                               now);
    }

    private static DeploymentValue corruptDeploymentValue() {
        var now = System.currentTimeMillis();

        return DeploymentValue.deploymentValue("corrupt-1",
                                               "org.test:my-slice:2.0.0",
                                               V1.bareVersion(),
                                               V2.bareVersion(),
                                               DeploymentStrategy.BLUE_GREEN.name(),
                                               "NO_SUCH_STATE",
                                               VersionRouting.ALL_OLD.toString(),
                                               "",
                                               "",
                                               CleanupPolicy.GRACE_PERIOD.name(),
                                               BASE.asString(),
                                               3,
                                               now,
                                               now);
    }

    static void commit(KVStore<AetherKey, AetherValue> kvStore, List<KVCommand<AetherKey>> commands) {
        kvStore.process(kvStore.createBatch(commands));
    }

    /// Only `apply` is reached by the manager; it commits into the shared store and acknowledges.
    @SuppressWarnings("unchecked")
    static RabiaNode<KVCommand<AetherKey>> committingNode(KVStore<AetherKey, AetherValue> kvStore) {
        return (RabiaNode<KVCommand<AetherKey>>) Proxy.newProxyInstance(RabiaNode.class.getClassLoader(),
                                                                         new Class[]{RabiaNode.class},
                                                                         (_, method, args) -> applyCommitting(kvStore,
                                                                                                              method.getName(),
                                                                                                              args));
    }

    @SuppressWarnings("unchecked")
    private static Object applyCommitting(KVStore<AetherKey, AetherValue> kvStore, String methodName, Object[] args) {
        if (!"apply".equals(methodName)) {
            throw new UnsupportedOperationException("Not touched by DeploymentManagerImpl: " + methodName);
        }

        var commands = (List<KVCommand<AetherKey>>) args[0];

        commit(kvStore, commands);

        return Promise.success(commands.stream().map(_ -> Unit.unit()).toList());
    }

    static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
