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
@PgSql
public interface OrderPersistence {

    /// Full row record matching the orders table.
    record OrderRow(long id, long userId, BigDecimal total, String status, Instant createdAt) {}

    /// Query record for creating orders.
    record CreateOrderRequest(long userId, BigDecimal total, String status) {}

    /// Joined projection: order with user name.
    record OrderWithUserName(long id, String userName, BigDecimal total, String status) {}

    // ========== @Query methods ==========

    /// Explicit SQL with named parameters and JOIN.
    @Query("SELECT o.id, u.name AS user_name, o.total, o.status FROM orders o JOIN users u ON o.user_id = u.id WHERE o.user_id = :userId")
    Promise<List<OrderWithUserName>> findOrdersWithUserName(Long userId);

    /// INSERT with record expansion.
    @Query("INSERT INTO orders VALUES(:request) RETURNING *")
    Promise<OrderRow> createOrder(CreateOrderRequest request);

    // ========== Auto-generated CRUD methods ==========

    /// Auto-generated: SELECT * FROM orders WHERE id = $1
    @Table(OrderRow.class)
    Promise<Option<OrderRow>> findById(Long id);

    /// Auto-generated: SELECT * FROM orders WHERE status = $1 ORDER BY created_at DESC
    Promise<List<OrderRow>> findByStatusOrderByCreatedAtDesc(String status);

    /// Auto-generated: SELECT count(*) FROM orders WHERE status = $1
    @Table(OrderRow.class)
    Promise<Long> countByStatus(String status);

    /// Auto-generated: SELECT EXISTS(SELECT 1 FROM orders WHERE id = $1)
    @Table(OrderRow.class)
    Promise<Boolean> existsById(Long id);
}
