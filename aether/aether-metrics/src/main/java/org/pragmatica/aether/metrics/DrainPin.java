// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

/// What a drain entry was pinned to when it was set (#2315): the drain's [DrainId], the boot token of the target process
/// (`0` when unknown) and the target's SWIM incarnation high-water mark at that moment (`0` when unknown). The registry
/// compares these, never a clock, to decide whether an end-of-episode signal belongs to this drain.
public record DrainPin(DrainId id, long process, long incarnation) {
    public static DrainPin drainPin(DrainId id, long process, long incarnation) {
        return new DrainPin(id, process, incarnation);
    }
}
