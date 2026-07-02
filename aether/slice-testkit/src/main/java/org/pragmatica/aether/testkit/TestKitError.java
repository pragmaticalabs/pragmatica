// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;


/// Failures surfaced by the slice test kit. Message style mirrors
/// [org.pragmatica.aether.slice.SliceLoadingFailure] / `SpiResourceProvider` so a kit failure reads
/// like a real slice-loading failure.
public sealed interface TestKitError extends Cause {
    /// A slice asked for a resource coordinate that has no fake or container registered — the
    /// fail-fast diagnostic (spec §7.1 MVP-6). Lists the exact `(resourceType, section)` the slice
    /// requested through `ctx.resources().provide(...)`.
    record MissingResource(String resourceType, String configSection) implements TestKitError {
        @Override
        public String message() {
            return "No fake or container registered for " + resourceType + ":" + configSection
                 + ". Register one with withResource(...)/withContainer(...) or a typed helper (withHttp/withPublisher/withNotifications) before build().";
        }
    }

    /// A `.withContainer(...)` path was taken but the optional Testcontainers / db-async classes are
    /// not on the classpath (spec §6.1 Option A guard).
    record ContainerSupportMissing(String detail) implements TestKitError {
        @Override
        public String message() {
            return "Testcontainer support is unavailable: " + detail
                 + ". Add org.testcontainers:testcontainers, org.testcontainers:postgresql and resource-db-async to the test classpath to use .withContainer(...).";
        }
    }

    /// A fake was invoked with an interaction it was not scripted for (e.g. an unscripted SQL read or
    /// HTTP path). Keeps the fake honest — it never invents a result.
    record UnscriptedInteraction(String detail) implements TestKitError {
        @Override
        public String message() {
            return detail;
        }
    }

    /// Applying the slice's `schema/` migrations to a container failed.
    record SchemaApplicationFailed(String location, Cause causeSource) implements TestKitError {
        @Override
        public String message() {
            return "Failed to apply schema migrations from '" + location + "': " + causeSource.message();
        }

        @Override
        public Option<Cause> source() {
            return some(causeSource);
        }
    }
}
