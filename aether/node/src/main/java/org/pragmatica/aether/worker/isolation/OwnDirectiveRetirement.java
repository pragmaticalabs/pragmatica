// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.utility.warning.OperatorWarningCode.OWN_DIRECTIVE_RETIRED;


/// A live WORKER halts when its OWN activation directive is retired or changed (#3193 ruling item 2, #3330).
///
/// The core retires a worker's `ActivationDirectiveKey` when it decides the worker departed (C17b, on every DEPARTED, a
/// STALE one included). That decision is an inference: a worker the core judged gone may still be running. Without
/// this observer such a process lingers as a member with no directive. With it, the process halts through the same
/// local drain the core-absence fence uses ([CoreAbsenceDetector]), so the drain ends in the node's drained exit
/// (`AetherNode.EXIT_DRAINED`), and it raises one [OWN_DIRECTIVE_RETIRED] operator warning naming this node.
///
/// ## The invariant
/// A delivered own `WORKER` directive can only repeat; any other delivered change of the own directive ends the process
/// (a worker changes community only by retirement and a fresh `WorkerJoin`, never by a move in place). The halt is
/// decided from the delivered change alone (#3330): the store is not read.
///
/// The first `ValuePut` of `ActivationDirectiveKey(self)` with role `WORKER`, on a configured worker, makes the node
/// DIRECTED and records the community it names. That record is never overwritten. After it, the halt fires on:
/// - a `ValueRemove` of `ActivationDirectiveKey(self)`; or
/// - a `ValuePut` of `ActivationDirectiveKey(self)` with role `WORKER` naming a community other than the recorded one.
///   This is how a removal and a re-create committed between two deliveries arrive: a worker learns its KV state from
///   projection installs, each delivered as a diff against the last delivered view, so a key that changed value
///   becomes ONE put and only a key absent from the new projection becomes a remove.
///
/// A put naming the SAME community is a repeat, even when another field of the value changed: "same" compares the
/// directive's `communityId` strings with `equals`, and nothing else. A put with another role does not arm the observer
/// and is not a change it acts on (the activation path rejects a role that does not match the configured node type).
///
/// Neither edge halts when:
/// - the node was never DIRECTED: a node never directed has no directive to lose.
/// - the node is already draining. A planned departure (a commanded drain) also ends in the retirement of the
///   directive. That drain already ends the process, so the change neither halts it again nor raises a warning.
///
/// A directive committed after the removal does not cancel the halt, whether or not the store already holds it when
/// the removal is handled. A change to another node's directive is ignored.
///
/// ## Why EXIT_DRAINED and not EXIT_IDENTITY_REFUSED
/// The identity is not refused for good: after a fresh `WorkerJoin` the same id can be directed again
/// (`WorkerDepartureRetirementTest.returningWorker_afterRetirement_isDirectedAgain`). This is the drained exit of a
/// process the cluster no longer counts, the same exit the core-absence dissolve takes.
///
/// ## Terminal
/// The halt fires at most once (`halted` is CAS-guarded), whichever edge fires it, and the warning has no recovery
/// code: the halt ends the process, so nothing is left to report the condition as cleared.
public final class OwnDirectiveRetirement {
    private static final Logger log = LoggerFactory.getLogger(OwnDirectiveRetirement.class);
    private static final String REMOVED = "removed";
    private static final String MOVED = "moved to another community";

    private final NodeId self;
    private final boolean configuredWorker;
    private final BooleanSupplier draining;
    private final Runnable halt;
    private final OperatorWarningSink operatorWarningSink;
    private final AtomicReference<Option<String>> directedCommunity = new AtomicReference<>(Option.none());
    private final AtomicBoolean halted = new AtomicBoolean();

    private OwnDirectiveRetirement(NodeId self,
                                   boolean configuredWorker,
                                   BooleanSupplier draining,
                                   Runnable halt,
                                   OperatorWarningSink operatorWarningSink) {
        this.self = self;
        this.configuredWorker = configuredWorker;
        this.draining = draining;
        this.halt = halt;
        this.operatorWarningSink = operatorWarningSink;
    }

    /// `draining` tells whether a drain already runs;
    /// `halt` starts the node's local drain, whose completion runs the drained exit.
    public static OwnDirectiveRetirement ownDirectiveRetirement(NodeId self,
                                                                boolean configuredWorker,
                                                                BooleanSupplier draining,
                                                                Runnable halt,
                                                                OperatorWarningSink operatorWarningSink) {
        return new OwnDirectiveRetirement(self, configuredWorker, draining, halt, operatorWarningSink);
    }

    /// On a configured worker, a `WORKER` directive for this node records the directed community on the first one, and
    /// halts the node once when a later one names a different community (the condition in the class header).
    @Contract
    public void onPut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> put) {
        if (isOwnWorkerDirective(put.cause().key(),
                                 put.cause().value())) {
            onOwnWorkerDirective(put.cause().value().communityId());
        }
    }

    /// Halts the node once, when the removal meets the condition in the class header.
    @Contract
    public void onRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> remove) {
        if (isOwn(remove.cause().key()) && directedCommunity.get().isPresent()) {
            haltOnce(REMOVED);
        }
    }

    /// Whether the halt has fired.
    public boolean halted() {
        return halted.get();
    }

    @Contract
    private void onOwnWorkerDirective(String communityId) {
        directedCommunity.updateAndGet(recorded -> recorded.orElse(Option.some(communityId)))
                         .filter(recorded -> !recorded.equals(communityId))
                         .onPresent(_ -> haltOnce(MOVED));
    }

    private boolean isOwnWorkerDirective(ActivationDirectiveKey key, ActivationDirectiveValue value) {
        return configuredWorker
               && isOwn(key)
               && ActivationDirectiveValue.WORKER.equals(value.role());
    }

    private boolean isOwn(ActivationDirectiveKey key) {
        return self.equals(key.nodeId());
    }

    @Contract
    private void haltOnce(String change) {
        if (!draining.getAsBoolean() && halted.compareAndSet(false, true)) {
            haltNode(change);
        }
    }

    @Contract
    private void haltNode(String change) {
        OperatorWarnings.raise(log,
                               operatorWarningSink,
                               OWN_DIRECTIVE_RETIRED,
                               self.id(),
                               "OWN DIRECTIVE RETIRED: the core {} this worker's activation directive ({}), so it "
                              + "no longer counts this process as a member of the community it runs in. Halting "
                              + "through the local drain; the node can return only through a fresh WorkerJoin.",
                               change,
                               self.id());
        halt.run();
    }
}
