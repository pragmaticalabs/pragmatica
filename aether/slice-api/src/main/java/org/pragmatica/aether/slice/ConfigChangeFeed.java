// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The node's feed of committed configuration changes to the slices that watch a section.
///
/// The applier side calls [#publish] with the dotted key AFTER the overlay has taken the change; the slice side
/// subscribes through [Watched]. Subscriptions are owned by the slice that declares them, identified by `owner`
/// (the slice artifact's string form), and are released together when that slice is unloaded.
public interface ConfigChangeFeed {
    /// Registers a subscription for `section` and runs the registration delivery (see [Watched#watch]).
    /// `bind` builds the section's record from the current configuration; it is called on the calling thread
    /// for the registration and later on the subscription's own delivery thread.
    <T> Result<Watched.Subscription> subscribe(String owner,
                                               String section,
                                               Fn0<Result<T>> bind,
                                               Fn1<Promise<Unit>, T> callback);

    /// Called on the KV applier thread for each committed put or remove this node honours. Never binds, never
    /// calls user code, never blocks.
    Unit publish(String key);
    /// Releases every subscription `owner` holds. Idempotent. The Promise completes when in-flight callbacks have
    /// returned, bounded by the callback timeout; no delivery starts after this call returns.
    Promise<Unit> releaseAll(String owner);
    /// Number of live subscriptions `owner` holds.
    int subscriberCount(String owner);
    /// Releases everything (node stop).
    Promise<Unit> shutdown();
}
