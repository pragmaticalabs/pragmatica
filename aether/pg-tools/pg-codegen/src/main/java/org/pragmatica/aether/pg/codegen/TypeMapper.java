package org.pragmatica.aether.pg.codegen;

import org.pragmatica.aether.pg.schema.model.BuiltinTypes;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.lang.Option;

import java.util.Map;
import java.util.Set;

/// Maps PostgreSQL types to Java types.
public final class TypeMapper {
    private TypeMapper() {}

    public record JavaTypeInfo(
        String typeName,
        String boxedTypeName,
        boolean primitive,
        Option<String> importStatement,
        String rowAccessorMethod,
        Option<String> rowAccessorTypeArg
    ) {}

    private static final Map<String, JavaTypeInfo> MAPPINGS = Map.ofEntries(
        // Numeric
        entry("smallint", "short", "Short", true, "java.lang.Short", "getObject", "Short.class"),
        entry("integer", "int", "Integer", true, null, "getInt", null),
        entry("bigint", "long", "Long", true, null, "getLong", null),
        entry("real", "float", "Float", true, "java.lang.Float", "getObject", "Float.class"),
        entry("double precision", "double", "Double", true, null, "getDouble", null),
        entry("numeric", "java.math.BigDecimal", "java.math.BigDecimal", false, "java.math.BigDecimal", "getObject", "java.math.BigDecimal.class"),
        entry("money", "java.math.BigDecimal", "java.math.BigDecimal", false, "java.math.BigDecimal", "getObject", "java.math.BigDecimal.class"),

        // Serial (map to their underlying integer types)
        entry("smallserial", "short", "Short", true, "java.lang.Short", "getObject", "Short.class"),
        entry("serial", "int", "Integer", true, null, "getInt", null),
        entry("bigserial", "long", "Long", true, null, "getLong", null),

        // String
        entry("text", "String", "String", false, null, "getString", null),
        entry("varchar", "String", "String", false, null, "getString", null),
        entry("char", "String", "String", false, null, "getString", null),
        entry("name", "String", "String", false, null, "getString", null),
        entry("citext", "String", "String", false, null, "getString", null),

        // Boolean
        entry("boolean", "boolean", "Boolean", true, null, "getBoolean", null),

        // DateTime
        entry("date", "java.time.LocalDate", "java.time.LocalDate", false, "java.time.LocalDate", "getObject", "java.time.LocalDate.class"),
        entry("timestamp", "java.time.LocalDateTime", "java.time.LocalDateTime", false, "java.time.LocalDateTime", "getObject", "java.time.LocalDateTime.class"),
        entry("timestamp without time zone", "java.time.LocalDateTime", "java.time.LocalDateTime", false, "java.time.LocalDateTime", "getObject", "java.time.LocalDateTime.class"),
        entry("timestamptz", "java.time.Instant", "java.time.Instant", false, "java.time.Instant", "getObject", "java.time.Instant.class"),
        entry("timestamp with time zone", "java.time.Instant", "java.time.Instant", false, "java.time.Instant", "getObject", "java.time.Instant.class"),
        entry("time", "java.time.LocalTime", "java.time.LocalTime", false, "java.time.LocalTime", "getObject", "java.time.LocalTime.class"),
        entry("time without time zone", "java.time.LocalTime", "java.time.LocalTime", false, "java.time.LocalTime", "getObject", "java.time.LocalTime.class"),
        entry("timetz", "java.time.OffsetTime", "java.time.OffsetTime", false, "java.time.OffsetTime", "getObject", "java.time.OffsetTime.class"),
        entry("time with time zone", "java.time.OffsetTime", "java.time.OffsetTime", false, "java.time.OffsetTime", "getObject", "java.time.OffsetTime.class"),
        entry("interval", "java.time.Duration", "java.time.Duration", false, "java.time.Duration", "getObject", "java.time.Duration.class"),

        // UUID
        entry("uuid", "java.util.UUID", "java.util.UUID", false, "java.util.UUID", "getObject", "java.util.UUID.class"),

        // JSON
        entry("json", "String", "String", false, null, "getString", null),
        entry("jsonb", "String", "String", false, null, "getString", null),

        // Binary
        entry("bytea", "byte[]", "byte[]", false, null, "getBytes", null),

        // Network
        entry("inet", "String", "String", false, null, "getString", null),
        entry("cidr", "String", "String", false, null, "getString", null),
        entry("macaddr", "String", "String", false, null, "getString", null),

        // Text search
        entry("tsvector", "String", "String", false, null, "getString", null),
        entry("tsquery", "String", "String", false, null, "getString", null),

        // XML
        entry("xml", "String", "String", false, null, "getString", null)
    );

    /// Map a PgType to its Java type info.
    public static Option<JavaTypeInfo> map(PgType type) {
        return switch (type) {
            case PgType.BuiltinType bt -> {
                var canonical = BuiltinTypes.canonicalize(bt.name());
                var info = MAPPINGS.get(canonical);
                yield info != null ? Option.present(info) : Option.empty();
            }
            case PgType.ArrayType at -> map(at.elementType()).map(elem ->
                new JavaTypeInfo(
                    elem.typeName() + "[]",
                    elem.boxedTypeName() + "[]",
                    false,
                    elem.importStatement(),
                    "getObject",
                    Option.present(elem.typeName() + "[].class")
                ));
            case PgType.EnumType _ -> Option.present(new JavaTypeInfo(
                "String", "String", false, Option.empty(), "getString", Option.empty()));
            default -> Option.present(new JavaTypeInfo(
                "String", "String", false, Option.empty(), "getString", Option.empty()));
        };
    }

    /// Map a PgType for a nullable column (primitives get boxed).
    public static Option<JavaTypeInfo> mapNullable(PgType type, CodegenConfig.NullableStyle style) {
        return map(type).map(info -> {
            if (style == CodegenConfig.NullableStyle.OPTION) {
                return info.primitive()
                    ? new JavaTypeInfo(info.boxedTypeName(), info.boxedTypeName(), false, info.importStatement(), info.rowAccessorMethod(), info.rowAccessorTypeArg())
                    : info;
            }
            return info; // NULLABLE_ANNOTATION keeps same type
        });
    }

    /// Get the set of imports needed for a Java type.
    public static Set<String> importsFor(JavaTypeInfo info) {
        var imports = new java.util.HashSet<String>();
        info.importStatement().onPresent(imports::add);
        return imports;
    }

    private static Map.Entry<String, JavaTypeInfo> entry(String pgType, String javaType, String boxedType,
                                                          boolean primitive, String importStmt,
                                                          String accessor, String typeArg) {
        return Map.entry(pgType, new JavaTypeInfo(
            javaType, boxedType, primitive,
            importStmt != null ? Option.present(importStmt) : Option.empty(),
            accessor,
            typeArg != null ? Option.present(typeArg) : Option.empty()
        ));
    }
}
