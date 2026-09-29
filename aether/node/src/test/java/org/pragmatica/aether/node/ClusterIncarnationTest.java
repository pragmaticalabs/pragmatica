// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1529 part 1: the cluster incarnation is minted once at genesis, is never overwritten by a second
/// genesis, and a restore keeps the restored lineage while moving to the next incarnation — the
/// operations run against a real [KVStore] applier, whose version fence is what makes them safe.
class ClusterIncarnationTest {
    static final String INCARNATION_ID = "01K4ZT9Q6W3X8Y2B7C5D1INST0";
    private static final ClusterIncarnationKey KEY = ClusterIncarnationKey.clusterIncarnationKey();

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = emptyStore();
    }

    @Nested
    class Current {
        @Test
        void current_isZero_beforeGenesis() {
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(ClusterIncarnation.NONE)
                                                           .isZero();
        }

        @Test
        void current_isTheCommittedIncarnation() {
            apply(new KVCommand.Put<>(KEY, ClusterIncarnationValue.clusterIncarnationValue("lineage-a", 4, INCARNATION_ID)));

            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(4);
        }
    }

    @Nested
    class Genesis {
        @Test
        void genesisCommand_mintsIncarnationOne_withTheFreshLineage() {
            ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-new")
                              .onPresent(ClusterIncarnationTest.this::apply);

            // The one fresh-id supplier mints both the lineage and the incarnation id.
            assertThat(ClusterIncarnation.committed(kvStore)).isEqualTo(Option.some(ClusterIncarnationValue.genesis("lineage-new", "lineage-new")));
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(ClusterIncarnationValue.GENESIS);
        }

        @Test
        void genesisCommand_isAbsent_onceAnIncarnationExists() {
            apply(new KVCommand.Put<>(KEY, ClusterIncarnationValue.clusterIncarnationValue("lineage-restored", 7, INCARNATION_ID)));

            assertThat(ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-new")
                                         .isPresent()).isFalse();
        }

        /// Two leaders that both saw no incarnation both build a genesis. The fence lets the first land
        /// and drops the second, so the cluster never ends up with two lineages in sequence.
        @Test
        void racingGeneses_resolveFirstWins() {
            var first = ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-first")
                                          .unwrap();
            var second = ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-second")
                                           .unwrap();

            apply(first);
            apply(second);

            assertThat(ClusterIncarnation.committed(kvStore)
                                         .map(ClusterIncarnationValue::lineageId)).isEqualTo(Option.some("lineage-first"));
        }
    }

    @Nested
    class Restore {
        @Test
        void restoreCommands_keepTheRestoredLineage_atTheNextIncarnation() {
            var restored = ClusterIncarnationValue.clusterIncarnationValue("lineage-backup", 5, INCARNATION_ID);

            ClusterIncarnation.restoreCommands(restored, restored.incarnation(), INCARNATION_ID)
                              .forEach(ClusterIncarnationTest.this::apply);

            assertThat(ClusterIncarnation.committed(kvStore)).isEqualTo(Option.some(ClusterIncarnationValue.clusterIncarnationValue("lineage-backup",
                                                                                                                                     6, INCARNATION_ID)));
        }

        /// A cold-restarted cluster mints its own genesis before the restore runs. The restore must still
        /// land — the restored lineage replaces the fresh one, one incarnation past the backup.
        @Test
        void restoreCommands_landOverAFreshGenesis() {
            ClusterIncarnation.genesisCommand(kvStore, () -> "lineage-fresh")
                              .onPresent(ClusterIncarnationTest.this::apply);

            applyBatch(ClusterIncarnation.restoreCommands(ClusterIncarnationValue.clusterIncarnationValue("lineage-backup", 5, INCARNATION_ID),
                                                          5, INCARNATION_ID));

            assertThat(ClusterIncarnation.committed(kvStore)).isEqualTo(Option.some(ClusterIncarnationValue.clusterIncarnationValue("lineage-backup",
                                                                                                                                     6, INCARNATION_ID)));
        }

        /// Restoring an OLDER backup of the lineage must not move the incarnation backwards: the live
        /// cluster is at L@7, the operator restores L@3, and the result is L@8, not L@4.
        @Test
        void restoreOfAnOlderBackup_landsAboveTheHighestRecorded_notBelowIt() {
            apply(new KVCommand.Put<>(KEY, ClusterIncarnationValue.clusterIncarnationValue("L1", 7, INCARNATION_ID)));

            applyBatch(ClusterIncarnation.restoreCommands(ClusterIncarnationValue.clusterIncarnationValue("L1", 3, INCARNATION_ID), 7, INCARNATION_ID));

            assertThat(ClusterIncarnation.committed(kvStore)).isEqualTo(Option.some(ClusterIncarnationValue.clusterIncarnationValue("L1",
                                                                                                                                     8, INCARNATION_ID)));
        }

        /// The backup store holds L@5..7 and the operator restores L@5: committing L@6 would REUSE a number
        /// that already names another history. The floor lands it at L@8.
        @Test
        void restoreOfAMiddleBackup_neverReusesARecordedIncarnation() {
            applyBatch(ClusterIncarnation.restoreCommands(ClusterIncarnationValue.clusterIncarnationValue("L1", 5, INCARNATION_ID), 7, INCARNATION_ID));

            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(8);
        }

        /// The `restored` arm of the max: a floor BELOW the restored incarnation must not win
        /// (adopted from v1621's probe `OK_floorBelowRestored_landsAtRestoredPlusOne`).
        @Test
        void restoreWithAFloorBelowTheRestored_landsAtRestoredPlusOne() {
            applyBatch(ClusterIncarnation.restoreCommands(ClusterIncarnationValue.clusterIncarnationValue("L1", 5, INCARNATION_ID), 3, INCARNATION_ID));

            assertThat(ClusterIncarnation.committed(kvStore)).isEqualTo(Option.some(ClusterIncarnationValue.clusterIncarnationValue("L1",
                                                                                                                                     6, INCARNATION_ID)));
        }

        /// The fence the restore sidesteps with its Remove: a plain successor-skipping write is refused.
        @Test
        void aNonSuccessorWrite_isRefused() {
            apply(new KVCommand.Put<>(KEY, ClusterIncarnationValue.clusterIncarnationValue("lineage-a", 1, INCARNATION_ID)));
            apply(new KVCommand.Put<>(KEY, ClusterIncarnationValue.clusterIncarnationValue("lineage-b", 5, INCARNATION_ID)));

            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(1);
        }
    }

    @Nested
    class Registrar {
        private final List<Runnable> scheduled = new ArrayList<>();
        private final AtomicInteger applies = new AtomicInteger();

        @Test
        void leaderGain_mintsGenesis_andLatches() {
            var registrar = registrar(this::applyToStore);

            registrar.onLeaderChange(leaderChange(true));

            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(ClusterIncarnationValue.GENESIS);
            assertThat(registrar.isComplete()).isTrue();
        }

        @Test
        void follower_neverMints() {
            var registrar = registrar(this::applyToStore);

            registrar.onLeaderChange(leaderChange(false));

            assertThat(ClusterIncarnation.current(kvStore)).isZero();
            assertThat(applies).hasValue(0);
        }

        @Test
        void existingIncarnation_isLeftAlone() {
            apply(new KVCommand.Put<>(KEY, ClusterIncarnationValue.clusterIncarnationValue("lineage-restored", 7, INCARNATION_ID)));
            var registrar = registrar(this::applyToStore);

            registrar.onLeaderChange(leaderChange(true));

            assertThat(applies).hasValue(0);
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(7);
            assertThat(registrar.isComplete()).isTrue();
        }

        /// A commit that fails (not yet quorate) is retried on the next pass rather than given up.
        @Test
        void transientCommitFailure_isRetried() {
            var failFirst = new AtomicInteger(1);
            var registrar = registrar(commands -> failFirst.getAndDecrement() > 0
                                                  ? Causes.cause("not quorate").<List<Object>> promise()
                                                  : applyToStore(commands));

            registrar.onLeaderChange(leaderChange(true));

            assertThat(registrar.isComplete()).isFalse();
            assertThat(scheduled).hasSize(1);
            scheduled.removeFirst().run();
            assertThat(registrar.isComplete()).isTrue();
            assertThat(ClusterIncarnation.current(kvStore)).isEqualTo(ClusterIncarnationValue.GENESIS);
        }

        /// A commit that reports success but whose value is not there — a fenced write that lost a race
        /// is dropped silently — is not trusted: the pass fails and retries.
        @Test
        void appliedButNotCommitted_isNotTrustedAndRetries() {
            var registrar = registrar(_ -> Promise.success(List.of()));

            registrar.onLeaderChange(leaderChange(true));

            assertThat(registrar.isComplete()).isFalse();
            assertThat(scheduled).hasSize(1);
        }

        /// A leader that lost leadership mid-retry must not mint when the captured retry later fires
        /// (adopted from v1621's probe `OK_leadershipLostMidRetry`).
        @Test
        void leadershipLostMidRetry_retryFires_butDoesNotMint() {
            var registrar = registrar(commands -> countedFailure());

            registrar.onLeaderChange(leaderChange(true));
            assertThat(applies).hasValue(1);
            assertThat(scheduled).hasSize(1);
            registrar.onLeaderChange(leaderChange(false));
            scheduled.removeFirst().run();

            assertThat(applies).as("the retry fired after leadership was lost and must not mint").hasValue(1);
            assertThat(scheduled).isEmpty();
            assertThat(ClusterIncarnation.current(kvStore)).isZero();
        }

        private Promise<List<Object>> countedFailure() {
            applies.incrementAndGet();

            return Causes.cause("not quorate").promise();
        }

        private ClusterIncarnationRegistrar registrar(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
            return ClusterIncarnationRegistrar.clusterIncarnationRegistrar(ClusterIncarnationRegistrar.genesisLeg(() -> kvStore,
                                                                                                                  applier,
                                                                                                                  () -> "lineage-minted"),
                                                                           this::capture);
        }

        private Promise<List<Object>> applyToStore(List<KVCommand<AetherKey>> commands) {
            applies.incrementAndGet();
            applyBatch(commands);

            return Promise.success(List.of());
        }

        private ScheduledFuture<?> capture(Runnable runnable, TimeSpan delay) {
            scheduled.add(runnable);

            return new NeverFires();
        }
    }

    private void apply(KVCommand<AetherKey> command) {
        applyBatch(List.of(command));
    }

    private void applyBatch(List<KVCommand<AetherKey>> commands) {
        kvStore.process(kvStore.createBatch(commands));
    }

    private static LeaderNotification.LeaderChange leaderChange(boolean localIsLeader) {
        return LeaderNotification.leaderChange(Option.some(NodeId.nodeId("node-1")
                                                                 .unwrap()),
                                               localIsLeader);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }

    private static final class NeverFires extends CompletableFuture<Object> implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
