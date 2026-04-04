package org.pragmatica.aether.slice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Meta-annotation for defining resource qualifiers.
///
/// Use this to create custom annotations that bind infrastructure resources
/// (like SqlConnector) to specific configuration sections in aether.toml.
///
/// Example defining a qualifier:
/// ```{@code
/// @ResourceQualifier(type = SqlConnector.class, config = "database.primary")
/// @Retention(RetentionPolicy.RUNTIME)
/// @Target(ElementType.PARAMETER)
/// public @interface PrimaryDb {}
/// }```
///
/// Example using the qualifier in a slice factory:
/// ```{@code
/// @Slice
/// public interface OrderRepository {
///     Promise<Order> save(Order order);
///
///     static OrderRepository orderRepository(
///             @PrimaryDb SqlConnector db,
///             InventoryService inventory) {
///         return new orderRepository(db, inventory);
///     }
/// }
/// }```
///
/// The annotation processor detects `@ResourceQualifier` on parameter annotations
/// and generates code that calls:
/// ```{@code
/// ctx.resources().provide(SqlConnector.class, "database.primary")
/// }```
///
/// Configuration in aether.toml:
/// ```
/// [database.primary]
/// jdbc_url = "jdbc:postgresql://localhost:5432/mydb"
/// username = "user"
/// password = "secret"
/// ```
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.ANNOTATION_TYPE) public@interface ResourceQualifier {
    Class<?> type();
    String config();
}
