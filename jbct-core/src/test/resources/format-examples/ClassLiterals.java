package org.example;

import java.util.HashMap;
import java.util.List;

/**
 * Test class literals for various types including primitive arrays.
 */
public interface ClassLiterals {
    static void configure(java.util.function.Consumer<Class< ? >> consumer) {
        // Primitive class literals
        consumer.accept(int.class);
        consumer.accept(byte.class);
        consumer.accept(void.class);
        // Primitive array class literals
        consumer.accept(byte[].class);
        consumer.accept(int[][].class);
        // Reference class literals
        consumer.accept(String.class);
        consumer.accept(HashMap.class);
        // Reference array class literals
        consumer.accept(String[].class);
        consumer.accept(Object[][].class);
        // Chained getClass() calls
        consumer.accept(List.of()
                            .getClass());
        consumer.accept(List.of(1)
                            .getClass());
        consumer.accept(List.of(1, 2, 3)
                            .getClass());
    }
}
