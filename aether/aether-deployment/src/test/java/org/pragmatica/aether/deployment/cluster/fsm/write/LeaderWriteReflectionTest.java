// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentContext;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentState;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2584 design §3.1 F1-F4: nothing in `cluster.fsm` but [FsmWriter] and [TermWriter] can hold or hand out a way to
/// apply consensus commands. The class list is read from the MAIN output directory only (the code source of
/// `ClusterDeploymentState`), never the test classpath, whose fixtures hold `ClusterNode` stubs by design.
class LeaderWriteReflectionTest {
    private static final String FSM = "org/pragmatica/aether/deployment/cluster/fsm/";
    private static final String FSM2 = "org/pragmatica/aether/deployment/cluster/fsm2/";
    private static final List<Class<?>> WRITERS = List.of(FsmWriter.class, TermWriter.class);

    @Test
    void classList_isTheMainOutputOnly_andReachesTheWriteSites() {
        var classes = mainClasses(FSM);
        var testSource = codeSource(LeaderWriteReflectionTest.class);

        assertThat(codeSource(ClusterDeploymentState.class)).as("the two code sources differ, so the filter discriminates")
                                                           .isNotEqualTo(testSource);
        assertThat(classes).as("positive controls")
                           .contains(ClusterDeploymentState.Active.class,
                                     ClusterDeploymentContext.class,
                                     classNamed("org.pragmatica.aether.deployment.cluster.fsm.StaleEntryCleaner"),
                                     FsmWriter.class,
                                     TermWriter.class);
        assertThat(classes).as("no class from the test code source").noneMatch(type -> codeSource(type).equals(testSource));
        assertThat(classes).as("no test class").noneMatch(type -> type.getName().endsWith("Test"));
    }

    @Test
    void F1_noFieldComponentOrReturnTypeHoldsAnApplier() {
        assertThat(applierHolders(mainClasses(FSM))).as("F1 over cluster.fsm").isEmpty();
    }

    @Test
    void F2_noParameterTakesAClusterNode_exceptTheContextConstructors() {
        assertThat(clusterNodeTakers(mainClasses(FSM))).as("F2 over cluster.fsm").isEmpty();
    }

    @Test
    void F3_theWritersAreFinal_andHandOutNoApplier() {
        var violations = new ArrayList<String>();

        for (var writer : WRITERS) {
            if (!Modifier.isFinal(writer.getModifiers())) {
                violations.add(writer.getSimpleName() + " is not final");
            }
            Arrays.stream(writer.getDeclaredMethods())
                  .filter(method -> !Modifier.isPrivate(method.getModifiers()) && !method.isSynthetic())
                  .filter(method -> handsOutAnApplier(method.getReturnType()))
                  .forEach(method -> violations.add(writer.getSimpleName() + "." + method.getName() + " returns "
                                                    + method.getGenericReturnType().getTypeName()));
        }
        assertThat(violations).isEmpty();
    }

    /// Green while `cluster.fsm2` has no applier (its package-info); red the day it acquires one, which forces a
    /// decision on whether the rule extends to it.
    @Test
    void F4_tripwire_fsm2HoldsNoApplier() {
        var classes = mainClasses(FSM2);

        assertThat(classes).as("positive control: fsm2 is on the main output").isNotEmpty();
        assertThat(applierHolders(classes)).as("F1 over cluster.fsm2").isEmpty();
        assertThat(clusterNodeTakers(classes)).as("F2 over cluster.fsm2").isEmpty();
    }

    private static List<String> applierHolders(List<Class<?>> classes) {
        var violations = new ArrayList<String>();

        classes.stream()
               .filter(type -> !WRITERS.contains(type))
               .forEach(type -> collectApplierHolders(type, violations));

        return violations;
    }

    private static void collectApplierHolders(Class<?> type, List<String> violations) {
        Arrays.stream(type.getDeclaredFields())
              .filter(field -> holdsAnApplier(field.getGenericType()))
              .forEach(field -> violations.add(type.getName() + " field " + field.getName() + ": " + field.getGenericType().getTypeName()));
        if (type.isRecord()) {
            Arrays.stream(type.getRecordComponents())
                  .filter(component -> holdsAnApplier(component.getGenericType()))
                  .forEach(component -> violations.add(type.getName() + " component " + component.getName()));
        }
        Arrays.stream(type.getDeclaredMethods())
              .filter(method -> holdsAnApplier(method.getGenericReturnType()))
              .forEach(method -> violations.add(type.getName() + " method " + method.getName() + " returns "
                                                + method.getGenericReturnType().getTypeName()));
    }

    private static List<String> clusterNodeTakers(List<Class<?>> classes) {
        var violations = new ArrayList<String>();

        for (var type : classes) {
            if (WRITERS.contains(type)) {
                continue;
            }
            Stream.concat(Arrays.stream(type.getDeclaredConstructors()), Arrays.stream(type.getDeclaredMethods()))
                  .filter(executable -> mentionsClusterNode(executable))
                  .filter(executable -> !(type == ClusterDeploymentContext.class && executable instanceof java.lang.reflect.Constructor<?>))
                  .forEach(executable -> violations.add(type.getName() + " " + executable));
        }
        return violations;
    }

    private static boolean mentionsClusterNode(Executable executable) {
        return Stream.concat(Arrays.stream(executable.getGenericParameterTypes()), Arrays.stream(executable.getParameterTypes()))
                     .map(Type::getTypeName)
                     .anyMatch(name -> name.contains("ClusterNode"));
    }

    private static boolean holdsAnApplier(Type type) {
        var name = type.getTypeName();

        return name.contains("ClusterNode") || name.contains("HierarchyStateWriter") || functionalOverKVCommand(type);
    }

    private static boolean functionalOverKVCommand(Type type) {
        return type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw
               && isFunctional(raw) && type.getTypeName().contains("KVCommand");
    }

    private static boolean handsOutAnApplier(Class<?> type) {
        return type.getName().contains("ClusterNode") || type.getName().contains("HierarchyStateWriter") || isFunctional(type);
    }

    private static boolean isFunctional(Class<?> type) {
        return type.isInterface() && (type.isAnnotationPresent(FunctionalInterface.class) || !type.isSealed() && abstractMethods(type) == 1);
    }

    private static long abstractMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                     .filter(method -> Modifier.isAbstract(method.getModifiers()))
                     .filter(method -> !isObjectMethod(method))
                     .count();
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());

            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /// Every class under `packagePath` in the code source of `ClusterDeploymentState` (the main output directory).
    private static List<Class<?>> mainClasses(String packagePath) {
        var root = codeSource(ClusterDeploymentState.class);

        try (var files = Files.walk(root.resolve(packagePath))) {
            return files.filter(file -> file.toString().endsWith(".class"))
                        .map(file -> root.relativize(file).toString())
                        .map(relative -> relative.substring(0, relative.length() - ".class".length()).replace('/', '.'))
                        .<Class<?>>map(LeaderWriteReflectionTest::classNamed)
                        .toList();
        } catch (IOException e) {
            throw new AssertionError("cannot list " + root.resolve(packagePath), e);
        }
    }

    private static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?> classNamed(String name) {
        try {
            return Class.forName(name, false, LeaderWriteReflectionTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
