// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.pragmatica.aether.slice.resource.ResourceAddress.ResourceAddressError.General;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.serialization.Codec;

import java.util.Set;
import java.util.regex.Pattern;

/// Namespace value object — the validated naming authority component of a [ResourceAddress].
///
/// A namespace is either the reserved token `system` (framework-internal resources) or a
/// Maven-derived identifier of the form `groupId + "." + strip_suffix(artifactId, "-blueprint")`.
/// This type owns the namespace grammar, length bounds and the `system` reservation; the raw
/// string is rendered verbatim via [#value()] so the `namespace:name:version` wire form is
/// unchanged. Parse-don't-validate: an instance is the proof that the grammar holds.
@Codec public record Namespace(String value) {
    public static final String SYSTEM = "system";

    /// Namespace applied to legacy / un-namespaced topic declarations when no blueprint coordinates
    /// are available to derive one (e.g. a bare `topicName` string with no deploy context).
    public static final String DEFAULT = "default";

    public static final Set<String> RESERVED = Set.of(SYSTEM);

    // Intentional deviation from spec text `[a-z0-9._-]+`: this pattern additionally requires a
    // leading alphanumeric (first char cannot be `.`, `_`, or `-`). Stricter and safe — avoids
    // ambiguous/relative-looking namespaces; can be relaxed to the spec form if a use case needs it.
    private static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    /// Namespace charset validation, shared by app and framework construction paths.
    public static Result<Namespace> namespace(String value) {
        return Verify.ensure(value, Is::present, General.NAMESPACE_INVALID)
                     .flatMap(present -> Verify.ensure(present, Is::matches, PATTERN, General.NAMESPACE_INVALID))
                     .map(Namespace::new);
    }

    /// App-context construction: rejects the reserved `system` namespace (and any `system.*` prefix)
    /// before the charset check, so operators using `SYSTEM` or `System.audit` get the more
    /// informative "reserved" diagnostic. The reserved check is case-insensitive.
    public static Result<Namespace> appNamespace(String value) {
        return Verify.ensure(value, Namespace::isNotReserved, General.NAMESPACE_RESERVED_FOR_APPS)
                     .flatMap(Namespace::namespace);
    }

    public boolean isSystem() {
        return SYSTEM.equalsIgnoreCase(value);
    }

    public boolean isReserved() {
        return isReserved(value);
    }

    public static boolean isReserved(String value) {
        if (value == null) {
            return false;
        }
        var dot = value.indexOf('.');
        var firstSegment = dot < 0 ? value : value.substring(0, dot);
        return RESERVED.stream().anyMatch(reserved -> reserved.equalsIgnoreCase(value))
               || RESERVED.stream().anyMatch(reserved -> reserved.equalsIgnoreCase(firstSegment));
    }

    private static boolean isNotReserved(String value) {
        return !isReserved(value);
    }

    @Override public String toString() {
        return value;
    }
}
