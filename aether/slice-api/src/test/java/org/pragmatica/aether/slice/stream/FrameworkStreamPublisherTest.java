// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublishers.FrameworkStreamPublisherError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the sealed-SPI publisher split (spec §6.1):
/// - Framework path can construct a {@link FrameworkStreamPublisher} for a system address.
/// - App path is refused (NOT_SYSTEM_NAMESPACE) when the factory is mis-called with an app address.
/// - {@link StreamPublisher#ensureAppAddress(ResourceAddress)} resolver fail-safe rejects system addresses.
/// - Publish delegates to the underlying transport publisher.
class FrameworkStreamPublisherTest {

    private static ResourceAddress systemAddress() {
        return SystemStreams.CLUSTER_EVENTS;
    }

    private static ResourceAddress appAddress() {
        return ResourceAddress.resourceAddress("com.example.app", "orders", "1.0.0").unwrap();
    }

    @Nested
    class FactoryConstruction {

        @Test
        void systemStreamPublisher_systemAddress_succeeds() {
            var result = FrameworkStreamPublishers.systemStreamPublisher(systemAddress(), transport(_ -> {}));

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isInstanceOf(FrameworkStreamPublisher.class);
        }

        @Test
        void systemStreamPublisher_appAddress_refused() {
            var result = FrameworkStreamPublishers.systemStreamPublisher(appAddress(), transport(_ -> {}));

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isEqualTo(FrameworkStreamPublisherError.General.NOT_SYSTEM_NAMESPACE));
        }
    }

    @Nested
    class PublishDelegation {

        @Test
        void publish_delegatesToTransport() {
            var captured = new AtomicReference<String>();
            var publisher = FrameworkStreamPublishers.systemStreamPublisher(systemAddress(), transport(captured::set)).unwrap();

            var result = publisher.publish("evt-1").await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(captured.get()).isEqualTo("evt-1");
        }

        /// #1342: the batch is delegated AS a batch and the transport's per-event outcomes come back unchanged —
        /// the inherited default had re-published each event and folded the results into one `Unit`, so a
        /// refused event was acknowledged as success.
        @Test
        void publishBatch_delegatesTheBatchToTransport_andCarriesEveryPerEventOutcome() {
            var batches = new ArrayList<List<String>>();
            var publisher = FrameworkStreamPublishers.systemStreamPublisher(systemAddress(), transport(batches::add, REFUSED_B)).unwrap();

            var outcomes = publisher.publishBatch(List.of("a", "b", "c")).await().unwrap();

            assertThat(batches).containsExactly(List.of("a", "b", "c"));
            assertThat(outcomes).containsExactly(new PublishOutcome.Published(0L),
                                                 new PublishOutcome.OutcomeUnknown(REFUSED_B),
                                                 new PublishOutcome.Published(2L));
        }
    }

    private static final Cause REFUSED_B = Causes.cause("transport refused b");

    private static StreamPublisher<String> transport(Consumer<String> onPublish) {
        return transport(_ -> {}, onPublish, Option.none());
    }

    private static StreamPublisher<String> transport(Consumer<List<String>> onBatch, Cause refusedB) {
        return transport(onBatch, _ -> {}, Option.some(refusedB));
    }

    /// A transport that refuses the event `"b"` with `refusedB` when given one; otherwise every event is
    /// published at its batch index.
    private static StreamPublisher<String> transport(Consumer<List<String>> onBatch, Consumer<String> onPublish, Option<Cause> refusedB) {
        return new StreamPublisher<>() {
            @Override
            public Promise<Unit> publish(String event) {
                onPublish.accept(event);

                return Promise.unitPromise();
            }

            @Override
            public Promise<List<PublishOutcome>> publishBatch(List<String> events) {
                onBatch.accept(events);

                return Promise.success(IntStream.range(0, events.size())
                                                .mapToObj(index -> outcome(index, events.get(index)))
                                                .toList());
            }

            private PublishOutcome outcome(int index, String event) {
                return event.equals("b")
                       ? refusedB.<PublishOutcome> map(PublishOutcome.OutcomeUnknown::new)
                                 .or(() -> new PublishOutcome.Published(index))
                       : new PublishOutcome.Published(index);
            }
        };
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
        void frameworkStreamPublisher_permitsOnlyFrameworkRecords() {
            var permitted = FrameworkStreamPublisher.class.getPermittedSubclasses();

            assertThat(permitted).hasSize(2);
            assertThat(permitted).extracting(Class::getSimpleName)
                                 .containsExactlyInAnyOrder("SystemStreamPublisher", "TestSystemStreamPublisher");
            assertThat(permitted).allSatisfy(c -> assertThat(c.getPackageName()).isEqualTo("org.pragmatica.aether.slice.stream"));
        }

        @Test
        void permittedImplementations_arePackagePrivate() {
            var permitted = FrameworkStreamPublisher.class.getPermittedSubclasses();

            assertThat(permitted).allSatisfy(c -> assertThat(java.lang.reflect.Modifier.isPublic(c.getModifiers()))
                    .as("permitted impl %s must not be public — apps must not be able to reference it", c.getSimpleName())
                    .isFalse());
        }

        @Test
        void cannotInstantiateProductionPermittedImplementationViaReflection_withoutAccess() {
            var permitted = java.util.Arrays.stream(FrameworkStreamPublisher.class.getPermittedSubclasses())
                                            .filter(c -> c.getSimpleName().equals("SystemStreamPublisher"))
                                            .findFirst()
                                            .orElseThrow();
            var ctor = permitted.getDeclaredConstructors()[0];

            // setAccessible(false) is the default; without a setAccessible(true) bypass an external
            // package cannot instantiate. We assert the constructor itself is not public so the
            // boundary is enforced at compile time for normal references.
            assertThat(java.lang.reflect.Modifier.isPublic(ctor.getModifiers())).isFalse();
        }
    }
}
