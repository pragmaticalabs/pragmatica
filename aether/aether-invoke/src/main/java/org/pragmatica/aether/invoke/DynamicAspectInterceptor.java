package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.DynamicAspectMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Intercepts slice method invocations to apply dynamic aspect logging.
///
/// <p>When aspect mode includes logging, logs ENTER/EXIT with duration.
/// When mode is NONE, delegates directly to the proceed function with zero overhead.
public interface DynamicAspectInterceptor {
    /// Intercept a slice method invocation with optional aspect logging.
    ///
    /// @param slice     Target slice artifact
    /// @param method    Method being invoked
    /// @param requestId Request ID for correlation
    /// @param proceed   The actual invocation to execute
    /// @param <R>       Response type
    /// @return Promise resolving to the invocation result
    <R> Promise<R> intercept(Artifact slice,
                             MethodName method,
                             String requestId,
                             Fn0<Promise<R>> proceed);

    /// Create an interceptor that applies dynamic aspect logging based on mode lookup.
    ///
    /// @param modeLookup     Resolves aspect mode for a given artifactBase and methodName
    /// @return a new interceptor instance
    static DynamicAspectInterceptor dynamicAspectInterceptor(Fn2<DynamicAspectMode, String, String> modeLookup) {
        return new DynamicAspectInterceptorImpl(modeLookup);
    }

    /// Create a no-op interceptor that always passes through without any aspect logic.
    static DynamicAspectInterceptor noOp() {
        return new NoOpDynamicAspectInterceptor();
    }
}

class DynamicAspectInterceptorImpl implements DynamicAspectInterceptor {
    private static final Logger aspectLog = LoggerFactory.getLogger("org.pragmatica.aether.aspect");

    private final Fn2<DynamicAspectMode, String, String> modeLookup;

    DynamicAspectInterceptorImpl(Fn2<DynamicAspectMode, String, String> modeLookup) {
        this.modeLookup = modeLookup;
    }

    @Override
    public <R> Promise<R> intercept(Artifact slice,
                                    MethodName method,
                                    String requestId,
                                    Fn0<Promise<R>> proceed) {
        var mode = modeLookup.apply(slice.base()
                                         .asString(),
                                    method.name());
        if (!mode.isLoggingEnabled()) {
            return proceed.apply();
        }
        return interceptWithLogging(slice, method, requestId, proceed);
    }

    private <R> Promise<R> interceptWithLogging(Artifact slice,
                                                MethodName method,
                                                String requestId,
                                                Fn0<Promise<R>> proceed) {
        var startTime = System.nanoTime();
        aspectLog.info("[aspect] [requestId={}] ENTER {}.{}", requestId, slice, method);
        return proceed.apply()
                      .onSuccess(_ -> logExit(slice, method, requestId, startTime, true, null))
                      .onFailure(cause -> logExit(slice,
                                                  method,
                                                  requestId,
                                                  startTime,
                                                  false,
                                                  cause.message()));
    }

    private void logExit(Artifact slice,
                         MethodName method,
                         String requestId,
                         long startTime,
                         boolean success,
                         String error) {
        var durationMs = (System.nanoTime() - startTime) / 1_000_000;
        if (success) {
            aspectLog.info("[aspect] [requestId={}] EXIT {}.{} duration={}ms success=true",
                           requestId,
                           slice,
                           method,
                           durationMs);
        } else {
            aspectLog.info("[aspect] [requestId={}] EXIT {}.{} duration={}ms success=false error={}",
                           requestId,
                           slice,
                           method,
                           durationMs,
                           error);
        }
    }
}

class NoOpDynamicAspectInterceptor implements DynamicAspectInterceptor {
    @Override
    public <R> Promise<R> intercept(Artifact slice,
                                    MethodName method,
                                    String requestId,
                                    Fn0<Promise<R>> proceed) {
        return proceed.apply();
    }
}
