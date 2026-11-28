// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Receipt inertness (#3828, PR A: the receipt is a key type nothing writes yet): no transaction production builds puts a
/// `DirectiveReceiptKey`; every mutation on it is a removal (an absent replacement). Called from the applier of the retirement and placement
/// tests, the one place each of their transactions passes before it is applied. The same rule guards the cluster FSM in `FsmHarness` (node
/// module's tests, which do not see this module's test classes). Fixture receipts are planted by [#plant], never through the applier: since #3860 the
/// applier refuses every receipt put.
final class ReceiptInertness {
    private ReceiptInertness() {}

    static void assertInert(List<? extends KVCommand<?>> commands) {
        commands.stream()
                .filter(KVCommand.LeaderTransaction.class::isInstance)
                .map(command -> (KVCommand.LeaderTransaction<?, ?>) command)
                .flatMap(transaction -> transaction.mutations().stream())
                .filter(mutation -> mutation.key() instanceof AetherKey.DirectiveReceiptKey)
                .forEach(mutation -> assertThat(mutation.replacement().isEmpty())
                                         .as("receipt inertness: a transaction puts the receipt key %s", mutation.key())
                                         .isTrue());
    }

    /// Plants a receipt as committed state, the way a replica receives one (`applyEffects`, the raw plain-effect writer), because the leader
    /// applier refuses a receipt put by design (#3860). The entry lands at the store's own revision, so it is applied and never skipped.
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void plant(KVStore store, AetherKey key, Object value) {
        var revision = store.committedRevision();

        assertThat(store.applyEffects(new ReplicaEntry(IncarnationTag.NONE, revision, revision, "plant-" + key, List.of(new KVCommand.Put(key, value)))).isSuccess())
            .as("fixture: planting %s", key)
            .isTrue();
    }
}
