// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.consensus.NodeId;


/// Names one drain of one node (#2315): the target and the registry's sequence number for the drain that set the entry.
///
/// A clear carries the id of the drain it ends, and [DrainCommandRegistry#clearDrain] refuses an id that is not the entry's
/// current one. A completion that arrives after a newer drain of the same node began therefore ends nothing.
public record DrainId(NodeId node, long sequence) {
    public static DrainId drainId(NodeId node, long sequence) {
        return new DrainId(node, sequence);
    }
}
