package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationContextPrincipalTest {

    @Test
    void currentPrincipal_returnsEmpty_whenNotInContext() {
        assertThat(InvocationContext.currentPrincipal().isEmpty()).isTrue();
    }

    @Test
    void currentOriginNode_returnsEmpty_whenNotInContext() {
        assertThat(InvocationContext.currentOriginNode().isEmpty()).isTrue();
    }

    @Test
    void runWithContext_setsAllThreeValues() {
        var result = InvocationContext.runWithContext(
            "req-123", "api-key:admin-svc", "node-0", 0, false,
            () -> {
                var reqId = InvocationContext.currentRequestId();
                var principal = InvocationContext.currentPrincipal();
                var origin = InvocationContext.currentOriginNode();
                return new String[]{
                    reqId.or(""),
                    principal.or(""),
                    origin.or("")
                };
            }
        );

        assertThat(result[0]).isEqualTo("req-123");
        assertThat(result[1]).isEqualTo("api-key:admin-svc");
        assertThat(result[2]).isEqualTo("node-0");
    }

    @Test
    void runWithContext_nullPrincipalAndOriginNode_doesNotFail() {
        var result = InvocationContext.runWithContext(
            "req-456", null, null, 0, false,
            () -> {
                var principal = InvocationContext.currentPrincipal();
                var origin = InvocationContext.currentOriginNode();
                return new boolean[]{principal.isEmpty(), origin.isEmpty()};
            }
        );

        assertThat(result[0]).isTrue();
        assertThat(result[1]).isTrue();
    }

    @Test
    void captureContext_capturesAllThreeValues() {
        var snapshot = InvocationContext.runWithContext(
            "req-789", "user:admin", "node-2", 0, false,
            InvocationContext::captureContext
        );

        assertThat(snapshot.requestId()).isEqualTo("req-789");
        assertThat(snapshot.principal()).isEqualTo("user:admin");
        assertThat(snapshot.originNode()).isEqualTo("node-2");
    }

    @Test
    void contextSnapshot_runWithCaptured_restoresAllThreeValues() {
        var snapshot = InvocationContext.runWithContext(
            "req-aaa", "service:gateway", "node-1", 0, false,
            InvocationContext::captureContext
        );

        var result = snapshot.runWithCaptured(() -> {
            var reqId = InvocationContext.currentRequestId();
            var principal = InvocationContext.currentPrincipal();
            var origin = InvocationContext.currentOriginNode();
            return new String[]{
                reqId.or(""),
                principal.or(""),
                origin.or("")
            };
        });

        assertThat(result[0]).isEqualTo("req-aaa");
        assertThat(result[1]).isEqualTo("service:gateway");
        assertThat(result[2]).isEqualTo("node-1");
    }

    @Test
    void runWithRequestId_principalAndOriginNodeRemainEmpty() {
        var result = InvocationContext.runWithRequestId(
            "req-old",
            () -> {
                var principal = InvocationContext.currentPrincipal();
                var origin = InvocationContext.currentOriginNode();
                return new boolean[]{principal.isEmpty(), origin.isEmpty()};
            }
        );

        assertThat(result[0]).isTrue();
        assertThat(result[1]).isTrue();
    }

    @Test
    void captureContext_outsideScope_returnsNullFields() {
        var snapshot = InvocationContext.captureContext();

        assertThat(snapshot.requestId()).isNull();
        assertThat(snapshot.principal()).isNull();
        assertThat(snapshot.originNode()).isNull();
    }

    @Test
    void contextSnapshot_runWithCaptured_nullRequestId_executesDirectly() {
        var snapshot = new InvocationContext.ContextSnapshot(null, null, null, 0, false);

        var result = snapshot.runWithCaptured(() -> "executed");

        assertThat(result).isEqualTo("executed");
    }

    @Test
    void runWithContext_runnableOverload_setsValues() {
        var holder = new String[3];

        InvocationContext.runWithContext(
            "req-run", "api-key:svc", "node-5", 0, false,
            () -> {
                holder[0] = InvocationContext.currentRequestId().or("");
                holder[1] = InvocationContext.currentPrincipal().or("");
                holder[2] = InvocationContext.currentOriginNode().or("");
            }
        );

        assertThat(holder[0]).isEqualTo("req-run");
        assertThat(holder[1]).isEqualTo("api-key:svc");
        assertThat(holder[2]).isEqualTo("node-5");
    }

    @Test
    void contextSnapshot_runWithCaptured_runnableOverload_restoresValues() {
        var snapshot = InvocationContext.runWithContext(
            "req-bbb", "user:test", "node-3", 0, false,
            InvocationContext::captureContext
        );

        var holder = new String[3];

        snapshot.runWithCaptured(() -> {
            holder[0] = InvocationContext.currentRequestId().or("");
            holder[1] = InvocationContext.currentPrincipal().or("");
            holder[2] = InvocationContext.currentOriginNode().or("");
        });

        assertThat(holder[0]).isEqualTo("req-bbb");
        assertThat(holder[1]).isEqualTo("user:test");
        assertThat(holder[2]).isEqualTo("node-3");
    }
}
