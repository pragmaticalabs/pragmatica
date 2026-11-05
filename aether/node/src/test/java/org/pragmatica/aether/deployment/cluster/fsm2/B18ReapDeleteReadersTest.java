// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.TerminationRetirement;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVNotificationRouter;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// **A notification-count pin** for the worker-side reader of a retired directive (P3-F plan §2 G1, B18): every node's community directory
/// reacts to an `ActivationDirectiveKey`
/// removal through an `onRemove` route (`WorkerHierarchyAssembly`, the `communityDirectoryRouter`). When the CTM's confirmed reap retires a
/// departed node's directive and departure record, that reaction must fire once, and never twice: not again for the record's removal, not for
/// a second retirement (a later `--force` clear, or the retry), and not for the cluster FSM's R-4.3.3 fallback.
///
/// What it pins is the number of `ActivationDirectiveKey` removal notifications the store publishes for the retired node, through a reader
/// registered the way the assembly registers its own (a `KVNotificationRouter` `onRemove(ActivationDirectiveKey)` route). It does not drive
/// `WorkerHierarchyAssembly`'s lambda itself, which reacts to each such notification once (it is a plain route on the same router).
/// Red when: the store publishes zero, or more than one, removal of the retired directive.
class B18ReapDeleteReadersTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final Retry ONE_ATTEMPT = Retry.retry()
                                                 .attempts(1)
                                                 .strategy(BackoffStrategy.fixed()
                                                                          .interval(TimeSpan.timeSpan(1).millis()));

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

    /// The community directory's route, registered the way the assembly registers it; it records every node whose directive was removed.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<NodeId> directoryReader(MessageRouter.MutableRouter router) {
        var removed = new ArrayList<NodeId>();
        var routes = KVNotificationRouter.<AetherKey, AetherValue> builder(AetherKey.class)
                                         .onRemove(ActivationDirectiveKey.class,
                                                   (ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> remove) -> removed.add(remove.cause()
                                                                                                                                            .key()
                                                                                                                                            .nodeId()))
                                         .build();

        routes.asRouteEntries()
              .forEach(entry -> entry.entries()
                                     .forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last())));

        return removed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TerminationRetirement retirementOver(FsmHarness h) {
        return TerminationRetirement.directiveAndRecord(h.store,
                                                        commands -> Promise.success(commands.stream()
                                                                                            .map(command -> h.applyCommand((KVCommand) command))
                                                                                            .toList()),
                                                        ONE_ATTEMPT,
                                                        () -> false);
    }

    @Test
    void aConfirmedReap_publishesOneDirectiveRemoval_andASecondRetirementAndTheFsmPublishNone() {
        var w = departedAndCleaned();
        var removed = directoryReader(w.h.router);
        var retirement = retirementOver(w.h);

        retirement.retire(CORE_2).await();
        w.h.drain();
        assertThat(removed).as("the confirmed reap's retirement: one removal of the directive").containsExactly(CORE_2);
        retirement.retire(CORE_2).await();
        w.h.drain();
        w.h.deliver(new ClusterInput.Reconcile());
        w.h.drain();

        assertThat(removed).as("a second retirement and the cluster FSM's R-4.3.3 have nothing left to remove").containsExactly(CORE_2);
        assertThat(w.h.submits(ClusterModel.SubmitKind.DIRECTIVE_REMOVAL)).isEmpty();
    }
}
