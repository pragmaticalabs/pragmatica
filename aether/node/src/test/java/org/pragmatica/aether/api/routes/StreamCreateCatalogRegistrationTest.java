// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.api.routes.StreamApiRoutes.StreamCreateRequest;
import org.pragmatica.aether.api.routes.StreamApiRoutes.StreamCreateResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.KvBackedStreamRegistry;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry.RegisteredByKind;
import org.pragmatica.aether.slice.stream.SystemStreamBootstrap;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;


/// #968: `POST /api/v1/streams` (body-carried name) answered `{"status":"created"}` for a stream
/// that never appeared in `GET /streams` or `/streams/namespaces` on any node. Two producers, one
/// symptom: the legacy handler minted rings and committed a `StreamConfigKey` but never wrote the
/// `StreamRegistryKey` catalog every read route consults (#1224's trace — #1229 fixed only the new
/// catalog-addressed route); and [KvBackedStreamRegistry#register] fired its consensus put without
/// awaiting it, so even the catalog-addressed route reported `"created"` for a put consensus refused.
/// Every assertion here reads the stream BACK from the catalog or the engine — never the response
/// alone, which is exactly the check that let the defect ship.
class StreamCreateCatalogRegistrationTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final String ADDRESS = NAMESPACE + ":" + STREAM + ":" + VERSION;
    private static final String BARE_NAME = "batch2probe-app";
    private static final Cause CONSENSUS_REFUSED = Causes.cause("Node is inactive");

    @Test
    void legacyCreate_addressName_isReadableBackFromTheCatalog() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            var result = legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 1));

            result.onFailure(cause -> fail("create of a catalog-addressed name must succeed: " + cause.message()));
            result.onSuccess(response -> assertThat(response.status()).isEqualTo("created"));
            assertThat(namespacesService.lookup(address()).isPresent()).as("response was %s but the catalog has no entry for %s",
                                                                          result,
                                                                          ADDRESS)
                      .isTrue();
            namespacesService.lookup(address())
                             .onPresent(entry -> assertThat(entry.registeredBy()).isEqualTo(RegisteredByKind.OPERATOR));
            assertThat(manager.streamInfo(ADDRESS).isPresent()).as("the ring must be materialized under the engine key")
                      .isTrue();
        } finally {
            manager.close();
        }
    }

    /// A bare name has no catalog address, so no read route can ever reach the ring a create would
    /// mint for it. Refuse with `400` naming the form to retype (the #1044 CLI shape) — never
    /// `"created"` for something nothing can list, read or delete.
    @Test
    void legacyCreate_bareName_isRefusedWith400AndNothingIsMintedOrRegistered() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            var result = legacyCreate(manager, namespacesService, new StreamCreateRequest(BARE_NAME, 1));

            result.onSuccess(response -> fail("a bare name cannot be registered in the catalog, yet the response was: " + response));
            result.onFailure(cause -> assertBadRequestNaming(cause, "namespace:stream:version"));
            assertThat(manager.streamInfo(BARE_NAME).isEmpty()).as("nothing minted under the bare name").isTrue();
            assertThat(namespacesService.snapshot()).isEmpty();
        } finally {
            manager.close();
        }
    }

    @Test
    void legacyCreate_missingName_isRefusedWith400() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            var result = legacyCreate(manager, namespacesService, new StreamCreateRequest(null, 1));

            result.onSuccess(response -> fail("a create without a name must be refused, got: " + response));
            result.onFailure(cause -> assertBadRequestNaming(cause, "Missing stream name"));
        } finally {
            manager.close();
        }
    }

    /// Ticket acceptance: the idempotent `"exists"` branch keeps working, and keeps reporting the
    /// EXISTING partition count rather than the one the repeat asked for.
    @Test
    void legacyCreate_repeatedAddress_reportsExistsWithExistingPartitionsAndDoesNotReRegister() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 2))
                .onFailure(cause -> fail("first create must succeed: " + cause.message()));

            var repeat = legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 4));

            repeat.onFailure(cause -> fail("repeated create must succeed: " + cause.message()));
            repeat.onSuccess(response -> {
                assertThat(response.status()).isEqualTo("exists");
                assertThat(response.partitions()).isEqualTo(2);
            });
            assertThat(namespacesService.snapshot()).hasSize(1);
            assertThat(namespacesService.snapshot().getFirst().refCount()).isEqualTo(1);
        } finally {
            manager.close();
        }
    }

    /// The catalog put is a consensus write. When consensus refuses it the client must hear so —
    /// and because the ring stays materialized, a retry once consensus accepts must register it
    /// (`tolerateAlreadyExists` on the mint) rather than fail on the half-done first attempt.
    @Test
    void legacyCreate_catalogPutRefused_isNotReportedCreated_andRetryRegisters() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var accepting = new AtomicBoolean(false);
        var namespacesService = kvBackedNamespaces(accepting);
        try {
            var refused = legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 1));

            refused.onSuccess(response -> fail("catalog put was refused by consensus, yet the response was: " + response));
            refused.onFailure(cause -> assertThat(cause.message()).contains(CONSENSUS_REFUSED.message()));
            assertThat(namespacesService.lookup(address()).isEmpty()).isTrue();

            accepting.set(true);
            var retried = legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 1));

            retried.onFailure(cause -> fail("retry after consensus accepts must succeed: " + cause.message()));
            retried.onSuccess(response -> assertThat(response.status()).isEqualTo("created"));
            assertThat(namespacesService.lookup(address()).isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// The catalog put's third outcome: consensus never answers. `register` bounds the wait
    /// (`KvBackedStreamRegistry.REGISTER_TIMEOUT`, 10 s in production; injected as 200 ms here through
    /// the registry's bound seam — no sleep) and the request fails with `CoreError.Timeout`, which is
    /// not `HttpStatusAware`, so `ProblemResponses.resolveStatus` renders it `500`. The ring stays
    /// materialized, so once consensus answers again a retry registers it. `@Timeout` turns an
    /// unbounded wait — the mutation that removes the bound — into a red instead of a hang.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void legacyCreate_catalogPutNeverCompletes_timesOutAsFailure_ringStays_andRetryRegisters() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var hanging = new AtomicBoolean(true);
        var namespacesService = hangingNamespaces(hanging, TimeSpan.timeSpan(200).millis());
        try {
            var result = legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 1));

            result.onSuccess(response -> fail("catalog put never completed, yet the response was: " + response));
            result.onFailure(cause -> {
                assertThat(cause).isInstanceOf(CoreError.Timeout.class);
                assertThat(cause).as("a Timeout is not HttpStatusAware, so it renders as 500").isNotInstanceOf(HttpStatusAware.class);
            });
            assertThat(manager.streamInfo(ADDRESS).isPresent()).as("the ring stays materialized for the retry").isTrue();
            assertThat(namespacesService.lookup(address()).isEmpty()).isTrue();

            hanging.set(false);
            var retried = legacyCreate(manager, namespacesService, new StreamCreateRequest(ADDRESS, 1));

            retried.onFailure(cause -> fail("retry once consensus answers must succeed: " + cause.message()));
            retried.onSuccess(response -> assertThat(response.status()).isEqualTo("created"));
            assertThat(namespacesService.lookup(address()).isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// Same refusal on #1229's catalog-addressed route: its test drove `StreamNamespacesService.inMemory()`,
    /// whose register is synchronous, and so could not see the fire-and-forget put.
    @Test
    void catalogCreate_catalogPutRefused_isNotReportedCreated() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = kvBackedNamespaces(new AtomicBoolean(false));
        try {
            var result = routesFor(manager, namespacesService).createStream(NAMESPACE,
                                                                            STREAM,
                                                                            VERSION,
                                                                            new StreamApiRoutes.CreateRequest(1));

            result.onSuccess(response -> fail("catalog put was refused by consensus, yet the response was: " + response));
            result.onFailure(cause -> assertThat(cause.message()).contains(CONSENSUS_REFUSED.message()));
            assertThat(namespacesService.lookup(address()).isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    private static Result<StreamCreateResponse> legacyCreate(StreamPartitionManager manager,
                                                             StreamNamespacesService namespacesService,
                                                             StreamCreateRequest request) {
        return routesFor(manager, namespacesService).createStream(request);
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager, StreamNamespacesService namespacesService) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager), namespacesService, null, null);
    }

    private static ResourceAddress address() {
        return ResourceAddress.resourceAddress(ADDRESS).unwrap();
    }

    private static void assertBadRequestNaming(Cause cause, String expectedFragment) {
        assertThat(cause).as("refusal must carry an HTTP status, got %s", cause).isInstanceOf(HttpError.class);
        assertThat(((HttpError) cause).status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cause.message()).contains(expectedFragment);
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    /// A registry over a real `KVStore` and a cluster stub that either applies the put into the store
    /// (accepting) or fails the promise (refusing) — the production shape, minus the network.
    private static StreamNamespacesService kvBackedNamespaces(AtomicBoolean accepting) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var registry = new KvBackedStreamRegistry(switchableClusterNode(store, accepting), store);

        return new StreamNamespacesService(registry, new SystemStreamBootstrap(registry));
    }

    /// A registry whose consensus apply never resolves while `hanging` is set (an unresolved promise),
    /// and lands the put in the store once cleared — the never-answers seam for the timeout branch,
    /// with the register bound injected so the test does not wait the production 10 s.
    private static StreamNamespacesService hangingNamespaces(AtomicBoolean hanging, TimeSpan registerTimeout) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var registry = new KvBackedStreamRegistry(hangingClusterNode(store, hanging), store, registerTimeout);

        return new StreamNamespacesService(registry, new SystemStreamBootstrap(registry));
    }

    private static ClusterNode<KVCommand<AetherKey>> hangingClusterNode(KVStore<AetherKey, AetherValue> store,
                                                                        AtomicBoolean hanging) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return NodeId.nodeId("test-node").unwrap();
            }

            @Override public TopologyManager topologyManager() {
                return null;
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                if (hanging.get()) {
                    return Promise.promise();
                }

                store.process(store.createBatch(commands));

                return Promise.success(List.of());
            }
        };
    }

    private static ClusterNode<KVCommand<AetherKey>> switchableClusterNode(KVStore<AetherKey, AetherValue> store,
                                                                           AtomicBoolean accepting) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return NodeId.nodeId("test-node").unwrap();
            }

            @Override public TopologyManager topologyManager() {
                return null;
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                if (!accepting.get()) {
                    return Promise.failure(CONSENSUS_REFUSED);
                }

                store.process(store.createBatch(commands));

                return Promise.success(List.of());
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
