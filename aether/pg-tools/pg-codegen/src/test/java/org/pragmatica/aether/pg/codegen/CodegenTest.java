package org.pragmatica.aether.pg.codegen;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodegenTest {

    CodegenConfig config(Path dir) {
        return CodegenConfig.defaults("com.example.db", dir);
    }

    @Nested
    class NamingConventionTests {
        @Test void tableToClassName() {
            assertThat(NamingConvention.tableToClassName("user_accounts", "Row")).isEqualTo("UserAccountsRow");
        }

        @Test void singleWord() {
            assertThat(NamingConvention.tableToClassName("users", "Row")).isEqualTo("UsersRow");
        }

        @Test void toFieldName() {
            assertThat(NamingConvention.toFieldName("created_at")).isEqualTo("createdAt");
        }

        @Test void toFieldNameSingleWord() {
            assertThat(NamingConvention.toFieldName("name")).isEqualTo("name");
        }

        @Test void toFactoryMethodName() {
            assertThat(NamingConvention.toFactoryMethodName("UsersRow")).isEqualTo("usersRow");
        }

        @Test void toEnumConstant() {
            assertThat(NamingConvention.toEnumConstant("pending")).isEqualTo("PENDING");
            assertThat(NamingConvention.toEnumConstant("in-progress")).isEqualTo("IN_PROGRESS");
        }

        @Test void toTypeName() {
            assertThat(NamingConvention.toTypeName("order_status")).isEqualTo("OrderStatus");
        }
    }

    @Nested
    class TypeMapperTests {
        @Test void integer() {
            var info = TypeMapper.map(new PgType.BuiltinType("integer", PgType.TypeCategory.NUMERIC)).unwrap();
            assertThat(info.typeName()).isEqualTo("int");
            assertThat(info.primitive()).isTrue();
            assertThat(info.rowAccessorMethod()).isEqualTo("getInt");
        }

        @Test void bigint() {
            var info = TypeMapper.map(new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC)).unwrap();
            assertThat(info.typeName()).isEqualTo("long");
        }

        @Test void text() {
            var info = TypeMapper.map(new PgType.BuiltinType("text", PgType.TypeCategory.STRING)).unwrap();
            assertThat(info.typeName()).isEqualTo("String");
            assertThat(info.primitive()).isFalse();
        }

        @Test void timestamptz() {
            var info = TypeMapper.map(new PgType.BuiltinType("timestamptz", PgType.TypeCategory.DATETIME)).unwrap();
            assertThat(info.typeName()).isEqualTo("java.time.Instant");
        }

        @Test void uuid() {
            var info = TypeMapper.map(new PgType.BuiltinType("uuid", PgType.TypeCategory.UUID)).unwrap();
            assertThat(info.typeName()).isEqualTo("java.util.UUID");
        }

        @Test void jsonb() {
            var info = TypeMapper.map(new PgType.BuiltinType("jsonb", PgType.TypeCategory.JSON)).unwrap();
            assertThat(info.typeName()).isEqualTo("String");
        }

        @Test void booleanType() {
            var info = TypeMapper.map(new PgType.BuiltinType("boolean", PgType.TypeCategory.BOOLEAN)).unwrap();
            assertThat(info.typeName()).isEqualTo("boolean");
            assertThat(info.primitive()).isTrue();
        }

        @Test void numeric() {
            var info = TypeMapper.map(new PgType.BuiltinType("numeric", PgType.TypeCategory.NUMERIC, List.of(10, 2))).unwrap();
            assertThat(info.typeName()).isEqualTo("java.math.BigDecimal");
        }

        @Test void array() {
            var arrType = new PgType.ArrayType(new PgType.BuiltinType("text", PgType.TypeCategory.STRING), 1);
            var info = TypeMapper.map(arrType).unwrap();
            assertThat(info.typeName()).isEqualTo("String[]");
        }

        @Test void nullablePrimitiveGetsBoxed() {
            var info = TypeMapper.mapNullable(
                new PgType.BuiltinType("integer", PgType.TypeCategory.NUMERIC),
                CodegenConfig.NullableStyle.OPTION
            ).unwrap();
            assertThat(info.typeName()).isEqualTo("Integer");
            assertThat(info.primitive()).isFalse();
        }
    }

    @Nested
    class RecordGeneratorTests {
        @Test void simpleTable(@TempDir Path tempDir) {
            var gen = new RecordGenerator(config(tempDir));
            var table = Table.table("users", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                Column.column("name", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false),
                Column.column("email", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), true)
            ), List.of());

            var result = gen.generate(table);
            assertThat(result.isSuccess()).isTrue();

            var file = result.unwrap();
            assertThat(file.className()).isEqualTo("UsersRow");
            assertThat(file.content()).contains("public record UsersRow(");
            assertThat(file.content()).contains("long id");
            assertThat(file.content()).contains("String name");
            assertThat(file.content()).contains("Option<String> email");
            assertThat(file.content()).contains("package com.example.db;");
        }

        @Test void generatesStaticFactory(@TempDir Path tempDir) {
            var gen = new RecordGenerator(config(tempDir));
            var table = Table.table("orders", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false)
            ), List.of());

            var content = gen.generate(table).unwrap().content();
            assertThat(content).contains("public static OrdersRow ordersRow(");
        }

        @Test void generatesRowMapper(@TempDir Path tempDir) {
            var gen = new RecordGenerator(config(tempDir));
            var table = Table.table("users", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                Column.column("name", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false)
            ), List.of());

            var content = gen.generate(table).unwrap().content();
            assertThat(content).contains("mapRow(RowAccessor row)");
            assertThat(content).contains("Result.all(");
            assertThat(content).contains("row.getLong(\"id\")");
            assertThat(content).contains("row.getString(\"name\")");
        }

        @Test void handlesVariousTypes(@TempDir Path tempDir) {
            var gen = new RecordGenerator(config(tempDir));
            var table = Table.table("test", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                Column.column("active", new PgType.BuiltinType("boolean", PgType.TypeCategory.BOOLEAN), false),
                Column.column("score", new PgType.BuiltinType("numeric", PgType.TypeCategory.NUMERIC, List.of(5, 2)), true),
                Column.column("created_at", new PgType.BuiltinType("timestamptz", PgType.TypeCategory.DATETIME), false),
                Column.column("data", new PgType.BuiltinType("jsonb", PgType.TypeCategory.JSON), true),
                Column.column("tags", new PgType.ArrayType(new PgType.BuiltinType("text", PgType.TypeCategory.STRING), 1), true)
            ), List.of());

            var content = gen.generate(table).unwrap().content();
            assertThat(content).contains("long id");
            assertThat(content).contains("boolean active");
            assertThat(content).contains("Option<java.math.BigDecimal> score");
            assertThat(content).contains("java.time.Instant createdAt");
            assertThat(content).contains("Option<String> data");
            assertThat(content).contains("Option<String[]> tags");
        }

        @Test void outputPathReflectsConfig(@TempDir Path tempDir) {
            var gen = new RecordGenerator(config(tempDir));
            var table = Table.table("users", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false)
            ), List.of());

            var file = gen.generate(table).unwrap();
            assertThat(file.path()).isEqualTo(tempDir.resolve("com/example/db/UsersRow.java"));
        }
    }

    @Nested
    class EnumGeneratorTests {
        @Test void simpleEnum(@TempDir Path tempDir) {
            var gen = new EnumGenerator(config(tempDir));
            var enumType = new PgType.EnumType("order_status", "", List.of("pending", "shipped", "delivered"));

            var result = gen.generate(enumType);
            assertThat(result.isSuccess()).isTrue();

            var file = result.unwrap();
            assertThat(file.className()).isEqualTo("OrderStatus");
            assertThat(file.content()).contains("public enum OrderStatus");
            assertThat(file.content()).contains("PENDING(\"pending\")");
            assertThat(file.content()).contains("SHIPPED(\"shipped\")");
            assertThat(file.content()).contains("DELIVERED(\"delivered\")");
            assertThat(file.content()).contains("fromPgValue(String value)");
        }
    }

    @Nested
    class PipelineTests {
        @Test void endToEndFromSql(@TempDir Path tempDir) {
            var pipeline = new CodegenPipeline(config(tempDir));

            var files = pipeline.generate(List.of(
                """
                CREATE TABLE users (
                    id bigint NOT NULL,
                    name text NOT NULL,
                    email text NOT NULL,
                    active boolean DEFAULT true NOT NULL,
                    created_at timestamptz DEFAULT now() NOT NULL,
                    PRIMARY KEY (id)
                )""",
                "CREATE TYPE user_role AS ENUM ('admin', 'user', 'guest')"
            )).unwrap();

            assertThat(files).hasSize(2);

            var record = files.stream().filter(f -> f.className().equals("UsersRow")).findFirst().orElseThrow();
            assertThat(record.content()).contains("public record UsersRow(");
            assertThat(record.content()).contains("long id");
            assertThat(record.content()).contains("boolean active");
            assertThat(record.content()).contains("java.time.Instant createdAt");

            var enumFile = files.stream().filter(f -> f.className().equals("UserRole")).findFirst().orElseThrow();
            assertThat(enumFile.content()).contains("ADMIN(\"admin\")");
        }

        @Test void writesToDisk(@TempDir Path tempDir) throws Exception {
            var pipeline = new CodegenPipeline(config(tempDir));

            pipeline.generateAndWrite(List.of(
                "CREATE TABLE orders (id bigint NOT NULL, total numeric(10,2) NOT NULL)"
            )).unwrap();

            var outputFile = tempDir.resolve("com/example/db/OrdersRow.java");
            assertThat(Files.exists(outputFile)).isTrue();
            var content = Files.readString(outputFile);
            assertThat(content).contains("public record OrdersRow(");
        }

        @Test void customOutputDirectory(@TempDir Path tempDir) {
            var customDir = tempDir.resolve("src/gen/java");
            var pipeline = new CodegenPipeline(CodegenConfig.defaults("my.app.model", customDir));

            var files = pipeline.generate(List.of(
                "CREATE TABLE products (id bigint NOT NULL, name text NOT NULL)"
            )).unwrap();

            assertThat(files.getFirst().path().toString())
                .contains("src/gen/java/my/app/model/ProductsRow.java");
            assertThat(files.getFirst().content()).contains("package my.app.model;");
        }
    }
}
