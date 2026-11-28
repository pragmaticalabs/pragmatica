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
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #3828 (design 3.4): the retirement unit of one node id, its record, its directive and its receipt, read ONCE as one value. A retirement
/// removes the unit's members each at the exact value read, or removes nothing: in the *retiring* form [#removals] holds a removal for each
/// member the lookup held and [#witnesses] a read witness "absent" for each member it did not, so the store refuses the transaction if any
/// member moved. The *guarding* form of the same read is for a retirer that may not retire: it removes nothing and guards on the record as
/// read. The members are kept as read and asked of nobody; a caller asks only the questions below, and never chooses members.
///
/// The unit read first answers `decide` about the unit read at an attempt (design 3.4, TR's one rule): the eight rows, 0 to 7, are one
/// comparison of epochs and the directive clause, and no other condition exists. Its caller is `TerminationRetirement`.
///
/// **Bound (ABA at an equal value, #3837).** "If any member moved" is a comparison by VALUE, never by instance: the removal expects the exact
/// value read and an "absent" witness expects absence. A member removed and re-issued at an EQUAL value between the read and the commit passes
/// both checks, and the retirement removes the re-issue; a re-issue at any other value refuses the whole transaction. The unit cannot tell an
/// equal re-issue from the member it read, and does not try. Pinned by `TerminationRetirementTest#v3860d_characterisation_anEqualReissueBetweenTheReadAndTheCommit_ofAUnitWithNoRecord_isRemoved`.
///
/// **Owner of the receipt (#3860).** This unit is the only production code that mutates a `DirectiveReceiptKey`, and only as a removal: the
/// applier admits a receipt mutation only as a removal in a transaction that also removes or witnesses absent the receipt's unit mates (the
/// departure record and the directive of the same node, see `RetiredWithUnit`), which is the shape [#removals] and [#witnesses] build.
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
    ///
    /// ```tla DirectiveReceipt:Retire
    /// \* W8, W9 (read: mirror): the retirement unit, read once over the acting leader's mirror: each member the read holds is
    /// \* removed at its exact value, each member it does not hold is witnessed absent (`removals`, `witnesses`). W8: the sweep, a
    /// \* WORKER beside a DEPARTED record with a directive or a receipt (DepartureFlow; fair). W9: a DEPARTED record and no directive,
    /// \* either role (R-4.3.3, Directory; not fair: an observed event). The same method over a store read is W10 and W15 (RetireP,
    /// \* AttemptRemove in the skeleton).
    /// Retire(L) ==
    ///   /\ On("retire")
    ///   /\ M(L).rec.st = "D"
    ///   /\ ((role = "WORKER" /\ (M(L).dir \/ M(L).rcpt # 0)) \/ ~M(L).dir)
    ///   /\ Submit(UnitTx(<<"retire", L>>, M(L)))
    ///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
    /// ```
    public static RetirementUnit retirementUnit(Function<AetherKey, Option<AetherValue>> lookup, NodeId node) {
        return new RetirementUnit(node,
                                  lookup.apply(new NodeDepartureKey(node)),
                                  lookup.apply(new ActivationDirectiveKey(node)),
                                  lookup.apply(new DirectiveReceiptKey(node)),
                                  true);
    }

    /// What a retirement does at an attempt (design 3.4, the table of TR's one rule): DONE, the unit is empty, so nothing is left to retire;
    /// END, the unit is no longer the one first read, so what is present stays; REMOVE, the unit read now is retired; WAIT, the record is
    /// ADMITTED at the epoch first read, so the departure is still to commit.
    public enum Decision {
        DONE,
        END,
        REMOVE,
        WAIT
    }

    /// Whether no member was held.
    public boolean isEmpty() {
        return record.isEmpty()
               && directive.isEmpty()
               && receipt.isEmpty();
    }

    /// The answer of the eight-row table (design 3.4), asked of the unit first read about `now`, the unit read at this attempt. An empty `now`
    /// is DONE (row 0). Otherwise one comparison of epochs, an absent record counting as epoch 0 on both sides (rows 1, 5, 6): epochs that
    /// differ END, and equal epochs go by the record now. The directive clause (a directive now that is absent or equal to the one first read)
    /// is asked at every REMOVE (rows 2, 3, 5, 6): a DEPARTED or an absent record REMOVEs when it holds and ENDs when it fails, an ADMITTED
    /// record WAITs (row 4), any other state ENDs (row 7).
    ///
    /// ```tla DirectiveReceipt:Decide
    /// \* TR's one rule (3.4 of the memo), rows 0 to 7 from the top, as this method with `decideAtTheEpochFirstRead` and `decideOn`
    /// \* answers it; row 7 (any other record state) has no instance in the model's record domain. `first` is the unit first read
    /// \* (its epoch and its directive), `now` the unit read at this attempt.
    /// Decide(first, now) ==
    ///   LET clause == ~now.dir \/ first.dir IN
    ///   IF now.rec.st = "none" /\ ~now.dir /\ now.rcpt = 0 THEN "DONE"
    ///   ELSE IF now.rec.e # first.e THEN "END"
    ///   ELSE IF now.rec.st = "A" THEN "WAIT"
    ///   ELSE IF clause THEN "REMOVE" ELSE "END"
    /// ```
    public Decision decide(RetirementUnit now) {
        return now.isEmpty()
               ? Decision.DONE
               : epochOf(now.record) == epochOf(record)
                 ? decideAtTheEpochFirstRead(now)
                 : Decision.END;
    }

    private Decision decideAtTheEpochFirstRead(RetirementUnit now) {
        return now.record.filter(NodeDepartureValue.class::isInstance)
                         .map(NodeDepartureValue.class::cast)
                         .fold(() -> removeWhenTheDirectiveHolds(now),
                               current -> decideOn(current.state(),
                                                   now));
    }

    private Decision decideOn(DepartureState state, RetirementUnit now) {
        return switch (state) {
            case DEPARTED -> removeWhenTheDirectiveHolds(now);
            case ADMITTED -> Decision.WAIT;
            case UNKNOWN -> Decision.END;
        };
    }

    private Decision removeWhenTheDirectiveHolds(RetirementUnit now) {
        return now.directive.isEmpty() || now.directive.equals(directive)
               ? Decision.REMOVE
               : Decision.END;
    }

    private static long epochOf(Option<AetherValue> record) {
        return record.filter(NodeDepartureValue.class::isInstance)
                     .map(NodeDepartureValue.class::cast)
                     .map(NodeDepartureValue::epoch)
                     .or(0L);
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
