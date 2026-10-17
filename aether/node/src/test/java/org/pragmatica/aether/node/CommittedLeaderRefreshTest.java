// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CommittedLeaderRefreshTest {
    @Test
    void unchangedReplayStillRefreshesExactCommittedLeaderSequence() {
        var router = MessageRouter.mutable();
        var notifications = new AtomicInteger();
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> notifications.incrementAndGet());
        var store = new KVStore<StructuredKey, Object>(router, new Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf buffer, T value) {}
        }, mock(Deserializer.class));
        var leader = new NodeId("installed-leader");
        store.process(store.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(leader, 9)))));
        store.replayNotifications();
        var before = notifications.get();
        store.replayNotifications();
        assertThat(notifications.get()).isEqualTo(before);

        var manager = mock(LeaderManager.class);
        AetherNode.refreshCommittedLeader(store, manager);
        verify(manager).onLeaderCommitted(leader, 9);
        verifyNoMoreInteractions(manager);
    }

    @Test
    void absentCommittedLeaderDoesNotInventAuthority() {
        var store = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf buffer, T value) {}
        }, mock(Deserializer.class));
        var manager = mock(LeaderManager.class);
        AetherNode.refreshCommittedLeader(store, manager);
        verifyNoInteractions(manager);
    }
}
