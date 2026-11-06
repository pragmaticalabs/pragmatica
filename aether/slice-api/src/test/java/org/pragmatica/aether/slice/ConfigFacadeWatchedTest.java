// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFacadeWatchedTest {
    private record Port(int value) {}

    private static Result<Port> bindPort(ConfigFacade facade) {
        return facade.requireInt("server", "port").map(Port::new);
    }

    private static ConfigurationProviderHolder holder() {
        return new ConfigurationProviderHolder();
    }

    private static final class ConfigurationProviderHolder {
        final ConfigurationProvider provider = IntrinsicConfigProvider.intrinsicConfigProvider("test",
                                                                                                                      Map.of("server.port", "8080"));
    }

    @Test
    void defaultWatchedRefuses_onAFacadeWithNoFeed() {
        var result = NoOpConfigFacade.INSTANCE.watched("server", ConfigFacadeWatchedTest::bindPort);

        assertThat(result.isFailure()).as("a success-shaped no-op would pretend to watch").isTrue();
        result.onSuccess(_ -> Assertions.fail("refusing default returned a Watched"))
              .onFailure(cause -> assertThat(cause).isSameAs(ConfigFacade.WATCHED_UNAVAILABLE));
    }

    @Test
    void watched_onAFacadeOverAProvider_withoutAFeed_bindsOnceAndNeverChanges() {
        var facade = ConfigFacade.configFacade("slice", holder().provider);
        var seen = new ArrayList<Port>();

        var watched = facade.watched("server", ConfigFacadeWatchedTest::bindPort).unwrap();
        var subscribed = watched.watch(port -> {
            seen.add(port);
            return Promise.unitPromise();
        });

        assertThat(subscribed.isSuccess()).isTrue();
        assertThat(seen).containsExactly(new Port(8080));
    }

    @Test
    void watched_withoutAFeed_failsWhenTheRecordCannotBeBound() {
        var facade = ConfigFacade.configFacade("slice", IntrinsicConfigProvider.intrinsicConfigProvider("test", Map.of()));

        assertThat(facade.watched("server", ConfigFacadeWatchedTest::bindPort).isFailure()).isTrue();
    }

    @Test
    void watched_withAFeed_subscribesUnderTheOwnerAndSection_andBindsThroughTheFacade() {
        var calls = new ArrayList<String>();
        var bound = new ArrayList<Port>();
        ConfigChangeFeed feed = new RecordingFeed(calls, bound);
        var facade = ConfigProviderFacade.configProviderFacade("slice", holder().provider, ConfigWatch.configWatch(feed, "org.example:slice:1.0.0"));

        var watched = facade.watched("server", ConfigFacadeWatchedTest::bindPort).unwrap();
        var subscribed = watched.watch(_ -> Promise.unitPromise());

        assertThat(subscribed.isSuccess()).isTrue();
        assertThat(calls).containsExactly("subscribe org.example:slice:1.0.0 server");
        assertThat(bound).as("the feed's bind function reads through the slice's own facade").containsExactly(new Port(8080));
    }

    private record RecordingFeed(List<String> calls, List<Port> bound) implements ConfigChangeFeed {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Result<Watched.Subscription> subscribe(String owner,
                                                          String section,
                                                          Fn0<Result<T>> bind,
                                                          Fn1<Promise<Unit>, T> callback) {
            calls.add("subscribe " + owner + " " + section);
            bind.apply().onSuccess(value -> bound.add((Port) value));
            return Result.success(Watched.Subscription.noop());
        }

        @Override
        public Unit publish(String key) {
            return Unit.unit();
        }

        @Override
        public Promise<Unit> releaseAll(String owner) {
            return Promise.unitPromise();
        }

        @Override
        public int subscriberCount(String owner) {
            return 0;
        }

        @Override
        public Promise<Unit> shutdown() {
            return Promise.unitPromise();
        }
    }
}
