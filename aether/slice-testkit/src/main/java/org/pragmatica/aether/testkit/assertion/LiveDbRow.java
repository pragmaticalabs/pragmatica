// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit.assertion;

import org.pragmatica.aether.resource.db.RowMapper;


/// [DbRow] backed by a live [RowMapper.RowAccessor]. Valid only during mapping (while the driver
/// row is open); the assertion helpers evaluate the caller's predicate/mapper inside that window.
record LiveDbRow(RowMapper.RowAccessor accessor) implements DbRow {
    @Override
    public String string(String column) {
        return accessor.getString(column)
                       .or("");
    }

    @Override
    public long integer(String column) {
        return accessor.getLong(column)
                       .or(0L);
    }

    @Override
    public double number(String column) {
        return accessor.getDouble(column)
                       .or(0.0);
    }

    @Override
    public boolean bool(String column) {
        return accessor.getBoolean(column)
                       .or(false);
    }

    @Override
    public byte[] bytes(String column) {
        return accessor.getBytes(column)
                       .or(new byte[0]);
    }
}
