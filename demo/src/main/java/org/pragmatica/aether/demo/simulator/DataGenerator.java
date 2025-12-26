package org.pragmatica.aether.demo.simulator;

import org.pragmatica.aether.demo.order.domain.OrderRepository;

import java.util.List;
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
        public IntRange {
            if (min > max) {
                throw new IllegalArgumentException("min must be <= max");
            }
        }

        public int random(Random random) {
            if (min == max) {
                return min;
            }
            return min + random.nextInt(max - min + 1);
        }

        public static IntRange of(int min, int max) {
            return new IntRange(min, max);
        }

        public static IntRange exactly(int value) {
            return new IntRange(value, value);
        }
    }

    /**
     * Generates random product IDs from a configured list.
     */
    record ProductIdGenerator(List<String> productIds) implements DataGenerator {
        public ProductIdGenerator {
            if (productIds == null || productIds.isEmpty()) {
                throw new IllegalArgumentException("productIds cannot be null or empty");
            }
        }

        @Override
        public String generate(Random random) {
            return productIds.get(random.nextInt(productIds.size()));
        }

        public static ProductIdGenerator withDefaults() {
            return new ProductIdGenerator(List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789"));
        }
    }

    /**
     * Generates random customer IDs.
     */
    record CustomerIdGenerator(String prefix, int maxId) implements DataGenerator {
        public CustomerIdGenerator {
            if (prefix == null) {
                throw new IllegalArgumentException("prefix cannot be null");
            }
            if (maxId <= 0) {
                throw new IllegalArgumentException("maxId must be positive");
            }
        }

        @Override
        public String generate(Random random) {
            return String.format("%s%08d", prefix, random.nextInt(maxId));
        }

        public static CustomerIdGenerator withDefaults() {
            return new CustomerIdGenerator("CUST-", 100_000_000);
        }
    }

    /**
     * Generates order request data for placeOrder entry point.
     */
    record OrderRequestGenerator(
        ProductIdGenerator productGenerator,
        CustomerIdGenerator customerGenerator,
        IntRange quantityRange
    ) implements DataGenerator {

        public OrderRequestGenerator {
            if (productGenerator == null || customerGenerator == null || quantityRange == null) {
                throw new IllegalArgumentException("All generators must be non-null");
            }
        }

        @Override
        public OrderRequestData generate(Random random) {
            return new OrderRequestData(
                customerGenerator.generate(random),
                productGenerator.generate(random),
                quantityRange.random(random)
            );
        }

        public static OrderRequestGenerator withDefaults() {
            return new OrderRequestGenerator(
                ProductIdGenerator.withDefaults(),
                CustomerIdGenerator.withDefaults(),
                IntRange.of(1, 5)
            );
        }

        /**
         * Generated order request data.
         */
        public record OrderRequestData(String customerId, String productId, int quantity) {
            public String toJson() {
                return String.format(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}",
                    customerId, productId, quantity
                );
            }
        }
    }

    /**
     * Generates order IDs from recent orders in the repository.
     * Falls back to synthetic IDs if repository is empty.
     */
    record OrderIdGenerator(OrderRepository repository) implements DataGenerator {
        public OrderIdGenerator {
            if (repository == null) {
                throw new IllegalArgumentException("repository cannot be null");
            }
        }

        @Override
        public String generate(Random random) {
            return repository.randomOrderId()
                             .fold(() -> "ORD-" + String.format("%08d", random.nextInt(100_000_000)), id -> id);
        }

        public static OrderIdGenerator withSharedRepository() {
            return new OrderIdGenerator(OrderRepository.instance());
        }
    }

    /**
     * Generates stock check request data.
     */
    record StockCheckGenerator(ProductIdGenerator productGenerator) implements DataGenerator {
        public StockCheckGenerator {
            if (productGenerator == null) {
                throw new IllegalArgumentException("productGenerator cannot be null");
            }
        }

        @Override
        public StockCheckData generate(Random random) {
            return new StockCheckData(productGenerator.generate(random));
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
        public PriceCheckGenerator {
            if (productGenerator == null) {
                throw new IllegalArgumentException("productGenerator cannot be null");
            }
        }

        @Override
        public PriceCheckData generate(Random random) {
            return new PriceCheckData(productGenerator.generate(random));
        }

        public static PriceCheckGenerator withDefaults() {
            return new PriceCheckGenerator(ProductIdGenerator.withDefaults());
        }

        public record PriceCheckData(String productId) {}
    }
}
