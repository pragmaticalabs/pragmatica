// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Cause;


public sealed interface ManagementRouteError extends Cause {
    static NoMatch noMatch(HttpMethod method, String path) {
        return new NoMatch(method, path);
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
        return new NoLeaderElected();
    }

    static LeaderDisconnected leaderDisconnected(String leaderNodeId) {
        return new LeaderDisconnected(leaderNodeId);
    }

    static NotLeader notLeader() {
        return new NotLeader();
    }

    static NotLocalTarget notLocalTarget(String nodeId) {
        return new NotLocalTarget(nodeId);
    }

    static TargetDisconnected targetDisconnected(String nodeId) {
        return new TargetDisconnected(nodeId);
    }

    record NoMatch(HttpMethod method, String path) implements ManagementRouteError {
        @Override
        public String message() {
            return "No management route matches " + method + " " + path;
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

    record NoLeaderElected() implements ManagementRouteError {
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

    record NotLeader() implements ManagementRouteError {
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
}
