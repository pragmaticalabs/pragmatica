// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.durabletopicstep;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// The #386 D5 context-carrying shape reached through a plain-interface STEP rather than declared on
/// the slice itself ([OrderAuditListener] injected into [OrderAuditSlice]).
///
/// The processor has a separate discovery and generation path for transitive reactive methods — a
/// step-qualified dispatch name and a different delegate expression — so a fixture that only covered
/// the direct path would leave that half unproven. As with the direct fixture, the load-bearing
/// assertion is implicit: this module compiles its generated sources, so a wrong adapter shape here
/// is a javac failure rather than a silent gap.
class GeneratedTransitiveContextualSubscriberTest {

    private static String factory;
    private static String manifest;

    @BeforeAll
    static void readGeneratedArtifacts() throws IOException {
        factory = Files.readString(locate(Paths.get("target", "generated-sources", "annotations",
                                                    "com", "example", "durabletopicstep",
                                                    "OrderAuditSliceFactory.java")));
        manifest = Files.readString(locate(Paths.get("target", "classes", "META-INF", "slice",
                                                     "OrderAuditSlice.manifest")));
    }

    private static Path locate(Path relative) {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);

        if (Files.exists(candidate)) {
            return candidate;
        }

        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    /// Same unpacking adapter as the direct path, but delegating through the step parameter.
    @Test
    void generatedAdapter_unpacksContextualEventThroughTheStep() {
        assertThat(factory).contains("contextual -> listener.onOrderPlaced((OrderPlaced) contextual.event(), contextual.context())");
        assertThat(factory).contains("new TypeToken<ContextualEvent>() {}");
    }

    /// The transitive handler is dispatched under its step-qualified name.
    @Test
    void manifest_marksStepQualifiedSubscriptionAsContextCarrying() {
        assertThat(manifest).contains("reactive.0.category=subscription");
        assertThat(manifest).contains("reactive.0.method=listenerOnOrderPlaced");
        assertThat(manifest).contains("reactive.0.context=message");
        assertThat(manifest).contains("reactive.0.messageType=com.example.durabletopic.OrderPlaced");
    }

    /// The host slice's own business method still gets its ordinary treatment — the context rule
    /// must not leak onto unrelated methods.
    @Test
    void hostSliceBusinessMethod_isUnaffected() {
        assertThat(factory).contains("delegate::report");
        assertThat(manifest).contains("envelope.version=1000");
    }
}
