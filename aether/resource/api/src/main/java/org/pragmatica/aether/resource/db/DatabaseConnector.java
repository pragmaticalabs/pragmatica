package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Base database connector — lifecycle contract only.
///
/// Concrete query APIs are defined in subtypes:
///   - {@link SqlConnector} for raw SQL operations
///   - JooqConnector (in resource-db-jooq-api) for type-safe jOOQ operations
///
/// Transport (JDBC vs R2DBC) is selected at runtime based on configuration URL.
public interface DatabaseConnector {
    DatabaseConnectorConfig config();
    Promise<Boolean> isHealthy();

    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }
}
