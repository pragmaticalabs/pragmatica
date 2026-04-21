package org.pragmatica.aether.example.comprehensive;

import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.List;


/// Full CRUD with every auto-generated operator suffix the validator supports.
///
/// Operator suffixes emit SQL as:
/// - no suffix   → `col = $N`
/// - `Not`       → `col != $N`
/// - `Like`      → `col LIKE $N`
/// - `IsNull`    → `col IS NULL` (zero params)
/// - `IsNotNull` → `col IS NOT NULL` (zero params)
///
/// Operator suffixes requiring non-String parameter types (Between on
/// timestamptz, GreaterThan on numeric) are exercised through @Query in
/// BasePersistence where method parameter types can be primitives.
///
/// The `customers` table is used as the CRUD target since it has columns of
/// all interesting shapes: text, boolean, timestamptz (nullable via
/// deleted_at), plus a UNIQUE constraint on email.
@PgSql public interface CrudPersistence {
    record CustomerRow(long id,
                       String name,
                       String email,
                       boolean active,
                       Instant createdAt,
                       Instant deletedAt,
                       String tier,
                       String preferences){}

    Promise<CustomerRow> save(CustomerRow customer);
    @Table(CustomerRow.class) Promise<Option<CustomerRow>> findById(Long id);
    @Table(CustomerRow.class) Promise<Unit> deleteById(Long id);
    @Table(CustomerRow.class) Promise<Long> countByActive(Boolean active);
    @Table(CustomerRow.class) Promise<Boolean> existsById(Long id);
    Promise<List<CustomerRow>> findByTier(String tier);
    Promise<List<CustomerRow>> findByTierNot(String tier);
    Promise<List<CustomerRow>> findByNameLike(String pattern);
    Promise<List<CustomerRow>> findByDeletedAtIsNull();
    Promise<List<CustomerRow>> findByDeletedAtIsNotNull();
    Promise<List<CustomerRow>> findByTierAndActive(String tier, Boolean active);
    Promise<List<CustomerRow>> findByActiveOrderByNameAsc(Boolean active);
    Promise<List<CustomerRow>> findByActiveOrderByTierAscNameDesc(Boolean active);
}
