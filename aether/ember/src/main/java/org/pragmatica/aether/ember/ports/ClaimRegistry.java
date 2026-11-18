// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.pragmatica.lang.Contract;

import static org.pragmatica.lang.Option.option;


/// The JVM-wide claim registry (M10): takes every port of a block or none, before any file lock is tried. In-JVM
/// exclusion must not depend on the file lock alone, because one JVM cannot lock a range twice
/// (`OverlappingFileLockException`).
sealed interface ClaimRegistry {
    ConcurrentMap<Integer, Object> HELD = new ConcurrentHashMap<>();

    /// All-or-none: a miss on any port leaves nothing of this owner behind.
    static boolean take(PortBlock block, Object owner) {
        var taken = block.ports().takeWhile(port -> put(port, owner)).count();

        return taken == PortBlock.STRIDE || releaseAndFail(block, owner);
    }

    @Contract
    static void release(PortBlock block, Object owner) {
        block.ports().forEach(port -> HELD.remove(port, owner));
    }

    private static boolean put(int port, Object owner) {
        return option(HELD.putIfAbsent(port, owner)).isEmpty();
    }

    private static boolean releaseAndFail(PortBlock block, Object owner) {
        release(block, owner);

        return false;
    }

    record unused() implements ClaimRegistry {}
}
