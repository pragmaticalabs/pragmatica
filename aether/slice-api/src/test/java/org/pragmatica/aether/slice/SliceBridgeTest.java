package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SliceBridgeTest {

    @Nested
    class ContractTests {

        @Test
        void invoke_delegatesToImplementation() {
            var input = new byte[]{1, 2, 3};
            var expected = new byte[]{4, 5, 6};
            SliceBridge bridge = stubBridge(expected);

            var result = bridge.invoke("testMethod", input).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(bytes -> assertThat(bytes).isEqualTo(expected));
        }

        @Test
        void start_returnsSuccessfulPromise() {
            SliceBridge bridge = stubBridge(new byte[0]);

            var result = bridge.start().await();

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void stop_returnsSuccessfulPromise() {
            SliceBridge bridge = stubBridge(new byte[0]);

            var result = bridge.stop().await();

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void methodNames_returnsConfiguredNames() {
            SliceBridge bridge = stubBridge(new byte[0]);

            var names = bridge.methodNames();

            assertThat(names).containsExactly("alpha", "beta");
        }
    }

    @Nested
    class InvokeTests {

        @Test
        void invoke_emptyInput_succeeds() {
            SliceBridge bridge = stubBridge(new byte[]{42});

            var result = bridge.invoke("someMethod", new byte[0]).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(bytes -> assertThat(bytes).containsExactly(42));
        }

        @Test
        void invoke_differentMethods_usesMethodName() {
            var calls = new java.util.ArrayList<String>();
            SliceBridge bridge = trackingBridge(calls);

            bridge.invoke("firstMethod", new byte[0]).await();
            bridge.invoke("secondMethod", new byte[0]).await();

            assertThat(calls).containsExactly("firstMethod", "secondMethod");
        }
    }

    @Nested
    class LifecycleTests {

        @Test
        void startThenStop_bothSucceed() {
            SliceBridge bridge = stubBridge(new byte[0]);

            var startResult = bridge.start().await();
            var stopResult = bridge.stop().await();

            assertThat(startResult.isSuccess()).isTrue();
            assertThat(stopResult.isSuccess()).isTrue();
        }
    }

    private static SliceBridge stubBridge(byte[] response) {
        return new SliceBridge() {
            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.success(response);
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public List<String> methodNames() {
                return List.of("alpha", "beta");
            }
        };
    }

    private static SliceBridge trackingBridge(List<String> calls) {
        return new SliceBridge() {
            @Override
            public Promise<byte[]> invoke(String methodName, byte[] input) {
                calls.add(methodName);
                return Promise.success(new byte[0]);
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public List<String> methodNames() {
                return List.of();
            }
        };
    }
}
