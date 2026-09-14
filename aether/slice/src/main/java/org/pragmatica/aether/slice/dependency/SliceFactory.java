// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
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

            return classLoadFailure(sliceClass, sliceClass.getName(), t).promise();
        }

        var verifiedResult = factoryMethodResult.onFailure(cause -> log.error("Failed to find factory method for {}: {}",
                                                                              sliceClass.getName(),
                                                                              cause.message()))
                                                .flatMap(method -> {
                                                             log.debug("Verifying parameters for method {} with {} dependencies",
                                                                       method.getName(),
                                                                       dependencies.size());

                                                             return verifyParameters(method, dependencies, descriptors).onFailure(cause -> log.error("Failed to verify parameters for {}: {}",
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

            return classLoadFailure(sliceClass, sliceClass.getName(), t).result();
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

    /// No class-resolution guard here: a [Method] holds its parameter classes already resolved
    /// (`getParameterTypes` clones a `Class<?>[]` the VM filled when it built the Method in
    /// `getDeclaredMethods0`), so an unresolvable parameter type surfaces in `getDeclaredMethods`
    /// above and never reaches this call — a guard here would be unreachable and unpinnable (#758).
    private static Result<Method> verifyParameters(Method method,
                                                   List<Slice> dependencies,
                                                   List<DependencyDescriptor> descriptors) {
        var parameterTypes = method.getParameterTypes();

        if (parameterTypes.length != 1) {
            return parameterCountMismatch(method.getName(), 1, parameterTypes.length).result();
        }

        if (!parameterTypes[0].equals(SliceCreationContext.class)) {
            return firstParameterMustBeCreationContext(method.getName(), parameterTypes[0].getName()).result();
        }

        return success(method);
    }

    // JBCT-RET-08: reflective static invoke — null receiver is the JDK Method.invoke contract
    @SuppressWarnings({"unchecked", "JBCT-RET-03", "JBCT-RET-08"})
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
                      // #930 — PERMANENT. This maps a Throwable escaping the slice's OWN factory
                      // method under reflection. A factory that throws is a defect in the slice
                      // being loaded, and it will throw identically on every retry and on every
                      // node, so retrying spends the budget to reach the same verdict later.
                      .mapError(cause -> SliceLoadingFailure.classify(cause, Unrecognised.PERMANENT))
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

    /// Two causes produce this state and nothing observable here separates them: the class was removed
    /// by a runtime or artifact upgrade, or the serving loader holds a DIFFERENT VERSION of the
    /// artifact than the slice was built against — `SharedLibraryClassLoader.addArtifact` keeps the
    /// first version loaded for a `groupId:artifactId` and ignores every later one, so a second slice
    /// asking for a newer version is served the older jar. The message states the evidence — which
    /// loader serves the package, its jars, and the artifact versions it holds — names both remedies
    /// and picks NEITHER: asserting the upgrade cause sends an operator to rebuild a slice that is
    /// fine, and a rebuild against the older jar would not even compile (#758).
    private static Cause servedPackageLacksClass(String context, String missingClass, ClassLoader owner) {
        return new SliceLoadingFailure.Fatal.ParameterMismatch(context,
                                                               "slice references class " + missingClass
                                                              + ", whose package " + packageOf(missingClass)
                                                              + " is served by " + loaderLabel(owner)
                                                              + " but the class is not"
                                                              + loadedArtifacts(owner)
                                                              + ". Two causes are indistinguishable from here: the class was"
                                                              + " REMOVED by a runtime or artifact upgrade, in which case"
                                                              + " rebuild against this runtime version; or that loader serves"
                                                              + " a DIFFERENT VERSION of the artifact than the slice was built"
                                                              + " against, in which case reconcile the version and do NOT"
                                                              + " rebuild. Compare the versions above with the slice's declared"
                                                              + " dependencies in META-INF/dependencies/<FactoryClass> before"
                                                              + " choosing (#758).");
    }

    /// The artifact versions a [SharedLibraryClassLoader] has loaded: the evidence an operator needs
    /// to tell a version skew from an upgrade, since only they hold the slice's own declaration. No
    /// other loader tracks versions, and an empty set says nothing, so both stay silent.
    private static String loadedArtifacts(ClassLoader owner) {
        if (!(owner instanceof SharedLibraryClassLoader sharedLoader)) {
            return "";
        }

        var versions = sharedLoader.getLoadedArtifacts()
                                   .entrySet()
                                   .stream()
                                   .map(entry -> entry.getKey() + ":" + entry.getValue().withQualifier())
                                   .sorted()
                                   .toList();

        return versions.isEmpty()
               ? ""
               : " (that loader has loaded " + versions + ")";
    }

    /// Reflective factory resolution that dies on an unresolvable class surfaces a class-resolution
    /// Throwable — a NoClassDefFoundError from getDeclaredMethods on eager JVMs, or a
    /// ClassNotFoundException from parameter inspection on lazy ones. The Throwable says WHICH class,
    /// never WHY, and the two causes need opposite remedies (#758). The discriminator is the OWNING
    /// LOADER, never the class name — a name prefix cannot tell a runtime class from an application
    /// class scaffolded under the vendor namespace (the ticket's own `org.pragmatica.example.ticketing`):
    ///
    ///   - a loader ABOVE the slice's own (the shared/infra loader, the runtime loader) SERVES the
    ///     missing class's package — it has defined a class in it, or holds the package directory as
    ///     a resource — and yet lacks the class: the message names the loader, its jars and the
    ///     versions it has loaded, and states BOTH causes that produce this state without choosing
    ///     between them, because nothing observable here separates them;
    ///   - no loader above the slice's serves that package: the message says exactly that, lists the
    ///     chain with each loader's URLs and every section a jar can come from, and asserts no cause.
    ///
    /// Both probes are needed. `getDefinedPackage` is lazy — populated only once a class from the
    /// package has been loaded — and a `[shared]`/`[infra]` jar nothing has loaded from yet is the
    /// DEFAULT state at factory-inspection time; the resource probe covers it, and keeps the verdict
    /// from flipping once a class does load. Only the FIRST unresolvable class is named:
    /// `getDeclaredMethods` fails once for the whole class, so the others are not enumerable from this
    /// failure. Anything that is not a resolution error stays the generic ClassLoadFailed.
    private static Cause classLoadFailure(Class<?> sliceClass, String context, Throwable t) {
        if (!isMissingClass(t)) {
            return new SliceLoadingFailure.Fatal.ClassLoadFailed(context, Causes.fromThrowable(t));
        }

        var missingClass = missingClassName(t);
        var sliceLoader = sliceClass.getClassLoader();

        return servingLoader(sliceLoader,
                             packageOf(missingClass)).map(owner -> servedPackageLacksClass(context, missingClass, owner))
                            .or(() -> new SliceLoadingFailure.Fatal.DependencyClassNotOnClasspath(context,
                                                                                                  missingClass,
                                                                                                  packageOf(missingClass),
                                                                                                  loaderChain(sliceLoader)));
    }

    /// The first loader strictly above the slice's own that serves `packageName`.
    private static Option<ClassLoader> servingLoader(ClassLoader sliceLoader, String packageName) {
        return ancestors(sliceLoader).skip(1)
                        .filter(loader -> serves(loader, packageName))
                        .findFirst()
                        .map(Option::some)
                        .orElseGet(Option::none);
    }

    /// Defined a class in the package, or holds its directory (own URLs for a URLClassLoader — a
    /// parent-first `getResource` would credit a child with its parent's contents — the chain
    /// otherwise). A jar written without directory entries is invisible to the second probe — most
    /// build tools write them, but not all: this repo's own `h2-2.4.240.jar` holds 1066 class entries
    /// and no directory entry. Such a jar falls to the unserved branch, which asserts no cause, so the
    /// gap costs detail and never a wrong verdict. The default package has no directory and gets the
    /// first probe only.
    private static boolean serves(ClassLoader loader, String packageName) {
        if (loader.getDefinedPackage(packageName) != null) {
            return true;
        }

        if (packageName.isEmpty()) {
            return false;
        }

        var directory = packageName.replace('.', '/');

        return (loader instanceof URLClassLoader urlLoader
                ? urlLoader.findResource(directory)
                : loader.getResource(directory)) != null;
    }

    /// The slice's loader and everything above it, each labelled with its URLs when it has any, so the
    /// reader sees which loaders were asked and what each one holds.
    private static List<String> loaderChain(ClassLoader sliceLoader) {
        return ancestors(sliceLoader).map(SliceFactory::loaderLabel)
                        .toList();
    }

    private static Stream<ClassLoader> ancestors(ClassLoader loader) {
        return Stream.iterate(loader, Objects::nonNull, ClassLoader::getParent);
    }

    private static String loaderLabel(ClassLoader loader) {
        return loader instanceof URLClassLoader urlLoader
               ? loader.getClass()
                       .getSimpleName() + Arrays.stream(urlLoader.getURLs())
                                                .map(URL::toString)
                                                .toList()
               : loader.getClass()
                       .getName();
    }

    private static String packageOf(String className) {
        var lastDot = className.lastIndexOf('.');

        return lastDot < 0
               ? ""
               : className.substring(0, lastDot);
    }

    private static boolean isMissingClass(Throwable t) {
        return causeChain(t).anyMatch(SliceFactory::isResolutionError);
    }

    private static boolean isResolutionError(Throwable t) {
        return t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException || t instanceof TypeNotPresentException;
    }

    /// Normalised to the binary name of the ELEMENT class: NoClassDefFoundError carries `a/b/C`, or
    /// the descriptor `[La/b/C;` (any depth) for an array-typed parameter, TypeNotPresentException
    /// carries `Type a.b.C not present`, ClassNotFoundException carries `a.b.C`. A primitive array
    /// cannot be unresolvable, so `[I` never arrives here.
    private static String missingClassName(Throwable t) {
        return causeChain(t).filter(SliceFactory::isResolutionError)
                         .findFirst()
                         .map(SliceFactory::throwableLabel)
                         .orElseGet(() -> throwableLabel(t))
                         .replace('/', '.')
                         .replaceFirst("^Type (.*) not present$",
                                       "$1")
                         .replaceFirst("^\\[+L(.*);$",
                                       "$1");
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
