// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Receipt inertness (#3828, PR A: the receipt is a key type nothing writes yet): no transaction production builds puts a
/// `DirectiveReceiptKey`; every mutation on it is a removal (an absent replacement). Called from the applier of the retirement and placement
/// tests, the one place each of their transactions passes before it is applied. The same rule guards the cluster FSM in `FsmHarness` (node
/// module's tests, which do not see this module's test classes). Fixture receipts are seeded through the store, never through the applier.
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
}
