// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.resource.notification.Notification;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.testkit.MapResourceProvider.ResourceKey;
import org.pragmatica.aether.testkit.assertion.DbAssertions;
import org.pragmatica.aether.testkit.container.ContainerResource;
import org.pragmatica.aether.testkit.fake.CapturingNotificationSender;
import org.pragmatica.aether.testkit.fake.CapturingPublisher;
import org.pragmatica.aether.testkit.fake.FakeHttpClient;
import org.pragmatica.aether.testkit.fake.HttpCall;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Option.option;


/// Default [SliceUnderTest]. Capture handles are resolved by looking up the resource map at the same
/// `(type, section)` coordinate the generated factory used — so a `CapturingPublisher` at topic
/// `T` is found under `(Publisher, T)`, a `FakeHttpClient` under `(HttpClient, section)`, etc.
record DefaultSliceUnderTest<T>(T client, Map<ResourceKey, Object> resources, List<ContainerResource<?>> containers) implements SliceUnderTest<T> {
    static <T> SliceUnderTest<T> defaultSliceUnderTest(T client,
                                                       Map<ResourceKey, Object> resources,
                                                       List<ContainerResource<?>> containers) {
        return new DefaultSliceUnderTest<>(client, Map.copyOf(resources), List.copyOf(containers));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E> List<E> published(String topic) {
        return (List<E>) lookup(Publisher.class, topic, CapturingPublisher.class).published();
    }

    @Override
    public List<Notification> notifications() {
        return resources.values()
                        .stream()
                        .filter(CapturingNotificationSender.class::isInstance)
                        .map(CapturingNotificationSender.class::cast)
                        .flatMap(sender -> sender.sent()
                                                 .stream())
                        .toList();
    }

    @Override
    public List<HttpCall> httpCalls(String section) {
        return lookup(HttpClient.class, section, FakeHttpClient.class).calls();
    }

    @Override
    public DbAssertions db(String section) {
        return DbAssertions.dbAssertions(connector(section));
    }

    /// AutoCloseable lifecycle boundary — stops provisioned containers; no meaningful return.
    @Override
    @Contract
    public void close() {
        containers.forEach(ContainerResource::stop);
    }

    private <X> X lookup(Class<?> type, String section, Class<X> expected) {
        return option(resources.get(new ResourceKey(type, section))).filter(expected::isInstance)
                     .map(expected::cast)
                     .fold(() -> TestKitFailures.raise(missing(expected.getSimpleName(),
                                                               type,
                                                               section)),
                           value -> value);
    }

    private SqlConnector connector(String section) {
        return option(resources.get(new ResourceKey(PgSqlConnector.class, section))).orElse(() -> option(resources.get(new ResourceKey(SqlConnector.class,
                                                                                                                                       section))))
                     .filter(SqlConnector.class::isInstance)
                     .map(SqlConnector.class::cast)
                     .fold(() -> TestKitFailures.raise(missing(SqlConnector.class.getSimpleName(),
                                                               SqlConnector.class,
                                                               section)),
                           value -> value);
    }

    private static Cause missing(String expected, Class<?> type, String section) {
        return new TestKitError.UnscriptedInteraction("No " + expected
                                                     + " registered for " + type.getSimpleName()
                                                     + ":" + section
                                                     + " — register it before build() to assert on it.");
    }
}
