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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicReference<Runnable> beforeApply = new AtomicReference<>(() -> {});
    private final ClusterNode<KVCommand<AetherKey>> cluster = new ClusterNode<>() {
        @Override public NodeId self() { return CORE; }
        @Override public TopologyManager topologyManager() { return null; }
        @Override public Promise<Unit> start() { return Promise.success(Unit.unit()); }
        @Override public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            commits.incrementAndGet();
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
        return authority.handle(new GovernorAuthorityMessage.Request(worker, "c", 1, expected, "host:9000", false)).await().unwrap();
    }

    private GovernorAuthorityMessage.Response refresh(NodeId worker, long expected) {
        return authority.handle(new GovernorAuthorityMessage.Request(worker, "c", 1, expected, "host:9000", true)).await().unwrap();
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
        assertThat(refresh(A, incumbent.communityTerm()).authority().isEmpty()).isTrue();
        assertThat(store.getTyped(KEY, GovernorAnnouncementValue.class).unwrap()).isEqualTo(incumbent);
    }

    /// The nominee's directive is a read witness of the claim's transaction (H13): a directive that is
    /// REPLACED before apply refuses the claim. Reassigning by a bare `Put` is no longer possible (H11), so the
    /// directive is removed and re-created in another community; creation where none exists stays allowed.
    @Test
    void candidateReassignedBeforeApply_claimIsRejected() {
        leader(CORE, 1);
        var directiveKey = AetherKey.ActivationDirectiveKey.activationDirectiveKey(A);
        beforeApply.set(() -> {
            store.process(store.createBatch(List.of(new KVCommand.Remove<>(directiveKey))));
            store.process(store.createBatch(List.of(new KVCommand.Put<>(directiveKey, AetherValue.ActivationDirectiveValue.worker("other", "")))));
        });
        assertThat(request(A, 0).authority().isEmpty()).isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }

    /// H11: a bare `Put` naming another community for a committed directive is refused at commit, so the
    /// directive is unchanged and the claim that was in flight is still granted.
    @Test
    void communityReassignmentByPut_isRefusedAtCommit_directiveUnchanged_claimStillGranted() {
        leader(CORE, 1);
        var directiveKey = AetherKey.ActivationDirectiveKey.activationDirectiveKey(A);
        beforeApply.set(() -> store.process(store.createBatch(List.of(
            new KVCommand.Put<>(directiveKey, AetherValue.ActivationDirectiveValue.worker("other", ""))))));
        assertThat(request(A, 0).authority().unwrap().governorId()).isEqualTo(A);
        assertThat(store.getTyped(directiveKey, AetherValue.ActivationDirectiveValue.class).unwrap())
            .isEqualTo(AetherValue.ActivationDirectiveValue.worker("c", ""));
    }

    /// The nominee's committed directive is read as role WORKER AND the claimed community (H13). The core's
    /// directive here names the community, so only the ROLE can be what refuses it, and its id sorts first so
    /// that without the role check it would be the nominated candidate.
    @Test
    void coreRoleNominee_claimIsRefused_byRoleNotByCommunity() {
        var coreNominee = new NodeId("a-core");
        store.process(store.createBatch(List.of(new KVCommand.Put<>(AetherKey.ActivationDirectiveKey.activationDirectiveKey(coreNominee),
            new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.CORE, "c", "")))));
        members.set(List.of(coreNominee, A, B));
        leader(CORE, 1);
        assertThat(request(coreNominee, 0).authority().isEmpty()).isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
        assertThat(authority.reconcile("c", coreNominee, 0, "core:9").await().unwrap().isEmpty()).as("recovery path refuses the core too").isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
        assertThat(authority.reconcile("c", A, 0, "a:9").await().unwrap().unwrap().governorId()).as("control: a worker in the same setup is granted").isEqualTo(A);
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

    /// #2542: a SWIM edge re-nominates the sitting governor with nothing changed; the committed value is already that
    /// announcement, so nothing is committed and the committed value (timestamp included) stays as it was.
    @Test
    void unchangedAnnouncement_edgeRequest_commitsNothing() {
        leader(CORE, 1);
        var granted = request(A, 0).authority().unwrap();
        var commitsAfterGrant = commits.get();

        var answered = request(A, granted.communityTerm()).authority().unwrap();

        assertThat(commits.get()).as("an unchanged announcement must not reach consensus").isEqualTo(commitsAfterGrant);
        assertThat(answered).as("the reply carries the committed value").isEqualTo(granted);
        assertThat(store.getTyped(KEY, GovernorAnnouncementValue.class).unwrap()).isEqualTo(granted);
    }

    /// Control for the test above: a change of roster is still committed, with the term kept.
    @Test
    void changedRoster_edgeRequest_isCommitted() {
        leader(CORE, 1);
        var granted = request(A, 0).authority().unwrap();
        var commitsAfterGrant = commits.get();
        members.set(List.of(A));

        var answered = request(A, granted.communityTerm()).authority().unwrap();

        assertThat(commits.get()).isEqualTo(commitsAfterGrant + 1);
        assertThat(answered.members()).containsExactly(A);
        assertThat(answered.communityTerm()).isEqualTo(granted.communityTerm());
    }

    /// Control: a changed address is a change.
    @Test
    void changedAddress_edgeRequest_isCommitted() {
        leader(CORE, 1);
        var granted = request(A, 0).authority().unwrap();
        var commitsAfterGrant = commits.get();

        var answered = authority.handle(new GovernorAuthorityMessage.Request(A, "c", 1, granted.communityTerm(), "moved:9000", false)).await().unwrap().authority().unwrap();

        assertThat(commits.get()).isEqualTo(commitsAfterGrant + 1);
        assertThat(answered.tcpAddress()).isEqualTo("moved:9000");
    }

    /// The re-announce timer is the liveness refresh: the same unchanged announcement is committed again, with only its
    /// timestamp new, so the committed `announcedAt` (and the Put the core's freshness window reads) keeps moving.
    @Test
    void unchangedAnnouncement_refreshRequest_isCommittedWithANewTimestamp() throws InterruptedException {
        leader(CORE, 1);
        var granted = request(A, 0).authority().unwrap();
        var commitsAfterGrant = commits.get();

        Thread.sleep(5);
        var answered = refresh(A, granted.communityTerm()).authority().unwrap();

        assertThat(commits.get()).isEqualTo(commitsAfterGrant + 1);
        assertThat(answered.announcedAt()).isGreaterThan(granted.announcedAt());
        assertThat(answered.unchangedFrom(granted)).as("nothing but the timestamp moved").isTrue();
    }

    /// The core's epoch is a generation counter that ticks every second; an announcement that differs only by a newer one is
    /// not a change (#2542), and a refresh carries the newer one.
    @Test
    void newerCoreEpoch_edgeRequestCommitsNothing_refreshCarriesIt() {
        var epoch = new AtomicReference<>(Epoch.ZERO);
        var observing = GovernorAuthority.governorAuthority(CORE, cluster, store, () -> true, _ -> members.get(), _ -> true, epoch::get, HlcClock.hlcClock(CORE));
        leader(CORE, 1);
        var granted = observing.handle(new GovernorAuthorityMessage.Request(A, "c", 1, 0, "host:9000", false)).await().unwrap().authority().unwrap();
        var commitsAfterGrant = commits.get();

        epoch.set(Epoch.epoch(0L, 1L, 0));
        var edge = observing.handle(new GovernorAuthorityMessage.Request(A, "c", 1, granted.communityTerm(), "host:9000", false)).await().unwrap().authority().unwrap();

        assertThat(commits.get()).isEqualTo(commitsAfterGrant);
        assertThat(edge.observedCoreEpoch()).isEqualTo(Epoch.ZERO);

        var refreshed = observing.handle(new GovernorAuthorityMessage.Request(A, "c", 1, granted.communityTerm(), "host:9000", true)).await().unwrap().authority().unwrap();

        assertThat(commits.get()).isEqualTo(commitsAfterGrant + 1);
        assertThat(refreshed.observedCoreEpoch()).isEqualTo(Epoch.epoch(0L, 1L, 0));
    }

    /// Core-initiated recovery names another governor, so it commits; it asks for the refresh itself.
    @Test
    void recoveryNamingTheSittingGovernor_isCommitted() {
        leader(CORE, 1);
        var granted = request(A, 0).authority().unwrap();
        var commitsAfterGrant = commits.get();

        authority.reconcile("c", A, granted.communityTerm(), "host:9000").await().unwrap();

        assertThat(commits.get()).isEqualTo(commitsAfterGrant + 1);
    }
}
