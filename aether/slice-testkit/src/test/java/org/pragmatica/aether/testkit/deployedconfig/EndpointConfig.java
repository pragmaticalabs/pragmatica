// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.deployedconfig;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// The config record an [EndpointSettings] parameter binds to.
///
/// Deliberately spans all three access shapes the generator emits, so the acceptance test covers
/// the whole `Result.all(...)` chain rather than one method of the facade:
///
///   - `host`/`port`/`secure` — `require*`, the required-primitive path
///   - `tags` — `requireStringList`, which the pre-#889 `ConfigService` adapter refused outright
///   - `weight` — `get*` wrapped in `Result.success`, the optional path
///
/// [#render] exists because the slice is loaded by a child-first `SliceClassLoader`: the test's own
/// copy of this record is a DIFFERENT class from the one the deployed slice holds, so the values
/// have to cross the loader boundary as a `String`, which resolves to the same JDK class on both
/// sides.
public record EndpointConfig(String host, int port, boolean secure, List<String> tags, Option<Integer> weight) {
    public static Result<EndpointConfig> endpointConfig(String host,
                                                        int port,
                                                        boolean secure,
                                                        List<String> tags,
                                                        Option<Integer> weight) {
        return Result.success(new EndpointConfig(host, port, secure, tags, weight));
    }

    public String render() {
        return host + "|" + port + "|" + secure + "|" + String.join("+", tags) + "|" + weight.map(Object::toString).or("none");
    }
}
