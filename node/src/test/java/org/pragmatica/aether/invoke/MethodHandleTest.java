package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodHandle;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

class MethodHandleTest {

    private StubSliceInvoker stubInvoker;

    @BeforeEach
    void setUp() {
        stubInvoker = new StubSliceInvoker();
    }

    @Nested
    class Creation {
        @Test
        void methodHandle_succeeds_withValidArtifactAndMethod() {
            stubInvoker.methodHandle("org.example:test-slice:1.0.0",
                                     "processRequest",
                                     String.class,
                                     String.class)
                       .onFailureRun(Assertions::fail)
                       .onSuccess(handle -> {
                           assertThat(handle.artifactCoordinate()).isEqualTo("org.example:test-slice:1.0.0");
                           assertThat(handle.methodName().name()).isEqualTo("processRequest");
                       });
        }

        @Test
        void methodHandle_fails_withInvalidArtifact() {
            stubInvoker.methodHandle("invalid-artifact",
                                     "processRequest",
                                     String.class,
                                     String.class)
                       .onSuccessRun(Assertions::fail)
                       .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
        }

        @Test
        void methodHandle_fails_withInvalidMethodName() {
            stubInvoker.methodHandle("org.example:test-slice:1.0.0",
                                     "InvalidMethod",  // Uppercase first letter
                                     String.class,
                                     String.class)
                       .onSuccessRun(Assertions::fail);
        }

        @Test
        void methodHandle_fails_withEmptyMethodName() {
            stubInvoker.methodHandle("org.example:test-slice:1.0.0",
                                     "",
                                     String.class,
                                     String.class)
                       .onSuccessRun(Assertions::fail);
        }
    }

    @Nested
    class Invocation {
        @Test
        void invoke_delegatesToTypedMethod() {
            var handle = stubInvoker.methodHandle("org.example:test-slice:1.0.0",
                                                  "processRequest",
                                                  String.class,
                                                  String.class)
                                    .unwrap();

            handle.invoke("test-input")
                  .await()
                  .onFailureRun(Assertions::fail)
                  .onSuccess(response -> assertThat(response).isEqualTo("response:test-input"));

            // Verify the typed invoke method was called
            assertThat(stubInvoker.lastArtifact).isNotNull();
            assertThat(stubInvoker.lastArtifact.asString()).isEqualTo("org.example:test-slice:1.0.0");
            assertThat(stubInvoker.lastMethodName.name()).isEqualTo("processRequest");
            assertThat(stubInvoker.lastRequest).isEqualTo("test-input");
        }

        @Test
        void fireAndForget_delegatesToTypedMethod() {
            var handle = stubInvoker.methodHandle("org.example:test-slice:1.0.0",
                                                  "processRequest",
                                                  String.class,
                                                  String.class)
                                    .unwrap();

            handle.fireAndForget("test-input")
                  .await()
                  .onFailureRun(Assertions::fail);

            // Verify the fire-and-forget method was called
            assertThat(stubInvoker.lastArtifact).isNotNull();
            assertThat(stubInvoker.fireAndForgetCalled).isTrue();
        }

        @Test
        void multipleInvocations_reusesSameParsedValues() {
            var handle = stubInvoker.methodHandle("org.example:test-slice:1.0.0",
                                                  "processRequest",
                                                  String.class,
                                                  String.class)
                                    .unwrap();

            // First invocation
            handle.invoke("input1").await();
            var firstArtifact = stubInvoker.lastArtifact;

            // Second invocation
            handle.invoke("input2").await();
            var secondArtifact = stubInvoker.lastArtifact;

            // Should be the same object (not re-parsed)
            assertThat(firstArtifact).isSameAs(secondArtifact);
        }
    }

    @Nested
    class MethodHandleImpl {
        @Test
        void artifactCoordinate_returnsArtifactString() {
            var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
            var methodName = MethodName.methodName("testMethod").unwrap();

            var handle = new SliceInvoker.MethodHandleImpl<>(artifact,
                                                              methodName,
                                                              String.class,
                                                              String.class,
                                                              stubInvoker);

            assertThat(handle.artifactCoordinate()).isEqualTo("org.example:test-slice:1.0.0");
        }

        @Test
        void methodName_returnsMethodName() {
            var artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
            var methodName = MethodName.methodName("testMethod").unwrap();

            var handle = new SliceInvoker.MethodHandleImpl<>(artifact,
                                                              methodName,
                                                              String.class,
                                                              String.class,
                                                              stubInvoker);

            assertThat(handle.methodName()).isEqualTo(methodName);
        }
    }

    /**
     * Stub implementation of SliceInvoker for testing MethodHandle.
     */
    static class StubSliceInvoker implements SliceInvoker {
        Artifact lastArtifact;
        MethodName lastMethodName;
        Object lastRequest;
        boolean fireAndForgetCalled = false;

        @Override
        public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
            this.lastArtifact = slice;
            this.lastMethodName = method;
            this.lastRequest = request;
            this.fireAndForgetCalled = true;
            return Promise.success(unit());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, Class<R> responseType) {
            this.lastArtifact = slice;
            this.lastMethodName = method;
            this.lastRequest = request;
            return Promise.success((R) ("response:" + request));
        }

        @Override
        public <R> Promise<R> invokeWithRetry(Artifact slice,
                                              MethodName method,
                                              Object request,
                                              Class<R> responseType,
                                              int maxRetries) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, Class<R> responseType) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public void onInvokeResponse(InvocationMessage.InvokeResponse response) {}

        @Override
        public Promise<Unit> stop() {
            return Promise.success(unit());
        }

        @Override
        public int pendingCount() {
            return 0;
        }

        @Override
        public Unit setFailureListener(SliceFailureListener listener) {
            return unit();
        }

        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                               String methodName,
                                                               Class<T> requestType,
                                                               Class<R> responseType) {
            // Validate artifact
            var artifactResult = Artifact.artifact(sliceArtifact);
            if (artifactResult.isFailure()) {
                return artifactResult.map(_ -> null);
            }
            // Validate method name
            var methodNameResult = MethodName.methodName(methodName);
            if (methodNameResult.isFailure()) {
                return methodNameResult.map(_ -> null);
            }
            // Return a stub method handle
            return Result.success(new StubMethodHandle<>(artifactResult.unwrap(),
                                                         methodNameResult.unwrap(),
                                                         responseType,
                                                         this));
        }

        private record StubMethodHandle<R, T>(Artifact artifact,
                                              MethodName method,
                                              Class<R> responseType,
                                              StubSliceInvoker invoker) implements MethodHandle<R, T> {
            @Override
            public Promise<R> invoke(T request) {
                invoker.lastArtifact = artifact;
                invoker.lastMethodName = method;
                invoker.lastRequest = request;
                return invoker.invoke(artifact, method, request, responseType);
            }

            @Override
            public Promise<Unit> fireAndForget(T request) {
                invoker.lastArtifact = artifact;
                invoker.lastMethodName = method;
                invoker.lastRequest = request;
                invoker.fireAndForgetCalled = true;
                return invoker.invoke(artifact, method, request);
            }

            @Override
            public String artifactCoordinate() {
                return artifact.asString();
            }

            @Override
            public MethodName methodName() {
                return method;
            }
        }
    }
}
