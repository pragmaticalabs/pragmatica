// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.worker.health;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.MemberHealth;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Report;
import org.pragmatica.aether.worker.health.CommunityHealthMessage.Request;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityObservationAuthorityTest {
    private final NodeId core = new NodeId("core");
    private final NodeId governor = new NodeId("governor");
    private final NodeId worker = new NodeId("worker");
    private final NodeId otherGovernor = new NodeId("other-governor");
    private final AtomicLong clock = new AtomicLong(1_000);
    private final AtomicReference<GovernorAnnouncementValue> authority = new AtomicReference<>(announcement(governor, 3));
    private final Map<NodeId, String> assignments = new HashMap<>(Map.of(governor, "community", worker, "community", otherGovernor, "other"));
    private final CommunityHealthIndex index = CommunityHealthIndex.communityHealthIndex(core, community -> "community".equals(community)
            ? Option.some(authority.get())
            : Option.some(announcement(otherGovernor, 7)),
        node -> Option.option(assignments.get(node)), clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(100).nanos(), 200);

    private Report report(NodeId sender, String community, long term, Request request) {
        return new Report(sender, community, term, request.incarnation(), request.sequence(),
            List.of(new MemberHealth(worker, 2, true, true, org.pragmatica.lang.io.TimeSpan.timeSpan(0).nanos())));
    }

    /// Right nonce, wrong term: a piggyback claiming a higher (or lower) term than the committed
    /// announcement is refused and grants nothing.
    @Test
    void rightNonceWrongTerm_isRefused() {
        var request = index.request("community").unwrap();
        assertThat(index.accept(governor, report(governor, "community", request.governorTerm() + 1, request))).isFalse();
        assertThat(index.accept(governor, report(governor, "community", request.governorTerm() - 1, request))).isFalse();
        assertThat(index.isReachable(worker)).isFalse();
        assertThat(index.hasFreshReport("community")).isFalse();
        // The challenge is still open: the genuine term is accepted afterwards.
        assertThat(index.accept(governor, report(governor, "community", request.governorTerm(), request))).isTrue();
    }

    /// Right nonce, wrong scope: the OTHER community's governor replaying this community's nonce
    /// under its own community id (or ours) is refused — the challenge binds sender AND community.
    @Test
    void rightNonceWrongScopeOrSender_isRefused() {
        var request = index.request("community").unwrap();
        assertThat(index.accept(otherGovernor, report(otherGovernor, "community", request.governorTerm(), request))).isFalse();
        assertThat(index.accept(otherGovernor, report(otherGovernor, "other", 7, request))).as("nonce issued for 'community' must not open 'other'").isFalse();
        assertThat(index.accept(worker, report(worker, "community", request.governorTerm(), request))).isFalse();
        assertThat(index.isReachable(worker)).isFalse();
    }

    /// H07 stale report: a report answering an OLD challenge (old incarnation/sequence) presented while
    /// a NEW challenge is pending must be refused — this is the replay the index's nonce exists for.
    /// Keeping a challenge pending isolates the nonce check from the missing-challenge guard.
    @Test
    void oldChallengeReport_presentedAgainstNewPendingChallenge_isRefused() {
        var first = index.request("community").unwrap();
        assertThat(index.accept(governor, report(governor, "community", first.governorTerm(), first))).isTrue();
        clock.addAndGet(100);
        assertThat(index.isReachable(worker)).isFalse();
        var second = index.request("community").unwrap();
        assertThat(second.sequence()).isNotEqualTo(first.sequence());
        var replay = report(governor, "community", first.governorTerm(), first);
        assertThat(index.accept(governor, replay)).as("old-nonce report must not satisfy the new challenge").isFalse();
        assertThat(index.isReachable(worker)).isFalse();
        assertThat(index.hasFreshReport("community")).isFalse();
        assertThat(index.accept(governor, report(governor, "community", second.governorTerm(), second))).isTrue();
        assertThat(index.isReachable(worker)).isTrue();
    }

    /// A report whose sender field names the governor but arrives from a different transport peer is
    /// refused by the index guard (the transport binding in production is InboundMessageAuthority;
    /// the runtime consumes reports already authenticated at that boundary).
    @Test
    void transportSenderMismatch_requiresAuthenticationBeforeRuntimeDispatch() {
        var request = index.request("community").unwrap();
        var forged = report(governor, "community", request.governorTerm(), request);
        assertThat(index.accept(worker, forged)).isFalse();
        var runtime = runtime();
        // The runtime assumes transport authentication has already succeeded.
        runtime.onReport(forged);
        assertThat(index.isReachable(worker)).as("runtime layer alone accepts a sender-field claim; transport binding is load-bearing").isTrue();
    }

    /// H07: an assigned worker's own direct (SWIM/pong) evidence never makes it alive or ready on the
    /// core; only fresh governor evidence does, and that expires.
    @Test
    void assignedWorkerDirectEvidence_cannotRenewItsOwnReachability() {
        var runtime = runtime();
        assertThat(runtime.aliveNodes(Set.of(core, worker, governor))).containsExactly(core);
        assertThat(runtime.readyNodes(Set.of(core, worker, governor))).containsExactly(core);
        var request = index.request("community").unwrap();
        assertThat(index.accept(governor, report(governor, "community", request.governorTerm(), request))).isTrue();
        assertThat(runtime.aliveNodes(Set.of(core))).containsExactlyInAnyOrder(core, worker);
        clock.addAndGet(100);
        // Direct evidence for the worker is still offered and still ignored after the report expired.
        assertThat(runtime.aliveNodes(Set.of(core, worker))).containsExactly(core);
    }

    /// Fresh leader: an empty index says every assigned worker is unreachable IMMEDIATELY; there is
    /// no leadership-age grace on this path (the grace in CommunityReachability is for unassigned nodes).
    @Test
    void freshLeaderIndex_assignedWorkerIsUnreachableWithZeroGrace() {
        var fresh = CommunityHealthIndex.communityHealthIndex(core, _ -> Option.some(authority.get()),
            node -> Option.option(assignments.get(node)), clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(100).nanos(), 200);
        assertThat(fresh.isReachable(worker)).isFalse();
        assertThat(fresh.isReachable(governor)).isFalse();
        assertThat(fresh.hasFreshReport("community")).isFalse();
    }

    private CommunityHealthRuntime runtime() {
        var directory = CommunityMemberDirectory.communityMemberDirectory();
        directory.put(governor, ActivationDirectiveValue.worker("community", ""));
        directory.put(worker, ActivationDirectiveValue.worker("community", ""));
        directory.put(otherGovernor, ActivationDirectiveValue.worker("other", ""));
        var reporter = CommunityHealthReporter.communityHealthReporter(core, _ -> Option.some(authority.get()), directory::assignment, core::equals, clock::get, org.pragmatica.lang.io.TimeSpan.timeSpan(100).nanos());
        var sent = new ArrayList<ProtocolMessage>();
        var evidence = new ArrayList<CommunityHealthIndex.GovernorEvidence>();
        return new CommunityHealthRuntime(core, directory, index, reporter, _ -> Option.some(authority.get()), () -> true,
            () -> "READY", () -> 1, (_, message) -> sent.add(message), evidence::add);
    }

    private static GovernorAnnouncementValue announcement(NodeId governor, long term) {
        return GovernorAnnouncementValue.governorAnnouncementValue(governor, List.of(governor), "", 0, term,
            org.pragmatica.aether.slice.generation.Epoch.ZERO, org.pragmatica.aether.slice.generation.Epoch.ZERO,
            org.pragmatica.hlc.HlcTimestamp.ZERO, false);
    }

}
