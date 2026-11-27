// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.function.ToLongFunction;

import org.pragmatica.consensus.NodeId;


/// The identity facts of a drain target (#2315), read when a drain is pinned and when an edge asks whether a node is still the
/// process that was drained: `process` is the boot token of the process the node's id currently names, `incarnation` the
/// membership view's incarnation high-water mark for it. `0` means unknown and is never taken as evidence of a difference.
public record DrainSubject(ToLongFunction<NodeId> process, ToLongFunction<NodeId> incarnation) {
    public static DrainSubject drainSubject(ToLongFunction<NodeId> process, ToLongFunction<NodeId> incarnation) {
        return new DrainSubject(process, incarnation);
    }

    /// A subject that knows nothing: every drain is pinned with process and incarnation `0`.
    public static DrainSubject unknown() {
        return new DrainSubject(_ -> 0L, _ -> 0L);
    }
}
