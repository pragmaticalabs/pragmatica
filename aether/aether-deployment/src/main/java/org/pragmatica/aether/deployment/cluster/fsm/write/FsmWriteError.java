// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;


/// Failures of the shared deployment-FSM write path (#2584).
public sealed interface FsmWriteError extends Cause {
    /// The write was not issued: the committed leader record names another node, or none, or this node's term has
    /// ended. It is never re-driven; the next leader re-derives whatever is derivable.
    record NotLeader(WriteSite site, Option<NodeId> committedLeader, String message) implements FsmWriteError, Cause.Terminal {
        public static final Fn2<NotLeader, WriteSite, Option<NodeId>> FACTORY = Causes.forTwoValues("Write %s not issued: the committed leader record names %s, and this node is not that leader or its term has ended",
                                                                                                    NotLeader::new);
    }
}
