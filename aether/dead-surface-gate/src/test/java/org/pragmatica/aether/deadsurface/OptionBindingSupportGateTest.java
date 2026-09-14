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

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.ScheduleConfig;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #761 — the enumeration the ticket required before any binder change, kept as a gate so it stays
/// true: every `Option<X>` component reachable from every record `ProviderBasedConfigService` is
/// handed has an X the binder supports (primitive, enum or record). The binder now REFUSES any
/// other X at bind time (`ConfigError.UnsupportedType`); this test moves that refusal to build time
/// for the records the product actually declares.
///
/// Roots are what reaches `ProviderBasedConfigService.config(section, Class)` in production:
/// every `ResourceFactory.configType()` the SPI provider binds (discovered through the same
/// `ServiceLoader` `SpiResourceProvider` uses, on this module's corpus classpath) plus the three
/// records `NodeDeploymentState` binds directly and the one `DatasourceConnectionProvider` binds.
/// Slice-declared config records never reach the binder — `FactoryClassGenerator` emits per-component
/// `ConfigFacade.get*` calls for them — so they are deliberately not walked here.
class OptionBindingSupportGateTest {
    /// Mirror of `ProviderBasedConfigService.primitiveParser`'s accepted set (package-private there).
    private static final Set<Class<?>> BINDER_PRIMITIVES = Set.of(String.class,
                                                                  int.class, Integer.class,
                                                                  long.class, Long.class,
                                                                  boolean.class, Boolean.class,
                                                                  double.class, Double.class,
                                                                  org.pragmatica.lang.io.TimeSpan.class,
                                                                  org.pragmatica.lang.parse.TimeSpan.class,
                                                                  Duration.class);

    /// The factory-declared roots this corpus must at least contain — the count measured when the
    /// gate was written. A smaller discovery means the classpath starved, not that the set shrank.
    private static final int MINIMUM_FACTORY_ROOTS = 12;

    @Test
    void everyOptionComponentReachableFromABinderRoot_hasABindableInnerType() {
        var roots = binderRoots();

        assertTrue(roots.size() >= MINIMUM_FACTORY_ROOTS,
                   "Corpus incomplete: only " + roots.size() + " binder roots discovered (" + roots
                   + "); the ResourceFactory ServiceLoader set on this classpath is smaller than when this gate was written");

        var visited = new HashSet<Class<?>>();
        var unsupported = new TreeSet<String>();
        var optionComponents = new ArrayList<String>();

        roots.forEach(root -> walk(root, visited, optionComponents, unsupported));

        assertTrue(optionComponents.size() >= 20,
                   "Instrument check: the walk found only " + optionComponents.size()
                   + " Option components; 26 were enumerated when this gate was written");
        assertEquals(Set.of(),
                     unsupported,
                     "#761: these Option<X> config components have an X the binder cannot bind and will be "
                     + "refused at bind time with ConfigError.UnsupportedType — declare a primitive, enum or "
                     + "record inner type, or teach the binder the new one");
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

    private static void walk(Class<?> type, Set<Class<?>> visited, List<String> options, Set<String> unsupported) {
        if (!type.isRecord() || !visited.add(type)) {
            return;
        }

        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getType() == Option.class) {
                var where = type.getSimpleName() + "." + component.getName() + " : " + component.getGenericType().getTypeName();

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
        if (!(genericType instanceof ParameterizedType parameterized)) {
            return false;
        }

        var arguments = parameterized.getActualTypeArguments();

        if (arguments.length != 1 || !(arguments[0] instanceof Class<?> inner)) {
            return false;
        }

        return BINDER_PRIMITIVES.contains(inner) || inner.isEnum() || inner.isRecord();
    }
}
