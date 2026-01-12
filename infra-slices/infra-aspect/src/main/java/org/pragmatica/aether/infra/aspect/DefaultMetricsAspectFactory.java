package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import static org.pragmatica.lang.Unit.unit;

/**
 * Default implementation of MetricsAspectFactory.
 * Uses JDK dynamic proxies with Micrometer timers for metrics collection.
 */
final class DefaultMetricsAspectFactory implements MetricsAspectFactory {
    private final MeterRegistry registry;
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    DefaultMetricsAspectFactory(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Aspect<T> create(MetricsConfig config) {
        return instance -> {
            if (!enabled.get()) {
                return instance;
            }
            var interfaces = instance.getClass()
                                     .getInterfaces();
            if (interfaces.length == 0) {
                return instance;
            }
            return (T) Proxy.newProxyInstance(instance.getClass()
                                                      .getClassLoader(),
                                              interfaces,
                                              new MetricsInvocationHandler<>(instance, config, registry, enabled));
        };
    }

    @Override
    public Unit setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        return unit();
    }

    @Override
    public boolean isEnabled() {
        return enabled.get();
    }

    @Override
    public MeterRegistry registry() {
        return registry;
    }

    // Note: InvocationHandler.invoke() requires `throws Throwable` - this is inherent
    // to the reflection API contract and acceptable for infrastructure code.
    private record MetricsInvocationHandler<T>(T delegate,
                                               MetricsConfig config,
                                               MeterRegistry registry,
                                               AtomicBoolean enabled) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!enabled.get() || isObjectMethod(method)) {
                return method.invoke(delegate, args);
            }
            var metricName = config.name() + "." + method.getName();
            if (isPromiseReturning(method)) {
                return invokeWithPromiseMetrics(method, args, metricName);
            }
            return invokeWithTimer(method, args, metricName);
        }

        @SuppressWarnings("unchecked")
        private Object invokeWithPromiseMetrics(Method method, Object[] args, String metricName) throws Throwable {
            var result = (Promise< ? >) method.invoke(delegate, args);
            return wrapPromise(result, metricName);
        }

        private <R> Promise<R> wrapPromise(Promise<R> promise, String metricName) {
            var sample = Timer.start(registry);
            return promise.onResult(r -> recordMetric(sample, metricName, r.isSuccess()));
        }

        private void recordMetric(Timer.Sample sample, String metricName, boolean success) {
            var timerName = success
                            ? metricName + ".success"
                            : metricName + ".failure";
            sample.stop(registry.timer(timerName,
                                       config.tags()
                                             .toArray(new String[0])));
        }

        private Object invokeWithTimer(Method method, Object[] args, String metricName) throws Throwable {
            var tagsArray = config.tags()
                                  .toArray(new String[0]);
            var timer = registry.timer(metricName, tagsArray);
            var sample = Timer.start(registry);
            try{
                var result = method.invoke(delegate, args);
                sample.stop(timer);
                return result;
            } catch (Throwable t) {
                sample.stop(registry.timer(metricName + ".error", tagsArray));
                throw t;
            }
        }

        private boolean isPromiseReturning(Method method) {
            return Promise.class.isAssignableFrom(method.getReturnType());
        }

        private boolean isObjectMethod(Method method) {
            return method.getDeclaringClass() == Object.class;
        }
    }
}
