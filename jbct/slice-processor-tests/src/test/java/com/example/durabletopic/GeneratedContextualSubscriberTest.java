// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.durabletopic;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/// Acceptance gate for the #386 D5 context-carrying subscriber ([DurableOrderSlice]).
///
/// This is the half that cannot be proven in `SliceProcessorTest`: its in-memory file manager has no
/// readable `resources.toml`, so the fail-closed validation there refuses every two-argument
/// subscriber before generation ever runs. Here the topic is declared durable in a real
/// `resources.toml` on the class output, so the accepting path executes for real.
///
/// The strongest assertion is implicit: this module compiles its generated sources with javac. If the
/// adapter emitted the wrong unpacking shape, the wrong cast, or referenced `ContextualEvent`
/// incorrectly, `DurableOrderSliceFactory` would not compile, the module build would fail, and these
/// tests would never run.
class GeneratedContextualSubscriberTest {

    private static String factory;
    private static String manifest;

    @BeforeAll
    static void readGeneratedArtifacts() throws IOException {
        factory = Files.readString(locate(Paths.get("target", "generated-sources", "annotations",
                                                    "com", "example", "durabletopic",
                                                    "DurableOrderSliceFactory.java")));
        manifest = Files.readString(locate(Paths.get("target", "classes", "META-INF", "slice",
                                                     "DurableOrderSlice.manifest")));
    }

    private static Path locate(Path relative) {
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);

        if (Files.exists(candidate)) {
            return candidate;
        }
        // Reactor builds may run from the repo root; fall back to the module-qualified path.
        return moduleDir.resolve(Paths.get("jbct", "slice-processor-tests")).resolve(relative);
    }

    /// The adapter takes the runtime's single erased carrier, casts the payload to the subscriber's
    /// declared type, and restores the two-argument call.
    @Test
    void generatedAdapter_unpacksContextualEvent() {
        assertThat(factory).contains("contextual -> delegate.onOrderPlaced((OrderPlaced) contextual.event(), contextual.context())");
    }

    /// The dispatch-side request type is the carrier, not the bare event: this is what tells the
    /// runtime to hand over an envelope-derived context rather than a plain payload.
    @Test
    void generatedAdapter_declaresContextualEventAsRequestType() {
        assertThat(factory).contains("new TypeToken<ContextualEvent>() {}");
    }

    /// The MessageContext is injected by the dispatcher, not carried in a request record. A
    /// synthesized `OnOrderPlacedRequest` would mean the processor treated it as a business
    /// parameter — the concrete regression `payloadParameters()` exists to prevent.
    @Test
    void generatedFactory_synthesizesNoRequestRecord() {
        assertThat(factory).doesNotContain("OnOrderPlacedRequest");
        assertThat(factory).doesNotContain("record OnOrderPlaced");
    }

    /// The additive manifest marker, emitted only for this shape.
    @Test
    void manifest_marksSubscriptionAsContextCarrying() {
        assertThat(manifest).contains("reactive.0.category=subscription");
        assertThat(manifest).contains("reactive.0.context=message");
    }

    /// The event type still travels for codec registration — the context parameter must not have
    /// displaced it.
    @Test
    void manifest_carriesEventTypeAlongsideTheContextMarker() {
        assertThat(manifest).contains("reactive.0.messageType=com.example.durabletopic.OrderPlaced");
        assertThat(manifest).contains("reactive.0.config=order-events");
    }

    /// The manifest addition is additive: the envelope stamp stays frozen at 1000 (#386 no-bump
    /// ruling), so runtimes that predate this key keep loading the artifact.
    @Test
    void manifest_keepsEnvelopeVersionFrozen() {
        assertThat(manifest).contains("envelope.version=1000");
    }
}
