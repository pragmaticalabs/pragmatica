// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;


@SuppressWarnings({"JBCT-LAM-01", "JBCT-LAM-02", "JBCT-SEQ-01", "JBCT-NEST-01", "JBCT-UTIL-02", "JBCT-ZONE-02", "JBCT-ZONE-03"})
public interface SliceFactory {
    Logger log = LoggerFactory.getLogger(SliceFactory.class);

    static Promise<Slice> createSlice(Class<?> sliceClass,
                                      SliceCreationContext creationContext,
                                      List<Slice> dependencies,
                                      List<DependencyDescriptor> descriptors) {
        log.debug("Creating slice from class {} with {} dependencies", sliceClass.getName(), dependencies.size());
        Result<Method> factoryMethodResult;

        try {
            factoryMethodResult = findFactoryMethod(sliceClass);
        } catch (Throwable t) {
            log.error("Exception in findFactoryMethod for {}: {}", sliceClass.getName(), t.getMessage(), t);

            return classLoadFailure(sliceClass.getName(), t).promise();
        }

        var verifiedResult = factoryMethodResult.onFailure(cause -> log.error("Failed to find factory method for {}: {}",
                                                                              sliceClass.getName(),
                                                                              cause.message()))
                                                .flatMap(method -> {
                                                             log.debug("Verifying parameters for method {} with {} dependencies",
                                                                       method.getName(),
                                                                       dependencies.size());

                                                             return verifyParameters(method,
                                                                                     sliceClass,
                                                                                     dependencies,
                                                                                     descriptors).onFailure(cause -> log.error("Failed to verify parameters for {}: {}",
                                                                                                                               method.getName(),
                                                                                                                               cause.message()));
                                                         });

        return verifiedResult.async()
                             .flatMap(method -> invokeFactory(method, creationContext, dependencies));
    }

    private static Result<Method> findFactoryMethod(Class<?> sliceClass) {
        var className = sliceClass.getSimpleName();

        log.debug("findFactoryMethod: className={}", className);
        var sliceName = className.endsWith("Factory")
                        ? className.substring(0,
                                              className.length() - "Factory".length())
                        : className;
        var expectedName = toLowercaseFirst(sliceName) + "Slice";

        log.debug("findFactoryMethod: expectedName={}", expectedName);
        Method[] methods;

        try {
            methods = sliceClass.getDeclaredMethods();
            log.trace("getDeclaredMethods returned {} methods for {}", methods.length, sliceClass.getName());
        } catch (Throwable t) {
            log.error("findFactoryMethod: getDeclaredMethods FAILED for {}: {}", sliceClass.getName(), t.getMessage(), t);

            return classLoadFailure(sliceClass.getName(), t).result();
        }

        return Arrays.stream(methods)
                     .filter(m -> Modifier.isStatic(m.getModifiers()))
                     .filter(m -> m.getName()
                                   .equals(expectedName))
                     .filter(m -> m.getReturnType()
                                   .equals(Promise.class))
                     .filter(m -> isPromiseOfSlice(m, sliceClass))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(() -> factoryMethodNotFound(sliceClass.getName(),
                                                            expectedName).result());
    }

    private static boolean isPromiseOfSlice(Method method, Class<?> sliceClass) {
        var genericReturnType = method.getGenericReturnType();

        if (genericReturnType instanceof ParameterizedType parameterizedType) {
            var typeArgs = parameterizedType.getActualTypeArguments();

            if (typeArgs.length == 1 && typeArgs[0] instanceof Class<?> classTypeArg) {
                return Slice.class.isAssignableFrom(classTypeArg);
            }
        }

        return false;
    }

    private static Result<Method> verifyParameters(Method method,
                                                   Class<?> sliceClass,
                                                   List<Slice> dependencies,
                                                   List<DependencyDescriptor> descriptors) {
        Class<?>[] parameterTypes;

        try {
            parameterTypes = method.getParameterTypes();
        } catch (Throwable t) {
            log.error("Failed to inspect factory parameters for {}: {}", method.getName(), t.getMessage(), t);

            return incompatibleRuntime(method.getName(), t).result();
        }

        if (parameterTypes.length != 1) {
            return parameterCountMismatch(method.getName(), 1, parameterTypes.length).result();
        }

        if (!parameterTypes[0].equals(SliceCreationContext.class)) {
            return firstParameterMustBeCreationContext(method.getName(), parameterTypes[0].getName()).result();
        }

        return success(method);
    }

    @SuppressWarnings({"unchecked", "JBCT-RET-03"})
    private static Promise<Slice> invokeFactory(Method method,
                                                SliceCreationContext creationContext,
                                                List<Slice> dependencies) {
        log.debug("Invoking factory method {}", method.getName());

        return Promise.lift(Causes::fromThrowable,
                            () -> {
                                method.setAccessible(true);
                                var args = new Object[]{creationContext};

                                log.debug("Calling factory method {} with args: SliceCreationContext",
                                          method.getName());

                                return (Promise<Slice>) method.invoke(null, args);
                            })
                      .mapError(SliceLoadingFailure::classify)
                      .flatMap(promise -> {
                                   log.info("Factory method {} returned promise, waiting for completion",
                                            method.getName());

                                   return promise.onSuccess(slice -> log.debug("Factory method {} completed, slice class: {}",
                                                                               method.getName(),
                                                                               slice.getClass().getName()))
                                                 .onFailure(cause -> log.error("Factory method {} failed: {}",
                                                                               method.getName(),
                                                                               cause.message()));
                               });
    }

    private static String toLowercaseFirst(String name) {
        if (name.isEmpty()) {
            return name;
        }

        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static Cause factoryMethodNotFound(String className, String methodName) {
        return new SliceLoadingFailure.Fatal.FactoryMethodNotFound(className, methodName);
    }

    private static Cause parameterCountMismatch(String methodName, int expected, int actual) {
        return new SliceLoadingFailure.Fatal.ParameterMismatch(methodName,
                                                               "expected " + expected
                                                              + " (SliceCreationContext), got " + actual
                                                              + " — slice was compiled against an older runtime"
                                                              + " (factory parameter 0 Aspect was removed); rebuild against this runtime version");
    }

    private static Cause firstParameterMustBeCreationContext(String methodName, String actual) {
        return new SliceLoadingFailure.Fatal.ParameterMismatch(methodName,
                                                               "factory parameter 0 must be SliceCreationContext, got " + actual
                                                              + " — slice was compiled against an older runtime; rebuild against this runtime version");
    }

    private static Cause incompatibleRuntime(String context, Throwable t) {
        return new SliceLoadingFailure.Fatal.ParameterMismatch(context,
                                                               "slice was compiled against an older runtime"
                                                              + " (references removed class " + missingClassName(t)
                                                              + ");"
                                                              + " rebuild against this runtime version");
    }

    /// Reflective factory resolution that dies on a removed class (an rc1-built slice referencing the
    /// deleted Aspect type) surfaces a class-resolution Throwable — as a NoClassDefFoundError from
    /// getDeclaredMethods on eager JVMs, or a ClassNotFoundException from parameter inspection on lazy
    /// ones. Map those to the actionable "rebuild" cause; keep the generic ClassLoadFailed otherwise.
    private static Cause classLoadFailure(String className, Throwable t) {
        return isMissingClass(t)
               ? incompatibleRuntime(className, t)
               : new SliceLoadingFailure.Fatal.ClassLoadFailed(className, Causes.fromThrowable(t));
    }

    private static boolean isMissingClass(Throwable t) {
        return causeChain(t).anyMatch(SliceFactory::isResolutionError);
    }

    private static boolean isResolutionError(Throwable t) {
        return t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException || t instanceof TypeNotPresentException;
    }

    private static String missingClassName(Throwable t) {
        return causeChain(t).filter(SliceFactory::isResolutionError)
                         .findFirst()
                         .map(SliceFactory::throwableLabel)
                         .orElseGet(() -> throwableLabel(t));
    }

    private static String throwableLabel(Throwable t) {
        return Option.option(t.getMessage()).or(t.getClass().getName());
    }

    private static Stream<Throwable> causeChain(Throwable t) {
        return Stream.iterate(t, Objects::nonNull, Throwable::getCause);
    }

    private static Cause parameterTypeMismatch(int index, String expected, String actual) {
        return new SliceLoadingFailure.Fatal.ParameterMismatch("parameter[" + index + "]",
                                                               "expected " + expected + ", got " + actual);
    }
}
