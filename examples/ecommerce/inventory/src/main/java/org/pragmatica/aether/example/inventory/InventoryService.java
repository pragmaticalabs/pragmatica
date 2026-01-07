package org.pragmatica.aether.example.inventory;

import org.pragmatica.aether.example.shared.LineItem;
import org.pragmatica.aether.example.shared.OrderId;
import org.pragmatica.aether.example.shared.ProductId;
import org.pragmatica.aether.example.shared.Quantity;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Inventory management slice.
 * Manages product stock levels, availability checking, and reservations.
 */
@Slice
public interface InventoryService {
    // === Requests ===
    record CheckStockRequest(List<LineItem> items) {
        public static CheckStockRequest checkStockRequest(List<LineItem> items) {
            return new CheckStockRequest(List.copyOf(items));
        }
    }

    record ReserveStockRequest(OrderId orderId, List<LineItem> items) {
        public static ReserveStockRequest reserveStockRequest(OrderId orderId, List<LineItem> items) {
            return new ReserveStockRequest(orderId, List.copyOf(items));
        }
    }

    record ReleaseStockRequest(String reservationId) {
        public static ReleaseStockRequest releaseStockRequest(String reservationId) {
            return new ReleaseStockRequest(reservationId);
        }
    }

    // === Responses ===
    record StockAvailability(Map<ProductId, Quantity> availableStock, List<ProductId> unavailableItems) {
        public static StockAvailability fullyAvailable(Map<ProductId, Quantity> stock) {
            return new StockAvailability(Map.copyOf(stock), List.of());
        }

        public static StockAvailability partiallyAvailable(Map<ProductId, Quantity> stock,
                                                           List<ProductId> unavailable) {
            return new StockAvailability(Map.copyOf(stock), List.copyOf(unavailable));
        }

        public boolean isFullyAvailable() {
            return unavailableItems.isEmpty();
        }

        public boolean hasUnavailableItems() {
            return ! unavailableItems.isEmpty();
        }
    }

    record StockReservation(String reservationId, OrderId orderId, Instant expiresAt) {
        private static final long RESERVATION_DURATION_MINUTES = 15;

        public static StockReservation stockReservation(OrderId orderId) {
            return new StockReservation(IdGenerator.generate("RES"),
                                        orderId,
                                        Instant.now()
                                               .plusSeconds(RESERVATION_DURATION_MINUTES * 60));
        }

        public boolean isExpired() {
            return Instant.now()
                          .isAfter(expiresAt);
        }
    }

    // === Errors ===
    sealed interface InventoryError extends Cause {
        record InsufficientStock(List<ProductId> products) implements InventoryError {
            @Override
            public String message() {
                var ids = products.stream()
                                  .map(ProductId::value)
                                  .toList();
                return "Insufficient stock for products: " + ids;
            }
        }

        record ReservationNotFound(String reservationId) implements InventoryError {
            @Override
            public String message() {
                return "Reservation not found: " + reservationId;
            }
        }

        record ReservationExpired(String reservationId) implements InventoryError {
            @Override
            public String message() {
                return "Reservation expired: " + reservationId;
            }
        }

        static InsufficientStock insufficientStock(List<ProductId> products) {
            return new InsufficientStock(List.copyOf(products));
        }
    }

    // === Operations ===
    Promise<StockAvailability> checkStock(CheckStockRequest request);

    Promise<StockReservation> reserveStock(ReserveStockRequest request);

    Promise<Unit> releaseStock(ReleaseStockRequest request);

    // === Factory ===
    static InventoryService inventoryService() {
        return new InventoryServiceImpl();
    }
}

/**
 * In-memory implementation for demo purposes.
 */
class InventoryServiceImpl implements InventoryService {
    private final Map<ProductId, Quantity> stock = new ConcurrentHashMap<>();
    private final Map<String, ReserveStockRequest> reservations = new ConcurrentHashMap<>();

    InventoryServiceImpl() {
        initializeDemoStock();
    }

    @Override
    public Promise<StockAvailability> checkStock(CheckStockRequest request) {
        return Promise.success(calculateAvailability(request.items()));
    }

    @Override
    public Promise<StockReservation> reserveStock(ReserveStockRequest request) {
        var availability = calculateAvailability(request.items());
        if (availability.hasUnavailableItems()) {
            return InventoryError.insufficientStock(availability.unavailableItems())
                                 .promise();
        }
        var reservation = StockReservation.stockReservation(request.orderId());
        reservations.put(reservation.reservationId(), request);
        request.items()
               .forEach(this::decrementStock);
        return Promise.success(reservation);
    }

    @Override
    public Promise<Unit> releaseStock(ReleaseStockRequest request) {
        var reserved = reservations.remove(request.reservationId());
        if (reserved != null) {
            reserved.items()
                    .forEach(this::incrementStock);
        }
        return Promise.success(Unit.unit());
    }

    private StockAvailability calculateAvailability(List<LineItem> items) {
        var available = items.stream()
                             .collect(Collectors.toMap(LineItem::productId,
                                                       item -> stock.getOrDefault(item.productId(),
                                                                                  Quantity.ZERO)));
        var unavailable = items.stream()
                               .filter(item -> available.get(item.productId())
                                                        .value() < item.quantity()
                                                                       .value())
                               .map(LineItem::productId)
                               .toList();
        return unavailable.isEmpty()
               ? StockAvailability.fullyAvailable(available)
               : StockAvailability.partiallyAvailable(available, unavailable);
    }

    private void decrementStock(LineItem item) {
        stock.computeIfPresent(item.productId(),
                               (_, current) -> current.subtract(item.quantity()));
    }

    private void incrementStock(LineItem item) {
        stock.compute(item.productId(),
                      (_, current) -> current == null
                                      ? item.quantity()
                                      : current.add(item.quantity()));
    }

    private void initializeDemoStock() {
        addStock("LAPTOP-PRO", 50);
        addStock("MOUSE-WIRELESS", 200);
        addStock("KEYBOARD-MECH", 100);
        addStock("MONITOR-4K", 30);
        addStock("HEADSET-BT", 75);
        addStock("WEBCAM-HD", 60);
        addStock("USB-HUB", 150);
        addStock("CHARGER-65W", 120);
    }

    private void addStock(String productId, int quantity) {
        ProductId.productId(productId)
                 .flatMap(id -> Quantity.quantity(quantity)
                                        .map(qty -> Map.entry(id, qty)))
                 .onSuccess(entry -> stock.put(entry.getKey(),
                                               entry.getValue()));
    }
}
