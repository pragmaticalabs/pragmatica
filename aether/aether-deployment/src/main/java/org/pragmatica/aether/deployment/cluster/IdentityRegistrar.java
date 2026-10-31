// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.IdentityMutations;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.some;


/// The leader-only shadow writer of the identity record (ID-1b, membership overhaul spec 2.1, G-1): on the leader's admission signal it
/// commits `admit(X, token)` to the record by compare-and-set, from absent or from an intent in either state. BootTokens REMAINS THE DECIDER:
/// this class refuses and evicts nothing. A record that disagrees with BootTokens (another boot token, or a terminated node id) is COUNTED
/// (`identity_record_divergence_total`), never acted on; the admissions it writes are COUNTED (`identity_grants_total`).
///
/// **Process identity here is the boot token alone** (R-1.6.2: staleness is decided by token equality, never by order): the bound process is
/// recorded as `ProcessIncarnation(0, token)`. A node with no boot token yet (`0`) is not recorded, since `0` carries no process identity.
///
/// **Guarantee, per operation.** `onAdmitted` writes at most once per admission signal, by compare-and-set against the value it read, so two
/// leaders (or two signals) racing for one node id commit exactly one of them; a refused commit leaves the record as it was. It does NOT
/// guarantee that every admitted node ends up in the record: a lost or refused commit is absorbed (see [#absorbed]), and the record is
/// completed by the next signal for the node or by the leader re-seed from the ledgers (ID-1c).
public interface IdentityRegistrar {
    Logger log = LoggerFactory.getLogger(IdentityRegistrar.class);

    /// The role of a node whose admission this registrar records: the admission signal is the core channel.
    String CORE_ROLE = "core";

    /// The admission signal for `node` (leader side). Completes once the decision is made and, if a commit was needed, once it is decided;
    /// never fails: a failure is counted and logged (see [#absorbed]).
    Promise<Unit> onAdmitted(NodeId node);
    /// Admissions this registrar committed to the record.
    long grants();
    /// Signals where the record named another process than BootTokens, or a terminated node id.
    long divergences();
    /// Commits that did not land (this core is not the leader, or the compare-and-set was refused).
    long failures();
    /// The counters under the names the operator sees.
    Map<String, Number> metrics();

    static IdentityRegistrar identityRegistrar(HierarchyStateWriter writer, ToLongFunction<NodeId> tokenOf, LongSupplier clock) {
        record registrar(HierarchyStateWriter writer,
                         ToLongFunction<NodeId> tokenOf,
                         LongSupplier clock,
                         AtomicLong grantCount,
                         AtomicLong divergenceCount,
                         AtomicLong failureCount) implements IdentityRegistrar {
            @Override
            public Promise<Unit> onAdmitted(NodeId node) {
                var token = tokenOf.applyAsLong(node);

                return token == 0L
                       ? Promise.unitPromise()
                       : register(node, token);
            }

            private Promise<Unit> register(NodeId node, long token) {
                return writer.read()
                             .apply(NodeIdentityKey.nodeIdentityKey(node))
                             .map(NodeIdentityValue.class::cast)
                             .fold(() -> grant(node, Option.none(), token),
                                   found -> judge(node, found, token));
            }

            private Promise<Unit> judge(NodeId node, NodeIdentityValue found, long token) {
                return switch (found.state()) {
                    case INTENDED, PROVISIONING -> grant(node, some(found), token);
                    case ADMITTED, DEPARTED -> agrees(found, token)
                                               ? Promise.unitPromise()
                                               : diverged(node, found);
                    case TERMINATED, UNKNOWN -> diverged(node, found);
                };
            }

            private static boolean agrees(NodeIdentityValue found, long token) {
                return found.process()
                            .map(ProcessIncarnation::bootToken)
                            .filter(bound -> bound == token)
                            .isPresent();
            }

            private Promise<Unit> diverged(NodeId node, NodeIdentityValue found) {
                divergenceCount.incrementAndGet();
                log.warn("Identity record for {} is {} and does not match the process BootTokens admitted; BootTokens decides", node.id(), found.state());

                return Promise.unitPromise();
            }

            private Promise<Unit> grant(NodeId node, Option<NodeIdentityValue> current, long token) {
                return IdentityMutations.admit(node, current, new ProcessIncarnation(0L, token), CORE_ROLE, clock.getAsLong())
                                        .async()
                                        .flatMap(mutation -> writer.commit(List.of(mutation), List.of()))
                                        .onSuccess(_ -> grantCount.incrementAndGet())
                                        // FER (degrade forward): the shadow write is not what admits the node (BootTokens decided already), so a
                                        // refused or lost commit must not fail the admission path. Guarantee earned: admission is unaffected and the
                                        // failure is counted and logged. Mechanism: a single attempt, no retry; the record catches up on the next
                                        // signal for the node or on the leader re-seed from the ledgers (ID-1c).
                                        .recover(cause -> absorbed(node, cause));
            }

            private Unit absorbed(NodeId node, Cause cause) {
                failureCount.incrementAndGet();
                log.debug("Identity record for {} was not written: {}", node.id(), cause.message());

                return Unit.unit();
            }

            @Override
            public long grants() {
                return grantCount.get();
            }

            @Override
            public long divergences() {
                return divergenceCount.get();
            }

            @Override
            public long failures() {
                return failureCount.get();
            }

            @Override
            public Map<String, Number> metrics() {
                return Map.of("identity_grants_total", grantCount.get(), "identity_record_divergence_total", divergenceCount.get());
            }
        }

        return new registrar(writer, tokenOf, clock, new AtomicLong(), new AtomicLong(), new AtomicLong());
    }
}
