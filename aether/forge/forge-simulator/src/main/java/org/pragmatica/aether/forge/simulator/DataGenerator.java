package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Framework for generating test data for load testing.
/// Each generator produces data appropriate for a specific entry point type.
public sealed interface DataGenerator {
    /// Generate test data.
    ///
    /// @param random thread-local random for deterministic generation
    /// @return generated data appropriate for the entry point
    Object generate(Random random);

    /// Convenience method using thread-local random.
    default Object generate() {
        return generate(ThreadLocalRandom.current());
    }

    /// Range for integer values.
    record IntRange(int min, int max) {
        private static final Cause MIN_GREATER_THAN_MAX = Causes.cause("min must be <= max");

        public int random(Random random) {
            if (min == max) {
                return min;
            }
            return min + random.nextInt(max - min + 1);
        }

        public static Result<IntRange> intRange(int min, int max) {
            return Verify.ensure(min, Verify.Is::lessThanOrEqualTo, max, MIN_GREATER_THAN_MAX)
                         .map(m -> new IntRange(m, max));
        }

        public static IntRange intRange(int value) {
            return intRange(value, value).unwrap();
        }
    }

    /// Generates random product IDs from a configured list.
    record ProductIdGenerator(List<String> productIds) implements DataGenerator {
        private static final Cause PRODUCT_IDS_EMPTY = Causes.cause("productIds cannot be null or empty");

        public ProductIdGenerator(List<String> productIds) {
            this.productIds = productIds == null
                              ? List.of()
                              : List.copyOf(productIds);
        }

        @Override
        public String generate(Random random) {
            return productIds.get(random.nextInt(productIds.size()));
        }

        public static Result<ProductIdGenerator> productIdGenerator(List<String> productIds) {
            return Verify.ensure(productIds, Verify.Is::notNull, PRODUCT_IDS_EMPTY)
                         .filter(PRODUCT_IDS_EMPTY,
                                 list -> !list.isEmpty())
                         .map(ProductIdGenerator::new);
        }

        public static ProductIdGenerator productIdGenerator() {
            return productIdGenerator(List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789")).unwrap();
        }
    }

    /// Generates random customer IDs.
    record CustomerIdGenerator(String prefix, int maxId) implements DataGenerator {
        private static final Cause PREFIX_NULL = Causes.cause("prefix cannot be null");
        private static final Cause MAX_ID_NOT_POSITIVE = Causes.cause("maxId must be positive");

        @Override
        public String generate(Random random) {
            return String.format("%s%08d", prefix, random.nextInt(maxId));
        }

        public static Result<CustomerIdGenerator> customerIdGenerator(String prefix, int maxId) {
            return Verify.ensure(prefix, Verify.Is::notNull, PREFIX_NULL)
                         .flatMap(_ -> Verify.ensure(maxId, Verify.Is::positive, MAX_ID_NOT_POSITIVE))
                         .map(_ -> new CustomerIdGenerator(prefix, maxId));
        }

        public static CustomerIdGenerator customerIdGenerator() {
            return customerIdGenerator("CUST-", 100_000_000).unwrap();
        }
    }

    /// Generates order request data for placeOrder entry point.
    record OrderRequestGenerator(ProductIdGenerator productGenerator,
                                 CustomerIdGenerator customerGenerator,
                                 IntRange quantityRange) implements DataGenerator {
        private static final Cause GENERATORS_NULL = Causes.cause("All generators must be non-null");

        @Override
        public OrderRequestData generate(Random random) {
            return OrderRequestData.orderRequestData(customerGenerator.generate(random),
                                                     productGenerator.generate(random),
                                                     quantityRange.random(random))
                                   .unwrap();
        }

        public static Result<OrderRequestGenerator> orderRequestGenerator(ProductIdGenerator productGenerator,
                                                                          CustomerIdGenerator customerGenerator,
                                                                          IntRange quantityRange) {
            return ensureGeneratorsNotNull(productGenerator, customerGenerator, quantityRange)
            .map(_ -> new OrderRequestGenerator(productGenerator, customerGenerator, quantityRange));
        }

        private static Result<IntRange> ensureGeneratorsNotNull(ProductIdGenerator productGenerator,
                                                                CustomerIdGenerator customerGenerator,
                                                                IntRange quantityRange) {
            return Verify.ensure(productGenerator, Verify.Is::notNull, GENERATORS_NULL)
                         .flatMap(_ -> Verify.ensure(customerGenerator, Verify.Is::notNull, GENERATORS_NULL))
                         .flatMap(_ -> Verify.ensure(quantityRange, Verify.Is::notNull, GENERATORS_NULL));
        }

        public static OrderRequestGenerator orderRequestGenerator() {
            return orderRequestGenerator(ProductIdGenerator.productIdGenerator(),
                                         CustomerIdGenerator.customerIdGenerator(),
                                         IntRange.intRange(1)).unwrap();
        }

        /// Generated order request data.
        public record OrderRequestData(String customerId, String productId, int quantity) {
            public static Result<OrderRequestData> orderRequestData(String customerId, String productId, int quantity) {
                return success(new OrderRequestData(customerId, productId, quantity));
            }

            public String toJson() {
                return String.format("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}",
                                     customerId,
                                     productId,
                                     quantity);
            }
        }
    }

    /// Generates order IDs from a pool of recent order IDs.
    /// Falls back to synthetic IDs if pool is empty.
    /// Thread-safe for concurrent load generation.
    record OrderIdGenerator(Queue<String> orderIdPool, int maxPoolSize) implements DataGenerator {
        private static final Queue<String> SHARED_POOL = new ConcurrentLinkedQueue<>();
        private static final int DEFAULT_MAX_POOL_SIZE = 1000;
        private static final Cause POOL_NULL = Causes.cause("orderIdPool cannot be null");
        private static final Cause MAX_POOL_NOT_POSITIVE = Causes.cause("maxPoolSize must be positive");

        @Override
        public String generate(Random random) {
            return option(orderIdPool.poll()).onPresent(orderIdPool::offer)
                         .or(syntheticOrderId(random));
        }

        private static String syntheticOrderId(Random random) {
            return "ORD-" + String.format("%08d", random.nextInt(100_000_000));
        }

        /// Add an order ID to the pool (called when orders are created).
        public Result<Unit> addOrderId(String orderId) {
            if (orderIdPool.size() < maxPoolSize) {
                orderIdPool.offer(orderId);
            }
            return unitResult();
        }

        public static Result<OrderIdGenerator> orderIdGenerator(Queue<String> orderIdPool, int maxPoolSize) {
            return Verify.ensure(orderIdPool, Verify.Is::notNull, POOL_NULL)
                         .flatMap(_ -> Verify.ensure(maxPoolSize, Verify.Is::positive, MAX_POOL_NOT_POSITIVE))
                         .map(_ -> new OrderIdGenerator(orderIdPool, maxPoolSize));
        }

        public static OrderIdGenerator orderIdGenerator() {
            return orderIdGenerator(SHARED_POOL, DEFAULT_MAX_POOL_SIZE).unwrap();
        }

        public static OrderIdGenerator orderIdGenerator(Queue<String> pool) {
            return orderIdGenerator(pool, DEFAULT_MAX_POOL_SIZE).unwrap();
        }

        /// Add order ID to the shared pool.
        public static Result<Unit> trackOrderId(String orderId) {
            if (SHARED_POOL.size() < DEFAULT_MAX_POOL_SIZE) {
                SHARED_POOL.offer(orderId);
            }
            return unitResult();
        }
    }

    /// Generates stock check request data.
    record StockCheckGenerator(ProductIdGenerator productGenerator) implements DataGenerator {
        private static final Cause GENERATOR_NULL = Causes.cause("productGenerator cannot be null");

        @Override
        public StockCheckData generate(Random random) {
            return StockCheckData.stockCheckData(productGenerator.generate(random))
                                 .unwrap();
        }

        public static Result<StockCheckGenerator> stockCheckGenerator(ProductIdGenerator productGenerator) {
            return Verify.ensure(productGenerator, Verify.Is::notNull, GENERATOR_NULL)
                         .map(StockCheckGenerator::new);
        }

        public static StockCheckGenerator stockCheckGenerator() {
            return stockCheckGenerator(ProductIdGenerator.productIdGenerator()).unwrap();
        }

        public record StockCheckData(String productId) {
            public static Result<StockCheckData> stockCheckData(String productId) {
                return success(new StockCheckData(productId));
            }
        }
    }

    /// Generates price check request data.
    record PriceCheckGenerator(ProductIdGenerator productGenerator) implements DataGenerator {
        private static final Cause GENERATOR_NULL = Causes.cause("productGenerator cannot be null");

        @Override
        public PriceCheckData generate(Random random) {
            return PriceCheckData.priceCheckData(productGenerator.generate(random))
                                 .unwrap();
        }

        public static Result<PriceCheckGenerator> priceCheckGenerator(ProductIdGenerator productGenerator) {
            return Verify.ensure(productGenerator, Verify.Is::notNull, GENERATOR_NULL)
                         .map(PriceCheckGenerator::new);
        }

        public static PriceCheckGenerator priceCheckGenerator() {
            return priceCheckGenerator(ProductIdGenerator.productIdGenerator()).unwrap();
        }

        public record PriceCheckData(String productId) {
            public static Result<PriceCheckData> priceCheckData(String productId) {
                return success(new PriceCheckData(productId));
            }
        }
    }

    record unused() implements DataGenerator {
        @Override
        public Object generate(Random random) {
            return "";
        }
    }
}
