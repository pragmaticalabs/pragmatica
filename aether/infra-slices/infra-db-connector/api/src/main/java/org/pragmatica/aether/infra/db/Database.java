package org.pragmatica.aether.infra.db;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Resource qualifier for injecting DatabaseConnector instances.
///
/// Use this annotation on factory method parameters to inject a DatabaseConnector
/// configured from the "database" section of aether.toml.
///
/// Example usage in a slice factory:
/// ```{@code
/// @Slice
/// public interface OrderRepository {
///     Promise<Order> save(Order order);
///
///     static OrderRepository orderRepository(@Database DatabaseConnector db) {
///         return new orderRepository(db);
///     }
/// }
/// }```
///
/// Configuration in aether.toml:
/// ```
/// [database]
/// jdbc_url = "jdbc:postgresql://localhost:5432/mydb"
/// username = "user"
/// password = "secret"
/// pool.min_size = 5
/// pool.max_size = 20
/// ```
///
/// For multiple database connections, create custom qualifiers:
/// ```{@code
/// @ResourceQualifier(type = DatabaseConnector.class, config = "database.primary")
/// @Retention(RetentionPolicy.RUNTIME)
/// @Target(ElementType.PARAMETER)
/// public @interface PrimaryDb {}
///
/// @ResourceQualifier(type = DatabaseConnector.class, config = "database.analytics")
/// @Retention(RetentionPolicy.RUNTIME)
/// @Target(ElementType.PARAMETER)
/// public @interface AnalyticsDb {}
/// }```
///
/// @see ResourceQualifier
/// @see DatabaseConnector
@ResourceQualifier(type = DatabaseConnector.class, config = "database")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Database {}
