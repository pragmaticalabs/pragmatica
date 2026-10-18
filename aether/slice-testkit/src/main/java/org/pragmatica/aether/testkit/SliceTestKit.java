// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.resource.notification.NotificationSender;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.testkit.MapResourceProvider.ResourceKey;
import org.pragmatica.aether.testkit.container.ContainerResource;
import org.pragmatica.aether.testkit.fake.CapturingNotificationSender;
import org.pragmatica.aether.testkit.fake.CapturingPublisher;
import org.pragmatica.aether.testkit.fake.FakeHttpClient;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.io.TimeSpan;


/// Fluent entry point for the slice test kit (spec §3.1). Spins ONE slice through its real generated
/// `{Interface}Factory` typed method, injecting a fake or testcontainer-backed resource per
/// coordinate, and hands back a [SliceUnderTest] driven via the slice's own typed interface.
///
/// ```java
/// try (var sut = SliceTestKit.forSlice(OrderIntakeFactory::orderIntake)
///                            .withContainer(PgSqlConnector.class, "database", Containers.postgres().withSchemaFrom("schema/"))
///                            .withHttp("http", inventory)
///                            .withPublisher("order-events", events)
///                            .build()) {
///     var response = sut.client().place(new PlaceRequest("ABC", 2)).await(...);
/// }
/// ```
public final class SliceTestKit<T> {
    private static final String CONFIG_SOURCE = "slice-test-kit";
    private static final String SLICE_UNDER_TEST = "slice-under-test";
    private static final TimeSpan BUILD_TIMEOUT = TimeSpan.timeSpan(60).seconds();

    private final Fn1<Promise<T>, SliceCreationContext> factory;
    private final Map<ResourceKey, Object> resources = new LinkedHashMap<>();
    private final Map<ResourceKey, ContainerResource<?>> containerSpecs = new LinkedHashMap<>();
    private Option<ConfigurationProvider> config = Option.none();

    private SliceTestKit(Fn1<Promise<T>, SliceCreationContext> factory) {
        this.factory = factory;
    }

    /// Start a kit for the slice built by a generated typed factory method, e.g.
    /// `OrderIntakeFactory::orderIntake`.
    public static <T> SliceTestKit<T> forSlice(Fn1<Promise<T>, SliceCreationContext> factory) {
        return new SliceTestKit<>(factory);
    }

    /// Register a fake (or any instance) for one `(resourceType, configSection)` coordinate.
    public <R> SliceTestKit<T> withResource(Class<R> resourceType, String configSection, R instance) {
        resources.put(new ResourceKey(resourceType, configSection), instance);

        return this;
    }

    /// Register a capturing pub-sub publisher for `topic` (also captured for `published(topic)`).
    public <E> SliceTestKit<T> withPublisher(String topic, CapturingPublisher<E> publisher) {
        resources.put(new ResourceKey(Publisher.class, topic), publisher);

        return this;
    }

    /// Register a scripted HTTP fake for `section` (also captured for `httpCalls(section)`).
    public SliceTestKit<T> withHttp(String section, FakeHttpClient client) {
        resources.put(new ResourceKey(HttpClient.class, section), client);

        return this;
    }

    /// Register a capturing notification sender (section `notification`).
    public SliceTestKit<T> withNotifications(CapturingNotificationSender sender) {
        resources.put(new ResourceKey(NotificationSender.class, "notification"), sender);

        return this;
    }

    /// Supply configuration as flat `section.key` entries, e.g. `"deployed.endpoint.port"`. The slice reads it through
    /// the same facade a deployed slice gets, so `@ResourceQualifier(type = ConfigurationSection.class)` parameters are
    /// parsed by the real generated factory code and delivered as the parsed record (#897).
    public SliceTestKit<T> withConfig(Map<String, String> values) {
        return withConfig(IntrinsicConfigProvider.intrinsicConfigProvider(CONFIG_SOURCE, values));
    }

    /// Supply configuration from any provider, e.g. one layering a TOML document over overrides.
    public SliceTestKit<T> withConfig(ConfigurationProvider provider) {
        config = Option.some(provider);

        return this;
    }

    /// Provision this coordinate via a testcontainer instead of a fake (spec §5.2).
    public SliceTestKit<T> withContainer(Class<?> resourceType, String configSection, ContainerResource<?> spec) {
        containerSpecs.put(new ResourceKey(resourceType, configSection), spec);

        return this;
    }

    /// Provision containers, resolve the slice through its factory, and hand back the driven handle.
    /// Fails the test fast (spec §7.1 MVP-6) if the slice asks for an unregistered resource.
    @TerminalOperation
    public SliceUnderTest<T> build() {
        var provisioned = provisionContainers();
        var provider = MapResourceProvider.mapResourceProvider(resources);
        var ctx = creationContext(provider);

        return factory.apply(ctx)
                      .await(BUILD_TIMEOUT)
                      .fold(TestKitFailures::raise,
                            client -> DefaultSliceUnderTest.defaultSliceUnderTest(client, resources, provisioned));
    }

    private SliceCreationContext creationContext(MapResourceProvider provider) {
        return config.map(source -> ConfigFacade.configFacade(SLICE_UNDER_TEST, source))
                     .map(facade -> SliceCreationContext.sliceCreationContext(NoOpSliceInvoker.INSTANCE,
                                                                              provider,
                                                                              facade))
                     .or(() -> SliceCreationContext.sliceCreationContext(NoOpSliceInvoker.INSTANCE, provider));
    }

    private List<ContainerResource<?>> provisionContainers() {
        containerSpecs.forEach((key, spec) -> resources.put(key, provisionOne(spec)));

        return List.copyOf(containerSpecs.values());
    }

    @TerminalOperation
    private Object provisionOne(ContainerResource<?> spec) {
        return spec.provision()
                   .await(BUILD_TIMEOUT)
                   .fold(TestKitFailures::raise, value -> value);
    }
}
