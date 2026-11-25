// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.interceptor.MetricsMethodInterceptor;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

class TerraApplicationTest {
    record First(int value) {}
    record Second(First first) {}
    record Factory<T>(String artifact, Class<T> sliceType, List<Class<?>> dependencies,
                       Fn1<Promise<T>, TerraContext> constructor) implements TerraFactory<T> {
        public Promise<T> create(TerraContext context) { return constructor.apply(context); }
    }
    private static final ConfigurationProvider CONFIG = ConfigurationProvider.builder().build();
    private static final ResourceProviderFacade NO_RESOURCES = new ResourceProviderFacade() {
        public <T> Promise<T> provide(Class<T> type, String section) { return cause("Unexpected acquisition").promise(); }
        public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) { return provide(type, section); }
        public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
    };

    @Test void start_reverseBlueprintOrder_injectsConstructedDependency() {
        var first = new Factory<>("g:first:1", First.class, List.of(), _ -> Promise.success(new First(42)));
        var second = new Factory<>("g:second:1", Second.class, List.<Class<?>>of(First.class), ctx -> ctx.slice(First.class).map(Second::new));
        var app = TerraApplication.start(new TerraBlueprint(List.of(second.artifact(), first.artifact())),
            List.of(first, second), _ -> CONFIG, NO_RESOURCES).await(timeSpan(5).seconds()).unwrap();
        assertThat(app.slice(Second.class).unwrap().first()).isSameAs(app.slice(First.class).unwrap());
        assertThat(app.close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(app.close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    @Test void start_missingDependency_refusesBeforeConstruction() {
        var attempts = new AtomicInteger();
        var factory = new Factory<>("g:second:1", Second.class, List.<Class<?>>of(First.class), _ -> {
            attempts.incrementAndGet();
            return Promise.success(new Second(new First(1)));
        });
        var result = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> CONFIG, NO_RESOURCES)
            .await(timeSpan(5).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(attempts).hasValue(0);
    }

    @Test void start_failedAcquisition_waitsForLateSuccessBeforeRelease() {
        var late = Promise.<Object>promise();
        var released = new AtomicInteger();
        ResourceProviderFacade provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return provide(type, section, ProvisioningContext.provisioningContext()); }
            @SuppressWarnings("unchecked") public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) {
                return section.equals("late") ? (Promise<T>) late : cause("failed").promise();
            }
            public Promise<Unit> releaseAll(String scope) {
                assertThat(late.isResolved()).isTrue();
                assertThat(scope).isEqualTo("g:first:1");
                released.incrementAndGet();
                return Promise.unitPromise();
            }
        };
        var factory = new Factory<>("g:first:1", First.class, List.of(), ctx ->
            Promise.all(ctx.resources().provide(Object.class, "late"), ctx.resources().provide(Object.class, "fail"))
                   .map((a, b) -> new First(1)));
        var startup = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> CONFIG, provider);
        assertThat(startup.isResolved()).isFalse();
        late.succeed(new Object());
        assertThat(startup.await(timeSpan(5).seconds()).isFailure()).isTrue();
        assertThat(released).hasValue(1);
    }

    @Test void parse_unsupportedConfiguration_refusesInsteadOfIgnoring() {
        assertThat(TerraBlueprint.parse("[[slices]]\nartifact='g:a:1'\n[security]\nmode='jwt'").isFailure()).isTrue();
        assertThat(TerraBlueprint.parse("[[slices]]\nartifact='g:a:1'\ninstances=3").isSuccess()).isTrue();
        assertThat(TerraBlueprint.parse("[[slices]]\nartifact='g:a:1'\n[[slices]]\nartifact='g:a:1'").isFailure()).isTrue();
        assertThat(TerraBlueprint.parse("[blueprint]\nunknown='ignored?'\n[[slices]]\nartifact='g:a:1'").isFailure()).isTrue();
    }

    @Test void start_factoryAndCleanupFail_releasesEveryScopeAndPreservesBothFailures() {
        var released = new java.util.ArrayList<String>();
        ResourceProviderFacade provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return cause("unused").promise(); }
            public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) {
                released.add(scope);
                if (scope.equals("g:second:1")) { throw new IllegalStateException("cleanup defect"); }
                return Promise.unitPromise();
            }
        };
        var first = new Factory<>("g:first:1", First.class, List.of(), _ -> Promise.success(new First(42)));
        var second = new Factory<>("g:second:1", Second.class, List.<Class<?>>of(First.class), _ -> cause("factory failed").promise());
        var result = TerraApplication.start(new TerraBlueprint(List.of(first.artifact(), second.artifact())),
            List.of(first, second), _ -> CONFIG, provider).await(timeSpan(5).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(result.<String>fold(c -> c.message(), _ -> "unexpected success")).contains("factory failed", "cleanup defect");
        assertThat(released).containsExactly(second.artifact(), first.artifact());
    }

    @Test void start_durableTopic_refusesInsteadOfDowngrading() {
        var config = ConfigurationProvider.builder().withDefaults(Map.of("events.durability", "durable")).build();
        var factory = new Factory<>("g:first:1", First.class, List.of(), ctx ->
            ctx.resources().provide(org.pragmatica.aether.slice.Publisher.class, "events").map(_ -> new First(1)));
        var result = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> config, NO_RESOURCES)
            .await(timeSpan(5).seconds());
        assertThat(result.<String>fold(c -> c.message(), _ -> "unexpected success")).contains("ephemeral topics only");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"factory promise", "slice", "binding result", "resource promise", "cleanup promise"})
    void start_nullExtensionReturn_failsAndReleasesEveryScope(String defect) {
        var released = new java.util.ArrayList<String>();
        ResourceProviderFacade provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return null; }
            public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) { return provide(type, section); }
            public Promise<Unit> releaseAll(String scope) {
                released.add(scope);
                return defect.equals("cleanup promise") && scope.equals("g:second:1") ? null : Promise.unitPromise();
            }
        };
        var first = new Factory<>("g:first:1", First.class, List.of(), _ -> Promise.success(new First(42)));
        TerraFactory<Second> second = new TerraFactory<>() {
            public String artifact() { return "g:second:1"; }
            public Class<Second> sliceType() { return Second.class; }
            public List<Class<?>> dependencies() { return List.of(First.class); }
            public Promise<Second> create(TerraContext context) {
                return switch (defect) {
                    case "factory promise" -> null;
                    case "slice" -> Promise.success(null);
                    case "resource promise" -> context.resources().provide(First.class, "test").map(Second::new);
                    case "cleanup promise" -> cause("construction failed").promise();
                    default -> context.slice(First.class).map(Second::new);
                };
            }
            public Result<Unit> bind(Second instance, TerraContext context) { return defect.equals("binding result") ? null : Result.unitResult(); }
        };
        var result = TerraApplication.start(new TerraBlueprint(List.of(first.artifact(), second.artifact())),
            List.of(first, second), _ -> CONFIG, provider).await(timeSpan(2).seconds());
        assertThat(result.<String>fold(c -> c.message(), _ -> "unexpected success")).contains("null " + defect);
        assertThat(released).containsExactly(second.artifact(), first.artifact());
    }

    /// #3650 item 1: `@Metrics` provisions on Terra. Before, the provisioning context carried no `MeterRegistry` and
    /// `MetricsInterceptorFactory` failed with "Context does not contain MeterRegistry".
    @Test void start_metricsInterceptor_provisionsAndRecordsIntoTheApplicationRegistry() {
        var config = ConfigurationProvider.builder().withDefaults(Map.of("metrics.greet.name", "greet.calls")).build();
        var interceptor = new java.util.concurrent.atomic.AtomicReference<MetricsMethodInterceptor>();
        var factory = new Factory<>("g:first:1", First.class, List.of(), ctx ->
            ctx.resources().provide(MetricsMethodInterceptor.class, "metrics.greet").onSuccess(interceptor::set).map(_ -> new First(1)));
        var resources = SpiResourceProvider.spiResourceProvider((_, _) -> cause("unscoped").result()).facade();
        var app = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> config, resources)
            .await(timeSpan(5).seconds());

        assertThat(app.<String>fold(c -> c.message(), _ -> "started")).isEqualTo("started");
        var metrics = app.unwrap().metrics();
        Fn1<Promise<String>, String> method = name -> Promise.success("hello " + name);
        assertThat(interceptor.get().intercept(method).apply("terra").await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(metrics.registry().find("greet.calls.success").timer().count()).isEqualTo(1);
        assertThat(app.unwrap().close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
        assertThat(metrics.registry().isClosed()).as("closing the application closes its metric sinks").isTrue();
    }

    /// The application registry is a default, never an override (#526 rule, as on Aether).
    @Test void start_callerSuppliedMeterRegistry_isKeptOverTheApplicationRegistry() {
        var seen = new CopyOnWriteArrayList<MeterRegistry>();
        var own = new SimpleMeterRegistry();
        ResourceProviderFacade provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return cause("unused").promise(); }
            @SuppressWarnings("unchecked") public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) {
                seen.add(context.extension(MeterRegistry.class).unwrap());
                return Promise.success((T) section);
            }
            public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
        };
        var factory = new Factory<>("g:first:1", First.class, List.of(), ctx ->
            Promise.all(ctx.resources().provide(String.class, "plain"),
                        ctx.resources().provide(String.class, "own", ProvisioningContext.provisioningContext().withExtension(MeterRegistry.class, own)))
                   .map((a, b) -> new First(1)));
        var app = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> CONFIG, provider)
            .await(timeSpan(5).seconds()).unwrap();

        assertThat(seen).containsExactly(app.metrics().registry(), own);
        assertThat(app.close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }

    @Test void start_failedConstruction_closesTheMetricSinks() {
        var registry = new java.util.concurrent.atomic.AtomicReference<MeterRegistry>();
        ResourceProviderFacade provider = new ResourceProviderFacade() {
            public <T> Promise<T> provide(Class<T> type, String section) { return cause("unused").promise(); }
            public <T> Promise<T> provide(Class<T> type, String section, ProvisioningContext context) {
                registry.set(context.extension(MeterRegistry.class).unwrap());
                return cause("acquisition failed").promise();
            }
            public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
        };
        var factory = new Factory<>("g:first:1", First.class, List.of(), ctx -> ctx.resources().provide(Object.class, "fail").map(_ -> new First(1)));
        var result = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> CONFIG, provider)
            .await(timeSpan(5).seconds());

        assertThat(result.isFailure()).isTrue();
        assertThat(registry.get().isClosed()).as("a refused start leaves no open sink behind").isTrue();
    }
}
