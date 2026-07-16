package org.pragmatica.aether.example.pgshowcase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


@PgSql
public interface OrderPersistence {
    record OrderRow(long id,
                    long userId,
                    BigDecimal total,
                    String status,
                    Instant createdAt,
                    String metadata,
                    UUID correlationId,
                    BigDecimal totalWithTax) {}

    record OrderSummary(long id, long userId, BigDecimal total, String status) {}

    record OrderWithUserName(long id, String userName, BigDecimal total, String status) {}

    @Query("SELECT o.id, u.name AS user_name, o.total, o.status "
          + "FROM orders o JOIN users u ON o.user_id = u.id "
          + "WHERE o.user_id = :userId")
    Promise<List<OrderWithUserName>> findOrdersWithUserName(Long userId);

    @Query("SELECT id, user_id, total, status FROM orders "
          + "WHERE user_id IN (SELECT id FROM users WHERE email LIKE :domain)")
    Promise<List<OrderSummary>> findOrdersByUserEmailDomain(String domain);

    @Query("INSERT INTO orders (user_id, total, status) " + "VALUES (:userId, :total, :status) RETURNING *")
    Promise<OrderRow> createOrder(Long userId, double total, String status);

    @Query("UPDATE orders SET status = :status WHERE id = :id")
    Promise<Unit> updateStatus(String status, Long id);

    Promise<OrderRow> save(OrderRow order);

    @Table(OrderRow.class)
    Promise<Option<OrderRow>> findById(Long id);

    @Table(OrderRow.class)
    Promise<Unit> deleteById(Long id);

    Promise<List<OrderRow>> findByStatusOrderByCreatedAtDesc(String status);
    Promise<List<OrderRow>> findByStatusNot(String status);

    @Table(OrderRow.class)
    Promise<Long> countByStatus(String status);

    @Table(OrderRow.class)
    Promise<Boolean> existsById(Long id);
}
