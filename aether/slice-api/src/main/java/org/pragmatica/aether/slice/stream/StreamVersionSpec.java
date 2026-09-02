// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Version specification as declared by a stream resource in `resources.toml`.
///
/// Two forms:
///  - [Exact] — pin to a specific `MAJOR.MINOR.PATCH` triplet.
///  - [Latest] — resolve to the latest registered version at subscribe time
///    (consumers only; producers must always pin).
///
/// Distinct from [ResourceVersion], which represents a concrete version.
/// A [StreamVersionSpec] captures the user's intent (a pin vs. latest marker);
/// the name-mapper resolves [Latest] into a [ResourceVersion] per spec §9.2.
public sealed interface StreamVersionSpec {
    String LATEST_TOKEN = "latest";

    sealed interface StreamVersionSpecError extends Cause {
        enum General implements StreamVersionSpecError {
            NULL_VALUE("Stream version specification cannot be null"),
            BLANK_VALUE("Stream version specification cannot be blank");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused")
        record unused() implements StreamVersionSpecError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    String asString();
    boolean isLatest();

    /// Exact version pin.
    record Exact(ResourceVersion version) implements StreamVersionSpec {
        @Override
        public String asString() {
            return version.asString();
        }

        @Override
        public boolean isLatest() {
            return false;
        }
    }

    /// Latest marker — resolved at subscribe time.
    enum Latest implements StreamVersionSpec {
        INSTANCE;
        @Override
        public String asString() {
            return LATEST_TOKEN;
        }
        @Override
        public boolean isLatest() {
            return true;
        }
    }

    @SuppressWarnings("JBCT-RET-06")  // parse-don't-validate factory: distinguishes null (NULL_VALUE) from blank (BLANK_VALUE), which Verify.Is.present conflates
    static Result<StreamVersionSpec> streamVersionSpec(String value) {
        if (value == null) {
            return StreamVersionSpecError.General.NULL_VALUE.result();
        }

        if (value.isBlank()) {
            return StreamVersionSpecError.General.BLANK_VALUE.result();
        }

        if (LATEST_TOKEN.equalsIgnoreCase(value.trim())) {
            return success(Latest.INSTANCE);
        }

        return ResourceVersion.resourceVersion(value).map(Exact::new);
    }

    static StreamVersionSpec exact(ResourceVersion version) {
        return new Exact(version);
    }

    static StreamVersionSpec latest() {
        return Latest.INSTANCE;
    }
}
