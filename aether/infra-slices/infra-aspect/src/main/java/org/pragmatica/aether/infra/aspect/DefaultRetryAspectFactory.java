package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.infra.InfraSliceError;
import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Retry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Unit.unit;

/// Default implementation of RetryAspectFactory.
/// Uses JDK dynamic proxies with pragmatica-lite Retry for retry logic.
final class DefaultRetryAspectFactory implements RetryAspectFactory {
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    @Override
    @SuppressWarnings("unchecked")
    public <T> Aspect<T> create(RetryConfig config) {
        return instance -> createProxy(instance, config);
    }

    @SuppressWarnings("unchecked")
    private <T> T createProxy(T instance, RetryConfig config) {
        if (!enabled.get()) {
            return instance;
        }
        var interfaces = instance.getClass()
                                 .getInterfaces();
        if (interfaces.length == 0) {
            return instance;
        }
        var handler = new RetryInvocationHandler<>(instance, config, enabled);
        return (T) Proxy.newProxyInstance(instance.getClass()
                                                  .getClassLoader(),
                                          interfaces,
                                          handler);
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

    @SuppressWarnings({"JBCT-VO-01", "JBCT-VO-02"})
    private record RetryInvocationHandler<T>(T delegate,
                                             RetryConfig config,
                                             AtomicBoolean enabled) implements InvocationHandler {
        @Override
        @SuppressWarnings("JBCT-EX-01")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!enabled.get() || isObjectMethod(method)) {
                return method.invoke(delegate, args);
            }
            if (isPromiseReturning(method)) {
                return invokeWithRetry(method, args);
            }
            return method.invoke(delegate, args);
        }

        @SuppressWarnings("unchecked")
        private Object invokeWithRetry(Method method, Object[] args) {
            var retry = Retry.retry()
                             .attempts(config.maxAttempts())
                             .strategy(config.backoffStrategy());
            return retry.execute(() -> safeInvoke(method, args));
        }

        @SuppressWarnings("unchecked")
        private Promise<?> safeInvoke(Method method, Object[] args) {
            try{
                return (Promise<?>) method.invoke(delegate, args);
            } catch (Exception e) {
                return InfraSliceError.retryFailed("Failed to invoke method: " + method.getName(),
                                                   e)
                                      .promise();
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
