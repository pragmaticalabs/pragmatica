// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.util.List;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Cause;


public sealed interface ManagementRouteError extends Cause {
    static NoMatch noMatch(HttpMethod method, String path) {
        return new NoMatch(method, path);
    }

    static BlankSpacerText blankSpacerText(List<PathToken> suffixTokens) {
        return new BlankSpacerText(suffixTokens);
    }

    static WrongParamCount wrongParamCount(String routeName, int expected, int actual) {
        return new WrongParamCount(routeName, expected, actual);
    }

    static MissingParam missingParam(String routeName, String paramName) {
        return new MissingParam(routeName, paramName);
    }

    static AmbiguousRoutes ambiguousRoutes(String first, String second, String signature) {
        return new AmbiguousRoutes(first, second, signature);
    }

    static LocalNotForwardable localNotForwardable(String routeName) {
        return new LocalNotForwardable(routeName);
    }

    static OwnerDisconnected ownerDisconnected(TaskGroup group, String ownerNodeId) {
        return new OwnerDisconnected(group, ownerNodeId);
    }

    static NoLeaderElected noLeaderElected() {
        return NoLeaderElected.INSTANCE;
    }

    static LeaderDisconnected leaderDisconnected(String leaderNodeId) {
        return new LeaderDisconnected(leaderNodeId);
    }

    static NotLeader notLeader() {
        return NotLeader.INSTANCE;
    }

    static NotLocalTarget notLocalTarget(String nodeId) {
        return new NotLocalTarget(nodeId);
    }

    static TargetDisconnected targetDisconnected(String nodeId) {
        return new TargetDisconnected(nodeId);
    }

    static PartitionOwnerUnresolved partitionOwnerUnresolved(String routeName, String path) {
        return new PartitionOwnerUnresolved(routeName, path);
    }

    static OwnerForwardLoop ownerForwardLoop(String routeName, String previousHop) {
        return new OwnerForwardLoop(routeName, previousHop);
    }

    record NoMatch(HttpMethod method, String path) implements ManagementRouteError {
        @Override
        public String message() {
            return "No management route matches " + method + " " + path;
        }
    }

    record BlankSpacerText(List<PathToken> suffixTokens) implements ManagementRouteError {
        @Override
        public String message() {
            return "Blank Spacer text in interleaved route suffix: " + suffixTokens;
        }
    }

    record WrongParamCount(String routeName, int expected, int actual) implements ManagementRouteError {
        @Override
        public String message() {
            return "Route " + routeName + " expects " + expected + " parameters, got " + actual;
        }
    }

    record MissingParam(String routeName, String paramName) implements ManagementRouteError {
        @Override
        public String message() {
            return "Route " + routeName + " missing parameter: " + paramName;
        }
    }

    record AmbiguousRoutes(String first, String second, String signature) implements ManagementRouteError {
        @Override
        public String message() {
            return "Ambiguous management routes: " + first + " and " + second + " share signature " + signature;
        }
    }

    record LocalNotForwardable(String routeName) implements ManagementRouteError {
        @Override
        public String message() {
            return "Route " + routeName + " is marked LOCAL and cannot be forwarded";
        }
    }

    record OwnerDisconnected(TaskGroup group, String ownerNodeId) implements ManagementRouteError {
        @Override
        public String message() {
            return "Task group " + group + " owner " + ownerNodeId + " is not connected";
        }
    }

    enum NoLeaderElected implements ManagementRouteError {
        INSTANCE;
        @Override
        public String message() {
            return "No leader elected for leader-bound management route";
        }
    }

    record LeaderDisconnected(String leaderNodeId) implements ManagementRouteError {
        @Override
        public String message() {
            return "Cluster leader " + leaderNodeId + " is not connected";
        }
    }

    enum NotLeader implements ManagementRouteError {
        INSTANCE;
        @Override
        public String message() {
            return "Target handler requires the cluster leader";
        }
    }

    record NotLocalTarget(String nodeId) implements ManagementRouteError {
        @Override
        public String message() {
            return "Per-node forward target " + nodeId + " is the local node; signal to handle locally";
        }
    }

    record TargetDisconnected(String nodeId) implements ManagementRouteError {
        @Override
        public String message() {
            return "Per-node forward target " + nodeId + " is not connected";
        }
    }

    /// No HRW owner could be computed for a [RouteTarget.PartitionOwner] route (#1039).
    ///
    /// `ReplicaSetController.ownerFor` returns empty only when no placement can be computed at all —
    /// an empty member view, or the bootstrap window before the first reconcile reports members. It
    /// is NOT the "unknown stream" case: HRW placement hashes a name over the member set, so a stream
    /// nobody ever created still has a deterministic owner.
    ///
    /// Answering locally instead would produce `servedByOwner=false` with an empty replica ring —
    /// indistinguishable from a genuinely empty partition, which is the exact confusion #1039 exists
    /// to remove. A clear failure is the honest outcome: the question has no authoritative answer yet.
    record PartitionOwnerUnresolved(String routeName, String path) implements ManagementRouteError {
        @Override
        public String message() {
            return "No partition owner resolvable for " + routeName
                 + " " + path
                 + " (empty member view or pre-reconcile bootstrap window)";
        }
    }

    /// An owner-forwarded request arrived at a node that does not resolve ITSELF as the partition's
    /// owner (#1039) — membership skew, where A forwards to B while B resolves A, or some third node.
    ///
    /// Raised by the RECEIVER, before dispatch, in `ManagementServerImpl.checkForwardedPartitionOwner`.
    /// The receiver refuses rather than forwarding again: a second hop is what could cycle, and the
    /// sender has already made an owner decision, so the disagreement itself is the answer. That
    /// terminates skew on a named cause in one round instead of letting it decay into the
    /// budget-exhaustion deadline, which is indistinguishable from a slow peer — and, more
    /// importantly, instead of answering with `servedByOwner=false` and a partial ring, which is
    /// indistinguishable from a genuinely empty partition.
    ///
    /// `previousHop` is the peer the cluster transport names (`HttpForwardRequest.sender`), never a
    /// client-supplied header.
    record OwnerForwardLoop(String routeName, String previousHop) implements ManagementRouteError {
        @Override
        public String message() {
            return "Owner-forward loop on " + routeName
                 + ": already forwarded by " + previousHop
                 + " — membership views disagree on the partition owner";
        }
    }
}
