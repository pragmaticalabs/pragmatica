package org.pragmatica.aether.resource.db.jooq;

import org.pragmatica.aether.resource.db.DatabaseConnector;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;
import org.jooq.SQLDialect;


/// Type-safe jOOQ connector for database operations.
///
/// Extends DatabaseConnector with jOOQ-specific operations for type-safe query building.
/// Does NOT duplicate raw SQL methods — use {@code dsl().resultQuery(sql, params)} for
/// occasional raw SQL within a jOOQ context.
///
/// Transport (JDBC or R2DBC) is selected at runtime based on configuration URL.
///
/// Example usage:
/// ```{@code
/// var query = connector.dsl()
///     .select(USERS.NAME, USERS.EMAIL)
///     .from(USERS)
///     .where(USERS.ID.eq(userId));
/// connector.fetchOne(query)
///          .map(record -> new User(record.get(USERS.NAME), record.get(USERS.EMAIL)))
/// }```
public interface JooqConnector extends DatabaseConnector {
    DSLContext dsl();
    <R extends Record> Promise<R> fetchOne(ResultQuery<R> query);
    <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query);
    <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query);
    Promise<Integer> execute(Query query);
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    @FunctionalInterface interface TransactionCallback<T> {
        Promise<T> execute(JooqConnector connector);
    }

    static SQLDialect mapDialect(DatabaseType type) {
        return switch (type){
            case POSTGRESQL, COCKROACHDB -> SQLDialect.POSTGRES;
            case MYSQL -> SQLDialect.MYSQL;
            case MARIADB -> SQLDialect.MARIADB;
            case H2 -> SQLDialect.H2;
            case SQLITE -> SQLDialect.SQLITE;
            case ORACLE, SQLSERVER, DB2 -> SQLDialect.DEFAULT;
        };
    }

    static <R extends Record> R extractSingleResult(org.jooq.Result<R> result) {
        if (result.isEmpty()) {return DatabaseConnectorError.ResultNotFound.INSTANCE.<R>result().unwrap();}
        if (result.size() > 1) {return DatabaseConnectorError.multipleResults(result.size()).<R>result()
                                                                             .unwrap();}
        return result.getFirst();
    }

    static <R extends Record> Option<R> extractOptionalResult(org.jooq.Result<R> result) {
        return result.isEmpty()
              ? Option.none()
              : Option.some(result.getFirst());
    }
}
