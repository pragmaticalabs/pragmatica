// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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

/// v1873 probe for #1916 (#755): two SLICES under one base `/users/`, same arity, the spacer at different slots.
public final class SpacerPositionSliceRoutes {
    private SpacerPositionSliceRoutes() {}

    public static final class IdThenEditSlice {}

    public static final class EditThenIdSlice {}

    public static final class IdThenEdit implements RouteSource, SliceRouterFactory<IdThenEditSlice> {
        public Class<IdThenEditSlice> sliceType() { return IdThenEditSlice.class; }
        public int routeSecurityContract() { return SliceRouterFactory.ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(IdThenEditSlice slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(IdThenEditSlice slice, JsonMapper m) { return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), m); }
        public Stream<Route<?>> routes() {
            return Stream.of(Route.<String>get("/users/").withPath(aLong(), spacer("edit"))
                                  .to((id, _) -> Promise.success("id-then-edit-" + id)).named("idThenEdit")
                                  .withSecurity(SecurityPolicy.publicRoute()).asJson());
        }
    }

    public static final class EditThenId implements RouteSource, SliceRouterFactory<EditThenIdSlice> {
        public Class<EditThenIdSlice> sliceType() { return EditThenIdSlice.class; }
        public int routeSecurityContract() { return SliceRouterFactory.ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(EditThenIdSlice slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(EditThenIdSlice slice, JsonMapper m) { return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), m); }
        public Stream<Route<?>> routes() {
            return Stream.of(Route.<String>get("/users/").withPath(spacer("edit"), aLong())
                                  .to((_, id) -> Promise.success("edit-then-id-" + id)).named("editThenId")
                                  .withSecurity(SecurityPolicy.publicRoute()).asJson());
        }
    }

    public static final class BothSlice {}

    /// ONE slice declaring both positions, id-then-edit FIRST: only a positional match picks edit-then-id for /users/edit/42.
    public static final class Both implements RouteSource, SliceRouterFactory<BothSlice> {
        public Class<BothSlice> sliceType() { return BothSlice.class; }
        public int routeSecurityContract() { return SliceRouterFactory.ROUTE_SECURITY_CONTRACT; }
        public SliceRouter create(BothSlice slice) { return create(slice, JsonMapper.defaultJsonMapper()); }
        public SliceRouter create(BothSlice slice, JsonMapper m) { return SliceRouter.sliceRouter(this, ErrorMapper.defaultMapper(), m); }
        public Stream<Route<?>> routes() {
            return Stream.concat(new IdThenEdit().routes(), new EditThenId().routes());
        }
    }
}
