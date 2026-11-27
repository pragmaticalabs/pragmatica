// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #3828 (design 3.4): the retirement unit of one node id, its record, its directive and its receipt, read ONCE as one value. A retirement
/// removes the unit's members each at the exact value read, or removes nothing: in the *retiring* form [#removals] holds a removal for each
/// member the lookup held and [#witnesses] a read witness "absent" for each member it did not, so the store refuses the transaction if any
/// member moved. The *guarding* form of the same read is for a retirer that may not retire: it removes nothing and guards on the record as
/// read. The members are kept as read and asked of nobody; a caller asks only the questions below, and never chooses members.
///
/// Not here, deliberately: the emptiness test and `decide` (design 3.4) have no caller before lane 4 (`TerminationRetirement`), which
/// deletes the `Identity` they replace.
///
/// @param node      the retiring id
/// @param record    the DEPARTED record as read
/// @param directive the activation directive as read
/// @param receipt   the directive receipt as read
/// @param retiring  the form: the retiring form removes the members, the guarding form only guards on the record
public record RetirementUnit(NodeId node,
                             Option<AetherValue> record,
                             Option<AetherValue> directive,
                             Option<AetherValue> receipt,
                             boolean retiring) {
    /// Reads the three keys once, in the fixed order record, directive, receipt: a transaction's notifications follow its mutation order,
    /// so the record leaves `departed` before the directive's removal reaches `Directory#onDirectiveRemove`. The lookup is the mirror
    /// in the FSM (`key -> Outbox.mirrored(m, key)`); returns the retiring form.
    public static RetirementUnit retirementUnit(Function<AetherKey, Option<AetherValue>> lookup, NodeId node) {
        return new RetirementUnit(node,
                                  lookup.apply(new NodeDepartureKey(node)),
                                  lookup.apply(new ActivationDirectiveKey(node)),
                                  lookup.apply(new DirectiveReceiptKey(node)),
                                  true);
    }

    /// The same read in the guarding form.
    public RetirementUnit guarding() {
        return new RetirementUnit(node, record, directive, receipt, false);
    }

    /// Whether this is the retiring form.
    public boolean retires() {
        return retiring;
    }

    /// The retiring form: a removal at the exact value read for each member that was held, the record first. The guarding form: none.
    public List<KVCommand.Mutation<AetherKey, AetherValue>> removals() {
        return retiring
               ? Stream.of(removal(new NodeDepartureKey(node),
                                   record),
                           removal(new ActivationDirectiveKey(node),
                                   directive),
                           removal(new DirectiveReceiptKey(node),
                                   receipt))
                       .flatMap(Option::stream)
                       .toList()
               : List.of();
    }

    /// The retiring form: "absent" for each member the lookup did not hold. The guarding form: the record as read, one witness.
    public List<KVCommand.ReadWitness<AetherKey>> witnesses() {
        return retiring
               ? Stream.of(absence(new NodeDepartureKey(node),
                                   record),
                           absence(new ActivationDirectiveKey(node),
                                   directive),
                           absence(new DirectiveReceiptKey(node),
                                   receipt))
                       .flatMap(Option::stream)
                       .toList()
               : List.of(new KVCommand.ReadWitness<AetherKey>(new NodeDepartureKey(node),
                                                              record.map(value -> (Object) value)));
    }

    /// The record now held by `lookup` is exactly the record member as read (absent equals absent). Reads no other member and no witness.
    public boolean recordUnchanged(Function<AetherKey, Option<AetherValue>> lookup) {
        return lookup.apply(new NodeDepartureKey(node))
                     .equals(record);
    }

    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> removal(AetherKey key,
                                                                              Option<AetherValue> member) {
        return member.map(value -> DepartureFlow.removal(key, value));
    }

    private static Option<KVCommand.ReadWitness<AetherKey>> absence(AetherKey key, Option<AetherValue> member) {
        return member.isEmpty()
               ? Option.some(new KVCommand.ReadWitness<AetherKey>(key, Option.none()))
               : Option.none();
    }
}
