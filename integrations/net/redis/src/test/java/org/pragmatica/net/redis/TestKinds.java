package org.pragmatica.net.redis;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/// Names the outcome of a result for assertions: the simple class name of the failure cause, or "success".
final class TestKinds {
    private TestKinds() {}

    static String kind(Result<?> result) {
        return result.fold(cause -> cause.getClass()
                                         .getSimpleName(),
                           _ -> "success");
    }

    static String message(Result<?> result) {
        return result.fold(TestKinds::chain, _ -> "");
    }

    /// The message of the cause, then of each wrapped origin, joined by " <- ".
    static String chain(Cause cause) {
        return cause instanceof Cause.Wrapped wrapped
               ? cause.message() + " <- " + chain(wrapped.origin())
               : cause.message();
    }
}
