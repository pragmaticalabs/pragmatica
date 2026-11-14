// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import org.pragmatica.aether.resource.entity.EntityError;
import org.pragmatica.lang.Cause;

/// Maps any failure of a store operation into the [EntityError] vocabulary.
///
/// An `EntityError` passes through unchanged. A transient cause (lost connection, serialization failure,
/// [PgEntityError.VersionConflict]) becomes `StorageUnavailable` (retry); everything else becomes `StorageFailed`.
/// The cluster-only members (`StaleOwnerEpoch`, `NotCurrentOwner`, `OwnershipNotYetCommitted`, `OwnerTransitioning`,
/// `StaleEpochRead`, `LinearizableUnavailable`) are never produced: a single database has no owner, epoch or
/// barrier. The timer members (`TimerNotFound`, `TimerTokenMismatch`, `TimerDelayInvalid`, `TimerFireFailed`) are not
/// produced by this lane; the timer layer owns them.
final class PgEntityErrors {
    private PgEntityErrors() {}

    static Cause toEntityError(String key, Cause cause) {
        return switch (cause) {
            case EntityError entityError -> entityError;
            case Cause transientCause when transientCause.isTransient() -> new EntityError.StorageUnavailable(key, transientCause);
            default -> new EntityError.StorageFailed(key, cause);
        };
    }
}
