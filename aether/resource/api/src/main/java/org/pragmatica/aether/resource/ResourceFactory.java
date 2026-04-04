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
    Class<T> resourceType();
    Class<C> configType();
    Promise<T> provision(C config);

    default Promise<T> provision(C config, ProvisioningContext context) {
        return provision(config);
    }

    default int priority() {
        return 0;
    }

    default boolean supports(C config) {
        return true;
    }

    default Promise<Unit> close(T resource) {
        if (resource instanceof AutoCloseable closeable) {return Promise.promise(promise -> {
                                                                                     try {
                                                                                         closeable.close();
                                                                                         promise.succeed(Unit.unit());
                                                                                     } catch (Exception e) {
                                                                                         promise.succeed(Unit.unit());
                                                                                     }
                                                                                 });}
        return Promise.unitPromise();
    }
}
