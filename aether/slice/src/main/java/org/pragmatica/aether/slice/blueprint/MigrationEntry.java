// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.serialization.Codec;


/// A single database migration script extracted from a blueprint artifact.
///
/// @param filename the migration file name (e.g., "V001__create_orders.sql")
/// @param sql the SQL content of the migration
/// @param checksum CRC32 checksum of the SQL content for change detection
@Codec public record MigrationEntry(String filename, String sql, long checksum) {
    public static MigrationEntry migrationEntry(String filename, String sql, long checksum) {
        return new MigrationEntry(filename, sql, checksum);
    }
}
