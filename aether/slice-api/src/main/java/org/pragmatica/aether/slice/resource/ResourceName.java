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


/// Resource-name value object — the validated kebab-case name component of a [ResourceAddress].
///
/// This type owns the name grammar (kebab-case: lowercase, no leading/trailing/double hyphen),
/// the length bound and the reserved-name set. The raw string is rendered verbatim via [#value()]
/// so the `namespace:name:version` wire form is unchanged. Parse-don't-validate: an instance is the
/// proof that the grammar holds.
@Codec
public record ResourceName(String value) {
    public static final Set<String> RESERVED = Set.of("latest");
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /// Resource-name (kebab-case) validation, shared by all resource-flavored address types.
    public static Result<ResourceName> resourceName(String value) {
        return Verify.ensure(value, Is::notNull, General.NAME_INVALID)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::matches, PATTERN, General.NAME_INVALID))
                     .flatMap(matched -> Verify.ensure(matched, ResourceName::hasValidHyphens, General.NAME_INVALID))
                     .flatMap(valid -> Verify.ensure(valid, ResourceName::isNotReserved, General.NAME_RESERVED))
                     .map(ResourceName::new);
    }

    /// Spec §4.2 name rule: kebab-case must not have a leading or trailing hyphen, and must not
    /// contain a double hyphen. The `PATTERN` regex (`[a-z][a-z0-9-]{0,63}`) already forbids a
    /// leading hyphen because the first character must be `[a-z]`, but we keep the explicit check
    /// for symmetry — otherwise a future regex tweak allowing leading digits would silently let
    /// leading hyphens through.
    private static boolean hasValidHyphens(String value) {
        return ! value.startsWith("-")
               && !value.endsWith("-")
               && !value.contains("--");
    }

    private static boolean isNotReserved(String value) {
        return ! RESERVED.contains(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
