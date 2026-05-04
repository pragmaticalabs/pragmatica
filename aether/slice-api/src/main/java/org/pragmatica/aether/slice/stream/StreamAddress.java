// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.Set;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.success;


/// Three-component stream address: `<namespace>:<stream>:<version>`.
///
/// Namespace is either the reserved token `system` (framework-internal streams)
/// or a Maven-derived identifier of the form `groupId + "." + strip_suffix(artifactId, "-blueprint")`.
///
/// Stream and version syntax are defined in {@link StreamVersion} and the companion grammar.
@Codec public record StreamAddress(String namespace, String stream, StreamVersion version) {
    public static final String SYSTEM_NAMESPACE = "system";

    public static final Set<String> RESERVED_NAMESPACES = Set.of(SYSTEM_NAMESPACE);

    public static final Set<String> RESERVED_STREAM_NAMES = Set.of("latest");

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private static final Pattern STREAM_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public sealed interface StreamAddressError extends Cause {
        enum General implements StreamAddressError {
            NULL_VALUE("Stream address cannot be null"),
            BLANK_VALUE("Stream address cannot be blank"),
            WRONG_FORMAT("Stream address must match namespace:stream:version"),
            NAMESPACE_INVALID("Stream namespace contains invalid characters or is empty"),
            NAMESPACE_RESERVED_FOR_APPS("Namespace 'system' (and any 'system.*' prefix) is reserved for framework use"),
            STREAM_NAME_INVALID("Stream name must be kebab-case (lowercase, no leading/trailing/double hyphen)"),
            STREAM_NAME_RESERVED("Stream name is reserved");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements StreamAddressError {
            @Override public String message() {
                return "";
            }
        }
    }

    /// Parse a canonical three-component address.
    public static Result<StreamAddress> streamAddress(String value) {
        if (value == null) {
            return StreamAddressError.General.NULL_VALUE.result();
        }
        if (value.isBlank()) {
            return StreamAddressError.General.BLANK_VALUE.result();
        }
        var parts = value.split(":", -1);
        if (parts.length != 3) {
            return StreamAddressError.General.WRONG_FORMAT.result();
        }
        return streamAddress(parts[0], parts[1], parts[2]);
    }

    /// Build an address from raw components. Used by both the string parser and callers
    /// that already hold parsed namespace/stream tokens (e.g., blueprint tooling).
    public static Result<StreamAddress> streamAddress(String namespace, String stream, String version) {
        return StreamVersion.streamVersion(version).flatMap(v -> streamAddress(namespace, stream, v));
    }

    public static Result<StreamAddress> streamAddress(String namespace, String stream, StreamVersion version) {
        return validateNamespace(namespace)
                .flatMap(_ -> validateStream(stream))
                .map(_ -> new StreamAddress(namespace, stream, version));
    }

    /// Namespace validation used by both address parsing and jbct blueprint build-time checks.
    /// Rejects the reserved `system` namespace (and any `system.*` prefix) when called via an app
    /// context (see {@link #systemNamespace()} for framework-internal construction). The reserved
    /// check is case-insensitive and runs before the lowercase-charset check so that operators
    /// using `SYSTEM` or `System.audit` get the more informative "reserved" diagnostic.
    public static Result<String> validateAppNamespace(String namespace) {
        if (isReservedNamespace(namespace)) {
            return StreamAddressError.General.NAMESPACE_RESERVED_FOR_APPS.result();
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
    public static Result<StreamAddress> systemStream(String stream, StreamVersion version) {
        return streamAddress(SYSTEM_NAMESPACE, stream, version);
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
        return namespace + ":" + stream + ":" + version.asString();
    }

    private static Result<String> validateNamespace(String namespace) {
        if (namespace == null || namespace.isBlank() || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            return StreamAddressError.General.NAMESPACE_INVALID.result();
        }
        return success(namespace);
    }

    private static Result<String> validateStream(String stream) {
        if (stream == null || !STREAM_PATTERN.matcher(stream).matches()) {
            return StreamAddressError.General.STREAM_NAME_INVALID.result();
        }
        if (stream.endsWith("-") || stream.contains("--")) {
            return StreamAddressError.General.STREAM_NAME_INVALID.result();
        }
        if (RESERVED_STREAM_NAMES.contains(stream)) {
            return StreamAddressError.General.STREAM_NAME_RESERVED.result();
        }
        return success(stream);
    }
}
