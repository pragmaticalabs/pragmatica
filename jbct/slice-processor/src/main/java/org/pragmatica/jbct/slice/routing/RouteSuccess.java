// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import org.pragmatica.lang.Option;

/// The success response an inline-table route declares in `routes.toml` (#2805): `status` (the success status) and
/// `location` (a `Location` template whose `{field}` placeholders name components of the result record). Nothing
/// declared is [#NONE]: the route answers 200, or 204 for a `Unit` result, and generates the source it always did.
///
/// @param status   the declared success status, empty when not declared
/// @param location the declared `Location` template, empty when not declared
public record RouteSuccess(Option<Integer> status, Option<String> location) {
    public static final RouteSuccess NONE = new RouteSuccess(Option.none(), Option.none());

    public static RouteSuccess routeSuccess(Option<Integer> status, Option<String> location) {
        return new RouteSuccess(status, location);
    }

    public boolean declared() {
        return status.isPresent() || location.isPresent();
    }
}
