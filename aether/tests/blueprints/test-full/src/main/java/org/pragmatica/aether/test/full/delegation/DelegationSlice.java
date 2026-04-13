package org.pragmatica.aether.test.full.delegation;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


@Slice public interface DelegationSlice {
    record TaskResponse(String taskGroup, String nodeId) {
        public static TaskResponse taskResponse() {
            var nodeId = System.getProperty("NODE_ID", System.getenv().getOrDefault("NODE_ID", "unknown"));
            return new TaskResponse("default", nodeId);
        }
    }

    record WorkRequest(String payload) {
        private static final Fn1<Cause, String> PAYLOAD_REQUIRED = Causes.forOneValue("Payload is required, got: '%s'");

        public static Result<WorkRequest> workRequest(String payload) {
            return Verify.ensure(payload, Verify.Is::notNull, PAYLOAD_REQUIRED.apply(payload))
                         .filter(p -> PAYLOAD_REQUIRED.apply(p), Verify.Is::notBlank)
                         .map(String::trim)
                         .map(WorkRequest::new);
        }
    }

    record WorkResponse(String status, String nodeId) {
        public static WorkResponse workResponse() {
            var nodeId = System.getProperty("NODE_ID", System.getenv().getOrDefault("NODE_ID", "unknown"));
            return new WorkResponse("accepted", nodeId);
        }
    }

    Promise<TaskResponse> task();
    Promise<WorkResponse> work(WorkRequest request);

    static DelegationSlice delegationSlice() {
        return new delegationSlice();
    }

    record delegationSlice() implements DelegationSlice {
        @Override public Promise<TaskResponse> task() {
            return Promise.success(TaskResponse.taskResponse());
        }

        @Override public Promise<WorkResponse> work(WorkRequest request) {
            return Promise.success(WorkResponse.workResponse());
        }
    }
}
