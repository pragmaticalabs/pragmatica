/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/// The reflective engine of the #3846 authority census (memo r3 section 2.1, item 3). The authority set A is COMPUTED, never
/// listed: a least fixpoint over types from ANY package, starting at the roots. A type T is in A when it is assignable to a
/// root; or it has a non-private field or record component whose generic type mentions a type in A; or it has a non-private
/// method whose generic RETURN type mentions a type in A and that takes no parameter mentioning a type in A. A member on
/// `noPropagate` is skipped by that rule. "Mentions" walks type arguments, wildcard and type-variable bounds and array
/// components.
///
/// Scanned classes are enumerated from a compiled-classes directory: nested, inner, anonymous and local classes are
/// included, and so are synthetic `val$x` and `this$0` fields, which are scanned like any field and fail closed. Lambdas
/// have no class file, so they are delegation by capture. The candidates of the fixpoint are the scanned classes plus every
/// `org.pragmatica` class their members mention, transitively (the engine reads `Fsm.Entered` through a scanned field).
///
/// Violations: CEN-1 is a field (private or not, synthetic or not) whose generic type mentions A and that is not on
/// `allowed`; CEN-2 is a non-private, non-bridge method whose generic return type mentions A, that takes no parameter
/// mentioning A and is not on `allowed`. Keys are `Outer.Inner.member` (fields) and `Outer.Inner.member()` (methods,
/// overloads fold into one key), with the package dropped.
final class AuthorityCensus {
    private AuthorityCensus() {}

    record Report(Set<String> violations, Set<String> authority) {}

    static Report scan(Path classesDir, String packagePath, Set<Class<?>> roots, Set<String> allowed, Set<String> noPropagate)
        throws IOException, ClassNotFoundException {
        var scanned = scannedClasses(classesDir, packagePath);
        var candidates = candidates(scanned);
        var authority = fixpoint(candidates, roots, noPropagate);
        var violations = new TreeSet<String>();

        for (var type : scanned) {
            collectViolations(type, authority, allowed, violations);
        }

        var names = new TreeSet<String>();

        authority.forEach(type -> names.add(chain(type)));

        return new Report(violations, names);
    }

    static String chain(Class<?> type) {
        var name = type.getName();
        var pkg = type.getPackageName();

        return (pkg.isEmpty()
                ? name
                : name.substring(pkg.length() + 1)).replace('$', '.');
    }

    private static List<Class<?>> scannedClasses(Path classesDir, String packagePath) throws IOException, ClassNotFoundException {
        var root = classesDir.resolve(packagePath);
        var classes = new ArrayList<Class<?>>();
        List<Path> files;

        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString()
                                            .endsWith(".class"))
                        .filter(path -> !path.getFileName()
                                             .toString()
                                             .equals("package-info.class"))
                        .sorted()
                        .toList();
        }

        for (var file : files) {
            var relative = classesDir.relativize(file)
                                     .toString();
            var name = relative.substring(0,
                                          relative.length() - ".class".length())
                               .replace('/', '.');

            classes.add(Class.forName(name,
                                      false,
                                      AuthorityCensus.class.getClassLoader()));
        }

        return classes;
    }

    private static Set<Class<?>> candidates(List<Class<?>> scanned) {
        var found = new HashSet<Class<?>>(scanned);
        var pending = new ArrayDeque<Class<?>>(scanned);

        while (!pending.isEmpty()) {
            for (var mentioned : mentionedBy(pending.poll())) {
                if (isProjectClass(mentioned) && found.add(mentioned)) {
                    pending.add(mentioned);
                }
            }
        }

        return found;
    }

    private static boolean isProjectClass(Class<?> type) {
        return type.getName()
                   .startsWith("org.pragmatica.");
    }

    private static List<Class<?>> mentionedBy(Class<?> type) {
        var out = new ArrayList<Class<?>>();

        for (var field : type.getDeclaredFields()) {
            collect(field.getGenericType(), out, new HashSet<>());
        }

        for (var method : type.getDeclaredMethods()) {
            collect(method.getGenericReturnType(), out, new HashSet<>());

            for (var parameter : method.getGenericParameterTypes()) {
                collect(parameter, out, new HashSet<>());
            }
        }

        return out;
    }

    private static void collect(Type type, List<Class<?>> out, Set<Type> seen) {
        if (!seen.add(type)) {
            return;
        }

        switch (type) {
            case Class<?> c when c.isArray() -> collect(c.getComponentType(), out, seen);
            case Class<?> c -> out.add(c);
            case ParameterizedType p -> collectParameterized(p, out, seen);
            case WildcardType w -> collectAll(w.getUpperBounds(), w.getLowerBounds(), out, seen);
            case TypeVariable<?> v -> collectAll(v.getBounds(), new Type[0], out, seen);
            case GenericArrayType g -> collect(g.getGenericComponentType(), out, seen);
            default -> {}
        }
    }

    private static void collectParameterized(ParameterizedType type, List<Class<?>> out, Set<Type> seen) {
        collect(type.getRawType(), out, seen);
        collectAll(type.getActualTypeArguments(), new Type[0], out, seen);
    }

    private static void collectAll(Type[] first, Type[] second, List<Class<?>> out, Set<Type> seen) {
        for (var type : first) {
            collect(type, out, seen);
        }

        for (var type : second) {
            collect(type, out, seen);
        }
    }

    private static boolean mentions(Type type, Set<Class<?>> authority) {
        var mentioned = new ArrayList<Class<?>>();

        collect(type, mentioned, new HashSet<>());

        return mentioned.stream()
                        .anyMatch(authority::contains);
    }

    private static Set<Class<?>> fixpoint(Set<Class<?>> candidates, Set<Class<?>> roots, Set<String> noPropagate) {
        var authority = new HashSet<Class<?>>(roots);

        for (var candidate : candidates) {
            if (roots.stream()
                     .anyMatch(root -> root.isAssignableFrom(candidate))) {
                authority.add(candidate);
            }
        }

        boolean grew;

        do {
            grew = false;

            for (var candidate : candidates) {
                if (!authority.contains(candidate) && propagates(candidate, authority, noPropagate)) {
                    authority.add(candidate);
                    grew = true;
                }
            }
        } while (grew);

        return authority;
    }

    private static boolean propagates(Class<?> type, Set<Class<?>> authority, Set<String> noPropagate) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                   .anyMatch(field -> propagatingField(type, field, authority, noPropagate))
               || java.util.Arrays.stream(type.getDeclaredMethods())
                                  .anyMatch(method -> propagatingMethod(type, method, authority, noPropagate));
    }

    private static boolean propagatingField(Class<?> type, Field field, Set<Class<?>> authority, Set<String> noPropagate) {
        var visible = !Modifier.isPrivate(field.getModifiers()) || (type.isRecord() && !Modifier.isStatic(field.getModifiers()));

        return visible && !noPropagate.contains(chain(type) + "." + field.getName()) && mentions(field.getGenericType(), authority);
    }

    private static boolean propagatingMethod(Class<?> type, Method method, Set<Class<?>> authority, Set<String> noPropagate) {
        return exposesAuthority(method, authority) && !noPropagate.contains(chain(type) + "." + method.getName() + "()");
    }

    private static boolean exposesAuthority(Method method, Set<Class<?>> authority) {
        return !Modifier.isPrivate(method.getModifiers()) && !method.isBridge() && !method.isSynthetic()
               && mentions(method.getGenericReturnType(), authority) && java.util.Arrays.stream(method.getGenericParameterTypes())
                                                                                        .noneMatch(parameter -> mentions(parameter,
                                                                                                                         authority));
    }

    private static void collectViolations(Class<?> type, Set<Class<?>> authority, Set<String> allowed, Set<String> violations) {
        for (var field : type.getDeclaredFields()) {
            var key = chain(type) + "." + field.getName();

            if (mentions(field.getGenericType(), authority) && !allowed.contains(key)) {
                violations.add("CEN-1 " + key);
            }
        }

        for (var method : type.getDeclaredMethods()) {
            var key = chain(type) + "." + method.getName() + "()";

            if (exposesAuthority(method, authority) && !allowed.contains(key)) {
                violations.add("CEN-2 " + key);
            }
        }
    }
}
