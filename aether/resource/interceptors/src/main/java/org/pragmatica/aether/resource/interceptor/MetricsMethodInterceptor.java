package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.aether.slice.MethodInterceptor;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import io.micrometer.core.instrument.Timer;

/// Method interceptor that records timing and success/failure counts via Micrometer.
public record MetricsMethodInterceptor(MetricsConfig config) implements MethodInterceptor {
    @Override
    public <R, T> Fn1<Promise<R>, T> intercept(Fn1<Promise<R>, T> method) {
        return request -> invokeWithMetrics(method, request);
    }

    private <R, T> Promise<R> invokeWithMetrics(Fn1<Promise<R>, T> method, T request) {
        var sample = Timer.start(config.registry());
        return method.apply(request)
                     .onResult(result -> recordMetrics(sample, result));
    }

    private <R> void recordMetrics(Timer.Sample sample, Result<R> result) {
        var suffix = result.isSuccess()
                     ? ".success"
                     : ".failure";
        var tagsArray = config.tags()
                              .toArray(new String[0]);
        sample.stop(config.registry()
                          .timer(config.name() + suffix,
                                 tagsArray));
    }
}
