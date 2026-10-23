// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;

/// The all-or-none argument of [ClusterIncarnation#replaceCommands], as a check on a built batch: when T1 is present,
/// every precondition of T2 must be established by T1's acceptance. Returns the ones that are not: a guard on T2, a
/// different leader, or a T2 mutation expecting anything but the incarnation's absence or a value one of T1's guards
/// witnesses. Empty for a batch of T2 alone.
public final class ReplaceWriteCheck {
    private ReplaceWriteCheck() {}

    public static List<String> unimpliedPreconditions(List<KVCommand<AetherKey>> commands) {
        var problems = new ArrayList<String>();

        if (commands.size() < 2) {
            return problems;
        }

        var t1 = transactionOf(commands.getFirst());
        var t2 = transactionOf(commands.getLast());

        t2.guards().forEach(guard -> problems.add("T2 guard on " + guard.key()));
        if (!t2.leader().equals(t1.leader())) {
            problems.add("T2 names another leader");
        }
        t2.mutations()
          .stream()
          .filter(mutation -> !impliedByT1(t1, mutation))
          .forEach(mutation -> problems.add("T2 expects " + mutation.expected() + " at " + mutation.key()));

        return problems;
    }

    private static boolean impliedByT1(KVCommand.LeaderTransaction<AetherKey, AetherValue> t1, KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return (mutation.key().equals(ClusterIncarnationKey.clusterIncarnationKey()) && mutation.expected().isEmpty())
               || t1.guards()
                    .stream()
                    .anyMatch(guard -> guard.key().equals(mutation.key()) && guard.expected().equals(mutation.expected()));
    }

    @SuppressWarnings("unchecked")
    static KVCommand.LeaderTransaction<AetherKey, AetherValue> transactionOf(KVCommand<AetherKey> command) {
        return (KVCommand.LeaderTransaction<AetherKey, AetherValue>) command;
    }
}
