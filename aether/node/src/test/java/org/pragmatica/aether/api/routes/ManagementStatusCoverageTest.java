// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.ParameterError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The management-API status funnel is total over the management plane's cause types.
///
/// Every concrete `Cause` class under `org.pragmatica.aether.api` (every enum constant, every record, every class) is
/// discovered from the compiled classes and answered through [ManagementStatus]. The test fails when
///
/// - a cause type is unclassified (a NEW type that reaches the funnel with no status: add it to the central table or
///   give it `HttpStatusAware`),
/// - a type the table lists no longer exists (a rename: the table entry would silently stop applying),
/// - a status differs from the pinned one.
///
/// An ad-hoc `Causes.cause("text")` instance has no type, so it is outside this universe by construction.
class ManagementStatusCoverageTest {
    private static final String UNCLASSIFIED = "UNCLASSIFIED";
    private static final String API_PACKAGE = "org/pragmatica/aether/api";

    /// Pinned statuses. Records are probed with default component values; a record carrying its status as a component
    /// (`StreamRefused`) is probed with `CONFLICT`.
    private static final Map<String, String> PINNED = new TreeMap<>(Map.of());

    @Test
    void everyManagementCauseType_isClassified_withItsPinnedStatus() throws Exception {
        var observed = observed();

        assertThat(observed).as("management cause types and the status each answers").isEqualTo(PINNED);
    }

    @Test
    void everyKeyTheCentralTableLists_stillNamesAnExistingTypeOrConstant() throws Exception {
        for (var key : ManagementStatus.listedKeys()) {
            var type = Class.forName(typeOf(key), false, ManagementStatus.class.getClassLoader());

            assertThat(Cause.class.isAssignableFrom(type)).as("%s is a Cause", key).isTrue();
            if (key.contains("#")) {
                var constant = key.substring(key.indexOf('#') + 1);

                assertThat(type.isEnum()).as("%s names an enum", key).isTrue();
                assertThat(Stream.of(type.getEnumConstants()).map(c -> ((Enum<?>) c).name())).as("constants of %s", type.getName()).contains(constant);
            }
        }
    }

    @Test
    void rawStreamError_reachingTheFunnel_answersItsClassStatus() {
        assertThat(ManagementStatus.statusOf(StreamError.General.BUFFER_FULL)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ManagementStatus.statusOf(StreamError.General.CONSUMER_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void parameterError_answers400() {
        assertThat(ManagementStatus.statusOf(new ParameterError.InvalidParameter("p"))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unclassifiedCause_answers500_andIsReportedOnce() {
        var untyped = Causes.cause("not a classified type");

        assertThat(ManagementStatus.classifiedStatus(untyped).isEmpty()).isTrue();
        assertThat(ManagementStatus.statusOf(untyped)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ManagementStatus.statusOf(untyped)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ManagementStatus.reportedTypes()).contains(untyped.getClass().getName());
    }

    @Test
    void composite_withAnUnclassifiedMember_isUnclassified() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(), Causes.cause("untyped").result());

        assertThat(ManagementStatus.classifiedStatus(composite).isEmpty()).isTrue();
        assertThat(ManagementStatus.statusOf(composite)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void composite_ofAgreeingMembers_answersTheirStatus() {
        var composite = Causes.composite(new ParameterError.InvalidParameter("a").result(), new ParameterError.InvalidParameter("b").result());

        assertThat(ManagementStatus.statusOf(composite)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static String typeOf(String key) {
        var hash = key.indexOf('#');

        return hash < 0 ? key : key.substring(0, hash);
    }

    private static Map<String, String> observed() throws Exception {
        var observed = new TreeMap<String, String>();

        for (var type : causeTypes()) {
            if (type.isEnum()) {
                for (var constant : type.getEnumConstants()) {
                    observed.put(type.getName() + "#" + ((Enum<?>) constant).name(), statusName((Cause) constant));
                }
            } else {
                observed.put(type.getName(), statusName(instantiate(type)));
            }
        }
        return observed;
    }

    private static String statusName(Cause cause) {
        return ManagementStatus.classifiedStatus(cause).map(HttpStatus::name).or(UNCLASSIFIED);
    }

    private static List<Class<?>> causeTypes() throws IOException, ClassNotFoundException {
        var root = Path.of(ManagementStatus.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        var types = new ArrayList<Class<?>>();

        try (Stream<Path> files = Files.walk(root.resolve(API_PACKAGE))) {
            for (var file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                var name = root.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                var type = Class.forName(name, false, ManagementStatus.class.getClassLoader());

                if (isConcreteNamedCause(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private static boolean isConcreteNamedCause(Class<?> type) {
        return Cause.class.isAssignableFrom(type)
               && !type.isInterface()
               && !Modifier.isAbstract(type.getModifiers())
               && !type.isAnonymousClass()
               && !type.isLocalClass()
               && !type.isSynthetic();
    }

    private static Cause instantiate(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        var parameterTypes = constructor.getParameterTypes();
        var args = new Object[parameterTypes.length];

        for (int i = 0; i < args.length; i++) {
            args[i] = defaultFor(parameterTypes[i]);
        }
        constructor.setAccessible(true);
        return (Cause) constructor.newInstance(args);
    }

    private static Object defaultFor(Class<?> type) {
        if (type == int.class || type == long.class || type == short.class || type == byte.class) return 0;
        if (type == boolean.class) return false;
        if (type == double.class || type == float.class) return 0.0;
        if (type == String.class) return "";
        if (type == HttpStatus.class) return HttpStatus.CONFLICT;
        if (type == Cause.class) return Causes.cause("probe");
        if (type == Option.class) return Option.none();
        if (type == List.class) return List.of();
        if (type == Throwable.class) return new RuntimeException("probe");
        return null;
    }
}
