// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Cause;


/// A `[streams.X]` section [StreamConfigParser] refuses for what it declares, as opposed to how its
/// `source` or `version` is spelled (those carry the address and version types' own causes). Typed so the
/// deploy validator derives the reported rule from the cause, never from its text (#1336 review, B1).
public sealed interface StreamDeclarationError extends Cause {
    /// Both `source` (external) and `version` (owned) set on one section.
    record VersionAndSourceBothSet(String alias) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' must not set both 'source' and 'version'";
        }
    }

    /// A producing role with `version = "latest"` (spec §11.1.3).
    record ProducerVersionLatest(String alias, String role) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' has role '" + role
                 + "' with version 'latest'; producers must pin to an exact MAJOR.MINOR.PATCH triplet (spec §11.1.3)";
        }
    }

    /// More partitions than [StreamConfigParser#MAX_PARTITIONS_PER_STREAM_CEILING] (spec §7/§10).
    record PartitionsOverCeiling(String alias, int partitions, int ceiling) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias
                 + "' declares " + partitions
                 + " partitions, over the per-stream ceiling of " + ceiling;
        }
    }

    /// `replicas < 1`, or `min-sync-replicas > replicas` (spec §11.x).
    record ReplicationInvalid(String alias, String detail) implements StreamDeclarationError {
        @Override
        public String message() {
            return "Stream resource '" + alias + "' has " + detail;
        }
    }
}
