package org.pragmatica.aether.example.inventory;

import org.pragmatica.aether.example.shared.LineItem;
import org.pragmatica.aether.example.shared.OrderId;
import org.pragmatica.aether.example.shared.ProductId;
import org.pragmatica.aether.example.shared.Quantity;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slice public interface InventoryService {
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
            return! unavailableItems.isEmpty();
        }
    }

    record StockReservation(String reservationId, OrderId orderId, Instant expiresAt) {
        private static final long RESERVATION_DURATION_MINUTES = 15;

        public static StockReservation stockReservation(OrderId orderId) {
            return new StockReservation(IdGenerator.generate("RES"),
                                        orderId,
                                        Instant.now().plusSeconds(RESERVATION_DURATION_MINUTES * 60));
        }

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    sealed interface InventoryError extends Cause {
        record InsufficientStock(List<ProductId> products) implements InventoryError {
            @Override public String message() {
                var ids = products.stream().map(ProductId::value)
                                         .toList();
                return "Insufficient stock for products: " + ids;
            }
        }

        record ReservationNotFound(String reservationId) implements InventoryError {
            @Override public String message() {
                return "Reservation not found: " + reservationId;
            }
        }

        record ReservationExpired(String reservationId) implements InventoryError {
            @Override public String message() {
                return "Reservation expired: " + reservationId;
            }
        }

        static InsufficientStock insufficientStock(List<ProductId> products) {
            return new InsufficientStock(List.copyOf(products));
        }
    }

    record ProductStock(ProductId productId, Quantity available){}

    Promise<StockAvailability> checkStock(CheckStockRequest request);
    Promise<StockReservation> reserveStock(ReserveStockRequest request);
    Promise<Unit> releaseStock(ReleaseStockRequest request);
    String SELECT_STOCK = "SELECT product_id, stock FROM products WHERE product_id = ?";
    String UPDATE_STOCK = "UPDATE products SET stock = stock - ? WHERE product_id = ? AND stock >= ?";
    String RESTORE_STOCK = "UPDATE products SET stock = stock + ? WHERE product_id = ?";
    String INSERT_RESERVATION = "INSERT INTO stock_reservations (reservation_id, order_id, expires_at) VALUES (?, ?, ?)";
    String INSERT_RESERVATION_ITEM = "INSERT INTO reservation_items (reservation_id, product_id, quantity) VALUES (?, ?, ?)";
    String SELECT_RESERVATION_ITEMS = "SELECT product_id, quantity FROM reservation_items WHERE reservation_id = ?";
    String DELETE_RESERVATION_ITEMS = "DELETE FROM reservation_items WHERE reservation_id = ?";
    String DELETE_RESERVATION = "DELETE FROM stock_reservations WHERE reservation_id = ?";

    static InventoryService inventoryService(@Sql SqlConnector db) {
        record inventoryService( SqlConnector db) implements InventoryService {
            @Override public Promise<StockAvailability> checkStock(CheckStockRequest request) {
                return fetchStockLevels(request.items()).map(stockMap -> buildAvailability(request.items(), stockMap));
            }

            @Override public Promise<StockReservation> reserveStock(ReserveStockRequest request) {
                return checkStock(CheckStockRequest.checkStockRequest(request.items())).flatMap(availability -> availability.hasUnavailableItems()
                                                                                                               ? InventoryError.insufficientStock(availability.unavailableItems()).promise()
                                                                                                               : performReservation(request));
            }

            @Override public Promise<Unit> releaseStock(ReleaseStockRequest request) {
                return db.transactional(conn -> restoreStockFromReservation(conn, request.reservationId()));
            }

            private Promise<Map<ProductId, Quantity>> fetchStockLevels(List<LineItem> items) {
                var promises = items.stream().map(item -> fetchSingleStock(item.productId()))
                                           .toList();
                return Promise.allOf(promises).flatMap(results -> Result.allOf(results).async())
                                    .map(inventoryService::toStockMap);
            }

            private Promise<ProductStock> fetchSingleStock(ProductId productId) {
                return db.queryOptional(SELECT_STOCK,
                                        row -> row.getInt("stock").flatMap(Quantity::quantity)
                                                         .map(qty -> new ProductStock(productId, qty)),
                                        productId.value()).map(opt -> opt.or(new ProductStock(productId, Quantity.ZERO)));
            }

            private static Map<ProductId, Quantity> toStockMap(List<ProductStock> stocks) {
                return stocks.stream().collect(Collectors.toMap(ProductStock::productId, ProductStock::available));
            }

            private static StockAvailability buildAvailability(List<LineItem> items,
                                                               Map<ProductId, Quantity> stockMap) {
                var unavailable = items.stream().filter(item -> stockMap.getOrDefault(item.productId(),
                                                                                      Quantity.ZERO).value() < item.quantity().value())
                                              .map(LineItem::productId)
                                              .toList();
                return unavailable.isEmpty()
                       ? StockAvailability.fullyAvailable(stockMap)
                       : StockAvailability.partiallyAvailable(stockMap, unavailable);
            }

            private Promise<StockReservation> performReservation(ReserveStockRequest request) {
                var reservation = StockReservation.stockReservation(request.orderId());
                return db.transactional(conn -> reserveInTransaction(conn, reservation, request.items()));
            }

            private Promise<StockReservation> reserveInTransaction(SqlConnector conn,
                                                                   StockReservation reservation,
                                                                   List<LineItem> items) {
                return conn.update(INSERT_RESERVATION,
                                   reservation.reservationId(),
                                   reservation.orderId().value(),
                                   reservation.expiresAt()).flatMap(_ -> decrementAllStock(conn, items))
                                  .flatMap(_ -> insertAllReservationItems(conn,
                                                                          reservation.reservationId(),
                                                                          items))
                                  .map(_ -> reservation);
            }

            private Promise<Integer> decrementAllStock(SqlConnector conn, List<LineItem> items) {
                var updates = items.stream().map(item -> conn.update(UPDATE_STOCK,
                                                                     item.quantity().value(),
                                                                     item.productId().value(),
                                                                     item.quantity().value()))
                                          .toList();
                return Promise.allOf(updates).flatMap(results -> Result.allOf(results).async())
                                    .map(counts -> counts.stream().mapToInt(Integer::intValue)
                                                                .sum());
            }

            private Promise<Integer> insertAllReservationItems(SqlConnector conn,
                                                               String reservationId,
                                                               List<LineItem> items) {
                var inserts = items.stream().map(item -> conn.update(INSERT_RESERVATION_ITEM,
                                                                     reservationId,
                                                                     item.productId().value(),
                                                                     item.quantity().value()))
                                          .toList();
                return Promise.allOf(inserts).flatMap(results -> Result.allOf(results).async())
                                    .map(counts -> counts.stream().mapToInt(Integer::intValue)
                                                                .sum());
            }

            private Promise<Unit> restoreStockFromReservation(SqlConnector conn, String reservationId) {
                return conn.queryList(SELECT_RESERVATION_ITEMS,
                                      row -> Result.all(row.getString("product_id"),
                                                        row.getInt("quantity")).map(ReservationItem::new),
                                      reservationId).flatMap(items -> restoreAllStock(conn, items))
                                     .flatMap(_ -> conn.update(DELETE_RESERVATION_ITEMS, reservationId))
                                     .flatMap(_ -> conn.update(DELETE_RESERVATION, reservationId))
                                     .mapToUnit();
            }

            private Promise<Integer> restoreAllStock(SqlConnector conn, List<ReservationItem> items) {
                var updates = items.stream().map(item -> conn.update(RESTORE_STOCK,
                                                                     item.quantity(),
                                                                     item.productId()))
                                          .toList();
                return Promise.allOf(updates).flatMap(results -> Result.allOf(results).async())
                                    .map(counts -> counts.stream().mapToInt(Integer::intValue)
                                                                .sum());
            }
        }
        return new inventoryService(db);
    }

    record ReservationItem(String productId, int quantity){}
}
