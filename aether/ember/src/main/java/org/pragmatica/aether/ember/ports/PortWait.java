// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Sleeping as a `Result`, so back-off loops stay in one error channel.
sealed interface PortWait {
    static Result<Unit> sleep(long millis) {
        return Result.lift(_ -> PortError.General.INTERRUPTED, () -> Thread.sleep(millis));
    }

    record unused() implements PortWait {}
}
