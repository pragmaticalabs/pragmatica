// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumers.FrameworkStreamConsumerError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the sealed-SPI consumer split (spec §6.1):
/// - Framework path can construct a {@link FrameworkStreamConsumer} for a system address.
/// - App path is refused (NOT_SYSTEM_NAMESPACE) when the factory is mis-called with an app address.
/// - {@link StreamAccess#ensureAppAddress(StreamAddress)} resolver fail-safe rejects system addresses.
/// - Read methods delegate to the underlying transport.
class FrameworkStreamConsumerTest {

    private static StreamAddress systemAddress() {
        return SystemStreams.CLUSTER_EVENTS;
    }

    private static StreamAddress appAddress() {
        return StreamAddress.streamAddress("com.example.app", "orders", "1.0.0").unwrap();
    }

    private static <T> StreamAccess<T> stubTransport() {
        return new StreamAccess<>() {
            @Override public Promise<Long> publish(T event) {
                return Promise.success(0L);
            }

            @Override public Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents) {
                return Promise.success(List.of());
            }

            @Override public Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents) {
                return Promise.success(List.of());
            }

            @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
                return Promise.unitPromise();
            }

            @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
                return Promise.success(Option.none());
            }

            @Override public Promise<StreamMetadata> metadata() {
                return Promise.success(new StreamMetadata("stub", 1, List.of()));
            }
        };
    }

    @Nested
    class FactoryConstruction {

        @Test
        void systemStreamConsumer_systemAddress_succeeds() {
            StreamAccess<String> transport = stubTransport();

            var result = FrameworkStreamConsumers.systemStreamConsumer(systemAddress(), transport);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isInstanceOf(FrameworkStreamConsumer.class);
        }

        @Test
        void systemStreamConsumer_appAddress_refused() {
            StreamAccess<String> transport = stubTransport();

            var result = FrameworkStreamConsumers.systemStreamConsumer(appAddress(), transport);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isEqualTo(FrameworkStreamConsumerError.General.NOT_SYSTEM_NAMESPACE));
        }
    }

    @Nested
    class ReadDelegation {

        @Test
        void fetch_byOffset_delegatesToTransport() {
            var capturedFromOffset = new AtomicReference<Long>();
            var capturedMaxEvents = new AtomicReference<Integer>();
            StreamAccess<String> transport = new DelegatingStub<>() {
                @Override public Promise<List<StreamEvent<String>>> fetch(long fromOffset, int maxEvents) {
                    capturedFromOffset.set(fromOffset);
                    capturedMaxEvents.set(maxEvents);
                    return Promise.success(List.of(new StreamEvent<>(7L, 100L, 0, "evt-7")));
                }
            };
            var consumer = FrameworkStreamConsumers.systemStreamConsumer(systemAddress(), transport).unwrap();

            var result = consumer.fetch(5L, 10).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(capturedFromOffset.get()).isEqualTo(5L);
            assertThat(capturedMaxEvents.get()).isEqualTo(10);
            result.onSuccess(events -> assertThat(events).hasSize(1));
        }

        @Test
        void fetch_byPartitionAndOffset_delegatesToTransport() {
            var capturedPartition = new AtomicReference<Integer>();
            StreamAccess<String> transport = new DelegatingStub<>() {
                @Override public Promise<List<StreamEvent<String>>> fetch(int partition, long fromOffset, int maxEvents) {
                    capturedPartition.set(partition);
                    return Promise.success(List.of());
                }
            };
            var consumer = FrameworkStreamConsumers.systemStreamConsumer(systemAddress(), transport).unwrap();

            var result = consumer.fetch(3, 0L, 50).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(capturedPartition.get()).isEqualTo(3);
        }

        @Test
        void commit_delegatesToTransport() {
            var capturedGroup = new AtomicReference<String>();
            var capturedOffset = new AtomicReference<Long>();
            StreamAccess<String> transport = new DelegatingStub<>() {
                @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
                    capturedGroup.set(consumerGroup);
                    capturedOffset.set(offset);
                    return Promise.unitPromise();
                }
            };
            var consumer = FrameworkStreamConsumers.systemStreamConsumer(systemAddress(), transport).unwrap();

            var result = consumer.commit("group-a", 0, 42L).await();

            assertThat(result.isSuccess()).isTrue();
            assertThat(capturedGroup.get()).isEqualTo("group-a");
            assertThat(capturedOffset.get()).isEqualTo(42L);
        }

        @Test
        void committedOffset_delegatesToTransport() {
            StreamAccess<String> transport = new DelegatingStub<>() {
                @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
                    return Promise.success(Option.some(99L));
                }
            };
            var consumer = FrameworkStreamConsumers.systemStreamConsumer(systemAddress(), transport).unwrap();

            var result = consumer.committedOffset("group-a", 0).await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(opt -> assertThat(opt).isEqualTo(Option.some(99L)));
        }

        @Test
        void metadata_delegatesToTransport() {
            StreamAccess<String> transport = new DelegatingStub<>() {
                @Override public Promise<StreamMetadata> metadata() {
                    return Promise.success(new StreamMetadata("system-stream", 4, List.of()));
                }
            };
            var consumer = FrameworkStreamConsumers.systemStreamConsumer(systemAddress(), transport).unwrap();

            var result = consumer.metadata().await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(meta -> {
                assertThat(meta.streamName()).isEqualTo("system-stream");
                assertThat(meta.partitionCount()).isEqualTo(4);
            });
        }
    }

    @Nested
    class AppStreamAccessFailSafe {

        @Test
        void ensureAppAddress_systemAddress_refused() {
            var result = StreamAccess.ensureAppAddress(systemAddress());

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause)
                    .isEqualTo(StreamAccess.StreamAccessError.General.SYSTEM_ADDRESS_REFUSED));
        }

        @Test
        void ensureAppAddress_appAddress_succeeds() {
            var addr = appAddress();

            var result = StreamAccess.ensureAppAddress(addr);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.unwrap()).isEqualTo(addr);
        }
    }

    @Nested
    class SealedHierarchy {

        @Test
        void frameworkStreamConsumer_isSealed() {
            assertThat(FrameworkStreamConsumer.class.isSealed()).isTrue();
        }

        @Test
        void frameworkStreamConsumer_permitsOnlyFrameworkRecords() {
            var permitted = FrameworkStreamConsumer.class.getPermittedSubclasses();

            assertThat(permitted).hasSize(2);
            assertThat(permitted).extracting(Class::getSimpleName)
                                 .containsExactlyInAnyOrder("SystemStreamConsumer", "TestSystemStreamConsumer");
            assertThat(permitted).allSatisfy(c -> assertThat(c.getPackageName()).isEqualTo("org.pragmatica.aether.slice.stream"));
        }

        @Test
        void permittedImplementations_arePackagePrivate() {
            var permitted = FrameworkStreamConsumer.class.getPermittedSubclasses();

            assertThat(permitted).allSatisfy(c -> assertThat(java.lang.reflect.Modifier.isPublic(c.getModifiers()))
                    .as("permitted impl %s must not be public — apps must not be able to reference it", c.getSimpleName())
                    .isFalse());
        }

        @Test
        void cannotInstantiateProductionPermittedImplementationViaReflection_withoutAccess() {
            var permitted = java.util.Arrays.stream(FrameworkStreamConsumer.class.getPermittedSubclasses())
                                            .filter(c -> c.getSimpleName().equals("SystemStreamConsumer"))
                                            .findFirst()
                                            .orElseThrow();
            var ctor = permitted.getDeclaredConstructors()[0];

            // setAccessible(false) is the default; without a setAccessible(true) bypass an external
            // package cannot instantiate. We assert the constructor itself is not public so the
            // boundary is enforced at compile time for normal references.
            assertThat(java.lang.reflect.Modifier.isPublic(ctor.getModifiers())).isFalse();
        }
    }

    /// Convenience base that returns success defaults — individual tests override only the method
    /// they care about, mirroring the publisher test's per-test transport overrides.
    private static class DelegatingStub<T> implements StreamAccess<T> {
        @Override public Promise<Long> publish(T event) {
            return Promise.success(0L);
        }

        @Override public Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents) {
            return Promise.success(List.of());
        }

        @Override public Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents) {
            return Promise.success(List.of());
        }

        @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
            return Promise.unitPromise();
        }

        @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
            return Promise.success(Option.none());
        }

        @Override public Promise<StreamMetadata> metadata() {
            return Promise.success(new StreamMetadata("stub", 1, List.of()));
        }
    }
}
