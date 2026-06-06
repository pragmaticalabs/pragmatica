// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceAddress.ResourceAddressError;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.Set;


/// Three-component stream address: `<namespace>:<stream>:<version>`.
///
/// Namespace is either the reserved token `system` (framework-internal streams)
/// or a Maven-derived identifier of the form `groupId + "." + strip_suffix(artifactId, "-blueprint")`.
///
/// This is the stream-flavored view of the resource-generic [ResourceAddress]: it keeps a flat
/// `(namespace, stream, version)` record shape (so its `@Codec` wire form is unchanged) and its
/// own [StreamAddressError] vocabulary and `stream()` accessor, but delegates all grammar,
/// validation, and `system`-namespace reservation to [ResourceAddress]. It carries no rules of
/// its own — [ResourceAddress] is the single source of truth. The distinct nominal type prevents
/// a (future) topic address from being passed where a stream address is expected.
@Codec public record StreamAddress(String namespace, String stream, StreamVersion version) {
    public static final String SYSTEM_NAMESPACE = ResourceAddress.SYSTEM_NAMESPACE;

    public static final Set<String> RESERVED_NAMESPACES = ResourceAddress.RESERVED_NAMESPACES;

    public static final Set<String> RESERVED_STREAM_NAMES = ResourceAddress.RESERVED_NAMES;

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
        return ResourceAddress.resourceAddress(value)
                              .map(StreamAddress::from)
                              .mapError(StreamAddress::mapError);
    }

    /// Build an address from raw components. Used by both the string parser and callers
    /// that already hold parsed namespace/stream tokens (e.g., blueprint tooling).
    public static Result<StreamAddress> streamAddress(String namespace, String stream, String version) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .map(StreamAddress::from)
                              .mapError(StreamAddress::mapError);
    }

    public static Result<StreamAddress> streamAddress(String namespace, String stream, StreamVersion version) {
        return ResourceAddress.resourceAddress(namespace, stream, version.toResourceVersion())
                              .map(StreamAddress::from)
                              .mapError(StreamAddress::mapError);
    }

    /// Adapt a resource-generic address to the stream-flavored view.
    public static StreamAddress from(ResourceAddress address) {
        return new StreamAddress(address.namespace(), address.name(), StreamVersion.from(address.version()));
    }

    /// The resource-generic view of this address.
    public ResourceAddress toResourceAddress() {
        return new ResourceAddress(namespace, stream, version.toResourceVersion());
    }

    /// Namespace validation used by both address parsing and jbct blueprint build-time checks.
    /// Rejects the reserved `system` namespace (and any `system.*` prefix) when called via an app
    /// context. See [ResourceAddress#validateAppNamespace(String)] for the shared rule.
    public static Result<String> validateAppNamespace(String namespace) {
        return ResourceAddress.validateAppNamespace(namespace).mapError(StreamAddress::mapError);
    }

    public static boolean isReservedNamespace(String namespace) {
        return ResourceAddress.isReservedNamespace(namespace);
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

    /// Translate a shared [ResourceAddressError] into the stream-flavored vocabulary so that
    /// callers (and tests) observe [StreamAddressError] constants regardless of the delegate.
    /// Version-grammar failures are re-flavored to [StreamVersion.StreamVersionError] so the
    /// stream surface never leaks resource-generic version errors.
    private static Cause mapError(Cause cause) {
        return switch (cause) {
            case ResourceAddressError.General general -> switch (general) {
                case NULL_VALUE -> StreamAddressError.General.NULL_VALUE;
                case BLANK_VALUE -> StreamAddressError.General.BLANK_VALUE;
                case WRONG_FORMAT -> StreamAddressError.General.WRONG_FORMAT;
                case NAMESPACE_INVALID -> StreamAddressError.General.NAMESPACE_INVALID;
                case NAMESPACE_RESERVED_FOR_APPS -> StreamAddressError.General.NAMESPACE_RESERVED_FOR_APPS;
                case NAME_INVALID -> StreamAddressError.General.STREAM_NAME_INVALID;
                case NAME_RESERVED -> StreamAddressError.General.STREAM_NAME_RESERVED;
            };
            case ResourceVersionError _ -> StreamVersion.mapVersionError(cause);
            default -> cause;
        };
    }
}
