package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionInfo;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamInfo;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator.ConsumerInfo;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;


/// Routes for stream management: list streams, stream info, partition details, publish, read events.
public final class StreamRoutes implements RouteSource {
    private static final Cause STREAM_NOT_FOUND = Causes.cause("Stream not found");

    private static final Cause MISSING_STREAM_NAME = Causes.cause("Missing stream name");

    private static final int DEFAULT_MAX_EVENTS = 100;

    private static final int DEFAULT_PARTITIONS = 4;

    private final Supplier<ManageableNode> nodeSupplier;
    private final ConsumerGroupCoordinator coordinator;
    private final ConsumerGroupRegistry registry;

    private StreamRoutes(Supplier<ManageableNode> nodeSupplier,
                         ConsumerGroupCoordinator coordinator,
                         ConsumerGroupRegistry registry) {
        this.nodeSupplier = nodeSupplier;
        this.coordinator = coordinator;
        this.registry = registry;
    }

    public static StreamRoutes streamRoutes(Supplier<ManageableNode> nodeSupplier,
                                            ConsumerGroupCoordinator coordinator,
                                            ConsumerGroupRegistry registry) {
        return new StreamRoutes(nodeSupplier, coordinator, registry);
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

    record StreamDeleteResponse(String name, String status){}

    record StreamConsumersResponse(String name, List<PartitionInfo> partitions){}

    record JoinGroupRequest(String groupId, String streamName, int partitionCount, String consumerId){}

    record LeaveGroupRequest(String groupId, String streamName, String consumerId){}

    record GroupStatusResponse(String groupId, Map<String, List<ConsumerInfo>> streams){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<StreamCreateResponse>route(ManagementRoute.STREAM_CREATE)
                                         .withBody(StreamCreateRequest.class)
                                         .toResult(this::createStream)
                                         .asJson(),
                         ManagementRoutes.<StreamListResponse>route(ManagementRoute.STREAM_LIST)
                                         .toJson(this::listStreams),
                         ManagementRoutes.<StreamInfoResponse>route(ManagementRoute.STREAM_GET)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::streamInfo)
                                         .asJson(),
                         ManagementRoutes.<PartitionDetail>route(ManagementRoute.STREAM_PARTITION)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aInteger())
                                         .toResult(this::partitionDetails)
                                         .asJson(),
                         ManagementRoutes.<PublishResponse>route(ManagementRoute.STREAM_PUBLISH)
                                         .withPath(PathParameter.aString())
                                         .withBody(PublishRequest.class)
                                         .toResult(this::publishEvent)
                                         .asJson(),
                         ManagementRoutes.<ReadEventsResponse>route(ManagementRoute.STREAM_READ)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aInteger())
                                         .withQuery(QueryParameter.aLong("from"),
                                                    QueryParameter.aInteger("max"),
                                                    QueryParameter.aString("readPreference"))
                                         .toResult(this::readEvents)
                                         .asJson(),
                         ManagementRoutes.<StreamDeleteResponse>route(ManagementRoute.STREAM_DELETE)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::deleteStream)
                                         .asJson(),
                         ManagementRoutes.<StreamConsumersResponse>route(ManagementRoute.STREAM_CONSUMERS)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::streamConsumers)
                                         .asJson(),
                         ManagementRoutes.<GroupStatusResponse>route(ManagementRoute.CONSUMER_GROUP_JOIN)
                                         .withBody(JoinGroupRequest.class)
                                         .toResult(this::joinGroup)
                                         .asJson(),
                         ManagementRoutes.<GroupStatusResponse>route(ManagementRoute.CONSUMER_GROUP_LEAVE)
                                         .withBody(LeaveGroupRequest.class)
                                         .toResult(this::leaveGroup)
                                         .asJson(),
                         ManagementRoutes.<GroupStatusResponse>route(ManagementRoute.CONSUMER_GROUP_STATUS)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::groupStatus)
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

    private Result<PublishResponse> publishEvent(String name, PublishRequest request) {
        return publishToPartition(name, request);
    }

    private Result<PublishResponse> publishToPartition(String name, PublishRequest request) {
        var payload = request.data().getBytes(StandardCharsets.UTF_8);
        return ensureStreamExists(name).flatMap(_ -> streamManager().publishLocal(name,
                                                                                   0,
                                                                                   payload,
                                                                                   System.currentTimeMillis()))
                                       .map(PublishResponse::new);
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

    private Result<Unit> ensureStreamExists(String name) {
        return streamManager().createStream(StreamConfig.streamConfig(name,
                                                                       DEFAULT_PARTITIONS,
                                                                       MANAGEMENT_API_RETENTION,
                                                                       "latest"))
                              .recover(_ -> Unit.unit());
    }

    private Result<ReadEventsResponse> readEvents(String name,
                                                  Integer partition,
                                                  Option<Long> fromOpt,
                                                  Option<Integer> maxOpt,
                                                  Option<String> readPreferenceOpt) {
        var fromOffset = fromOpt.or(0L);
        var maxEvents = maxOpt.or(DEFAULT_MAX_EVENTS);
        var preference = readPreferenceOpt.map(StreamRoutes::parseReadPreference)
                                          .or(ReadPreference.GOVERNOR);
        return readLocalEvents(name, partition, fromOffset, maxEvents, preference);
    }

    private Result<ReadEventsResponse> readLocalEvents(String name,
                                                       Integer partition,
                                                       long fromOffset,
                                                       int maxEvents,
                                                       ReadPreference preference) {
        return streamManager().readLocal(name, partition, fromOffset, maxEvents)
                            .map(list -> list.stream().map(EventRecord::fromRawEvent)
                                                    .toList())
                            .map(ReadEventsResponse::new);
    }

    private static ReadPreference parseReadPreference(String value) {
        return switch (value.toUpperCase()){
            case "ANY_REPLICA", "ANY-REPLICA" -> ReadPreference.ANY_REPLICA;
            case "NEAREST" -> ReadPreference.NEAREST;
            default -> ReadPreference.GOVERNOR;
        };
    }

    private Result<StreamDeleteResponse> deleteStream(String name) {
        return streamManager().destroyStream(name).map(_ -> new StreamDeleteResponse(name, "deleted"));
    }

    private Result<StreamConsumersResponse> streamConsumers(String name) {
        return streamManager().allPartitionInfo(name).map(partitions -> new StreamConsumersResponse(name, partitions));
    }

    private Result<GroupStatusResponse> joinGroup(JoinGroupRequest request) {
        return coordinator.joinGroup(request.groupId(),
                                     request.streamName(),
                                     request.partitionCount(),
                                     request.consumerId(),
                                     nodeSupplier.get().self())
        .map(_ -> new GroupStatusResponse(request.groupId(),
                                          coordinator.groupStatus(request.groupId())));
    }

    private Result<GroupStatusResponse> leaveGroup(LeaveGroupRequest request) {
        return coordinator.leaveGroup(request.groupId(),
                                      request.streamName(),
                                      request.consumerId())
        .map(_ -> new GroupStatusResponse(request.groupId(),
                                          coordinator.groupStatus(request.groupId())));
    }

    private Result<GroupStatusResponse> groupStatus(String groupId) {
        return Result.success(new GroupStatusResponse(groupId, coordinator.groupStatus(groupId)));
    }

    private StreamPartitionManager streamManager() {
        return nodeSupplier.get().streamPartitionManager();
    }
}
