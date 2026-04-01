package org.pragmatica.aether.pg.schema.builder;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceLocation;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaBuilderTest {

    static final SourceSpan SPAN = SourceSpan.of(SourceLocation.START, SourceLocation.START);

    @Nested
    class TableEvents {
        @Test void createTable() {
            var columns = List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                Column.column("name", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), true)
            );
            var event = new SchemaEvent.TableCreated(SPAN, "users", "", columns, List.of(), Option.empty());

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.tables()).hasSize(1);
            var table = schema.table("users").unwrap();
            assertThat(table.columns()).hasSize(2);
            assertThat(table.columns().getFirst().name()).isEqualTo("id");
            assertThat(table.columns().getLast().name()).isEqualTo("name");
            assertThat(table.columns().getFirst().nullable()).isFalse();
            assertThat(table.columns().getLast().nullable()).isTrue();
        }

        @Test void createSchemaQualifiedTable() {
            var columns = List.of(Column.column("id", new PgType.BuiltinType("integer", PgType.TypeCategory.NUMERIC), false));
            var event = new SchemaEvent.TableCreated(SPAN, "users", "public", columns, List.of(), Option.empty());

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.table("public.users").isPresent()).isTrue();
        }

        @Test void dropTable() {
            var create = new SchemaEvent.TableCreated(SPAN, "users", "", List.of(), List.of(), Option.empty());
            var drop = new SchemaEvent.TableDropped(SPAN, "users");

            var schema = SchemaBuilder.build(List.of(create, drop)).unwrap();

            assertThat(schema.tables()).isEmpty();
        }

        @Test void renameTable() {
            var create = new SchemaEvent.TableCreated(SPAN, "users", "", List.of(), List.of(), Option.empty());
            var rename = new SchemaEvent.TableRenamed(SPAN, "users", "accounts");

            var schema = SchemaBuilder.build(List.of(create, rename)).unwrap();

            assertThat(schema.table("users").isEmpty()).isTrue();
            assertThat(schema.table("accounts").isPresent()).isTrue();
        }

        @Test void createTableWithConstraints() {
            var columns = List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                Column.column("email", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false)
            );
            var constraints = List.<Constraint>of(
                new Constraint.PrimaryKey(Option.present("pk_users"), List.of("id")),
                new Constraint.Unique(Option.present("uq_email"), List.of("email"))
            );
            var event = new SchemaEvent.TableCreated(SPAN, "users", "", columns, constraints, Option.empty());

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.table("users").unwrap().constraints()).hasSize(2);
        }
    }

    @Nested
    class ColumnEvents {
        SchemaEvent.TableCreated createUsers() {
            return new SchemaEvent.TableCreated(SPAN, "users", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                Column.column("name", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), true)
            ), List.of(), Option.empty());
        }

        @Test void addColumn() {
            var add = new SchemaEvent.ColumnAdded(SPAN, "users",
                Column.column("email", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false));

            var schema = SchemaBuilder.build(List.of(createUsers(), add)).unwrap();

            assertThat(schema.table("users").unwrap().columns()).hasSize(3);
            assertThat(schema.table("users").unwrap().column("email").isPresent()).isTrue();
        }

        @Test void dropColumn() {
            var drop = new SchemaEvent.ColumnDropped(SPAN, "users", "name");

            var schema = SchemaBuilder.build(List.of(createUsers(), drop)).unwrap();

            assertThat(schema.table("users").unwrap().columns()).hasSize(1);
            assertThat(schema.table("users").unwrap().column("name").isEmpty()).isTrue();
        }

        @Test void renameColumn() {
            var rename = new SchemaEvent.ColumnRenamed(SPAN, "users", "name", "display_name");

            var schema = SchemaBuilder.build(List.of(createUsers(), rename)).unwrap();

            assertThat(schema.table("users").unwrap().column("name").isEmpty()).isTrue();
            assertThat(schema.table("users").unwrap().column("display_name").isPresent()).isTrue();
        }

        @Test void changeColumnType() {
            var change = new SchemaEvent.ColumnTypeChanged(SPAN, "users", "name",
                new PgType.BuiltinType("varchar", PgType.TypeCategory.STRING, List.of(255)));

            var schema = SchemaBuilder.build(List.of(createUsers(), change)).unwrap();

            var col = schema.table("users").unwrap().column("name").unwrap();
            assertThat(col.type().name()).isEqualTo("varchar");
        }

        @Test void changeColumnDefault() {
            var change = new SchemaEvent.ColumnDefaultChanged(SPAN, "users", "name", Option.present("'unknown'"));

            var schema = SchemaBuilder.build(List.of(createUsers(), change)).unwrap();

            var col = schema.table("users").unwrap().column("name").unwrap();
            assertThat(col.defaultExpr().isPresent()).isTrue();
            assertThat(col.defaultExpr().unwrap()).isEqualTo("'unknown'");
        }

        @Test void changeColumnNullability() {
            var change = new SchemaEvent.ColumnNullabilityChanged(SPAN, "users", "name", false);

            var schema = SchemaBuilder.build(List.of(createUsers(), change)).unwrap();

            assertThat(schema.table("users").unwrap().column("name").unwrap().nullable()).isFalse();
        }

        @Test void columnNotFoundError() {
            var change = new SchemaEvent.ColumnRenamed(SPAN, "users", "nonexistent", "other");

            var result = SchemaBuilder.build(List.of(createUsers(), change));

            assertThat(result.isFailure()).isTrue();
        }

        @Test void tableNotFoundError() {
            var add = new SchemaEvent.ColumnAdded(SPAN, "nonexistent",
                Column.column("x", new PgType.BuiltinType("integer", PgType.TypeCategory.NUMERIC), true));

            var result = SchemaBuilder.build(List.of(add));

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class ConstraintEvents {
        @Test void addConstraint() {
            var create = new SchemaEvent.TableCreated(SPAN, "users", "", List.of(
                Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false)
            ), List.of(), Option.empty());
            var addPk = new SchemaEvent.ConstraintAdded(SPAN, "users",
                new Constraint.PrimaryKey(Option.present("pk_users"), List.of("id")));

            var schema = SchemaBuilder.build(List.of(create, addPk)).unwrap();

            assertThat(schema.table("users").unwrap().constraints()).hasSize(1);
        }

        @Test void dropConstraint() {
            var create = new SchemaEvent.TableCreated(SPAN, "users", "", List.of(), List.of(
                new Constraint.Unique(Option.present("uq_email"), List.of("email"))
            ), Option.empty());
            var drop = new SchemaEvent.ConstraintDropped(SPAN, "users", "uq_email");

            var schema = SchemaBuilder.build(List.of(create, drop)).unwrap();

            assertThat(schema.table("users").unwrap().constraints()).isEmpty();
        }
    }

    @Nested
    class IndexEvents {
        @Test void createIndex() {
            var createTable = new SchemaEvent.TableCreated(SPAN, "users", "", List.of(
                Column.column("email", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false)
            ), List.of(), Option.empty());
            var createIndex = new SchemaEvent.IndexCreated(SPAN,
                Index.index("idx_email", "users", List.of(
                    new Index.IndexElement("email", Option.empty(), Option.empty())
                )));

            var schema = SchemaBuilder.build(List.of(createTable, createIndex)).unwrap();

            assertThat(schema.table("users").unwrap().indexes()).hasSize(1);
            assertThat(schema.table("users").unwrap().indexes().getFirst().name()).isEqualTo("idx_email");
        }
    }

    @Nested
    class TypeEvents {
        @Test void createEnum() {
            var event = new SchemaEvent.TypeCreated(SPAN,
                new PgType.EnumType("order_status", "", List.of("pending", "shipped", "delivered")));

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.enumTypes()).hasSize(1);
            assertThat(schema.enumTypes().get("order_status").values()).containsExactly("pending", "shipped", "delivered");
        }

        @Test void addEnumValue() {
            var create = new SchemaEvent.TypeCreated(SPAN,
                new PgType.EnumType("status", "", List.of("active", "inactive")));
            var addValue = new SchemaEvent.EnumValueAdded(SPAN, "status", "suspended", Option.empty(), Option.present("inactive"));

            var schema = SchemaBuilder.build(List.of(create, addValue)).unwrap();

            assertThat(schema.enumTypes().get("status").values()).containsExactly("active", "inactive", "suspended");
        }
    }

    @Nested
    class SequenceEvents {
        @Test void createSequence() {
            var event = new SchemaEvent.SequenceCreated(SPAN, Sequence.sequence("user_id_seq", ""));

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.sequences()).hasSize(1);
        }

        @Test void dropSequence() {
            var create = new SchemaEvent.SequenceCreated(SPAN, Sequence.sequence("user_id_seq", ""));
            var drop = new SchemaEvent.SequenceDropped(SPAN, "user_id_seq");

            var schema = SchemaBuilder.build(List.of(create, drop)).unwrap();

            assertThat(schema.sequences()).isEmpty();
        }
    }

    @Nested
    class SchemaEvents {
        @Test void createSchema() {
            var event = new SchemaEvent.SchemaCreated(SPAN, "analytics");

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.schemas()).contains("analytics");
        }
    }

    @Nested
    class ExtensionEvents {
        @Test void createExtension() {
            var event = new SchemaEvent.ExtensionCreated(SPAN, "pg_trgm");

            var schema = SchemaBuilder.build(List.of(event)).unwrap();

            assertThat(schema.extensions()).contains("pg_trgm");
        }
    }

    @Nested
    class MigrationSequence {
        @Test void multiStepMigration() {
            var events = List.<SchemaEvent>of(
                // V001: Create users table
                new SchemaEvent.TableCreated(SPAN, "users", "", List.of(
                    Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                    Column.column("name", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false),
                    Column.column("email", new PgType.BuiltinType("text", PgType.TypeCategory.STRING), false)
                ), List.of(
                    new Constraint.PrimaryKey(Option.present("pk_users"), List.of("id"))
                ), Option.empty()),

                // V002: Add unique index on email
                new SchemaEvent.IndexCreated(SPAN, new Index(
                    "idx_users_email", "users",
                    List.of(new Index.IndexElement("email", Option.empty(), Option.empty())),
                    Index.IndexMethod.BTREE, true, false, Option.empty(), List.of()
                )),

                // V003: Add phone column, rename name to display_name
                new SchemaEvent.ColumnAdded(SPAN, "users",
                    Column.column("phone", new PgType.BuiltinType("varchar", PgType.TypeCategory.STRING, List.of(20)), true)),
                new SchemaEvent.ColumnRenamed(SPAN, "users", "name", "display_name"),

                // V004: Create orders table with FK
                new SchemaEvent.TableCreated(SPAN, "orders", "", List.of(
                    Column.column("id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                    Column.column("user_id", new PgType.BuiltinType("bigint", PgType.TypeCategory.NUMERIC), false),
                    Column.column("total", new PgType.BuiltinType("numeric", PgType.TypeCategory.NUMERIC, List.of(10, 2)), false)
                ), List.of(
                    new Constraint.PrimaryKey(Option.present("pk_orders"), List.of("id")),
                    new Constraint.ForeignKey(Option.present("fk_orders_user"), List.of("user_id"),
                        "users", List.of("id"), Constraint.FkAction.CASCADE, Constraint.FkAction.RESTRICT)
                ), Option.empty())
            );

            var schema = SchemaBuilder.build(events).unwrap();

            // Verify final state
            assertThat(schema.tables()).hasSize(2);

            var users = schema.table("users").unwrap();
            assertThat(users.columns()).hasSize(4); // id, display_name, email, phone
            assertThat(users.column("display_name").isPresent()).isTrue();
            assertThat(users.column("name").isEmpty()).isTrue();
            assertThat(users.column("phone").unwrap().nullable()).isTrue();
            assertThat(users.indexes()).hasSize(1);
            assertThat(users.indexes().getFirst().unique()).isTrue();
            assertThat(users.constraints()).hasSize(1);

            var orders = schema.table("orders").unwrap();
            assertThat(orders.columns()).hasSize(3);
            assertThat(orders.constraints()).hasSize(2);
        }
    }
}
