package org.pragmatica.aether.pg.codegen.jooq;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.aether.pg.schema.model.Constraint.*;
import org.pragmatica.aether.pg.schema.model.PgType.*;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JooqXmlExporterTest {

    private final JooqXmlConfig config = JooqXmlConfig.defaults();

    @Test
    void empty_schema_produces_valid_xml() {
        var schema = Schema.empty().withSchema("public");
        var result = JooqXmlExporter.toXml(schema, config);
        assertThat(result.isSuccess()).isTrue();
        var xml = result.unwrap();
        assertThat(xml).contains("<information_schema");
        assertThat(xml).contains("<catalogs>");
        assertThat(xml).contains("<schemata>");
        assertThat(xml).contains("<tables>");
        assertThat(xml).contains("</information_schema>");
    }

    @Test
    void single_table_with_columns() {
        var table = Table.table("users", "public",
                                List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false),
                                        Column.column("name", new BuiltinType("text", TypeCategory.STRING), false),
                                        Column.column("email", new BuiltinType("varchar", TypeCategory.STRING, List.of(255)), true)),
                                List.of());
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        assertThat(result.isSuccess()).isTrue();
        var xml = result.unwrap();
        assertThat(xml).contains("<table_name>users</table_name>");
        assertThat(xml).contains("<column_name>id</column_name>");
        assertThat(xml).contains("<data_type>bigint</data_type>");
        assertThat(xml).contains("<column_name>name</column_name>");
        assertThat(xml).contains("<data_type>text</data_type>");
        assertThat(xml).contains("<column_name>email</column_name>");
        assertThat(xml).contains("<character_maximum_length>255</character_maximum_length>");
        assertThat(xml).contains("<is_nullable>NO</is_nullable>");
        assertThat(xml).contains("<is_nullable>YES</is_nullable>");
        assertThat(xml).contains("<ordinal_position>1</ordinal_position>");
        assertThat(xml).contains("<ordinal_position>3</ordinal_position>");
    }

    @Test
    void primary_key_constraint() {
        var pk = new PrimaryKey(Option.present("users_pkey"), List.of("id"));
        var table = Table.table("users", "public",
                                List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)),
                                List.of(pk));
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<constraint_name>users_pkey</constraint_name>");
        assertThat(xml).contains("<constraint_type>PRIMARY KEY</constraint_type>");
        assertThat(xml).contains("<column_name>id</column_name>");
    }

    @Test
    void foreign_key_with_cascade() {
        var fk = new ForeignKey(Option.present("orders_user_fk"),
                                List.of("user_id"),
                                "users",
                                List.of("id"),
                                FkAction.NO_ACTION,
                                FkAction.CASCADE);
        var usersTable = Table.table("users", "public",
                                     List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)),
                                     List.of(new PrimaryKey(Option.present("users_pkey"), List.of("id"))));
        var ordersTable = Table.table("orders", "public",
                                      List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false),
                                              Column.column("user_id", new BuiltinType("int8", TypeCategory.NUMERIC), false)),
                                      List.of(fk));
        var schema = Schema.empty().withSchema("public").withTable(usersTable).withTable(ordersTable);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<constraint_type>FOREIGN KEY</constraint_type>");
        assertThat(xml).contains("<delete_rule>CASCADE</delete_rule>");
        assertThat(xml).contains("<update_rule>NO ACTION</update_rule>");
    }

    @Test
    void check_constraint() {
        var check = new Check(Option.present("price_positive"), "price >= 0");
        var table = Table.table("products", "public",
                                List.of(Column.column("price", new BuiltinType("numeric", TypeCategory.NUMERIC, List.of(19, 4)), false)),
                                List.of(check));
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<constraint_name>price_positive</constraint_name>");
        assertThat(xml).contains("<check_clause>price &gt;= 0</check_clause>");
    }

    @Test
    void enum_type_emitted() {
        var enumType = new EnumType("order_status", "public", List.of("pending", "shipped", "delivered"));
        var table = Table.table("orders", "public",
                                List.of(Column.column("status", enumType, false)),
                                List.of());
        var schema = Schema.empty().withSchema("public").withTable(table).withEnumType(enumType);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<data_type>USER-DEFINED</data_type>");
        assertThat(xml).contains("<udt_name>order_status</udt_name>");
        assertThat(xml).contains("<enums>");
        assertThat(xml).contains("<enum_name>order_status</enum_name>");
        assertThat(xml).contains("<enum_value>pending</enum_value>");
        assertThat(xml).contains("<enum_value>shipped</enum_value>");
        assertThat(xml).contains("<enum_value>delivered</enum_value>");
    }

    @Test
    void array_column() {
        var arrayType = new ArrayType(new BuiltinType("text", TypeCategory.STRING), 1);
        var table = Table.table("products", "public",
                                List.of(Column.column("tags", arrayType, true)),
                                List.of());
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<data_type>ARRAY</data_type>");
        assertThat(xml).contains("<udt_name>_text</udt_name>");
    }

    @Test
    void index_emitted() {
        var index = Index.index("idx_users_email", "users",
                                List.of(new Index.IndexElement("email", Option.empty(), Option.empty())));
        var table = new Table("users", "public",
                              List.of(Column.column("email", new BuiltinType("text", TypeCategory.STRING), false)),
                              List.of(),
                              List.of(index),
                              Option.empty(),
                              Option.empty());
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<index_name>idx_users_email</index_name>");
        assertThat(xml).contains("<is_unique>NO</is_unique>");
    }

    @Test
    void sequence_emitted() {
        var seq = new Sequence("users_id_seq", "public",
                               Option.present("bigint"),
                               Option.present(1L),
                               Option.present(1L),
                               Option.present(1L),
                               Option.present(9223372036854775807L),
                               Option.present(1L),
                               false,
                               Option.present("users.id"));
        var schema = Schema.empty().withSchema("public").withSequence(seq);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<sequence_name>users_id_seq</sequence_name>");
        assertThat(xml).contains("<data_type>bigint</data_type>");
        assertThat(xml).contains("<start_value>1</start_value>");
        assertThat(xml).contains("<cycle_option>NO</cycle_option>");
    }

    @Test
    void identity_column() {
        var col = Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)
                        .withDefault("nextval('users_id_seq')");
        var identityCol = new Column(col.name(), col.type(), col.nullable(), col.defaultExpr(),
                                     col.generatedExpr(),
                                     Option.present(new Column.IdentitySpec(Column.IdentityKind.ALWAYS)),
                                     col.comment());
        var table = Table.table("users", "public", List.of(identityCol), List.of());
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<is_identity>YES</is_identity>");
        assertThat(xml).contains("<identity_generation>ALWAYS</identity_generation>");
    }

    @Test
    void generated_column() {
        var col = new Column("total", new BuiltinType("numeric", TypeCategory.NUMERIC),
                             false, Option.empty(),
                             Option.present("qty * price"),
                             Option.empty(), Option.empty());
        var table = Table.table("line_items", "public", List.of(col), List.of());
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<is_generated>ALWAYS</is_generated>");
        assertThat(xml).contains("<generation_expression>qty * price</generation_expression>");
    }

    @Test
    void multi_schema_support() {
        var table1 = Table.table("users", "auth", List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)), List.of());
        var table2 = Table.table("products", "catalog", List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)), List.of());
        var schema = Schema.empty().withSchema("auth").withSchema("catalog").withTable(table1).withTable(table2);
        var multiConfig = config.withIncludedSchemas(Set.of("auth", "catalog"));
        var result = JooqXmlExporter.toXml(schema, multiConfig);
        var xml = result.unwrap();
        assertThat(xml).contains("<schema_name>auth</schema_name>");
        assertThat(xml).contains("<schema_name>catalog</schema_name>");
        assertThat(xml).contains("<table_schema>auth</table_schema>");
        assertThat(xml).contains("<table_schema>catalog</table_schema>");
    }

    @Test
    void schema_filtering_excludes_non_included() {
        var table1 = Table.table("users", "public", List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)), List.of());
        var table2 = Table.table("internal", "pg_catalog", List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)), List.of());
        var schema = Schema.empty().withSchema("public").withSchema("pg_catalog").withTable(table1).withTable(table2);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<table_name>users</table_name>");
        assertThat(xml).doesNotContain("<table_name>internal</table_name>");
    }

    @Test
    void unnamed_constraint_gets_synthetic_name() {
        var pk = new PrimaryKey(Option.empty(), List.of("id"));
        var table = Table.table("users", "public",
                                List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)),
                                List.of(pk));
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<constraint_type>PRIMARY KEY</constraint_type>");
        assertThat(xml).contains("users_constraint_");
    }

    @Test
    void unique_constraint() {
        var unique = new Unique(Option.present("users_email_key"), List.of("email"));
        var table = Table.table("users", "public",
                                List.of(Column.column("email", new BuiltinType("text", TypeCategory.STRING), false)),
                                List.of(unique));
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<constraint_name>users_email_key</constraint_name>");
        assertThat(xml).contains("<constraint_type>UNIQUE</constraint_type>");
    }

    @Test
    void column_with_default_expression() {
        var col = Column.column("created_at", new BuiltinType("timestamptz", TypeCategory.DATETIME), false)
                        .withDefault("now()");
        var table = Table.table("events", "public", List.of(col), List.of());
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<column_default>now()</column_default>");
    }

    @Test
    void comment_emitted_when_enabled() {
        var table = Table.table("users", "public",
                                List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)),
                                List.of()).withComment("User accounts");
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<comment>User accounts</comment>");
    }

    @Test
    void domain_type_emitted_as_user_defined() {
        var domain = new PgType.DomainType("email", "public",
                                           new BuiltinType("text", TypeCategory.STRING),
                                           Option.present("VALUE ~ '^.+@.+$'"));
        var table = Table.table("users", "public",
                                List.of(Column.column("email", domain, false)),
                                List.of());
        var schema = Schema.empty().withSchema("public").withTable(table).withDomainType(domain);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).contains("<data_type>USER-DEFINED</data_type>");
        assertThat(xml).contains("<udt_name>email</udt_name>");
    }

    @Test
    void exclusion_constraint_skipped() {
        var exclusion = new Exclusion(Option.present("no_overlap"), "gist", "tsrange USING &&");
        var table = Table.table("reservations", "public",
                                List.of(Column.column("id", new BuiltinType("int8", TypeCategory.NUMERIC), false)),
                                List.of(exclusion));
        var schema = Schema.empty().withSchema("public").withTable(table);
        var result = JooqXmlExporter.toXml(schema, config);
        var xml = result.unwrap();
        assertThat(xml).doesNotContain("no_overlap");
    }
}
