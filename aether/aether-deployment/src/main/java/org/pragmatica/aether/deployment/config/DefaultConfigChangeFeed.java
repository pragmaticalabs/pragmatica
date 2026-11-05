// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.ConfigChangeFeed;
import org.pragmatica.aether.slice.Watched;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// The node's [ConfigChangeFeed]: registry of `Watched` subscriptions keyed by the slice that declares them.
///
/// **Ordering key.** `applySeq` is node-local, assigned under `feedLock` per published change; `asOf` (the KV
/// store's committed revision at that moment) is carried for logs and events and never compared.
///
/// **Registration loses no update.** A subscription is added to the registry and the baseline `applySeq` is read
/// under ONE `feedLock` hold, strictly before the registration bind. A change published before that hold is
/// already in the overlay (the overlay write precedes the publish) and so in the bind; one published after finds
/// the subscription and triggers it.
///
/// **`feedLock` is a leaf lock.** The applier thread holds the KV mutation lock, takes `feedLock`, and inside it
/// reads `committedRevision()`; registration takes only `feedLock`. Nothing under `feedLock` takes the mutation
/// lock, binds, or calls user code.
public final class DefaultConfigChangeFeed implements ConfigChangeFeed {
    private static final String CALLBACK_TIMEOUT_KEY = "config.watch.callback.timeout";
    private static final TimeSpan DEFAULT_CALLBACK_TIMEOUT = TimeSpan.timeSpan(30).seconds();
    private static final Cause FEED_CLOSED = Causes.cause("Config change feed is shut down");

    private final ReentrantLock feedLock = new ReentrantLock();
    private final LongSupplier committedRevision;

    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private final WatchEnvironment env;
    // Guarded by feedLock.
    private final Map<String, Set<ConfigSubscription<?>>> byOwner = new HashMap<>();
    private long applySeq;
    private boolean closed;

    private DefaultConfigChangeFeed(LongSupplier committedRevision,
                                    ConfigReadProtocol readProtocol,
                                    TimeSpan callbackTimeout) {
        this.committedRevision = committedRevision;
        this.env = WatchEnvironment.watchEnvironment(readProtocol, callbackTimeout, sink::get);
    }

    /// @param committedRevision the KV store's committed revision, sampled per published change (label only)
    /// @param readProtocol      reads a record from ONE configuration state, or fails (the overlay's `atomically`)
    /// @param callbackTimeout   how long a callback may run; read once at node start, never watched
    public static DefaultConfigChangeFeed defaultConfigChangeFeed(LongSupplier committedRevision,
                                                                  ConfigReadProtocol readProtocol,
                                                                  TimeSpan callbackTimeout) {
        return new DefaultConfigChangeFeed(committedRevision, readProtocol, callbackTimeout);
    }

    /// `config.watch.callback.timeout` (whole seconds) from the node configuration, default 30 s [taste]. Read once
    /// at node start and never watched, so it cannot depend on the configuration it times.
    public static TimeSpan callbackTimeoutFrom(Option<ConfigurationProvider> nodeConfig) {
        return nodeConfig.flatMap(config -> config.getString(CALLBACK_TIMEOUT_KEY))
                         .flatMap(DefaultConfigChangeFeed::parsePositiveSeconds)
                         .map(seconds -> TimeSpan.timeSpan(seconds).seconds())
                         .or(DEFAULT_CALLBACK_TIMEOUT);
    }

    /// The overlay's consistent-read protocol, or the unprotected one on a node with no overlay.
    public static ConfigReadProtocol readProtocolOver(Option<DynamicConfigurationProvider> dynamicProvider) {
        return dynamicProvider.<ConfigReadProtocol> map(DefaultConfigChangeFeed::over)
                              .or(ConfigReadProtocol.unprotected());
    }

    private static ConfigReadProtocol over(DynamicConfigurationProvider provider) {
        return provider::atomically;
    }

    private static Option<Long> parsePositiveSeconds(String raw) {
        return Number.parseLong(raw.trim())
                     .option()
                     .filter(seconds -> seconds > 0);
    }

    public Unit setOperatorWarningSink(OperatorWarningSink operatorSink) {
        sink.set(operatorSink);

        return unit();
    }

    // ---------------------------------------------------------------- registration
    @Override
    public <T> Result<Watched.Subscription> subscribe(String owner,
                                                      String section,
                                                      Fn0<Result<T>> bind,
                                                      Fn1<Promise<Unit>, T> callback) {
        var subscription = new ConfigSubscription<>(env, owner, section, bind, callback);

        return enrol(owner, subscription).flatMap(subscription::register)
                    .onFailure(_ -> forget(owner, subscription))
                    .map(registered -> registered);
    }

    /// r2: add to the registry and read the baseline in one hold of `feedLock`.
    private <T> Result<Long> enrol(String owner, ConfigSubscription<T> subscription) {
        feedLock.lock();
        try {
            if (closed) {
                return FEED_CLOSED.result();
            }

            byOwner.computeIfAbsent(owner, _ -> new HashSet<>()).add(subscription);

            return success(applySeq);
        } finally {
            feedLock.unlock();
        }
    }

    private Unit forget(String owner, ConfigSubscription<?> subscription) {
        feedLock.lock();
        try {
            option(byOwner.get(owner)).onPresent(set -> set.remove(subscription));
        } finally {
            feedLock.unlock();
        }

        return subscription.abandon();
    }

    // ---------------------------------------------------------------- publish
    @Override
    public Unit publish(String key) {
        feedLock.lock();
        try {
            var seq = ++applySeq;
            var asOf = committedRevision.getAsLong();

            byOwner.values().forEach(set -> set.forEach(subscription -> subscription.triggerIfCovers(key, seq, asOf)));
        } finally {
            feedLock.unlock();
        }

        return unit();
    }

    // ---------------------------------------------------------------- release
    @Override
    public Promise<Unit> releaseAll(String owner) {
        return releaseAll(detach(owner));
    }

    @Override
    public int subscriberCount(String owner) {
        feedLock.lock();
        try {
            return option(byOwner.get(owner)).map(Set::size)
                         .or(0);
        } finally {
            feedLock.unlock();
        }
    }

    @Override
    public Promise<Unit> shutdown() {
        return releaseAll(detachAll());
    }

    private List<ConfigSubscription<?>> detach(String owner) {
        feedLock.lock();
        try {
            return option(byOwner.remove(owner)).<List<ConfigSubscription<?>>> map(List::copyOf)
                         .or(List.of());
        } finally {
            feedLock.unlock();
        }
    }

    private List<ConfigSubscription<?>> detachAll() {
        feedLock.lock();
        try {
            closed = true;
            var all = new ArrayList<ConfigSubscription<?>>();

            byOwner.values().forEach(all::addAll);
            byOwner.clear();

            return all;
        } finally {
            feedLock.unlock();
        }
    }

    private static Promise<Unit> releaseAll(List<ConfigSubscription<?>> subscriptions) {
        return Promise.allOf(subscriptions.stream().map(ConfigSubscription::release).toList()).mapToUnit();
    }

    // ---------------------------------------------------------------- test seams
    /// Lock-order probe: true when `feedLock` is held by the calling thread.
    boolean holdsFeedLock() {
        return feedLock.isHeldByCurrentThread();
    }
}
