package org.pragmatica.aether.resource.db;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Resource qualifier for injecting SqlConnector instances.
///
/// Use this annotation on factory method parameters to inject a SqlConnector
/// configured from the "database" section of aether.toml.
///
/// Example usage in a slice factory:
/// ```{@code
/// @Slice
/// public interface OrderRepository {
///     Promise<Order> save(Order order);
///
///     static OrderRepository orderRepository(@Sql SqlConnector db) {
///         return new orderRepository(db);
///     }
/// }
/// }```
///
/// For multiple database connections, create custom qualifiers:
/// ```{@code
/// @ResourceQualifier(type = SqlConnector.class, config = "database.primary")
/// @Retention(RetentionPolicy.RUNTIME)
/// @Target(ElementType.PARAMETER)
/// public @interface PrimaryDb {}
/// }```
///
/// Transport (JDBC or R2DBC) is selected automatically based on configuration:
/// - If `r2dbc_url` is present → R2DBC transport
/// - Otherwise → JDBC transport (default)
///
/// @see SqlConnector
/// @see ResourceQualifier
@ResourceQualifier(type = SqlConnector.class, config = "database")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Sql {}
