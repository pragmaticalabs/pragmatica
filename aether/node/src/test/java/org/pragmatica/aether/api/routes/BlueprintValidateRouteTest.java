// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import org.pragmatica.aether.api.ManagementApiResponses.BlueprintValidationResponse;
import org.pragmatica.aether.deployment.cluster.BlueprintService;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.blueprint.BlueprintParser;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #811 (NT-176b), route arm: `POST /api/v1/blueprints/validate` answers what a deploy would answer. The service stub
/// parses the DSL fine but refuses it at `validateDeployable`, so a route that only parses (`validate`) answers
/// `valid=true` and this test goes red.
class BlueprintValidateRouteTest {
    private static final String DSL = """
            id = "org.example:topic-app:1.0.0"

            [[slices]]
            artifact = "org.example:topic-slice:1.0.0"
            instances = 3
            """;
    private static final String REFUSAL = "topic section [order-events] refused: unknown key replication-factor";

    @Test
    void validateRoute_reportsInvalidWithDeployRefusal_whenBlueprintParsesButDeployWouldRefuse() {
        var response = respond();

        assertThat(response.valid()).as("the route must report what a deploy refuses, not only what parses")
                                    .isFalse();
        assertThat(response.errors()).containsExactly(REFUSAL);
    }

    private static BlueprintValidationResponse respond() {
        var route = SliceRoutes.sliceRoutes(BlueprintValidateRouteTest::node)
                               .routes()
                               .filter(candidate -> candidate.name()
                                                             .equals(ManagementRoute.BLUEPRINT_VALIDATE.name()))
                               .findFirst()
                               .orElseThrow();
        var holder = new java.util.concurrent.atomic.AtomicReference<Object>();

        route.handler()
             .handle(request())
             .await()
             .onFailure(cause -> fail("route must answer, got: " + cause.message()))
             .onSuccess(holder::set);

        return (BlueprintValidationResponse) holder.get();
    }

    private static ManageableNode node() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> "blueprintService".equals(method.getName())
                                                                         ? service()
                                                                         : unsupported(method.getName()));
    }

    private static BlueprintService service() {
        return (BlueprintService) Proxy.newProxyInstance(BlueprintService.class.getClassLoader(),
                                                         new Class[]{BlueprintService.class},
                                                         (_, method, _) -> switch (method.getName()) {
                                                             case "validate" -> BlueprintParser.parse(DSL);
                                                             case "validateDeployable" -> Causes.cause(REFUSAL).promise();
                                                             default -> unsupported(method.getName());
                                                         });
    }

    private static Object unsupported(String name) {
        throw new UnsupportedOperationException("Not implemented in test proxy: " + name);
    }

    private static RequestContext request() {
        InvocationHandler handler = (proxy, method, args) -> "bodyAsString".equals(method.getName())
                                                             ? DSL
                                                             : method.isDefault()
                                                               ? InvocationHandler.invokeDefault(proxy, method, args)
                                                               : unsupported(method.getName());

        return (RequestContext) Proxy.newProxyInstance(RequestContext.class.getClassLoader(),
                                                       new Class[]{RequestContext.class},
                                                       handler);
    }
}
