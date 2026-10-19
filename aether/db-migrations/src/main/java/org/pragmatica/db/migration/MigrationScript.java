// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

/// Runtime-neutral script data. Checksums retain the blueprint's UTF-8 CRC32 representation.
public record MigrationScript(String filename, String sql, long checksum) {
    public static MigrationScript migrationScript(String filename, String sql, long checksum) {
        return new MigrationScript(filename, sql, checksum);
    }
}
