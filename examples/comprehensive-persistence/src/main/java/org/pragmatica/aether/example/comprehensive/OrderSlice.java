package org.pragmatica.aether.example.comprehensive;

import org.pragmatica.aether.example.comprehensive.BasePersistence.CustomerOrder;
import org.pragmatica.aether.example.comprehensive.BasePersistence.CustomerRevenue;
import org.pragmatica.aether.example.comprehensive.BasePersistence.ProductWithTags;
import org.pragmatica.aether.example.comprehensive.CrudPersistence.CustomerRow;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;


@Slice public interface OrderSlice {
    Promise<List<CustomerOrder>> customerOrders(String status);
    Promise<List<CustomerRevenue>> revenue(Boolean active);
    Promise<Option<CustomerRow>> customer(Long customerId);
    Promise<Long> snapshotCount();
    Promise<BigDecimal> recentOrderTotal(Instant since, BigDecimal minTotal, Long customerId);
    Promise<List<ProductWithTags>> productsWithTag(String tag);

    static OrderSlice orderSlice(BasePersistence base, CrudPersistence crud, AnalyticsPersistence analytics) {
        record orderSlice(BasePersistence base, CrudPersistence crud, AnalyticsPersistence analytics) implements OrderSlice {
            @Override public Promise<List<CustomerOrder>> customerOrders(String status) {
                return base.findCustomerOrdersByStatus(status);
            }

            @Override public Promise<List<CustomerRevenue>> revenue(Boolean active) {
                return base.customerRevenueReport(active);
            }

            @Override public Promise<Option<CustomerRow>> customer(Long customerId) {
                return crud.findById(customerId);
            }

            @Override public Promise<Long> snapshotCount() {
                return analytics.countSnapshots();
            }

            @Override public Promise<BigDecimal> recentOrderTotal(Instant since, BigDecimal minTotal, Long customerId) {
                return base.recentOrderTotalForCustomer(since, minTotal, customerId);
            }

            @Override public Promise<List<ProductWithTags>> productsWithTag(String tag) {
                return base.productsWithTag(tag);
            }
        }
        return new orderSlice(base, crud, analytics);
    }
}
