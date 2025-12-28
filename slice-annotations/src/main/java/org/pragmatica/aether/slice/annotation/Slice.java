package org.pragmatica.aether.slice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an Aether slice implementation.
 * <p>
 * When applied to an interface, the annotation processor generates:
 * <ul>
 *   <li>API interface in the {@code .api} subpackage (same methods, for callers)</li>
 *   <li>Proxy classes for each dependency (delegates to SliceInvoker)</li>
 *   <li>Factory class that wires dependencies and creates the slice</li>
 *   <li>Manifest entries for artifact-to-class mapping</li>
 * </ul>
 * <p>
 * <b>Requirements:</b>
 * <ul>
 *   <li>Must be applied to an interface</li>
 *   <li>Interface must have a static factory method with lowercase-first name</li>
 *   <li>All non-static, non-default methods become slice entry points</li>
 *   <li>Entry point methods must return {@code Promise<T>}</li>
 *   <li>Entry point methods must have exactly one parameter</li>
 * </ul>
 * <p>
 * <b>Example:</b>
 * <pre>{@code
 * @Slice
 * public interface InventoryService {
 *     Promise<StockAvailability> checkStock(CheckStockRequest request);
 *     Promise<StockReservation> reserveStock(ReserveStockRequest request);
 *
 *     // Factory method - lowercase-first of interface name
 *     static InventoryService inventoryService() {
 *         return new InventoryServiceImpl();
 *     }
 * }
 * }</pre>
 * <p>
 * <b>With dependencies:</b>
 * <pre>{@code
 * @Slice
 * public interface PlaceOrder {
 *     Promise<OrderResult> placeOrder(PlaceOrderRequest request);
 *
 *     // Dependencies are typed interfaces from other slices' API packages
 *     static PlaceOrder placeOrder(InventoryService inventory, PricingService pricing) {
 *         return new PlaceOrderImpl(inventory, pricing);
 *     }
 * }
 * }</pre>
 *
 * @see <a href="https://github.com/siy/aether/blob/main/docs/typed-slice-api-design.md">Typed Slice API Design</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Slice {
    // Marker annotation - no parameters needed
    // All metadata derived from interface structure
}
