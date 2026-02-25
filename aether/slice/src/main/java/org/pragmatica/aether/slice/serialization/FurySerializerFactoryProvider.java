package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.fury.FuryDeserializer;
import org.pragmatica.serialization.fury.FurySerializer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.fury.Fury;
import org.apache.fury.config.Language;

/// Fury-based serializer factory provider with class-ID-based registration.
///
/// Uses {@code requireClassRegistration(true)} with explicit hash-based IDs for
/// slice classes and sequential IDs for core framework classes. This ensures
/// cross-slice serialization compatibility: hash IDs derived from class FQN
/// produce the same ID regardless of which bridge serializes/deserializes.
///
/// Core classes (Option, Result, Unit) from FrameworkClassLoader get sequential
/// IDs since they're the same Class objects in every bridge.
///
/// Slice classes get deterministic hash-based IDs in range [10000, 30000) to
/// avoid overlap with Fury built-in types (0-255) and core sequential IDs (256+).
public interface FurySerializerFactoryProvider extends SerializerFactoryProvider {
    static FurySerializerFactoryProvider furySerializerFactoryProvider() {
        return FurySerializerFactoryProvider::buildFactory;
    }

    private static Result<SerializerFactory> buildFactory(List<Class<?>> serializableClasses,
                                                          ClassLoader sliceClassLoader) {
        org.apache.fury.logging.LoggerFactory.useSlf4jLogging(true);
        int coreCount = Runtime.getRuntime()
                               .availableProcessors();
        var fury = Fury.builder()
                       .withLanguage(Language.JAVA)
                       .requireClassRegistration(true)
                       .withClassLoader(sliceClassLoader)
                       .buildThreadSafeFuryPool(coreCount * 2, coreCount * 4);
        // 1. Register core classes (sequential IDs — same Class objects everywhere)
        SliceCoreClasses.INSTANCE.classesToRegister()
                        .forEach(fury::register);
        // 2. Expand slice classes recursively (record fields may reference other types)
        var expanded = expandRecursively(serializableClasses);
        // 3. Register slice classes with deterministic hash-based IDs
        return registerWithDeterministicIds(fury, expanded)
        .map(_ -> singletonFactory(FurySerializer.furySerializer(fury), FuryDeserializer.furyDeserializer(fury)));
    }

    private static Result<Unit> registerWithDeterministicIds(org.apache.fury.ThreadSafeFury fury,
                                                             Set<Class<?>> expanded) {
        var idToClass = new HashMap<Integer, Class<?>>();
        for (var clazz : expanded) {
            var id = deterministicId(clazz.getName());
            var existing = idToClass.put(id, clazz);
            if (existing != null && !existing.equals(clazz)) {
                return Result.failure(Causes.cause("Hash collision: " + clazz.getName() + " and " + existing.getName()
                                                   + " both map to ID " + id));
            }
            fury.register(clazz, (short) id);
        }
        return Result.unitResult();
    }

    /// Recursively expand classes to include user-defined field types.
    /// Records' components and class fields may reference types that also
    /// need registration with requireClassRegistration(true).
    private static Set<Class<?>> expandRecursively(List<Class<?>> roots) {
        var result = new LinkedHashSet<Class<?>>();
        var queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            var clazz = queue.poll();
            if (!result.add(clazz)) {
                continue;
            }
            if (clazz.isRecord()) {
                for (var component : clazz.getRecordComponents()) {
                    addIfUserDefined(component.getType(), queue, result);
                }
            }
            for (var field : clazz.getDeclaredFields()) {
                addIfUserDefined(field.getType(), queue, result);
            }
        }
        return result;
    }

    private static void addIfUserDefined(Class<?> type, ArrayDeque<Class<?>> queue, Set<Class<?>> seen) {
        if (type.isPrimitive() || type.isArray()) {
            return;
        }
        var name = type.getName();
        if (name.startsWith("java.") || name.startsWith("org.pragmatica.lang.")) {
            return;
        }
        if (!seen.contains(type)) {
            queue.add(type);
        }
    }

    /// Deterministic ID from class name. Range [10000, 30000) avoids overlap with
    /// Fury built-ins (0-255) and core sequential IDs (256+).
    private static int deterministicId(String className) {
        return (className.hashCode() & 0x7FFFFFFF) % 20000 + 10000;
    }

    private static SerializerFactory singletonFactory(Serializer serializer, Deserializer deserializer) {
        record singletonFactory(Serializer theSerializer, Deserializer theDeserializer) implements SerializerFactory {
            @Override
            public Promise<Serializer> serializer() {
                return Promise.success(theSerializer);
            }

            @Override
            public Promise<Deserializer> deserializer() {
                return Promise.success(theDeserializer);
            }
        }
        return new singletonFactory(serializer, deserializer);
    }
}
