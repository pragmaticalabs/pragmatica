// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.channels.FileLock;
import java.util.List;

import org.pragmatica.lang.Contract;


/// A claimed block. `close()` releases the file locks and the registry entries; a claim that is never closed (a node whose
/// stop timed out, M15) is held until JVM exit.
public record PortClaim(PortBlock block, Object owner, List<FileLock> locks, boolean registered) implements AutoCloseable {
    public static PortClaim portClaim(PortBlock block, Object owner, List<FileLock> locks, boolean registered) {
        return new PortClaim(block, owner, locks, registered);
    }

    @Override
    @Contract
    public void close() {
        PortLockFile.release(locks);
        if (registered) {
            ClaimRegistry.release(block, owner);
        }
    }
}
