// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.NodeShareable;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PoolScope;
import org.pragmatica.aether.slice.ProvisioningContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// T13 (#2973 D3.4): this module's OWN `META-INF/services` ships exactly the expected [NodeShareable] factories, none
/// of which declares `provision(C, ProvisioningContext)` or `sectionBinder()`. The enumeration is positive, so an
/// empty scan fails.
class NodeShareableContractTest {
    private static final Path SERVICES = Path.of("target/classes/META-INF/services/org.pragmatica.aether.resource.ResourceFactory");

    private static List<String> services() {
        try {
            return Files.readAllLines(SERVICES)
                        .stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
        } catch (IOException e) {
            throw new AssertionError("cannot read " + SERVICES.toAbsolutePath(), e);
        }
    }

    private static List<String> shareableServices() {
        return services().stream()
                         .filter(name -> NodeShareable.class.isAssignableFrom(load(name)))
                         .toList();
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(name, e);
        }
    }

    private static boolean declaresForbidden(Method method) {
        return method.getName().equals("sectionBinder") && method.getParameterCount() == 0
               || method.getName().equals("provision") && method.getParameterCount() == 2
                  && method.getParameterTypes()[1] == ProvisioningContext.class;
    }

    @Test
    void shipsExactlyTheExpectedMarkers_withoutForbiddenOverrides() {
        assertThat(Set.copyOf(shareableServices())).isEqualTo(Set.of("org.pragmatica.aether.resource.db.async.AsyncSqlConnectorFactory",
                                       "org.pragmatica.aether.resource.db.async.PgSqlConnectorFactory"));

        shareableServices().forEach(name -> assertThat(Arrays.stream(load(name).getDeclaredMethods())
                                                             .filter(NodeShareableContractTest::declaresForbidden)
                                                             .toList()).as(name)
                                                                      .isEmpty());
    }

    @Test
    void theNotificationListener_isNotAMarker() {
        assertThat(services()).contains("org.pragmatica.aether.resource.db.async.NotificationListenerFactory");
        assertThat(shareableServices()).doesNotContain("org.pragmatica.aether.resource.db.async.NotificationListenerFactory");
    }

    @SuppressWarnings("unchecked")
    @Test
    void shareKey_followsTheConnectionInputs_andPoolScope() throws Exception {
        for (var name : shareableServices()) {
            var factory = (NodeShareable<Object, DatabaseConnectorConfig>) load(name).getDeclaredConstructor().newInstance();
            var reference = config("h", "db-a", "p", null);

            assertThat(factory.shareKey(reference)).as(name).isEqualTo(factory.shareKey(config("h", "db-b", "p", null)));
            assertThat(factory.shareKey(reference)).as(name).isNotEqualTo(factory.shareKey(config("other", "db-a", "p", null)));
            assertThat(factory.shareKey(reference)).as(name).isNotEqualTo(factory.shareKey(config("h", "db-a", "q", null)));
            assertThat(factory.shareKey(config("h", "db-a", "p", PoolScope.SLICE)).isEmpty()).as(name).isTrue();
            assertThat(factory.demand(reference).max()).as(name).isEqualTo(reference.poolConfig().maxConnections());
            assertThat(factory.demand(reference).warm()).as("%s: the transport's real warm-up", name).isEqualTo(4);
            assertThat(factory.demand(reference).endpoint()).as(name).doesNotContain("p@").contains("h:5432");
        }
    }

    private static DatabaseConnectorConfig config(String host, String name, String password, PoolScope scope) {
        var builder = databaseConnectorConfigBuilder().withName(name)
                                                      .withAsyncUrl("postgresql://" + host + ":5432/db")
                                                      .withJdbcUrl("jdbc:postgresql://" + host + ":5432/db")
                                                      .withR2dbcUrl("r2dbc:postgresql://" + host + ":5432/db")
                                                      .withUsername("u")
                                                      .withPassword(password)
                                                      // explicit: pg-async derives its application name from the
                                                      // datasource name when no node id is set, and the name it sends
                                                      // is part of the key
                                                      .withApplicationName("app");

        return (scope == null ? builder : builder.withPoolScope(scope)).build().unwrap();
    }
}
