package org.pragmatica.aether.slice;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.serialization.SerializerFactory;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.serialization.Deserializer;
import org.pragmatica.net.serialization.Serializer;

import java.util.Map;

/**
 * Internal representation of a loaded slice optimized for method invocation routing.
 * <p>
 * This record wraps a Slice instance along with routing metadata and serialization
 * infrastructure needed for efficient byte-level method invocations.
 * <p>
 * The call() method implements the complete invocation pipeline:
 * 1. Method lookup
 * 2. Fork-join serializer/deserializer acquisition
 * 3. Input deserialization
 * 4. Method invocation
 * 5. Response serialization
 */
public record InternalSlice(
        Artifact artifact,
        Slice slice,
        Map<MethodName, InternalMethod> methodMap,
        SerializerFactory serializerFactory
) {

    /**
     * Internal method descriptor containing type information for serialization.
     */
    public record InternalMethod(
            SliceMethod<?, ?> method,
            TypeToken<?> parameterType,
            TypeToken<?> returnType
    ) {}

    /**
     * Invokes a slice method with raw byte buffer input, returning raw byte buffer output.
     * <p>
     * This is the critical path for all slice method invocations. The implementation:
     * - Looks up the method by name
     * - Obtains serializer and deserializer concurrently (fork-join)
     * - Deserializes the input ByteBuf to typed parameter
     * - Invokes the typed method
     * - Serializes the response to ByteBuf
     * - Returns the result ByteBuf
     * <p>
     * Each serializer/deserializer instance is used exactly once per invocation.
     *
     * @param methodName The name of the method to invoke
     * @param input      The serialized input parameter
     *
     * @return Promise resolving to serialized response
     */
    public Promise<ByteBuf> call(MethodName methodName, ByteBuf input) {
        return lookupMethod(methodName)
                .async()
                .flatMap(internalMethod ->
                                 acquireSerializationPair()
                                         .flatMap(pair ->
                                                          deserializeInput(pair.deserializer(),
                                                                           input,
                                                                           internalMethod.parameterType())
                                                                  .flatMap(parameter ->
                                                                                   invokeMethod(internalMethod.method(),
                                                                                                parameter)
                                                                                           .flatMap(response ->
                                                                                                            serializeResponse(
                                                                                                                    pair.serializer(),
                                                                                                                    response)
                                                                                                   )
                                                                          )
                                                 )
                        );
    }

    private Result<InternalMethod> lookupMethod(MethodName methodName) {
        var method = methodMap.get(methodName);
        return method != null
               ? Result.success(method)
               : METHOD_NOT_FOUND.apply(methodName).result();
    }

    private Promise<SerializationPair> acquireSerializationPair() {
        return Promise.all(
                serializerFactory.serializer(),
                serializerFactory.deserializer()
                          ).map(SerializationPair::new);
    }

    private record SerializationPair(Serializer serializer, Deserializer deserializer) {}

    @SuppressWarnings("unchecked")
    private <T> Promise<T> deserializeInput(Deserializer deserializer, ByteBuf input, TypeToken<T> typeToken) {
        return Promise.lift(
                Causes::fromThrowable,
                () -> (T) deserializer.read(input)
                           );
    }

    @SuppressWarnings("unchecked")
    private <T, R> Promise<R> invokeMethod(SliceMethod<?, ?> method, T parameter) {
        return Promise.lift(
                Causes::fromThrowable,
                () -> ((SliceMethod<R, T>) method).apply(parameter)
                           ).flatMap(promise -> promise);
    }

    private <R> Promise<ByteBuf> serializeResponse(Serializer serializer, R response) {
        return Promise.lift(
                Causes::fromThrowable,
                () -> {
                    ByteBuf output = Unpooled.buffer();
                    serializer.write(output, response);
                    return output;
                }
                           );
    }

    // Error constants
    private static final Fn1<Cause, MethodName> METHOD_NOT_FOUND =
            Causes.forValue("Method not found: {0}");
}
