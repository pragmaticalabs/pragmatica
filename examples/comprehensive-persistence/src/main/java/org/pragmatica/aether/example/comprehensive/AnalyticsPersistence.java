package org.pragmatica.aether.example.comprehensive;

import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;


@AnalyticsPgSql public interface AnalyticsPersistence {
    record OrderMetric(long id,
                       LocalDate eventDate,
                       long customerId,
                       int orderCount,
                       BigDecimal revenue,
                       Instant createdAt){}

    record NewOrderMetric(LocalDate eventDate, long customerId, int orderCount, BigDecimal revenue){}

    record DailySnapshot(long id,
                         LocalDate snapshotDate,
                         int activeCustomers,
                         int totalOrders,
                         BigDecimal totalRevenue){}

    record RevenueTotal(long customerId, BigDecimal totalRevenue){}

    @Table(OrderMetric.class) Promise<Option<OrderMetric>> findById(Long id);
    Promise<OrderMetric> save(OrderMetric metric);
    @Table(OrderMetric.class) Promise<OrderMetric> insert(NewOrderMetric metric);
    @Table(OrderMetric.class) Promise<Unit> deleteById(Long id);

    @Query("SELECT customer_id, sum(revenue) AS total_revenue " + "FROM order_metrics " + "WHERE customer_id = :customerId " + "GROUP BY customer_id") Promise<List<RevenueTotal>> customerRevenue(Long customerId);

    @Query("SELECT id, event_date, customer_id, order_count, revenue, created_at " + "FROM order_metrics " + "WHERE customer_id = :customerId") Promise<List<OrderMetric>> metricsByCustomer(Long customerId);

    @Query("SELECT count(*) FROM daily_snapshot") Promise<Long> countSnapshots();

    @Query("WITH recent AS (SELECT customer_id, revenue FROM order_metrics WHERE created_at > :since) " + "SELECT sum(revenue) AS total_revenue FROM recent WHERE customer_id = :customerId") Promise<BigDecimal> recentRevenueForCustomer(Instant since,
                                                                                                                                                                                                                                           Long customerId);
}
