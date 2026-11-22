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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        List<KVCommand<AetherKey>> applied() {
            return applied;
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException();}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @SuppressWarnings("unchecked")
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applied.addAll(commands);
            return (Promise<List<R>>) (Promise<?>) Promise.success(List.of());
        }
    }
}
