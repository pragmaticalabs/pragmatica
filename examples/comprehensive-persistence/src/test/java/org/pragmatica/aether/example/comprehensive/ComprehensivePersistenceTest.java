package org.pragmatica.aether.example.comprehensive;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.comprehensive.AnalyticsPersistence.NewOrderMetric;
import org.pragmatica.aether.example.comprehensive.AnalyticsPersistence.OrderMetric;
import org.pragmatica.aether.example.comprehensive.BasePersistence.CustomerOrder;
import org.pragmatica.aether.example.comprehensive.BasePersistence.CustomerRevenue;
import org.pragmatica.aether.example.comprehensive.BasePersistence.OrderDetail;
import org.pragmatica.aether.example.comprehensive.BasePersistence.OrderTotal;
import org.pragmatica.aether.example.comprehensive.BasePersistence.ProductSales;
import org.pragmatica.aether.example.comprehensive.BasePersistence.ProductWithTags;
import org.pragmatica.aether.example.comprehensive.CrudPersistence.CustomerRow;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


/// Smoke tests that prove the generated persistence factories and the slice
/// factory compose without touching a real database.
///
/// We stub every persistence interface inline - the slice factory accepts
/// lambda/anonymous implementations because each persistence type is an
/// interface, and each method returns a Promise.
class ComprehensivePersistenceTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:00:00Z");
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Nested
    class SliceWiring {

        @Test void customerOrders_returnsStubbedList_whenBaseProvidesData() {
            var stub = new StubBase();
            var slice = OrderSlice.orderSlice(stub, new StubCrud(), new StubAnalytics());

            var result = slice.customerOrders("paid").await()
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            result.onSuccess(orders -> assertThat(orders).hasSize(1)
                                                         .first()
                                                         .extracting(CustomerOrder::orderStatus)
                                                         .isEqualTo("paid"));
        }

        @Test void revenue_aggregatesPerCustomer_whenStubbed() {
            var slice = OrderSlice.orderSlice(new StubBase(), new StubCrud(), new StubAnalytics());

            var result = slice.revenue(true).await()
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            result.onSuccess(list -> assertThat(list).hasSize(1)
                                                      .first()
                                                      .extracting(CustomerRevenue::orderCount)
                                                      .isEqualTo(3L));
        }

        @Test void customer_returnsSomeCustomer_whenCrudReturnsOne() {
            var slice = OrderSlice.orderSlice(new StubBase(), new StubCrud(), new StubAnalytics());

            var result = slice.customer(1L).await()
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            result.onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test void snapshotCount_delegates_toAnalytics() {
            var slice = OrderSlice.orderSlice(new StubBase(), new StubCrud(), new StubAnalytics());

            var result = slice.snapshotCount().await()
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            result.onSuccess(count -> assertThat(count).isEqualTo(42L));
        }

        @Test void recentOrderTotal_delegates_toBase() {
            var slice = OrderSlice.orderSlice(new StubBase(), new StubCrud(), new StubAnalytics());

            var result = slice.recentOrderTotal(NOW, new BigDecimal("10.00"), 1L).await()
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            result.onSuccess(total -> assertThat(total.totalAmount()).isEqualTo(new BigDecimal("297.00")));
        }

        @Test void productsWithTag_delegates_toBase() {
            var slice = OrderSlice.orderSlice(new StubBase(), new StubCrud(), new StubAnalytics());

            var result = slice.productsWithTag("premium").await()
                              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            result.onSuccess(list -> assertThat(list).hasSize(1)
                                                      .first()
                                                      .extracting(ProductWithTags::tags)
                                                      .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY)
                                                      .containsExactly("premium", "new"));
        }
    }

    @Nested
    class RecordShapes {

        @Test void customerOrder_isRecordWithExpectedArity() {
            var record = new CustomerOrder(10L, 1L, "Alice", "a@example.com",
                                           new BigDecimal("99.00"), "paid", NOW);
            assertThat(record.orderId()).isEqualTo(10L);
            assertThat(record.customerEmail()).isEqualTo("a@example.com");
        }

        @Test void orderDetail_carriesGeneratedColumn() {
            var total = new BigDecimal("100.00");
            var totalWithTax = new BigDecimal("108.00");
            var detail = new OrderDetail(5L, 1L, total, totalWithTax, "paid", "{}", CORRELATION);
            assertThat(detail.totalWithTax()).isEqualTo(totalWithTax);
            assertThat(detail.correlationId()).isEqualTo(CORRELATION);
        }

        @Test void orderMetric_usesLocalDateForEventDate() {
            var metric = new OrderMetric(1L, LocalDate.of(2026, 4, 20), 1L, 3, new BigDecimal("150.00"), NOW);
            assertThat(metric.eventDate()).isEqualTo(LocalDate.of(2026, 4, 20));
        }

        @Test void newOrderMetric_omitsIdField() {
            var metric = new NewOrderMetric(LocalDate.of(2026, 4, 20), 1L, 3, new BigDecimal("150.00"));
            assertThat(metric.customerId()).isEqualTo(1L);
        }

        @Test void productWithTags_carriesArrayColumn() {
            var product = new ProductWithTags(1L, "SKU-001", "Widget", new BigDecimal("19.99"),
                                               new String[]{"premium", "new"});
            assertThat(product.tags()).containsExactly("premium", "new");
        }
    }

    // --- Stubs ---

    private static final class StubBase implements BasePersistence {
        @Override public Promise<List<CustomerOrder>> findCustomerOrdersByStatus(String status) {
            return Promise.success(List.of(new CustomerOrder(10L, 1L, "Alice", "a@example.com",
                                                              new BigDecimal("99.00"), status, NOW)));
        }
        @Override public Promise<List<CustomerRevenue>> customerRevenueReport(Boolean active) {
            return Promise.success(List.of(new CustomerRevenue(1L, "Alice", 3L,
                                                                new BigDecimal("297.00"),
                                                                new BigDecimal("49.00"),
                                                                new BigDecimal("149.00"))));
        }
        @Override public Promise<List<ProductSales>> productSalesReport(String status) {
            return Promise.success(List.of());
        }
        @Override public Promise<List<OrderDetail>> findHighValueOrders(String status,
                                                                         double minTotal,
                                                                         String altStatus,
                                                                         double altMinTotal) {
            return Promise.success(List.of());
        }
        @Override public Promise<List<OrderDetail>> ordersForDomain(String domain) {
            return Promise.success(List.of());
        }
        @Override public Promise<Long> distinctCustomersByStatus(String status) {
            return Promise.success(0L);
        }
        @Override public Promise<List<OrderDetail>> findByCorrelationId(UUID correlationId) {
            return Promise.success(List.of());
        }
        @Override public Promise<List<ProductWithTags>> productsWithTag(String tag) {
            return Promise.success(List.of(new ProductWithTags(1L, "SKU-001", "Widget",
                                                                new BigDecimal("19.99"),
                                                                new String[]{"premium", "new"})));
        }
        @Override public Promise<OrderTotal> recentOrderTotalForCustomer(Instant since,
                                                                          BigDecimal minTotal,
                                                                          Long customerId) {
            return Promise.success(new OrderTotal(new BigDecimal("297.00")));
        }
    }

    private static final class StubCrud implements CrudPersistence {
        @Override public Promise<CustomerRow> save(CustomerRow customer) { return Promise.success(customer); }
        @Override public Promise<Option<CustomerRow>> findById(Long id) {
            return Promise.success(Option.some(new CustomerRow(id, "Alice", "a@example.com",
                                                                true, NOW, null, "gold", "{}")));
        }
        @Override public Promise<Unit> deleteById(Long id) { return Promise.success(Unit.unit()); }
        @Override public Promise<Long> countByActive(Boolean active) { return Promise.success(0L); }
        @Override public Promise<Boolean> existsById(Long id) { return Promise.success(true); }
        @Override public Promise<List<CustomerRow>> findByTier(String tier) { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByTierNot(String tier) { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByNameLike(String pattern) { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByDeletedAtIsNull() { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByDeletedAtIsNotNull() { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByTierAndActive(String tier, Boolean active) { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByActiveOrderByNameAsc(Boolean active) { return Promise.success(List.of()); }
        @Override public Promise<List<CustomerRow>> findByActiveOrderByTierAscNameDesc(Boolean active) { return Promise.success(List.of()); }
    }

    private static final class StubAnalytics implements AnalyticsPersistence {
        @Override public Promise<Option<OrderMetric>> findById(Long id) { return Promise.success(Option.none()); }
        @Override public Promise<OrderMetric> save(OrderMetric metric) { return Promise.success(metric); }
        @Override public Promise<OrderMetric> insert(NewOrderMetric metric) {
            return Promise.success(new OrderMetric(1L, metric.eventDate(), metric.customerId(),
                                                    metric.orderCount(), metric.revenue(), NOW));
        }
        @Override public Promise<Unit> deleteById(Long id) { return Promise.success(Unit.unit()); }
        @Override public Promise<List<AnalyticsPersistence.RevenueTotal>> customerRevenue(Long customerId) { return Promise.success(List.of()); }
        @Override public Promise<List<OrderMetric>> metricsByCustomer(Long customerId) { return Promise.success(List.of()); }
        @Override public Promise<Long> countSnapshots() { return Promise.success(42L); }
        @Override public Promise<AnalyticsPersistence.RevenueSum> recentRevenueForCustomer(Instant since, Long customerId) {
            return Promise.success(new AnalyticsPersistence.RevenueSum(BigDecimal.ZERO));
        }
    }
}
