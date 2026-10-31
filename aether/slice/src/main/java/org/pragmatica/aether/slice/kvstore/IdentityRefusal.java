// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Why an identity mutation builder refused: the record is not in a state the mutation expects, or the mutation's own input does not fit
/// the record. A refusal changes nothing; the leader that gets one re-reads the record and decides again.
public sealed interface IdentityRefusal extends Cause {
    /// The record is absent, or in a state the operation does not expect. `found` is the state's name, or `absent`.
    record WrongState(NodeId node, String operation, String found, String message) implements IdentityRefusal {
        static final Fn3<WrongState, NodeId, String, String> FACTORY = Causes.forThreeValues("Node %s: %s is refused, the record is %s",
                                                                                              WrongState::new);
    }

    /// `provisioned` named an operation id other than the one the committed intent carries.
    record OperationMismatch(NodeId node, OperationId offered, OperationId intended, String message) implements IdentityRefusal {
        static final Fn3<OperationMismatch, NodeId, OperationId, OperationId> FACTORY = Causes.forThreeValues("Node %s: operation id %s does not match the committed intent %s",
                                                                                                                OperationMismatch::new);
    }

    /// `readmit` named a process other than the one the record binds: a different process is never re-admitted under the same node id.
    record ProcessMismatch(NodeId node, String message) implements IdentityRefusal {
        static final Fn1<ProcessMismatch, NodeId> FACTORY = Causes.forOneValue("Node %s: another process than the one the record binds",
                                                                               ProcessMismatch::new);
    }

    /// The cause does not belong to the operation: `abandon` takes the four abandonment causes, `terminate` the six writer ranks.
    record WrongCause(NodeId node, IdentityCause cause, String operation, String message) implements IdentityRefusal {
        static final Fn3<WrongCause, NodeId, IdentityCause, String> FACTORY = Causes.forThreeValues("Node %s: cause %s does not belong to %s",
                                                                                                    WrongCause::new);
    }
}
