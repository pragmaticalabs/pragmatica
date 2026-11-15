// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

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
    private static final FsmWarningKey UNJUDGEABLE_KEY = FsmWarningKey.fsmWarningKey(Warnings.UNJUDGEABLE, "c1");
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

    /// (a, mirrored key) The close of a committed open row settles as `Failed` (`ApplyTimeout`) and stays in consensus; the condition returns
    /// in the same drain, before the retry flush, so the family set again equals the mirror (open), whose key is present. Red when: the close
    /// and the re-raise are published at once although the stale close may still commit (the unsure key does not gate `settled`).
    @Test
    void reRaiseBehindAFailedCloseOfAMirroredKey_isPublishedOnlyOnceAWriteOfThisNodeLands() {
        var h = openRowCommitted();

        placement(h, "CORE_ONLY");
        var closing = warningsWrite(h);

        h.settle(closing, new Settlement.Failed("ApplyTimeout"));
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.mailbox.addLast(new ClusterInput.Reconcile());
        h.drain();
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the condition returned").isTrue();
        assertThat(h.model().warningsUnsure.isEmpty()).as("precondition: the failed close left its key unsure").isFalse();
        assertThat(h.warns(PLACEMENT_VALID)).as("no close published while the stale close may commit").isEmpty();
        assertThat(h.warns(PLACEMENT)).as("only the first opener").hasSize(1);
        settleAll(h);
        assertThat(h.warns(PLACEMENT_VALID)).as("the close once a write of this node landed").hasSize(1);
        assertThat(h.warns(PLACEMENT)).as("and the re-raise").hasSize(2);
        assertThat(openRow(h, PLACEMENT_KEY)).as("the committed row is open, as the warning is").isTrue();
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

    /// (b, mirrored key) The close of a committed open row is in flight when the condition returns (both held), then it expires unsettled:
    /// the family set again equals the mirror (open), whose key is present. Red when: the expiry's step publishes the close and the re-raise
    /// although the expired close may still commit (the expiry does not leave the key unsure).
    @Test
    void reRaiseBehindAnExpiredCloseOfAMirroredKey_isPublishedOnlyOnceAWriteOfThisNodeLands() {
        var h = openRowCommitted();

        placement(h, "CORE_ONLY");
        var closing = warningsWrite(h);

        placement(h, "NOWHERE");
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the condition returned").isTrue();
        h.advance(h.settings.submissionExpiry() + h.settings.timing().tick());
        assertThat(h.model().submitted.containsKey(closing.correlationId())).as("precondition: the closing write expired")
                  .isFalse();
        assertThat(h.warns(PLACEMENT_VALID)).as("no close published while the expired close may commit").isEmpty();
        assertThat(h.warns(PLACEMENT)).as("only the first opener").hasSize(1);
        settleAll(h);
        assertThat(h.warns(PLACEMENT_VALID)).as("the close once a write of this node landed").hasSize(1);
        assertThat(h.warns(PLACEMENT)).as("and the re-raise").hasSize(2);
        assertThat(openRow(h, PLACEMENT_KEY)).as("the committed row is open, as the warning is").isTrue();
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

    /// (c, absent key) The old incarnation's opening of `invalid-placement` is in flight (never settled) when the node reincarnates; the
    /// placement was fixed meanwhile, so the new incarnation rebuilds from a snapshot without the row and has nothing to write. Returns the
    /// old incarnation's write, still held: it may commit at any time.
    private static ClusterEffect.Submit rebuiltWithoutTheRow(FsmHarness h) {
        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var orphan = warningsWrite(h);

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.deliver(new ClusterInput.Deactivate());
        h.activate();
        assertThat(row(h, PLACEMENT_KEY).isEmpty()).as("precondition: the snapshot lacks the row").isTrue();
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: nothing adopted").isFalse();
        assertThat(h.model().warningsUnsure.isEmpty()).as("precondition: no mirrored key to doubt").isTrue();
        assertThat(h.submits(WARNINGS)).as("precondition: the new incarnation wrote nothing").hasSize(1);

        return orphan;
    }

    /// The new incarnation raises and closes the warning before its flush: both steps run before the queued `Flush`.
    private static void raisedAndClosedBeforeTheFlush(FsmHarness h) {
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.mailbox.addLast(new ClusterInput.Reconcile());
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.mailbox.addLast(new ClusterInput.Reconcile());
        h.drain();
        assertThat(h.model().invalidPlacementWarned.contains(ORDERS)).as("precondition: the leader closed it").isFalse();
    }

    /// (c, absent key; red3274c) No settlement of any WARNINGS write. Red when: the raise and the close are published in the close step
    /// (`settled` compared two empty open sets against a mirror that cannot see the old incarnation's batch) and nothing is written, so a
    /// late commit of that batch leaves the row open while the operator saw "recovered".
    @Test
    void raiseAndCloseOfAKeyAbsentFromTheRebuiltMirror_publishNothingBeforeAWriteTouchingTheKeyIsAccepted() {
        var h = threeCores();

        rebuiltWithoutTheRow(h);
        raisedAndClosedBeforeTheFlush(h);
        assertThat(h.warns(PLACEMENT)).as("no opener published before a write touching the key is accepted").isEmpty();
        assertThat(h.warns(PLACEMENT_VALID)).as("no close published before a write touching the key is accepted").isEmpty();
        var write = warningsWrite(h);

        assertThat(h.submits(WARNINGS)).as("the new incarnation writes the key").hasSize(2);
        assertThat(write.transaction()
                        .mutations()
                        .stream()
                        .anyMatch(mutation -> mutation.key()
                                                      .equals(PLACEMENT_KEY) && mutation.expected()
                                                                                        .isEmpty())).as("with the expected value \"no row\"")
                  .isTrue();
    }

    /// The old incarnation's batch committed before the new incarnation's write (the mirror never saw it): that write's CAS ("no row") is
    /// refused, the refusal re-indexes the committed row, and the re-draft writes [Warnings#CLEAR] over it. Red when: the warnings are
    /// published before that write is accepted, or the committed row stays open.
    @Test
    void orphanBatchCommittedFirst_isOverwrittenByTheClose_beforeTheWarningsArePublished() {
        var h = threeCores();
        var orphan = rebuiltWithoutTheRow(h);

        raisedAndClosedBeforeTheFlush(h);
        lateCommit(h, orphan);
        assertThat(openRow(h, PLACEMENT_KEY)).as("precondition: the orphan batch committed the open row").isTrue();
        assertThat(h.warns(PLACEMENT_VALID)).as("nothing published while the row the mirror lacks is open").isEmpty();
        settleAll(h);
        assertThat(h.warns(PLACEMENT)).as("the opener once").hasSize(1);
        assertThat(h.warns(PLACEMENT_VALID)).as("the close once, after its write committed").hasSize(1);
        assertThat(openRow(h, PLACEMENT_KEY)).as("no open row left").isFalse();
        assertThat(row(h, PLACEMENT_KEY).map(FsmWarningValue::revision)
                                        .or(0L)).as("written over the orphan's row (revision 1)").isGreaterThan(1L);
    }

    /// The orphan batch committed, then the `LeaderKey` moved to core-2 before the new incarnation's write applied (this node has not seen
    /// it): the leader fence refuses that write. Red when: this node publishes the opener or the close; the committed row stays open with
    /// its opener marker, which core-2 inherits and replays.
    @Test
    void orphanBatchCommitted_thenLeaderMoved_publishesNothing() {
        var h = threeCores();
        var orphan = rebuiltWithoutTheRow(h);

        raisedAndClosedBeforeTheFlush(h);
        lateCommit(h, orphan);
        h.notifying = false;
        h.commitLeader(CORE_2, 100L);
        h.notifying = true;
        settleAll(h);
        assertThat(h.warns(PLACEMENT)).as("no opener published by the deposed node").isEmpty();
        assertThat(h.warns(PLACEMENT_VALID)).as("no close published by the deposed node").isEmpty();
        assertThat(openRow(h, PLACEMENT_KEY)).as("the committed row stays open for core-2 to inherit").isTrue();
        assertThat(row(h, PLACEMENT_KEY).flatMap(FsmWarningValue::opener)
                                        .isPresent()).as("with the opener marker core-2 replays").isTrue();
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

    private static final FsmWarningValue STALE_OPEN = FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("stale"), Option.none(), 1L);

    private static ClusterModel.TxDraft failedWrite(FsmWarningValue replacement) {
        return ClusterModel.TxDraft.draft(WARNINGS,
                                          KEY,
                                          List.of(new KVCommand.Mutation<AetherKey, AetherValue>(KEY,
                                                                                                 Option.none(),
                                                                                                 Option.some(replacement))));
    }

    /// An unsure key is cleared only by the value the stale write would have left. Red when: any mirror row clears it (`observed` compares
    /// the write's replacement with itself), so a stale write can still commit over a key believed current.
    @Test
    void unsureKey_staysUnsureWhileTheMirrorShowsAValueOtherThanTheStaleWritesReplacement() {
        var m = new ClusterModel();

        Warnings.unconfirmed(m, failedWrite(STALE_OPEN));
        Warnings.indexRow(m, KEY, Warnings.CLEAR);
        assertThat(m.warningsUnsure.containsKey(KEY)).as("a different value does not show the stale write committed").isTrue();
        Warnings.indexRow(m, KEY, STALE_OPEN);
        assertThat(m.warningsUnsure.containsKey(KEY)).as("its own replacement does").isFalse();
    }

    /// Two unsettled writes of one key with different replacements: either may commit, so one observed value settles neither. Red when:
    /// the later write's row replaces the doubt (`unsure` merge keeps the row), so its replacement showing in the mirror clears the key
    /// while the earlier write may still commit.
    @Test
    void keyOfTwoDisagreeingUnsettledWrites_isNotClearedByOneObservedReplacement() {
        var m = new ClusterModel();

        Warnings.unconfirmed(m, failedWrite(STALE_OPEN));
        Warnings.unconfirmed(m, failedWrite(Warnings.CLEAR));
        Warnings.indexRow(m, KEY, Warnings.CLEAR);
        assertThat(m.warningsUnsure.containsKey(KEY)).as("the other write may still commit").isTrue();
    }

    /// The confirming write of an unsure key whose family set still wants its row open (the new incarnation adopted a marker-free open row and
    /// the condition holds) rewrites that row, not [Warnings#CLEAR]. Red when: the confirming write always closes: a live warning is closed
    /// in the KV store while the family set holds it open.
    @Test
    void confirmingWriteOfAnUnsureKeyWhoseWarningIsOpen_keepsTheRowOpen() {
        var m = new ClusterModel();
        var ctx = SurvivorPinSupport.ctx(1_000L, 0L);

        Warnings.indexRow(m, UNJUDGEABLE_KEY, FsmWarningValue.fsmWarningValue(Map.of(), true, Option.none(), Option.none(), 1L));
        Warnings.inherit(m, ctx);

        assertThat(m.warningsUnsure.containsKey(UNJUDGEABLE_KEY)).as("precondition: Rebuild marked the mirrored key unsure").isTrue();
        assertThat(Warnings.difference(m)).as("the confirming write keeps the row open, under the next revision")
                                          .singleElement()
                                          .satisfies(mutation -> assertThat(mutation.replacement())
                                              .isEqualTo(Option.some(FsmWarningValue.fsmWarningValue(Map.of(), true, Option.none(), Option.none(), 2L))));
    }

    /// An unsure key that neither family set names and the mirror lacks (its opening failed and never committed, then the condition went
    /// away) has nothing to write; it still needs its confirming write, or `settled` is false for ever. Red when: `current` lacks the
    /// absent-from-both case: no write is drafted and the key stays unsure.
    @Test
    void unsureKeyAbsentFromBothTheFamilySetsAndTheMirror_isConfirmedByAClose() {
        var m = new ClusterModel();

        Warnings.unconfirmed(m, failedWrite(STALE_OPEN));

        assertThat(Warnings.difference(m)).as("a CLEAR row over \"no row\"")
                                          .containsExactly(new KVCommand.Mutation<AetherKey, AetherValue>(KEY,
                                                                                                          Option.none(),
                                                                                                          Option.some(FsmWarningValue.fsmWarningValue(Map.of(), false, Option.none(), Option.none(), 1L))));
    }

    /// Item 3 of #3274 (the `onPut` path), the condition gone: the successor raises `e` while suspended, then the deposed leader's row
    /// carrying both markers (`[e up, e down]`, a tombstone) commits after its `Rebuild`, and the slice reports ACTIVE again. Red when: `e`
    /// stays in the successor's family set, so its row stays open. (The earlier tripwire that showed `e` staying adopted was a test-setup
    /// error: the ACTIVE row was seeded without a notification, so the FSM never saw it and the failure sweep could not close `e`.)
    @Test
    void replayedUpAndDownMarkers_overASuccessorsOwnRaise_leaveNoOpenWarning() {
        var h = replayedTombstone();

        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("e is closed").isFalse();
        assertThat(openRow(h, KEY)).as("no open row left").isFalse();
        assertThat(publishedRetryEvents(h).getLast()).as("the last event published for e is its close").isEqualTo(RECOVERY);
    }

    private static FsmHarness replayedTombstone() {
        var h = suspendedSuccessor(Option.none());

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the successor raised e while suspended")
                  .isTrue();
        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("up"), Option.some("down")));
        LcSupport.node(h, CORE_1, 11L).writes(ORDERS, SliceObservedState.ACTIVE);
        h.drain();
        resume(h);
        h.deliver(new ClusterInput.Reconcile());

        return h;
    }

    /// The codes of the published warnings of the retry family for ORDERS, in publication order.
    private static List<String> publishedRetryEvents(FsmHarness h) {
        return h.effects(ClusterEffect.Warn.class)
                .stream()
                .filter(warn -> warn.subject()
                                    .equals(ORDERS.asString()) && (warn.code()
                                                                       .equals(OPENER) || warn.code()
                                                                                              .equals(RECOVERY)))
                .map(ClusterEffect.Warn::code)
                .toList();
    }

    /// Item 3 (the tla3274c trace): the successor raises `e` while suspended (held), then the deposed leader's late batch commits the row
    /// with both markers (`[e up, e down]`), the condition still holding `e`; the successor resumes and its write lands. The late row is
    /// older in commit order than the successor's own raise, so its markers are published first. Red when: the replayed markers are held
    /// after the own raise, so the last event published for `e` is its recovery while the condition, the family set and the row hold it open.
    @Test
    void lateReplayOfAnOlderEpisode_isPublishedBeforeTheSuccessorsOwnRaise() {
        var h = suspendedSuccessor(Option.none());

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the successor raised e while suspended")
                  .isTrue();
        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("up"), Option.some("down")));
        h.drain();
        resume(h);
        h.deliver(new ClusterInput.Reconcile());
        var events = publishedRetryEvents(h);

        assertThat(events).as("the late episode and the own raise are all published").hasSize(3);
        assertThat(events.getLast()).as("the last event published for e is the successor's own raise").isEqualTo(OPENER);
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("e stays in the family set").isTrue();
        assertThat(openRow(h, KEY)).as("the committed row is open").isTrue();
    }

    /// Item 3, deposed without a `Rebuild`: the leader's write carrying its raise of `e` is accepted after the committed `LeaderKey` moved
    /// (the accepted prefix is kept, `warningsLanded`), then the new leader's row closing `e` commits and is replayed late. That row is
    /// newer than the accepted write, so it is held after the accepted prefix. Red when: the late replay is inserted before the first held
    /// warning of the key whatever an accepted write carried, so the recovery is published before the raise it closes.
    @Test
    void lateReplayAfterAnAcceptedPrefix_isHeldAfterThatPrefix() {
        var h = suspendedSuccessor(Option.none());

        resume(h);
        h.policy = FsmHarness.SettlePolicy.HOLD;
        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        var raise = warningsWrite(h);

        assertThat(h.applyHeld(raise.correlationId())).as("precondition: the raise's write commits").isInstanceOf(Settlement.Accepted.class);
        h.commitLeader(CORE_2, 4L);
        h.drain();
        h.settleHeld(raise, new Settlement.Accepted());
        assertThat(h.model().warningsLanded).as("precondition: the accepted prefix is kept while another node leads").isPositive();
        assertThat(publishedRetryEvents(h)).as("precondition: nothing published while another node leads").isEmpty();
        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), false, Option.none(), Option.some("down"), 50L));
        h.drain();
        h.commitLeader(FsmHarness.SELF, 5L);
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.deliver(new ClusterInput.Reconcile());
        var events = publishedRetryEvents(h);

        assertThat(events).as("the raise and the new leader's recovery are published").isNotEmpty();
        assertThat(events.getFirst()).as("the raise the accepted write committed comes first").isEqualTo(OPENER);
        assertThat(events).as("the replayed recovery follows it").containsSubsequence(OPENER, RECOVERY);
    }

    /// Item 3b (the tla3274d trace): while the successor is suspended, the deposed leader's two rows of `e` commit and are replayed late,
    /// in commit order: first a tombstone (`[e up, e down]`), then an open row (`[e up]`); the condition holds `e` and the successor
    /// resumes. Red when: the second replay is inserted before the first one's entries (a per-key search that cannot tell a late replay
    /// from an own warning), so `up, up, down` is published while the row is open.
    @Test
    void twoLateReplaysOfOneKey_arePublishedInCommitOrder() {
        var h = suspendedSuccessor(Option.none());

        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("up"), Option.some("down"), 1L));
        h.drain();
        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("up again"), Option.none(), 2L));
        h.drain();
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("precondition: the open row is adopted").isTrue();
        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().warningsHeld).as("precondition: the condition raises nothing new over the adopted key").hasSize(3);
        resume(h);
        h.deliver(new ClusterInput.Reconcile());

        assertThat(publishedRetryEvents(h)).as("both late episodes, in commit order").containsExactly(OPENER, RECOVERY, OPENER);
        assertThat(h.model().retryExhaustedWarned.contains(ORDERS)).as("e stays in the family set").isTrue();
        assertThat(openRow(h, KEY)).as("the committed row is open").isTrue();
    }

    /// Item 3b, across keys: the successor raises `e` (its own warning, unlanded) while suspended, then the deposed leader's open row of
    /// another key (`invalid-placement`) commits and is replayed late. The late row is older in commit order than every own unlanded
    /// warning, whatever its key. Red when: the replay is held after the own raise (appended, or searched for per key).
    @Test
    void lateReplayOfAnotherKey_isPublishedBeforeTheSuccessorsOwnUnlandedWarning() {
        var h = suspendedSuccessor(Option.none());

        LcSupport.node(h, CORE_1, 11L).fails(ORDERS, false);
        h.drain();
        assertThat(h.model().warningsHeld).as("precondition: the own raise of e is held").hasSize(1);
        h.seed(PLACEMENT_KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("late placement"), Option.none(), 1L));
        h.drain();
        assertThat(h.model().warningsHeld).as("precondition: the late placement opener is held").hasSize(2);
        resume(h);
        var codes = h.effects(ClusterEffect.Warn.class)
                     .stream()
                     .map(ClusterEffect.Warn::code)
                     .filter(code -> code.equals(PLACEMENT) || code.equals(OPENER))
                     .toList();

        assertThat(codes).as("the late replay is published before the own raise").startsWith(PLACEMENT, OPENER);
    }

    /// Item 3b, the boundary after a publish: a late replay is published at the resume, then the node is deposed again (no `Rebuild`) and
    /// another late row commits. Red when: `publish` drops the published prefix but leaves the late-replay boundary where it was, past the
    /// end of the emptied held list, so the next late replay is inserted out of range.
    @Test
    void lateReplayAfterAPublishedReplay_isHeldAtThePublishedBoundary() {
        var h = suspendedSuccessor(Option.none());

        h.seed(KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), false, Option.some("up"), Option.some("down"), 1L));
        h.drain();
        resume(h);
        assertThat(publishedRetryEvents(h)).as("precondition: the first late replay is published").containsExactly(OPENER, RECOVERY);
        assertThat(h.model().warningsHeld).as("precondition: nothing is held after the resume").isEmpty();
        h.commitLeader(CORE_2, 4L);
        h.drain();
        h.seed(PLACEMENT_KEY,
               FsmWarningValue.fsmWarningValue(Map.of(), true, Option.some("late placement"), Option.none(), 1L));
        h.drain();
        assertThat(h.model().warningsHeld).as("the second late replay is held").hasSize(1);
        h.commitLeader(FsmHarness.SELF, 5L);
        h.deliver(new ClusterInput.Reconcile());
        h.drain();

        assertThat(h.warns(PLACEMENT)).as("the second late replay is published at the next resume").isNotEmpty();
    }
}
