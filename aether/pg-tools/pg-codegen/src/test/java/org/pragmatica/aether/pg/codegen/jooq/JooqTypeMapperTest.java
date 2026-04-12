package org.pragmatica.aether.pg.codegen.jooq;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.PgType.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JooqTypeMapperTest {

    @Test
    void maps_int4() {
        var result = JooqTypeMapper.map(new BuiltinType("int4", TypeCategory.NUMERIC), "public");
        assertThat(result.dataType()).isEqualTo("integer");
        assertThat(result.udtName()).isEqualTo("int4");
        assertThat(result.udtSchema()).isEqualTo("pg_catalog");
    }

    @Test
    void maps_bigint() {
        var result = JooqTypeMapper.map(new BuiltinType("int8", TypeCategory.NUMERIC), "public");
        assertThat(result.dataType()).isEqualTo("bigint");
        assertThat(result.udtName()).isEqualTo("int8");
    }

    @Test
    void maps_smallint() {
        var result = JooqTypeMapper.map(new BuiltinType("int2", TypeCategory.NUMERIC), "public");
        assertThat(result.dataType()).isEqualTo("smallint");
        assertThat(result.udtName()).isEqualTo("int2");
    }

    @Test
    void maps_serial_to_integer() {
        var result = JooqTypeMapper.map(new BuiltinType("serial", TypeCategory.SERIAL), "public");
        assertThat(result.dataType()).isEqualTo("integer");
        assertThat(result.udtName()).isEqualTo("int4");
    }

    @Test
    void maps_bigserial_to_bigint() {
        var result = JooqTypeMapper.map(new BuiltinType("bigserial", TypeCategory.SERIAL), "public");
        assertThat(result.dataType()).isEqualTo("bigint");
        assertThat(result.udtName()).isEqualTo("int8");
    }

    @Test
    void maps_varchar_with_length() {
        var result = JooqTypeMapper.map(new BuiltinType("varchar", TypeCategory.STRING, List.of(255)), "public");
        assertThat(result.dataType()).isEqualTo("character varying");
        assertThat(result.udtName()).isEqualTo("varchar");
        assertThat(result.characterMaximumLength().isPresent()).isTrue();
        assertThat(result.characterMaximumLength().unwrap()).isEqualTo(255);
    }

    @Test
    void maps_varchar_without_length() {
        var result = JooqTypeMapper.map(new BuiltinType("varchar", TypeCategory.STRING), "public");
        assertThat(result.dataType()).isEqualTo("character varying");
        assertThat(result.characterMaximumLength().isPresent()).isFalse();
    }

    @Test
    void maps_numeric_with_precision_and_scale() {
        var result = JooqTypeMapper.map(new BuiltinType("numeric", TypeCategory.NUMERIC, List.of(19, 4)), "public");
        assertThat(result.dataType()).isEqualTo("numeric");
        assertThat(result.numericPrecision().unwrap()).isEqualTo(19);
        assertThat(result.numericScale().unwrap()).isEqualTo(4);
    }

    @Test
    void maps_text() {
        var result = JooqTypeMapper.map(new BuiltinType("text", TypeCategory.STRING), "public");
        assertThat(result.dataType()).isEqualTo("text");
        assertThat(result.udtName()).isEqualTo("text");
    }

    @Test
    void maps_uuid() {
        var result = JooqTypeMapper.map(new BuiltinType("uuid", TypeCategory.UUID), "public");
        assertThat(result.dataType()).isEqualTo("uuid");
    }

    @Test
    void maps_jsonb() {
        var result = JooqTypeMapper.map(new BuiltinType("jsonb", TypeCategory.JSON), "public");
        assertThat(result.dataType()).isEqualTo("jsonb");
        assertThat(result.udtName()).isEqualTo("jsonb");
    }

    @Test
    void maps_timestamptz() {
        var result = JooqTypeMapper.map(new BuiltinType("timestamptz", TypeCategory.DATETIME), "public");
        assertThat(result.dataType()).isEqualTo("timestamp with time zone");
        assertThat(result.udtName()).isEqualTo("timestamptz");
    }

    @Test
    void maps_boolean() {
        var result = JooqTypeMapper.map(new BuiltinType("bool", TypeCategory.BOOLEAN), "public");
        assertThat(result.dataType()).isEqualTo("boolean");
        assertThat(result.udtName()).isEqualTo("bool");
    }

    @Test
    void maps_bytea() {
        var result = JooqTypeMapper.map(new BuiltinType("bytea", TypeCategory.BINARY), "public");
        assertThat(result.dataType()).isEqualTo("bytea");
    }

    @Test
    void maps_inet() {
        var result = JooqTypeMapper.map(new BuiltinType("inet", TypeCategory.NETWORK), "public");
        assertThat(result.dataType()).isEqualTo("inet");
    }

    @Test
    void maps_array_type() {
        var inner = new BuiltinType("int4", TypeCategory.NUMERIC);
        var result = JooqTypeMapper.map(new ArrayType(inner, 1), "public");
        assertThat(result.dataType()).isEqualTo("ARRAY");
        assertThat(result.udtName()).isEqualTo("_int4");
        assertThat(result.udtSchema()).isEqualTo("pg_catalog");
    }

    @Test
    void maps_text_array() {
        var inner = new BuiltinType("text", TypeCategory.STRING);
        var result = JooqTypeMapper.map(new ArrayType(inner, 1), "public");
        assertThat(result.dataType()).isEqualTo("ARRAY");
        assertThat(result.udtName()).isEqualTo("_text");
    }

    @Test
    void maps_enum_as_user_defined() {
        var enumType = new EnumType("order_status", "public", List.of("pending", "shipped"));
        var result = JooqTypeMapper.map(enumType, "public");
        assertThat(result.dataType()).isEqualTo("USER-DEFINED");
        assertThat(result.udtName()).isEqualTo("order_status");
        assertThat(result.udtSchema()).isEqualTo("public");
    }

    @Test
    void maps_enum_with_empty_schema_uses_default() {
        var enumType = new EnumType("status", "", List.of("active"));
        var result = JooqTypeMapper.map(enumType, "public");
        assertThat(result.udtSchema()).isEqualTo("public");
    }

    @Test
    void maps_domain_as_user_defined() {
        var domain = new DomainType("email", "public",
                                    new BuiltinType("text", TypeCategory.STRING),
                                    org.pragmatica.lang.Option.present("VALUE ~ '^.+@.+$'"));
        var result = JooqTypeMapper.map(domain, "public");
        assertThat(result.dataType()).isEqualTo("USER-DEFINED");
        assertThat(result.udtName()).isEqualTo("email");
    }

    @Test
    void maps_composite_as_user_defined() {
        var composite = new CompositeType("address", "public", List.of());
        var result = JooqTypeMapper.map(composite, "public");
        assertThat(result.dataType()).isEqualTo("USER-DEFINED");
        assertThat(result.udtName()).isEqualTo("address");
    }

    @Test
    void maps_unknown_builtin_preserves_name() {
        var result = JooqTypeMapper.map(new BuiltinType("pg_lsn", TypeCategory.NUMERIC), "public");
        assertThat(result.dataType()).isEqualTo("pg_lsn");
        assertThat(result.udtName()).isEqualTo("pg_lsn");
        assertThat(result.udtSchema()).isEqualTo("pg_catalog");
    }
}
