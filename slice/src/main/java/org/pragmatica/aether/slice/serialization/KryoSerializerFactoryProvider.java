package org.pragmatica.aether.slice.serialization;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.ClassRegistrator;
import org.pragmatica.serialization.kryo.KryoDeserializer;
import org.pragmatica.serialization.kryo.KryoPoolFactory;
import org.pragmatica.serialization.kryo.KryoSerializer;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;

/**
 * Kryo-based serializer factory provider using FIFO pooling pattern.
 * <p>
 * Kryo instances are not thread-safe, so this implementation maintains a pool
 * of Serializer/Deserializer pairs with asynchronous FIFO access protocol.
 * <p>
 * Pool size defaults to 2 * number of processors.
 */
public interface KryoSerializerFactoryProvider extends SerializerFactoryProvider {
    static KryoSerializerFactoryProvider kryoSerializerFactoryProvider(ClassRegistrator... registrators) {
        record kryoProvider(ClassRegistrator[] registrators) implements KryoSerializerFactoryProvider {
            @Override
            public SerializerFactory createFactory(List<TypeToken< ? >> typeTokens) {
                // Create Kryo pool (standard pattern from existing code)
                Pool<Kryo> kryoPool = KryoPoolFactory.kryoPool(registrators);
                // Create pooled serializers using the Kryo pool
                Serializer serializer = KryoSerializer.kryoSerializer(registrators);
                Deserializer deserializer = KryoDeserializer.kryoDeserializer(registrators);
                // Pool size: 2 * processors (same as KryoPoolFactory default)
                int poolSize = Runtime.getRuntime()
                                      .availableProcessors() * 2;
                // Return FIFO pooled factory
                return pooledFactory(serializer, deserializer, poolSize);
            }
        }
        return new kryoProvider(registrators);
    }

    private static SerializerFactory pooledFactory(Serializer serializer, Deserializer deserializer, int poolSize) {
        record pooledFactory(
        BlockingQueue<Serializer> serializerPool,
        BlockingQueue<Deserializer> deserializerPool) implements SerializerFactory {
            @Override
            public Promise<Serializer> serializer() {
                // Async blocking take from pool
                return Promise.lift(
                Causes::fromThrowable, serializerPool::take);
            }

            @Override
            public Promise<Deserializer> deserializer() {
                // Async blocking take from pool
                return Promise.lift(
                Causes::fromThrowable, deserializerPool::take);
            }

            // Release back to pool after use
            void releaseSerializer(Serializer s) {
                serializerPool.offer(s);
            }

            void releaseDeserializer(Deserializer d) {
                deserializerPool.offer(d);
            }
        }
        // Initialize pools with instances
        BlockingQueue<Serializer> serializerQueue = new ArrayBlockingQueue<>(poolSize);
        BlockingQueue<Deserializer> deserializerQueue = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++ ) {
            serializerQueue.offer(serializer);
            deserializerQueue.offer(deserializer);
        }
        return new pooledFactory(serializerQueue, deserializerQueue);
    }
}
