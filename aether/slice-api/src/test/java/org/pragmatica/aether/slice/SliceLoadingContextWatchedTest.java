// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// What `ctx.config().watched(...)` does on a deployed slice's loading context, by what the context was built with.
/// A context that has a composite but NO change feed refuses: a `Watched` that bound once and never changed would
/// let the slice believe it is watching. (The explicit non-Aether entry, `ConfigFacade#configFacade`, binds once by
/// the caller's own choice; that is pinned in [ConfigFacadeWatchedTest].)
class SliceLoadingContextWatchedTest {
    private static final String OWNER = "org.example:probe:1.0.0";

    private record Port(int value) {}

    private static Result<Port> bindPort(ConfigFacade facade) {
        return facade.requireInt("server", "port").map(Port::new);
    }

    private SliceLoadingContext contextWith(Option<ConfigWatch> watch) {
        var context = SliceLoadingContext.sliceLoadingContext(noInvoker(), noResources(), OWNER, Option.none(), watch);

        context.setCompositeBuilder(_ -> Option.some(IntrinsicConfigProvider.intrinsicConfigProvider("node", Map.of("server.port", "8080"))));
        context.materializeComposite(getClass().getClassLoader());
        return context;
    }

    @Test
    void watched_refuses_onAContextWithACompositeButNoFeed_whileEveryReadStillWorks() {
        var facade = contextWith(Option.none()).config();

        var result = facade.watched("server", SliceLoadingContextWatchedTest::bindPort);

        assertThat(result.isFailure()).as("a Watched that never changes would be a refusal hiding as a success").isTrue();
        result.onSuccess(_ -> Assertions.fail("the watch-less context handed back a Watched"))
              .onFailure(cause -> assertThat(cause).isSameAs(ConfigFacade.WATCHED_UNAVAILABLE));
        assertThat(facade.requireInt("server", "port").isSuccess()).as("plain reads are unaffected").isTrue();
    }

    @Test
    void watched_subscribesUnderTheOwner_onAContextWithAFeed() {
        var calls = new ArrayList<String>();
        var context = contextWith(Option.some(ConfigWatch.configWatch(new RecordingFeed(calls), OWNER)));

        var watched = context.config().watched("server", SliceLoadingContextWatchedTest::bindPort).unwrap();
        var subscribed = watched.watch(_ -> Promise.unitPromise());

        assertThat(subscribed.isSuccess()).isTrue();
        assertThat(calls).containsExactly("subscribe " + OWNER + " server");
    }

    @Test
    void releaseConfigWatches_releasesTheContextsOwnerOnly() {
        var calls = new ArrayList<String>();
        var context = contextWith(Option.some(ConfigWatch.configWatch(new RecordingFeed(calls), OWNER)));

        context.releaseConfigWatches().await();

        assertThat(calls).containsExactly("release " + OWNER);
    }

    @Test
    void releaseConfigWatches_isANoop_withoutAFeed() {
        assertThat(contextWith(Option.none()).releaseConfigWatches().await().isSuccess()).isTrue();
    }

    private record RecordingFeed(List<String> calls) implements ConfigChangeFeed {
        @Override
        public <T> Result<Watched.Subscription> subscribe(String owner, String section, Fn0<Result<T>> bind, Fn1<Promise<Unit>, T> callback) {
            calls.add("subscribe " + owner + " " + section);
            return Result.success(Watched.Subscription.noop());
        }

        @Override
        public Unit publish(String key) {
            return Unit.unit();
        }

        @Override
        public Promise<Unit> releaseAll(String owner) {
            calls.add("release " + owner);
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

    private static SliceInvokerFacade noInvoker() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact, String methodName, TypeToken<T> requestType, TypeToken<R> responseType) {
                return Causes.cause("no invoker in this test").result();
            }
        };
    }

    private static ResourceProviderFacade noResources() {
        return new ResourceProviderFacade() {
            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return Causes.cause("no resources in this test").promise();
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return Causes.cause("no resources in this test").promise();
            }

            @Override
            public Promise<Unit> releaseAll(String sliceId) {
                return Promise.unitPromise();
            }
        };
    }
}
