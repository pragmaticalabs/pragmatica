// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.routing.RequestRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #742 round-trip pin: for every route this migration folded onto a catalog `ManagementRoute` entry
/// (`STREAM_GET`/`STREAM_PARTITION`/`STREAM_REPLICAS`/`STREAM_READ`/`STREAMS_PUBLISH`/`STREAMS_DELETE`),
/// an address
/// `ManagementRoute.assemble` produces must dispatch back to that SAME route.
///
/// This is pinned at BOTH layers `ManagementRouter#dispatch` actually chains, not just one:
///  1. `ManagementRoute.match` / `RouteMatcher` -- the fast path tried first when a catalog entry
///     exists, matched purely from `ManagementRoute.values()`'s own declared `tokens()`.
///  2. `RequestRouter`, built here from the REAL `Route<?>` registrations in `StreamApiRoutes`
///     (the same construction `ManagementServer` performs) -- the path `ManagementRouter#dispatch`
///     falls back to whenever the catalog name lookup misses, and the ONLY path the still-unmigrated
///     legacy `StreamRoutes` entries dispatch through today.
///
/// Layer 1 alone is tautological: `RouteMatcher` is built solely from `ManagementRoute.values()`'s
/// own tokens(), so it can never observe a drift between the enum's declared shape and the
/// independently hand-written `.withPath(...)` registration in `StreamApiRoutes`. Layer 2 pins that
/// the two independently-maintained route tables actually agree -- which is the property the earlier
/// CLI-unreachability defect (declared `withPath(...)` arity not covering an interleaved trailing
/// spacer) broke.
///
/// [mechanism, verified 2026-08-30]: what Layer 2 actually proves is route-NAME-selection agreement,
/// not full positional-argument correctness. `RequestRouter`'s spacer match
/// (`allSpacersPresent`) is a set-membership check over the trailing path elements, not a
/// positional one, so a same-arity, same-type swap between two adjacent `.withPath(...)` slots
/// (e.g. a spacer literal and the real param beside it) still resolves to the correctly-NAMED
/// route here -- `found.name()` would stay green -- even though the handler would then receive
/// that pair of values transposed. That narrower defect only surfaces at the real positional bind
/// in `RequestContext#matchPath`, which this pin does not drive. Confirmed by mutation-probing this
/// exact assertion (swapped `STREAM_READ`'s `version`/`spacer("read")` slots; stayed green; reverted).
/// Tracked, not fixed here -- `RequestRouter` is core dispatch-selection logic shared by every
/// `Route<?>` consumer, outside this change's scope: see #755.
class ManagementRouteDispatchRoundTripTest {
    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                        new Class[]{ManageableNode.class},
                                                        (_, method, _) -> stubbed(method.getName(), manager));
    }

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> new KVStore<AetherKey, AetherValue>(null, null, null);
            case "streamWriteRouter" -> StreamWriteRouter.localOnly(manager);
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    @Test
    void foldedCatalogRoutes_assembleThenDispatch_resolveToSameRouteAtBothLayers() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            Supplier<ManageableNode> nodeSupplier = () -> nodeWith(manager);
            var apiRoutes = StreamApiRoutes.streamApiRoutes(nodeSupplier,
                                                             StreamNamespacesService.inMemory(),
                                                             ConsumerGroupCoordinator.noOp(),
                                                             ConsumerGroupRegistry.consumerGroupRegistry());
            var legacyRoutes = StreamRoutes.streamRoutes(nodeSupplier,
                                                         ConsumerGroupCoordinator.noOp(),
                                                         ConsumerGroupRegistry.consumerGroupRegistry());
            var requestRouter = RequestRouter.with(apiRoutes, legacyRoutes);

            pin(requestRouter, ManagementRoute.STREAM_GET, List.of("myns", "mystream", "1.0.0"));
            pin(requestRouter, ManagementRoute.STREAM_PARTITION, List.of("myns", "mystream", "1.0.0", "3"));
            pin(requestRouter, ManagementRoute.STREAM_REPLICAS, List.of("myns", "mystream", "1.0.0", "3"));
            pin(requestRouter, ManagementRoute.STREAM_READ, List.of("myns", "mystream", "1.0.0", "3"));
            pin(requestRouter, ManagementRoute.STREAMS_PUBLISH, List.of("myns", "mystream", "1.0.0"));
            pin(requestRouter, ManagementRoute.STREAMS_DELETE, List.of("myns", "mystream", "1.0.0"));
        } finally {
            manager.close();
        }
    }

    private static void pin(RequestRouter requestRouter, ManagementRoute route, List<String> values) {
        var path = route.assemble(values)
                        .onFailure(cause -> fail(route.name() + " must assemble from " + values + ": " + cause))
                        .unwrap();

        // Layer 1: ManagementRoute.match / RouteMatcher.
        var matched = ManagementRoute.match(route.method(), path)
                                     .onFailure(cause -> fail(route.name() + "'s own assembled path \"" + path
                                                              + "\" must match back to itself: " + cause))
                                     .unwrap();

        assertThat(matched.route()).as("%s assembled \"%s\", but ManagementRoute.match resolved a different route",
                                       route.name(),
                                       path)
                                   .isEqualTo(route);

        var expectedParams = new LinkedHashMap<String, String>();
        var names = route.paramNames();

        for (int i = 0; i < names.size(); i++) {
            expectedParams.put(names.get(i), values.get(i));
        }

        assertThat(matched.params()).as("%s round-tripped param values through assemble -> match", route.name())
                                    .isEqualTo(expectedParams);

        // Layer 2: RequestRouter, built from the REAL Route<?> registrations.
        requestRouter.findRoute(route.method(), path)
                     .onEmpty(() -> fail(route.name() + " assembled \"" + path
                                        + "\", but RequestRouter (built from StreamApiRoutes' real Route<?> "
                                        + "registrations) found no matching route"))
                     .onPresent(found -> assertThat(found.name()).as("%s assembled \"%s\", but RequestRouter "
                                                                     + "dispatched to a differently-named Route<?> "
                                                                     + "(\"%s\") -- the catalog enum and the hand-"
                                                                     + "written registration have drifted",
                                                                     route.name(),
                                                                     path,
                                                                     found.name())
                                                    .isEqualTo(route.name()));
    }
}
