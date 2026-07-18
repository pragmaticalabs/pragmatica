// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.resource;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;


/// Three-component resource address: `<namespace>:<name>:<version>`.
///
/// This is the single, uniform naming type for addressing across Aether — streams,
/// pub/sub topics, and any future addressable resource share this exact type, its grammar,
/// validation, `system`-namespace reservation, and ordering. The stream-vs-topic domain
/// distinction lives in the registry KEY types (`StreamRegistryKey` vs `TopicSubscriptionKey`),
/// not in the address: a resource address is a uniform naming scheme, reused directly.
///
/// The three components are dedicated value objects: [Namespace] owns the namespace grammar and the
/// `system` reservation, [ResourceName] owns the kebab-case name grammar, and [ResourceVersion]
/// owns the MAJOR.MINOR.PATCH grammar. Each VO is the proof that its component is valid, so this
/// type only composes them — the string form `namespace:name:version` is rendered by delegating to
/// each VO's raw value, keeping the KV/TOML wire form unchanged.
@Codec
public record ResourceAddress(Namespace namespace, ResourceName name, ResourceVersion version) {
    public static final String SYSTEM_NAMESPACE = Namespace.SYSTEM;
    /// Namespace applied to legacy / un-namespaced topic declarations when no blueprint coordinates
    /// are available to derive one (e.g. a bare `topicName` string with no deploy context). The
    /// deploy path replaces this with the blueprint-derived namespace; this constant is only the
    /// floor that keeps a bare name resolving to a valid `namespace:name:version`.
    public static final String DEFAULT_NAMESPACE = Namespace.DEFAULT;

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
            @Override
            public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused")
        record unused() implements ResourceAddressError {
            @Override
            public String message() {
                return "";
            }
        }
    }

    /// Parse a canonical three-component address.
    @SuppressWarnings("JBCT-RET-06")  // parse-don't-validate factory: distinguishes null (NULL_VALUE) from blank (BLANK_VALUE), which Verify.Is.present conflates
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

    /// Build an address from raw component strings. Used by both the string parser and callers
    /// that already hold parsed namespace/name tokens (e.g., blueprint tooling).
    public static Result<ResourceAddress> resourceAddress(String namespace, String name, String version) {
        return ResourceVersion.resourceVersion(version).flatMap(v -> resourceAddress(namespace, name, v));
    }

    public static Result<ResourceAddress> resourceAddress(String namespace, String name, ResourceVersion version) {
        return Namespace.namespace(namespace).flatMap(ns -> ResourceName.resourceName(name).map(nm -> new ResourceAddress(ns,
                                                                                                                          nm,
                                                                                                                          version)));
    }

    /// Build an address from already-validated VO components.
    public static Result<ResourceAddress> resourceAddress(Namespace namespace,
                                                          ResourceName name,
                                                          ResourceVersion version) {
        return Result.success(new ResourceAddress(namespace, name, version));
    }

    /// Namespace validation used by both address parsing and jbct blueprint build-time checks.
    /// Rejects the reserved `system` namespace (and any `system.*` prefix) when called via an app
    /// context (see [#systemNamespace()] for framework-internal construction).
    public static Result<String> validateAppNamespace(String namespace) {
        return Namespace.appNamespace(namespace).map(Namespace::value);
    }

    public static boolean isReservedNamespace(String namespace) {
        return Namespace.isReserved(namespace);
    }

    /// Framework-only construction path that accepts the reserved `system` namespace.
    public static Result<ResourceAddress> systemResource(String name, ResourceVersion version) {
        return resourceAddress(SYSTEM_NAMESPACE, name, version);
    }

    public static String systemNamespace() {
        return SYSTEM_NAMESPACE;
    }

    public boolean isSystem() {
        return namespace.isSystem();
    }

    @Override
    public String toString() {
        return asString();
    }

    public String asString() {
        return namespace.value() + ":" + name.value() + ":" + version.asString();
    }
}
