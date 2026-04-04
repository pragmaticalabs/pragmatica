package org.pragmatica.aether.example.pgshowcase;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;


/// Order persistence adapter.
///
/// Demonstrates @PgSql patterns from the spec:
/// - @Query with named parameters and joins
/// - Auto-generated CRUD (findById, findByStatus, save, deleteById)
/// - countByStatus, existsById
/// - Query record parameter
/// - Return type projection (OrderWithUserName = joined subset)
/// - OrderBy clause in auto-generated methods
@PgSql public interface OrderPersistence {
    record OrderRow(long id, long userId, BigDecimal total, String status, Instant createdAt){}

    record CreateOrderRequest(long userId, BigDecimal total, String status){}

    record OrderWithUserName(long id, String userName, BigDecimal total, String status){}

    @Query("SELECT o.id, u.name AS user_name, o.total, o.status FROM orders o JOIN users u ON o.user_id = u.id WHERE o.user_id = :userId") Promise<List<OrderWithUserName>> findOrdersWithUserName(Long userId);

    @Query("INSERT INTO orders VALUES(:request) RETURNING *") Promise<OrderRow> createOrder(CreateOrderRequest request);
    @Table(OrderRow.class) Promise<Option<OrderRow>> findById(Long id);
    Promise<List<OrderRow>> findByStatusOrderByCreatedAtDesc(String status);
    @Table(OrderRow.class) Promise<Long> countByStatus(String status);
    @Table(OrderRow.class) Promise<Boolean> existsById(Long id);
}
