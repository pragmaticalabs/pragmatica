package org.pragmatica.aether.slice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation for defining resource qualifiers.
 * <p>
 * Use this to create custom annotations that bind infrastructure resources
 * (like DatabaseConnector) to specific configuration sections in aether.toml.
 * <p>
 * Example defining a qualifier:
 * <pre>{@code
 * @ResourceQualifier(type = DatabaseConnector.class, config = "database.primary")
 * @Retention(RetentionPolicy.RUNTIME)
 * @Target(ElementType.PARAMETER)
 * public @interface PrimaryDb {}
 * }</pre>
 * <p>
 * Example using the qualifier in a slice factory:
 * <pre>{@code
 * @Slice
 * public interface OrderRepository {
 *     Promise<Order> save(Order order);
 *
 *     static OrderRepository orderRepository(
 *             @PrimaryDb DatabaseConnector db,
 *             InventoryService inventory) {
 *         return new orderRepository(db, inventory);
 *     }
 * }
 * }</pre>
 * <p>
 * The annotation processor detects {@code @ResourceQualifier} on parameter annotations
 * and generates code that calls:
 * <pre>{@code
 * ctx.resources().provide(DatabaseConnector.class, "database.primary")
 * }</pre>
 * <p>
 * Configuration in aether.toml:
 * <pre>
 * [database.primary]
 * jdbc_url = "jdbc:postgresql://localhost:5432/mydb"
 * username = "user"
 * password = "secret"
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ResourceQualifier {

    /**
     * The resource type to provision.
     * <p>
     * This should be the interface type (e.g., DatabaseConnector.class),
     * not the implementation type.
     *
     * @return Resource interface class
     */
    Class<?> type();

    /**
     * The configuration section path.
     * <p>
     * Dot-separated path to the configuration section in aether.toml
     * (e.g., "database.primary", "cache.sessions").
     *
     * @return Configuration section path
     */
    String config();
}
