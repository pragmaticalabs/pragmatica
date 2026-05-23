package org.pragmatica.aether.example.pgshowcase;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.time.Instant;
import java.util.List;


@PgSql
public interface UserPersistence {
    record UserRow(long id,
                   String name,
                   String email,
                   boolean active,
                   Instant createdAt,
                   Instant updatedAt,
                   Instant deletedAt) {}

    @Table(UserRow.class)
    Promise<Option<UserRow>> findById(Long id);

    Promise<UserRow> insert(UserRow user);

    @Table(UserRow.class)
    Promise<Boolean> existsById(Long id);

    Promise<List<UserRow>> findByNameLike(String pattern);
    Promise<List<UserRow>> findByDeletedAtIsNull();
    Promise<List<UserRow>> findByDeletedAtIsNotNull();

    @Query("SELECT count(*) FROM users WHERE active = :active AND deleted_at IS NULL")
    Promise<Long> countActiveUsers(Boolean active);
}
