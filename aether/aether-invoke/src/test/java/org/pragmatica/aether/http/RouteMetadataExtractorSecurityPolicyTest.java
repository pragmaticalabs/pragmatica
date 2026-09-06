// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the non-codegen counterpart of #763's fix. A hand-written [Route] that never calls
/// `.withSecurity(...)` carries [Route#security()]'s anonymous default implementation — NOT a
/// [SecurityPolicy] instance — so `RouteMetadataExtractorImpl#resolveSecurityPolicy` must fall
/// back to [SecurityPolicy#unspecified()] (inherit the deployment's global policy at request
/// time via `AppHttpServer`), not [SecurityPolicy#publicRoute()] (silently public regardless of
/// the deployment's configured mode).
///
/// #763 turned out to have two sites sharing this defect: the codegen path
/// (`RouteConfigLoader`, covered by `AppHttpServerRouteSecurityPolicyTest`) and this one
/// (`RouteMetadataExtractorImpl`, used for hand-written/programmatic routes). Both are fixed;
/// this test pins the second.
class RouteMetadataExtractorSecurityPolicyTest {
    @Test
    void handWrittenRoute_withNoDeclaredSecurity_resolvesToUnspecified_notPublic() {
        Route<String> route = Route.<String>get("/items")
                                    .to(_ -> Promise.success("ok"))
                                    .asJson();

        var definitions = RouteMetadataExtractor.routeMetadataExtractor()
                                                 .extract(route, "org.example:stub:1.0.0");

        assertThat(definitions).hasSize(1);
        assertThat(definitions.getFirst().security()).isEqualTo(SecurityPolicy.unspecified());
    }
}
