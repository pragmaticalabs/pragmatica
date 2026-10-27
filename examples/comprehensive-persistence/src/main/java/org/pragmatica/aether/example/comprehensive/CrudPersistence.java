package org.pragmatica.aether.example.comprehensive;

import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


@PgSql
public interface CrudPersistence {
    /// Stored in the jsonb column `customers.preferences`. The CRUD table says the column is jsonb, so the record is
    /// read and written as a JSON document without `@Jsonb`. A document written before a component existed decodes
    /// with it empty, which is why every component is an `Option`.
    record Preferences(Option<String> theme, Option<String> language) {}

    record CustomerRow(long id,
                       String name,
                       String email,
                       boolean active,
                       Instant createdAt,
                       Instant deletedAt,
                       String tier,
                       Preferences preferences) {}

    Promise<CustomerRow> save(CustomerRow customer);

    @Table(CustomerRow.class)
    Promise<Option<CustomerRow>> findById(Long id);

    @Table(CustomerRow.class)
    Promise<Unit> deleteById(Long id);

    @Table(CustomerRow.class)
    Promise<Long> countByActive(Boolean active);

    @Table(CustomerRow.class)
    Promise<Boolean> existsById(Long id);

    Promise<List<CustomerRow>> findByTier(String tier);
    Promise<List<CustomerRow>> findByTierNot(String tier);
    Promise<List<CustomerRow>> findByNameLike(String pattern);
    Promise<List<CustomerRow>> findByDeletedAtIsNull();
    Promise<List<CustomerRow>> findByDeletedAtIsNotNull();
    Promise<List<CustomerRow>> findByTierAndActive(String tier, Boolean active);
    Promise<List<CustomerRow>> findByActiveOrderByNameAsc(Boolean active);
    Promise<List<CustomerRow>> findByActiveOrderByTierAscNameDesc(Boolean active);
}
