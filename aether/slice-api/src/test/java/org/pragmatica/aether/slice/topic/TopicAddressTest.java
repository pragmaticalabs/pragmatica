// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.topic.TopicAddress.TopicAddressError.General;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.topic.TopicAddress.systemTopic;
import static org.pragmatica.aether.slice.topic.TopicAddress.topicAddress;
import static org.pragmatica.aether.slice.topic.TopicAddress.validateAppNamespace;
import static org.pragmatica.aether.slice.topic.TopicVersion.topicVersion;


class TopicAddressTest {

    private static Cause errorOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class StringParsing {

        @Test
        void parsesSystemAddress() {
            var addr = topicAddress("system:cluster-events:1.0.0").unwrap();

            assertThat(addr.namespace()).isEqualTo("system");
            assertThat(addr.topic()).isEqualTo("cluster-events");
            assertThat(addr.version()).isEqualTo(topicVersion(1, 0, 0).unwrap());
            assertThat(addr.isSystem()).isTrue();
        }

        @Test
        void parsesAppAddress() {
            var addr = topicAddress("com.example.myapp:orders:2.1.3").unwrap();

            assertThat(addr.namespace()).isEqualTo("com.example.myapp");
            assertThat(addr.topic()).isEqualTo("orders");
            assertThat(addr.version()).isEqualTo(topicVersion(2, 1, 3).unwrap());
            assertThat(addr.isSystem()).isFalse();
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(topicAddress(null))).isEqualTo(General.NULL_VALUE);
        }

        @Test
        void rejectsBlank() {
            assertThat(errorOf(topicAddress(""))).isEqualTo(General.BLANK_VALUE);
            assertThat(errorOf(topicAddress("   "))).isEqualTo(General.BLANK_VALUE);
        }

        @Test
        void rejectsTooFewComponents() {
            assertThat(errorOf(topicAddress("system:cluster-events"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsTooManyComponents() {
            assertThat(errorOf(topicAddress("a:b:1.0.0:extra"))).isEqualTo(General.WRONG_FORMAT);
        }

        @Test
        void rejectsEmptyNamespace() {
            assertThat(errorOf(topicAddress(":orders:1.0.0"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsEmptyTopic() {
            assertThat(errorOf(topicAddress("system::1.0.0"))).isEqualTo(General.TOPIC_NAME_INVALID);
        }

        @Test
        void rejectsUppercaseTopic() {
            assertThat(errorOf(topicAddress("system:ClusterEvents:1.0.0"))).isEqualTo(General.TOPIC_NAME_INVALID);
        }

        @Test
        void rejectsLeadingHyphenTopic() {
            assertThat(errorOf(topicAddress("system:-orders:1.0.0"))).isEqualTo(General.TOPIC_NAME_INVALID);
        }

        @Test
        void rejectsTrailingHyphenTopic() {
            assertThat(errorOf(topicAddress("system:orders-:1.0.0"))).isEqualTo(General.TOPIC_NAME_INVALID);
        }

        @Test
        void rejectsDoubleHyphenTopic() {
            assertThat(errorOf(topicAddress("system:or--ders:1.0.0"))).isEqualTo(General.TOPIC_NAME_INVALID);
        }

        @Test
        void rejectsReservedTopicName() {
            assertThat(errorOf(topicAddress("system:latest:1.0.0"))).isEqualTo(General.TOPIC_NAME_RESERVED);
        }

        @Test
        void rejectsMalformedVersion() {
            var error = errorOf(topicAddress("system:cluster-events:1.0"));

            assertThat(error).isInstanceOf(TopicVersion.TopicVersionError.class);
        }
    }

    @Nested
    class SystemConstruction {

        @Test
        void systemTopicAccepted() {
            var addr = systemTopic("cluster-events", topicVersion(1, 0, 0).unwrap()).unwrap();

            assertThat(addr.namespace()).isEqualTo("system");
            assertThat(addr.isSystem()).isTrue();
        }
    }

    @Nested
    class AppNamespaceValidation {

        @Test
        void acceptsMavenDerived() {
            assertThat(validateAppNamespace("com.example.myapp").isSuccess()).isTrue();
            assertThat(validateAppNamespace("io.acme.billing.invoice-service").isSuccess()).isTrue();
            assertThat(validateAppNamespace("org.pragmatica.aether.forge").isSuccess()).isTrue();
        }

        @Test
        void rejectsSystemCaseInsensitive() {
            assertThat(errorOf(validateAppNamespace("system"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("System"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("SYSTEM"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void rejectsSystemDotPrefix() {
            assertThat(errorOf(validateAppNamespace("system.audit"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
            assertThat(errorOf(validateAppNamespace("system.cluster-events"))).isEqualTo(General.NAMESPACE_RESERVED_FOR_APPS);
        }

        @Test
        void acceptsNonSystemFirstSegment() {
            assertThat(validateAppNamespace("systems.foo").isSuccess()).isTrue();
            assertThat(validateAppNamespace("systemic.thing").isSuccess()).isTrue();
        }

        @Test
        void rejectsUppercaseInNamespace() {
            assertThat(errorOf(validateAppNamespace("Com.Example.foo"))).isEqualTo(General.NAMESPACE_INVALID);
            assertThat(errorOf(validateAppNamespace("CamelCase"))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsEmpty() {
            assertThat(errorOf(validateAppNamespace(""))).isEqualTo(General.NAMESPACE_INVALID);
        }

        @Test
        void rejectsNull() {
            assertThat(errorOf(validateAppNamespace(null))).isEqualTo(General.NAMESPACE_INVALID);
        }
    }

    @Nested
    class Rendering {

        @Test
        void asStringUsesCanonicalSeparators() {
            var addr = topicAddress("com.example.myapp", "orders", topicVersion(1, 2, 3).unwrap()).unwrap();

            assertThat(addr.asString()).isEqualTo("com.example.myapp:orders:1.2.3");
        }

        @Test
        void toStringDelegatesToAsString() {
            var addr = topicAddress("system:cluster-events:1.0.0").unwrap();

            assertThat(addr.toString()).isEqualTo("system:cluster-events:1.0.0");
        }

        @Test
        void roundtripPreservesComponents() {
            var original = topicAddress("com.example.myapp:orders:1.0.0").unwrap();
            var reparsed = topicAddress(original.asString()).unwrap();

            assertThat(reparsed).isEqualTo(original);
        }

        @Test
        void defaultVersionIsOneZeroZero() {
            assertThat(TopicVersion.defaultVersion()).isEqualTo(topicVersion(1, 0, 0).unwrap());
        }
    }
}
