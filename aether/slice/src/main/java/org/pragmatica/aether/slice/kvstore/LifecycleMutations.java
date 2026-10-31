// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;
import java.util.Locale;

import org.pragmatica.aether.slice.kvstore.AetherKey.CapacityLedgerKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.FreedCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityFacet;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.LifecycleRefusal.LedgerRefused;
import org.pragmatica.aether.slice.kvstore.LifecycleRefusal.ProcessMismatch;
import org.pragmatica.aether.slice.kvstore.LifecycleRefusal.WrongCause;
import org.pragmatica.aether.slice.kvstore.LifecycleRefusal.WrongState;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The mutation builders of the per-node lifecycle record (O3-1, q4 7.1, owner ruling c37a7c11e2). PURE: each takes the value the leader
/// READ and the intended change, and returns the [Mutation]s that make it, each carrying the value it was built from as the expected value, so
/// the applier commits the change only if the record (and the fleet counter) are still what the leader saw. No engine contact, no clock (the
/// caller passes `now`), and **no delete**: every builder writes a replacement, and a released capacity becomes the `FREED` tombstone (I-O1).
///
/// The record holds two facets under one key, and each builder touches exactly one of them. The other facet is copied from the value read, so a
/// stale read of either facet loses the compare-and-set and cannot overwrite the other (I-O4).
///
/// **Capacity facet** (the phase machine of `CapacityControlledLifecycle`, storage only; every count change travels in the same list as the
/// record mutation, as `CapacityControlledLifecycle#mutate` does today): [#reserve] (absent to `DISPATCHED`, counted), [#observe] (to
/// `OBSERVED`, counted when it adopts), [#refuse] (`DISPATCHED` to `RELEASED`, no count change), [#free] (to `FREED`, counted down).
/// The ledger counts every record whose capacity is not `FREED`, `RELEASED` included, exactly as today's `release` pass decrements it
/// **[guess: q4 7.1 I-O2 words it as DISPATCHED, OBSERVED, UNKNOWN only; the code is the authority for a behaviour-neutral change]**.
///
/// **Identity facet** (rev 3): [#admit] (absent to `ADMITTED`, epoch 1), [#depart], [#readmit] (same boot token, epoch + 1), [#terminate]
/// (`ADMITTED` or `DEPARTED` to `TERMINATED`). Which cause wins when intents meet is the terminator's decision (ID-4a); `terminate` writes the
/// cause it is given.
///
/// Not here, by design: the read witness on the cluster configuration (`reserve`'s guard) and the `NodePlacementKey` delete that `release`
/// makes are the writer's, moved with it in O3-4.
public sealed interface LifecycleMutations {
    /// What the fleet counter is before anything was ever counted (an absent counter reads as this, as `observeBound` reads it today).
    CapacityLedgerValue EMPTY_LEDGER = new CapacityLedgerValue(0, 0L, false);
    Cause BLANK_SOURCE = Causes.cause("A capacity facet needs a source name");
    Cause BLANK_ROLE = Causes.cause("The identity facet needs a role");
    Cause UNKNOWN_PROCESS = Causes.cause("A process with no boot token cannot be bound to a node id");

    /// `reserve`: a create is about to be dispatched for this node id. Expects no capacity facet; needs a complete inventory and room under
    /// `limit`. Writes `DISPATCHED` and counts one more.
    static Result<List<Mutation<AetherKey, AetherValue>>> reserve(NodeId node,
                                                                  Option<NodeLifecycleValue> current,
                                                                  Option<CapacityLedgerValue> ledger,
                                                                  int limit,
                                                                  String source,
                                                                  String binding,
                                                                  String intendedRole) {
        return Verify.ensure(source, Verify.Is::present, BLANK_SOURCE)
                     .flatMap(_ -> noCapacity(node, current, "reserve"))
                     .flatMap(_ -> roomInLedger(node, ledger, limit))
                     .map(before -> List.of(record(node,
                                                   current,
                                                   withCapacity(current,
                                                                new CapacityFacet(source,
                                                                                  binding,
                                                                                  intendedRole.toLowerCase(Locale.ROOT),
                                                                                  CapacityPhase.DISPATCHED,
                                                                                  none(),
                                                                                  none()))),
                                            ledgerChange(ledger,
                                                         new CapacityLedgerValue(before.allocated() + 1,
                                                                                 before.version() + 1,
                                                                                 true))));
    }

    /// `observe`: the provider reports an instance for this node id. From no capacity facet (or a `FREED` one) it adopts the instance and counts
    /// one more; from `DISPATCHED`, `RELEASED` or `UNKNOWN` it confirms, with no count change. Already `OBSERVED` writes nothing. A facet of
    /// another source or binding is refused. The ledger version moves with every change, as today.
    static Result<List<Mutation<AetherKey, AetherValue>>> observe(NodeId node,
                                                                  Option<NodeLifecycleValue> current,
                                                                  Option<CapacityLedgerValue> ledger,
                                                                  String source,
                                                                  String binding) {
        return current.flatMap(NodeLifecycleValue::capacity)
                      .fold(() -> adopt(node, current, ledger, source, binding, ""),
                            found -> observeFound(node, current, ledger, source, binding, found));
    }

    /// `refuse`: the provider did not create the node. Expects `DISPATCHED` of the same source and binding; writes `RELEASED`, no count change.
    static Result<List<Mutation<AetherKey, AetherValue>>> refuse(NodeId node,
                                                                 Option<NodeLifecycleValue> current,
                                                                 String source,
                                                                 String binding) {
        return capacityIn(node, current, "refuse", CapacityPhase.DISPATCHED).flatMap(found -> sameSource(node,
                                                                                                         found,
                                                                                                         source,
                                                                                                         binding,
                                                                                                         "refuse"))
                         .map(found -> List.of(record(node,
                                                      current,
                                                      withCapacity(current,
                                                                   found.withPhase(CapacityPhase.RELEASED)))));
    }

    /// `free`: the capacity of this node id ends. From any phase but `FREED`; writes the `FREED` tombstone with its cause and time and counts
    /// one fewer. The record stays (I-O1).
    static Result<List<Mutation<AetherKey, AetherValue>>> free(NodeId node,
                                                               Option<NodeLifecycleValue> current,
                                                               Option<CapacityLedgerValue> ledger,
                                                               FreedCause cause,
                                                               long now) {
        return Verify.ensure(cause,
                             FreedCause::isCause,
                             WrongCause.FACTORY.apply(node,
                                                      cause.name(),
                                                      "free"))
                     .flatMap(_ -> liveCapacity(node, current))
                     .flatMap(found -> countedDown(node, ledger).map(before -> List.of(record(node,
                                                                                              current,
                                                                                              withCapacity(current,
                                                                                                           found.freed(cause,
                                                                                                                       now))),
                                                                                       ledgerChange(ledger,
                                                                                                    new CapacityLedgerValue(before.allocated() - 1,
                                                                                                                            before.version() + 1,
                                                                                                                            before.inventoryComplete())))));
    }

    /// `admit(X, process)`: the node's first admission. Expects no identity facet (a capacity-only record is the normal case of a provisioned
    /// node); writes `ADMITTED`, epoch 1.
    static Result<List<Mutation<AetherKey, AetherValue>>> admit(NodeId node,
                                                                Option<NodeLifecycleValue> current,
                                                                ProcessIncarnation process,
                                                                String role,
                                                                long now) {
        return Verify.ensure(role, Verify.Is::present, BLANK_ROLE)
                     .flatMap(_ -> Verify.ensure(process, LifecycleMutations::isKnown, UNKNOWN_PROCESS))
                     .flatMap(_ -> noIdentity(node, current, "admit"))
                     .map(_ -> List.of(record(node,
                                              current,
                                              withIdentity(current,
                                                           new IdentityFacet(IdentityState.ADMITTED,
                                                                             1L,
                                                                             process,
                                                                             role,
                                                                             none(),
                                                                             now)))));
    }

    /// `readmit(X, process)`: the same process comes back. Expects `DEPARTED` with the same boot token; the epoch grows by one.
    static Result<List<Mutation<AetherKey, AetherValue>>> readmit(NodeId node,
                                                                  Option<NodeLifecycleValue> current,
                                                                  ProcessIncarnation process,
                                                                  long now) {
        return identityIn(node, current, "readmit", IdentityState.DEPARTED).flatMap(found -> sameProcess(node,
                                                                                                         found,
                                                                                                         process))
                         .map(found -> List.of(record(node,
                                                      current,
                                                      withIdentity(current,
                                                                   found.admitted(process, now)))));
    }

    /// `depart(X)`: the admitted process is gone for now. Expects `ADMITTED`; epoch and process stay.
    static Result<List<Mutation<AetherKey, AetherValue>>> depart(NodeId node,
                                                                 Option<NodeLifecycleValue> current,
                                                                 long now) {
        return identityIn(node, current, "depart", IdentityState.ADMITTED).map(found -> List.of(record(node,
                                                                                                       current,
                                                                                                       withIdentity(current,
                                                                                                                    found.departed(now)))));
    }

    /// `terminate(X, cause)`: the node id ends for good. Expects `ADMITTED` or `DEPARTED`; the cause is one of the six writer ranks.
    static Result<List<Mutation<AetherKey, AetherValue>>> terminate(NodeId node,
                                                                    Option<NodeLifecycleValue> current,
                                                                    IdentityCause cause,
                                                                    long now) {
        return Verify.ensure(cause,
                             IdentityCause::isWriterRank,
                             WrongCause.FACTORY.apply(node,
                                                      cause.name(),
                                                      "terminate"))
                     .flatMap(_ -> identityIn(node, current, "terminate", IdentityState.ADMITTED, IdentityState.DEPARTED))
                     .map(found -> List.of(record(node,
                                                  current,
                                                  withIdentity(current,
                                                               found.terminated(cause, now)))));
    }

    private static Result<List<Mutation<AetherKey, AetherValue>>> observeFound(NodeId node,
                                                                               Option<NodeLifecycleValue> current,
                                                                               Option<CapacityLedgerValue> ledger,
                                                                               String source,
                                                                               String binding,
                                                                               CapacityFacet found) {
        return switch (found.phase()) {
            case OBSERVED -> Result.success(List.of());
            case FREED -> adopt(node, current, ledger, source, binding, "");
            case DISPATCHED, RELEASED, UNKNOWN -> sameSource(node, found, source, binding, "observe").map(same -> confirmed(node,
                                                                                                                            current,
                                                                                                                            ledger,
                                                                                                                            same));
        };
    }

    private static List<Mutation<AetherKey, AetherValue>> confirmed(NodeId node,
                                                                    Option<NodeLifecycleValue> current,
                                                                    Option<CapacityLedgerValue> ledger,
                                                                    CapacityFacet found) {
        var before = ledger.or(EMPTY_LEDGER);

        return List.of(record(node, current, withCapacity(current, found.withPhase(CapacityPhase.OBSERVED))),
                       ledgerChange(ledger,
                                    new CapacityLedgerValue(before.allocated(),
                                                            before.version() + 1,
                                                            before.inventoryComplete())));
    }

    private static Result<List<Mutation<AetherKey, AetherValue>>> adopt(NodeId node,
                                                                        Option<NodeLifecycleValue> current,
                                                                        Option<CapacityLedgerValue> ledger,
                                                                        String source,
                                                                        String binding,
                                                                        String intendedRole) {
        var before = ledger.or(EMPTY_LEDGER);

        return Verify.ensure(source, Verify.Is::present, BLANK_SOURCE).map(_ -> List.of(record(node,
                                                                                               current,
                                                                                               withCapacity(current,
                                                                                                            new CapacityFacet(source,
                                                                                                                              binding,
                                                                                                                              intendedRole,
                                                                                                                              CapacityPhase.OBSERVED,
                                                                                                                              none(),
                                                                                                                              none()))),
                                                                                        ledgerChange(ledger,
                                                                                                     new CapacityLedgerValue(before.allocated() + 1,
                                                                                                                             before.version() + 1,
                                                                                                                             before.inventoryComplete()))));
    }

    private static boolean isKnown(ProcessIncarnation process) {
        return process.bootToken() != 0L;
    }

    private static Result<Unit> noCapacity(NodeId node, Option<NodeLifecycleValue> current, String operation) {
        return current.flatMap(NodeLifecycleValue::capacity)
                      .fold(Result::unitResult,
                            found -> WrongState.FACTORY.apply(node,
                                                              operation,
                                                              "capacity " + found.phase().name())
                                                       .result());
    }

    private static Result<Unit> noIdentity(NodeId node, Option<NodeLifecycleValue> current, String operation) {
        return current.flatMap(NodeLifecycleValue::identity)
                      .fold(Result::unitResult,
                            found -> WrongState.FACTORY.apply(node,
                                                              operation,
                                                              "identity " + found.state().name())
                                                       .result());
    }

    private static Result<CapacityFacet> liveCapacity(NodeId node, Option<NodeLifecycleValue> current) {
        return current.flatMap(NodeLifecycleValue::capacity)
                      .toResult(WrongState.FACTORY.apply(node, "free", "no capacity"))
                      .filter(found -> WrongState.FACTORY.apply(node,
                                                                "free",
                                                                "capacity " + found.phase().name()),
                              found -> found.phase() != CapacityPhase.FREED);
    }

    private static Result<CapacityFacet> capacityIn(NodeId node,
                                                    Option<NodeLifecycleValue> current,
                                                    String operation,
                                                    CapacityPhase phase) {
        return current.flatMap(NodeLifecycleValue::capacity)
                      .toResult(WrongState.FACTORY.apply(node, operation, "no capacity"))
                      .filter(found -> WrongState.FACTORY.apply(node,
                                                                operation,
                                                                "capacity " + found.phase().name()),
                              found -> found.phase() == phase);
    }

    private static Result<IdentityFacet> identityIn(NodeId node,
                                                    Option<NodeLifecycleValue> current,
                                                    String operation,
                                                    IdentityState... allowed) {
        return current.flatMap(NodeLifecycleValue::identity)
                      .toResult(WrongState.FACTORY.apply(node, operation, "no identity"))
                      .filter(found -> WrongState.FACTORY.apply(node,
                                                                operation,
                                                                "identity " + found.state().name()),
                              found -> List.of(allowed).contains(found.state()));
    }

    private static Result<CapacityFacet> sameSource(NodeId node,
                                                    CapacityFacet found,
                                                    String source,
                                                    String binding,
                                                    String operation) {
        return Option.some(found)
                     .filter(facet -> facet.sourceName()
                                           .equals(source) && facet.sourceBinding()
                                                                   .equals(binding))
                     .toResult(WrongState.FACTORY.apply(node,
                                                        operation,
                                                        "capacity of source " + found.sourceName()));
    }

    private static Result<IdentityFacet> sameProcess(NodeId node, IdentityFacet found, ProcessIncarnation offered) {
        return Option.some(found)
                     .filter(bound -> bound.process()
                                           .bootToken() == offered.bootToken())
                     .toResult(ProcessMismatch.FACTORY.apply(node));
    }

    private static Result<CapacityLedgerValue> roomInLedger(NodeId node,
                                                            Option<CapacityLedgerValue> ledger,
                                                            int limit) {
        return ledger.toResult(LedgerRefused.FACTORY.apply(node, "reserve", "no ledger"))
                     .filter(found -> LedgerRefused.FACTORY.apply(node, "reserve", "inventory incomplete"),
                             CapacityLedgerValue::inventoryComplete)
                     .filter(found -> LedgerRefused.FACTORY.apply(node, "reserve", "at the limit of " + limit),
                             found -> found.allocated() < limit);
    }

    private static Result<CapacityLedgerValue> countedDown(NodeId node, Option<CapacityLedgerValue> ledger) {
        return ledger.toResult(LedgerRefused.FACTORY.apply(node, "free", "no ledger"))
                     .filter(found -> LedgerRefused.FACTORY.apply(node, "free", "nothing allocated to release"),
                             found -> found.allocated() > 0);
    }

    private static NodeLifecycleValue withCapacity(Option<NodeLifecycleValue> current, CapacityFacet capacity) {
        return new NodeLifecycleValue(current.flatMap(NodeLifecycleValue::identity), some(capacity));
    }

    private static NodeLifecycleValue withIdentity(Option<NodeLifecycleValue> current, IdentityFacet identity) {
        return new NodeLifecycleValue(some(identity), current.flatMap(NodeLifecycleValue::capacity));
    }

    private static Mutation<AetherKey, AetherValue> record(NodeId node,
                                                           Option<NodeLifecycleValue> current,
                                                           NodeLifecycleValue next) {
        return new Mutation<>(NodeIdentityKey.nodeIdentityKey(node), current.map(AetherValue.class::cast), some(next));
    }

    private static Mutation<AetherKey, AetherValue> ledgerChange(Option<CapacityLedgerValue> before,
                                                                 CapacityLedgerValue after) {
        return new Mutation<>(CapacityLedgerKey.INSTANCE, before.map(AetherValue.class::cast), some(after));
    }

    record unused() implements LifecycleMutations {}
}
