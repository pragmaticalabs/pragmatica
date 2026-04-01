package org.pragmatica.aether.example.pgshowcase;

import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.List;

/// User persistence adapter.
///
/// Demonstrates CRUD auto-generation and query narrowing.
@PgSql
public interface UserPersistence {

    /// Full row record matching the users table.
    record UserRow(long id, String name, String email, boolean active, Instant createdAt, Instant updatedAt) {}

    /// Auto-generated: SELECT * FROM users WHERE id = $1
    @Table(UserRow.class)
    Promise<Option<UserRow>> findById(Long id);

    /// Auto-generated: INSERT INTO users (...) VALUES (...) RETURNING *
    Promise<UserRow> insert(UserRow user);

    /// Auto-generated: SELECT EXISTS(SELECT 1 FROM users WHERE id = $1)
    @Table(UserRow.class)
    Promise<Boolean> existsById(Long id);
}
