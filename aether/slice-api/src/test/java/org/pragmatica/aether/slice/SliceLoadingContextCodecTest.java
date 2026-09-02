// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Codec scoping for slice-provisioned resources (#526).
///
/// Before this, every resource a slice provisioned was handed the NODE codec, which knows framework
/// types and nothing an application declared — so `publisher.publish(new OrderPlaced(...))` threw
/// "No codec registered for class". These tests pin both halves of the fix: an application type now
/// round-trips, and a framework type encodes to the very same bytes it did before.
class SliceLoadingContextCodecTest {
    private static final String SLICE_ID = "org.example:orders-slice";

    /// An application-defined type. The node codec has never heard of it — that is the point.
    record OrderPlaced(String orderId) {}

    private static final SliceCodec.TypeCodec<OrderPlaced> ORDER_PLACED_CODEC =
        new SliceCodec.TypeCodec<>(OrderPlaced.class,
                                   SliceCodec.deterministicTag(OrderPlaced.class.getName()),
                                   (codec, buf, value) -> codec.write(buf, value.orderId()),
                                   (codec, buf) -> new OrderPlaced(codec.read(buf)));

    /// Stands in for a generated slice adapter: its `codec()` layers the application's own types
    /// over whatever parent the runtime supplies, exactly as `FactoryClassGenerator` emits.
    record OrderSlice() implements Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        @Override
        public SliceCodec codec(SliceCodec parent) {
            return SliceCodec.sliceCodec(parent, List.of(ORDER_PLACED_CODEC));
        }
    }

    private static ResourceProviderFacade recordingFacade(AtomicReference<ProvisioningContext> captured) {
        return new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return Promise.success(null);
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                captured.set(context);

                return Promise.success(null);
            }

            @Override
            public Promise<Unit> releaseAll(String sliceId) {
                return Promise.unitPromise();
            }
        };
    }

    private static SliceInvokerFacade noOpInvoker() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                   String methodName,
                                                                   TypeToken<T> requestType,
                                                                   TypeToken<R> responseType) {
                return Result.success(null);
            }
        };
    }

    private static SliceLoadingContext armedContext(AtomicReference<ProvisioningContext> captured, SliceCodec nodeCodec) {
        return SliceLoadingContext.sliceLoadingContext(noOpInvoker(),
                                                       recordingFacade(captured),
                                                       SLICE_ID,
                                                       Option.some(nodeCodec));
    }

    @Nested
    class ExtensionAttachment {
        @Test
        void provide_suppliesSliceCodecAsSerializerAndDeserializer_whenNodeCodecIsArmed() {
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, FrameworkCodecs.frameworkCodecs());

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());

            assertThat(captured.get().extension(Serializer.class).isSuccess()).isTrue();
            assertThat(captured.get().extension(Deserializer.class).isSuccess()).isTrue();
        }

        @Test
        void provide_suppliesNoSerializer_whenNoNodeCodecIsArmed() {
            var captured = new AtomicReference<ProvisioningContext>();
            var context = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), recordingFacade(captured), SLICE_ID);

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());

            assertThat(captured.get().extension(Serializer.class).isFailure()).isTrue();
            assertThat(captured.get().extension(Deserializer.class).isFailure()).isTrue();
        }

        /// #526 is a resource-provisioning-boundary defect, not a stream defect. The distributed cache
        /// and idempotency interceptors read `Serializer`/`Deserializer` from the very same context and
        /// were equally unable to encode application types. Binding at the boundary fixes every
        /// resource at once — this pins that the codec is not attached only for stream sections.
        @Test
        void provide_suppliesTheSameSliceCodec_forNonStreamResources() {
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, FrameworkCodecs.frameworkCodecs());

            context.resources().provide(String.class, "cache.orders", ProvisioningContext.provisioningContext());
            var cacheSerializer = captured.get().extension(Serializer.class).or((Serializer) null);

            context.resources().provide(String.class, "idempotency.orders", ProvisioningContext.provisioningContext());
            var idempotencySerializer = captured.get().extension(Serializer.class).or((Serializer) null);

            context.bindSliceCodec(new OrderSlice());

            assertThat(cacheSerializer).isSameAs(idempotencySerializer);
            assertThat(cacheSerializer.encode(new OrderPlaced("order-7"))).isNotEmpty();
        }
    }

    @Nested
    class ApplicationTypes {
        @Test
        void publish_roundTripsApplicationType_afterSliceCodecIsBound() {
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, FrameworkCodecs.frameworkCodecs());

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());
            context.bindSliceCodec(new OrderSlice());

            var serializer = captured.get().extension(Serializer.class).or((Serializer) null);
            var deserializer = captured.get().extension(Deserializer.class).or((Deserializer) null);

            OrderPlaced decoded = deserializer.decode(serializer.encode(new OrderPlaced("order-42")));

            assertThat(decoded).isEqualTo(new OrderPlaced("order-42"));
        }

        @Test
        void publish_failsLoudNamingTheSlice_whenUsedBeforeSliceCodecIsBound() {
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, FrameworkCodecs.frameworkCodecs());

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());

            var serializer = captured.get().extension(Serializer.class).or((Serializer) null);

            assertThatThrownBy(() -> serializer.encode(new OrderPlaced("order-42"))).isInstanceOf(IllegalStateException.class)
                                                                                     .hasMessageContaining(SLICE_ID)
                                                                                     .hasMessageContaining(OrderPlaced.class.getName());
        }

        @Test
        void publish_failsLoudNamingTheType_whenTypeIsNotInTheSliceCodec() {
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, FrameworkCodecs.frameworkCodecs());

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());
            context.bindSliceCodec(new OrderSlice());

            var serializer = captured.get().extension(Serializer.class).or((Serializer) null);

            assertThatThrownBy(() -> serializer.encode(new Unregistered("x"))).isInstanceOf(IllegalArgumentException.class)
                                                                               .hasMessageContaining(Unregistered.class.getName());
        }

        record Unregistered(String value) {}
    }

    /// Requirement 1 of #526: framework-typed streams must behave IDENTICALLY. The slice codec is a
    /// CHILD of the node codec, inheriting every framework registration verbatim, so this asserts
    /// byte-for-byte equality rather than merely "still works".
    @Nested
    class FrameworkTypesUnchanged {
        @Test
        void encode_producesBytesIdenticalToTheNodeCodec_forString() {
            assertSameBytesAsNodeCodec("the-event-payload");
        }

        @Test
        void encode_producesBytesIdenticalToTheNodeCodec_forList() {
            assertSameBytesAsNodeCodec(List.of("a", "b", "c"));
        }

        @Test
        void encode_producesBytesIdenticalToTheNodeCodec_forNestedContainers() {
            assertSameBytesAsNodeCodec(List.of(42, "mixed", true));
        }

        @Test
        void decode_readsBytesWrittenByTheNodeCodec_forString() {
            var nodeCodec = FrameworkCodecs.frameworkCodecs();
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, nodeCodec);

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());
            context.bindSliceCodec(new OrderSlice());

            var deserializer = captured.get().extension(Deserializer.class).or((Deserializer) null);
            String decoded = deserializer.decode(nodeCodec.encode("written-by-node-codec"));

            assertThat(decoded).isEqualTo("written-by-node-codec");
        }

        private static void assertSameBytesAsNodeCodec(Object value) {
            var nodeCodec = FrameworkCodecs.frameworkCodecs();
            var captured = new AtomicReference<ProvisioningContext>();
            var context = armedContext(captured, nodeCodec);

            context.resources().provide(String.class, "streams.orders", ProvisioningContext.provisioningContext());
            context.bindSliceCodec(new OrderSlice());

            var serializer = captured.get().extension(Serializer.class).or((Serializer) null);

            assertThat(serializer.encode(value)).isEqualTo(nodeCodec.encode(value));
        }
    }
}
