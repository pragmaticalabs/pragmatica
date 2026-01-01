package org.pragmatica.aether.slice.dependency;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/**
 * Creates slice instances via reflection using static factory methods.
 * <p>
 * Factory method convention:
 * - Static method
 * - Name: lowercase-first version of class name (e.g., UserService.userService(...))
 * - Returns: instance of the slice class
 * - Parameters: resolved dependency slices in declaration order
 * <p>
 * Parameter matching:
 * - Matched by position
 * - Type verified against dependency class
 * - Optional name verification from META-INF/dependencies descriptor
 */
public interface SliceFactory {
    /**
     * Create slice instance using static factory method.
     *
     * @param sliceClass   The slice class to instantiate
     * @param dependencies Resolved dependency instances in declaration order
     * @param descriptors  Dependency descriptors from META-INF/dependencies (for verification)
     *
     * @return Created slice instance
     */
    static Result<Slice> createSlice(Class< ? > sliceClass,
                                     List<Slice> dependencies,
                                     List<DependencyDescriptor> descriptors) {
        return findFactoryMethod(sliceClass)
               .flatMap(method -> verifyParameters(method, dependencies, descriptors))
               .flatMap(method -> invokeFactory(method, dependencies));
    }

    private static Result<Method> findFactoryMethod(Class< ? > sliceClass) {
        var expectedName = toLowercaseFirst(sliceClass.getSimpleName());
        return Arrays.stream(sliceClass.getDeclaredMethods())
                     .filter(m -> Modifier.isStatic(m.getModifiers()))
                     .filter(m -> m.getName()
                                   .equals(expectedName))
                     .filter(m -> sliceClass.isAssignableFrom(m.getReturnType()))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(() -> factoryMethodNotFound(sliceClass.getName(),
                                                            expectedName)
                                      .result());
    }

    private static Result<Method> verifyParameters(Method method,
                                                   List<Slice> dependencies,
                                                   List<DependencyDescriptor> descriptors) {
        var parameters = method.getParameters();
        if (parameters.length != dependencies.size()) {
            return parameterCountMismatch(method.getName(),
                                          parameters.length,
                                          dependencies.size())
                   .result();
        }
        for (int i = 0; i < parameters.length; i++ ) {
            var parameter = parameters[i];
            var dependency = dependencies.get(i);
            var descriptor = descriptors.get(i);
            // Verify type matches
            if (!parameter.getType()
                          .isInstance(dependency)) {
                return parameterTypeMismatch(i,
                                             parameter.getType()
                                                      .getName(),
                                             dependency.getClass()
                                                       .getName())
                       .result();
            }
        }
        return Result.success(method);
    }

    private static Result<Slice> invokeFactory(Method method, List<Slice> dependencies) {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               method.setAccessible(true);
                               var instance = method.invoke(null, dependencies.toArray());
                               return (Slice) instance;
                           });
    }

    private static String toLowercaseFirst(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static Cause factoryMethodNotFound(String className, String methodName) {
        return Causes.cause("Factory method not found: " + className + "." + methodName + "()");
    }

    private static Cause parameterCountMismatch(String methodName, int expected, int actual) {
        return Causes.cause("Parameter count mismatch in " + methodName + ": expected " + expected + ", got " + actual);
    }

    private static Cause parameterTypeMismatch(int index, String expected, String actual) {
        return Causes.cause("Parameter type mismatch at index " + index + ": expected " + expected + ", got " + actual);
    }
}
