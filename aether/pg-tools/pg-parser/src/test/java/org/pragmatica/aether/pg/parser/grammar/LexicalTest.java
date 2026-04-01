package org.pragmatica.aether.pg.parser.grammar;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LexicalTest extends GrammarTestBase {

    @Nested
    class Identifiers {
        @Test void unquoted() { assertParsesRule("foo", "ColId"); }
        @Test void quoted() { assertParsesRule("\"MyTable\"", "ColId"); }
        @Test void quotedWithEscapedQuote() { assertParsesRule("\"my\"\"table\"", "ColId"); }
        @Test void unicodeIdentifier() { assertParsesRule("U&\"test\"", "ColId"); }
        @Test void underscore() { assertParsesRule("_foo_bar_123", "ColId"); }
        @Test void qualifiedName() { assertParsesRule("public.users", "QualifiedName"); }
        @Test void threePartName() { assertParsesRule("catalog.public.users", "QualifiedName"); }
    }

    @Nested
    class Literals {
        @Test void integer() { assertParsesRule("42", "NumericLiteral"); }
        @Test void decimal() { assertParsesRule("3.14", "NumericLiteral"); }
        @Test void scientific() { assertParsesRule("1.5e10", "NumericLiteral"); }
        @Test void hex() { assertParsesRule("0xFF", "NumericLiteral"); }
        @Test void basicString() { assertParsesRule("'hello'", "StringLiteral"); }
        @Test void escapedQuoteInString() { assertParsesRule("'it''s'", "StringLiteral"); }
        @Test void escapeString() { assertParsesRule("E'hello\\nworld'", "StringLiteral"); }
        @Test void dollarQuoted() { assertParsesRule("$$hello world$$", "StringLiteral"); }
        @Test void trueKeyword() { assertParsesRule("TRUE", "BooleanLiteral"); }
        @Test void falseKeyword() { assertParsesRule("false", "BooleanLiteral"); }
        @Test void nullLiteral() { assertParsesRule("NULL", "NullLiteral"); }
    }

    @Nested
    class DataTypes {
        @Test void integer() { assertParsesRule("integer", "DataType"); }
        @Test void bigint() { assertParsesRule("bigint", "DataType"); }
        @Test void smallint() { assertParsesRule("smallint", "DataType"); }
        @Test void int4() { assertParsesRule("int4", "DataType"); }
        @Test void text() { assertParsesRule("text", "DataType"); }
        @Test void varchar() { assertParsesRule("varchar", "DataType"); }
        @Test void varcharN() { assertParsesRule("varchar(255)", "DataType"); }
        @Test void charN() { assertParsesRule("char(10)", "DataType"); }
        @Test void characterVarying() { assertParsesRule("character varying", "DataType"); }
        @Test void characterVaryingN() { assertParsesRule("character varying(100)", "DataType"); }
        @Test void numericPrecision() { assertParsesRule("numeric(10,2)", "DataType"); }
        @Test void doublePrecision() { assertParsesRule("double precision", "DataType"); }
        @Test void real() { assertParsesRule("real", "DataType"); }
        @Test void boolType() { assertParsesRule("boolean", "DataType"); }
        @Test void date() { assertParsesRule("date", "DataType"); }
        @Test void timestamp() { assertParsesRule("timestamp", "DataType"); }
        @Test void timestampTz() { assertParsesRule("timestamptz", "DataType"); }
        @Test void timestampWithTz() { assertParsesRule("timestamp with time zone", "DataType"); }
        @Test void timestampWithoutTz() { assertParsesRule("timestamp without time zone", "DataType"); }
        @Test void timeTz() { assertParsesRule("timetz", "DataType"); }
        @Test void interval() { assertParsesRule("interval", "DataType"); }
        @Test void intervalDay() { assertParsesRule("interval day to second", "DataType"); }
        @Test void uuid() { assertParsesRule("uuid", "DataType"); }
        @Test void jsonb() { assertParsesRule("jsonb", "DataType"); }
        @Test void json() { assertParsesRule("json", "DataType"); }
        @Test void bytea() { assertParsesRule("bytea", "DataType"); }
        @Test void inet() { assertParsesRule("inet", "DataType"); }
        @Test void serial() { assertParsesRule("serial", "DataType"); }
        @Test void bigserial() { assertParsesRule("bigserial", "DataType"); }
        @Test void tsvector() { assertParsesRule("tsvector", "DataType"); }
        @Test void money() { assertParsesRule("money", "DataType"); }
        @Test void xml() { assertParsesRule("xml", "DataType"); }
        @Test void integerArray() { assertParsesRule("integer[]", "DataType"); }
        @Test void textArrayMultiDim() { assertParsesRule("text[][]", "DataType"); }
        @Test void varcharArray() { assertParsesRule("varchar(100)[]", "DataType"); }
        @Test void arrayKeyword() { assertParsesRule("integer ARRAY", "DataType"); }
        @Test void customType() { assertParsesRule("public.my_type", "DataType"); }
    }

    @Nested
    class Expressions {
        @Test void simpleAdd() { assertParsesRule("1 + 2", "Expr"); }
        @Test void multiplication() { assertParsesRule("a * b + c", "Expr"); }
        @Test void comparison() { assertParsesRule("x > 10", "Expr"); }
        @Test void andOr() { assertParsesRule("a = 1 AND b = 2 OR c = 3", "Expr"); }
        @Test void notExpr() { assertParsesRule("NOT active", "Expr"); }
        @Test void isNull() { assertParsesRule("x IS NULL", "Expr"); }
        @Test void isNotNull() { assertParsesRule("x IS NOT NULL", "Expr"); }
        @Test void inList() { assertParsesRule("x IN (1, 2, 3)", "Expr"); }
        @Test void between() { assertParsesRule("x BETWEEN 1 AND 10", "Expr"); }
        @Test void like() { assertParsesRule("name LIKE '%test%'", "Expr"); }
        @Test void ilike() { assertParsesRule("name ILIKE '%test%'", "Expr"); }
        @Test void typeCast() { assertParsesRule("'2024-01-01'::date", "Expr"); }
        @Test void castFunction() { assertParsesRule("CAST(x AS integer)", "Expr"); }
        @Test void caseWhen() { assertParsesRule("CASE WHEN x > 0 THEN 'pos' ELSE 'neg' END", "Expr"); }
        @Test void caseSimple() { assertParsesRule("CASE status WHEN 1 THEN 'active' WHEN 2 THEN 'inactive' END", "Expr"); }
        @Test void coalesce() { assertParsesRule("COALESCE(a, b, c)", "Expr"); }
        @Test void nullif() { assertParsesRule("NULLIF(a, 0)", "Expr"); }
        @Test void functionCall() { assertParsesRule("lower(name)", "Expr"); }
        @Test void aggregateDistinct() { assertParsesRule("count(DISTINCT status)", "Expr"); }
        @Test void aggregateStar() { assertParsesRule("count(*)", "Expr"); }
        @Test void nestedParens() { assertParsesRule("(a + b) * (c - d)", "Expr"); }
        @Test void arraySubscript() { assertParsesRule("tags[1]", "Expr"); }
        @Test void jsonArrow() { assertParsesRule("data->>'name'", "Expr"); }
        @Test void jsonDeep() { assertParsesRule("data->'address'->>'city'", "Expr"); }
        @Test void stringConcat() { assertParsesRule("a || b || c", "Expr"); }
        @Test void parameterRef() { assertParsesRule("$1", "Expr"); }
        @Test void existsSubquery() { assertParsesRule("EXISTS (SELECT 1 FROM users)", "Expr"); }
        @Test void greatest() { assertParsesRule("GREATEST(a, b, c)", "Expr"); }
        @Test void least() { assertParsesRule("LEAST(a, b, c)", "Expr"); }
        @Test void extract() { assertParsesRule("EXTRACT(year FROM created_at)", "Expr"); }
        @Test void arrayConstructor() { assertParsesRule("ARRAY[1, 2, 3]", "Expr"); }
        @Test void typedLiteral() { assertParsesRule("date '2024-01-01'", "Expr"); }
        @Test void isDistinctFrom() { assertParsesRule("a IS DISTINCT FROM b", "Expr"); }
    }

    @Nested
    class CaseInsensitivity {
        @Test void selectLowercase() { assertParses("select 1"); }
        @Test void selectUppercase() { assertParses("SELECT 1"); }
        @Test void selectMixedCase() { assertParses("SeLeCt 1"); }
        @Test void createTableMixed() {
            assertParses("Create Table test (id Integer Not Null)");
        }
    }
}
