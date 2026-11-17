package conduit.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

/// Unwrapping helpers for tests: a value where success is expected, the cause where failure is expected.
public final class Expect {
    private Expect() {}

    public static <T> T success(Result<T> result) {
        return result.fold(cause -> {
                               throw new AssertionError("unexpected failure: " + cause.message());
                           },
                           value -> value);
    }

    public static Cause failure(Result<?> result) {
        return result.fold(cause -> cause,
                           value -> {
                               throw new AssertionError("expected a failure, got " + value);
                           });
    }

    public static <T> T success(Promise<T> promise) {
        return success(promise.await());
    }

    public static Cause failure(Promise<?> promise) {
        return failure(promise.await());
    }
}
