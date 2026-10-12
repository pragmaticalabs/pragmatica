// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.RuntimeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.cluster.state.kvstore.VersionFenced;
import org.pragmatica.cluster.state.kvstore.WitnessedRemoval;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// Deployment state machine spec R-1.5.1 / R-1.5.2 / R-1.6.1 / R-1.6.2: the markers on the new values are enforced by the
/// REAL applier, not just declared. A [LeaderAuthorized] value cannot be written by a bare Put or removed without a
/// leader transaction; a [VersionFenced] value accepts only the immediate successor of the committed version; a
/// [WitnessedRemoval] value is removed only by a witness equal to it. `DeploymentApplyValue` carries no marker.
///
/// Every refusal is paired with an accepted control in the same run, so a refusal is the marker's and not a blanket
/// rejection of everything.
class DeploymentFsmKvMarkersTest {
    private static final NodeId NODE = NodeId.nodeId("core-7").unwrap();
    private static final NodeId OTHER_NODE = NodeId.nodeId("worker-9").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-slice:1.2.3").unwrap();
    private static final LeaderValue LEADER = new LeaderValue(NODE, 5L);
    private static final LeaderValue DEPOSED_LEADER = new LeaderValue(NODE, 4L);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);

    private KVStore<StructuredKey, Object> store;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
    }

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private boolean transaction(LeaderValue leader, StructuredKey key, Object expected, Object replacement) {
        var mutation = new KVCommand.Mutation<StructuredKey, Object>(key, Option.option(expected), Option.option(replacement));
        var command = new KVCommand.LeaderTransaction<StructuredKey, Object>(key, "tx", leader, List.of(), List.of(mutation));

        return ((KVCommand.TransactionResult) store.process(store.createBatch(List.of(command))).getFirst()).accepted();
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    private static SliceDesiredValue desired(SliceGoal goal, long counter) {
        return new SliceDesiredValue(goal, new Generation(5L, counter), 1_760_000_111_000L);
    }

    private static SliceObservedValue observed(SliceObservedState state, long version) {
        return new SliceObservedValue(state,
                                      version,
                                      new Generation(5L, 1L),
                                      PROCESS,
                                      Option.none(),
                                      false,
                                      version == 1L
                                      ? 0
                                      : 3,
                                      List.of("handle"),
                                      1_760_000_222_000L);
    }

    @Nested
    class Declarations {
        @Test
        void leaderAuthorizedValues_areExactlyDesiredJudgmentAndDeparture() {
            var judgment = new ArtifactJudgmentValue(false, 0, Map.of());
            var departure = new NodeDepartureValue(DepartureState.ADMITTED, 1L, PROCESS, Option.none(), 1L);

            assertThat(desired(SliceGoal.LOADED, 1L)).isInstanceOf(LeaderAuthorized.class);
            assertThat(judgment).isInstanceOf(LeaderAuthorized.class);
            assertThat(departure).isInstanceOf(LeaderAuthorized.class);
            assertThat(observed(SliceObservedState.LOADING, 1L)).isNotInstanceOf(LeaderAuthorized.class);
            assertThat(applyRecord()).isNotInstanceOf(LeaderAuthorized.class);
        }

        @Test
        void observedValue_isVersionFencedAndWitnessedRemoval_andFencesByItsVersion() {
            var value = observed(SliceObservedState.ACTIVE, 9L);

            assertThat(value).isInstanceOf(VersionFenced.class);
            assertThat(value).isInstanceOf(WitnessedRemoval.class);
            assertThat(value.fenceVersion()).isEqualTo(9L);
            assertThat(desired(SliceGoal.ACTIVE, 1L)).isNotInstanceOf(VersionFenced.class);
        }

        @Test
        void applyValue_carriesNoMarker() {
            assertThat(applyRecord()).isNotInstanceOf(LeaderAuthorized.class)
                               .isNotInstanceOf(VersionFenced.class)
                               .isNotInstanceOf(WitnessedRemoval.class);
        }

        @Test
        void everyNewKey_isARuntimeKey_neverBackedUp() {
            List<AetherKey> keys = List.of(SliceDesiredKey.sliceDesiredKey(NODE, ARTIFACT),
                                           SliceObservedKey.sliceObservedKey(NODE, ARTIFACT),
                                           DeploymentApplyKey.deploymentApplyKey(BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap()),
                                           ArtifactJudgmentKey.artifactJudgmentKey(ARTIFACT),
                                           NodeDepartureKey.nodeDepartureKey(NODE));

            assertThat(keys).hasSize(5);
            assertThat(keys).allMatch(RuntimeKey.class::isInstance);
            assertThat(keys).noneMatch(ClusterStateKey.class::isInstance);
        }
    }

    @Nested
    class LeaderAuthorizedValues {
        @Test
        void sliceDesired_enforcedByTheApplier() {
            assertLeaderOnly(SliceDesiredKey.sliceDesiredKey(NODE, ARTIFACT),
                             desired(SliceGoal.LOADED, 1L),
                             desired(SliceGoal.ACTIVE, 2L));
        }

        @Test
        void artifactJudgment_enforcedByTheApplier() {
            assertLeaderOnly(ArtifactJudgmentKey.artifactJudgmentKey(ARTIFACT),
                             new ArtifactJudgmentValue(false, 1, Map.of()),
                             new ArtifactJudgmentValue(true,
                                                       3,
                                                       Map.of(OTHER_NODE, new UnresponsiveEntry(new Generation(5L, 2L), SliceGoal.ACTIVE, 1L))));
        }

        @Test
        void nodeDeparture_enforcedByTheApplier() {
            assertLeaderOnly(NodeDepartureKey.nodeDepartureKey(NODE),
                             new NodeDepartureValue(DepartureState.DEPARTED, 1L, PROCESS, Option.some(DepartureCause.NODE_REMOVED), 10L),
                             new NodeDepartureValue(DepartureState.ADMITTED, 2L, PROCESS, Option.none(), 20L));
        }

        private void assertLeaderOnly(StructuredKey key, Object first, Object second) {
            apply(new KVCommand.Put<>(key, first));
            assertThat(stored(key)).as("a bare Put of a LeaderAuthorized value is dropped").isNull();

            assertThat(transaction(DEPOSED_LEADER, key, null, first)).as("a deposed leader's transaction").isFalse();
            assertThat(stored(key)).isNull();

            assertThat(transaction(LEADER, key, null, first)).as("the committed leader's transaction").isTrue();
            assertThat(stored(key)).isEqualTo(first);

            apply(new KVCommand.Put<>(key, second));
            assertThat(stored(key)).as("a bare Put over a committed value is dropped").isEqualTo(first);

            apply(new KVCommand.Remove<>(key));
            apply(new KVCommand.Remove<>(key, Option.some(first)));
            assertThat(stored(key)).as("a Remove, with or without a witness, is dropped").isEqualTo(first);

            assertThat(transaction(DEPOSED_LEADER, key, first, second)).isFalse();
            assertThat(transaction(LEADER, key, second, first)).as("an expected value that is not committed").isFalse();
            assertThat(stored(key)).isEqualTo(first);

            assertThat(transaction(LEADER, key, first, second)).as("the leader's refresh").isTrue();
            assertThat(stored(key)).isEqualTo(second);

            assertThat(transaction(LEADER, key, second, null)).as("the leader's removal").isTrue();
            assertThat(stored(key)).isNull();
        }
    }

    @Nested
    class ObservedValue {
        private final SliceObservedKey key = SliceObservedKey.sliceObservedKey(NODE, ARTIFACT);

        @Test
        void nodePut_isAcceptedWithoutALeader_andFollowsTheVersionChain() {
            apply(new KVCommand.Put<>(key, observed(SliceObservedState.LOADING, 1L)));
            assertThat(stored(key)).isEqualTo(observed(SliceObservedState.LOADING, 1L));

            apply(new KVCommand.Put<>(key, observed(SliceObservedState.LOADED, 2L)));
            assertThat(stored(key)).as("the immediate successor").isEqualTo(observed(SliceObservedState.LOADED, 2L));
        }

        @Test
        void putThatIsNotTheSuccessor_isRefused() {
            apply(new KVCommand.Put<>(key, observed(SliceObservedState.LOADING, 1L)));
            apply(new KVCommand.Put<>(key, observed(SliceObservedState.LOADED, 2L)));

            apply(new KVCommand.Put<>(key, observed(SliceObservedState.ACTIVATING, 2L)));
            assertThat(stored(key)).as("a late identical resubmission").isEqualTo(observed(SliceObservedState.LOADED, 2L));

            apply(new KVCommand.Put<>(key, observed(SliceObservedState.ACTIVE, 4L)));
            assertThat(stored(key)).as("a version jump").isEqualTo(observed(SliceObservedState.LOADED, 2L));

            apply(new KVCommand.Put<>(key, observed(SliceObservedState.LOADING, 1L)));
            assertThat(stored(key)).as("a regression").isEqualTo(observed(SliceObservedState.LOADED, 2L));

            apply(new KVCommand.Put<>(key, observed(SliceObservedState.ACTIVATING, 3L)));
            assertThat(stored(key)).as("control: the successor still lands").isEqualTo(observed(SliceObservedState.ACTIVATING, 3L));
        }

        @Test
        void remove_requiresAWitnessEqualToTheCommittedValue() {
            var first = observed(SliceObservedState.LOADING, 1L);
            var second = observed(SliceObservedState.LOADED, 2L);

            apply(new KVCommand.Put<>(key, first));
            apply(new KVCommand.Put<>(key, second));

            apply(new KVCommand.Remove<>(key));
            assertThat(stored(key)).as("a witnessless Remove").isEqualTo(second);

            apply(new KVCommand.Remove<>(key, Option.some(first)));
            assertThat(stored(key)).as("a superseded witness").isEqualTo(second);

            apply(new KVCommand.Remove<>(key, Option.some(second)));
            assertThat(stored(key)).as("control: the equal witness removes").isNull();
        }

        @Test
        void afterRemoval_theChainRestartsAtVersionOne() {
            var first = observed(SliceObservedState.LOADING, 1L);

            apply(new KVCommand.Put<>(key, first));
            apply(new KVCommand.Remove<>(key, Option.some(first)));
            assertThat(stored(key)).isNull();

            apply(new KVCommand.Put<>(key, first));
            assertThat(stored(key)).isEqualTo(first);
        }

        @Test
        void leaderTransaction_removesAtTheExactValueOnly() {
            var committed = observed(SliceObservedState.LOADED, 1L);

            apply(new KVCommand.Put<>(key, committed));

            assertThat(transaction(LEADER, key, observed(SliceObservedState.LOADED, 2L), null)).as("a value that is not committed").isFalse();
            assertThat(stored(key)).isEqualTo(committed);
            assertThat(transaction(DEPOSED_LEADER, key, committed, null)).isFalse();
            assertThat(transaction(LEADER, key, committed, null)).as("control: exact value, committed leader").isTrue();
            assertThat(stored(key)).isNull();
        }
    }

    @Nested
    class UnmarkedValue {
        @Test
        void applyRecord_isWrittenAndRemovedByBarePutAndRemove() {
            var key = DeploymentApplyKey.deploymentApplyKey(BlueprintId.blueprintId("org.example:orders-app:1.2.3").unwrap());

            apply(new KVCommand.Put<>(key, applyRecord()));
            assertThat(stored(key)).isEqualTo(applyRecord());

            apply(new KVCommand.Remove<>(key));
            assertThat(stored(key)).isNull();
        }
    }

    private static DeploymentApplyValue applyRecord() {
        return new DeploymentApplyValue("attempt-17", Option.none(), 1_760_000_333_000L);
    }
}
