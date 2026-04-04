package org.pragmatica.aether.stream;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.function.Function;


/// ResourceFactory SPI implementation for provisioning StreamAccess instances.
///
/// Discovered via ServiceLoader. Creates PartitionedStreamAccess instances using
/// runtime extensions (StreamPartitionManager, Serializer, Deserializer) from the ProvisioningContext.
public final class StreamAccessFactory implements ResourceFactory<StreamAccess, StreamConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("StreamAccess requires ProvisioningContext with runtime extensions");

    @Override public Class<StreamAccess> resourceType() {
        return StreamAccess.class;
    }

    @Override public Class<StreamConfig> configType() {
        return StreamConfig.class;
    }

    @Override public Promise<StreamAccess> provision(StreamConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override public Promise<StreamAccess> provision(StreamConfig config, ProvisioningContext context) {
        return context.extension(StreamPartitionManager.class).flatMap(manager -> context.extension(Serializer.class)
                                                                                                   .flatMap(serializer -> context.extension(Deserializer.class)
                                                                                                                                           .map(deserializer -> buildAccess(manager,
                                                                                                                                                                            serializer,
                                                                                                                                                                            deserializer,
                                                                                                                                                                            config,
                                                                                                                                                                            context))))
                                .async();
    }

    @SuppressWarnings("unchecked") private static StreamAccess buildAccess(StreamPartitionManager manager,
                                                                           Serializer serializer,
                                                                           Deserializer deserializer,
                                                                           StreamConfig config,
                                                                           ProvisioningContext context) {
        ensureStreamExists(manager, config);
        var keyExtractor = extractPartitionKeyFunction(context);
        return PartitionedStreamAccess.streamAccess(manager,
                                                    serializer,
                                                    deserializer,
                                                    config.name(),
                                                    config.partitions(),
                                                    keyExtractor);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) private static <T> Option<Function<T, Object>> extractPartitionKeyFunction(ProvisioningContext context) {
        return context.keyExtractor()
                                   .map(fn -> (Function<T, Object>) input -> ((org.pragmatica.lang.Functions.Fn1) fn).apply(input));
    }

    private static void ensureStreamExists(StreamPartitionManager manager, StreamConfig config) {
        manager.createStream(config);
    }
}
