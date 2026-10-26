// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.Map;


/// The value term of the commit ring's byte measure (R-07): a constant estimate of a value's heap bytes per value class, and a default for
/// every class the table does not name. A constant per class, never an encode inside the store's mutation; the constants are tuned against
/// the encoded size by measurement.
public record ValueSizeTable(Map<Class<?>, Integer> bytesByClass, int defaultBytes) {
    public static final int DEFAULT_VALUE_BYTES = 256;

    public ValueSizeTable {
        bytesByClass = Map.copyOf(bytesByClass);
    }

    /// The untuned table: every value class is estimated at [#DEFAULT_VALUE_BYTES].
    public static ValueSizeTable valueSizeTable() {
        return valueSizeTable(Map.of());
    }

    public static ValueSizeTable valueSizeTable(Map<Class<?>, Integer> bytesByClass) {
        return new ValueSizeTable(bytesByClass, DEFAULT_VALUE_BYTES);
    }

    /// The estimate for one value: its class's constant, or the default.
    public int estimate(Object value) {
        return bytesByClass.getOrDefault(value.getClass(), defaultBytes);
    }
}
