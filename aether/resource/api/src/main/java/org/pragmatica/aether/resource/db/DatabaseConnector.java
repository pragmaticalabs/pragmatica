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
    /// Returns the connector configuration.
    ///
    /// @return Connector configuration
    DatabaseConnectorConfig config();

    /// Checks if the connection is healthy.
    ///
    /// @return Promise with true if healthy
    Promise<Boolean> isHealthy();

    /// Stops the connector and releases all resources (connection pools, etc.).
    ///
    /// @return Promise that completes when all resources are released
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }
}
