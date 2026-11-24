// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3390 F1 (ruling know e8888553b4): every stream config that is committed, by any path, passes the one commit validator a
/// fresh create runs (`StreamPartitionManager.validateCommit`, enforced at the commit choke point `publishStreamConfig`), and a
/// node that hydrates or adopts a committed config refuses one the ring invariant refuses BY NAME, never as a memory refusal.
/// One test per enumerated site (s55/fx3390a-sites.md): commit A1 fresh create, A2 re-publish of an uncommitted stream, A3 a
/// committed config arriving from outside this build (backup restore; a raw Put on the follower); node B1 hydrate, B2 a
/// committed life replacing a local losing one, B3 a create of a name whose committed life is applied, B4 same-life adoption.
class StreamConfigCommitValidatorTest {
    private static final long KIB = 1024L;
    private static final long MIB = 1024 * KIB;
    private static final long GIB = 1024 * MIB;
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();

    // ---- commit side ------------------------------------------------------------------------------------------------------

    /// A1: the fresh create refuses before materialisation, so a refused config leaves no entry and nothing committed.
    /// Mutation "drop validateCommit from createFreshStream" (the choke point alone refuses) turns the absence red.
    @Test
    void createStream_hotBelowMaxEvent_isRefusedBeforeMaterialisation_andNeverCommitted() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            manager.createStream(withHot(userStream("orders", 600_000L), some(100_000L), none()))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.HotWindowBelowMaxEvent("orders",
                                                                                                          "[hot] max_bytes",
                                                                                                          100_000L,
                                                                                                          600_000L)));
            assertThat(manager.streamExists("orders")).as("the refused stream never entered `streams`").isFalse();
            assertThat(manager.totalAllocatedBytes()).as("no floor bytes reserved").isZero();
            assertThat(committedConfigs(node)).as("nothing was committed").isEmpty();
        } finally {
            manager.close();
        }
    }

    /// A2: the re-publish of a materialized-but-uncommitted stream commits the INCOMING config, so the ring window is checked at
    /// the choke point. Mutation "publishStreamConfig runs only the replication and storage checks" turns this red.
    @Test
    void republish_hotBelowMaxEvent_isRefused_andNeverCommitted() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            materializeUncommitted(node, manager, userStream("orders", 600_000L));

            var second = manager.createStream(withHot(userStream("orders", 600_000L), some(100_000L), none()));

            assertThat(committedConfigs(node)).as("no committed config may carry [hot] max_bytes below max_event_size (second create answered %s)",
                                                  second)
                                              .isEmpty();
            second.onSuccess(_ -> fail("must be refused"))
                  .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.HotWindowBelowMaxEvent("orders",
                                                                                                         "[hot] max_bytes",
                                                                                                         100_000L,
                                                                                                         600_000L)));
        } finally {
            manager.close();
        }
    }

    @Test
    void republish_hotCountZero_isRefusedByName_andNeverCommitted() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            materializeUncommitted(node, manager, userStream("zero", MIB));

            manager.createStream(withHot(userStream("zero", MIB), none(), some(0L)))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.RetentionBoundInvalid("zero",
                                                                                                         "hot.max_count",
                                                                                                         0L)));
            assertThat(committedConfigs(node)).as("no committed config may carry hot.max_count 0").isEmpty();
        } finally {
            manager.close();
        }
    }

    /// Ruling e8888553b4 (2): within a life `max_event_size` is immutable. The re-publish keeps the entry's incarnation, so a
    /// changed `max_event_size` is refused there, naming the key. Mutation "drop checkLifeImmutables from validateCommit" turns
    /// this red.
    @Test
    void republish_changedMaxEventSize_sameLife_isRefusedByName_andNeverCommitted() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            materializeUncommitted(node, manager, userStream("life", MIB));
            var localLife = manager.enforcedConfig("life")
                                   .unwrap();

            manager.createStream(userStream("life", 256 * KIB))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.ImmutableKeyChanged("life",
                                                                                                       "max_event_size",
                                                                                                       String.valueOf(MIB),
                                                                                                       String.valueOf(256 * KIB))));
            assertThat(committedConfigs(node)).as("no config of the local life (incarnation %d) was committed",
                                                  localLife.incarnation())
                                              .isEmpty();
        } finally {
            manager.close();
        }
    }

    /// The other immutable keys the life's own config holds ([StreamPartitionManager#adopts] holds the same ones): a re-publish
    /// that changes `encryption_key_id` or `[truncation]` is refused naming that key. Mutation "drop the encryption_key_id (or
    /// [truncation]) clause of immutableChange" turns the matching assertion red.
    @Test
    void republish_changedEncryptionKeyOrTruncation_sameLife_isRefusedByName_andNeverCommitted() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            var life = userStream("keyed", MIB);

            materializeUncommitted(node, manager, life);

            assertThat(refusedKey(manager, withEncryptionKey(life, some("key-2")))).isEqualTo("encryption_key_id");
            assertThat(refusedKey(manager, withTruncation(life))).isEqualTo("[truncation]");
            assertThat(committedConfigs(node)).as("neither changed config was committed").isEmpty();
        } finally {
            manager.close();
        }
    }

    /// Positive control of the same-life rule: the life's own `max_event_size` re-publishes, and a NEW life (destroy, then create)
    /// may change it.
    @Test
    void newLife_mayChangeMaxEventSize_andTheLifesOwnValueRepublishes() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            materializeUncommitted(node, manager, userStream("life", MIB));
            var oldLife = manager.enforcedConfig("life")
                                 .unwrap();

            manager.createStream(userStream("life", MIB))
                   .onFailure(cause -> assertThat(cause).as("the life's own value re-publishes")
                                                        .isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
            assertThat(committedConfigs(node)).extracting(StreamConfig::maxEventSizeBytes)
                                              .containsExactly(MIB);

            manager.destroyStream("life")
                   .onFailure(cause -> fail("destroy: " + cause.message()));
            manager.createStream(userStream("life", 256 * KIB))
                   .onFailure(cause -> fail("a new life may change max_event_size: " + cause.message()));

            assertThat(committedConfigs(node)).filteredOn(config -> config.incarnation() != oldLife.incarnation())
                                              .extracting(StreamConfig::maxEventSizeBytes)
                                              .containsExactly(256 * KIB);
        } finally {
            manager.close();
        }
    }

    // ---- node side ----------------------------------------------------------------------------------------------------------

    /// A3 + B1: a committed config this build would never commit (a backup restore, an older build) reaches the follower as a
    /// raw Put. It is refused with no entry and no reservation, and B3 (a create of the name) answers the typed refusal, never
    /// a memory refusal or the transient `StreamConfigNotYetVisible`. Mutation "hydrateEntry skips the ring check" turns the
    /// absence red; "adoptCommittedLife skips the ring check" turns the answer red.
    @Test
    void follower_committedHotBelowMaxEvent_isRefusedByName_withNoEntry() {
        var committed = withHot(userStream("follower", 600_000L), some(100_000L), none()).withIncarnation(7L);
        var follower = streamPartitionManager(GIB);

        try {
            follower.onStreamConfigPut(put(committed));

            assertThat(follower.streamExists("follower")).as("no entry for a config the ring invariant refuses").isFalse();
            assertThat(follower.totalAllocatedBytes()).as("nothing reserved").isZero();
            follower.createStream(userStream("follower", 600_000L))
                    .onSuccess(_ -> fail("must be refused"))
                    .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.HotWindowBelowMaxEvent("follower",
                                                                                                           "[hot] max_bytes",
                                                                                                           100_000L,
                                                                                                           600_000L)));
        } finally {
            follower.close();
        }
    }

    @Test
    void follower_committedHotCountZero_isRefusedByName_notAsAMemoryRefusal() {
        var committed = withHot(userStream("zerof", MIB), none(), some(0L)).withIncarnation(7L);
        var follower = streamPartitionManager(GIB);

        try {
            follower.onStreamConfigPut(put(committed));

            assertThat(follower.streamExists("zerof")).isFalse();
            follower.createStream(userStream("zerof", MIB))
                    .onSuccess(_ -> fail("must be refused"))
                    .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.RetentionBoundInvalid("zerof",
                                                                                                          "hot.max_count",
                                                                                                          0L)));
        } finally {
            follower.close();
        }
    }

    /// B2: a committed life replacing this node's losing proposal is held to the same rule; the losing entry is released and no
    /// entry of the refused life is built.
    @Test
    void follower_committedLifeReplacingALosingOne_isRefusedByName_withNoEntry() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(GIB, node);

        try {
            materializeUncommitted(node, manager, userStream("lost", 600_000L));
            var local = manager.enforcedConfig("lost")
                               .unwrap();
            var committed = withHot(userStream("lost", 600_000L), some(100_000L), none()).withIncarnation(local.incarnation() + 1);

            manager.onStreamConfigPut(put(committed));

            assertThat(manager.streamExists("lost")).as("the refused committed life built no entry").isFalse();
            assertThat(manager.totalAllocatedBytes()).as("the losing life's floor was released").isZero();
        } finally {
            manager.close();
        }
    }

    /// B4: a same-life committed config that changes `[hot]` is a storage change [StreamPartitionManager] adopts onto the
    /// existing entry; one the ring invariant refuses is not adopted, and the enforced config stays. A valid `[hot]` change is
    /// adopted (the control). Mutation "adopts drops the ring check" turns this red.
    @Test
    void follower_sameLifeHotChangeBelowMaxEvent_isNotAdopted_aValidOneIs() {
        var enforced = userStream("adopt", 600_000L).withIncarnation(7L);
        var follower = streamPartitionManager(GIB);

        try {
            follower.onStreamConfigPut(put(enforced));
            follower.onStreamConfigPut(put(withHot(enforced, some(100_000L), none())));

            assertThat(follower.enforcedConfig("adopt")).as("the refused [hot] change is not adopted")
                                                        .isEqualTo(some(enforced));

            var valid = withHot(enforced, some(1_200_000L), none());

            follower.onStreamConfigPut(put(valid));

            assertThat(follower.enforcedConfig("adopt")).as("a valid [hot] change is adopted")
                                                        .isEqualTo(some(valid));
        } finally {
            follower.close();
        }
    }

    /// The consequence F1 had on every follower: after the refused re-publish, every config the owner commits is one a follower
    /// hydrates into a ring that holds a `max_event_size` event (ruling e8888553b4).
    @Test
    void everyCommittedConfig_hydratesOnAFollower_intoARingThatHoldsOneMaxEvent() {
        var node = new RecordingClusterNode();
        var owner = streamPartitionManager(GIB, node);
        var follower = streamPartitionManager(GIB);

        try {
            materializeUncommitted(node, owner, userStream("orders", 600_000L));
            owner.createStream(withHot(userStream("orders", 600_000L), some(100_000L), none()))
                 .onSuccess(_ -> fail("must be refused"));
            owner.createStream(userStream("orders", 600_000L))
                 .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));

            var committed = committedConfigs(node);

            assertThat(committed).as("the valid re-create committed").hasSize(1);
            committed.forEach(config -> follower.onStreamConfigPut(put(config)));

            var published = follower.publishLocalAtFloor("orders", 0, new byte[300_000], System.currentTimeMillis(), 0);

            assertThat(published.isSuccess()).as("a 300000-byte event within max_event_size 600000 on the follower: %s", published)
                                             .isTrue();
        } finally {
            follower.close();
            owner.close();
        }
    }

    // ---- fixture ------------------------------------------------------------------------------------------------------------

    private static void materializeUncommitted(RecordingClusterNode node, StreamPartitionManager manager, StreamConfig config) {
        node.failNextApply();
        manager.createStream(config)
               .onSuccess(_ -> fail("the first commit is made to fail"));
        assertThat(manager.streamExists(config.name())).as("materialized, uncommitted").isTrue();
        assertThat(node.applied()).isEmpty();
    }

    private static StreamConfig userStream(String name, long maxEventSize) {
        return StreamConfig.streamConfig(name, 1, RetentionPolicy.retentionPolicy(), "latest", maxEventSize);
    }

    private static StreamConfig withHot(StreamConfig config, Option<Long> bytes, Option<Long> count) {
        var hot = HotWindowDeclaration.hotWindowDeclaration(bytes, count);

        return config.withDeclaration(StreamDeclaration.streamDeclaration(none(), none(), some(hot), none()));
    }

    private static String refusedKey(StreamPartitionManager manager, StreamConfig config) {
        return manager.createStream(config)
                      .fold(cause -> cause instanceof StreamError.ImmutableKeyChanged changed
                                     ? changed.key()
                                     : cause.message(),
                            _ -> "created");
    }

    private static StreamConfig withEncryptionKey(StreamConfig config, Option<String> keyId) {
        return new StreamConfig(config.name(),
                                config.partitions(),
                                config.retention(),
                                config.autoOffsetReset(),
                                config.maxEventSizeBytes(),
                                config.consistencyMode(),
                                config.replicationFactor(),
                                config.confirmationFactor(),
                                config.compression(),
                                keyId,
                                config.incarnation(),
                                config.declaration());
    }

    private static StreamConfig withTruncation(StreamConfig config) {
        var truncation = TruncationDeclaration.truncationDeclaration(some(TruncationPolicy.UNTIL_CONSUMED), none());

        return config.withDeclaration(StreamDeclaration.streamDeclaration(none(), some(truncation), none(), none()));
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> put(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private static List<StreamConfig> committedConfigs(RecordingClusterNode node) {
        return node.applied()
                   .stream()
                   .filter(command -> command instanceof KVCommand.Put<?, ?> put && put.value() instanceof StreamConfigValue)
                   .map(command -> ((StreamConfigValue) ((KVCommand.Put<?, ?>) command).value()).config())
                   .toList();
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean failNext = new AtomicBoolean();

        void failNextApply() {
            failNext.set(true);
        }

        List<KVCommand<AetherKey>> applied() {
            return List.copyOf(applied);
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            if (failNext.getAndSet(false)) {
                return Causes.cause("commit down").promise();
            }

            applied.addAll(commands);
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
