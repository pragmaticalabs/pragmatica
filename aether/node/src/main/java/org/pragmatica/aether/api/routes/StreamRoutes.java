package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionInfo;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamInfo;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;


/// Routes for stream management: list streams, stream info, partition details, publish, read events.
public final class StreamRoutes implements RouteSource {
    private static final Cause STREAM_NOT_FOUND = Causes.cause("Stream not found");

    private static final Cause MISSING_STREAM_NAME = Causes.cause("Missing stream name");

    private static final int DEFAULT_MAX_EVENTS = 100;

    private static final int DEFAULT_PARTITIONS = 4;

    private final Supplier<AetherNode> nodeSupplier;

    private StreamRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static StreamRoutes streamRoutes(Supplier<AetherNode> nodeSupplier) {
        return new StreamRoutes(nodeSupplier);
    }

    record StreamListResponse(List<StreamSummary> streams){}

    record StreamSummary(String name, int partitions, long totalEvents, long totalBytes) {
        static StreamSummary fromStreamInfo(StreamInfo info) {
            return new StreamSummary(info.name(), info.partitions(), info.totalEvents(), info.totalBytes());
        }
    }

    record StreamInfoResponse(String name,
                              int partitions,
                              long totalEvents,
                              long totalBytes,
                              List<PartitionDetail> partitionDetails){}

    record PartitionDetail(int partition, long headOffset, long tailOffset, long eventCount) {
        static PartitionDetail fromPartitionInfo(PartitionInfo info) {
            return new PartitionDetail(info.partition(), info.headOffset(), info.tailOffset(), info.eventCount());
        }
    }

    record PublishRequest(String data){}

    record PublishResponse(long offset){}

    record StreamCreateRequest(String name, Integer partitions){}

    record StreamCreateResponse(String name, int partitions, String status){}

    record EventRecord(long offset, String data, long timestamp) {
        static EventRecord fromRawEvent(OffHeapRingBuffer.RawEvent event) {
            return new EventRecord(event.offset(),
                                   Base64.getEncoder().encodeToString(event.data()),
                                   event.timestamp());
        }
    }

    record ReadEventsResponse(List<EventRecord> events){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<StreamCreateResponse>post("/api/streams")
                              .withBody(StreamCreateRequest.class)
                              .toResult(this::createStream)
                              .asJson(),
                         Route.<StreamListResponse>get("/api/streams").toJson(this::listStreams),
                         Route.<StreamInfoResponse>get("/api/streams")
                              .withPath(PathParameter.aString())
                              .toResult(this::streamInfo)
                              .asJson(),
                         Route.<PartitionDetail>get("/api/streams")
                              .withPath(PathParameter.aString(),
                                        PathParameter.aInteger())
                              .toResult(this::partitionDetails)
                              .asJson(),
                         Route.<PublishResponse>post("/api/streams")
                              .withPath(PathParameter.aString(),
                                        PathParameter.spacer("publish"))
                              .withBody(PublishRequest.class)
                              .toResult(this::publishEvent)
                              .asJson(),
                         Route.<ReadEventsResponse>get("/api/streams")
                              .withPath(PathParameter.aString(),
                                        PathParameter.aInteger(),
                                        PathParameter.spacer("read"))
                              .withQuery(QueryParameter.aLong("from"),
                                         QueryParameter.aInteger("max"))
                              .toValue(this::readEvents)
                              .asJson());
    }

    private StreamListResponse listStreams() {
        var streams = streamManager().listStreams()
                                   .stream()
                                   .map(StreamSummary::fromStreamInfo)
                                   .toList();
        return new StreamListResponse(streams);
    }

    private Result<StreamInfoResponse> streamInfo(String name) {
        return streamManager().streamInfo(name)
                            .toResult(STREAM_NOT_FOUND)
                            .flatMap(info -> buildStreamInfoResponse(name, info));
    }

    private Result<StreamInfoResponse> buildStreamInfoResponse(String name, StreamInfo info) {
        return streamManager().allPartitionInfo(name)
                            .map(partitions -> partitions.stream().map(PartitionDetail::fromPartitionInfo)
                                                                .toList())
                            .map(details -> new StreamInfoResponse(info.name(),
                                                                   info.partitions(),
                                                                   info.totalEvents(),
                                                                   info.totalBytes(),
                                                                   details));
    }

    private Result<PartitionDetail> partitionDetails(String name, Integer partition) {
        return streamManager().partitionInfo(name, partition).map(PartitionDetail::fromPartitionInfo);
    }

    private Result<PublishResponse> publishEvent(String name, String spacer, PublishRequest request) {
        var payload = request.data().getBytes(StandardCharsets.UTF_8);
        ensureStreamExists(name);
        return streamManager().publishLocal(name, 0, payload, System.currentTimeMillis()).map(PublishResponse::new);
    }

    private Result<StreamCreateResponse> createStream(StreamCreateRequest request) {
        return Option.option(request.name()).toResult(MISSING_STREAM_NAME)
                            .flatMap(name -> createStreamWithConfig(name, request));
    }

    private Result<StreamCreateResponse> createStreamWithConfig(String name, StreamCreateRequest request) {
        var partitions = Option.option(request.partitions()).or(DEFAULT_PARTITIONS);
        var config = StreamConfig.streamConfig(name, partitions, MANAGEMENT_API_RETENTION, "latest");
        return streamManager().createStream(config).map(_ -> new StreamCreateResponse(name, partitions, "created"));
    }

    private static final RetentionPolicy MANAGEMENT_API_RETENTION = RetentionPolicy.retentionPolicy(10_000,
                                                                                                    4 * 1024 * 1024L,
                                                                                                    60 * 60 * 1000L);

    private void ensureStreamExists(String name) {
        streamManager().createStream(StreamConfig.streamConfig(name,
                                                               DEFAULT_PARTITIONS,
                                                               MANAGEMENT_API_RETENTION,
                                                               "latest"));
    }

    private ReadEventsResponse readEvents(String name,
                                          Integer partition,
                                          String spacer,
                                          Option<Long> fromOpt,
                                          Option<Integer> maxOpt) {
        var fromOffset = fromOpt.or(0L);
        var maxEvents = maxOpt.or(DEFAULT_MAX_EVENTS);
        var events = streamManager().readLocal(name, partition, fromOffset, maxEvents)
                                  .map(list -> list.stream().map(EventRecord::fromRawEvent)
                                                          .toList())
                                  .or(List.of());
        return new ReadEventsResponse(events);
    }

    private StreamPartitionManager streamManager() {
        return nodeSupplier.get().streamPartitionManager();
    }
}
