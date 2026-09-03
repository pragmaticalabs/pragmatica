// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record SchemaPolicy(FailureMode failureMode, FailoverMode failoverMode, TimeSpan migrationTimeout) {
    public enum FailureMode {
        LEAVE_PARTIAL,
        ROLLBACK_BATCH
    }

    public enum FailoverMode {
        AUTO_RESUME,
        MANUAL_ONLY
    }

    /// #760/#724 review round 2 item c: upper bound on a single migration attempt inside
    /// [SchemaOrchestratorService] — without it a wedged connection or a runaway script holds the
    /// migration lock, and the in-flight fence, indefinitely, and no external caller (manual retry,
    /// rebuild recovery) can ever get back in. Validated here, in this record's own compact
    /// constructor, rather than in `ConfigValidator`: the bound is specific to schema migration with
    /// no cross-cutting deployment concern for `ConfigValidator` to arbitrate, so the record that
    /// owns the field owns its invariant too.
    @SuppressWarnings("JBCT-EX-01") public SchemaPolicy {
        if (migrationTimeout.nanos() <= 0) {
            throw new IllegalArgumentException("migrationTimeout must be positive, got: " + migrationTimeout);
        }
    }

    public static final TimeSpan DEFAULT_MIGRATION_TIMEOUT = timeSpan(15).minutes();

    public static SchemaPolicy schemaPolicy() {
        return new SchemaPolicy(FailureMode.LEAVE_PARTIAL, FailoverMode.AUTO_RESUME, DEFAULT_MIGRATION_TIMEOUT);
    }

    public static SchemaPolicy schemaPolicy(FailureMode failureMode, FailoverMode failoverMode) {
        return new SchemaPolicy(failureMode, failoverMode, DEFAULT_MIGRATION_TIMEOUT);
    }

    public static SchemaPolicy schemaPolicy(FailureMode failureMode,
                                            FailoverMode failoverMode,
                                            TimeSpan migrationTimeout) {
        return new SchemaPolicy(failureMode, failoverMode, migrationTimeout);
    }
}
