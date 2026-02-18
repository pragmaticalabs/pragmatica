package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;

/// SPI interface for creating infrastructure resources from configuration.
///
/// Resource factories are discovered via ServiceLoader and registered with
/// {@link ResourceProvider}. Each factory creates resources of a specific type
/// from a specific configuration class.
///
/// Example implementation:
/// ```{@code
/// public final class JdbcDatabaseConnectorFactory
///        implements ResourceFactory<DatabaseConnector, DatabaseConnectorConfig> {
///
///     @Override
///     public Class<DatabaseConnector> resourceType() {
///         return DatabaseConnector.class;
///     }
///
///     @Override
///     public Class<DatabaseConnectorConfig> configType() {
///         return DatabaseConnectorConfig.class;
///     }
///
///     @Override
///     public Promise<DatabaseConnector> provision(DatabaseConnectorConfig config) {
///         return JdbcDatabaseConnector.jdbcDatabaseConnector(config);
///     }
/// }
/// }```
///
/// Registration via META-INF/services/org.pragmatica.aether.resource.ResourceFactory:
/// ```
/// org.pragmatica.aether.resource.db.jdbc.JdbcDatabaseConnectorFactory
/// ```
///
/// @param <T> Resource type created by this factory
/// @param <C> Configuration type required to create the resource
public interface ResourceFactory<T, C> {
    /// Get the resource type this factory creates.
    ///
    /// @return Resource class
    Class<T> resourceType();

    /// Get the configuration type required to create resources.
    ///
    /// @return Configuration class
    Class<C> configType();

    /// Provision a resource instance from configuration.
    ///
    /// @param config Configuration for the resource
    /// @return Promise containing the provisioned resource or error
    Promise<T> provision(C config);

    /// Provision a resource instance from configuration with additional provisioning context.
    ///
    /// The context carries type tokens and key extractors that factories may use
    /// for generic type handling or sharded resource creation.
    /// Default implementation delegates to {@link #provision(Object)}, ignoring the context.
    ///
    /// @param config  Configuration for the resource
    /// @param context Provisioning context with type tokens and key extractor
    /// @return Promise containing the provisioned resource or error
    default Promise<T> provision(C config, ProvisioningContext context) {
        return provision(config);
    }
}
