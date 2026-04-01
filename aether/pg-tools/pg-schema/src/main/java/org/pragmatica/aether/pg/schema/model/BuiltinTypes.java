package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.pg.schema.model.PgType.TypeCategory.*;

/// Registry of built-in PostgreSQL types and their aliases.
public final class BuiltinTypes {
    private BuiltinTypes() {}

    private static final Map<String, PgType.BuiltinType> TYPES = Map.ofEntries(// Numeric
    entry("smallint", NUMERIC),
    entry("int2", NUMERIC),
    entry("integer", NUMERIC),
    entry("int", NUMERIC),
    entry("int4", NUMERIC),
    entry("bigint", NUMERIC),
    entry("int8", NUMERIC),
    entry("real", NUMERIC),
    entry("float4", NUMERIC),
    entry("double precision", NUMERIC),
    entry("float8", NUMERIC),
    entry("float", NUMERIC),
    entry("numeric", NUMERIC),
    entry("decimal", NUMERIC),
    entry("money", MONEY),
    // Serial (auto-increment)
    entry("smallserial", SERIAL),
    entry("serial2", SERIAL),
    entry("serial", SERIAL),
    entry("serial4", SERIAL),
    entry("bigserial", SERIAL),
    entry("serial8", SERIAL),
    // String
    entry("text", STRING),
    entry("varchar", STRING),
    entry("character varying", STRING),
    entry("char", STRING),
    entry("character", STRING),
    entry("name", STRING),
    entry("citext", STRING),
    // Boolean
    entry("boolean", BOOLEAN),
    entry("bool", BOOLEAN),
    // DateTime
    entry("date", DATETIME),
    entry("timestamp", DATETIME),
    entry("timestamp with time zone", DATETIME),
    entry("timestamp without time zone", DATETIME),
    entry("timestamptz", DATETIME),
    entry("time", DATETIME),
    entry("time with time zone", DATETIME),
    entry("time without time zone", DATETIME),
    entry("timetz", DATETIME),
    entry("interval", DATETIME),
    // UUID
    entry("uuid", UUID),
    // JSON
    entry("json", JSON),
    entry("jsonb", JSON),
    // Binary
    entry("bytea", BINARY),
    // Network
    entry("inet", NETWORK),
    entry("cidr", NETWORK),
    entry("macaddr", NETWORK),
    entry("macaddr8", NETWORK),
    // XML
    entry("xml", XML),
    // Bit
    entry("bit", BIT),
    entry("bit varying", BIT),
    entry("varbit", BIT),
    // Text search
    entry("tsvector", TEXTSEARCH),
    entry("tsquery", TEXTSEARCH));

    /// Canonical names for type aliases
    private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("int", "integer"),
                                                                     Map.entry("int2", "smallint"),
                                                                     Map.entry("int4", "integer"),
                                                                     Map.entry("int8", "bigint"),
                                                                     Map.entry("float4", "real"),
                                                                     Map.entry("float8", "double precision"),
                                                                     Map.entry("float", "double precision"),
                                                                     Map.entry("decimal", "numeric"),
                                                                     Map.entry("bool", "boolean"),
                                                                     Map.entry("serial2", "smallserial"),
                                                                     Map.entry("serial4", "serial"),
                                                                     Map.entry("serial8", "bigserial"),
                                                                     Map.entry("character varying", "varchar"),
                                                                     Map.entry("character", "char"),
                                                                     Map.entry("timestamp with time zone", "timestamptz"),
                                                                     Map.entry("time with time zone", "timetz"),
                                                                     Map.entry("bit varying", "varbit"));

    public static Option<PgType.BuiltinType> lookup(String typeName) {
        var normalized = typeName.toLowerCase().trim();
        var type = TYPES.get(normalized);
        return type != null
               ? Option.present(type)
               : Option.empty();
    }

    public static String canonicalize(String typeName) {
        var normalized = typeName.toLowerCase().trim();
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public static PgType resolve(String typeName, List<Integer> modifiers, int arrayDims) {
        var canonical = canonicalize(typeName);
        var builtin = TYPES.get(canonical);
        PgType base = builtin != null
                      ? new PgType.BuiltinType(canonical, builtin.category(), modifiers)
                      : new PgType.CustomType(typeName, "");
        return arrayDims > 0
               ? new PgType.ArrayType(base, arrayDims)
               : base;
    }

    private static Map.Entry<String, PgType.BuiltinType> entry(String name, PgType.TypeCategory category) {
        return Map.entry(name, new PgType.BuiltinType(name, category));
    }
}
