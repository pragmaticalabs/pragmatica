package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.ClassRegistrator;
import org.pragmatica.serialization.fury.FuryDeserializer;
import org.pragmatica.serialization.fury.FuryFactory;
import org.pragmatica.serialization.fury.FurySerializer;

import java.util.List;

import org.apache.fury.ThreadSafeFury;

/**
 * Fury-based serializer factory provider using singleton pattern.
 * <p>
 * Uses Apache Fury's ThreadSafeFury which is thread-safe and can be safely
 * shared across concurrent method invocations. No pooling required.
 */
public interface FurySerializerFactoryProvider extends SerializerFactoryProvider {
    static FurySerializerFactoryProvider furySerializerFactoryProvider(ClassRegistrator... registrators) {
        record furyProvider(ClassRegistrator[] registrators) implements FurySerializerFactoryProvider {
            @Override
            public SerializerFactory createFactory(List<TypeToken< ? >> typeTokens) {
                // Create single ThreadSafeFury instance for all method invocations
                ThreadSafeFury fury = FuryFactory.fury(registrators);
                // Create singleton serializer and deserializer
                Serializer serializer = FurySerializer.furySerializer(registrators);
                Deserializer deserializer = FuryDeserializer.furyDeserializer(registrators);
                // Return factory that provides same instances (thread-safe)
                return singletonFactory(serializer, deserializer);
            }
        }
        return new furyProvider(registrators);
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
