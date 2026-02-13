package org.pragmatica.aether.infra.db;

import org.pragmatica.lang.Result;

/// Factory for creating DatabaseConnector instances.
///
/// Implementations are discovered via ServiceLoader and selected based on
/// the configuration and available drivers.
public interface DatabaseConnectorFactory {

    /// Creates a new DatabaseConnector with the given configuration.
    ///
    /// @param config Connector configuration
    /// @return Result with connector or failure
    Result<DatabaseConnector> create(DatabaseConnectorConfig config);

    /// Returns the priority of this factory.
    ///
    /// Higher priority factories are tried first. Default implementations
    /// should return 0, specialized implementations should return higher values.
    ///
    /// @return Factory priority (higher = more preferred)
    default int priority() {
        return 0;
    }

    /// Checks if this factory supports the given database type.
    ///
    /// @param type Database type
    /// @return true if this factory can create connectors for this type
    default boolean supports(DatabaseType type) {
        return true;
    }

    /// Returns a descriptive name for this factory.
    ///
    /// @return Factory name
    String name();
}
