package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;

class SliceRuntimeErrorTest {

    @Nested
    class InvokerNotConfiguredTests {

        @Test
        void message_containsNotConfiguredDescription() {
            var error = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            assertThat(error.message()).contains("SliceInvoker not configured");
        }

        @Test
        void message_containsUsageHint() {
            var error = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            assertThat(error.message()).contains("outside of the Aether runtime");
        }

        @Test
        void instance_isSingleton() {
            var first = SliceRuntimeError.InvokerNotConfigured.INSTANCE;
            var second = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            assertThat(first).isSameAs(second);
        }

        @Test
        void instance_implementsSliceRuntimeError() {
            var error = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            assertThat(error).isInstanceOf(SliceRuntimeError.class);
        }

        @Test
        void instance_implementsCause() {
            var error = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            assertThat(error).isInstanceOf(Cause.class);
        }

        @Test
        void result_createsFailureResult() {
            var error = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            var result = error.<String>result();

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void promise_createsFailurePromise() {
            var error = SliceRuntimeError.InvokerNotConfigured.INSTANCE;

            var promise = error.<String>promise();
            var result = promise.await();

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class SealedInterfaceTests {

        @Test
        void sealedInterface_invokerNotConfiguredIsPermittedSubtype() {
            assertThat(SliceRuntimeError.class.isSealed()).isTrue();
            assertThat(SliceRuntimeError.class.getPermittedSubclasses())
                .contains(SliceRuntimeError.InvokerNotConfigured.class);
        }
    }
}
