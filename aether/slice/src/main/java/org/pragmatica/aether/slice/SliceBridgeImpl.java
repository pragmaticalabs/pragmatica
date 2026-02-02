package org.pragmatica.aether.slice;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.serialization.SerializerFactory;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;

/**
 * Implementation of SliceBridge for Node-Slice communication.
 * <p>
 * This class bridges the Node (Application ClassLoader) and Slices (isolated ClassLoader)
 * using byte arrays for serialized data. It wraps a Slice instance and handles all
 * serialization/deserialization at the boundary.
 * <p>
 * This implementation uses byte[] at the boundary, avoiding Netty types in the API
 * to maintain complete isolation from Netty at the API level.
 * <p>
 * <b>Invocation Flow:</b>
 * <ol>
 *   <li>Receive method name + serialized input (byte[])</li>
 *   <li>Look up method by name</li>
 *   <li>Deserialize input to typed parameter</li>
 *   <li>Invoke method on slice</li>
 *   <li>Serialize response to byte[]</li>
 *   <li>Return serialized response</li>
 * </ol>
 *
 * @see SliceBridge
 * @see Slice
 */
public record SliceBridgeImpl(Artifact artifact,
                              Slice slice,
                              Map<String, InternalMethod> methodMap,
                              SerializerFactory serializerFactory) implements SliceBridge {
    /**
     * Internal method descriptor containing type information for serialization.
     */
    public record InternalMethod(SliceMethod<?, ?> method,
                                 TypeToken<?> parameterType,
                                 TypeToken<?> returnType) {}

    /**
     * Create a SliceBridgeImpl from a Slice instance.
     *
     * @param artifact          The slice artifact coordinates
     * @param slice             The slice instance
     * @param serializerFactory Factory for serialization
     * @return SliceBridgeImpl wrapping the slice
     */
    public static SliceBridgeImpl sliceBridge(Artifact artifact,
                                              Slice slice,
                                              SerializerFactory serializerFactory) {
        var methodMap = slice.methods()
                             .stream()
                             .collect(java.util.stream.Collectors.toMap(m -> m.name()
                                                                              .name(),
                                                                        m -> new InternalMethod(m,
                                                                                                m.parameterType(),
                                                                                                m.returnType())));
        return new SliceBridgeImpl(artifact, slice, Map.copyOf(methodMap), serializerFactory);
    }

    @Override
    public Promise<byte[]> invoke(String methodName, byte[] input) {
        return lookupMethod(methodName).async()
                           .flatMap(method -> invokeWithSerialization(method, input));
    }

    private Promise<byte[]> invokeWithSerialization(InternalMethod method, byte[] input) {
        return acquireSerializationPair().flatMap(pair -> executeInvocation(pair, method, input));
    }

    private Promise<byte[]> executeInvocation(SerializationPair pair, InternalMethod method, byte[] input) {
        return deserializeInput(pair.deserializer(), input, method.parameterType())
        .flatMap(parameter -> invokeAndSerialize(pair.serializer(), method.method(), parameter));
    }

    private <T> Promise<byte[]> invokeAndSerialize(Serializer serializer, SliceMethod<?, ?> method, T parameter) {
        return invokeMethod(method, parameter).flatMap(response -> serializeResponse(serializer, response));
    }

    @Override
    public Promise<Unit> start() {
        return slice.start();
    }

    @Override
    public Promise<Unit> stop() {
        return slice.stop();
    }

    @Override
    public List<String> methodNames() {
        return List.copyOf(methodMap.keySet());
    }

    private Result<InternalMethod> lookupMethod(String methodName) {
        return Option.option(methodMap.get(methodName))
                     .toResult(METHOD_NOT_FOUND.apply(methodName));
    }

    private Promise<SerializationPair> acquireSerializationPair() {
        return Promise.all(serializerFactory.serializer(),
                           serializerFactory.deserializer())
                      .map(SerializationPair::new);
    }

    private record SerializationPair(Serializer serializer, Deserializer deserializer) {}

    @SuppressWarnings("unchecked")
    private <T> Promise<T> deserializeInput(Deserializer deserializer, byte[] input, TypeToken<T> typeToken) {
        return Promise.lift(Causes::fromThrowable, () -> (T) deserializer.decode(input));
    }

    @SuppressWarnings("unchecked")
    private <T, R> Promise<R> invokeMethod(SliceMethod<?, ?> method, T parameter) {
        return Promise.lift(Causes::fromThrowable,
                            () -> ((SliceMethod<R, T>) method).apply(parameter))
                      .flatMap(promise -> promise);
    }

    private <R> Promise<byte[]> serializeResponse(Serializer serializer, R response) {
        return Promise.lift(Causes::fromThrowable, () -> serializer.encode(response));
    }

    // Error constants
    private static final Fn1<Cause, String> METHOD_NOT_FOUND = Causes.forOneValue("Method not found: {0}");
}
