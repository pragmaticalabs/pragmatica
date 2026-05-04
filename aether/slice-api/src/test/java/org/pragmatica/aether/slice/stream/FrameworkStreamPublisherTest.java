// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers.FrameworkStreamPublisherError;
import org.pragmatica.lang.Promise;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the sealed-SPI publisher split (spec §6.1):
/// - Framework path can construct a {@link FrameworkStreamPublisher} for a system address.
/// - App path is refused (NOT_SYSTEM_NAMESPACE) when the factory is mis-called with an app address.
/// - {@link StreamPublisher#ensureAppAddress(StreamAddress)} resolver fail-safe rejects system addresses.
/// - Publish delegates to the underlying transport publisher.
class FrameworkStreamPublisherTest {

    private static StreamAddress systemAddress() {
        return SystemStreams.CLUSTER_EVENTS;
    }

    private static StreamAddress appAddress() {
        return StreamAddress.streamAddress("com.example.app", "orders", "1.0.0").unwrap();
    }

    @Nested
    class FactoryConstruction {

        @Test
        void systemStreamPublisher_systemAddress_succeeds() {
            StreamPublisher<String> transport = event -> Promise.unitPromise();

            var result = FrameworkStreamPublishers.systemStreamPublisher(systemAddress(), transport);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isInstanceOf(FrameworkStreamPublisher.class);
        }

        @Test
        void systemStreamPublisher_appAddress_refused() {
            StreamPublisher<String> transport = event -> Promise.unitPromise();

            var result = FrameworkStreamPublishers.systemStreamPublisher(appAddress(), transport);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isEqualTo(FrameworkStreamPublisherError.General.NOT_SYSTEM_NAMESPACE));
        }
    }

    @Nested
    class PublishDelegation {

        @Test
        void publish_delegatesToTransport() {
            var captured = new AtomicReference<String>();
            StreamPublisher<String> transport = event -> {
                captured.set(event);
                return Promise.unitPromise();
            };
            var publisher = FrameworkStreamPublishers.systemStreamPublisher(systemAddress(), transport).unwrap();

            var result = publisher.publish("evt-1").await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(captured.get()).isEqualTo("evt-1");
        }

        @Test
        void publishBatch_delegatesEachEvent() {
            var count = new java.util.concurrent.atomic.AtomicInteger();
            StreamPublisher<String> transport = event -> {
                count.incrementAndGet();
                return Promise.unitPromise();
            };
            var publisher = FrameworkStreamPublishers.systemStreamPublisher(systemAddress(), transport).unwrap();

            var result = publisher.publishBatch(java.util.List.of("a", "b", "c")).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(count.get()).isEqualTo(3);
        }
    }

    @Nested
    class AppPublisherFailSafe {

        @Test
        void ensureAppAddress_systemAddress_refused() {
            var result = StreamPublisher.ensureAppAddress(systemAddress());

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause)
                    .isEqualTo(StreamPublisher.StreamPublisherError.General.SYSTEM_ADDRESS_REFUSED));
        }

        @Test
        void ensureAppAddress_appAddress_succeeds() {
            var addr = appAddress();

            var result = StreamPublisher.ensureAppAddress(addr);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isEqualTo(addr);
        }
    }

    @Nested
    class SealedHierarchy {

        @Test
        void frameworkStreamPublisher_isSealed() {
            assertThat(FrameworkStreamPublisher.class.isSealed()).isTrue();
        }

        @Test
        void frameworkStreamPublisher_permitsOnlyFrameworkRecord() {
            var permitted = FrameworkStreamPublisher.class.getPermittedSubclasses();

            assertThat(permitted).hasSize(1);
            assertThat(permitted[0].getSimpleName()).isEqualTo("SystemStreamPublisher");
            assertThat(permitted[0].getPackageName()).isEqualTo("org.pragmatica.aether.slice.stream");
        }

        @Test
        void permittedImplementation_isPackagePrivate() {
            var permitted = FrameworkStreamPublisher.class.getPermittedSubclasses()[0];
            var modifiers = permitted.getModifiers();

            assertThat(java.lang.reflect.Modifier.isPublic(modifiers))
                    .as("permitted impl must not be public — apps must not be able to reference it")
                    .isFalse();
        }

        @Test
        void cannotInstantiatePermittedImplementationViaReflection_withoutAccess() {
            var permitted = FrameworkStreamPublisher.class.getPermittedSubclasses()[0];
            var ctor = permitted.getDeclaredConstructors()[0];

            // setAccessible(false) is the default; without a setAccessible(true) bypass an external
            // package cannot instantiate. We assert the constructor itself is not public so the
            // boundary is enforced at compile time for normal references.
            assertThat(java.lang.reflect.Modifier.isPublic(ctor.getModifiers())).isFalse();
        }
    }
}
