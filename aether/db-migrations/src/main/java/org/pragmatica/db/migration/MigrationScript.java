// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.db.migration;

/// Runtime-neutral script data. Checksums retain the blueprint's UTF-8 CRC32 representation.
public record MigrationScript(String filename, String sql, long checksum) {
    public static MigrationScript migrationScript(String filename, String sql, long checksum) {
        return new MigrationScript(filename, sql, checksum);
    }
}
