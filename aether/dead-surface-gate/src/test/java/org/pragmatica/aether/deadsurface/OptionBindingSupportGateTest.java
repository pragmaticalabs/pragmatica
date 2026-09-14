// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


/// #761 — the enumeration the ticket required before any binder change, kept as a gate so it stays
/// true: every `Option<X>` component reachable from every record `ProviderBasedConfigService` is
/// handed has an X the binder supports (primitive, enum or record). The binder now REFUSES any
/// other X at bind time (`ConfigError.UnsupportedType`, surfacing at slice activation as
/// `SliceLoadingFailure.Fatal.ConfigurationFailed`); this test moves that refusal to build time for
/// the records the product actually declares.
///
/// Roots are what reaches `ProviderBasedConfigService.config(section, Class)` in production:
/// every `ResourceFactory.configType()` the SPI provider binds (discovered through the same
/// `ServiceLoader` `SpiResourceProvider` uses, on this module's corpus classpath) plus the three
/// records `NodeDeploymentState` binds directly and the one `DatasourceConnectionProvider` binds.
/// Slice-declared config records never reach the binder — `FactoryClassGenerator` emits per-component
/// `ConfigFacade.get*` calls for them — so they are deliberately not walked here.
///
/// Boundary of the corpus: a `ResourceFactory` shipped INSIDE a slice jar
/// (`SliceScopedResourceProvider.discoverSliceFactories`) binds its `configType()` through the same
/// binder but is loaded from the slice's own class loader, so no build-time gate can enumerate it;
/// none exists in this repository, and for such a factory the bind-time refusal is the behaviour.
///
/// The corpus is an EXACT expected set, not a floor: the walked records and their `Option<X>`
/// components are named below, so a record vanishing from the classpath (a lost `ServiceLoader`
/// registration — half the corpus starving passed the old `>= 12` floor) or an `Option` component
/// appearing reddens this test with the name. That makes it also the wire-or-delete tripwire for
/// these records: removing one is a deliberate edit here, never a silent shrink.
class OptionBindingSupportGateTest {
    /// Mirror of `ProviderBasedConfigService.primitiveParser`'s accepted set (package-private there).
    private static final Set<Class<?>> BINDER_PRIMITIVES = Set.of(String.class,
                                                                  int.class,
                                                                  Integer.class,
                                                                  long.class,
                                                                  Long.class,
                                                                  boolean.class,
                                                                  Boolean.class,
                                                                  double.class,
                                                                  Double.class,
                                                                  org.pragmatica.lang.io.TimeSpan.class,
                                                                  org.pragmatica.lang.parse.TimeSpan.class,
                                                                  Duration.class);

    /// Every record the walk reaches from the roots (binary names, so nested records are unambiguous).
    private static final Set<String> EXPECTED_RECORDS = Set.of("org.pragmatica.aether.resource.ScheduleConfig",
                                                               "org.pragmatica.aether.resource.TopicConfig",
                                                               "org.pragmatica.aether.resource.db.DatabaseConnectorConfig",
                                                               "org.pragmatica.aether.resource.db.PoolConfig",
                                                               "org.pragmatica.aether.resource.entity.DurableEntityConfig",
                                                               "org.pragmatica.aether.resource.http.HttpClientConfig",
                                                               "org.pragmatica.aether.resource.http.JsonConfig",
                                                               "org.pragmatica.aether.resource.interceptor.CacheConfig",
                                                               "org.pragmatica.aether.resource.interceptor.CircuitBreakerConfig",
                                                               "org.pragmatica.aether.resource.interceptor.IdempotencyConfig",
                                                               "org.pragmatica.aether.resource.interceptor.LogConfig",
                                                               "org.pragmatica.aether.resource.interceptor.MetricsConfig",
                                                               "org.pragmatica.aether.resource.interceptor.RateGuardConfig",
                                                               "org.pragmatica.aether.resource.interceptor.RateLimitConfig",
                                                               "org.pragmatica.aether.resource.interceptor.RetryConfig",
                                                               "org.pragmatica.aether.resource.notification.NotificationConfig",
                                                               "org.pragmatica.aether.resource.notification.RetryConfig",
                                                               "org.pragmatica.aether.slice.PgNotificationConfig",
                                                               "org.pragmatica.aether.slice.RetentionPolicy",
                                                               "org.pragmatica.aether.slice.StreamConfig",
                                                               "org.pragmatica.aether.slice.TierAwareRetention",
                                                               "org.pragmatica.email.http.HttpEmailConfig",
                                                               "org.pragmatica.net.smtp.SmtpAuth",
                                                               "org.pragmatica.net.smtp.SmtpConfig",
                                                               "org.pragmatica.storage.ContentStoreConfig");

    /// Every `Option<X>` component the walk finds, as `Record.component : Option<X>`.
    private static final Set<String> EXPECTED_OPTION_COMPONENTS = Set.of("DatabaseConnectorConfig.asyncUrl : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.database : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.host : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.jdbcUrl : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.name : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.password : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.port : org.pragmatica.lang.Option<java.lang.Integer>",
                                                                         "DatabaseConnectorConfig.r2dbcUrl : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "DatabaseConnectorConfig.type : org.pragmatica.lang.Option<org.pragmatica.aether.resource.db.DatabaseType>",
                                                                         "DatabaseConnectorConfig.username : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "HttpClientConfig.backend : org.pragmatica.lang.Option<org.pragmatica.aether.resource.http.HttpClientConfig$HttpBackend>",
                                                                         "HttpClientConfig.baseUrl : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "HttpClientConfig.json : org.pragmatica.lang.Option<org.pragmatica.aether.resource.http.JsonConfig>",
                                                                         "HttpEmailConfig.endpoint : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "HttpEmailConfig.fromAddress : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "NotificationConfig.httpConfig : org.pragmatica.lang.Option<org.pragmatica.email.http.HttpEmailConfig>",
                                                                         "NotificationConfig.retryConfig : org.pragmatica.lang.Option<org.pragmatica.aether.resource.notification.RetryConfig>",
                                                                         "NotificationConfig.smtpConfig : org.pragmatica.lang.Option<org.pragmatica.net.smtp.SmtpConfig>",
                                                                         "PoolConfig.validationQuery : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "RetentionPolicy.tierAwareRetention : org.pragmatica.lang.Option<org.pragmatica.aether.slice.TierAwareRetention>",
                                                                         "SmtpConfig.auth : org.pragmatica.lang.Option<org.pragmatica.net.smtp.SmtpAuth>",
                                                                         "StreamConfig.encryptionKeyId : org.pragmatica.lang.Option<java.lang.String>",
                                                                         "TopicConfig.minSyncReplicas : org.pragmatica.lang.Option<java.lang.Integer>",
                                                                         "TopicConfig.partitions : org.pragmatica.lang.Option<java.lang.Integer>",
                                                                         "TopicConfig.replicas : org.pragmatica.lang.Option<java.lang.Integer>",
                                                                         "TopicConfig.retention : org.pragmatica.lang.Option<org.pragmatica.lang.parse.TimeSpan>");

    @Test
    void everyOptionComponentReachableFromABinderRoot_hasABindableInnerType() {
        var visited = new HashSet<Class<?>>();
        var unsupported = new TreeSet<String>();
        var optionComponents = new TreeSet<String>();

        binderRoots().forEach(root -> walk(root, visited, optionComponents, unsupported));
        assertEquals(Set.of(),
                     unsupported,
                     "#761: these Option<X> config components have an X the binder cannot bind and will be "
                    + "refused at bind time with ConfigError.UnsupportedType — declare a primitive, enum or "
                    + "record inner type, or teach the binder the new one");
        assertEquals(EXPECTED_RECORDS,
                     recordNames(visited),
                     () -> drift("records", EXPECTED_RECORDS, recordNames(visited))
                          + " A missing one means its ResourceFactory registration or classpath entry is gone "
                          + "(or it was deleted — then delete it here too); a new one must be added here once "
                          + "its Option components have been checked.");
        assertEquals(EXPECTED_OPTION_COMPONENTS,
                     optionComponents,
                     () -> drift("Option<X> components", EXPECTED_OPTION_COMPONENTS, optionComponents)
                          + " Add or remove the named component here after checking its X.");
    }

    private static List<Class<?>> binderRoots() {
        var roots = new ArrayList<Class<?>>();

        ServiceLoader.load(ResourceFactory.class).forEach(factory -> roots.add(factory.configType()));
        roots.add(TopicConfig.class);
        roots.add(ScheduleConfig.class);
        roots.add(StreamConfig.class);
        roots.add(DatabaseConnectorConfig.class);

        return roots;
    }

    private static String drift(String what, Set<String> expected, Set<String> actual) {
        var missing = new TreeSet<>(expected);
        var unexpected = new TreeSet<>(actual);

        missing.removeAll(actual);
        unexpected.removeAll(expected);

        return "Corpus drift: the " + what
             + " reachable from the binder roots differ from this gate's expected set — missing " + missing
             + ", unexpected " + unexpected
             + ".";
    }

    private static Set<String> recordNames(Set<Class<?>> records) {
        var names = new TreeSet<String>();

        records.forEach(record -> names.add(record.getName()));

        return names;
    }

    private static void walk(Class<?> type, Set<Class<?>> visited, Set<String> options, Set<String> unsupported) {
        if (!type.isRecord() || !visited.add(type)) {
            return;
        }

        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getType() == Option.class) {
                var where = type.getSimpleName()
                          + "." + component.getName()
                          + " : " + component.getGenericType().getTypeName();

                options.add(where);
                if (!innerTypeIsBindable(component.getGenericType())) {
                    unsupported.add(where);
                }
            }

            walk(component.getType(), visited, options, unsupported);
            if (component.getGenericType() instanceof ParameterizedType parameterized) {
                for (Type argument : parameterized.getActualTypeArguments()) {
                    if (argument instanceof Class<?> argumentClass) {
                        walk(argumentClass, visited, options, unsupported);
                    }
                }
            }
        }
    }

    /// Exactly `extractOptionValue`'s dispatch: a raw or nested-generic `Option` is case (a); a
    /// plain class that is neither primitive, enum nor record is case (b); both are refused.
    private static boolean innerTypeIsBindable(Type genericType) {
        if (! (genericType instanceof ParameterizedType parameterized)) {
            return false;
        }

        var arguments = parameterized.getActualTypeArguments();

        if (arguments.length != 1 || !(arguments[0] instanceof Class<?> inner)) {
            return false;
        }

        return BINDER_PRIMITIVES.contains(inner) || inner.isEnum() || inner.isRecord();
    }
}
