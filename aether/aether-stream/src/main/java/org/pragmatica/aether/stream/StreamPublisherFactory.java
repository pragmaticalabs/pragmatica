package org.pragmatica.aether.stream;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;

import java.util.function.Function;


/// ResourceFactory SPI implementation for provisioning StreamPublisher instances.
///
/// Discovered via ServiceLoader. Creates StreamPublisherImpl instances using
/// runtime extensions (StreamPartitionManager, Serializer) from the ProvisioningContext.
public final class StreamPublisherFactory implements ResourceFactory<StreamPublisher, StreamConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("StreamPublisher requires ProvisioningContext with runtime extensions");

    @Override public Class<StreamPublisher> resourceType() {
        return StreamPublisher.class;
    }

    @Override public Class<StreamConfig> configType() {
        return StreamConfig.class;
    }

    @Override public Promise<StreamPublisher> provision(StreamConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override public Promise<StreamPublisher> provision(StreamConfig config, ProvisioningContext context) {
        return context.extension(StreamPartitionManager.class).flatMap(manager -> context.extension(Serializer.class)
                                                                                                   .map(serializer -> buildPublisher(manager,
                                                                                                                                     serializer,
                                                                                                                                     config,
                                                                                                                                     context)))
                                .async();
    }

    @SuppressWarnings("unchecked") private static StreamPublisher buildPublisher(StreamPartitionManager manager,
                                                                                 Serializer serializer,
                                                                                 StreamConfig config,
                                                                                 ProvisioningContext context) {
        ensureStreamExists(manager, config);
        var keyExtractor = extractPartitionKeyFunction(context);
        return StreamPublisherImpl.streamPublisher(manager, serializer, config.name(), config.partitions(), keyExtractor);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) private static <T> Option<Function<T, Object>> extractPartitionKeyFunction(ProvisioningContext context) {
        return context.keyExtractor()
                                   .map(fn -> (Function<T, Object>) input -> ((org.pragmatica.lang.Functions.Fn1) fn).apply(input));
    }

    private static void ensureStreamExists(StreamPartitionManager manager, StreamConfig config) {
        manager.createStream(config);
    }
}
