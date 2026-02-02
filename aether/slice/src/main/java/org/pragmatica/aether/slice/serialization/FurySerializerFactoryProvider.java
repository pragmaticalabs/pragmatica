package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.ClassRegistrator;
import org.pragmatica.serialization.fury.FuryDeserializer;
import org.pragmatica.serialization.fury.FurySerializer;

import java.util.List;

/**
 * Fury-based serializer factory provider using singleton pattern.
 * <p>
 * Uses Apache Fury's ThreadSafeFury which is thread-safe and can be safely
 * shared across concurrent method invocations. No pooling required.
 */
public interface FurySerializerFactoryProvider extends SerializerFactoryProvider {
    static FurySerializerFactoryProvider furySerializerFactoryProvider(ClassRegistrator... registrators) {
        return typeTokens -> {
            // Create singleton serializer and deserializer (ThreadSafeFury is thread-safe)
            Serializer serializer = FurySerializer.furySerializer(registrators);
            Deserializer deserializer = FuryDeserializer.furyDeserializer(registrators);
            return singletonFactory(serializer, deserializer);
        };
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
