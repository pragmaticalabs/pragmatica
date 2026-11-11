package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.Submitted;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;

import org.pragmatica.aether.slice.kvstore.AetherValue.FsmWarningValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;


import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #2834: the open set of the leader-local warnings is a committed KV mirror (`FsmWarningKey`), reconciled by one WARNINGS transaction per
/// drain. Scenario: an unparsable placement opens `invalid-placement`; fixing it closes it.
class WarningsPersistenceTest {
    private static final LeaderValue LEADER = new LeaderValue(FsmHarness.SELF, 1L);
    private static final FsmWarningKey KEY = new FsmWarningKey("invalid-placement", ORDERS.asString());

    private static FsmHarness opened() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.activate();

        return h;
    }

    private static boolean persisted(FsmHarness h) {
        return h.snapshot()
                .entries()
                .stream()
                .anyMatch(entry -> entry.key()
                                        .equals(KEY));
    }

    /// Red when: the open warning is not written (no put), or its close is not removed from the committed set.
    @Test
    void openIsPut_andCloseIsRemoved() {
        var h = opened();

        assertThat(h.warningPuts()).containsExactly(KEY);
        assertThat(persisted(h)).as("the open warning is committed").isTrue();

        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.drain();
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.warns("placement-valid")).hasSize(1);
        assertThat(h.mutations(FsmWarningKey.class)).as("one put, then one removal").hasSize(2);
        assertThat(h.mutations(FsmWarningKey.class).getLast().replacement().isPresent()).as("the last mutation removes").isFalse();
        assertThat(persisted(h)).as("the closed warning left the committed set").isFalse();
    }

    /// Red when: an unchanged open set is written again on later drains (churn), or the committed mirror is not kept.
    @Test
    void unchangedOpenSet_isWrittenOnce() {
        var h = opened();

        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());
        h.drain();

        assertThat(h.submits(WARNINGS)).hasSize(1);
    }

    /// Red when: a refused WARNINGS transaction is not drafted again, so the open warning is never committed (a successor would not
    /// inherit it).
    @Test
    void refusedWarningsTransaction_isDraftedAgain() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var first = h.submits(WARNINGS).getFirst();

        h.settleHeld(first, h.refused(first));

        assertThat(h.submits(WARNINGS)).as("drafted again after the refusal").hasSize(2);
        assertThat(persisted(h)).as("nothing was committed by the refused one").isFalse();

        var second = h.submits(WARNINGS).getLast();

        h.settleHeld(second, h.applyTransaction(second));

        assertThat(persisted(h)).isTrue();
        assertThat(h.submits(WARNINGS)).as("no third write once accepted").hasSize(2);
    }

    /// Red when: an accepted WARNINGS transaction does not update the mirror at once (before the applier's notification): the next reconcile
    /// drafts the same write again. The acceptance is settled here without the commit, so only the acceptance can move the mirror.
    @Test
    void acceptedWarningsTransaction_updatesTheMirror_beforeTheNotification() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        var first = h.submits(WARNINGS).getFirst();

        h.settleHeld(first, new Settlement.Accepted());
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.submits(WARNINGS)).as("the accepted write is not drafted again").hasSize(1);
    }

    /// Red when: a second WARNINGS transaction is drafted while one is still in flight (the in-flight guard of `pending` / `draft`): the
    /// reconcile marks the set dirty again, but the mirror still lacks the held write, so the same difference would be sent twice.
    @Test
    void warningsTransactionInFlight_isNotDraftedAgain() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        h.deliver(new ClusterInput.Reconcile());

        assertThat(h.submits(WARNINGS)).as("one in flight, none drafted beside it").hasSize(1);
    }

    /// Red when: a step that changed nothing leaves the open set dirty (the clean-again clause of `noteRaised`): a no-op flush would be queued.
    @Test
    void stepThatChangesNothing_leavesTheOpenSetClean() {
        var m = new ClusterModel();

        m.warningsDirty = true;
        Warnings.noteRaised(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(Warnings.pending(m)).isFalse();
    }

    /// Red when: a flush while suspended leaves the open set dirty (it would draft on the first flush after the resume without the reconcile).
    @Test
    void flushWhileSuspended_clearsTheDirtyMark() {
        var m = new ClusterModel();

        m.warningsDirty = true;
        Outbox.flush(m, SurvivorPinSupport.ctx(1_000L, 0L), true);

        assertThat(m.warningsDirty).isFalse();
    }

    /// Red when: a WARNINGS submission that expired (lost) does not mark the open set dirty, so only the next reconcile would write it again.
    @Test
    void expiredWarningsTransaction_marksTheOpenSetDirty() {
        var m = new ClusterModel();
        var draft = TxDraft.draft(WARNINGS, KEY, List.of());
        var bound = SurvivorPinSupport.settings().submissionExpiry() * 1_000_000L;

        m.submitted = m.submitted.put("c1", new Submitted("c1", WARNINGS, List.of(draft), 1L, LEADER, 5L));
        Outbox.expire(m, SurvivorPinSupport.ctx(1_000L, 5L + bound));

        assertThat(m.warningsDirty).isTrue();
    }

    /// Red when: the reconcile pass does not mark the open set dirty (the level-triggered backstop that drafts a failed WARNINGS write again).
    @Test
    void reconcilePass_marksTheOpenSetDirty() {
        var m = new ClusterModel();

        Reconcile.run(m, SurvivorPinSupport.ctx(1_000L, 0L));

        assertThat(m.warningsDirty).isTrue();
    }

    /// Red when: the mirror view the refusal logic compares against (`Outbox.mirrored`) does not read the committed open set: a write whose
    /// expected value is the mirror's own would count as moved, and a refusal would never correct the mirror.
    @Test
    void mirroredView_readsTheCommittedOpenSet() {
        var m = new ClusterModel();
        var row = FsmWarningValue.fsmWarningValue(Map.of());

        assertThat(Outbox.mirrored(m, KEY)).as("absent row").isEqualTo(Option.none());

        Warnings.indexRow(m, KEY, row);

        assertThat(Outbox.mirrored(m, KEY)).as("held row").isEqualTo(Option.some(row));
    }
}
