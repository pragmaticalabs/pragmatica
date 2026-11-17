// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import org.pragmatica.lang.Cause;


/// Failures that exist only in the Postgres store. They reach callers wrapped in an `EntityError`
/// (see [PgEntityErrors]), never bare.
public sealed interface PgEntityError extends Cause {
    /// An optimistic write found the row at a different version than the caller expected: another writer committed
    /// first. Retrying from a fresh read is the recovery.
    record VersionConflict(String key, long expectedVersion) implements PgEntityError, Cause.Transient {
        public static VersionConflict versionConflict(String key, long expectedVersion) {
            return new VersionConflict(key, expectedVersion);
        }

        @Override
        public String message() {
            return "Durable entity row for key '" + key
                 + "' is no longer at version " + expectedVersion
                 + " - another writer committed first";
        }
    }

    /// The user-supplied mutator threw. The transaction rolled back; the row is unchanged.
    record MutatorFailed(String key, String reason) implements PgEntityError {
        public static MutatorFailed mutatorFailed(String key, Throwable thrown) {
            return new MutatorFailed(key, String.valueOf(thrown));
        }

        @Override
        public String message() {
            return "Mutator for durable entity key '" + key + "' failed: " + reason;
        }
    }
}
