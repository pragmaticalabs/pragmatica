// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.StreamStorage;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
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

/// #3458 M-1 (T-M2, write-back arm): a stream config whose declared storage is `UNKNOWN` was decoded from a newer build's value
/// and is never written back. The one production writer of a `StreamConfigValue` is `StreamPartitionManager.publishStreamConfig`
/// (census in state-M-1.md), reached by every create path, so the refusal is pinned on each: the explicit create, the publish
/// auto-create (ASYNC commit), and the declared create. A known mode, and none, still publish (the control).
class StreamStorageWriteGuardTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();

    private static StreamConfig declaring(String name, Option<StreamStorage> storage) {
        return StreamConfig.streamConfig(name)
                           .withDeclaration(StreamDeclaration.streamDeclaration(none(), none(), none(), none(), storage));
    }

    private static StreamConfig belowReplicationMinimum(String name) {
        var defaults = StreamConfig.streamConfig(name);

        return StreamConfig.streamConfig(name,
                                         defaults.partitions(),
                                         defaults.retention(),
                                         defaults.autoOffsetReset(),
                                         defaults.maxEventSizeBytes(),
                                         defaults.consistencyMode(),
                                         2,
                                         3,
                                         defaults.compression(),
                                         defaults.encryptionKeyId())
                           .withDeclaration(StreamDeclaration.streamDeclaration(none(),
                                                                                none(),
                                                                                none(),
                                                                                none(),
                                                                                some(StreamStorage.UNKNOWN)));
    }

    @Test
    void unknownStorage_isRefusedAtTheWrite_onTheExplicitCreate_andNothingIsPublished() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            manager.createStream(declaring("orders", some(StreamStorage.UNKNOWN)))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.StreamStorageUnknown("orders")))
                   .onFailure(cause -> assertThat(cause.message()).contains("orders").contains("storage mode"));
            assertThat(node.applied()).as("nothing was written").isEmpty();
        } finally {
            manager.close();
        }
    }

    @Test
    void unknownStorage_isRefusedAtTheWrite_onThePublishAutoCreate() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            manager.ensureStreamMaterialized(declaring("orders", some(StreamStorage.UNKNOWN)))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.StreamStorageUnknown("orders")));
            assertThat(node.applied()).as("nothing was written").isEmpty();
        } finally {
            manager.close();
        }
    }

    @Test
    void unknownStorage_isRefusedAtTheWrite_onTheDeclaredCreate() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            manager.createDeclaredStream(declaring("orders", some(StreamStorage.UNKNOWN)))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.StreamStorageUnknown("orders")));
            assertThat(node.applied()).as("nothing was written").isEmpty();
        } finally {
            manager.close();
        }
    }

    /// F2 (v3586 fix round 1): the refusal is a PRE-materialisation check beside the replication minimum, so a refused `UNKNOWN`
    /// leaves no trace: the stream is absent and the floor reservation never moved. Mutation "drop checkStorageKnown from
    /// createFreshStream" (the publish-time backstop alone refuses) turns the absence/reservation asserts red.
    @Test
    void unknownStorage_refusedOnAFreshCreate_leavesNoStream_andNoFloorReservation() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            var availableBefore = manager.availableBytes();

            manager.createStream(declaring("orders", some(StreamStorage.UNKNOWN)))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.StreamStorageUnknown("orders")));
            assertThat(manager.streamExists("orders")).as("the refused stream never entered `streams`").isFalse();
            assertThat(manager.totalAllocatedBytes()).as("no floor bytes reserved").isZero();
            assertThat(manager.availableBytes()).as("the floor reservation is unchanged").isEqualTo(availableBefore);
        } finally {
            manager.close();
        }
    }

    /// a7 (v3586b): the check order is replication minimum FIRST, then storage. A config failing both reports the replication
    /// refusal. Mutation "swap checkReplicationMinimum and checkStorageKnown in createFreshStream" turns this red.
    @Test
    void aConfigFailingBothChecks_reportsTheReplicationMinimum_notTheUnknownStorage_replicationMinimumIsCheckedFirst() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            manager.createStream(belowReplicationMinimum("orders"))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicationRefused.class));
            assertThat(manager.streamExists("orders")).isFalse();
            assertThat(node.applied()).as("nothing was written").isEmpty();
        } finally {
            manager.close();
        }
    }

    /// The publish-time backstop: a RE-create of a materialized-but-uncommitted stream skips the fresh-create chain
    /// (`ensureConfigCommitted` -> `republishExistingConfig`), so an `UNKNOWN` config arriving there is refused at
    /// `publishStreamConfig`. Mutation "drop checkStorageKnown from publishStreamConfig" turns this red.
    @Test
    void unknownStorage_isRefusedOnTheRepublishOfAnUncommittedStream_byThePublishBackstop() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            node.failNextApply();
            manager.createStream(declaring("orders", some(StreamStorage.PERSISTENT)))
                   .onSuccess(_ -> fail("the first commit is made to fail"));
            assertThat(manager.streamExists("orders")).as("materialized, uncommitted").isTrue();
            assertThat(node.applied()).isEmpty();

            manager.createStream(declaring("orders", some(StreamStorage.UNKNOWN)))
                   .onSuccess(_ -> fail("must be refused"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.StreamStorageUnknown("orders")));
            assertThat(node.applied()).as("UNKNOWN was never written").isEmpty();
        } finally {
            manager.close();
        }
    }

    @Test
    void aKnownModeAndNone_stillPublish() {
        var node = new RecordingClusterNode();
        var manager = streamPartitionManager(Long.MAX_VALUE, node);

        try {
            manager.createStream(declaring("persistent", some(StreamStorage.PERSISTENT)))
                   .onFailure(_ -> fail("PERSISTENT must publish"));
            manager.createStream(declaring("memory", some(StreamStorage.MEMORY)))
                   .onFailure(_ -> fail("MEMORY must publish"));
            manager.createStream(declaring("absent", none()))
                   .onFailure(_ -> fail("none must publish"));
            assertThat(node.applied()).as("one Put per accepted stream").hasSize(3);
        } finally {
            manager.close();
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final List<KVCommand<AetherKey>> applied = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean failNext = new AtomicBoolean();

        void failNextApply() {
            failNext.set(true);
        }

        List<KVCommand<AetherKey>> applied() {
            return applied;
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
