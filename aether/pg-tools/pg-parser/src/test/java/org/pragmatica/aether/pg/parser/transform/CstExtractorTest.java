package org.pragmatica.aether.pg.parser.transform;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.ast.common.Identifier;

import static org.assertj.core.api.Assertions.assertThat;

class CstExtractorTest {
    static final PostgresParser PARSER = PostgresParser.create();

    private CstNavigator parse(String sql) {
        var cst = PARSER.parseCst(sql).unwrap();
        return CstNavigator.of((CstNode.NonTerminal) cst);
    }

    private CstNavigator dataTypeFrom(String typeName) {
        var nav = parse("CREATE TABLE t (col " + typeName + ")");
        var types = nav.findAll("DataType");
        assertThat(types).as("DataType for: " + typeName).isNotEmpty();
        return types.getFirst();
    }

    @Nested
    class IdentifierExtraction {
        @Test void unquoted() {
            var nav = parse("CREATE TABLE users (id integer)");
            var id = CstExtractor.extractIdentifier(nav.findAll("ColId").getFirst());
            assertThat(id.normalized()).isEqualTo("users");
            assertThat(id.style()).isEqualTo(Identifier.QuoteStyle.UNQUOTED);
        }

        @Test void quoted() {
            var nav = parse("CREATE TABLE \"MyTable\" (id integer)");
            var qname = CstExtractor.extractQualifiedName(nav.findAll("QualifiedName").getFirst());
            assertThat(qname.name().value()).isEqualTo("MyTable");
            assertThat(qname.name().style()).isEqualTo(Identifier.QuoteStyle.DOUBLE_QUOTED);
            assertThat(qname.name().normalized()).isEqualTo("MyTable");
        }

        @Test void unquotedCaseFolded() {
            var nav = parse("CREATE TABLE MyTable (id integer)");
            var qname = CstExtractor.extractQualifiedName(nav.findAll("QualifiedName").getFirst());
            assertThat(qname.name().value()).isEqualTo("MyTable");
            assertThat(qname.name().normalized()).isEqualTo("mytable");
        }
    }

    @Nested
    class QualifiedNameExtraction {
        @Test void simpleName() {
            var nav = parse("CREATE TABLE users (id integer)");
            var qname = CstExtractor.extractQualifiedName(nav.findAll("QualifiedName").getFirst());
            assertThat(qname.parts()).hasSize(1);
            assertThat(qname.name().normalized()).isEqualTo("users");
            assertThat(qname.schema().isEmpty()).isTrue();
        }

        @Test void schemaQualified() {
            var nav = parse("CREATE TABLE public.users (id integer)");
            var qname = CstExtractor.extractQualifiedName(nav.findAll("QualifiedName").getFirst());
            assertThat(qname.parts()).hasSize(2);
            assertThat(qname.schema().unwrap().normalized()).isEqualTo("public");
            assertThat(qname.name().normalized()).isEqualTo("users");
            assertThat(qname.normalized()).isEqualTo("public.users");
        }
    }

    @Nested
    class DataTypeExtraction {
        @Test void integer() {
            var dt = CstExtractor.extractDataType(dataTypeFrom("integer"));
            assertThat(dt.normalized()).isEqualTo("integer");
            assertThat(dt.modifiers()).isEmpty();
            assertThat(dt.isArray()).isFalse();
        }

        @Test void bigint() { assertThat(CstExtractor.extractDataType(dataTypeFrom("bigint")).normalized()).isEqualTo("bigint"); }
        @Test void text() { assertThat(CstExtractor.extractDataType(dataTypeFrom("text")).normalized()).isEqualTo("text"); }

        @Test void varcharWithLength() {
            var dt = CstExtractor.extractDataType(dataTypeFrom("varchar(255)"));
            assertThat(dt.normalized()).isEqualTo("varchar");
            assertThat(dt.modifiers()).containsExactly(255);
        }

        @Test void numericPrecisionScale() {
            var dt = CstExtractor.extractDataType(dataTypeFrom("numeric(10,2)"));
            assertThat(dt.normalized()).isEqualTo("numeric");
            assertThat(dt.modifiers()).containsExactly(10, 2);
        }

        @Test void timestamptz() { assertThat(CstExtractor.extractDataType(dataTypeFrom("timestamptz")).normalized()).isEqualTo("timestamptz"); }

        @Test void timestampWithTimeZone() {
            assertThat(CstExtractor.extractDataType(dataTypeFrom("timestamp with time zone")).normalized()).contains("timestamp");
        }

        @Test void jsonb() { assertThat(CstExtractor.extractDataType(dataTypeFrom("jsonb")).normalized()).isEqualTo("jsonb"); }

        @Test void integerArray() {
            var dt = CstExtractor.extractDataType(dataTypeFrom("integer[]"));
            assertThat(dt.normalized()).isEqualTo("integer");
            assertThat(dt.isArray()).isTrue();
            assertThat(dt.arrayDimensions()).isEqualTo(1);
        }

        @Test void textMultiDimArray() {
            var dt = CstExtractor.extractDataType(dataTypeFrom("text[][]"));
            assertThat(dt.normalized()).isEqualTo("text");
            assertThat(dt.arrayDimensions()).isEqualTo(2);
        }

        @Test void serial() { assertThat(CstExtractor.extractDataType(dataTypeFrom("serial")).normalized()).isEqualTo("serial"); }
        @Test void bigserial() { assertThat(CstExtractor.extractDataType(dataTypeFrom("bigserial")).normalized()).isEqualTo("bigserial"); }
        @Test void uuid() { assertThat(CstExtractor.extractDataType(dataTypeFrom("uuid")).normalized()).isEqualTo("uuid"); }
        @Test void booleanType() { assertThat(CstExtractor.extractDataType(dataTypeFrom("boolean")).normalized()).isEqualTo("boolean"); }

        @Test void customType() {
            var dt = CstExtractor.extractDataType(dataTypeFrom("public.my_type"));
            assertThat(dt.customTypeName().isPresent()).isTrue();
            assertThat(dt.customTypeName().unwrap().normalized()).isEqualTo("public.my_type");
        }
    }

    @Nested
    class NavigatorColumnList {
        @Test void singleColumn() {
            var nav = parse("CREATE TABLE t (id integer, PRIMARY KEY (id))");
            var colLists = nav.findAll("ColumnList");
            assertThat(colLists).isNotEmpty();
            assertThat(CstExtractor.extractColumnList(colLists.getFirst())).hasSize(1);
        }

        @Test void multipleColumns() {
            var nav = parse("CREATE TABLE t (a integer, b text, c text, PRIMARY KEY (a, b, c))");
            var colLists = nav.findAll("ColumnList");
            var threeColList = colLists.stream()
                .filter(cl -> CstExtractor.extractColumnList(cl).size() == 3)
                .findFirst().orElseThrow();
            var cols = CstExtractor.extractColumnList(threeColList);
            assertThat(cols).hasSize(3);
            assertThat(cols.stream().map(Identifier::normalized).toList())
                .containsExactly("a", "b", "c");
        }
    }

    @Nested
    class CreateTableExtraction {
        @Test void extractTableName() {
            var nav = parse("CREATE TABLE public.users (id bigint)");
            var qname = nav.findAll("QualifiedName").getFirst();
            assertThat(CstExtractor.extractQualifiedName(qname).normalized()).isEqualTo("public.users");
        }

        @Test void extractColumns() {
            var nav = parse("CREATE TABLE t (id bigint NOT NULL, name text, active boolean DEFAULT true)");
            assertThat(nav.findAll("TableElement")).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test void detectIfNotExists() {
            assertThat(parse("CREATE TABLE IF NOT EXISTS t (id integer)")
                .findAll("CreateTableStmt").getFirst().has("IfNotExists")).isTrue();
        }

        @Test void detectPrimaryKey() {
            var constraints = parse("CREATE TABLE t (id bigint, PRIMARY KEY (id))").findAll("TableConstraintElem");
            assertThat(constraints.stream().anyMatch(c -> c.has("PrimaryKW"))).isTrue();
        }

        @Test void detectForeignKey() {
            var constraints = parse("CREATE TABLE o (id bigint, uid bigint, FOREIGN KEY (uid) REFERENCES u(id) ON DELETE CASCADE)")
                .findAll("TableConstraintElem");
            assertThat(constraints.stream().anyMatch(c -> c.has("ForeignKW"))).isTrue();
        }
    }
}
