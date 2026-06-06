// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceAddress.ResourceAddressError;
import org.pragmatica.aether.slice.resource.ResourceVersion.ResourceVersionError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Codec;

import java.util.Set;


/// Three-component pub/sub topic address: `<namespace>:<topic>:<version>`.
///
/// Namespace is either the reserved token `system` (framework-internal topics)
/// or a Maven-derived identifier of the form `groupId + "." + artifactId`.
///
/// This is the topic-flavored view of the resource-generic [ResourceAddress]: it keeps a flat
/// `(namespace, topic, version)` record shape (so its `@Codec` wire form is unchanged) and its
/// own [TopicAddressError] vocabulary and `topic()` accessor, but delegates all grammar,
/// validation, and `system`-namespace reservation to [ResourceAddress]. It carries no rules of
/// its own — [ResourceAddress] is the single source of truth. The distinct nominal type prevents
/// a stream address from being passed where a topic address is expected.
@Codec public record TopicAddress(String namespace, String topic, TopicVersion version) {
    public static final String SYSTEM_NAMESPACE = ResourceAddress.SYSTEM_NAMESPACE;

    public static final Set<String> RESERVED_NAMESPACES = ResourceAddress.RESERVED_NAMESPACES;

    public static final Set<String> RESERVED_TOPIC_NAMES = ResourceAddress.RESERVED_NAMES;

    /// Namespace applied to legacy / un-namespaced topic declarations when no blueprint coordinates
    /// are available to derive one (e.g. a bare `topicName` string with no deploy context). The
    /// deploy path replaces this with the blueprint-derived namespace; this constant is only the
    /// floor that keeps a bare name resolving to a valid `namespace:topic:version`.
    public static final String DEFAULT_NAMESPACE = "default";

    public sealed interface TopicAddressError extends Cause {
        enum General implements TopicAddressError {
            NULL_VALUE("Topic address cannot be null"),
            BLANK_VALUE("Topic address cannot be blank"),
            WRONG_FORMAT("Topic address must match namespace:topic:version"),
            NAMESPACE_INVALID("Topic namespace contains invalid characters or is empty"),
            NAMESPACE_RESERVED_FOR_APPS("Namespace 'system' (and any 'system.*' prefix) is reserved for framework use"),
            TOPIC_NAME_INVALID("Topic name must be kebab-case (lowercase, no leading/trailing/double hyphen)"),
            TOPIC_NAME_RESERVED("Topic name is reserved");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements TopicAddressError {
            @Override public String message() {
                return "";
            }
        }
    }

    /// Parse a canonical three-component address.
    public static Result<TopicAddress> topicAddress(String value) {
        return ResourceAddress.resourceAddress(value)
                              .map(TopicAddress::from)
                              .mapError(TopicAddress::mapError);
    }

    /// Build an address from raw components. Used by both the string parser and callers
    /// that already hold parsed namespace/topic tokens (e.g., blueprint tooling).
    public static Result<TopicAddress> topicAddress(String namespace, String topic, String version) {
        return ResourceAddress.resourceAddress(namespace, topic, version)
                              .map(TopicAddress::from)
                              .mapError(TopicAddress::mapError);
    }

    public static Result<TopicAddress> topicAddress(String namespace, String topic, TopicVersion version) {
        return ResourceAddress.resourceAddress(namespace, topic, version.toResourceVersion())
                              .map(TopicAddress::from)
                              .mapError(TopicAddress::mapError);
    }

    /// Adapt a resource-generic address to the topic-flavored view.
    public static TopicAddress from(ResourceAddress address) {
        return new TopicAddress(address.namespace(), address.name(), TopicVersion.from(address.version()));
    }

    /// The resource-generic view of this address.
    public ResourceAddress toResourceAddress() {
        return new ResourceAddress(namespace, topic, version.toResourceVersion());
    }

    /// Namespace validation used by both address parsing and jbct blueprint build-time checks.
    /// Rejects the reserved `system` namespace (and any `system.*` prefix) when called via an app
    /// context. See [ResourceAddress#validateAppNamespace(String)] for the shared rule.
    public static Result<String> validateAppNamespace(String namespace) {
        return ResourceAddress.validateAppNamespace(namespace).mapError(TopicAddress::mapError);
    }

    public static boolean isReservedNamespace(String namespace) {
        return ResourceAddress.isReservedNamespace(namespace);
    }

    /// Framework-only construction path that accepts the reserved `system` namespace.
    public static Result<TopicAddress> systemTopic(String topic, TopicVersion version) {
        return topicAddress(SYSTEM_NAMESPACE, topic, version);
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
        return namespace + ":" + topic + ":" + version.asString();
    }

    /// Translate a shared [ResourceAddressError] into the topic-flavored vocabulary so that
    /// callers (and tests) observe [TopicAddressError] constants regardless of the delegate.
    /// Version-grammar failures are re-flavored to [TopicVersion.TopicVersionError] so the
    /// topic surface never leaks resource-generic version errors.
    private static Cause mapError(Cause cause) {
        return switch (cause) {
            case ResourceAddressError.General general -> switch (general) {
                case NULL_VALUE -> TopicAddressError.General.NULL_VALUE;
                case BLANK_VALUE -> TopicAddressError.General.BLANK_VALUE;
                case WRONG_FORMAT -> TopicAddressError.General.WRONG_FORMAT;
                case NAMESPACE_INVALID -> TopicAddressError.General.NAMESPACE_INVALID;
                case NAMESPACE_RESERVED_FOR_APPS -> TopicAddressError.General.NAMESPACE_RESERVED_FOR_APPS;
                case NAME_INVALID -> TopicAddressError.General.TOPIC_NAME_INVALID;
                case NAME_RESERVED -> TopicAddressError.General.TOPIC_NAME_RESERVED;
            };
            case ResourceVersionError _ -> TopicVersion.mapVersionError(cause);
            default -> cause;
        };
    }
}
