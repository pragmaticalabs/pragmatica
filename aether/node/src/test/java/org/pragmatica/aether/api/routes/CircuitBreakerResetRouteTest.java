// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.CircuitBreakerResetResponse;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The breaker reset route through its registered handler. v2711 finding 5: the route took no body before the per-source reset, so an
/// empty body still resets every source. v2711 finding 6: a well-formed source the committed configuration does not declare is refused
/// with a 400 instead of silently resetting nothing.
class CircuitBreakerResetRouteTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final Set<String> DECLARED = Set.of("eu-1");

    private final List<Option<SourceName>> resets = new CopyOnWriteArrayList<>();

    @Test
    void reset_emptyBody_resetsEverySource() {
        var response = handle("").unwrap();

        assertThat(response.scope()).isEqualTo("all");
        assertThat(resets).containsExactly(Option.none());
    }

    @Test
    void reset_emptyObject_resetsEverySource() {
        var response = handle("{}").unwrap();

        assertThat(response.scope()).isEqualTo("all");
        assertThat(resets).containsExactly(Option.none());
    }

    @Test
    void reset_declaredSource_resetsThatSourceOnly() {
        var response = handle("{\"source\":\"eu-1\"}").unwrap();

        assertThat(response.scope()).isEqualTo("eu-1");
        assertThat(resets).hasSize(1);
        assertThat(resets.getFirst()
                         .map(SourceName::value)).isEqualTo(Option.some("eu-1"));
    }

    @Test
    void reset_undeclaredSource_refusedBadRequest_andResetsNothing() {
        var outcome = handle("{\"source\":\"eu-l\"}");

        assertThat(outcome.isFailure()).as("an unknown source is refused").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(HttpError.class,
                                                                            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST)));
        assertThat(resets).as("nothing was reset").isEmpty();
    }

    private Result<CircuitBreakerResetResponse> handle(String body) {
        return resetRoute().handler()
                           .handle(requestWith(body))
                           .await()
                           .map(CircuitBreakerResetResponse.class::cast);
    }

    private Route<?> resetRoute() {
        return ClusterTopologyRoutes.clusterTopologyRoutes(this::node)
                                    .routes()
                                    .filter(route -> route.name()
                                                          .equals(ManagementRoute.CLUSTER_CIRCUIT_BREAKER_RESET.name()))
                                    .findFirst()
                                    .orElseThrow();
    }

    private ManageableNode node() {
        var ctm = (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                                  new Class[]{ClusterTopologyManager.class},
                                                                  (_, method, args) -> switch (method.getName()) {
                                                                      case "declaresSource" -> DECLARED.contains(((SourceName) args[0]).value());
                                                                      case "resetCircuitBreaker" -> recordReset(args);
                                                                      default -> throw new UnsupportedOperationException(method.getName());
                                                                  });

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "clusterTopologyManager" -> Option.some(ctm);
                                                           default -> throw new UnsupportedOperationException(method.getName());
                                                       });
    }

    @SuppressWarnings("unchecked")
    private int recordReset(Object[] args) {
        resets.add(args.length > 1
                   ? (Option<SourceName>) args[1]
                   : Option.none());

        return 0;
    }

    private static RequestContext requestWith(String body) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "body" -> body.getBytes(StandardCharsets.UTF_8);
            case "fromJson" -> MAPPER.readString(body, (TypeToken<?>) args[0]);
            default -> InvocationHandler.invokeDefault(proxy, method, args);
        };

        return (RequestContext) Proxy.newProxyInstance(RequestContext.class.getClassLoader(),
                                                       new Class[]{RequestContext.class},
                                                       handler);
    }
}
