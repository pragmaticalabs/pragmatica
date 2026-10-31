// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.IdentityFacts;
import org.pragmatica.consensus.net.IdentityLedger;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The apply listener of the identity record (ID-1b, membership overhaul spec 2.1): every core, on APPLYING a `TERMINATED` record, writes the
/// retirement to ITS OWN identity ledger (F1-F3) before the apply callback returns, so the fact is durable before the core acts on it. A core
/// that was down at the apply receives the record by the store's replay (a synthetic put per changed key), which reaches this listener too.
///
/// **What is written.** Retirements only: the ledger keeps `Terminated(X, cause)` and nothing else (ID-1a: a loaded old boot token would
/// refuse every restarted peer), so an `ADMITTED` / `DEPARTED` record writes nothing, and a `TERMINATED` record with no process (an abandoned
/// intent) writes nothing either: "no ledger fact for intents" (q4 7.1).
///
/// **When it writes nothing.** On a node that cannot vouch (no ledger, not yet loaded, or WIPED: [BootTokens#offer] is empty). Writing the
/// ledger file of a WIPED node would make its next restart find a file and mistake itself for a healthy node, which is the loss the ledger
/// exists to expose; such a node regains the fact by the merge at join (ID-1c).
///
/// **Not done here, on purpose.** The in-memory registry is not told: adopting a retirement into BootTokens is the record reader of ID-2,
/// and this class must not change BootTokens behaviour.
public interface IdentityRecordPersister {
    Logger log = LoggerFactory.getLogger(IdentityRecordPersister.class);

    /// A bound for the apply thread: one small fsync, so a healthy disk is far inside it; a stalled one must not stall the apply path.
    int WRITE_BOUND_SECONDS = 5;

    /// The record under `key` was applied with `value`.
    Unit onPut(NodeIdentityKey key, NodeIdentityValue value);
    /// Ledger writes that did not complete (failed or timed out).
    long failures();

    static IdentityRecordPersister identityRecordPersister(IdentityLedger ledger, BootTokens tokens) {
        record persister(IdentityLedger ledger, BootTokens tokens, AtomicLong failureCount) implements IdentityRecordPersister {
            @Override
            public Unit onPut(NodeIdentityKey key, NodeIdentityValue value) {
                return retirement(key, value).map(this::writeAhead)
                                             .or(Unit.unit());
            }

            private Option<IdentityFacts> retirement(NodeIdentityKey key, NodeIdentityValue value) {
                return Option.option(value)
                             .filter(found -> found.state() == IdentityState.TERMINATED)
                             .filter(found -> found.process().isPresent())
                             .filter(_ -> tokens.offer().isPresent())
                             .map(found -> IdentityFacts.terminated(key.nodeId(),
                                                                    found.cause().map(Enum::name).or(IdentityState.TERMINATED.name())));
            }

            // The apply callback is the point where this core starts to act on the fact, so the write must be durable before it returns.
            @TerminalOperation
            private Unit writeAhead(IdentityFacts fact) {
                return ledger.record(fact)
                             .await(timeSpan(WRITE_BOUND_SECONDS).seconds())
                             // FER (degrade forward): the retirement is the cluster's committed decision and a local disk failure must not undo
                             // it. Guarantee earned: the record stands in the KV and is applied as usual; this core's restart protection for the
                             // node id is off until a write succeeds. Mechanism: the ledger reports the lost durability to the operator (the
                             // `identity_ledger_durable` gauge and event), and the failure is counted here; there is no retry.
                             .onFailure(this::absorbed)
                             .map(_ -> Unit.unit())
                             .or(Unit.unit());
            }

            private Unit absorbed(Cause cause) {
                failureCount.incrementAndGet();
                log.warn("Identity ledger write for an applied retirement did not complete: {}", cause.message());

                return Unit.unit();
            }

            @Override
            public long failures() {
                return failureCount.get();
            }
        }

        return new persister(ledger, tokens, new AtomicLong());
    }
}
