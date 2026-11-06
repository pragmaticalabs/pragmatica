// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// What a [ConfigProviderFacade] does when asked for a [Watched]: exactly one of three, chosen by whoever built the
/// facade and never inferred from an absent feed.
///
///   - [Feed]: a deployed slice. Subscribes to the node's feed under the slice's own owner key.
///   - [BindOnce]: the explicit non-Aether / test-kit entry (`ConfigFacade#configFacade`). The record is bound once
///     and never changes; the caller asked for exactly that by choosing that entry.
///   - [Refused]: a loading context that has a composite but no feed. A `Watched` that quietly never changed would be
///     a refusal hiding as a success, so it refuses with a named cause.
sealed interface WatchSupport {
    <T> Result<Watched<T>> watched(ConfigFacade facade, String section, Fn1<Result<T>, ConfigFacade> binder);

    record Feed(ConfigWatch watch) implements WatchSupport {
        @Override
        public <T> Result<Watched<T>> watched(ConfigFacade facade,
                                              String section,
                                              Fn1<Result<T>, ConfigFacade> binder) {
            return success(callback -> watch.feed()
                                            .subscribe(watch.owner(),
                                                       section,
                                                       () -> binder.apply(facade),
                                                       callback));
        }
    }

    enum BindOnce implements WatchSupport {
        INSTANCE;
        @Override
        public <T> Result<Watched<T>> watched(ConfigFacade facade,
                                              String section,
                                              Fn1<Result<T>, ConfigFacade> binder) {
            return binder.apply(facade)
                         .map(Watched::constant);
        }
    }

    enum Refused implements WatchSupport {
        INSTANCE;
        @Override
        public <T> Result<Watched<T>> watched(ConfigFacade facade,
                                              String section,
                                              Fn1<Result<T>, ConfigFacade> binder) {
            return ConfigFacade.WATCHED_UNAVAILABLE.result();
        }
    }
}
