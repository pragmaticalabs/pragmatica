package org.pragmatica.aether.http;

import java.util.stream.Stream;

import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.adapter.SliceRouterFactory;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

import static org.pragmatica.http.routing.PathParameter.aLong;
import static org.pragmatica.http.routing.PathParameter.spacer;

/// Two siblings that differ ONLY by spacer position (same base, arity 1, spacer set {edit}), with different policies:
/// `/users/{id}/edit` is public, `/users/edit/{id}` requires role `admin`.
public final class SpacerPositionPolicySliceRoutes {
    private SpacerPositionPolicySliceRoutes() {}

    public static final class MixedSlice {}

    static Route<?> publicIdThenEdit() {
        return Route.<String>get("/users/").withPath(aLong(), spacer("edit"))
                    .to((id, _) -> Promise.success("id-then-edit-" + id)).named("idThenEdit")
                    .withSecurity(SecurityPolicy.publicRoute()).asJson();
    }

    static Route<?> adminEditThenId() {
        return Route.<String>get("/users/").withPath(spacer("edit"), aLong())
                    .to((_, id) -> Promise.success("edit-then-id-" + id)).named("editThenId")
                    .withSecurity(SecurityPolicy.roleRequired("admin")).asJson();
    }

    /// ONE slice declaring both siblings.
    public static final class Mixed implements RouteSource, SliceRouterFactory<MixedSlice> {
        public Class<MixedSlice> sliceType() { return MixedSlice.class; }
        public int routeSecurityContract() { return SliceRouterFactory.ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(MixedSlice slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(MixedSlice slice, JsonMapper m) { return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), m); }
        public Stream<Route<?>> routes() { return Stream.of(publicIdThenEdit(), adminEditThenId()); }
    }
}
