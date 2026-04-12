package org.pragmatica.aether.pg.codegen.jooq;

import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.PgType.*;
import org.pragmatica.lang.Option;

import java.util.List;


/// Maps pg-tools PgType to jOOQ XML column type fields.
///
/// Produces the exact strings that jOOQ XMLDatabase expects when configured
/// with `dialect=POSTGRES` — matching what PostgreSQL's `information_schema.columns`
/// would return for a live database.
public final class JooqTypeMapper {
    private JooqTypeMapper() {}

    /// Result of mapping a PgType to jOOQ XML fields.
    public record TypeMapping(String dataType,
                              String udtName,
                              String udtSchema,
                              Option<Integer> characterMaximumLength,
                              Option<Integer> numericPrecision,
                              Option<Integer> numericScale,
                              Option<Integer> datetimePrecision) {
        public static TypeMapping of(String dataType, String udtName, String udtSchema) {
            return new TypeMapping(dataType, udtName, udtSchema,
                                  Option.empty(), Option.empty(), Option.empty(), Option.empty());
        }

        public TypeMapping withCharacterMaximumLength(int length) {
            return new TypeMapping(dataType, udtName, udtSchema,
                                  Option.present(length), numericPrecision, numericScale, datetimePrecision);
        }

        public TypeMapping withNumericPrecision(int precision, int scale) {
            return new TypeMapping(dataType, udtName, udtSchema,
                                  characterMaximumLength, Option.present(precision), Option.present(scale), datetimePrecision);
        }

        public TypeMapping withDatetimePrecision(int precision) {
            return new TypeMapping(dataType, udtName, udtSchema,
                                  characterMaximumLength, numericPrecision, numericScale, Option.present(precision));
        }
    }

    /// Map a PgType to jOOQ XML type fields.
    public static TypeMapping map(PgType type, String defaultSchema) {
        return switch (type) {
            case BuiltinType b -> mapBuiltin(b);
            case ArrayType a -> mapArray(a);
            case EnumType e -> mapUserDefined(e.name(), schemaOrDefault(e.schema(), defaultSchema));
            case CompositeType c -> mapUserDefined(c.name(), schemaOrDefault(c.schema(), defaultSchema));
            case DomainType d -> mapUserDefined(d.name(), schemaOrDefault(d.schema(), defaultSchema));
            case CustomType c -> mapUserDefined(c.name(), schemaOrDefault(c.schema(), defaultSchema));
        };
    }

    private static TypeMapping mapBuiltin(BuiltinType type) {
        var modifiers = type.modifiers();
        return switch (type.name()) {
            case "int2", "smallint" -> TypeMapping.of("smallint", "int2", "pg_catalog");
            case "int4", "integer", "int" -> TypeMapping.of("integer", "int4", "pg_catalog");
            case "int8", "bigint" -> TypeMapping.of("bigint", "int8", "pg_catalog");
            case "serial", "serial4" -> TypeMapping.of("integer", "int4", "pg_catalog");
            case "bigserial", "serial8" -> TypeMapping.of("bigint", "int8", "pg_catalog");
            case "smallserial", "serial2" -> TypeMapping.of("smallint", "int2", "pg_catalog");
            case "float4", "real" -> TypeMapping.of("real", "float4", "pg_catalog");
            case "float8", "double precision" -> TypeMapping.of("double precision", "float8", "pg_catalog");
            case "numeric", "decimal" -> mapNumeric(modifiers);
            case "bool", "boolean" -> TypeMapping.of("boolean", "bool", "pg_catalog");
            case "varchar", "character varying" -> mapVarchar(modifiers);
            case "char", "character", "bpchar" -> mapChar(modifiers);
            case "text" -> TypeMapping.of("text", "text", "pg_catalog");
            case "uuid" -> TypeMapping.of("uuid", "uuid", "pg_catalog");
            case "jsonb" -> TypeMapping.of("jsonb", "jsonb", "pg_catalog");
            case "json" -> TypeMapping.of("json", "json", "pg_catalog");
            case "bytea" -> TypeMapping.of("bytea", "bytea", "pg_catalog");
            case "timestamp", "timestamp without time zone" -> mapTimestamp("timestamp without time zone", "timestamp", modifiers);
            case "timestamptz", "timestamp with time zone" -> mapTimestamp("timestamp with time zone", "timestamptz", modifiers);
            case "date" -> TypeMapping.of("date", "date", "pg_catalog");
            case "time", "time without time zone" -> mapTimestamp("time without time zone", "time", modifiers);
            case "timetz", "time with time zone" -> mapTimestamp("time with time zone", "timetz", modifiers);
            case "interval" -> TypeMapping.of("interval", "interval", "pg_catalog");
            case "inet" -> TypeMapping.of("inet", "inet", "pg_catalog");
            case "cidr" -> TypeMapping.of("cidr", "cidr", "pg_catalog");
            case "macaddr" -> TypeMapping.of("macaddr", "macaddr", "pg_catalog");
            case "macaddr8" -> TypeMapping.of("macaddr8", "macaddr8", "pg_catalog");
            case "money" -> TypeMapping.of("money", "money", "pg_catalog");
            case "xml" -> TypeMapping.of("xml", "xml", "pg_catalog");
            case "bit" -> mapBit(modifiers);
            case "varbit", "bit varying" -> mapVarbit(modifiers);
            case "tsvector" -> TypeMapping.of("tsvector", "tsvector", "pg_catalog");
            case "tsquery" -> TypeMapping.of("tsquery", "tsquery", "pg_catalog");
            case "oid" -> TypeMapping.of("oid", "oid", "pg_catalog");
            default -> TypeMapping.of(type.name(), type.name(), "pg_catalog");
        };
    }

    private static TypeMapping mapNumeric(List<Integer> modifiers) {
        var mapping = TypeMapping.of("numeric", "numeric", "pg_catalog");
        if (modifiers.size() >= 2) {
            return mapping.withNumericPrecision(modifiers.get(0), modifiers.get(1));
        }
        if (modifiers.size() == 1) {
            return mapping.withNumericPrecision(modifiers.getFirst(), 0);
        }
        return mapping;
    }

    private static TypeMapping mapVarchar(List<Integer> modifiers) {
        var mapping = TypeMapping.of("character varying", "varchar", "pg_catalog");
        if (!modifiers.isEmpty()) {
            return mapping.withCharacterMaximumLength(modifiers.getFirst());
        }
        return mapping;
    }

    private static TypeMapping mapChar(List<Integer> modifiers) {
        var mapping = TypeMapping.of("character", "bpchar", "pg_catalog");
        if (!modifiers.isEmpty()) {
            return mapping.withCharacterMaximumLength(modifiers.getFirst());
        }
        return mapping.withCharacterMaximumLength(1);
    }

    private static TypeMapping mapTimestamp(String dataType, String udtName, List<Integer> modifiers) {
        var mapping = TypeMapping.of(dataType, udtName, "pg_catalog");
        if (!modifiers.isEmpty()) {
            return mapping.withDatetimePrecision(modifiers.getFirst());
        }
        return mapping;
    }

    private static TypeMapping mapBit(List<Integer> modifiers) {
        var mapping = TypeMapping.of("bit", "bit", "pg_catalog");
        if (!modifiers.isEmpty()) {
            return mapping.withCharacterMaximumLength(modifiers.getFirst());
        }
        return mapping.withCharacterMaximumLength(1);
    }

    private static TypeMapping mapVarbit(List<Integer> modifiers) {
        var mapping = TypeMapping.of("bit varying", "varbit", "pg_catalog");
        if (!modifiers.isEmpty()) {
            return mapping.withCharacterMaximumLength(modifiers.getFirst());
        }
        return mapping;
    }

    private static TypeMapping mapArray(ArrayType type) {
        var elementMapping = map(type.elementType(), "pg_catalog");
        return TypeMapping.of("ARRAY", "_" + elementMapping.udtName(), elementMapping.udtSchema());
    }

    private static TypeMapping mapUserDefined(String name, String schema) {
        return TypeMapping.of("USER-DEFINED", name, schema);
    }

    private static String schemaOrDefault(String schema, String defaultSchema) {
        return schema.isEmpty() ? defaultSchema : schema;
    }
}
