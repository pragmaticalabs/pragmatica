// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;


/// A database connector, releasable through the project's async close convention.
///
/// `stop()` is this family's own release verb and every connector implements it — but it was a
/// THIRD close convention that `ResourceFactory`'s default dispatch could not see, alongside
/// `AutoCloseable` and `AsyncCloseable`. None of the six concrete connectors implements either of
/// those two, so every pool (Hikari, the Netty pg pool, R2DBC) was left open at slice unload while
/// the close reported success (#891).
///
/// Extending [AsyncCloseable] here folds the third convention into the project's one for all seven
/// DB factories at once, with no change to any implementor: `close()` delegates to `stop()`, which
/// is the method they already override. Implementors keep overriding `stop()`.
public interface DatabaseConnector extends AsyncCloseable {
    DatabaseConnectorConfig config();
    Promise<Boolean> isHealthy();

    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> close() {
        return stop();
    }
}
