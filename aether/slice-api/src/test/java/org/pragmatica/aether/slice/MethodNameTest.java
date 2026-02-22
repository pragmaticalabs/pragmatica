package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MethodNameTest {

    @Nested
    class ValidationTests {

        @Test
        void methodName_validCamelCase_returnsSuccess() {
            var result = MethodName.methodName("processOrder");

            assertThat(result.isSuccess()).isTrue();
            result.onFailure(_ -> assertThat(true).isFalse())
                  .onSuccess(mn -> assertThat(mn.name()).isEqualTo("processOrder"));
        }

        @Test
        void methodName_validSimpleName_returnsSuccess() {
            var result = MethodName.methodName("handle42");

            assertThat(result.isSuccess()).isTrue();
            result.onFailure(_ -> assertThat(true).isFalse())
                  .onSuccess(mn -> assertThat(mn.name()).isEqualTo("handle42"));
        }

        @Test
        void methodName_validWithNumbers_returnsSuccess() {
            var result = MethodName.methodName("getUser123");

            assertThat(result.isSuccess()).isTrue();
            result.onFailure(_ -> assertThat(true).isFalse())
                  .onSuccess(mn -> assertThat(mn.name()).isEqualTo("getUser123"));
        }

        @Test
        void methodName_startsWithUpperCase_returnsFailure() {
            var result = MethodName.methodName("ProcessOrder");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_containsUnderscore_returnsFailure() {
            var result = MethodName.methodName("process_order");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_containsHyphen_returnsFailure() {
            var result = MethodName.methodName("process-order");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_emptyString_returnsFailure() {
            var result = MethodName.methodName("");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_singleLetter_returnsFailure() {
            var result = MethodName.methodName("a");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_startsWithDigit_returnsFailure() {
            var result = MethodName.methodName("1method");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_containsSpaces_returnsFailure() {
            var result = MethodName.methodName("process order");

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void methodName_containsDot_returnsFailure() {
            var result = MethodName.methodName("process.order");

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class EqualityTests {

        @Test
        void equals_sameValue_returnsTrue() {
            var first = MethodName.methodName("processOrder").unwrap();
            var second = MethodName.methodName("processOrder").unwrap();

            assertThat(first).isEqualTo(second);
        }

        @Test
        void equals_differentValue_returnsFalse() {
            var first = MethodName.methodName("processOrder").unwrap();
            var second = MethodName.methodName("handleRequest").unwrap();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void hashCode_sameValue_returnsSameHash() {
            var first = MethodName.methodName("processOrder").unwrap();
            var second = MethodName.methodName("processOrder").unwrap();

            assertThat(first.hashCode()).isEqualTo(second.hashCode());
        }
    }

    @Nested
    class ToStringTests {

        @Test
        void toString_returnsNameDirectly() {
            var methodName = MethodName.methodName("processOrder").unwrap();

            assertThat(methodName.toString()).isEqualTo("processOrder");
        }
    }
}
