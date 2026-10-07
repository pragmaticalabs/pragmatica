// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// #1543 part E1 — the operator-facing entry points of node replacement, and the interface the rolling upgrade (part F)
/// builds on: begin a replacement, look at it, settle one that stopped with both nodes kept. The phases themselves are
/// driven by [NodeReplacementReconciler]; this only creates and settles records, always through the leader's
/// compare-and-set commit.
public interface NodeReplacementService {
    /// Start replacing `original` with a freshly minted node the leader provisions (CTM mode). `targetVersion` is the version
    /// the replacement must run before the swap is kept (`""` = none). Refused with a [Refusal] when it cannot start.
    Promise<NodeReplacementValue> begin(NodeId original, String targetVersion);
    /// Start replacing `original` with the node the operator chose and starts itself (`replacement`, a fresh id). For a core
    /// the admission intent is committed in the same transaction as the record. Refused when the id is already a member, paired
    /// or reserved.
    Promise<NodeReplacementValue> beginExternal(NodeId original, NodeId replacement, String targetVersion);
    /// The committed record for `original`.
    Option<NodeReplacementValue> status(NodeId original);
    /// Every committed record.
    Map<NodeId, NodeReplacementValue> all();
    /// Settle a replacement that ended `FAILED_KEPT_BOTH`: keep the new node and finish retiring the old, or give the new
    /// one up.
    Promise<Unit> settle(NodeId original, Settlement settlement);

    enum Settlement {
        KEEP_NEW,
        ROLL_BACK
    }

    sealed interface Refusal extends Cause {
        record NotLeader() implements Refusal {
            @Override
            public String message() {
                return "Only the leader starts a replacement";
            }
        }

        record UnknownNode(NodeId node) implements Refusal {
            @Override
            public String message() {
                return "Node " + node.id() + " is not a known cluster member";
            }
        }

        record RoleNotSupported(NodeId node, String role) implements Refusal {
            @Override
            public String message() {
                return "Replacing a " + role + " node (" + node.id() + ") is not supported; cores and workers only";
            }
        }

        record ReplacementIdInUse(NodeId node) implements Refusal {
            @Override
            public String message() {
                return "Node id " + node.id() + " is already a member, paired or reserved; choose a fresh id";
            }
        }

        record AlreadyReplacing(NodeId node) implements Refusal {
            @Override
            public String message() {
                return "A replacement is already in progress for " + node.id() + ", or for another node (one at a time)";
            }
        }

        record NothingToSettle(NodeId node) implements Refusal {
            @Override
            public String message() {
                return "Replacement of " + node.id() + " is not stopped with both nodes kept";
            }
        }

        record Conflict(NodeId node) implements Refusal {
            @Override
            public String message() {
                return "The replacement record of " + node.id() + " changed concurrently; retry";
            }
        }
    }
}
