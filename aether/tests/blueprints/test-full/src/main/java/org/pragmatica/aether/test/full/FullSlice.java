package org.pragmatica.aether.test.full;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.Map;

import static org.pragmatica.lang.Result.success;


@Slice public interface FullSlice {
    record StatusResponse(String sliceName, String version, long uptime) {
        private static final long START_TIME = System.currentTimeMillis();

        public static StatusResponse statusResponse() {
            return new StatusResponse("test-full", "1.0.0", System.currentTimeMillis() - START_TIME);
        }
    }

    record ConfigResponse(Map<String, String> values) {
        public static ConfigResponse configResponse(Map<String, String> values) {
            return new ConfigResponse(values);
        }
    }

    record InvokeRequest(String target) {
        private static final Fn1<Cause, String> TARGET_REQUIRED = Causes.forOneValue("Target is required, got: '%s'");

        public static Result<InvokeRequest> invokeRequest(String target) {
            return Verify.ensure(target, Verify.Is::notNull, TARGET_REQUIRED.apply(target))
                         .filter(t -> TARGET_REQUIRED.apply(t), Verify.Is::notBlank)
                         .map(String::trim)
                         .map(InvokeRequest::new);
        }
    }

    record InvokeResponse(String target, String result) {
        public static InvokeResponse invokeResponse(String target, String result) {
            return new InvokeResponse(target, result);
        }
    }

    Promise<StatusResponse> status();
    Promise<ConfigResponse> config();
    Promise<InvokeResponse> invoke(InvokeRequest request);

    static FullSlice fullSlice() {
        return new fullSlice();
    }

    record fullSlice() implements FullSlice {
        @Override public Promise<StatusResponse> status() {
            return Promise.success(StatusResponse.statusResponse());
        }

        @Override public Promise<ConfigResponse> config() {
            return Promise.success(ConfigResponse.configResponse(Map.of()));
        }

        @Override public Promise<InvokeResponse> invoke(InvokeRequest request) {
            return Promise.success(InvokeResponse.invokeResponse(request.target(), "invoked"));
        }
    }
}
