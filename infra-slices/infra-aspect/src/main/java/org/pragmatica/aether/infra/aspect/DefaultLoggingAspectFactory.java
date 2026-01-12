package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.lang.Unit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Default implementation of LoggingAspectFactory.
 * Uses JDK dynamic proxies to wrap slice instances.
 */
final class DefaultLoggingAspectFactory implements LoggingAspectFactory {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultLoggingAspectFactory.class);
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    @Override
    @SuppressWarnings("unchecked")
    public <T> Aspect<T> create(LogConfig config) {
        return instance -> {
            if (!enabled.get()) {
                return instance;
            }
            var interfaces = instance.getClass()
                                     .getInterfaces();
            if (interfaces.length == 0) {
                LOG.warn("Cannot create logging proxy for class without interfaces: {}",
                         instance.getClass()
                                 .getName());
                return instance;
            }
            return (T) Proxy.newProxyInstance(instance.getClass()
                                                      .getClassLoader(),
                                              interfaces,
                                              new LoggingInvocationHandler<>(instance, config, enabled));
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

    private record LoggingInvocationHandler<T>(T delegate,
                                               LogConfig config,
                                               AtomicBoolean enabled) implements InvocationHandler {
        private static final Logger LOGGER = LoggerFactory.getLogger(LoggingInvocationHandler.class);

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!enabled.get() || isObjectMethod(method)) {
                return method.invoke(delegate, args);
            }
            var startTime = System.nanoTime();
            var methodName = config.name() + "." + method.getName();
            logEntry(methodName, args);
            try{
                var result = method.invoke(delegate, args);
                var duration = (System.nanoTime() - startTime) / 1_000_000.0;
                logExit(methodName, result, duration);
                return result;
            } catch (Throwable t) {
                var duration = (System.nanoTime() - startTime) / 1_000_000.0;
                logError(methodName, t, duration);
                throw t;
            }
        }

        private void logEntry(String methodName, Object[] args) {
            if (config.logArgs() && args != null && args.length > 0) {
                log("-> {} args={}",
                    methodName,
                    summarizeArgs(args));
            } else {
                log("-> {}",
                    methodName);
            }
        }

        private void logExit(String methodName, Object result, double durationMs) {
            if (config.logResult() && config.logDuration()) {
                log("<- {} result={} ({}ms)", methodName, summarize(result), String.format("%.2f", durationMs));
            } else if (config.logDuration()) {
                log("<- {} ({}ms)", methodName, String.format("%.2f", durationMs));
            } else if (config.logResult()) {
                log("<- {} result={}", methodName, summarize(result));
            } else {
                log("<- {}", methodName);
            }
        }

        private void logError(String methodName, Throwable t, double durationMs) {
            LOGGER.error("x {} error={} ({}ms)", methodName, t.getMessage(), String.format("%.2f", durationMs));
        }

        private void log(String format, Object... args) {
            switch (config.level()) {
                case TRACE -> LOGGER.trace(format, args);
                case DEBUG -> LOGGER.debug(format, args);
                case INFO -> LOGGER.info(format, args);
                case WARN -> LOGGER.warn(format, args);
                case ERROR -> LOGGER.error(format, args);
            }
        }

        private String summarizeArgs(Object[] args) {
            return Arrays.toString(args);
        }

        private String summarize(Object obj) {
            if (obj == null) {
                return "null";
            }
            var str = obj.toString();
            return str.length() > 100
                   ? str.substring(0, 100) + "..."
                   : str;
        }

        private boolean isObjectMethod(Method method) {
            return method.getDeclaringClass() == Object.class;
        }
    }
}
