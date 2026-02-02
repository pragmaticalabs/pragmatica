package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates slice instances via reflection using static factory methods.
 * <p>
 * RFC-0001 Factory method convention:
 * - Static method
 * - Name: lowercase-first version of class name (e.g., UserService.userService(...))
 * - Returns: Promise<SliceType>
 * - First parameter: Aspect<SliceType>
 * - Second parameter: SliceInvokerFacade
 * - Remaining parameters: resolved dependency slices in declaration order
 */
public interface SliceFactory {
    Logger log = LoggerFactory.getLogger(SliceFactory.class);

    /**
     * Create slice instance using static factory method.
     *
     * @param sliceClass    The slice class to instantiate
     * @param invokerFacade The slice invoker facade for inter-slice calls
     * @param dependencies  Resolved dependency instances in declaration order
     * @param descriptors   Dependency descriptors from META-INF/dependencies (for verification)
     * @return Promise of created slice instance
     */
    static Promise<Slice> createSlice(Class<?> sliceClass,
                                      SliceInvokerFacade invokerFacade,
                                      List<Slice> dependencies,
                                      List<DependencyDescriptor> descriptors) {
        log.info("Creating slice from class {} with {} dependencies", sliceClass.getName(), dependencies.size());
        Result<Method> factoryMethodResult;
        try{
            log.info("About to call findFactoryMethod for {}", sliceClass.getName());
            factoryMethodResult = findFactoryMethod(sliceClass);
            log.info("findFactoryMethod for {} returned: {}",
                     sliceClass.getName(),
                     factoryMethodResult.isSuccess()
                     ? "success"
                     : "failure");
        } catch (Throwable t) {
            log.error("Exception in findFactoryMethod for {}: {}", sliceClass.getName(), t.getMessage(), t);
            return Causes.fromThrowable(t)
                         .promise();
        }
        var verifiedResult = factoryMethodResult.onFailure(cause -> log.error("Failed to find factory method for {}: {}",
                                                                              sliceClass.getName(),
                                                                              cause.message()))
                                                .flatMap(method -> {
                                                             log.info("Verifying parameters for method {} with {} dependencies",
                                                                      method.getName(),
                                                                      dependencies.size());
                                                             return verifyParameters(method,
                                                                                     sliceClass,
                                                                                     dependencies,
                                                                                     descriptors)
        .onFailure(cause -> log.error("Failed to verify parameters for {}: {}",
                                      method.getName(),
                                      cause.message()));
                                                         });
        log.info("verifyParameters for {} result: {}, failure message: {}",
                 sliceClass.getName(),
                 verifiedResult.isSuccess()
                 ? "success"
                 : "failure",
                 verifiedResult.fold(cause -> cause.message(), _ -> "n/a"));
        return verifiedResult.async()
                             .flatMap(method -> {
                                          log.info("About to invoke factory method {}",
                                                   method.getName());
                                          return invokeFactory(method, invokerFacade, dependencies);
                                      });
    }

    private static Result<Method> findFactoryMethod(Class<?> sliceClass) {
        // Generated factories have two methods:
        // - {sliceName}() -> Promise<{SliceName}> (service interface)
        // - {sliceName}Slice() -> Promise<Slice> (Slice wrapper for runtime)
        // We need the *Slice method to get a Slice instance
        var className = sliceClass.getSimpleName();
        log.debug("findFactoryMethod: className={}", className);
        var sliceName = className.endsWith("Factory")
                        ? className.substring(0,
                                              className.length() - "Factory".length())
                        : className;
        var expectedName = toLowercaseFirst(sliceName) + "Slice";
        log.debug("findFactoryMethod: expectedName={}", expectedName);
        log.info("findFactoryMethod: About to call getDeclaredMethods on {}", sliceClass.getName());
        Method[] methods;
        try{
            methods = sliceClass.getDeclaredMethods();
            log.info("findFactoryMethod: getDeclaredMethods returned {} methods for {}",
                     methods.length,
                     sliceClass.getName());
        } catch (Throwable t) {
            log.error("findFactoryMethod: getDeclaredMethods FAILED for {}: {}", sliceClass.getName(), t.getMessage(), t);
            return Causes.fromThrowable(t)
                         .result();
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
                // The *Slice factory method returns Promise<Slice>
                return Slice.class.isAssignableFrom(classTypeArg);
            }
        }
        return false;
    }

    private static Result<Method> verifyParameters(Method method,
                                                   Class<?> sliceClass,
                                                   List<Slice> dependencies,
                                                   List<DependencyDescriptor> descriptors) {
        var parameters = method.getParameters();
        // New-style factories have exactly 2 parameters: (Aspect, SliceInvokerFacade)
        // Dependencies are resolved dynamically via SliceInvokerFacade at runtime
        if (parameters.length != 2) {
            return parameterCountMismatch(method.getName(), 2, parameters.length).result();
        }
        // Verify first parameter is Aspect
        if (!parameters[0].getType()
                       .equals(Aspect.class)) {
            return firstParameterMustBeAspect(method.getName(),
                                              parameters[0].getType()
                                                        .getName()).result();
        }
        // Verify second parameter is SliceInvokerFacade
        if (!parameters[1].getType()
                       .equals(SliceInvokerFacade.class)) {
            return secondParameterMustBeInvoker(method.getName(),
                                                parameters[1].getType()
                                                          .getName()).result();
        }
        return Result.success(method);
    }

    @SuppressWarnings("unchecked")
    private static Promise<Slice> invokeFactory(Method method,
                                                SliceInvokerFacade invokerFacade,
                                                List<Slice> dependencies) {
        log.info("Invoking factory method {}", method.getName());
        return Promise.lift(Causes::fromThrowable,
                            () -> {
                                method.setAccessible(true);
                                // New-style factories take only (Aspect, SliceInvokerFacade)
        // Dependencies are resolved dynamically via SliceInvokerFacade
        var args = new Object[]{Aspect.identity(), invokerFacade};
                                log.debug("Calling factory method {} with args: Aspect, SliceInvokerFacade",
                                          method.getName());
                                return (Promise<Slice>) method.invoke(null, args);
                            })
                      .flatMap(promise -> {
                                   log.info("Factory method {} returned promise, waiting for completion",
                                            method.getName());
                                   return promise.onSuccess(slice -> log.info("Factory method {} completed successfully, slice class: {}",
                                                                              method.getName(),
                                                                              slice.getClass()
                                                                                   .getName()))
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
        return Causes.cause("Factory method not found: " + className + "." + methodName + "() returning Promise<Slice>");
    }

    private static Cause parameterCountMismatch(String methodName, int expected, int actual) {
        return Causes.cause("Parameter count mismatch in " + methodName + ": expected " + expected
                            + " (Aspect, SliceInvokerFacade, + dependencies), got " + actual);
    }

    private static Cause firstParameterMustBeAspect(String methodName, String actual) {
        return Causes.cause("First parameter of " + methodName + " must be Aspect, got " + actual);
    }

    private static Cause secondParameterMustBeInvoker(String methodName, String actual) {
        return Causes.cause("Second parameter of " + methodName + " must be SliceInvokerFacade, got " + actual);
    }

    private static Cause parameterTypeMismatch(int index, String expected, String actual) {
        return Causes.cause("Parameter type mismatch at index " + index + ": expected " + expected + ", got " + actual);
    }
}
