package org.pragmatica.aether.pg.codegen.processor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.pg.codegen.processor.MethodNameParser.*;

class MethodNameParserTest {

    @Nested
    class SimpleFinds {
        @Test
        void findById_singleColumn_equalsOperator() {
            var result = parse("findById");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.FIND);
            assertThat(result.conditions()).hasSize(1);
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("id");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.EQUALS);
        }

        @Test
        void findByStatus_singleColumn_equalsOperator() {
            var result = parse("findByStatus");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.FIND);
            assertThat(result.conditions()).hasSize(1);
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("status");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.EQUALS);
        }

        @Test
        void findAll_noConditions() {
            var result = parse("findAll");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.FIND_ALL);
            assertThat(result.conditions()).isEmpty();
            assertThat(result.orderBy()).isEmpty();
        }
    }

    @Nested
    class CompoundConditions {
        @Test
        void findByStatusAndCreatedAtAfter_twoColumns() {
            var result = parse("findByStatusAndCreatedAtAfter");
            assertThat(result).isNotNull();
            assertThat(result.conditions()).hasSize(2);
            assertThat(result.conditions().get(0).columnName()).isEqualTo("status");
            assertThat(result.conditions().get(0).operator()).isEqualTo(Operator.EQUALS);
            assertThat(result.conditions().get(1).columnName()).isEqualTo("created_at");
            assertThat(result.conditions().get(1).operator()).isEqualTo(Operator.GREATER_THAN);
        }

        @Test
        void findByNameAndEmailAndActive_threeColumns() {
            var result = parse("findByNameAndEmailAndActive");
            assertThat(result).isNotNull();
            assertThat(result.conditions()).hasSize(3);
            assertThat(result.conditions().get(0).columnName()).isEqualTo("name");
            assertThat(result.conditions().get(1).columnName()).isEqualTo("email");
            assertThat(result.conditions().get(2).columnName()).isEqualTo("active");
        }
    }

    @Nested
    class Operators {
        @Test
        void findByAgeGreaterThan_greaterThanOperator() {
            var result = parse("findByAgeGreaterThan");
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("age");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.GREATER_THAN);
        }

        @Test
        void findByAgeLessThan_lessThanOperator() {
            var result = parse("findByAgeLessThan");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.LESS_THAN);
        }

        @Test
        void findByCreatedAtBefore_lessThanViaAlias() {
            var result = parse("findByCreatedAtBefore");
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("created_at");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.LESS_THAN);
        }

        @Test
        void findByNameLike_likeOperator() {
            var result = parse("findByNameLike");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.LIKE);
        }

        @Test
        void findByStatusIn_inOperator() {
            var result = parse("findByStatusIn");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.IN);
        }

        @Test
        void findByEmailIsNull_isNullOperator() {
            var result = parse("findByEmailIsNull");
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("email");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.IS_NULL);
        }

        @Test
        void findByEmailIsNotNull_isNotNullOperator() {
            var result = parse("findByEmailIsNotNull");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.IS_NOT_NULL);
        }

        @Test
        void findByAgeBetween_betweenOperator() {
            var result = parse("findByAgeBetween");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.BETWEEN);
            assertThat(result.conditions().getFirst().operator().paramCount()).isEqualTo(2);
        }

        @Test
        void findByStatusNot_notEqualsOperator() {
            var result = parse("findByStatusNot");
            assertThat(result.conditions().getFirst().operator()).isEqualTo(Operator.NOT_EQUALS);
        }
    }

    @Nested
    class OrderByTests {
        @Test
        void findByActiveOrderByNameAsc_singleOrderBy() {
            var result = parse("findByActiveOrderByNameAsc");
            assertThat(result.conditions()).hasSize(1);
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("active");
            assertThat(result.orderBy()).hasSize(1);
            assertThat(result.orderBy().getFirst().columnName()).isEqualTo("name");
            assertThat(result.orderBy().getFirst().direction()).isEqualTo(SortDirection.ASC);
        }

        @Test
        void findByActiveOrderByCreatedAtDesc_descending() {
            var result = parse("findByActiveOrderByCreatedAtDesc");
            assertThat(result.orderBy()).hasSize(1);
            assertThat(result.orderBy().getFirst().columnName()).isEqualTo("created_at");
            assertThat(result.orderBy().getFirst().direction()).isEqualTo(SortDirection.DESC);
        }

        @Test
        void findByActiveOrderByNameAscCreatedAtDesc_multipleOrderBy() {
            var result = parse("findByActiveOrderByNameAscCreatedAtDesc");
            assertThat(result.orderBy()).hasSize(2);
            assertThat(result.orderBy().get(0).columnName()).isEqualTo("name");
            assertThat(result.orderBy().get(0).direction()).isEqualTo(SortDirection.ASC);
            assertThat(result.orderBy().get(1).columnName()).isEqualTo("created_at");
            assertThat(result.orderBy().get(1).direction()).isEqualTo(SortDirection.DESC);
        }
    }

    @Nested
    class OtherOperations {
        @Test
        void save_saveOperation() {
            var result = parse("save");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.SAVE);
            assertThat(result.conditions()).isEmpty();
        }

        @Test
        void insert_insertOperation() {
            var result = parse("insert");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.INSERT);
        }

        @Test
        void deleteById_deleteOperation() {
            var result = parse("deleteById");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.DELETE);
            assertThat(result.conditions()).hasSize(1);
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("id");
        }

        @Test
        void countByStatus_countOperation() {
            var result = parse("countByStatus");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.COUNT);
            assertThat(result.conditions()).hasSize(1);
        }

        @Test
        void existsById_existsOperation() {
            var result = parse("existsById");
            assertThat(result).isNotNull();
            assertThat(result.operation()).isEqualTo(Operation.EXISTS);
            assertThat(result.conditions()).hasSize(1);
            assertThat(result.conditions().getFirst().columnName()).isEqualTo("id");
        }
    }

    @Nested
    class UnrecognizedPatterns {
        @Test
        void unknownPrefix_returnsNull() {
            var result = parse("updateById");
            assertThat(result).isNull();
        }
    }
}
