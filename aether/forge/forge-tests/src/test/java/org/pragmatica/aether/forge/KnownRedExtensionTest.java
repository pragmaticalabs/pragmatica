// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.opentest4j.AssertionFailedError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/// Pins the three outcomes of [KnownRed]: a failure with the declared signature is ABORTED (reported skipped, naming the ticket), any
/// other failure is a REAL failure, and a pass FAILS ("remove @KnownRed"). Run through a real JUnit launcher on fixtures that are
/// disabled unless this test enables them, so the surrounding build never executes them.
class KnownRedExtensionTest {
    private static final String ENABLE = "knownred.fixtures";

    private static Map<String, String> outcomes;

    @BeforeAll
    static void runFixtures() {
        outcomes = new HashMap<>();
        for (var fixture : new Class<?>[]{Absorbed.class, OtherFailure.class, WrongExceptionClass.class, Passing.class,
                                          SetUpFailing.class, CauseChain.class}) {
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
    void failingSetUp_withTheSignature_abortsTheClass() {
        // a failing @BeforeAll is reported on the class container, not on a test
        assertThat(outcomes.entrySet().stream().filter(e -> e.getKey().startsWith("SetUpFailing.")).map(Map.Entry::getValue))
            .anyMatch(outcome -> outcome.startsWith("ABORTED") && outcome.contains("known red #9004"));
    }

    @Test
    void signatureIsFoundInTheCauseChain() {
        assertThat(outcomes.get("CauseChain.failsWithTheSignatureInACause")).startsWith("ABORTED").contains("#9005");
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
                    var name = fixture.getSimpleName() + "." + identifier.getDisplayName().replace("()", "");
                    var detail = result.getThrowable().map(t -> " " + t.getClass().getSimpleName() + ": " + t.getMessage()).orElse("");

                    if (identifier.isTest() || result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                        results.put(name, result.getStatus() + detail);
                    }
                }
            });
        } finally {
            System.clearProperty(ENABLE);
        }

        return results;
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
    @KnownRed(ticket = "#9004", exception = IllegalStateException.class, messageContains = "the known signature")
    static class SetUpFailing {
        @BeforeAll
        static void setUp() {
            throw new IllegalStateException("the known signature in setUp");
        }

        @Test
        void neverRuns() { }
    }

    @EnabledIfSystemProperty(named = ENABLE, matches = "true")
    static class CauseChain {
        @Test
        @KnownRed(ticket = "#9005", exception = IllegalStateException.class, messageContains = "the known signature")
        void failsWithTheSignatureInACause() {
            throw new IllegalStateException("wrapper", new RuntimeException("the known signature"));
        }
    }
}
