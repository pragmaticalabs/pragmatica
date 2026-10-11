// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.persistence;

import java.util.List;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Persistence adapter for key-value store backed by PostgreSQL.
@TestPersistenceDb
public interface KvPersistence {
    record KvRow(String key, String value) {}

    @Query("SELECT key, value FROM kv_store WHERE key = :key")
    Promise<Option<KvRow>> findByKey(String key);

    @Query("INSERT INTO kv_store (key, value) VALUES (:key, :value) ON CONFLICT (key) DO UPDATE SET value = :value, updated_at = NOW()")
    Promise<Unit> upsert(String key, String value);

    @Query("SELECT key, value FROM kv_store ORDER BY key")
    Promise<List<KvRow>> listAll();
}
