package org.pragmatica.aether.example.comprehensive;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.lang.Promise;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


/// Analytical queries over the primary schema.
///
/// Showcases:
/// - JOINs across three tables with alias resolution
/// - Aggregations (`count`, `sum`, `min`, `max`) with `GROUP BY`
/// - Subqueries in WHERE and in SELECT
/// - Complex WHERE combining `AND` and `OR`
/// - Common Table Expressions (`WITH`)
/// - `TEXT[]` array columns mapped to `String[]`
/// - Non-primitive `@Query` parameters (`BigDecimal`, `Instant`, `UUID`)
/// - Non-primitive scalar returns (`Promise<BigDecimal>` from an aggregate query)
/// - Narrow projections for each shape so the validator rewrites `SELECT *`.
@PgSql public interface BasePersistence {
    record CustomerOrder(long orderId,
                         long customerId,
                         String customerName,
                         String customerEmail,
                         BigDecimal orderTotal,
                         String orderStatus,
                         Instant orderCreatedAt){}

    record CustomerRevenue(long customerId,
                           String customerName,
                           Long orderCount,
                           BigDecimal totalRevenue,
                           BigDecimal minOrderValue,
                           BigDecimal maxOrderValue){}

    record ProductSales(long productId,
                        String productSku,
                        String productName,
                        Long unitsSold,
                        BigDecimal totalRevenue){}

    record OrderDetail(long id,
                       long customerId,
                       BigDecimal total,
                       BigDecimal totalWithTax,
                       String status,
                       String metadata,
                       UUID correlationId){}

    record ProductWithTags(long id, String sku, String name, BigDecimal price, String[] tags){}

    @Query("SELECT o.id AS order_id, c.id AS customer_id, c.name AS customer_name, " + "c.email AS customer_email, o.total AS order_total, o.status AS order_status, " + "o.created_at AS order_created_at " + "FROM customers c JOIN orders o ON o.customer_id = c.id " + "WHERE o.status = :status") Promise<List<CustomerOrder>> findCustomerOrdersByStatus(String status);

    @Query("SELECT c.id AS customer_id, c.name AS customer_name, " + "count(o.id) AS order_count, sum(o.total) AS total_revenue, " + "min(o.total) AS min_order_value, max(o.total) AS max_order_value " + "FROM customers c LEFT JOIN orders o ON o.customer_id = c.id " + "WHERE c.active = :active " + "GROUP BY c.id, c.name") Promise<List<CustomerRevenue>> customerRevenueReport(Boolean active);

    @Query("SELECT p.id AS product_id, p.sku AS product_sku, p.name AS product_name, " + "sum(i.quantity) AS units_sold, sum(i.quantity * i.unit_price) AS total_revenue " + "FROM products p JOIN order_items i ON i.product_id = p.id " + "JOIN orders o ON o.id = i.order_id " + "WHERE o.status = :status " + "GROUP BY p.id, p.sku, p.name") Promise<List<ProductSales>> productSalesReport(String status);

    @Query("SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id " + "FROM orders " + "WHERE (status = :status AND total > :minTotal) " + "OR (status = :altStatus AND total > :altMinTotal)") Promise<List<OrderDetail>> findHighValueOrders(String status,
                                                                                                                                                                                                                                                                         double minTotal,
                                                                                                                                                                                                                                                                         String altStatus,
                                                                                                                                                                                                                                                                         double altMinTotal);

    @Query("SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id " + "FROM orders " + "WHERE customer_id IN (SELECT id FROM customers WHERE email LIKE :domain)") Promise<List<OrderDetail>> ordersForDomain(String domain);

    @Query("SELECT count(DISTINCT customer_id) AS count FROM orders WHERE status = :status") Promise<Long> distinctCustomersByStatus(String status);

    @Query("SELECT id, customer_id, total, total_with_tax, status, metadata, correlation_id " + "FROM orders WHERE correlation_id = :correlationId") Promise<List<OrderDetail>> findByCorrelationId(UUID correlationId);

    @Query("SELECT id, sku, name, price, tags FROM products WHERE :tag = ANY(tags)") Promise<List<ProductWithTags>> productsWithTag(String tag);

    @Query("WITH recent_orders AS (" + "  SELECT id, total, customer_id FROM orders " + "  WHERE created_at > :since AND total > :minTotal" + ") " + "SELECT sum(total) AS total_amount FROM recent_orders WHERE customer_id = :customerId") Promise<BigDecimal> recentOrderTotalForCustomer(Instant since,
                                                                                                                                                                                                                                                                                             BigDecimal minTotal,
                                                                                                                                                                                                                                                                                             Long customerId);
}
