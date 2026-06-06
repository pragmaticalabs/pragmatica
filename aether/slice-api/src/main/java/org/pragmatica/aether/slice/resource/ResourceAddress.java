// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.Set;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;


/// Three-component resource address: `<namespace>:<name>:<version>`.
///
/// This is the single, uniform naming type for addressing across Aether — streams,
/// pub/sub topics, and any future addressable resource share this exact type, its grammar,
/// validation, `system`-namespace reservation, and ordering. The stream-vs-topic domain
/// distinction lives in the registry KEY types (`StreamRegistryKey` vs `TopicSubscriptionKey`),
/// not in the address: a resource address is a uniform naming scheme, reused directly.
///
/// Namespace is either the reserved token `system` (framework-internal resources)
/// or a Maven-derived identifier of the form `groupId + "." + strip_suffix(artifactId, "-blueprint")`.
@Codec public record ResourceAddress(String namespace, String name, ResourceVersion version) {
    public static final String SYSTEM_NAMESPACE = "system";

    /// Namespace applied to legacy / un-namespaced topic declarations when no blueprint coordinates
    /// are available to derive one (e.g. a bare `topicName` string with no deploy context). The
    /// deploy path replaces this with the blueprint-derived namespace; this constant is only the
    /// floor that keeps a bare name resolving to a valid `namespace:name:version`.
    public static final String DEFAULT_NAMESPACE = "default";

    public static final Set<String> RESERVED_NAMESPACES = Set.of(SYSTEM_NAMESPACE);

    public static final Set<String> RESERVED_NAMES = Set.of("latest");

    // Intentional deviation from spec text `[a-z0-9._-]+`: this pattern additionally requires a
    // leading alphanumeric (first char cannot be `.`, `_`, or `-`). Stricter and safe — avoids
    // ambiguous/relative-looking namespaces; can be relaxed to the spec form if a use case needs it.
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public sealed interface ResourceAddressError extends Cause {
        enum General implements ResourceAddressError {
            NULL_VALUE("Resource address cannot be null"),
            BLANK_VALUE("Resource address cannot be blank"),
            WRONG_FORMAT("Resource address must match namespace:name:version"),
            NAMESPACE_INVALID("Resource namespace contains invalid characters or is empty"),
            NAMESPACE_RESERVED_FOR_APPS("Namespace 'system' (and any 'system.*' prefix) is reserved for framework use"),
            NAME_INVALID("Resource name must be kebab-case (lowercase, no leading/trailing/double hyphen)"),
            NAME_RESERVED("Resource name is reserved");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements ResourceAddressError {
            @Override public String message() {
                return "";
            }
        }
    }

    /// Parse a canonical three-component address.
    public static Result<ResourceAddress> resourceAddress(String value) {
        if (value == null) {
            return ResourceAddressError.General.NULL_VALUE.result();
        }
        if (value.isBlank()) {
            return ResourceAddressError.General.BLANK_VALUE.result();
        }
        var parts = value.split(":", -1);
        if (parts.length != 3) {
            return ResourceAddressError.General.WRONG_FORMAT.result();
        }
        return resourceAddress(parts[0], parts[1], parts[2]);
    }

    /// Build an address from raw components. Used by both the string parser and callers
    /// that already hold parsed namespace/name tokens (e.g., blueprint tooling).
    public static Result<ResourceAddress> resourceAddress(String namespace, String name, String version) {
        return ResourceVersion.resourceVersion(version).flatMap(v -> resourceAddress(namespace, name, v));
    }

    public static Result<ResourceAddress> resourceAddress(String namespace, String name, ResourceVersion version) {
        return validateNamespace(namespace)
                .flatMap(_ -> validateName(name))
                .map(_ -> new ResourceAddress(namespace, name, version));
    }

    /// Namespace validation used by both address parsing and jbct blueprint build-time checks.
    /// Rejects the reserved `system` namespace (and any `system.*` prefix) when called via an app
    /// context (see [#systemNamespace()] for framework-internal construction). The reserved
    /// check is case-insensitive and runs before the lowercase-charset check so that operators
    /// using `SYSTEM` or `System.audit` get the more informative "reserved" diagnostic.
    public static Result<String> validateAppNamespace(String namespace) {
        if (isReservedNamespace(namespace)) {
            return ResourceAddressError.General.NAMESPACE_RESERVED_FOR_APPS.result();
        }
        return validateNamespace(namespace);
    }

    public static boolean isReservedNamespace(String namespace) {
        if (namespace == null) {
            return false;
        }
        if (RESERVED_NAMESPACES.stream().anyMatch(r -> r.equalsIgnoreCase(namespace))) {
            return true;
        }
        var dot = namespace.indexOf('.');
        var firstSegment = dot < 0 ? namespace : namespace.substring(0, dot);
        return RESERVED_NAMESPACES.stream().anyMatch(r -> r.equalsIgnoreCase(firstSegment));
    }

    /// Framework-only construction path that accepts the reserved `system` namespace.
    public static Result<ResourceAddress> systemResource(String name, ResourceVersion version) {
        return resourceAddress(SYSTEM_NAMESPACE, name, version);
    }

    public static String systemNamespace() {
        return SYSTEM_NAMESPACE;
    }

    public boolean isSystem() {
        return SYSTEM_NAMESPACE.equalsIgnoreCase(namespace);
    }

    @Override public String toString() {
        return asString();
    }

    public String asString() {
        return namespace + ":" + name + ":" + version.asString();
    }

    /// Namespace charset validation, shared by app and framework construction paths.
    public static Result<String> validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank() || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            return ResourceAddressError.General.NAMESPACE_INVALID.result();
        }
        return success(namespace);
    }

    /// Resource-name (kebab-case) validation, shared by all resource-flavored address types.
    public static Result<String> validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return ResourceAddressError.General.NAME_INVALID.result();
        }
        if (hasInvalidHyphenPlacement(name)) {
            return ResourceAddressError.General.NAME_INVALID.result();
        }
        if (RESERVED_NAMES.contains(name)) {
            return ResourceAddressError.General.NAME_RESERVED.result();
        }
        return success(name);
    }

    /// Spec §4.2 name rule: kebab-case must not have a leading or trailing hyphen, and
    /// must not contain a double hyphen. The `NAME_PATTERN` regex (`[a-z][a-z0-9-]{0,63}`)
    /// already forbids a leading hyphen because the first character must be `[a-z]`, but we keep
    /// the explicit check here for symmetry with the `endsWith("-")` and `contains("--")`
    /// branches — otherwise a future regex tweak (e.g., dropping the leading-letter constraint to
    /// allow leading digits) would silently let leading hyphens through.
    private static boolean hasInvalidHyphenPlacement(String name) {
        return name.startsWith("-") || name.endsWith("-") || name.contains("--");
    }
}
