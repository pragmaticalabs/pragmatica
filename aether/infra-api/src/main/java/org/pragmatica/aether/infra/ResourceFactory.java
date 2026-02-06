package org.pragmatica.aether.infra;

import org.pragmatica.lang.Promise;

/**
 * SPI interface for creating infrastructure resources from configuration.
 * <p>
 * Resource factories are discovered via ServiceLoader and registered with
 * {@link ResourceProvider}. Each factory creates resources of a specific type
 * from a specific configuration class.
 * <p>
 * Example implementation:
 * <pre>{@code
 * public final class JdbcDatabaseConnectorFactory
 *        implements ResourceFactory<DatabaseConnector, DatabaseConnectorConfig> {
 *
 *     @Override
 *     public Class<DatabaseConnector> resourceType() {
 *         return DatabaseConnector.class;
 *     }
 *
 *     @Override
 *     public Class<DatabaseConnectorConfig> configType() {
 *         return DatabaseConnectorConfig.class;
 *     }
 *
 *     @Override
 *     public Promise<DatabaseConnector> provision(DatabaseConnectorConfig config) {
 *         return JdbcDatabaseConnector.jdbcDatabaseConnector(config);
 *     }
 * }
 * }</pre>
 * <p>
 * Registration via META-INF/services/org.pragmatica.aether.infra.ResourceFactory:
 * <pre>
 * org.pragmatica.aether.infra.db.jdbc.JdbcDatabaseConnectorFactory
 * </pre>
 *
 * @param <T> Resource type created by this factory
 * @param <C> Configuration type required to create the resource
 */
public interface ResourceFactory<T, C> {

    /**
     * Get the resource type this factory creates.
     *
     * @return Resource class
     */
    Class<T> resourceType();

    /**
     * Get the configuration type required to create resources.
     *
     * @return Configuration class
     */
    Class<C> configType();

    /**
     * Provision a resource instance from configuration.
     *
     * @param config Configuration for the resource
     * @return Promise containing the provisioned resource or error
     */
    Promise<T> provision(C config);
}
