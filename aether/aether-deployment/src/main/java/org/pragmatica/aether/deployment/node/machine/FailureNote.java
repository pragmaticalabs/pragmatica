// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;


/// A failure waiting to be written as a FAILED row: the real reason, the disposition (`fatal`) and the generation of the
/// chain that failed.
public record FailureNote(String reason, boolean fatal, Generation generation) {
    public static FailureNote failureNote(String reason, boolean fatal, Generation generation) {
        return new FailureNote(reason, fatal, generation);
    }
}
