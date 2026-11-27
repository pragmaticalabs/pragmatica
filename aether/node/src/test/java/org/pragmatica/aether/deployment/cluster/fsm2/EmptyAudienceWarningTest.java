// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// #3815: a committed target whose placement audience is empty is visible to the operator as `placement-audience-empty`, once per episode,
/// closed by `placement-audience-available` when the audience is non-empty (or the target or its policy goes away). The floor is unchanged.
class EmptyAudienceWarningTest {
    private static final String OPEN = "placement-audience-empty";
    private static final String CLOSE = "placement-audience-available";
    private static final NodeId W1 = node("worker-1");
    private static final NodeId W2 = node("worker-2");
    private static final NodeId W3 = node("worker-3");
    private static final Set<NodeId> ALL = Set.of(CORE_1, CORE_2, CORE_3, W1, W2, W3);

    private static MbWorld formingWorld(String placement, int floor, NodeId... workers) {
        var w = MbWorld.worldWithFloor(floor, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, placement)
                       .community("east-w-0", 3, CommunityState.FORMING)
                       .seams(seams -> seams.withReady(ALL)
                                            .withLocalAlive(Option.some(Set.of(workers))));

        for (var worker : workers) {
            w.directive(worker, worker("east-w-0"));
        }

        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(workers[0], false, workers));
        w.activate();

        return w;
    }

    /// Red when: the empty audience raises nothing, raises on every pass, or the text leaves out the target, the policy, the community
    /// state, the live members, the floor or the way out (`allow_below_rf`).
    @Test
    void oneWorker_workersOnly_raisesOnce_acrossTicks_andNamesWhatTheOperatorNeeds() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        var warns = w.h.warns(OPEN);

        assertThat(warns).as("once per episode").hasSize(1);
        assertThat(warns.getFirst().subject()).isEqualTo(ORDERS.base().asString());
        assertThat(warns.getFirst().message()).contains(ORDERS.base().asString(),
                                                        "WORKERS_ONLY",
                                                        "east-w-0",
                                                        "FORMING",
                                                        "1 live",
                                                        "floor is 3",
                                                        "allow_below_rf");
        assertThat(w.h.warns(CLOSE)).isEmpty();
        assertThat(hosts(w.h, ORDERS)).as("the floor stays: nothing is placed").isEmpty();
    }

    /// Red when: the episode is not closed once the audience is non-empty (two more workers make the community ACTIVE), or the target is
    /// not placed then, or the episode does not reopen for a later empty audience (checked by the count staying at one open).
    @Test
    void audienceBecomesNonEmpty_closesTheEpisode_andTheTargetPlaces() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).hasSize(1);
        w.h.seed(new ActivationDirectiveKey(W2), worker("east-w-0"));
        w.h.seed(new ActivationDirectiveKey(W3), worker("east-w-0"));
        w.h.drain();
        w.refreshSeams(seams -> seams.withLocalAlive(Option.some(Set.of(W1, W2, W3))));
        w.announce("east-w-0", W1, W1, W2, W3);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        var closes = w.h.warns(CLOSE);

        assertThat(closes).hasSize(1);
        assertThat(closes.getFirst().subject()).isEqualTo(ORDERS.base().asString());
        assertThat(closes.getFirst().message()).doesNotContain(Recoveries.SUBJECT_GONE);
        assertThat(w.h.warns(OPEN)).as("no second opener").hasSize(1);
        assertThat(hosts(w.h, ORDERS)).hasSize(1);
        assertThat(hosts(w.h, ORDERS).getFirst()).isIn(W1, W2, W3);
    }

    /// Red when: a cluster whose community is FORMING with three live workers (the floor) raises the event at any pass: its ACTIVE write is
    /// due in the same tick, so the audience is not stalled (Placement runs before the community write commits).
    @Test
    void threeWorkers_fromTheStart_raiseNothing() {
        var w = threeForming(Set.of(), Set.of());

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).isEmpty();
        assertThat(w.h.warns(CLOSE)).isEmpty();
        assertThat(hosts(w.h, ORDERS)).hasSize(1);
    }

    /// Red when: the community's own ACTIVE write is refused and the empty audience stays silent (the draft is redone every pass, so a
    /// suppression read from the draft would hide the stall for good). The event is computed from committed state, except while that write is
    /// in flight: it is raised on the first pass after the refusal.
    @Test
    void activeWriteRefused_raisesOnTheNextPass_andClosesWhenItCommits() {
        var w = threeForming(Set.of(ClusterModel.SubmitKind.COMMUNITY), Set.of());

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("the write is in flight").isEmpty();
        var write = w.h.held.values().iterator().next();

        w.h.held.clear();
        w.h.settle(write, w.h.refused(write));
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("refused: the stall is told").hasSize(1);
        w.h.holdKinds = Set.of();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(CLOSE)).as("the next write commits: the episode closes").hasSize(1);
        assertThat(hosts(w.h, ORDERS)).hasSize(1);
    }

    /// Red when: the ACTIVE write is held in flight past several passes and then commits, and an event opens meanwhile or after: the
    /// community's own write in flight raises nothing, and once it commits the audience is not empty.
    @Test
    void activeWriteCommits_afterSeveralPasses_opensNoEvent() {
        var w = threeForming(Set.of(ClusterModel.SubmitKind.COMMUNITY), Set.of());

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        var write = w.h.held.values().iterator().next();

        w.h.held.values().removeIf(held -> !held.equals(write));
        w.h.settleHeld(write,
                       w.h.applyHeld(write.correlationId()));
        w.h.holdKinds = Set.of();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).isEmpty();
        assertThat(w.h.warns(CLOSE)).isEmpty();
        assertThat(hosts(w.h, ORDERS)).hasSize(1);
    }

    /// Red when: the in-flight window of a community outlives the write that opened it: once the committed state agrees, the window is gone,
    /// so a later activation (a degraded community recovering) opens its own and cannot inherit an expired one.
    @Test
    void activationWindow_opensWithTheWrite_andEndsWhenTheCommittedStateAgrees() {
        var w = threeForming(Set.of(ClusterModel.SubmitKind.COMMUNITY), Set.of());

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().activationDeadline.containsKey("east-w-0")).as("the write is drafted: its window is open")
                  .isTrue();
        w.h.holdKinds = Set.of();
        w.h.held.clear();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().activationDeadline.isEmpty()).as("the committed state agrees: no window").isTrue();
    }

    /// Red when: the ACTIVE write fails (the settlement is Failed) and the empty audience stays silent: a failed write is not in flight, so
    /// the stall is told on the first pass after it, the same as a refusal.
    @Test
    void activeWriteFailed_raisesOnTheNextPass() {
        var w = threeForming(Set.of(ClusterModel.SubmitKind.COMMUNITY), Set.of());

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("the write is in flight").isEmpty();
        var write = w.h.held.values().iterator().next();

        w.h.held.clear();
        w.h.settleHeld(write, new ClusterInput.Settlement.Failed("injected"));
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("failed: the stall is told").hasSize(1);
    }

    /// Red when: the ACTIVE write is lost (never settles) and the empty audience stays silent past the in-flight bound.
    @Test
    void activeWriteLost_raisesAfterTheInFlightBound() {
        var w = threeForming(Set.of(), Set.of(ClusterModel.SubmitKind.COMMUNITY));

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("the write is in flight").isEmpty();
        w.h.advance(w.h.settings.submissionExpiry() + 3 * w.h.settings.timing().tick());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("lost: the stall is told").hasSize(1);
    }

    private static MbWorld threeForming(Set<ClusterModel.SubmitKind> hold, Set<ClusterModel.SubmitKind> lose) {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "WORKERS_ONLY")
                       .community("east-w-0", 3, CommunityState.FORMING)
                       .seams(seams -> seams.withReady(ALL)
                                            .withLocalAlive(Option.some(Set.of(W1, W2, W3))));

        w.h.holdKinds = hold;
        w.h.loseKinds = lose;
        w.directive(W1, worker("east-w-0")).directive(W2, worker("east-w-0")).directive(W3, worker("east-w-0"));
        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.activate();

        return w;
    }

    /// Red when: a policy other than WORKERS_ONLY that computes an empty audience (CORE_ONLY with no ready core) stays silent: the one
    /// site covers every policy kind.
    @Test
    void coreOnly_withNoReadyCore_raisesToo() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "CORE_ONLY")
                       .seams(seams -> seams.withReady(Set.of()))
                       .activate();

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).hasSize(1);
        assertThat(w.h.warns(OPEN).getFirst().message()).contains("CORE_ONLY");
    }

    /// Red when: a community's pending ACTIVE write holds back the event of a CORE_ONLY target: the write changes the worker audience only,
    /// so a CORE_ONLY target with no ready core is told on the first pass, the community due or not.
    @Test
    void coreOnly_withACommunityDue_stillRaisesOnTheFirstPass() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "CORE_ONLY")
                       .community("east-w-0", 3, CommunityState.FORMING)
                       .seams(seams -> seams.withReady(Set.of(W1, W2, W3))
                                            .withLocalAlive(Option.some(Set.of(W1, W2, W3))));

        w.h.holdKinds = Set.of(ClusterModel.SubmitKind.COMMUNITY);
        w.directive(W1, worker("east-w-0")).directive(W2, worker("east-w-0")).directive(W3, worker("east-w-0"));
        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.activate();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).hasSize(1);
    }

    /// Red when: removing the target leaves the episode open (subject gone).
    @Test
    void targetRemoved_closesTheEpisode_subjectGone() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.seedRemove(new SliceTargetKey(ORDERS.base()));
        w.h.drain();
        var closes = w.h.warns(CLOSE);

        assertThat(closes).hasSize(1);
        assertThat(closes.getFirst().message()).contains(Recoveries.SUBJECT_GONE);
        assertThat(closes.getFirst().message()).as("the removal reason the operator reads")
                  .isEqualTo("Placement audience of " + ORDERS.base().asString() + ": "
                             + Recoveries.gone(ORDERS.base().asString(), "the target was removed"));
    }

    /// Red when: a target whose placement becomes unparsable keeps `placement-audience-empty` open for a policy that no longer exists.
    @Test
    void placementTurnsInvalid_closesTheEpisode() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 1, 1, "SIDEWAYS"));
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns("invalid-placement")).hasSize(1);
        assertThat(w.h.warns(CLOSE)).hasSize(1);
        assertThat(w.h.warns(CLOSE).getFirst().message()).as("the reason the operator reads")
                  .isEqualTo("Placement audience of " + ORDERS.base().asString() + ": its placement does not parse");
    }

    /// Red when: the opening text states a core count other than the eligible cores (three ready cores here), or leaves out any word of the
    /// way out.
    @Test
    void emptyAudience_text_isExactly_theOperatorsInstruction() {
        var w = formingWorld("WORKERS_ONLY", 3, W1);

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN).getFirst().message()).isEqualTo(
            "Target " + ORDERS.base().asString() + " is not placed: its placement WORKERS_ONLY has an empty audience (3 eligible cores; "
            + "communities: east-w-0 FORMING with 1 live). The community viability floor is 3 live members: add workers, "
            + "or lower viability_floor in [community_policy] with allow_below_rf = true");
    }

    /// Red when: the community census is not in id order (east-w-0 before east-w-1, whatever the order they were created in).
    @Test
    void emptyAudience_census_listsCommunitiesInIdOrder() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "WORKERS_ONLY")
                       .community("east-w-1", 3, CommunityState.FORMING)
                       .community("east-w-0", 3, CommunityState.FORMING)
                       .seams(seams -> seams.withReady(ALL)
                                            .withLocalAlive(Option.some(Set.of(W1))));

        w.directive(W1, worker("east-w-0"));
        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1));
        w.activate();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN).getFirst().message())
            .contains("(3 eligible cores; communities: east-w-0 FORMING with 1 live, east-w-1 FORMING with 0 live).");
    }

    /// Red when: the event is held back at the bound minus one nanosecond, or is not raised at the bound: the window is the
    /// submission-expiry bound in nanoseconds from the draft (a milliseconds-to-nanoseconds factor off by a thousand ends it far too early).
    @Test
    void activeWriteLost_heldAtTheBoundMinusOne_raisedAtTheBound() {
        var w = threeForming(Set.of(), Set.of(ClusterModel.SubmitKind.COMMUNITY));
        var bound = w.h.monoNs + w.h.settings.submissionExpiry() * 1_000_000L;

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.monoNs = bound - 1;
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("one nanosecond inside the window: still in flight").isEmpty();
        w.h.monoNs = bound;
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("at the bound the window has ended: the stall is told").hasSize(1);
    }

    /// Red when: an ACTIVE community whose live members meet the floor but are not ready (empty worker audience, no write due) is held back
    /// as if its ACTIVE write were in flight: `due` must exclude a community that is already ACTIVE (it has no window, which reads as open).
    @Test
    void activeCommunity_membersNotReady_raisesTheEvent_noWriteIsDue() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "WORKERS_ONLY")
                       .community("east-w-0", 3, CommunityState.ACTIVE)
                       .seams(seams -> seams.withReady(Set.of(CORE_1, CORE_2, CORE_3))
                                            .withLocalAlive(Option.some(Set.of(W1, W2, W3))));

        w.directive(W1, worker("east-w-0")).directive(W2, worker("east-w-0")).directive(W3, worker("east-w-0"));
        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.activate();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("ACTIVE with the floor met: nothing is in flight, the empty audience is told").hasSize(1);
    }

    private static MbWorld activeWorld() {
        var w = MbWorld.worldWithFloor(3, CORE_1, CORE_2, CORE_3)
                       .target(ORDERS, 1, "WORKERS_ONLY")
                       .community("east-w-0", 3, CommunityState.ACTIVE)
                       .seams(seams -> seams.withReady(ALL)
                                            .withLocalAlive(Option.some(Set.of(W1, W2, W3))));

        w.directive(W1, worker("east-w-0")).directive(W2, worker("east-w-0")).directive(W3, worker("east-w-0"));
        w.h.seedQuietly(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.activate();

        return w;
    }

    /// The window belongs to ONE write, the community's own ACTIVE write (#3852 F1). A DEGRADED write opens the window and commits late,
    /// coalesced with a roster return (its KvPut, its settlement and W3's return land before the next pass), so no pass sees agreement
    /// between; the ACTIVE write is then drafted. Red when: the ACTIVE write is held back only for the DEGRADED write's remaining window and
    /// the event opens about 6 seconds into it (the submission-expiry bound is 35 s), far inside the ACTIVE write's own bound. The steps are in
    /// seconds, not ticks: a tick is 30 s, which is longer than the bound itself.
    @Test
    void degradedWriteWindow_commitsLate_doesNotCutTheActiveWritesHoldBackShort() {
        var w = degradedCommittedLate_activeWriteDrafted();
        var second = 1_000L;

        w.h.advance(6 * second);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("the ACTIVE write has been in flight ~6 seconds, far below its own bound").isEmpty();
    }

    /// Red when: a window of another edge is kept for the ACTIVE write (the ACTIVE write then has no window of its own, which `Placement` reads
    /// as "not drafted yet": held back for ever). The ACTIVE write is lost after the F1 sequence; the stall must be told past ITS bound.
    @Test
    void degradedWriteCommitsLate_theActiveWriteLost_raisesAfterItsOwnBound() {
        var w = degradedCommittedLate_activeWriteDrafted();

        w.h.advance(w.h.settings.submissionExpiry() + 3 * w.h.settings.timing().tick());
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("the ACTIVE write is lost: the stall is told past its own bound").hasSize(1);
    }

    /// The F1 sequence up to the point where the ACTIVE write is drafted: the DEGRADED write commits late, coalesced with a roster return, at
    /// submission-expiry minus 3 seconds; no pass saw agreement between, so the slot still holds the DEGRADED write's window. COMMUNITY drafts stay held.
    private static MbWorld degradedCommittedLate_activeWriteDrafted() {
        var w = activeWorld();
        var expiry = w.h.settings.submissionExpiry();
        var second = 1_000L;

        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(hosts(w.h, ORDERS)).as("placed while ACTIVE").hasSize(1);
        w.h.holdKinds = Set.of(ClusterModel.SubmitKind.COMMUNITY);
        w.announce("east-w-0", W1, W1, W2);
        w.h.deliver(new ClusterInput.Reconcile());
        var opened = w.h.model().activationDeadline.get("east-w-0");

        assertThat(opened.isPresent()).as("the DEGRADED write opened the window").isTrue();
        w.h.advance(expiry - 3 * second);
        assertThat(w.h.held).as("the DEGRADED write is held").isNotEmpty();
        var degraded = w.h.held.values().iterator().next();

        w.h.held.values().removeIf(held -> !held.equals(degraded));
        w.h.settle(degraded, w.h.applyHeld(degraded.correlationId()));
        w.h.seed(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().communities.get("east-w-0").map(v -> v.state().name()).or("none"))
            .as("committed DEGRADED, the ACTIVE write drafted").isEqualTo("DEGRADED");
        assertThat(w.h.warns(OPEN)).as("the ACTIVE write is in flight").isEmpty();

        return w;
    }

    /// Control of the test above, same sequence plus one agreement pass after the DEGRADED commit (W3 still off the roster): the window is
    /// removed and the ACTIVE write opens its own, so no opener appears. Green at head: the red above comes from the carried-over window.
    @Test
    void control_degradedWriteCommits_withAnAgreementPass_theActiveWriteHasItsOwnWindow() {
        var w = activeWorld();
        var expiry = w.h.settings.submissionExpiry();
        var second = 1_000L;

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.holdKinds = Set.of(ClusterModel.SubmitKind.COMMUNITY);
        w.announce("east-w-0", W1, W1, W2);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.advance(expiry - 3 * second);
        assertThat(w.h.held).as("the DEGRADED write is held").isNotEmpty();
        var degraded = w.h.held.values().iterator().next();

        w.h.held.values().removeIf(held -> !held.equals(degraded));
        w.h.settle(degraded, w.h.applyHeld(degraded.correlationId()));
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().activationDeadline.get("east-w-0").isPresent()).as("agreement removed the window").isFalse();
        var opensBefore = w.h.warns(OPEN).size();

        w.announce("east-w-0", W1, W1, W2, W3);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.advance(6 * second);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("no new opener while the fresh window is open").hasSize(opensBefore);
    }

    /// The window belongs to ONE write (#3852, reverse direction): the DEGRADED write commits, the ACTIVE write is drafted and in flight, and
    /// the DEGRADED write's settlement (refused here) arrives late. Red when: that settlement closes the community's window, which is now the
    /// ACTIVE write's, and the event opens while the ACTIVE write is still inside its own bound.
    @Test
    void degradedWriteRefusedLate_whileTheActiveWriteIsInFlight_leavesItsWindowOpen() {
        lateSettlementOfTheDegradedWrite_leavesTheActiveWritesWindowOpen(true);
    }

    /// Same as the refusal above, through the failure path (`Outbox.failed`): a late failure of the older write.
    @Test
    void degradedWriteFailedLate_whileTheActiveWriteIsInFlight_leavesItsWindowOpen() {
        lateSettlementOfTheDegradedWrite_leavesTheActiveWritesWindowOpen(false);
    }

    private static void lateSettlementOfTheDegradedWrite_leavesTheActiveWritesWindowOpen(boolean refused) {
        var w = activeWorld();
        var second = 1_000L;

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.holdKinds = Set.of(ClusterModel.SubmitKind.COMMUNITY);
        w.announce("east-w-0", W1, W1, W2);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.held).as("the DEGRADED write is held").isNotEmpty();
        var degraded = w.h.held.values().iterator().next();

        w.h.held.clear();
        w.h.applyTransaction(degraded);
        w.h.seed(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().communities.get("east-w-0").map(v -> v.state().name()).or("none"))
            .as("committed DEGRADED, the ACTIVE write drafted").isEqualTo("DEGRADED");
        assertThat(w.h.held).as("the ACTIVE write is held in flight").isNotEmpty();
        w.h.advance(2 * second);
        w.h.settleHeld(degraded,
                       refused
                       ? w.h.refused(degraded)
                       : new ClusterInput.Settlement.Failed("late"));
        w.h.advance(2 * second);
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.warns(OPEN)).as("the older write settled late: the ACTIVE write is still inside its own bound").isEmpty();
    }

    /// The window belongs to ONE write (#3852, enforcer in `Placement`): the DEGRADED write's window has run out, then its commit lands
    /// coalesced with the roster return, so the pass that sees committed DEGRADED has not drafted the ACTIVE write yet. Red when: the stale
    /// window of the other write, past its deadline, is read as the ACTIVE write's and the event opens at once.
    @Test
    void expiredDegradedWindow_activeWriteNotYetDrafted_isHeldBack() {
        activeWriteNotYetDrafted_afterTheDegradedCommit(Option.none());
    }

    /// Same pass, with the slot holding a closed window of a write that left FORMING for ACTIVE: the same target state, another edge, so
    /// not the ACTIVE write's window of a DEGRADED community. Red when: `ActivationWindow#isFor` ignores the state the write leaves.
    @Test
    void closedWindowOfTheFormingEdge_isNotTheDegradedCommunitysActiveWrite() {
        activeWriteNotYetDrafted_afterTheDegradedCommit(Option.some(ActivationWindow.activationWindow(99L,
                                                                                                     CommunityState.FORMING,
                                                                                                     CommunityState.ACTIVE,
                                                                                                     0L)));
    }

    /// Same pass, with the slot holding a closed window of a write that left DEGRADED for DEGRADED: the same starting state, another target.
    /// Red when: `ActivationWindow#isFor` ignores the state the write targets.
    @Test
    void closedWindowOfAnotherTarget_isNotTheDegradedCommunitysActiveWrite() {
        activeWriteNotYetDrafted_afterTheDegradedCommit(Option.some(ActivationWindow.activationWindow(98L,
                                                                                                     CommunityState.DEGRADED,
                                                                                                     CommunityState.DEGRADED,
                                                                                                     0L)));
    }

    private static void activeWriteNotYetDrafted_afterTheDegradedCommit(Option<ActivationWindow> slot) {
        var w = activeWorld();
        var expiry = w.h.settings.submissionExpiry();
        var tick = w.h.settings.timing().tick();

        w.h.deliver(new ClusterInput.Reconcile());
        w.h.holdKinds = Set.of(ClusterModel.SubmitKind.COMMUNITY);
        w.announce("east-w-0", W1, W1, W2);
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.advance(expiry + tick);
        assertThat(w.h.held).as("the DEGRADED write is held").isNotEmpty();
        var degraded = w.h.held.values().iterator().next();

        w.h.held.values().removeIf(held -> !held.equals(degraded));
        w.h.settle(degraded, w.h.applyHeld(degraded.correlationId()));
        w.h.seed(new GovernorAnnouncementKey("east-w-0"), roster(W1, false, W1, W2, W3));
        w.h.drain();
        slot.onPresent(window -> w.h.model().activationDeadline = w.h.model().activationDeadline.put("east-w-0", window));
        w.h.deliver(new ClusterInput.Reconcile());
        assertThat(w.h.model().communities.get("east-w-0").map(v -> v.state().name()).or("none"))
            .as("committed DEGRADED, the ACTIVE write drafted").isEqualTo("DEGRADED");
        assertThat(w.h.warns(OPEN)).as("the ACTIVE write has no window of its own to have run out").isEmpty();
    }
}
