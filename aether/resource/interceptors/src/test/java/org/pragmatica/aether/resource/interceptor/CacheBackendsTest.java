// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.BackendRuntime;
import org.pragmatica.aether.resource.interceptor.DhtNamespaceExtensionTest.RecordingDhtClient;
import org.pragmatica.aether.resource.interceptor.DhtNamespaceExtensionTest.StringDeserializer;
import org.pragmatica.aether.resource.interceptor.DhtNamespaceExtensionTest.StringSerializer;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3658: the backend-selection seam. The contract each selectable backend honours on every runtime it is
/// available on, the mode->backend mapping that keeps existing sections working, and a typed refusal for each
/// pairing the seam rejects: an unknown backend, a backend that does not fit the mode, and `dht` on Terra.
///
/// The Terra context below CARRIES both DHT clients, so a refusal there can only come from the runtime check,
/// never from a missing extension; [Refusals#terraWithoutDhtExtensions_refusesByRuntime_notByMissingExtension]
/// covers the bare context Terra really provisions with.
class CacheBackendsTest {
    private final RecordingDhtClient replicated = new RecordingDhtClient();
    private final RecordingDhtClient cacheNamespace = new RecordingDhtClient();
    private final ProvisioningContext aether = ProvisioningContext.provisioningContext()
                                                                  .withKeyExtractor(key -> key)
                                                                  .withExtension(DHTClient.class, replicated)
                                                                  .withExtension(CacheDhtClient.class,
                                                                                 new CacheDhtClient(cacheNamespace))
                                                                  .withExtension(Serializer.class, new StringSerializer())
                                                                  .withExtension(Deserializer.class, new StringDeserializer());
    private final ProvisioningContext terra = aether.withExtension(BackendRuntime.class, BackendRuntime.TERRA);

    @Nested
    class Contract {
        @Test
        void memoryOnTerra_honoursTheBackendContract() {
            assertHonoursContract(cacheBackend(cache(CacheMode.LOCAL, none()), terra));
        }

        @Test
        void memoryOnAether_honoursTheBackendContract() {
            assertHonoursContract(cacheBackend(cache(CacheMode.LOCAL, none()), aether));
        }

        @Test
        void dhtCacheOnAether_honoursTheBackendContract_inTheCacheNamespace() {
            assertHonoursContract(cacheBackend(cache(CacheMode.DISTRIBUTED, none()), aether));
            assertThat(replicated.storage).isEmpty();
        }

        @Test
        void tieredOnAether_honoursTheBackendContract() {
            assertHonoursContract(cacheBackend(cache(CacheMode.TIERED, none()), aether));
        }

        @Test
        void dhtIdempotencyOnAether_honoursTheBackendContract_inTheReplicatedDht() {
            assertHonoursContract(cacheBackend(idempotency(CacheMode.DISTRIBUTED, none()), aether));
            assertThat(cacheNamespace.storage).isEmpty();
        }

        @Test
        void memoryIdempotencyOnTerra_honoursTheBackendContract() {
            assertHonoursContract(cacheBackend(idempotency(CacheMode.LOCAL, none()), terra));
        }

        /// put -> get sees it; remove -> get misses; an unknown key misses.
        private static void assertHonoursContract(CacheBackend backend) {
            backend.put("k", "v")
                   .await()
                   .unwrap();

            assertThat(backend.get("k")
                              .await()
                              .unwrap()).isEqualTo(some("v"));
            assertThat(backend.get("absent")
                              .await()
                              .unwrap()).isEqualTo(Option.none());

            backend.remove("k")
                   .await()
                   .unwrap();

            assertThat(backend.get("k")
                              .await()
                              .unwrap()).isEqualTo(Option.none());
        }
    }

    @Nested
    class Mapping {
        @Test
        void absentBackend_followsTheMode_memoryForLocal_dhtForDistributed_tieredOverDhtForTiered() {
            assertThat(cacheBackend(cache(CacheMode.LOCAL, none()), aether)).isInstanceOf(InMemoryCache.class);
            assertThat(cacheBackend(cache(CacheMode.DISTRIBUTED, none()), aether)).isInstanceOf(DHTCacheBackend.class);
            assertThat(cacheBackend(cache(CacheMode.TIERED, none()), aether)).isInstanceOf(TieredCache.class);
        }

        @Test
        void namedBackend_matchingTheMode_isSelected() {
            assertThat(cacheBackend(cache(CacheMode.LOCAL, some("memory")), aether)).isInstanceOf(InMemoryCache.class);
            assertThat(cacheBackend(cache(CacheMode.DISTRIBUTED, some("dht")), aether)).isInstanceOf(DHTCacheBackend.class);
            assertThat(cacheBackend(cache(CacheMode.TIERED, some("dht")), aether)).isInstanceOf(TieredCache.class);
        }

        @Test
        void tiered_writesThroughToTheSharedBackend() {
            cacheBackend(cache(CacheMode.TIERED, none()), aether).put("k", "v")
                                                                .await()
                                                                .unwrap();

            assertThat(cacheNamespace.storage).isNotEmpty();
        }
    }

    @Nested
    class Refusals {
        @Test
        void dhtOnTerra_cacheDistributedAndTiered_refusedNamingBackendStoreAndRuntime() {
            for (var mode : new CacheMode[]{CacheMode.DISTRIBUTED, CacheMode.TIERED}) {
                for (var backend : List.of(Option.<String>none(), some("dht"))) {
                    assertThat(failureOf(CacheBackends.cacheBackend(cache(mode, backend), terra)))
                        .as("cache %s backend %s", mode, backend)
                        .isEqualTo(CacheBackendError.BackendUnavailable.backendUnavailable("dht", "calls", BackendRuntime.TERRA));
                }
            }
        }

        @Test
        void dhtOnTerra_idempotencyDistributedAndTiered_refusedNamingBackendStoreAndRuntime() {
            for (var mode : new CacheMode[]{CacheMode.DISTRIBUTED, CacheMode.TIERED}) {
                for (var backend : List.of(Option.<String>none(), some("dht"))) {
                    assertThat(failureOf(CacheBackends.cacheBackend(idempotency(mode, backend), terra)))
                        .as("idempotency %s backend %s", mode, backend)
                        .isEqualTo(CacheBackendError.BackendUnavailable.backendUnavailable("dht", "dedup", BackendRuntime.TERRA));
                }
            }
        }

        @Test
        void dhtOnTerra_messageNamesBackendStoreAndRuntime() {
            assertThat(failureOf(CacheBackends.cacheBackend(cache(CacheMode.DISTRIBUTED, none()), terra)).message())
                .contains("'dht'", "'calls'", "TERRA");
        }

        @Test
        void terraWithoutDhtExtensions_refusesByRuntime_notByMissingExtension() {
            var bareTerra = ProvisioningContext.provisioningContext()
                                               .withExtension(BackendRuntime.class, BackendRuntime.TERRA);

            assertThat(failureOf(CacheBackends.cacheBackend(cache(CacheMode.DISTRIBUTED, none()), bareTerra)))
                .isEqualTo(CacheBackendError.BackendUnavailable.backendUnavailable("dht", "calls", BackendRuntime.TERRA));
        }

        @Test
        void unknownBackend_refusedNamingIt() {
            assertThat(failureOf(CacheBackends.cacheBackend(cache(CacheMode.DISTRIBUTED, some("redis")), aether)))
                .isEqualTo(CacheBackendError.UnknownBackend.unknownBackend("redis", "calls"));
        }

        @Test
        void backendNotFittingTheMode_refusedAsAConflict() {
            assertThat(failureOf(CacheBackends.cacheBackend(cache(CacheMode.LOCAL, some("dht")), aether)))
                .isEqualTo(CacheBackendError.ModeConflict.modeConflict("calls", CacheMode.LOCAL, "dht"));
            assertThat(failureOf(CacheBackends.cacheBackend(cache(CacheMode.DISTRIBUTED, some("memory")), aether)))
                .isEqualTo(CacheBackendError.ModeConflict.modeConflict("calls", CacheMode.DISTRIBUTED, "memory"));
            assertThat(failureOf(CacheBackends.cacheBackend(cache(CacheMode.TIERED, some("memory")), aether)))
                .isEqualTo(CacheBackendError.ModeConflict.modeConflict("calls", CacheMode.TIERED, "memory"));
        }

        @Test
        void refusals_areTerminal() {
            assertThat(CacheBackendError.BackendUnavailable.backendUnavailable("dht", "calls", BackendRuntime.TERRA).isTerminal()).isTrue();
            assertThat(CacheBackendError.UnknownBackend.unknownBackend("redis", "calls").isTerminal()).isTrue();
            assertThat(CacheBackendError.ModeConflict.modeConflict("calls", CacheMode.LOCAL, "dht").isTerminal()).isTrue();
        }

        /// Both factories obtain their backend through the seam: each refuses `dht` on Terra with the seam's error.
        @Test
        void factories_onTerra_refuseDhtThroughTheSeam() {
            var cacheConfig = CacheConfig.cacheConfig("calls", CacheStrategy.CACHE_ASIDE, CacheMode.DISTRIBUTED).unwrap();
            var idempotencyConfig = IdempotencyConfig.idempotencyConfig("dedup", CacheMode.TIERED).unwrap();

            assertThat(failureOf(new CacheInterceptorFactory().provision(cacheConfig, terra).await()))
                .isEqualTo(CacheBackendError.BackendUnavailable.backendUnavailable("dht", "calls", BackendRuntime.TERRA));
            assertThat(failureOf(new IdempotencyInterceptorFactory().provision(idempotencyConfig, terra).await()))
                .isEqualTo(CacheBackendError.BackendUnavailable.backendUnavailable("dht", "dedup", BackendRuntime.TERRA));
        }

        @Test
        void factories_onTerra_provisionMemory() {
            var cacheConfig = CacheConfig.cacheConfig("calls", CacheStrategy.CACHE_ASIDE, CacheMode.LOCAL).unwrap();
            var idempotencyConfig = IdempotencyConfig.idempotencyConfig("dedup", CacheMode.LOCAL).unwrap();

            assertThat(new CacheInterceptorFactory().provision(cacheConfig, terra)
                                                    .await()
                                                    .unwrap()
                                                    .cache()).isInstanceOf(InMemoryCache.class);
            assertThat(new IdempotencyInterceptorFactory().provision(idempotencyConfig, terra)
                                                          .await()
                                                          .unwrap()
                                                          .store()).isInstanceOf(InMemoryCache.class);
        }
    }

    private static CacheBackend cacheBackend(StoreSpec spec, ProvisioningContext context) {
        return CacheBackends.cacheBackend(spec, context)
                            .unwrap();
    }

    private static StoreSpec cache(CacheMode mode, Option<String> backend) {
        return StoreSpec.storeSpec(CacheConfig.cacheConfig("calls", CacheStrategy.CACHE_ASIDE, 300, 100, mode, backend)
                                              .unwrap());
    }

    private static StoreSpec idempotency(CacheMode mode, Option<String> backend) {
        return StoreSpec.storeSpec(IdempotencyConfig.idempotencyConfig("dedup", 3600, 100, mode, backend)
                                                    .unwrap());
    }

    private static Cause failureOf(Result<?> result) {
        return result.fold(cause -> cause, value -> Causes.cause("unexpected success: " + value));
    }
}
