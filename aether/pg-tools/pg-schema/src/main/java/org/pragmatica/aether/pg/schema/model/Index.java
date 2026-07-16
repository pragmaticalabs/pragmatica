// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.model;

import java.util.List;

import org.pragmatica.lang.Option;


public record Index(String name,
                    String table,
                    List<IndexElement> elements,
                    IndexMethod method,
                    boolean unique,
                    boolean concurrent,
                    Option<String> whereClause,
                    List<String> includeColumns) {
    public record IndexElement(String expression, Option<SortOrder> order, Option<NullsOrder> nullsOrder) {}

    public enum IndexMethod {
        BTREE,
        HASH,
        GIN,
        GIST,
        BRIN,
        SPGIST
    }

    public enum SortOrder {
        ASC,
        DESC
    }

    public enum NullsOrder {
        FIRST,
        LAST
    }

    public static Index index(String name, String table, List<IndexElement> elements) {
        return new Index(name, table, elements, IndexMethod.BTREE, false, false, Option.empty(), List.of());
    }
}
