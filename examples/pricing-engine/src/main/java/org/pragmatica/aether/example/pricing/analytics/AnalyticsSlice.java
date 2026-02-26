package org.pragmatica.aether.example.pricing.analytics;

import org.pragmatica.aether.example.pricing.catalog.CatalogSlice.HighValueOrderEvent;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;

/// Analytics slice -- subscribes to high-value order events and provides hourly summary queries.
@Slice
public interface AnalyticsSlice {
    // === Requests / Responses ===
    record EmptyRequest() {}

    record HourlyBucket(int hour, int orderCount, long revenueCents) {
        static HourlyBucket hourlyBucket(int hour, int orderCount, long revenueCents) {
            return new HourlyBucket(hour, orderCount, revenueCents);
        }
    }

    record HighValueSummary(int totalOrders, long totalRevenueCents, List<HourlyBucket> hourlyDistribution) {
        public HighValueSummary {
            hourlyDistribution = List.copyOf(hourlyDistribution);
        }

        static HighValueSummary highValueSummary(int totalOrders,
                                                 long totalRevenueCents,
                                                 List<HourlyBucket> hourlyDistribution) {
            return new HighValueSummary(totalOrders, totalRevenueCents, hourlyDistribution);
        }
    }

    // === Operations ===
    /// Topic subscriber -- stores high-value order events in DB.
    @HighValueOrderSubscription
    Promise<Unit> onHighValueOrder(HighValueOrderEvent event);

    /// Query endpoint -- hourly distribution of high-value orders.
    Promise<HighValueSummary> getHighValueSummary(EmptyRequest request);

    // === Factory ===
    static AnalyticsSlice analyticsSlice(@Sql SqlConnector db) {
        record analyticsSlice(SqlConnector db) implements AnalyticsSlice {
            private static final String INSERT_ORDER = "INSERT INTO high_value_orders (product_id, quantity, total_cents, region_code) VALUES (?, ?, ?, ?)";

            private static final String SELECT_HOURLY = "SELECT HOUR(created_at) AS hr, COUNT(*) AS order_count, SUM(total_cents) AS revenue"
                                                       + " FROM high_value_orders WHERE created_at >= CURRENT_DATE"
                                                       + " GROUP BY HOUR(created_at) ORDER BY hr";

            private static final RowMapper<HourlyBucket> BUCKET_MAPPER = analyticsSlice::mapBucket;

            private static Result<HourlyBucket> mapBucket(RowMapper.RowAccessor row) {
                return Result.all(row.getInt("hr"),
                                  row.getInt("order_count"),
                                  row.getLong("revenue"))
                             .map(HourlyBucket::new);
            }

            @Override
            public Promise<Unit> onHighValueOrder(HighValueOrderEvent event) {
                return insertOrder(event.productId(), event.quantity(), event.totalCents(), event.regionCode());
            }

            private Promise<Unit> insertOrder(String productId, int quantity, int totalCents, String regionCode) {
                return db.update(INSERT_ORDER, productId, quantity, totalCents, regionCode)
                         .mapToUnit();
            }

            @Override
            public Promise<HighValueSummary> getHighValueSummary(EmptyRequest request) {
                return db.queryList(SELECT_HOURLY, BUCKET_MAPPER)
                         .map(analyticsSlice::toSummary);
            }

            private static HighValueSummary toSummary(List<HourlyBucket> buckets) {
                var totalOrders = buckets.stream()
                                         .mapToInt(HourlyBucket::orderCount)
                                         .sum();
                var totalRevenue = buckets.stream()
                                          .mapToLong(HourlyBucket::revenueCents)
                                          .sum();
                return new HighValueSummary(totalOrders, totalRevenue, buckets);
            }
        }
        return new analyticsSlice(db);
    }
}
