package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind.WARNINGS;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #2834: the open set of the leader-local warnings is a committed KV mirror (`FsmWarningKey`), reconciled by one WARNINGS transaction per
/// drain. Scenario: an unparsable placement opens `invalid-placement`; fixing it closes it.
class WarningsPersistenceTest {
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
}
