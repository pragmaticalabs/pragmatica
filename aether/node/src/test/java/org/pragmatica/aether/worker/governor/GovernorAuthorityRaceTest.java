// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.worker.governor;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorAuthorityRaceTest {
    private static final NodeId CORE = new NodeId("core");
    private static final NodeId A = new NodeId("worker-a");
    private static final NodeId B = new NodeId("worker-b");
    private static final AetherKey.GovernorAnnouncementKey KEY = AetherKey.GovernorAnnouncementKey.forCommunity("c");
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final AtomicReference<Runnable> beforeApply = new AtomicReference<>(() -> {});
    private final AtomicBoolean activeLeader = new AtomicBoolean(true);
    private final AtomicReference<List<NodeId>> members = new AtomicReference<>(List.of(A, B));
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

    private GovernorAuthority authority() {
        return GovernorAuthority.governorAuthority(CORE, cluster, store,
            activeLeader::get, _ -> members.get(), _ -> true, () -> Epoch.ZERO, HlcClock.hlcClock(CORE));
    }

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void seed() {
        store.process(store.createBatch(List.of(
            new KVCommand.Put<>(AetherKey.CommunityKey.communityKey("c"), AetherValue.CommunityValue.communityValue("", "WORKER", 2)),
            new KVCommand.Put<>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(A), AetherValue.ActivationDirectiveValue.worker("c", "")),
            new KVCommand.Put<>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(B), AetherValue.ActivationDirectiveValue.worker("c", "")))));
        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(CORE, 1)))));
    }

    private GovernorAuthorityMessage.Response request(GovernorAuthority authority, NodeId worker, long expected) {
        return authority.handle(new GovernorAuthorityMessage.Request(worker, "c", 1, expected, "host:9000")).await().unwrap();
    }

    /// Two grantable nominations built from the same empty base: A reads previous=none as the
    /// deterministic candidate; before its transaction applies, the membership view changes and B's
    /// nomination commits. A's stale-base transaction must be refused (none), never echoed as a grant.
    /// (A nomination that is not the deterministic candidate is refused BEFORE consensus — the
    /// lowest eligible member wins — so the only reachable race is across membership views.)
    @Test
    void competingNominationsFromTheSameBase_exactlyOneAccepted_loserSeesRefusal() {
        var authority = authority();
        var winner = new AtomicReference<GovernorAnnouncementValue>();
        beforeApply.set(() -> {
            members.set(List.of(B));
            winner.set(request(authority, B, 0).authority().unwrap());
        });
        var loser = request(authority, A, 0);
        assertThat(winner.get().governorId()).isEqualTo(B);
        assertThat(winner.get().communityTerm()).isEqualTo(1);
        assertThat(loser.authority().isEmpty()).as("stale-base claim must be refused, not echoed").isTrue();
        var committed = store.getTyped(KEY, GovernorAnnouncementValue.class).unwrap();
        assertThat(committed.governorId()).isEqualTo(B);
        assertThat(committed.communityTerm()).isEqualTo(1);
        // The loser's next attempt with the now-correct term is a refusal that NAMES the winner.
        members.set(List.of(A, B));
        assertThat(request(authority, A, 1).authority().unwrap().governorId()).isEqualTo(B);
        assertThat(store.getTyped(KEY, GovernorAnnouncementValue.class).unwrap().communityTerm()).isEqualTo(1);
    }

    /// Same nominee, two identical requests racing (retry after a timed-out first request): the
    /// second's transaction is built on the stale empty base and is refused; the announcer then
    /// falls back to committed state, which already names it.
    @Test
    void duplicateRequestFromSameNominee_secondIsRefusedNotDoubleMinted() {
        var authority = authority();
        var first = new AtomicReference<GovernorAnnouncementValue>();
        beforeApply.set(() -> first.set(request(authority, A, 0).authority().unwrap()));
        var second = request(authority, A, 0);
        assertThat(first.get().communityTerm()).isEqualTo(1);
        assertThat(second.authority().isEmpty()).isTrue();
        assertThat(store.getTyped(KEY, GovernorAnnouncementValue.class).unwrap().communityTerm()).isEqualTo(1);
    }

    /// A new authority instance (core restart / leader move) over the same committed store continues
    /// the generation: same owner refresh keeps term 1, owner change mints 2, never restarts at 1.
    @Test
    void newAuthorityInstanceOverSameStore_continuesCommittedGeneration() {
        var first = request(authority(), A, 0).authority().unwrap();
        assertThat(first.communityTerm()).isEqualTo(1);
        var restarted = authority();
        assertThat(request(restarted, A, 1).authority().unwrap().communityTerm()).isEqualTo(1);
        assertThat(request(restarted, A, 1).authority().unwrap().governorId()).isEqualTo(A);
        var changed = restarted.reconcile("c", B, 1, "b:9").await().unwrap().unwrap();
        assertThat(changed.communityTerm()).isEqualTo(2);
        assertThat(changed.communityEpoch()).isEqualTo(Epoch.epoch(2, 0L));
        // And a fresh instance after THAT still cannot be talked back to term 1.
        assertThat(request(authority(), A, 1).authority().unwrap().governorId()).isEqualTo(B);
        assertThat(request(authority(), A, 2).authority().unwrap().governorId()).isEqualTo(B);
    }

    /// The store names this core as leader but the engine says it is not the ACTIVE leader: the
    /// request is a failed promise (no grant, no write), not a silent echo of previous state.
    @Test
    void storeLeaderButInactiveEngine_refusesWithFailureAndWritesNothing() {
        activeLeader.set(false);
        var result = authority().handle(new GovernorAuthorityMessage.Request(A, "c", 1, 0, "host:9000")).await();
        assertThat(result.isFailure()).isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }
}
