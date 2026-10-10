package org.example;

import java.util.List;
import java.util.Map;


public interface ShiftOperatorsAndNestedBlocks {
    int USER_TAG_LIMIT = 1 << 21;
    int RIGHT = 1 >> 2;
    int UNSIGNED = 1 >>> 2;
    int FROM_NAMES = a << b;
    boolean LESS = x < y;
    boolean CONSTANT_LESS = MAX < x;
    boolean CONSTANT_GREATER = MAX > x;
    int CONSTANT_SHIFT = MAX << 2;
    int INDEXED_SHIFT = arr[0] << 2;
    int CALL_SHIFT = f(a) << 2;
    int MIXED = 1 << 2 | 3;
    boolean BOTH = i < 10 && j > 3;
    Map<String, Map<String, Integer>> NESTED_GENERICS = null;
    Object DIAMOND = new java.util.ArrayList<>();
    Object TYPED = new java.util.HashMap<String, Integer>();
    Object WILDCARD_ARRAY = new Class<?>[0];
    Object EXPLICIT = java.util.Collections.<String> emptyList();
    Object CAST = (List<String>) null;

    private static void nestedIfInChainLambda(List<String> list, String previousName) {
        list.stream()
            .forEach(entry -> {
                         if (previousName != null) {
                             throw new IllegalStateException("Tag %d is pinned to both %s and %s".formatted(list.size(),
                                                                                                            previousName,
                                                                                                            entry));
                         }
                     });
    }

    private static void nestedBlocksAfterAWrappedStatement(List<String> list, String previousName) {
        list.stream()
            .forEach(entry -> {
                         var wrapped = foo(entry,
                                           previousName,
                                           list,
                                           list.size(),
                                           previousName,
                                           entry,
                                           list.hashCode(),
                                           entry.length());

                         if (previousName != null) {
                             for (var item : list) {
                                 if (item.isEmpty()) {
                                     throw new IllegalStateException("short");
                                 }
                             }
                         } else {
                             wrapped.run();
                         }
                     });
    }
}
