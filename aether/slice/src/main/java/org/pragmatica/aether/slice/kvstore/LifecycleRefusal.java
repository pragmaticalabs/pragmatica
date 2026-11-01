// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Why a lifecycle mutation builder refused: the record is not in a state the mutation expects, or the capacity ledger cannot take the
/// change. A refusal changes nothing; the leader that gets one re-reads the record and decides again.
public sealed interface LifecycleRefusal extends Cause {
    /// The record, or one facet of it, is absent or in a state the operation does not expect. `found` names what was there.
    record WrongState(NodeId node, String operation, String found, String message) implements LifecycleRefusal {
        static final Fn3<WrongState, NodeId, String, String> FACTORY = Causes.forThreeValues("Node %s: %s is refused, the record has %s",
                                                                                             WrongState::new);
    }

    /// `readmit` named a process other than the one the identity facet binds: a different process is never re-admitted under the same node id.
    record ProcessMismatch(NodeId node, String message) implements LifecycleRefusal {
        static final Fn1<ProcessMismatch, NodeId> FACTORY = Causes.forOneValue("Node %s: another process than the one the record binds",
                                                                               ProcessMismatch::new);
    }

    /// The cause does not belong to the operation (`UNKNOWN` is a wire sentinel, never a cause).
    record WrongCause(NodeId node, String cause, String operation, String message) implements LifecycleRefusal {
        static final Fn3<WrongCause, NodeId, String, String> FACTORY = Causes.forThreeValues("Node %s: cause %s does not belong to %s",
                                                                                             WrongCause::new);
    }

    /// The capacity ledger cannot take the change: absent, inventory incomplete, at its limit, or inconsistent with the record.
    record LedgerRefused(NodeId node, String operation, String reason, String message) implements LifecycleRefusal {
        static final Fn3<LedgerRefused, NodeId, String, String> FACTORY = Causes.forThreeValues("Node %s: %s is refused by the capacity ledger: %s",
                                                                                                LedgerRefused::new);
    }
}
