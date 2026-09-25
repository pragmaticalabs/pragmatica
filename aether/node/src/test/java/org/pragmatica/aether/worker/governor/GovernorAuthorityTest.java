// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorAuthorityTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId A = new NodeId("worker-a");
    private static final NodeId B = new NodeId("worker-b");
    private static final AetherKey.GovernorAnnouncementKey KEY = AetherKey.GovernorAnnouncementKey.forCommunity("c");
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicReference<List<NodeId>> members = new AtomicReference<>(List.of(A, B));
    private final AtomicReference<Runnable> beforeApply = new AtomicReference<>(() -> {});
    private final ClusterNode<KVCommand<AetherKey>> cluster = new ClusterNode<>() {
        @Override public NodeId self() { return CORE; }
        @Override public TopologyManager topologyManager() { return null; }
        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }
        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            beforeApply.getAndSet(() -> {}).run();
            return Promise.success(store.process(store.createBatch(commands)));
        }
    };
    private final GovernorAuthority authority = GovernorAuthority.governorAuthority(CORE, cluster, store,
        () -> true, _ -> members.get(), _ -> true, () -> Epoch.ZERO, HlcClock.hlcClock(CORE));

    @BeforeEach
    void seedCommunity() {
        store.process(store.createBatch(List.of(
            new KVCommand.Put<>(AetherKey.CommunityKey.communityKey("c"), AetherValue.CommunityValue.communityValue("", "WORKER", 2)),
            new KVCommand.Put<>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(A), AetherValue.ActivationDirectiveValue.worker("c", "")),
            new KVCommand.Put<>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(B), AetherValue.ActivationDirectiveValue.worker("c", "")))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void leader(NodeId node, long view) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(node, view)))));
    }

    private GovernorAuthorityMessage.Response request(NodeId worker, long expected) {
        return authority.handle(new GovernorAuthorityMessage.Request(worker, "c", 1, expected, "host:9000")).await().unwrap();
    }

    @Test
    void claim_refresh_replacement_continueCommittedTerm() {
        leader(CORE, 1);
        assertThat(request(B, 0).authority().isEmpty()).isTrue();
        var first = request(A, 0).authority().unwrap();
        assertThat(first.governorId()).isEqualTo(A);
        assertThat(first.communityTerm()).isEqualTo(1);
        assertThat(request(A, 1).authority().unwrap().communityTerm()).isEqualTo(1);
        members.set(List.of(B));
        assertThat(request(B, 1).authority().unwrap().communityTerm()).isEqualTo(2);
        assertThat(request(A, 1).authority().unwrap().governorId()).isEqualTo(B);
    }

    @Test
    void coreRecovery_replacesStillEligibleIncumbentWithMonotonicCommittedTerm() {
        leader(CORE, 1);
        var old = request(A, 0).authority().unwrap();
        var replacement = authority.reconcile("c", B, old.communityTerm(), "b:9").await().unwrap().unwrap();
        assertThat(replacement.governorId()).isEqualTo(B);
        assertThat(replacement.communityTerm()).isEqualTo(old.communityTerm() + 1);
        assertThat(authority.reconcile("c", A, old.communityTerm(), "a:9").await().unwrap().unwrap()).isEqualTo(replacement);
    }

    @Test
    void leadershipChangesBeforeApply_staleLeaderCannotGrant() {
        leader(CORE, 1);
        beforeApply.set(() -> leader(new NodeId("replacement-core"), 2));
        assertThat(request(A, 0).authority().isEmpty()).isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }

    @Test
    void rejectedRefresh_doesNotReturnExistingAuthorityAsAnAcceptedGrant() {
        leader(CORE, 1);
        var incumbent = request(A, 0).authority().unwrap();
        beforeApply.set(() -> leader(new NodeId("replacement-core"), 2));
        assertThat(request(A, incumbent.communityTerm()).authority().isEmpty()).isTrue();
        assertThat(store.getTyped(KEY, GovernorAnnouncementValue.class).unwrap()).isEqualTo(incumbent);
    }

    @Test
    void candidateReassignedBeforeApply_claimIsRejected() {
        leader(CORE, 1);
        beforeApply.set(() -> store.process(store.createBatch(List.of(
            new KVCommand.Put<>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(A), AetherValue.ActivationDirectiveValue.worker("other", ""))))));
        assertThat(request(A, 0).authority().isEmpty()).isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }

    @Test
    void directWorkerPut_cannotMintAuthority() {
        var forged = GovernorAnnouncementValue.governorAnnouncementValue(A, 1);
        store.process(store.createBatch(List.of(new KVCommand.Put<>(KEY, forged))));
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }

    @Test
    void emptyCommunity_cannotAcquireAuthority() {
        leader(CORE, 1);
        members.set(List.of());
        assertThat(request(A, 0).authority().isEmpty()).isTrue();
    }
}
