// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A node that starts draining stops promoting and demoting its storage, not only when it later stops. The drain is a
/// real one: the leader's `ClusterSyncPing` naming this node reaches `DrainProcedure.initiate`, whose single drain edge
/// (`DrainAssembly.drainInitiatedEmitter`) is where the storage hook is bound -- the same route as
/// `ScheduledTaskDrainWiringBootTest`. Cycles stop counting afterwards: a tick that still ran a cycle would show.
class AetherNodePromotionDrainBootTest {
    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 180, unit = SECONDS)
    void aRealDrain_stopsPromotionAndDemotion_andNoMoreCyclesRun() {
        var router = MessageRouter.DelegateRouter.delegate();

        node = AetherNode.aetherNode(PromotionBootFixture.config(tempDir, false),
                                      router,
                                      NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                      new AtomicInteger()::incrementAndGet)
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));
        PromotionBootFixture.awaitActive(node);

        var artifacts = node.storageSetups().get(PromotionBootFixture.ARTIFACTS);

        assertThat(artifacts.promotionManager().isActive()).as("control: promotion runs before the drain").isTrue();
        PromotionBootFixture.await(() -> artifacts.promotionManager().stats().cycles() > 0,
                                   "control: the node's own tick is running promotion cycles");

        commitDrainAuthority();
        router.route(drainPing());

        PromotionBootFixture.await(() -> !artifacts.promotionManager().isActive() && !artifacts.demotionManager().isActive(),
                                   "the drain edge stopped promotion and demotion of artifacts");
        assertThat(node.storageSetups().get("content").promotionManager().isActive()).isFalse();
        assertThat(node.storageSetups().get("content").demotionManager().isActive()).isFalse();

        PromotionBootFixture.sleep(1_500);

        var settled = artifacts.promotionManager().stats().cycles();

        PromotionBootFixture.sleep(3_500);

        assertThat(artifacts.promotionManager().stats().cycles()).as("three more maintenance ticks ran no promotion cycle")
                                                                  .isEqualTo(settled);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void commitDrainAuthority() {
        var store = node.kvStore();
        KVCommand command = new KVCommand.Put<>(org.pragmatica.cluster.state.kvstore.LeaderKey.INSTANCE,
                                                new org.pragmatica.cluster.state.kvstore.LeaderValue(node.self(), 1));

        store.process(store.createBatch(List.of(command)));
    }

    private ClusterSyncPing drainPing() {
        return new ClusterSyncPing(node.self(), Map.of(), 1L, 0L, 0L, 0L, Set.of(), Set.of(node.self()), Map.of(), Set.of(), true, true);
    }
}
