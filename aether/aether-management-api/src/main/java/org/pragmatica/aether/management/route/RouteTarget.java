// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import org.pragmatica.aether.slice.delegation.TaskGroup;


/// Forwarding disposition for a management API route.
///
/// The load balancer consults the target of each route to decide how to forward it:
///   - [TaskGroupTarget] — forward to the node currently assigned the given task group
///   - [AnyCoreNode]     — forward to any connected core node (read-only endpoints)
///   - [LocalNode]       — handle on the receiving node, do not forward
public sealed interface RouteTarget {
    RouteTarget ANY = new AnyCoreNode();

    RouteTarget LOCAL = new LocalNode();

    static RouteTarget taskGroup(TaskGroup group) {
        return new TaskGroupTarget(group);
    }

    record TaskGroupTarget(TaskGroup group) implements RouteTarget{}

    record AnyCoreNode() implements RouteTarget{}

    record LocalNode() implements RouteTarget{}
}
