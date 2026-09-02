// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Reflectively walks a config record tree and lists every accessor as a target for the #519
/// dead-config-accessor gate — no marker interface, no annotation on ~20 record files, no hand-written
/// registry to keep in sync with the tree it describes: the record tree already declares its own shape
/// via `getRecordComponents()`, so scope discovery just reads it.
///
/// Descends into nested record components directly (`OperationsConfig` inside `ClusterBootstrapConfig`)
/// and into the type arguments of generic components (`Map<String, SourceProfile>` -> `SourceProfile`),
/// since both are real ways a record tree nests another record.
final class ConfigRecordScope {
    private ConfigRecordScope() {}

    record Accessor(Class<?> declaringClass, java.lang.reflect.Method accessorMethod) {
        MethodRef toMethodRef() {
            return MethodRef.of(accessorMethod);
        }
    }

    static List<Accessor> walk(Class<?> root) {
        var accessors = new ArrayList<Accessor>();

        walk(root, new HashSet<>(), accessors);

        return accessors;
    }

    private static void walk(Class<?> type, Set<Class<?>> visited, List<Accessor> accessors) {
        if (!type.isRecord() || !visited.add(type)) {
            return;
        }

        for (RecordComponent component : type.getRecordComponents()) {
            accessors.add(new Accessor(type, component.getAccessor()));

            walk(component.getType(), visited, accessors);

            for (Class<?> typeArgument : genericTypeArguments(component.getGenericType())) {
                walk(typeArgument, visited, accessors);
            }
        }
    }

    private static List<Class<?>> genericTypeArguments(Type genericType) {
        if (!(genericType instanceof ParameterizedType parameterizedType)) {
            return List.of();
        }

        var result = new ArrayList<Class<?>>();

        for (Type argument : parameterizedType.getActualTypeArguments()) {
            if (argument instanceof Class<?> classArgument) {
                result.add(classArgument);
            }
        }

        return result;
    }
}
