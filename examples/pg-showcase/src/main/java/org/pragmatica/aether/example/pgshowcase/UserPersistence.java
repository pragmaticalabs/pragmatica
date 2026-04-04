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
@PgSql public interface UserPersistence {
    record UserRow(long id, String name, String email, boolean active, Instant createdAt, Instant updatedAt){}

    @Table(UserRow.class) Promise<Option<UserRow>> findById(Long id);
    Promise<UserRow> insert(UserRow user);
    @Table(UserRow.class) Promise<Boolean> existsById(Long id);
}
