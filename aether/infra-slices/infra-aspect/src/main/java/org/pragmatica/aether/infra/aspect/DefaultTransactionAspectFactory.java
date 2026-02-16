package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// Default implementation of TransactionAspectFactory.
/// Uses JDK dynamic proxies with ThreadLocal for transaction context.
final class DefaultTransactionAspectFactory implements TransactionAspectFactory {
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final ThreadLocal<TransactionContext> currentContext = new ThreadLocal<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> Aspect<T> create(TransactionConfig config) {
        return instance -> createProxy(instance, config);
    }

    @SuppressWarnings("unchecked")
    private <T> T createProxy(T instance, TransactionConfig config) {
        if (!enabled.get()) {
            return instance;
        }
        var interfaces = instance.getClass()
                                 .getInterfaces();
        if (interfaces.length == 0) {
            return instance;
        }
        var handler = new TransactionInvocationHandler<>(instance, config, this);
        return (T) Proxy.newProxyInstance(instance.getClass()
                                                  .getClassLoader(),
                                          interfaces,
                                          handler);
    }

    @Override
    public Option<TransactionContext> currentTransaction() {
        return option(currentContext.get()).filter(TransactionContext::isActive);
    }

    @Override
    public Promise<TransactionContext> begin(TransactionConfig config) {
        return currentTransaction().map(existing -> routeExistingTransaction(config, existing))
                                 .or(() -> beginNewTransaction(config));
    }

    private Promise<TransactionContext> beginNewTransaction(TransactionConfig config) {
        return TransactionContext.transactionContext(config)
                                 .async()
                                 .onSuccess(currentContext::set);
    }

    private Promise<TransactionContext> routeExistingTransaction(TransactionConfig config,
                                                                 TransactionContext existing) {
        return switch (config.propagation()) {
            case REQUIRED, SUPPORTS -> Promise.success(existing);
            case REQUIRES_NEW -> suspendAndBeginNew(config, existing);
            case MANDATORY -> Promise.success(existing);
            case NOT_SUPPORTED -> suspendExisting(existing);
            case NEVER -> TransactionError.transactionAlreadyActive("begin")
                                          .promise();
            case NESTED -> beginNested(config, existing);
        };
    }

    private Promise<TransactionContext> suspendAndBeginNew(TransactionConfig config,
                                                           TransactionContext existing) {
        var suspended = existing.suspend();
        var newContext = TransactionContext.nestedContext(config, suspended);
        currentContext.set(newContext);
        return Promise.success(newContext);
    }

    private Promise<TransactionContext> suspendExisting(TransactionContext existing) {
        var suspended = existing.suspend();
        currentContext.set(suspended);
        return Promise.success(suspended);
    }

    private Promise<TransactionContext> beginNested(TransactionConfig config,
                                                    TransactionContext parent) {
        var nested = TransactionContext.nestedContext(config, parent);
        currentContext.set(nested);
        return Promise.success(nested);
    }

    @Override
    public Promise<Unit> commit() {
        return currentTransaction().toResult(TransactionError.noActiveTransaction("commit"))
                                 .async()
                                 .flatMap(this::commitTransaction);
    }

    private Promise<Unit> commitTransaction(TransactionContext context) {
        var committed = context.commit();
        restoreParentOrClear(committed);
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> rollback() {
        return currentTransaction().toResult(TransactionError.noActiveTransaction("rollback"))
                                 .async()
                                 .flatMap(this::rollbackTransaction);
    }

    private Promise<Unit> rollbackTransaction(TransactionContext context) {
        var rolledBack = context.rollback();
        restoreParentOrClear(rolledBack);
        return Promise.success(unit());
    }

    private void restoreParentOrClear(TransactionContext context) {
        var activeParent = context.parentContext()
                                  .filter(TransactionContext::isActive);
        activeParent.onPresent(parent -> currentContext.set(parent.resume()))
                    .onEmpty(currentContext::remove);
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
    private record TransactionInvocationHandler<T>(T delegate,
                                                   TransactionConfig config,
                                                   DefaultTransactionAspectFactory factory) implements InvocationHandler {
        @Override
        @SuppressWarnings("JBCT-EX-01")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return method.invoke(delegate, args);
            }
            if (!factory.enabled.get() || !isPromiseReturning(method)) {
                return method.invoke(delegate, args);
            }
            return invokeWithTransaction(method, args);
        }

        @SuppressWarnings({"unchecked", "JBCT-EX-01"})
        private Object invokeWithTransaction(Method method, Object[] args) throws Throwable {
            var existingTx = factory.currentTransaction();
            return existingTx.isPresent()
                   ? routeByPropagation(method, args, existingTx.unwrap())
                   : runInNewTransaction(method, args);
        }

        private Promise<?> runInNewTransaction(Method method, Object[] args) {
            var methodResult = factory.begin(config)
                                      .flatMap(ctx -> safeInvoke(method, args));
            return commitOrRollback(methodResult);
        }

        private Promise<?> commitOrRollback(Promise<?> methodResult) {
            return methodResult.flatMap(this::commitAndReturn)
                               .onFailure(cause -> factory.rollback());
        }

        private Promise<?> commitAndReturn(Object result) {
            return factory.commit()
                          .map(u -> result);
        }

        private Promise<?> routeByPropagation(Method method,
                                              Object[] args,
                                              TransactionContext existing) {
            return switch (config.propagation()) {
                case REQUIRED, SUPPORTS, MANDATORY -> safeInvoke(method, args);
                case REQUIRES_NEW -> runInSuspendedContext(method, args, existing);
                case NOT_SUPPORTED -> runWithoutTransaction(method, args, existing);
                case NEVER -> TransactionError.transactionAlreadyActive(method.getName())
                                              .promise();
                case NESTED -> runInNestedTransaction(method, args, existing);
            };
        }

        private Promise<?> runInSuspendedContext(Method method,
                                                 Object[] args,
                                                 TransactionContext existing) {
            var methodResult = factory.begin(config)
                                      .flatMap(ctx -> safeInvoke(method, args));
            return commitOrRollback(methodResult).onSuccess(result -> factory.currentContext.set(existing.resume()));
        }

        private Promise<?> runWithoutTransaction(Method method,
                                                 Object[] args,
                                                 TransactionContext existing) {
            factory.currentContext.set(existing.suspend());
            return safeInvoke(method, args).onSuccess(result -> factory.currentContext.set(existing.resume()));
        }

        private Promise<?> runInNestedTransaction(Method method,
                                                  Object[] args,
                                                  TransactionContext parent) {
            var nested = TransactionContext.nestedContext(config, parent);
            factory.currentContext.set(nested);
            return safeInvoke(method, args).onSuccess(result -> commitNested(nested, parent))
                             .onFailure(cause -> rollbackNested(nested, parent));
        }

        private void commitNested(TransactionContext nested, TransactionContext parent) {
            factory.currentContext.set(nested.commit());
            factory.currentContext.set(parent);
        }

        private void rollbackNested(TransactionContext nested, TransactionContext parent) {
            factory.currentContext.set(nested.rollback());
            factory.currentContext.set(parent);
        }

        @SuppressWarnings("unchecked")
        private Promise<?> safeInvoke(Method method, Object[] args) {
            try{
                return (Promise<?>) method.invoke(delegate, args);
            } catch (Exception e) {
                return TransactionError.operationFailed(method.getName(),
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
