// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import org.pragmatica.serialization.Codec;


@Codec
public record MigrationEntry(String filename, String sql, long checksum) {
    public static MigrationEntry migrationEntry(String filename, String sql, long checksum) {
        return new MigrationEntry(filename, sql, checksum);
    }

    /// The entry for `sql`, its checksum the CRC-32 of the UTF-8 bytes: the one place a script's checksum is computed.
    public static MigrationEntry migrationEntry(String filename, String sql) {
        return migrationEntry(filename, sql, crc32(sql));
    }

    private static long crc32(String content) {
        var crc = new CRC32();

        crc.update(content.getBytes(StandardCharsets.UTF_8));

        return crc.getValue();
    }
}
