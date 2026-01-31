package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Framework for generating test data for load testing.
 * Each generator produces data appropriate for a specific entry point type.
 */
public sealed interface DataGenerator {
    /**
     * Generate test data.
     *
     * @param random thread-local random for deterministic generation
     * @return generated data appropriate for the entry point
     */
    Object generate(Random random);

    /**
     * Convenience method using thread-local random.
     */
    default Object generate() {
        return generate(ThreadLocalRandom.current());
    }

    /**
     * Range for integer values.
     */
    record IntRange(int min, int max) {
        private static final Cause MIN_GREATER_THAN_MAX = Causes.cause("min must be <= max");

        public int random(Random random) {
            return min == max
                   ? min
                   : min + random.nextInt(max - min + 1);
        }

        public static Result<IntRange> intRange(int min, int max) {
            if (min > max) {
                return MIN_GREATER_THAN_MAX.result();
            }
            return Result.success(new IntRange(min, max));
        }

        public static IntRange intRangeExact(int value) {
            return new IntRange(value, value);
        }
    }

    /**
     * Generates random product IDs from a configured list.
     */
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
            if (productIds == null || productIds.isEmpty()) {
                return PRODUCT_IDS_EMPTY.result();
            }
            return Result.success(new ProductIdGenerator(productIds));
        }

        public static ProductIdGenerator withDefaults() {
            return new ProductIdGenerator(List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789"));
        }
    }

    /**
     * Generates random customer IDs.
     */
    record CustomerIdGenerator(String prefix, int maxId) implements DataGenerator {
        private static final Cause PREFIX_NULL = Causes.cause("prefix cannot be null");
        private static final Cause MAX_ID_NOT_POSITIVE = Causes.cause("maxId must be positive");

        @Override
        public String generate(Random random) {
            return String.format("%s%08d", prefix, random.nextInt(maxId));
        }

        public static Result<CustomerIdGenerator> customerIdGenerator(String prefix, int maxId) {
            if (prefix == null) {
                return PREFIX_NULL.result();
            }
            if (maxId <= 0) {
                return MAX_ID_NOT_POSITIVE.result();
            }
            return Result.success(new CustomerIdGenerator(prefix, maxId));
        }

        public static CustomerIdGenerator withDefaults() {
            return new CustomerIdGenerator("CUST-", 100_000_000);
        }
    }

    /**
     * Generates order request data for placeOrder entry point.
     */
    record OrderRequestGenerator(ProductIdGenerator productGenerator,
                                 CustomerIdGenerator customerGenerator,
                                 IntRange quantityRange) implements DataGenerator {
        private static final Cause GENERATORS_NULL = Causes.cause("All generators must be non-null");

        @Override
        public OrderRequestData generate(Random random) {
            return new OrderRequestData(customerGenerator.generate(random),
                                        productGenerator.generate(random),
                                        quantityRange.random(random));
        }

        public static Result<OrderRequestGenerator> orderRequestGenerator(ProductIdGenerator productGenerator,
                                                                          CustomerIdGenerator customerGenerator,
                                                                          IntRange quantityRange) {
            if (productGenerator == null || customerGenerator == null || quantityRange == null) {
                return GENERATORS_NULL.result();
            }
            return Result.success(new OrderRequestGenerator(productGenerator, customerGenerator, quantityRange));
        }

        public static OrderRequestGenerator withDefaults() {
            return new OrderRequestGenerator(ProductIdGenerator.withDefaults(),
                                             CustomerIdGenerator.withDefaults(),
                                             IntRange.intRangeExact(1));
        }

        /**
         * Generated order request data.
         */
        public record OrderRequestData(String customerId, String productId, int quantity) {
            public String toJson() {
                return String.format("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}",
                                     customerId,
                                     productId,
                                     quantity);
            }
        }
    }

    /**
     * Generates order IDs from a pool of recent order IDs.
     * Falls back to synthetic IDs if pool is empty.
     * Thread-safe for concurrent load generation.
     */
    record OrderIdGenerator(Queue<String> orderIdPool, int maxPoolSize) implements DataGenerator {
        private static final Queue<String> SHARED_POOL = new ConcurrentLinkedQueue<>();
        private static final int DEFAULT_MAX_POOL_SIZE = 1000;
        private static final Cause POOL_NULL = Causes.cause("orderIdPool cannot be null");
        private static final Cause MAX_POOL_NOT_POSITIVE = Causes.cause("maxPoolSize must be positive");

        @Override
        public String generate(Random random) {
            var orderId = orderIdPool.poll();
            if (orderId != null) {
                orderIdPool.offer(orderId);
                return orderId;
            }
            return "ORD-" + String.format("%08d", random.nextInt(100_000_000));
        }

        /**
         * Add an order ID to the pool (called when orders are created).
         */
        public void addOrderId(String orderId) {
            if (orderIdPool.size() < maxPoolSize) {
                orderIdPool.offer(orderId);
            }
        }

        public static Result<OrderIdGenerator> orderIdGenerator(Queue<String> orderIdPool, int maxPoolSize) {
            if (orderIdPool == null) {
                return POOL_NULL.result();
            }
            if (maxPoolSize <= 0) {
                return MAX_POOL_NOT_POSITIVE.result();
            }
            return Result.success(new OrderIdGenerator(orderIdPool, maxPoolSize));
        }

        public static OrderIdGenerator withSharedPool() {
            return new OrderIdGenerator(SHARED_POOL, DEFAULT_MAX_POOL_SIZE);
        }

        public static OrderIdGenerator withNewPool() {
            return new OrderIdGenerator(new ConcurrentLinkedQueue<>(), DEFAULT_MAX_POOL_SIZE);
        }

        /**
         * Add order ID to the shared pool.
         */
        public static void trackOrderId(String orderId) {
            if (SHARED_POOL.size() < DEFAULT_MAX_POOL_SIZE) {
                SHARED_POOL.offer(orderId);
            }
        }
    }

    /**
     * Generates stock check request data.
     */
    record StockCheckGenerator(ProductIdGenerator productGenerator) implements DataGenerator {
        private static final Cause GENERATOR_NULL = Causes.cause("productGenerator cannot be null");

        @Override
        public StockCheckData generate(Random random) {
            return new StockCheckData(productGenerator.generate(random));
        }

        public static Result<StockCheckGenerator> stockCheckGenerator(ProductIdGenerator productGenerator) {
            if (productGenerator == null) {
                return GENERATOR_NULL.result();
            }
            return Result.success(new StockCheckGenerator(productGenerator));
        }

        public static StockCheckGenerator withDefaults() {
            return new StockCheckGenerator(ProductIdGenerator.withDefaults());
        }

        public record StockCheckData(String productId) {}
    }

    /**
     * Generates price check request data.
     */
    record PriceCheckGenerator(ProductIdGenerator productGenerator) implements DataGenerator {
        private static final Cause GENERATOR_NULL = Causes.cause("productGenerator cannot be null");

        @Override
        public PriceCheckData generate(Random random) {
            return new PriceCheckData(productGenerator.generate(random));
        }

        public static Result<PriceCheckGenerator> priceCheckGenerator(ProductIdGenerator productGenerator) {
            if (productGenerator == null) {
                return GENERATOR_NULL.result();
            }
            return Result.success(new PriceCheckGenerator(productGenerator));
        }

        public static PriceCheckGenerator withDefaults() {
            return new PriceCheckGenerator(ProductIdGenerator.withDefaults());
        }

        public record PriceCheckData(String productId) {}
    }
}
