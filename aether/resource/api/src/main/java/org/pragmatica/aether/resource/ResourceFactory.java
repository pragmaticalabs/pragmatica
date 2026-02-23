package org.pragmatica.aether.resource;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// SPI interface for creating infrastructure resources from configuration.
///
/// Resource factories are discovered via ServiceLoader and registered with
/// {@link ResourceProvider}. Each factory creates resources of a specific type
/// from a specific configuration class.
///
/// Example implementation:
/// ```{@code
/// public final class JdbcSqlConnectorFactory
///        implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {
///
///     @Override
///     public Class<SqlConnector> resourceType() {
///         return SqlConnector.class;
///     }
///
///     @Override
///     public Class<DatabaseConnectorConfig> configType() {
///         return DatabaseConnectorConfig.class;
///     }
///
///     @Override
///     public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
///         return JdbcSqlConnector.jdbcSqlConnector(config);
///     }
/// }
/// }```
///
/// Registration via META-INF/services/org.pragmatica.aether.resource.ResourceFactory:
/// ```
/// org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory
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

    /// Returns the priority of this factory.
    ///
    /// When multiple factories support the same resource type, the one with the
    /// highest priority that supports the current configuration is selected.
    /// Default is 0 (lowest priority — fallback).
    ///
    /// @return Factory priority (higher = more preferred)
    default int priority() {
        return 0;
    }

    /// Checks if this factory supports the given configuration.
    ///
    /// Used by SpiResourceProvider to select the appropriate factory when
    /// multiple factories are registered for the same resource type.
    /// For example, an R2DBC factory returns true only when r2dbcUrl is present.
    ///
    /// @param config Configuration to check
    /// @return true if this factory can handle the configuration
    default boolean supports(C config) {
        return true;
    }

    /// Close/release a resource instance.
    ///
    /// Default implementation handles AutoCloseable resources automatically.
    /// For non-AutoCloseable resources, this is a no-op.
    /// Override for custom cleanup logic.
    ///
    /// @param resource The resource instance to close
    /// @return Promise completing when the resource is closed
    default Promise<Unit> close(T resource) {
        if (resource instanceof AutoCloseable closeable) {
            return Promise.promise(promise -> {
                                       try{
                                           closeable.close();
                                           promise.succeed(Unit.unit());
                                       } catch (Exception e) {
                                           promise.succeed(Unit.unit());
                                       }
                                   });
        }
        return Promise.unitPromise();
    }
}
