// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisionIntent;
import org.pragmatica.aether.slice.kvstore.IdentityRefusal.OperationMismatch;
import org.pragmatica.aether.slice.kvstore.IdentityRefusal.ProcessMismatch;
import org.pragmatica.aether.slice.kvstore.IdentityRefusal.WrongCause;
import org.pragmatica.aether.slice.kvstore.IdentityRefusal.WrongState;
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


/// The mutation builders of the identity record (ID-1b, membership overhaul spec 2.1 and q4 7.1). PURE: each takes the value the leader
/// READ and the intended change, and returns the [Mutation] that carries that read value as its expected value, so the applier commits it
/// only if the record is still what the leader saw (compare-and-set). No engine contact, no clock (the caller passes `now`), no removal:
/// every builder writes a replacement, and `TERMINATED` accepts no builder at all (I-J8: a terminated node id never returns).
///
/// ```
/// INTENDED -> PROVISIONING -> ADMITTED <-> DEPARTED -> TERMINATED     (INTENDED | PROVISIONING -> TERMINATED(cause) by abandon)
/// ```
///
/// | Builder | Expects | Writes |
/// |---|---|---|
/// | [#intend] | absent | `INTENDED`, epoch 0, no process |
/// | [#provisioned] | `INTENDED` with the same operation id | `PROVISIONING` with the server id |
/// | [#admit] | absent (bootstrap, genesis, manual) or `INTENDED` / `PROVISIONING` | `ADMITTED`, the process, epoch + 1 |
/// | [#abandon] | `INTENDED` or `PROVISIONING` | `TERMINATED(cause)`, cause one of the four abandonments |
/// | [#readmit] | `DEPARTED` with the same boot token | `ADMITTED`, epoch + 1 |
/// | [#depart] | `ADMITTED` | `DEPARTED` |
/// | [#terminate] | `ADMITTED` or `DEPARTED` | `TERMINATED(cause)`, optionally superseded by a successor |
///
/// Which cause wins when several intents meet (the precedence ranks) is the terminator's decision (ID-4a), not the builder's: `terminate`
/// writes the cause it is given.
public sealed interface IdentityMutations {
    Cause BLANK_ROLE = Causes.cause("The identity record needs a role");
    Cause BLANK_SERVER_ID = Causes.cause("The provider server id must not be blank");
    Cause UNKNOWN_PROCESS = Causes.cause("A process with no boot token cannot be bound to a node id");
    Set<IdentityState> INTENT_STATES = Set.of(IdentityState.INTENDED, IdentityState.PROVISIONING);
    Set<IdentityState> ADMITTED_STATES = Set.of(IdentityState.ADMITTED, IdentityState.DEPARTED);

    /// `intend(X, intent)`: the node id exists as a committed intent before any provider call. Expects absent.
    static Result<Mutation<AetherKey, AetherValue>> intend(NodeId node,
                                                           Option<NodeIdentityValue> current,
                                                           ProvisionIntent intent,
                                                           String role,
                                                           long now) {
        return Verify.ensure(role, Verify.Is::present, BLANK_ROLE)
                     .flatMap(_ -> absent(node, current, "intend"))
                     .map(_ -> new NodeIdentityValue(IdentityState.INTENDED,
                                                     0L,
                                                     none(),
                                                     role,
                                                     none(),
                                                     none(),
                                                     now,
                                                     some(intent)))
                     .map(next -> mutation(node, current, next));
    }

    /// `provisioned(X, serverId)`: the provider confirmed a server for this very intent. Expects `INTENDED` with the same operation id.
    static Result<Mutation<AetherKey, AetherValue>> provisioned(NodeId node,
                                                                Option<NodeIdentityValue> current,
                                                                OperationId operation,
                                                                String serverId,
                                                                long now) {
        return Verify.ensure(serverId, Verify.Is::present, BLANK_SERVER_ID)
                     .flatMap(_ -> inState(node,
                                           current,
                                           "provisioned",
                                           Set.of(IdentityState.INTENDED)))
                     .flatMap(found -> sameOperation(node, found, operation))
                     .map(found -> found.provisioned(serverId, now))
                     .map(next -> mutation(node, current, next));
    }

    /// `admit(X, process)`: the node's first admission. Expects absent (today's path, unchanged) or an intent in either state (an auto-healed
    /// node; a lost reply can skip `PROVISIONING`). The epoch becomes the base's plus one.
    static Result<Mutation<AetherKey, AetherValue>> admit(NodeId node,
                                                          Option<NodeIdentityValue> current,
                                                          ProcessIncarnation process,
                                                          String role,
                                                          long now) {
        return Verify.ensure(role, Verify.Is::present, BLANK_ROLE)
                     .flatMap(_ -> Verify.ensure(process, IdentityMutations::isKnown, UNKNOWN_PROCESS))
                     .flatMap(_ -> admissionBase(node, current, role, now))
                     .map(base -> base.admitted(process, now))
                     .map(next -> mutation(node, current, next));
    }

    /// `abandon(X, cause)`: the intent ends before any admission. Expects `INTENDED` or `PROVISIONING`; the cause is one of the four
    /// abandonments. The committed intent stays on the record, so the operator sees the server it named.
    static Result<Mutation<AetherKey, AetherValue>> abandon(NodeId node,
                                                            Option<NodeIdentityValue> current,
                                                            IdentityCause cause,
                                                            long now) {
        return Verify.ensure(cause,
                             IdentityCause::isAbandonment,
                             WrongCause.FACTORY.apply(node, cause, "abandon"))
                     .flatMap(_ -> inState(node, current, "abandon", INTENT_STATES))
                     .map(found -> found.terminated(cause,
                                                    none(),
                                                    now))
                     .map(next -> mutation(node, current, next));
    }

    /// `readmit(X, process)`: the same process comes back. Expects `DEPARTED` with the same boot token; the epoch grows by one.
    static Result<Mutation<AetherKey, AetherValue>> readmit(NodeId node,
                                                            Option<NodeIdentityValue> current,
                                                            ProcessIncarnation process,
                                                            long now) {
        return inState(node,
                       current,
                       "readmit",
                       Set.of(IdentityState.DEPARTED)).flatMap(found -> sameProcess(node, found, process))
                      .map(found -> found.admitted(process, now))
                      .map(next -> mutation(node, current, next));
    }

    /// `depart(X)`: the admitted process is gone for now. Expects `ADMITTED`; epoch and process stay.
    static Result<Mutation<AetherKey, AetherValue>> depart(NodeId node, Option<NodeIdentityValue> current, long now) {
        return inState(node,
                       current,
                       "depart",
                       Set.of(IdentityState.ADMITTED)).map(found -> found.departed(now))
                      .map(next -> mutation(node, current, next));
    }

    /// `terminate(X, cause, successor)`: the node id ends for good. Expects `ADMITTED` or `DEPARTED`; the cause is one of the six writer
    /// ranks, never an abandonment.
    static Result<Mutation<AetherKey, AetherValue>> terminate(NodeId node,
                                                              Option<NodeIdentityValue> current,
                                                              IdentityCause cause,
                                                              Option<NodeId> supersededBy,
                                                              long now) {
        return Verify.ensure(cause,
                             IdentityCause::isWriterRank,
                             WrongCause.FACTORY.apply(node, cause, "terminate"))
                     .flatMap(_ -> inState(node, current, "terminate", ADMITTED_STATES))
                     .map(found -> found.terminated(cause, supersededBy, now))
                     .map(next -> mutation(node, current, next));
    }

    private static boolean isKnown(ProcessIncarnation process) {
        return process.bootToken() != 0L;
    }

    private static Result<NodeIdentityValue> admissionBase(NodeId node,
                                                           Option<NodeIdentityValue> current,
                                                           String role,
                                                           long now) {
        return current.fold(() -> Result.success(NodeIdentityValue.blank(role, now)),
                            _ -> inState(node, current, "admit", INTENT_STATES));
    }

    private static Result<Unit> absent(NodeId node, Option<NodeIdentityValue> current, String operation) {
        return current.fold(Result::unitResult,
                            found -> WrongState.FACTORY.apply(node,
                                                              operation,
                                                              found.state().name())
                                                       .result());
    }

    private static Result<NodeIdentityValue> inState(NodeId node,
                                                     Option<NodeIdentityValue> current,
                                                     String operation,
                                                     Set<IdentityState> allowed) {
        return current.toResult(WrongState.FACTORY.apply(node, operation, "absent"))
                      .filter(found -> WrongState.FACTORY.apply(node,
                                                                operation,
                                                                found.state().name()),
                              found -> allowed.contains(found.state()));
    }

    private static Result<NodeIdentityValue> sameOperation(NodeId node, NodeIdentityValue found, OperationId offered) {
        return found.intent()
                    .map(ProvisionIntent::operationId)
                    .filter(offered::equals)
                    .toResult(OperationMismatch.FACTORY.apply(node,
                                                              offered,
                                                              found.intent()
                                                                   .map(ProvisionIntent::operationId)
                                                                   .or(offered)))
                    .map(_ -> found);
    }

    private static Result<NodeIdentityValue> sameProcess(NodeId node,
                                                         NodeIdentityValue found,
                                                         ProcessIncarnation offered) {
        return found.process()
                    .filter(bound -> bound.bootToken() == offered.bootToken())
                    .toResult(ProcessMismatch.FACTORY.apply(node))
                    .map(_ -> found);
    }

    private static Mutation<AetherKey, AetherValue> mutation(NodeId node,
                                                             Option<NodeIdentityValue> current,
                                                             NodeIdentityValue next) {
        return new Mutation<>(NodeIdentityKey.nodeIdentityKey(node), current.map(AetherValue.class::cast), some(next));
    }

    record unused() implements IdentityMutations {}
}
