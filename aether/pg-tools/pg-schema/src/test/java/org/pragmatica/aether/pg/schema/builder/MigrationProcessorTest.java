package org.pragmatica.aether.pg.schema.builder;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProcessorTest {
    static MigrationProcessor processor;

    @BeforeAll
    static void setup() {
        processor = MigrationProcessor.create();
    }

    @Nested
    class SingleStatements {
        @Test void createSimpleTable() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint NOT NULL, name text, email varchar(255) NOT NULL)"
            )).unwrap();

            assertThat(schema.tables()).hasSize(1);
            var users = schema.table("users").unwrap();
            assertThat(users.columns()).hasSize(3);
            assertThat(users.columns().get(0).name()).isEqualTo("id");
            assertThat(users.columns().get(0).nullable()).isFalse();
            assertThat(users.columns().get(1).name()).isEqualTo("name");
            assertThat(users.columns().get(1).nullable()).isTrue();
            assertThat(users.columns().get(2).name()).isEqualTo("email");
            assertThat(users.columns().get(2).nullable()).isFalse();
        }

        @Test void createTableWithPrimaryKey() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint NOT NULL, name text, PRIMARY KEY (id))"
            )).unwrap();

            var users = schema.table("users").unwrap();
            assertThat(users.constraints()).hasSize(1);
            assertThat(users.constraints().getFirst()).isInstanceOf(org.pragmatica.aether.pg.schema.model.Constraint.PrimaryKey.class);
        }

        @Test void createTableSchemaQualified() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE public.users (id bigint)"
            )).unwrap();

            assertThat(schema.table("public.users").isPresent()).isTrue();
        }

        @Test void createEnum() {
            var schema = processor.processAll(List.of(
                "CREATE TYPE order_status AS ENUM ('pending', 'shipped', 'delivered')"
            )).unwrap();

            assertThat(schema.enumTypes()).hasSize(1);
            assertThat(schema.enumTypes().get("order_status").values())
                .containsExactly("pending", "shipped", "delivered");
        }

        @Test void createExtension() {
            var schema = processor.processAll(List.of(
                "CREATE EXTENSION IF NOT EXISTS pg_trgm"
            )).unwrap();

            assertThat(schema.extensions()).contains("pg_trgm");
        }

        @Test void createSchema() {
            var schema = processor.processAll(List.of(
                "CREATE SCHEMA analytics"
            )).unwrap();

            assertThat(schema.schemas()).contains("analytics");
        }

        @Test void createSequence() {
            var schema = processor.processAll(List.of(
                "CREATE SEQUENCE user_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1"
            )).unwrap();

            assertThat(schema.sequences()).hasSize(1);
        }
    }

    @Nested
    class AlterTableStatements {
        @Test void addColumn() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint NOT NULL)",
                "ALTER TABLE users ADD COLUMN phone varchar(20)"
            )).unwrap();

            var users = schema.table("users").unwrap();
            assertThat(users.columns()).hasSize(2);
            assertThat(users.column("phone").isPresent()).isTrue();
        }

        @Test void dropColumn() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, name text, legacy text)",
                "ALTER TABLE users DROP COLUMN legacy"
            )).unwrap();

            assertThat(schema.table("users").unwrap().columns()).hasSize(2);
            assertThat(schema.table("users").unwrap().column("legacy").isEmpty()).isTrue();
        }

        @Test void renameColumn() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, name text)",
                "ALTER TABLE users RENAME COLUMN name TO display_name"
            )).unwrap();

            assertThat(schema.table("users").unwrap().column("name").isEmpty()).isTrue();
            assertThat(schema.table("users").unwrap().column("display_name").isPresent()).isTrue();
        }

        @Test void addConstraint() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, email text)",
                "ALTER TABLE users ADD CONSTRAINT uq_email UNIQUE (email)"
            )).unwrap();

            assertThat(schema.table("users").unwrap().constraints()).hasSize(1);
        }

        @Test void setNotNull() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, name text)",
                "ALTER TABLE users ALTER COLUMN name SET NOT NULL"
            )).unwrap();

            assertThat(schema.table("users").unwrap().column("name").unwrap().nullable()).isFalse();
        }

        @Test void dropNotNull() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, name text NOT NULL)",
                "ALTER TABLE users ALTER COLUMN name DROP NOT NULL"
            )).unwrap();

            assertThat(schema.table("users").unwrap().column("name").unwrap().nullable()).isTrue();
        }
    }

    @Nested
    class IndexStatements {
        @Test void createIndex() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, email text)",
                "CREATE INDEX idx_email ON users (email)"
            )).unwrap();

            var users = schema.table("users").unwrap();
            assertThat(users.indexes()).hasSize(1);
            assertThat(users.indexes().getFirst().name()).isEqualTo("idx_email");
        }

        @Test void createUniqueIndex() {
            var schema = processor.processAll(List.of(
                "CREATE TABLE users (id bigint, email text)",
                "CREATE UNIQUE INDEX idx_email ON users (email)"
            )).unwrap();

            assertThat(schema.table("users").unwrap().indexes().getFirst().unique()).isTrue();
        }
    }

    @Nested
    class MultiStepMigration {
        @Test void realisticMigrationSequence() {
            var schema = processor.processAll(List.of(
                // V001: Base tables
                """
                CREATE TABLE users (
                    id bigserial NOT NULL,
                    name text NOT NULL,
                    email text NOT NULL,
                    created_at timestamptz DEFAULT now() NOT NULL,
                    PRIMARY KEY (id)
                )""",
                // V002: Add unique index
                "CREATE UNIQUE INDEX idx_users_email ON users (email)",
                // V003: Create orders table with FK
                """
                CREATE TABLE orders (
                    id bigserial NOT NULL,
                    user_id bigint NOT NULL,
                    total numeric(10,2) NOT NULL,
                    status text DEFAULT 'pending' NOT NULL,
                    created_at timestamptz DEFAULT now() NOT NULL,
                    PRIMARY KEY (id),
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )""",
                // V004: Add columns and index
                "ALTER TABLE users ADD COLUMN phone varchar(20)",
                "CREATE INDEX idx_orders_user ON orders (user_id)",
                // V005: Rename column
                "ALTER TABLE users RENAME COLUMN name TO display_name"
            )).unwrap();

            // Verify final state
            assertThat(schema.tables()).hasSize(2);

            var users = schema.table("users").unwrap();
            assertThat(users.columns()).hasSize(5); // id, display_name, email, created_at, phone
            assertThat(users.column("display_name").isPresent()).isTrue();
            assertThat(users.column("name").isEmpty()).isTrue();
            assertThat(users.column("phone").isPresent()).isTrue();
            assertThat(users.column("phone").unwrap().nullable()).isTrue();
            assertThat(users.indexes()).hasSize(1);
            assertThat(users.indexes().getFirst().unique()).isTrue();

            var orders = schema.table("orders").unwrap();
            assertThat(orders.columns()).hasSize(5);
            assertThat(orders.constraints()).hasSize(2); // PK + FK
            assertThat(orders.indexes()).hasSize(1);
        }

        @Test void stepwiseReturnsIntermediateSnapshots() {
            var snapshots = processor.processStepwise(List.of(
                "CREATE TABLE users (id bigint)",
                "ALTER TABLE users ADD COLUMN name text",
                "CREATE TABLE orders (id bigint)"
            )).unwrap();

            assertThat(snapshots).hasSize(3);
            assertThat(snapshots.get(0).tables()).hasSize(1);
            assertThat(snapshots.get(0).table("users").unwrap().columns()).hasSize(1);
            assertThat(snapshots.get(1).table("users").unwrap().columns()).hasSize(2);
            assertThat(snapshots.get(2).tables()).hasSize(2);
        }
    }
}
