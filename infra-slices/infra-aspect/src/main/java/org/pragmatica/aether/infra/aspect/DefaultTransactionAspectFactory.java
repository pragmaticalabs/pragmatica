package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/**
 * Default implementation of TransactionAspectFactory.
 * Uses JDK dynamic proxies with ThreadLocal for transaction context.
 */
final class DefaultTransactionAspectFactory implements TransactionAspectFactory {
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final ThreadLocal<TransactionContext> currentContext = new ThreadLocal<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> Aspect<T> create(TransactionConfig config) {
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
                                              new TransactionInvocationHandler<>(instance, config, this));
        };
    }

    @Override
    public Option<TransactionContext> currentTransaction() {
        return option(currentContext.get())
                     .filter(TransactionContext::isActive);
    }

    @Override
    public Promise<TransactionContext> begin(TransactionConfig config) {
        return currentTransaction()
                                 .fold(() -> beginNewTransaction(config),
                                       existing -> handleExistingTransaction(config, existing));
    }

    private Promise<TransactionContext> beginNewTransaction(TransactionConfig config) {
        var context = TransactionContext.transactionContext(config);
        currentContext.set(context);
        return Promise.success(context);
    }

    private Promise<TransactionContext> handleExistingTransaction(TransactionConfig config,
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
        return currentTransaction()
                                 .fold(() -> TransactionError.noActiveTransaction("commit")
                                                             .<Unit> promise(),
                                       this::commitTransaction);
    }

    private Promise<Unit> commitTransaction(TransactionContext context) {
        var committed = context.commit();
        restoreParentOrClear(committed);
        return Promise.success(unit());
    }

    @Override
    public Promise<Unit> rollback() {
        return currentTransaction()
                                 .fold(() -> TransactionError.noActiveTransaction("rollback")
                                                             .<Unit> promise(),
                                       this::rollbackTransaction);
    }

    private Promise<Unit> rollbackTransaction(TransactionContext context) {
        var rolledBack = context.rollback();
        restoreParentOrClear(rolledBack);
        return Promise.success(unit());
    }

    private void restoreParentOrClear(TransactionContext context) {
        context.parentContext()
               .filter(TransactionContext::isActive)
               .fold(() -> {
                         currentContext.remove();
                         return unit();
                     },
                     parent -> {
                         currentContext.set(parent.resume());
                         return unit();
                     });
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

    private record TransactionInvocationHandler<T>(T delegate,
                                                   TransactionConfig config,
                                                   DefaultTransactionAspectFactory factory) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (isObjectMethod(method)) {
                return method.invoke(delegate, args);
            }
            if (!factory.enabled.get() || !isPromiseReturning(method)) {
                return method.invoke(delegate, args);
            }
            return invokeWithTransaction(method, args);
        }

        @SuppressWarnings("unchecked")
        private Object invokeWithTransaction(Method method, Object[] args) throws Throwable {
            var existingTx = factory.currentTransaction();
            return existingTx.fold(() -> executeInNewTransaction(method, args),
                                   existing -> executeInExistingTransaction(method, args, existing));
        }

        private Promise< ?> executeInNewTransaction(Method method, Object[] args) {
            return factory.begin(config)
                          .flatMap(ctx -> executeMethod(method, args))
                          .flatMap(result -> factory.commit()
                                                    .map(u -> result))
                          .onFailure(cause -> factory.rollback());
        }

        private Promise< ?> executeInExistingTransaction(Method method,
                                                         Object[] args,
                                                         TransactionContext existing) {
            return handlePropagation(method, args, existing);
        }

        private Promise< ?> handlePropagation(Method method,
                                              Object[] args,
                                              TransactionContext existing) {
            return switch (config.propagation()) {
                case REQUIRED, SUPPORTS, MANDATORY -> executeMethod(method, args);
                case REQUIRES_NEW -> executeInSuspendedContext(method, args, existing);
                case NOT_SUPPORTED -> executeWithoutTransaction(method, args, existing);
                case NEVER -> TransactionError.transactionAlreadyActive(method.getName())
                                              .promise();
                case NESTED -> executeInNestedTransaction(method, args, existing);
            };
        }

        private Promise< ?> executeInSuspendedContext(Method method,
                                                      Object[] args,
                                                      TransactionContext existing) {
            return factory.begin(config)
                          .flatMap(ctx -> executeMethod(method, args))
                          .flatMap(result -> factory.commit()
                                                    .map(u -> result))
                          .onFailure(cause -> factory.rollback())
                          .map(result -> {
                              factory.currentContext.set(existing.resume());
                              return result;
                          });
        }

        private Promise< ?> executeWithoutTransaction(Method method,
                                                      Object[] args,
                                                      TransactionContext existing) {
            factory.currentContext.set(existing.suspend());
            return executeMethod(method, args)
                                .map(result -> {
                                    factory.currentContext.set(existing.resume());
                                    return result;
                                });
        }

        private Promise< ?> executeInNestedTransaction(Method method,
                                                       Object[] args,
                                                       TransactionContext parent) {
            var nested = TransactionContext.nestedContext(config, parent);
            factory.currentContext.set(nested);
            return executeMethod(method, args)
                                .map(result -> {
                                         factory.currentContext.set(nested.commit());
                                         factory.currentContext.set(parent);
                                         return result;
                                     })
                                .onFailure(cause -> {
                                               factory.currentContext.set(nested.rollback());
                                               factory.currentContext.set(parent);
                                           });
        }

        @SuppressWarnings("unchecked")
        private Promise< ?> executeMethod(Method method, Object[] args) {
            try{
                return (Promise< ? >) method.invoke(delegate, args);
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
