// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.serialization.Codec;


@Codec
public record MigrationEntry(String filename, String sql, long checksum) {
    public static MigrationEntry migrationEntry(String filename, String sql, long checksum) {
        return new MigrationEntry(filename, sql, checksum);
    }
}
