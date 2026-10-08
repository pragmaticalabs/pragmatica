// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.opentest4j.AssertionFailedError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/// Pins [KnownRed]: a failure with the declared signature is ABORTED (reported skipped, naming the ticket), any other failure is a REAL
/// failure, a pass FAILS ("remove @KnownRed"); the same holds on `@RepeatedTest` and inside a `@Nested` class; a signature that would absorb
/// everything fails the annotated test; and the annotation cannot be placed on a class, nor on a method that is not a test. Run through a real
/// JUnit launcher on fixtures that are disabled unless this test enables them, so the surrounding build never executes them.
class KnownRedExtensionTest {
    private static final String ENABLE = "knownred.fixtures";

    private static Map<String, String> outcomes;

    @BeforeAll
    static void runFixtures() {
        outcomes = new TreeMap<>();
        for (var fixture : new Class<?>[]{Absorbed.class, OtherFailure.class, WrongExceptionClass.class, Passing.class, CauseChain.class,
                                          RepeatedPassing.class, RepeatedAbsorbed.class, NestedHolder.class, BlankMessage.class, BlankMessagePassing.class,
                                          CatchAllException.class, BlankTicket.class}) {
            outcomes.putAll(run(fixture));
        }
    }

    @Test
    void failureWithTheDeclaredSignature_isAborted_andNamesTheTicket() {
        assertThat(outcomes.get("Absorbed.failsWithTheSignature")).startsWith("ABORTED").contains("known red #9001").contains("IllegalStateException");
    }

    @Test
    void failureWithAnotherMessage_isARealFailure() {
        assertThat(outcomes.get("OtherFailure.failsForANewReason")).startsWith("FAILED").contains("a different reason");
    }

    @Test
    void failureWithAnotherExceptionClass_isARealFailure() {
        assertThat(outcomes.get("WrongExceptionClass.failsWithTheRightMessageButTheWrongClass")).startsWith("FAILED");
    }

    @Test
    void passingTest_failsWithRemoveKnownRed() {
        assertThat(outcomes.get("Passing.passes")).startsWith("FAILED").contains("remove @KnownRed").contains("#9002");
    }

    @Test
    void signatureIsFoundInTheCauseChain() {
        assertThat(outcomes.get("CauseChain.failsWithTheSignatureInACause")).startsWith("ABORTED").contains("#9005");
    }

    @Test
    void repeatedTest_passingInvocationsEachFlip() {
        assertThat(of("RepeatedPassing.")).hasSize(2).allSatisfy(outcome -> assertThat(outcome).startsWith("FAILED").contains("remove @KnownRed"));
    }

    @Test
    void repeatedTest_failingInvocationsAreEachAbsorbed() {
        assertThat(of("RepeatedAbsorbed.")).hasSize(2).allSatisfy(outcome -> assertThat(outcome).startsWith("ABORTED").contains("#9006"));
    }

    @Test
    void nestedClass_methodLevelAnnotationFlipsOnAPass() {
        assertThat(of("NestedHolder$Inner.")).hasSize(1).allSatisfy(outcome -> assertThat(outcome).startsWith("FAILED").contains("remove @KnownRed").contains("#9007"));
    }

    @Test
    void blankMessageSignature_failsTheTest_insteadOfAbsorbingEverything() {
        assertThat(outcomes.get("BlankMessage.failsForAnyReason")).startsWith("FAILED").contains("malformed @KnownRed").contains("messageContains is blank");
        assertThat(outcomes.get("BlankMessagePassing.passes")).startsWith("FAILED").contains("malformed @KnownRed");
    }

    @Test
    void catchAllExceptionClass_failsTheTest() {
        assertThat(outcomes.get("CatchAllException.failsForAnyReason")).startsWith("FAILED").contains("malformed @KnownRed").contains("catch-all");
    }

    @Test
    void blankTicket_failsTheTest() {
        assertThat(outcomes.get("BlankTicket.failsWithTheSignature")).startsWith("FAILED").contains("malformed @KnownRed").contains("ticket is blank");
    }

    @Test
    void annotationTargetIsMethodOnly_soAClassLevelUseDoesNotCompile() {
        assertThat(KnownRed.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
    }

    @Test
    void noTestClassPutsKnownRedOnAMethodThatIsNotATest() throws Exception {
        var problems = new ArrayList<String>();

        for (var type : testClasses()) {
            try {
                problems.addAll(misplaced(type));
            } catch (LinkageError e) {
                // a class whose signatures cannot link here is not a placement problem of ours
            }
        }

        assertThat(problems).as("@KnownRed on a method the extension never intercepts (a setup method) is silently inert").isEmpty();
        assertThat(misplaced(Misplaced.class)).as("control: the scan can see a misplaced annotation").hasSize(1);
    }

    private static List<String> of(String prefix) {
        return outcomes.entrySet().stream().filter(e -> e.getKey().startsWith(prefix)).map(Map.Entry::getValue).toList();
    }

    private static List<String> misplaced(Class<?> type) {
        var found = new ArrayList<String>();

        for (Method method : type.getDeclaredMethods()) {
            if (AnnotationSupport.isAnnotated(method, KnownRed.class)
                && !AnnotationSupport.isAnnotated(method, Test.class)
                && !AnnotationSupport.isAnnotated(method, TestTemplate.class)) {
                found.add(type.getSimpleName() + "." + method.getName());
            }
        }

        return found;
    }

    /// Every class of this module's compiled test tree, loaded without initialising it; this test's own fixtures are excluded.
    private static List<Class<?>> testClasses() throws IOException, URISyntaxException {
        var root = Path.of(KnownRedExtensionTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var found = new ArrayList<Class<?>>();

        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                var name = root.relativize(path).toString().replace('/', '.').replaceAll("\\.class$", "");

                if (name.startsWith(KnownRedExtensionTest.class.getName())) {
                    continue;
                }

                try {
                    found.add(Class.forName(name, false, KnownRedExtensionTest.class.getClassLoader()));
                } catch (ClassNotFoundException | LinkageError e) {
                    // a class that cannot even load is not a placement problem of ours
                }
            }
        }

        return found;
    }

    private static Map<String, String> run(Class<?> fixture) {
        var results = new ConcurrentHashMap<String, String>();
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selectClass(fixture)).build();
        var launcher = LauncherFactory.create(LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build());

        System.setProperty(ENABLE, "true");
        try {
            launcher.execute(request, new TestExecutionListener() {
                @Override
                public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                    if (!identifier.isTest()) {
                        return;
                    }

                    var owner = identifier.getUniqueId().contains("[nested-class:")
                                ? fixture.getSimpleName() + "$" + nestedName(identifier.getUniqueId())
                                : fixture.getSimpleName();
                    var name = owner + "." + identifier.getDisplayName().replace("()", "");
                    var detail = result.getThrowable().map(t -> " " + t.getClass().getSimpleName() + ": " + t.getMessage()).orElse("");

                    results.put(name, result.getStatus() + detail);
                }
            });
        } finally {
            System.clearProperty(ENABLE);
        }

        return results;
    }

    private static String nestedName(String uniqueId) {
        var start = uniqueId.indexOf("[nested-class:") + "[nested-class:".length();

        return uniqueId.substring(start, uniqueId.indexOf(']', start));
    }

    // --- fixtures: only run through the launcher above ---------------------------------------------------------------------------

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class Absorbed {
        @Test
        @KnownRed(ticket = "#9001", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsWithTheSignature() {
            throw new IllegalStateException("boom: the known signature, as in the nightly");
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class OtherFailure {
        @Test
        @KnownRed(ticket = "#9001", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsForANewReason() {
            throw new IllegalStateException("a different reason");
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class WrongExceptionClass {
        @Test
        @KnownRed(ticket = "#9001", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsWithTheRightMessageButTheWrongClass() {
            throw new AssertionFailedError("the known signature");
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class Passing {
        @Test
        @KnownRed(ticket = "#9002", exception = IllegalStateException.class, messageContains = "the known signature")
        void passes() { }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class CauseChain {
        @Test
        @KnownRed(ticket = "#9005", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsWithTheSignatureInACause() {
            throw new IllegalStateException("wrapper", new RuntimeException("the known signature"));
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class RepeatedPassing {
        @RepeatedTest(2)
        @KnownRed(ticket = "#9006", exception = IllegalStateException.class, messageContains = "the known signature")
        void passesTwice() { }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class RepeatedAbsorbed {
        @RepeatedTest(2)
        @KnownRed(ticket = "#9006", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsTwice() {
            throw new IllegalStateException("the known signature");
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class NestedHolder {
        @Nested
        class Inner {
            @Test
            @KnownRed(ticket = "#9007", exception = IllegalStateException.class, messageContains = "the known signature")
            void passes() { }
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class BlankMessage {
        @Test
        @KnownRed(ticket = "#9008", exception = IllegalStateException.class, messageContains = "  ")
        void failsForAnyReason() {
            throw new IllegalStateException("anything at all");
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class BlankMessagePassing {
        @Test
        @KnownRed(ticket = "#9008", exception = IllegalStateException.class, messageContains = "")
        void passes() { }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class CatchAllException {
        @Test
        @KnownRed(ticket = "#9009", exception = Exception.class, messageContains = "anything")
        void failsForAnyReason() {
            throw new IllegalStateException("anything at all");
        }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class BlankTicket {
        @Test
        @KnownRed(ticket = " ", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsWithTheSignature() {
            throw new IllegalStateException("the known signature");
        }
    }

    /// A misplaced annotation, for the scan's control only (never run).
    static class Misplaced {
        @BeforeEach
        @KnownRed(ticket = "#9010", exception = IllegalStateException.class, messageContains = "setup")
        void setUp() { }
    }
}
