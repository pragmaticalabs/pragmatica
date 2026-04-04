package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;

import java.util.List;


/// An index definition.
public record Index(String name,
                    String table,
                    List<IndexElement> elements,
                    IndexMethod method,
                    boolean unique,
                    boolean concurrent,
                    Option<String> whereClause,
                    List<String> includeColumns) {
    public record IndexElement(String expression, Option<SortOrder> order, Option<NullsOrder> nullsOrder){}

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
