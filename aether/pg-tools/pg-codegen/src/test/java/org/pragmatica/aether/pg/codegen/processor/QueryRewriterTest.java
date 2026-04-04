package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.pg.codegen.processor.QueryRewriter.*;

class QueryRewriterTest {

    @Nested
    class NamedParamRewriting {
        @Test
        void singleParam_rewritesToPositional() {
            var result = rewriteNamedParams(
                "SELECT * FROM users WHERE email = :email",
                List.of("email")
            );
            assertThat(result.sql()).isEqualTo("SELECT * FROM users WHERE email = $1");
            assertThat(result.parameterOrder()).containsExactly("email");
        }

        @Test
        void twoParams_rewriteInOrder() {
            var result = rewriteNamedParams(
                "SELECT * FROM users WHERE email = :email AND active = :active",
                List.of("email", "active")
            );
            assertThat(result.sql()).isEqualTo("SELECT * FROM users WHERE email = $1 AND active = $2");
            assertThat(result.parameterOrder()).containsExactly("email", "active");
        }

        @Test
        void sameParamTwice_samePositionalNumber() {
            var result = rewriteNamedParams(
                "SELECT * FROM users WHERE id = :id OR manager_id = :id",
                List.of("id")
            );
            assertThat(result.sql()).isEqualTo("SELECT * FROM users WHERE id = $1 OR manager_id = $1");
            assertThat(result.parameterOrder()).containsExactly("id");
        }

        @Test
        void noParams_unchangedSql() {
            var result = rewriteNamedParams(
                "SELECT * FROM users",
                List.of()
            );
            assertThat(result.sql()).isEqualTo("SELECT * FROM users");
            assertThat(result.parameterOrder()).isEmpty();
        }

        @Test
        void threeParamsWithReuse_correctPositioning() {
            var result = rewriteNamedParams(
                "SELECT * FROM orders WHERE user_id = :userId AND status = :status AND created_at > :createdAfter",
                List.of("userId", "status", "createdAfter")
            );
            assertThat(result.sql()).isEqualTo(
                "SELECT * FROM orders WHERE user_id = $1 AND status = $2 AND created_at > $3"
            );
            assertThat(result.parameterOrder()).containsExactly("userId", "status", "createdAfter");
        }
    }

    @Nested
    class ExtractNamedParams {
        @Test
        void extractsUniqueParams() {
            var params = QueryRewriter.extractNamedParams(
                "SELECT * FROM users WHERE id = :id OR manager_id = :id AND status = :status"
            );
            assertThat(params).containsExactly("id", "status");
        }

        @Test
        void emptyForNoParams() {
            var params = QueryRewriter.extractNamedParams("SELECT * FROM users");
            assertThat(params).isEmpty();
        }
    }

    @Nested
    class InsertRecordExpansion {
        @Test
        void expandsValuesWithRecord() {
            var fields = List.of(
                new RecordField("name", "name"),
                new RecordField("email", "email"),
                new RecordField("active", "active")
            );
            var result = expandInsertRecord(
                "INSERT INTO users VALUES(:request)",
                "request",
                fields,
                Map.of()
            );
            assertThat(result.sql()).isEqualTo(
                "INSERT INTO users (name, email, active) VALUES ($1, $2, $3)"
            );
            assertThat(result.parameterOrder()).containsExactly("name", "email", "active");
        }

        @Test
        void expandsWithExistingPositions() {
            var fields = List.of(
                new RecordField("name", "name"),
                new RecordField("email", "email")
            );
            var result = expandInsertRecord(
                "INSERT INTO users VALUES(:record)",
                "record",
                fields,
                Map.of("tenantId", 1)
            );
            assertThat(result.sql()).isEqualTo(
                "INSERT INTO users (name, email) VALUES ($2, $3)"
            );
        }

        @Test
        void noMatchReturnsUnchanged() {
            var result = expandInsertRecord(
                "INSERT INTO users (name) VALUES ($1)",
                "request",
                List.of(),
                Map.of()
            );
            assertThat(result.sql()).isEqualTo("INSERT INTO users (name) VALUES ($1)");
        }
    }

    @Nested
    class UpdateRecordExpansion {
        @Test
        void expandsSetWithRecord() {
            var fields = List.of(
                new RecordField("name", "name"),
                new RecordField("email", "email")
            );
            var result = expandUpdateRecord(
                "UPDATE users SET :request WHERE id = :id",
                "request",
                fields,
                Map.of("id", 1)
            );
            assertThat(result.sql()).isEqualTo(
                "UPDATE users SET name = $2, email = $3 WHERE id = :id"
            );
            assertThat(result.parameterOrder()).containsExactly("name", "email");
        }
    }

    @Nested
    class QueryNarrowing {
        @Test
        void narrowsSelectStar() {
            var result = narrowSelect(
                "SELECT * FROM users WHERE active = true",
                List.of("id", "name")
            );
            assertThat(result).isEqualTo("SELECT id, name FROM users WHERE active = true");
        }

        @Test
        void narrowsAliasedStar() {
            var result = narrowSelect(
                "SELECT u.* FROM users u WHERE u.active = true",
                List.of("id", "name")
            );
            assertThat(result).isEqualTo("SELECT u.id, u.name FROM users u WHERE u.active = true");
        }

        @Test
        void noStarLeavesUnchanged() {
            var sql = "SELECT id, name FROM users";
            var result = narrowSelect(sql, List.of("id", "name"));
            assertThat(result).isEqualTo(sql);
        }
    }

    @Nested
    class FieldConversion {
        @Test
        void convertsFieldNamesToRecordFields() {
            var fields = fieldsToRecordFields(List.of("firstName", "lastName", "createdAt"));
            assertThat(fields).hasSize(3);
            assertThat(fields.get(0).fieldName()).isEqualTo("firstName");
            assertThat(fields.get(0).columnName()).isEqualTo("first_name");
            assertThat(fields.get(2).columnName()).isEqualTo("created_at");
        }
    }
}
