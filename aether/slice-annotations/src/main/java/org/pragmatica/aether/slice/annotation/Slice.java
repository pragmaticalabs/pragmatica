package org.pragmatica.aether.slice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks an interface as an Aether slice implementation.
///
/// When applied to an interface, the annotation processor generates:
///
///   - API interface in the `.api` subpackage (same methods, for callers)
///   - Proxy classes for each dependency (delegates to SliceInvoker)
///   - Factory class that wires dependencies and creates the slice
///   - Manifest entries for artifact-to-class mapping
///
///
/// **Requirements:**
///
///   - Must be applied to an interface
///   - Interface must have a static factory method with lowercase-first name
///   - All non-static, non-default methods become slice entry points
///   - Entry point methods must return `Promise<T>`
///   - Entry point methods must have exactly one parameter
///
///
/// **Example:**
/// ```{@code
/// @Slice
/// public interface InventoryService {
///     Promise<StockAvailability> checkStock(CheckStockRequest request);
///     Promise<StockReservation> reserveStock(ReserveStockRequest request);
///
///     // Factory method - lowercase-first of interface name
///     static InventoryService inventoryService() {
///         return new InventoryServiceImpl();
///     }
/// }
/// }```
///
/// **With dependencies:**
/// ```{@code
/// @Slice
/// public interface PlaceOrder {
///     Promise<OrderResult> placeOrder(PlaceOrderRequest request);
///
///     // Dependencies are typed interfaces from other slices' API packages
///     static PlaceOrder placeOrder(InventoryService inventory, PricingService pricing) {
///         return new PlaceOrderImpl(inventory, pricing);
///     }
/// }
/// }```
///
/// @see <a href="https://github.com/siy/aether/blob/main/docs/typed-slice-api-design.md">Typed Slice API Design</a>
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Slice {}
