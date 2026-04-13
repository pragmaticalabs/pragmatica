package org.pragmatica.aether.test.persistence;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Persistence adapter for key-value store backed by PostgreSQL.
@PgSql public interface KvPersistence {
    record KvRow(String key, String value){}

    @Query("SELECT key, value FROM kv_store WHERE key = :key") Promise<Option<KvRow>> findByKey(String key);

    @Query("INSERT INTO kv_store (key, value) VALUES (:key, :value) ON CONFLICT (key) DO UPDATE SET value = :value, updated_at = NOW()") Promise<Unit> upsert(String key,
                                                                                                                                                              String value);

    @Query("SELECT key, value FROM kv_store ORDER BY key") Promise<List<KvRow>> listAll();
}
