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

/**
 * Default implementation of RetryAspectFactory.
 * Uses JDK dynamic proxies with pragmatica-lite Retry for retry logic.
 */
final class DefaultRetryAspectFactory implements RetryAspectFactory {
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    @Override
    @SuppressWarnings("unchecked")
    public <T> Aspect<T> create(RetryConfig config) {
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
                                              new RetryInvocationHandler<>(instance, config, enabled));
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

    // Note: InvocationHandler.invoke() requires `throws Throwable` - this is inherent
    // to the reflection API contract and acceptable for infrastructure code.
    private record RetryInvocationHandler<T>(T delegate,
                                             RetryConfig config,
                                             AtomicBoolean enabled) implements InvocationHandler {
        @Override
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
            var retry = Retry.create()
                             .attempts(config.maxAttempts())
                             .strategy(config.backoffStrategy());
            return retry.execute(() -> {
                                     try{
                                         return (Promise< ? >) method.invoke(delegate, args);
                                     } catch (Exception e) {
                                         return Promise.failure(InfraSliceError.RetryError.retryError("Failed to invoke method: " + method.getName(),
                                                                                                      e));
                                     }
                                 });
        }

        private boolean isPromiseReturning(Method method) {
            return Promise.class.isAssignableFrom(method.getReturnType());
        }

        private boolean isObjectMethod(Method method) {
            return method.getDeclaringClass() == Object.class;
        }
    }
}
