package org.pragmatica.aether.demo.order.inventory;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory Service Slice - manages product stock and reservations.
 */
public record InventoryServiceSlice() implements Slice {

    // Mock stock data
    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>(Map.of(
        "PROD-ABC123", 100,
        "PROD-DEF456", 50,
        "PROD-GHI789", 25
    ));

    private static final Map<String, ReservedStock> RESERVATIONS = new ConcurrentHashMap<>();

    private record ReservedStock(String productId, int quantity) {}

    public static InventoryServiceSlice inventoryServiceSlice() {
        return new InventoryServiceSlice();
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("checkStock").unwrap(),
                this::checkStock,
                new TypeToken<StockAvailability>() {},
                new TypeToken<CheckStockRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("reserveStock").unwrap(),
                this::reserveStock,
                new TypeToken<StockReservation>() {},
                new TypeToken<ReserveStockRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("releaseStock").unwrap(),
                this::releaseStock,
                new TypeToken<StockReleased>() {},
                new TypeToken<ReleaseStockRequest>() {}
            )
        );
    }

    private Promise<StockAvailability> checkStock(CheckStockRequest request) {
        var productId = request.productId();
        var available = STOCK.getOrDefault(productId, 0);

        if (available == 0) {
            return new InventoryError.ProductNotFound(productId).promise();
        }

        var sufficient = available >= request.quantity();
        return Promise.success(new StockAvailability(productId, available, sufficient));
    }

    private Promise<StockReservation> reserveStock(ReserveStockRequest request) {
        var productId = request.productId();
        var available = STOCK.getOrDefault(productId, 0);
        var requested = request.quantity();

        if (available == 0) {
            return new InventoryError.ProductNotFound(productId).promise();
        }

        if (available < requested) {
            return new InventoryError.InsufficientStock(productId, requested, available).promise();
        }

        // Reserve atomically
        STOCK.compute(productId, (k, v) -> v - requested);

        var reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        RESERVATIONS.put(reservationId, new ReservedStock(productId, requested));

        return Promise.success(new StockReservation(reservationId, productId, requested));
    }

    private Promise<StockReleased> releaseStock(ReleaseStockRequest request) {
        var reservation = RESERVATIONS.remove(request.reservationId());

        if (reservation == null) {
            return new InventoryError.ReservationNotFound(request.reservationId()).promise();
        }

        STOCK.compute(reservation.productId(), (k, v) -> v + reservation.quantity());

        return Promise.success(new StockReleased(request.reservationId()));
    }
}
