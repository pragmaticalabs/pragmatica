// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.aether.resource.db.SqlConnector;

/// The seam for the timer layer (E-C/E-D): runs inside the delete transaction, after the state row is removed, so
/// that pending timers of the key are removed atomically with the state. The store itself owns no timers.
@FunctionalInterface
public interface DeleteHook {
    Promise<Unit> afterDelete(SqlConnector transaction, String entity, String key);

    /// The hook of a store with no timer layer: nothing to remove.
    static DeleteHook noTimers() {
        return (_, _, _) -> Promise.success(Unit.unit());
    }
}
