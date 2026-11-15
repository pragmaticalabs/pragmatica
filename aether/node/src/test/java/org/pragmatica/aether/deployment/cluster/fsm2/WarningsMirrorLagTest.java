// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// #3274: the mirror can lag a WARNINGS write that commits after this node stopped counting it as in flight: a write settled as failed
/// (`ApplyTimeout`, an unknown outcome) or expired, and a batch of the previous incarnation still in consensus at `Rebuild`. While it lags,
/// the `settled` shortcut of `Warnings.publish` compared the family sets with a stale mirror: a close was published (and nothing written)
/// while the committed row stayed open. The keys of such a write, and every mirrored key at `Rebuild`, are now unsure until a write of
/// this node touching them is accepted or the stale write is seen committed.
class WarningsMirrorLagTest {
    private static final String PLACEMENT = "invalid-placement";
    private static final String PLACEMENT_VALID = "placement-valid";

    private static final FsmWarningKey PLACEMENT_KEY = FsmWarningKey.fsmWarningKey(PLACEMENT, ORDERS.asString());

    private static final String OPENER = "retry-exhausted-unsettled";
    private static final String RECOVERY = "retry-exhausted-settled";
    private static final FsmWarningKey KEY = FsmWarningKey.fsmWarningKey(OPENER, ORDERS.asString());

    private static Option<FsmWarningValue> row(FsmHarness h, FsmWarningKey key) {
        return Option.from(h.snapshot()
                            .entries()
                            .stream()
                            .filter(entry -> entry.key()
                                                  .equals(key))
                            .map(entry -> (FsmWarningValue) entry.value())
                            .findFirst());
    }

    private static boolean openRow(FsmHarness h, FsmWarningKey key) {
        return row(h, key).map(FsmWarningValue::open)
                  .or(false);
    }

    private static ClusterEffect.Submit warningsWrite(FsmHarness h) {
        return h.submits(WARNINGS)
                .getLast();
    }

    /// ORDERS' placement does not parse, every submission is held: the leader opens `invalid-placement`, its WARNINGS write in flight.
    private static FsmHarness openingInFlight() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the leader opened it").isTrue();
        assertThat(h.warns(PLACEMENT)).as("precondition: the opener is held while its write is in flight").isEmpty();

        return h;
    }

    /// The held write commits now, and its notification has not reached the FSM yet: the mirror lags it.
    private static void lateCommit(FsmHarness h, ClusterEffect.Submit write) {
        h.notifying = false;
        assertThat(h.applyHeld(write.correlationId())).as("precondition: the stale write commits")
                  .isInstanceOf(Settlement.Accepted.class);
        h.notifying = true;
    }

    private static void placement(FsmHarness h, String placement) {
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, placement));
        h.drain();
        h.deliver(new ClusterInput.Reconcile());
    }

    /// Every held write is applied (in submission order) and settled with the applier's verdict; later writes apply at once.
    private static void settleAll(FsmHarness h) {
        h.policy = FsmHarness.SettlePolicy.APPLY;
        while (!h.held.isEmpty()) {
            var write = h.held.values().iterator().next();

            h.settleHeld(write,
                         h.applyHeld(write.correlationId()));
        }

        h.deliver(new ClusterInput.Reconcile());
    }

    /// (a) The opening write settles as `Failed` (`ApplyTimeout`), then commits late; the placement is fixed before the mirror sees the
    /// commit. Red when: the close is published at once against the mirror that lacks the open row (`settled`), and nothing is written, so the
    /// committed row stays open while the operator sees "recovered".
    @Test
    void closeAfterAFailedWriteThatCommitsLate_isPublishedOnlyOnceTheRowIsClosed() {
        var h = openingInFlight();
        var opening = warningsWrite(h);

        h.settleHeld(opening, new Settlement.Failed("ApplyTimeout"));
        lateCommit(h, opening);
        placement(h, "CORE_ONLY");
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the leader closed it").isFalse();
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the late commit left the row open").isTrue();
        assertThat(h.warns(PLACEMENT_VALID)).as("no close published while the committed row the mirror lags is open")
                  .isEmpty();
        settleAll(h);
        assertThat(h.warns(PLACEMENT_VALID)).as("the close is published once its write committed").hasSize(1);
        assertThat(h.warns(PLACEMENT)).as("the opener once").hasSize(1);
        assertThat(openRow(h, PLACEMENT_KEY)).as("no open row left").isFalse();
        assertThat(row(h, PLACEMENT_KEY).map(kept -> sameContent(kept, Warnings.CLEAR))
                                        .or(false)).as("the close writes the CLEAR row; the FSM never removes a WARNINGS row (#3274)")
                  .isTrue();
        assertThat(h.model().warningsUnsure.isEmpty()).as("the accepted write cleared the key").isTrue();
    }

    /// The failed write is still held (it may commit at any time); the retry flush commits its row.
    private static void retried(FsmHarness h) {
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advance(h.settings.timing().retryBase());
        h.deliver(new ClusterInput.Reconcile());
    }

    private static boolean sameContent(FsmWarningValue left, FsmWarningValue right) {
        return left.members().equals(right.members()) && left.open() == right.open() && left.opener().equals(right.opener())
               && left.recovery().equals(right.recovery());
    }

    /// The opener of `invalid-placement` committed and published (an open row with no markers), then every submission is held.
    private static FsmHarness openRowCommitted() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the opener committed").isTrue();
        h.policy = FsmHarness.SettlePolicy.HOLD;

        return h;
    }

    /// Fencing by revision: the close of a committed open row is in flight when the condition returns, then fails (`ApplyTimeout`) and stays
    /// in consensus; the retry writes the row open again (no removal on the way), ending on the content the stale close's CAS expects.
    /// Red when: the rows written do not bump the revision, so the committed value equals that CAS's `expected` and the stale close
    /// removes the open row of an open warning.
    @Test
    void staleCloseOfAnOpenRow_cannotCommitAfterTheRowIsWrittenAgainWithTheSameContent() {
        var h = openRowCommitted();
        var before = row(h, PLACEMENT_KEY).unwrap();

        placement(h, "CORE_ONLY");
        var closing = warningsWrite(h);

        placement(h, "NOWHERE");
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(closing, new Settlement.Failed("ApplyTimeout"));
        h.advance(h.settings.timing().retryBase());
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the warning is open").isTrue();
        assertThat(sameContent(row(h, PLACEMENT_KEY).unwrap(), before)).as("precondition: the row holds the content the stale close expects")
                  .isTrue();
        assertThat(h.applyHeld(closing.correlationId())).as("the stale close's CAS expects the old revision").isInstanceOf(Settlement.Refused.class);
        assertThat(openRow(h, PLACEMENT_KEY)).as("the open warning keeps its open row").isTrue();
    }

    /// Fencing by [Warnings#CLEAR]: the opening write of an absent key (CAS expects no row) fails and stays in consensus, its retry commits
    /// the row, the close commits its tombstone while the node reincarnates, and the new incarnation (every key unsure) closes the
    /// inherited tombstone. Red when: the close of the unsure key removes the row, which restores "no row" and lets the stale opening
    /// commit an open row for a closed warning.
    @Test
    void staleOpeningOfAnAbsentKey_cannotCommitAfterTheUnsureKeyIsClosed() {
        var h = openingInFlight();
        var opening = warningsWrite(h);

        h.settleHeld(opening, new Settlement.Failed("ApplyTimeout"));
        retried(h);
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the retry committed the open row").isTrue();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        placement(h, "CORE_ONLY");
        var tombstone = warningsWrite(h);

        h.deliver(new ClusterInput.Deactivate());
        assertThat(h.applyHeld(tombstone.correlationId())).as("precondition: the old incarnation's tombstone commits")
                  .isInstanceOf(Settlement.Accepted.class);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the new incarnation closed it").isFalse();
        assertThat(h.applyHeld(opening.correlationId())).as("the stale opening's CAS expects no row").isInstanceOf(Settlement.Refused.class);
        assertThat(openRow(h, PLACEMENT_KEY)).as("no open row for the closed warning").isFalse();
        assertThat(row(h, PLACEMENT_KEY).map(kept -> sameContent(kept, Warnings.CLEAR))
                                        .or(false)).as("the confirming row is kept").isTrue();
    }

    /// Fencing by never removing (row -> CLEAR -> row): the close of a committed open row fails and stays in consensus, its retry commits
    /// the tombstone and then [Warnings#CLEAR] (the row is never removed), and the condition returns: the row is written again with the
    /// content the stale close's CAS expects, under a later revision. Red when: a close removes the row, so the revision restarts and the
    /// row ends equal, revision included, to the stale close's `expected`, and the stale close removes the open row of an open warning.
    @Test
    void staleCloseOfAnOpenRow_cannotCommitAfterTheRowIsClosedAndWrittenAgain() {
        var h = openRowCommitted();
        var before = row(h, PLACEMENT_KEY).unwrap();

        placement(h, "CORE_ONLY");
        var closing = warningsWrite(h);

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.settleHeld(closing, new Settlement.Failed("ApplyTimeout"));
        h.advance(h.settings.timing().retryBase());
        h.deliver(new ClusterInput.Reconcile());
        assertThat(row(h, PLACEMENT_KEY).map(kept -> sameContent(kept, Warnings.CLEAR))
                                        .or(false)).as("precondition: the retry closed the row with CLEAR, never removed it").isTrue();
        placement(h, "NOWHERE");
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the warning is open again").isTrue();
        assertThat(sameContent(row(h, PLACEMENT_KEY).unwrap(), before)).as("precondition: the row holds the content the stale close expects")
                  .isTrue();
        assertThat(h.applyHeld(closing.correlationId())).as("the stale close's CAS expects the old revision").isInstanceOf(Settlement.Refused.class);
        assertThat(openRow(h, PLACEMENT_KEY)).as("the open warning keeps its open row").isTrue();
    }

    /// Fencing by never removing (none -> row -> CLEAR, no reincarnation): the opening write of an absent key fails and stays in consensus,
    /// its retry is accepted (which clears the key: `confirmed`), then the placement is fixed and the close writes [Warnings#CLEAR]. Red
    /// when: the close removes the row, restoring "no row", the value the stale opening's CAS expects, so the stale opening commits an open
    /// row for a closed warning.
    @Test
    void staleOpeningOfAnAbsentKey_cannotCommitAfterItsRetryAndAClose() {
        var h = staleOpeningAfterRetryAndClose();
        var opening = h.held.values().iterator().next();

        assertThat(h.applyHeld(opening.correlationId())).as("the stale opening's CAS expects no row").isInstanceOf(Settlement.Refused.class);
        assertThat(openRow(h, PLACEMENT_KEY)).as("no open row for the closed warning").isFalse();
    }

    private static FsmHarness staleOpeningAfterRetryAndClose() {
        var h = openingInFlight();
        var opening = warningsWrite(h);

        h.settleHeld(opening, new Settlement.Failed("ApplyTimeout"));
        retried(h);
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the retry committed the open row").isTrue();
        placement(h, "CORE_ONLY");
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the leader closed it").isFalse();
        assertThat(row(h, PLACEMENT_KEY).map(kept -> sameContent(kept, Warnings.CLEAR))
                                        .or(false)).as("precondition: the close wrote CLEAR, never removed the row").isTrue();
        assertThat(h.held.keySet()).as("precondition: only the stale opening is held").containsExactly(opening.correlationId());

        return h;
    }

    /// The opening write settles as `Failed` and commits late, and this time its notification reaches the FSM: the mirror shows the
    /// write's replacement, so the key is current without any further write. Red when: `observed` never clears the key, which then holds
    /// the opener back (its write is only confirmed later) although the mirror already holds the committed row.
    @Test
    void failedWriteThatCommitsLate_clearsItsKeyOnceTheMirrorShowsTheCommit() {
        var h = openingInFlight();
        var opening = warningsWrite(h);

        h.settleHeld(opening, new Settlement.Failed("ApplyTimeout"));
        assertThat(h.model().warningsUnsure.isEmpty()).as("precondition: the failed write left its key unsure")
                  .isFalse();
        h.applyHeld(opening.correlationId());
        h.drain();
        assertThat(h.model().warningsUnsure.isEmpty()).as("the mirror's row is the stale write's replacement: the key is current")
                  .isTrue();
        assertThat(h.warns(PLACEMENT)).as("the opener is published through the settled shortcut").hasSize(1);
    }

    /// (b) The placement is fixed while the opening write is in flight (the close is held), the write commits late, then it expires
    /// unsettled (`Outbox.expire`). Red when: the expiry's step publishes the close against the mirror that lacks the open row.
    @Test
    void closeHeldBehindAnExpiredWriteThatCommitsLate_isPublishedOnlyOnceTheRowIsClosed() {
        var h = openingInFlight();
        var opening = warningsWrite(h);

        placement(h, "CORE_ONLY");
        assertThat(h.warns(PLACEMENT_VALID)).as("precondition: the close is held behind the write in flight").isEmpty();
        lateCommit(h, opening);
        h.advance(h.settings.submissionExpiry() + h.settings.timing().tick());
        assertThat(h.model().submitted.containsKey(opening.correlationId())).as("precondition: the opening write expired")
                  .isFalse();
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the late commit left the row open").isTrue();
        assertThat(h.warns(PLACEMENT_VALID)).as("no close published while the committed row the mirror lags is open")
                  .isEmpty();
        settleAll(h);
        assertThat(h.warns(PLACEMENT_VALID)).as("the close is published once its write committed").hasSize(1);
        assertThat(openRow(h, PLACEMENT_KEY)).as("no open row left").isFalse();
    }

    /// (c) Restart: the leader's close of a committed open row is in flight when the condition returns, the node reincarnates (`Deactivate`,
    /// `Activate`) and adopts the open row from the snapshot, then the old incarnation's close commits (its settlement is dropped as another
    /// incarnation's) and the mirror lags it. Red when: the new incarnation, whose family set agrees with the stale mirror, writes nothing, so
    /// the committed row stays closed while the warning is open (a successor would inherit no open row and the operator would never see the
    /// warning closed or raised again).
    @Test
    void oldIncarnationsCloseCommittingAfterActivate_doesNotLeaveTheOpenWarningUncommitted() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.activate();
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the opener committed").isTrue();
        h.policy = FsmHarness.SettlePolicy.HOLD;
        placement(h, "CORE_ONLY");
        var closing = warningsWrite(h);

        placement(h, "NOWHERE");
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the condition returned")
                  .isTrue();
        h.deliver(new ClusterInput.Deactivate());
        h.activate();
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the new incarnation adopted the open row")
                  .isTrue();
        lateCommit(h, closing);
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the old incarnation's close committed").isFalse();
        settleAll(h);
        h.deliver(new ClusterInput.Reconcile());
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("the warning is open").isTrue();
        assertThat(openRow(h, PLACEMENT_KEY)).as("and its committed row is open again").isTrue();
        assertThat(h.model().warningsUnsure.isEmpty()).as("the rebuilt keys are current once the write is accepted")
                  .isTrue();
    }

    /// `ORDERS` one failure short on core-1, with the committed leader naming the old leader (core-2): this node rebuilds suspended. `value`,
    /// when present, is committed at [#KEY] before the rebuild.
    private static FsmHarness suspendedSuccessor(Option<FsmWarningValue> value) {
        var h = LcSupport.leader(DeploymentAtomicity.ALL_OR_NOTHING, 0.5d, CORE_1);

        SlFixtures.seedTarget(h, ORDERS, 1, 1);
        SlFixtures.seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, SlFixtures.G1);
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.LOADING, SlFixtures.G1, 11L);
        SlFixtures.seedJudgment(h,
                                ORDERS,
                                new ArtifactJudgmentValue(false, Map.of(CORE_1, 5), Map.of(), Set.of()));
        h.commitLeader(CORE_2, 2L);
        value.onPresent(present -> h.seed(KEY, present));
        h.activate();
        h.policy = FsmHarness.SettlePolicy.HOLD;

        return h;
    }

    private static void resume(FsmHarness h) {
        h.commitLeader(FsmHarness.SELF, 3L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.deliver(new ClusterInput.Reconcile());
        h.drain();
    }

    /// R2: the confirming row ([Warnings#CLEAR]: closed, no markers) is "no warning". Red when: a successor inheriting it publishes anything,
    /// adopts the key, or fails to raise and publish the warning normally afterwards.
    @Test
    void inheritedConfirmingRow_showsNothing_andTheWarningIsRaisedNormally() {
        var h = suspendedSuccessor(Option.some(Warnings.CLEAR));

        resume(h);
        assertThat(h.warns(OPENER)).as("nothing replayed").isEmpty();
        assertThat(h.warns(RECOVERY)).as("no recovery replayed").isEmpty();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("not adopted").isFalse();
        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.warns(OPENER)).as("raised and published once its row commits").hasSize(1);
        assertThat(openRow(h, KEY)).as("its row is open").isTrue();
        assertThat(h.model().warningsUnsure.isEmpty()).as("the inherited key is current once a write of this node touched it")
                  .isTrue();
    }

    /// R3 (known transient): a stale write of a key the mirror never held (the previous leader's open row with an opener marker, committed
    /// after this node's `Rebuild`, the condition absent) is not covered by the unsure keys. The row is shown open until the next flush
    /// closes it with [Warnings#CLEAR]. Red when: the row stays open, or this node publishes a warning or a recovery for it.
    @Test
    void staleOpenRowOfAKeyAbsentFromTheMirror_isClosedByTheNextFlush() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.activate();
        h.seed(PLACEMENT_KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("stale opener"), Option.none()));
        h.drain();
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the stale row is committed open (the transient)")
                  .isTrue();
        h.deliver(new ClusterInput.Reconcile());
        assertThat(openRow(h, PLACEMENT_KEY)).as("the next flush closed it").isFalse();
        assertThat(row(h, PLACEMENT_KEY).map(kept -> sameContent(kept, Warnings.CLEAR))
                                        .or(false)).as("with the CLEAR row, not a removal (#3274)").isTrue();
        assertThat(h.warns(PLACEMENT)).as("this node never opened it").isEmpty();
        assertThat(h.warns(PLACEMENT_VALID)).as("nor closed it").isEmpty();
    }

    /// Item 3 of #3274 (the `onPut` path): the successor raises `e` while suspended, then the deposed leader's row carrying both markers
    /// (`[e up, e down]`, a tombstone) commits after its `Rebuild`, and the slice is ACTIVE again. Red when: `e` stays in the successor's
    /// family set (a tombstone does not un-adopt), so its row stays open and a recurrence is never raised.
    @Disabled("#3274 item 3 reproduces at rc5 (onPut keeps e in the family set on a tombstone); enable when the later lane fixes onPut")
    @Test
    void replayedUpAndDownMarkers_overASuccessorsOwnRaise_leaveNoOpenWarning() {
        var h = replayedTombstone();

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("e is closed").isFalse();
        assertThat(openRow(h, KEY)).as("no open row left").isFalse();
    }

    /// Tripwire for item 3: asserts the CURRENT (defective) behaviour; when it goes red, onPut was fixed: enable the test above and delete this one.
    @Test
    void replayedUpAndDownMarkers_currentBehaviour_eStaysInTheFamilySet() {
        var h = replayedTombstone();

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("known defect: e stays adopted").isTrue();
    }

    private static FsmHarness replayedTombstone() {
        var h = suspendedSuccessor(Option.none());

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the successor raised e while suspended")
                  .isTrue();
        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("up"), Option.some("down")));
        SlFixtures.seedRow(h, CORE_1, ORDERS, SliceObservedState.ACTIVE, SlFixtures.G1, 12L);
        h.drain();
        resume(h);
        h.deliver(new ClusterInput.Reconcile());

        return h;
    }
}
