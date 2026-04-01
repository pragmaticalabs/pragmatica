package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;

/// PostgreSQL type system representation.
public sealed interface PgType {
    String name();

    record BuiltinType(String name, TypeCategory category, List<Integer> modifiers) implements PgType {
        public BuiltinType(String name, TypeCategory category) {
            this(name, category, List.of());
        }
    }

    record ArrayType(PgType elementType, int dimensions) implements PgType {
        public String name() {
            return elementType.name() + "[]".repeat(dimensions);
        }
    }

    record EnumType(String name, String schema, List<String> values) implements PgType {
        public EnumType withValue(String value) {
            var newValues = new ArrayList<>(values);
            newValues.add(value);
            return new EnumType(name, schema, List.copyOf(newValues));
        }

        public EnumType withValueBefore(String value, String before) {
            var newValues = new ArrayList<>(values);
            int idx = newValues.indexOf(before);
            if ( idx >= 0) { newValues.add(idx, value);} else { newValues.add(value);}
            return new EnumType(name, schema, List.copyOf(newValues));
        }

        public EnumType withValueAfter(String value, String after) {
            var newValues = new ArrayList<>(values);
            int idx = newValues.indexOf(after);
            if ( idx >= 0) { newValues.add(idx + 1, value);} else { newValues.add(value);}
            return new EnumType(name, schema, List.copyOf(newValues));
        }
    }

    record CompositeType(String name, String schema, List<CompositeField> fields) implements PgType{}

    record CompositeField(String name, PgType type){}

    record DomainType(String name, String schema, PgType baseType, Option<String> checkExpression) implements PgType{}

    record CustomType(String name, String schema) implements PgType{}

    enum TypeCategory {
        NUMERIC,
        STRING,
        BOOLEAN,
        DATETIME,
        UUID,
        JSON,
        BINARY,
        NETWORK,
        XML,
        MONEY,
        SERIAL,
        BIT,
        TEXTSEARCH
    }
}
