// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.TerminationRetirement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// The readers of a retired identity (phase-3 design 12 item 4b; plan G1-G3): once a termination authority (CPR COMPLETE, the CTM's confirmed
/// reap B18, the operator `--force` clear B19) removed a departed node's directive and departure record in ONE transaction, the cluster FSM
/// mirrors neither, and its R-4.3.3 directive-removal fallback and its GC have nothing left to collect. Red when: the model keeps either key, or a
/// reconcile after the retirement submits a directive removal or a GC touching the node.
class RetiredIdentityReadersTest {
    private static final long TOKEN_2 = 0x2222L;

    /// core-2 departed and its rows were cleaned up (C17, C17b): only its directive and its DEPARTED record remain.
    private static MbWorld departedAndCleaned() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.target(ORDERS, 1, "CORE_ONLY")
         .directive(CORE_2, ActivationDirectiveValue.core())
         .instance(CORE_2, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), TOKEN_2)
         .activate();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.drain();

        return w;
    }

    @Test
    void aRetiredIdentity_leavesTheModel_andNothingIsLeftToCollect() {
        var w = departedAndCleaned();

        assertThat(w.present(new SliceObservedKey(CORE_2, ORDERS))).as("precondition: C17b ran").isFalse();
        assertThat(w.recordOf(CORE_2).map(record -> record.state())).as("precondition: departed").isEqualTo(Option.some(DepartureState.DEPARTED));
        var retirement = TerminationRetirement.transactionFor(w.h.store, CORE_2).unwrap();

        w.h.clearLog();
        assertThat(((KVCommand.TransactionResult) w.h.applyCommand(retirement)).accepted()).as("the retirement committed").isTrue();
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.drain();

        assertThat(w.h.model().directives.get(CORE_2).isPresent()).as("the directive mirror").isFalse();
        assertThat(w.h.model().records.get(CORE_2).isPresent()).as("the record mirror").isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).as("R-4.3.3 has nothing to do").isEmpty();
        assertThat(w.h.submits(SubmitKind.GC)
                      .stream()
                      .flatMap(submit -> submit.transaction().mutations().stream())
                      .map(KVCommand.Mutation::key)
                      .filter(key -> key.equals(new NodeDepartureKey(CORE_2)) || key.equals(new ActivationDirectiveKey(CORE_2))))
                      .as("no GC of the retired identity")
                      .isEmpty();
    }
}
