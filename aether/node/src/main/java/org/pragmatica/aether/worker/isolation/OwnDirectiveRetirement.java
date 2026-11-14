// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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


/// A live WORKER halts when its OWN activation directive is retired (#3193, ruling item 2).
///
/// The core retires a worker's `ActivationDirectiveKey` when it decides the worker departed (C17b, on every DEPARTED, a
/// STALE one included). That decision is an inference: a worker the core judged gone may still be running. Without
/// this observer such a process lingers as a member with no directive. With it, the process halts through the same
/// local drain the core-absence fence uses ([CoreAbsenceDetector]), so the drain ends in the node's drained exit
/// (`AetherNode.EXIT_DRAINED`), and it raises one [OWN_DIRECTIVE_RETIRED] operator warning naming this node.
///
/// ## The condition
/// The halt fires on a `ValueRemove` of `ActivationDirectiveKey(self)` when ALL of these hold:
/// - this node is a configured worker, and it was DIRECTED: it saw a `ValuePut` of its own key with role `WORKER`.
///   A node never directed has no directive to lose, so a removal before the first directive does nothing.
/// - no replacement is committed: the store, read when the removal is handled, holds no directive for this node. A
///   remove followed by a put of the same key in one transaction leaves a replacement in the store, so it does not
///   halt.
/// - this node is not already draining. A planned departure (a commanded drain) also ends in the retirement of the
///   directive. That drain already ends the process, so the removal neither halts it again nor raises a warning.
///
/// A directive rewrite is a `ValuePut`, never a `ValueRemove`, so it cannot fire the halt. A worker learns its KV
/// state from projection installs, and each install is delivered as a diff against the last delivered view: a key
/// that changed value becomes a put, and only a key absent from the new projection becomes a remove. A removal of
/// another node's directive is ignored.
///
/// ## Why EXIT_DRAINED and not EXIT_IDENTITY_REFUSED
/// The identity is not refused for good: after a fresh `WorkerJoin` the same id can be directed again
/// (`WorkerDepartureRetirementTest.returningWorker_afterRetirement_isDirectedAgain`). This is the drained exit of a
/// process the cluster no longer counts, the same exit the core-absence dissolve takes.
///
/// ## Terminal
/// The halt fires at most once (`halted` is CAS-guarded), and the warning has no recovery code: the halt ends the
/// process, so nothing is left to report the condition as cleared.
public final class OwnDirectiveRetirement {
    private static final Logger log = LoggerFactory.getLogger(OwnDirectiveRetirement.class);

    private final NodeId self;
    private final boolean configuredWorker;
    private final Supplier<Option<ActivationDirectiveValue>> committedDirective;
    private final BooleanSupplier draining;
    private final Runnable halt;
    private final OperatorWarningSink operatorWarningSink;
    private final AtomicBoolean directed = new AtomicBoolean();
    private final AtomicBoolean halted = new AtomicBoolean();

    private OwnDirectiveRetirement(NodeId self,
                                   boolean configuredWorker,
                                   Supplier<Option<ActivationDirectiveValue>> committedDirective,
                                   BooleanSupplier draining,
                                   Runnable halt,
                                   OperatorWarningSink operatorWarningSink) {
        this.self = self;
        this.configuredWorker = configuredWorker;
        this.committedDirective = committedDirective;
        this.draining = draining;
        this.halt = halt;
        this.operatorWarningSink = operatorWarningSink;
    }

    /// `committedDirective` reads this node's directive from the store; `draining` tells whether a drain already runs;
    /// `halt` starts the node's local drain, whose completion runs the drained exit.
    public static OwnDirectiveRetirement ownDirectiveRetirement(NodeId self,
                                                                boolean configuredWorker,
                                                                Supplier<Option<ActivationDirectiveValue>> committedDirective,
                                                                BooleanSupplier draining,
                                                                Runnable halt,
                                                                OperatorWarningSink operatorWarningSink) {
        return new OwnDirectiveRetirement(self,
                                          configuredWorker,
                                          committedDirective,
                                          draining,
                                          halt,
                                          operatorWarningSink);
    }

    /// Latches DIRECTED on a `WORKER` directive for this node, on a configured worker only.
    @Contract
    public void onPut(ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> put) {
        if (configuredWorker && isOwn(put.cause().key()) && ActivationDirectiveValue.WORKER.equals(put.cause()
                                                                                                      .value()
                                                                                                      .role())) {
            directed.set(true);
        }
    }

    /// Halts the node once, when the removal meets the condition in the class header.
    @Contract
    public void onRemove(ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> remove) {
        if (isUnreplacedOwnRemoval(remove.cause().key()) && halted.compareAndSet(false, true)) {
            haltNode();
        }
    }

    /// Whether the halt has fired.
    public boolean halted() {
        return halted.get();
    }

    private boolean isUnreplacedOwnRemoval(ActivationDirectiveKey key) {
        return directed.get()
               && isOwn(key)
               && committedDirective.get()
                                    .isEmpty()
               && !draining.getAsBoolean();
    }

    private boolean isOwn(ActivationDirectiveKey key) {
        return self.equals(key.nodeId());
    }

    @Contract
    private void haltNode() {
        OperatorWarnings.raise(log,
                               operatorWarningSink,
                               OWN_DIRECTIVE_RETIRED,
                               self.id(),
                               "OWN DIRECTIVE RETIRED: the core removed this worker's activation directive ({}) with no "
                              + "replacement, so it no longer counts this process as a member. Halting through the local "
                              + "drain; the node can return only through a fresh WorkerJoin.",
                               self.id());
        halt.run();
    }
}
