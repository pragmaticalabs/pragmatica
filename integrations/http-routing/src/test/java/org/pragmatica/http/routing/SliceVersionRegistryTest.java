package org.pragmatica.http.routing;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.routing.security.RouteSecurityPolicy;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit coverage for the #198 §6.4 versioned-route representation: routes carry the API version as
/// metadata (no baked `/v{N}/`), the mounted path is composed at registration time in path mode
/// (the default), and the per-slice version registry exposes the deploy-time metadata the runtime
/// needs to choose path mode or header mode.
class SliceVersionRegistryTest {
    private static final Handler<String> HANDLER = _ -> Promise.success("ok");

    private static Route<String> versionedRoute(String path, int version) {
        return Route.route(HttpMethod.GET,
                           path,
                           HANDLER,
                           CommonContentType.APPLICATION_JSON,
                           List.of(),
                           "get",
                           new RouteSecurityPolicy() {},
                           version);
    }

    @Test
    void version_defaultsToZero_forUnversionedRoute() {
        var route = Route.route(HttpMethod.GET, "/{id}", HANDLER, CommonContentType.APPLICATION_JSON);

        assertThat(route.version()).isEqualTo(0);
    }

    @Test
    void version_isCarried_whenRouteIsVersioned() {
        assertThat(versionedRoute("/", 2).version()).isEqualTo(2);
    }

    @Test
    void mountInPathMode_returnsRouteUnchanged_whenUnversioned() {
        var route = Route.route(HttpMethod.GET, "/orders/{id}", HANDLER, CommonContentType.APPLICATION_JSON);

        var mounted = Route.mountInPathMode(route, "/api/orders");

        assertThat(mounted.path()).isEqualTo(route.path());
        assertThat(mounted).isSameAs(route);
    }

    @Test
    void mountInPathMode_composesApiPrefixAndVersionSegment_whenVersioned() {
        // The un-versioned compiled path for `GET /{id:Long}` is its base path "/"; path mode
        // composes it into the byte-identical "/api/orders/v1/" the old codegen baked directly.
        var mounted = Route.mountInPathMode(versionedRoute("/", 1), "/api/orders");

        assertThat(mounted.path()).isEqualTo("/api/orders/v1/");
        assertThat(mounted.version()).isEqualTo(1);
    }

    @Test
    void mountInPathMode_distinguishesVersions_forSameBindKey() {
        var v1 = Route.mountInPathMode(versionedRoute("/", 1), "/api/orders");
        var v2 = Route.mountInPathMode(versionedRoute("/", 2), "/api/orders");

        assertThat(v1.path()).isEqualTo("/api/orders/v1/");
        assertThat(v2.path()).isEqualTo("/api/orders/v2/");
        assertThat(v1.path()).isNotEqualTo(v2.path());
    }

    @Test
    void unversionedRegistry_reportsNoVersions() {
        assertThat(SliceVersionRegistry.UNVERSIONED.isVersioned()).isFalse();
        assertThat(SliceVersionRegistry.UNVERSIONED.versions()).isEmpty();
        assertThat(SliceVersionRegistry.UNVERSIONED.apiPrefix()).isEmpty();
        assertThat(SliceVersionRegistry.UNVERSIONED.defaultVersion().isEmpty()).isTrue();
    }

    @Test
    void routeSource_exposesUnversionedRegistry_byDefault() {
        RouteSource source = () -> List.<Route<?>>of().stream();

        assertThat(source.versionRegistry()).isSameAs(SliceVersionRegistry.UNVERSIONED);
    }

    @Test
    void versionRegistry_carriesApiPrefixDefaultAndPerVersionMetadata() {
        var registry = SliceVersionRegistry.sliceVersionRegistry("/api/orders",
                                                                 false,
                                                                 Option.some(2),
                                                                 List.of(SliceVersionRegistry.VersionInfo.versionInfo(1, true, Option.some("2026-12-31")),
                                                                         SliceVersionRegistry.VersionInfo.versionInfo(2, false, Option.none())));

        assertThat(registry.isVersioned()).isTrue();
        assertThat(registry.apiPrefix()).isEqualTo("/api/orders");
        assertThat(registry.requireVersionHeader()).isFalse();
        assertThat(registry.defaultVersion().or(0)).isEqualTo(2);
        assertThat(registry.versions()).hasSize(2);
        assertThat(registry.versions().getFirst().version()).isEqualTo(1);
        assertThat(registry.versions().getFirst().deprecated()).isTrue();
        assertThat(registry.versions().getFirst().sunset().or("")).isEqualTo("2026-12-31");
        assertThat(registry.versions().get(1).version()).isEqualTo(2);
        assertThat(registry.versions().get(1).deprecated()).isFalse();
        assertThat(registry.versions().get(1).sunset().isEmpty()).isTrue();
    }
}
