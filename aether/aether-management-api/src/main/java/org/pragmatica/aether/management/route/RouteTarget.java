/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
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
