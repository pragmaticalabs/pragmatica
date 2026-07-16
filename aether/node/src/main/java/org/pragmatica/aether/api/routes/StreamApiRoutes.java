// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistry;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.stream.StreamVersionSpec;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator.ConsumerInfo;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// HTTP route surface for stream namespaces — spec event-stream-namespaces §12.
///
/// Routes are addressed by `(namespace, stream, version)` path segments. The legacy flat
/// `?streamName=` query-string routes were removed in Wave 4A; clients use the path-based
/// shape exclusively.
///
/// `system:*` write enforcement (§12.2 — 405 Method Not Allowed regardless of role) is
/// implemented at the `ManagementServer` security pipeline, not here, so the check
/// short-circuits before role evaluation.
public final class StreamApiRoutes implements RouteSource {
    private static final Cause STREAM_NOT_FOUND = Causes.cause("Stream not found");
    private static final Cause GROUP_NOT_FOUND = Causes.cause("Consumer group not found");
    private static final int DEFAULT_PARTITIONS = 4;

    private static final RetentionPolicy MANAGEMENT_API_RETENTION = RetentionPolicy.retentionPolicy(10_000,
                                                                                                    4 * 1024 * 1024L,
                                                                                                    60 * 60 * 1000L);

    private final Supplier<ManageableNode> nodeSupplier;
    private final StreamNamespacesService namespacesService;
    private final ConsumerGroupCoordinator coordinator;

    private StreamApiRoutes(Supplier<ManageableNode> nodeSupplier,
                            StreamNamespacesService namespacesService,
                            ConsumerGroupCoordinator coordinator) {
        this.nodeSupplier = nodeSupplier;
        this.namespacesService = namespacesService;
        this.coordinator = coordinator;
    }

    /// Factory keeps `registry` in the signature for compatibility with the upstream wiring point;
    /// it is unused at the route layer in Wave 4A — group enumeration is via the coordinator's
    /// per-group status map. The parameter will become live in Wave 4B when durable group
    /// enumeration lands as a first-class registry query.
    @SuppressWarnings("unused")
    public static StreamApiRoutes streamApiRoutes(Supplier<ManageableNode> nodeSupplier,
                                                  StreamNamespacesService namespacesService,
                                                  ConsumerGroupCoordinator coordinator,
                                                  org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry registry) {
        return new StreamApiRoutes(nodeSupplier, namespacesService, coordinator);
    }

    // ====================== DTOs ======================
    public record StreamSummary(String namespace,
                                String stream,
                                String version,
                                int refCount,
                                long registeredAtEpochMs) {
        static StreamSummary fromEntry(StreamRegistryEntry entry) {
            return new StreamSummary(entry.address().namespace().value(),
                                     entry.address().name().value(),
                                     entry.address().version().asString(),
                                     entry.refCount(),
                                     entry.registeredAtEpochMillis());
        }
    }

    public record StreamListResponse(List<StreamSummary> streams) {}

    public record VersionsListResponse(String namespace, String stream, List<StreamSummary> versions) {}

    public record StreamMetadataResponse(String namespace,
                                         String stream,
                                         String version,
                                         int refCount,
                                         int partitionCount,
                                         long maxEvents,
                                         long maxBytes,
                                         long maxAgeMs,
                                         long registeredAtEpochMs,
                                         String registeredBy) {
        static StreamMetadataResponse fromEntry(StreamRegistryEntry entry) {
            var retention = entry.retention();

            return new StreamMetadataResponse(entry.address().namespace().value(),
                                              entry.address().name().value(),
                                              entry.address().version().asString(),
                                              entry.refCount(),
                                              defaultPartitionCount(),
                                              retention.maxCount(),
                                              retention.maxBytes(),
                                              retention.maxAgeMs(),
                                              entry.registeredAtEpochMillis(),
                                              entry.registeredBy().name());
        }
    }

    public record PublishRequest(String data) {}

    public record PublishResponse(String address, long offset) {}

    public record PublishBatchResponse(String address, int published, List<Long> offsets) {}

    public record GroupCreateRequest(String groupId, String initialPosition) {}

    public record GroupResponse(String address, String groupId, String status) {}

    public record GroupListResponse(String address, List<GroupSummary> groups) {}

    public record GroupSummary(String groupId, Map<String, List<ConsumerInfo>> consumersByStream) {}

    public record DeleteResponse(String address, String status) {}

    /// Spec event-stream-namespaces §16: paginated polling read used by `aether stream tail` and
    /// scripted operator polling. Each request returns events from `fromOffset` (inclusive) up to
    /// `maxEvents` records; the `nextOffset` field tells the caller where to resume on the next
    /// poll, and `hasMore` is `true` iff the page filled to `maxEvents` (meaning the producer
    /// likely has more queued than fit in this page).
    public record StreamEventsResponse(String address, List<EventEntry> events, long nextOffset, boolean hasMore) {}

    public record EventEntry(long offset, Instant timestamp, int partition, String payload) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(
        // ---------- Read routes (ALL_AUTHENTICATED via GET) ----------
        ManagementRoutes.<StreamListResponse> route(ManagementRoute.STREAMS_LIST)
                        .withQuery(QueryParameter.aString("namespace"),
                                   QueryParameter.aInteger("limit"),
                                   QueryParameter.aString("cursor"))
                        .toResult(this::listStreams)
                        .asJson(),
                         ManagementRoutes.<VersionsListResponse> route(ManagementRoute.STREAMS_VERSIONS_LIST)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::listVersions)
                                         .asJson(),
                         ManagementRoutes.<StreamMetadataResponse> route(ManagementRoute.STREAMS_LATEST)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::resolveLatest)
                                         .asJson(),
                         ManagementRoutes.<StreamMetadataResponse> route(ManagementRoute.STREAMS_METADATA)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::streamMetadata)
                                         .asJson(),
                         ManagementRoutes.<GroupListResponse> route(ManagementRoute.STREAMS_GROUPS_LIST)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::listGroups)
                                         .asJson(),

        // ---------- Tail subscription (Wave 6B: SSE/WebSocket deferred to issue #212) ----------
        ManagementRoutes.<StreamMetadataResponse> route(ManagementRoute.STREAMS_TAIL)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString())
                        .toResult(this::tailDeferred)
                        .asJson(),

        // ---------- Polling-based paginated event read (Wave 6B; RC1) ----------
        ManagementRoutes.<StreamEventsResponse> route(ManagementRoute.STREAMS_EVENTS)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString())
                        .withQuery(QueryParameter.aLong("fromOffset"),
                                   QueryParameter.aInteger("maxEvents"))
                        .to(this::streamEvents)
                        .asJson(),

        // ---------- Write routes (OPERATOR_AND_ABOVE for /api/streams) ----------
        ManagementRoutes.<PublishResponse> route(ManagementRoute.STREAMS_PUBLISH)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString())
                        .withBody(PublishRequest.class)
                        .to(this::publishEvent)
                        .asJson(),
                         ManagementRoutes.<PublishBatchResponse> route(ManagementRoute.STREAMS_PUBLISH_BATCH)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .withBody(PublishRequest[].class)
                                         .to(this::publishBatch)
                                         .asJson(),
                         ManagementRoutes.<GroupResponse> route(ManagementRoute.STREAMS_GROUP_CREATE)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .withBody(GroupCreateRequest.class)
                                         .toResult(this::createGroup)
                                         .asJson(),
                         ManagementRoutes.<GroupResponse> route(ManagementRoute.STREAMS_GROUP_DELETE)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::deleteGroup)
                                         .asJson(),

        // ---------- Destructive (ADMIN_ONLY via override) ----------
        ManagementRoutes.<DeleteResponse> route(ManagementRoute.STREAMS_DELETE)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString())
                        .toResult(this::deleteStream)
                        .asJson());
    }

    // ============================ Handlers ============================
    private Result<StreamListResponse> listStreams(Option<String> namespace,
                                                   Option<Integer> limit,
                                                   Option<String> cursor) {
        var snapshot = namespacesService.snapshot();
        var filtered = namespace.fold(() -> snapshot,
                                      ns -> snapshot.stream()
                                                    .filter(e -> e.address()
                                                                  .namespace()
                                                                  .value()
                                                                  .equals(ns))
                                                    .toList());
        var capped = limit.fold(() -> filtered,
                                n -> filtered.stream()
                                             .limit(n)
                                             .toList());
        var summaries = capped.stream().map(StreamSummary::fromEntry).toList();

        return Result.success(new StreamListResponse(summaries));
    }

    private Result<VersionsListResponse> listVersions(String namespace, String stream) {
        var versions = namespacesService.snapshot()
                                        .stream()
                                        .filter(e -> e.address()
                                                      .namespace()
                                                      .value()
                                                      .equals(namespace))
                                        .filter(e -> e.address()
                                                      .name()
                                                      .value()
                                                      .equals(stream))
                                        .map(StreamSummary::fromEntry)
                                        .toList();

        if (versions.isEmpty()) {
            return STREAM_NOT_FOUND.result();
        }

        return Result.success(new VersionsListResponse(namespace, stream, versions));
    }

    private Result<StreamMetadataResponse> resolveLatest(String namespace, String stream) {
        return namespacesService.resolve(namespace,
                                         stream,
                                         StreamVersionSpec.latest())
                                .map(StreamMetadataResponse::fromEntry);
    }

    private Result<StreamMetadataResponse> streamMetadata(String namespace, String stream, String version) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .flatMap(addr -> namespacesService.lookup(addr)
                                                                .toResult(StreamRegistry.StreamRegistryError.General.NOT_FOUND))
                              .map(StreamMetadataResponse::fromEntry);
    }

    private Result<GroupListResponse> listGroups(String namespace, String stream, String version) {
        return ResourceAddress.resourceAddress(namespace, stream, version).map(addr -> new GroupListResponse(addr.asString(),
                                                                                                             List.of()));
    }

    /// Wave 6B: Tail subscription via SSE/WebSocket is deferred to issue #212 — the streaming
    /// protocol layer requires chunked encoding, keep-alive, and fan-out infrastructure beyond the
    /// scope of RC1. Operators polling for new events should use GET `/events?fromOffset=…` (which
    /// is the always-available polling fallback that the `aether stream tail` CLI now drives).
    private Result<StreamMetadataResponse> tailDeferred(String namespace, String stream, String version) {
        return Causes.cause("Tail subscription via SSE/WebSocket is deferred to issue #212. "
                           + "For polling-based tail, use GET /api/streams/" + namespace
                           + "/" + stream
                           + "/" + version
                           + "/events?fromOffset=N&maxEvents=K.").result();
    }

    /// Spec event-stream-namespaces §16: paginated event read for polling-based tail subscription.
    /// Resolves the address, verifies the registry entry exists (404 via [StreamRegistryError.General.NOT_FOUND]
    /// otherwise), then reads partition 0 starting at `fromOffset` through the owner-routing
    /// [StreamReadRouter] with [ReadPreference#NEAREST] — local-first, forwarding to the partition owner
    /// when this node is metadata-only (#265) rather than reading its own empty ring. Partition 0 is the
    /// canonical write target for `STREAMS_PUBLISH`/`STREAMS_PUBLISH_BATCH` in this wave; multi-partition
    /// reads are out of scope until partitioning is exposed to the public publish API.
    private Promise<StreamEventsResponse> streamEvents(String namespace,
                                                       String stream,
                                                       String version,
                                                       Option<Long> fromOffset,
                                                       Option<Integer> maxEvents) {
        var offset = fromOffset.or(0L);
        var limit = clampMaxEvents(maxEvents.or(DEFAULT_MAX_EVENTS));

        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .async()
                              .flatMap(addr -> readEventsAtAddress(addr, offset, limit));
    }

    private Promise<StreamEventsResponse> readEventsAtAddress(ResourceAddress addr, long fromOffset, int maxEvents) {
        var streamName = addr.asString();

        return namespacesService.lookup(addr)
                                .toResult(StreamRegistry.StreamRegistryError.General.NOT_FOUND)
                                .async()
                                .flatMap(_ -> streamReadRouter().read(streamName,
                                                                      0,
                                                                      fromOffset,
                                                                      maxEvents,
                                                                      ReadPreference.NEAREST))
                                .map(events -> buildEventsResponse(addr, events, fromOffset, maxEvents));
    }

    private static StreamEventsResponse buildEventsResponse(ResourceAddress addr,
                                                            List<org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent> events,
                                                            long fromOffset,
                                                            int maxEvents) {
        var entries = events.stream().map(StreamApiRoutes::toEventEntry).toList();
        var nextOffset = events.isEmpty()
                         ? fromOffset
                         : events.getLast().offset() + 1;
        var hasMore = events.size() >= maxEvents;

        return new StreamEventsResponse(addr.asString(), entries, nextOffset, hasMore);
    }

    private static EventEntry toEventEntry(org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent raw) {
        return new EventEntry(raw.offset(),
                              Instant.ofEpochMilli(raw.timestamp()),
                              0,
                              new String(raw.data(), StandardCharsets.UTF_8));
    }

    private static int clampMaxEvents(int requested) {
        if (requested < 1) {
            return 1;
        }

        if (requested > MAX_EVENTS_PER_PAGE) {
            return MAX_EVENTS_PER_PAGE;
        }

        return requested;
    }

    private static final int DEFAULT_MAX_EVENTS = 100;
    private static final int MAX_EVENTS_PER_PAGE = 1000;

    private Promise<PublishResponse> publishEvent(String namespace,
                                                  String stream,
                                                  String version,
                                                  PublishRequest request) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .async()
                              .flatMap(addr -> publishOne(addr, request).map(offset -> new PublishResponse(addr.asString(),
                                                                                                           offset)));
    }

    private Promise<PublishBatchResponse> publishBatch(String namespace,
                                                       String stream,
                                                       String version,
                                                       PublishRequest[] requests) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .async()
                              .flatMap(addr -> publishMany(addr, requests));
    }

    private Promise<PublishBatchResponse> publishMany(ResourceAddress addr, PublishRequest[] requests) {
        var perEvent = Arrays.stream(requests).map(req -> publishOne(addr, req)).toList();

        return Promise.allOf(perEvent).flatMap(results -> collectOffsets(addr, results));
    }

    private Promise<PublishBatchResponse> collectOffsets(ResourceAddress addr, List<Result<Long>> results) {
        return Result.allOf(results)
                     .map(offsets -> new PublishBatchResponse(addr.asString(),
                                                              offsets.size(),
                                                              offsets))
                     .async();
    }

    /// Owner-routed publish to partition 0. When this node is metadata-only (#265) the write is
    /// forwarded to the partition owner via [StreamWriteRouter] instead of failing PARTITION_NOT_LOCAL
    /// on a local append; an owner node appends locally (and awaits the min-sync barrier).
    private Promise<Long> publishOne(ResourceAddress addr, PublishRequest request) {
        var streamName = addr.asString();
        var payload = decodePayload(request.data());

        return ensureStreamExists(streamName).async()
                                 .flatMap(_ -> streamWriteRouter().publish(streamName,
                                                                           0,
                                                                           payload,
                                                                           System.currentTimeMillis()));
    }

    /// Adapter boundary: JSON `data` may legally be absent (null on the wire). Wrap into Option,
    /// then encode UTF-8. Empty body == empty payload, not an error.
    private static byte[] decodePayload(String data) {
        return Option.option(data)
                     .map(StreamApiRoutes::utf8Bytes)
                     .or(EMPTY_PAYLOAD);
    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /// Publish auto-create guard. When the stream is not yet materialized locally, prefer the committed
    /// `StreamConfig` from applied KV state (which carries the app/blueprint `replicas` / `minSyncReplicas`
    /// durability knobs committed at slice activation) so a first publish racing ahead of local
    /// materialization preserves the replication factor rather than fabricating a `replicas=1/min-sync=0`
    /// management default over it. Falls back to the management default only for a genuinely
    /// management-only stream with no committed entry.
    private Result<org.pragmatica.lang.Unit> ensureStreamExists(String streamName) {
        var config = nodeSupplier.get()
                                 .kvStore()
                                 .getTyped(StreamConfigKey.streamConfigKey(streamName),
                                           StreamConfigValue.class)
                                 .map(StreamConfigValue::config)
                                 .or(() -> StreamConfig.streamConfig(streamName,
                                                                     DEFAULT_PARTITIONS,
                                                                     MANAGEMENT_API_RETENTION,
                                                                     "latest"));

        return streamManager().ensureStreamMaterialized(config)
                            .recover(_ -> org.pragmatica.lang.Unit.unit());
    }

    private Result<GroupResponse> createGroup(String namespace,
                                              String stream,
                                              String version,
                                              GroupCreateRequest request) {
        return ResourceAddress.resourceAddress(namespace, stream, version).flatMap(addr -> joinGroupAtAddress(addr,
                                                                                                              request));
    }

    private Result<GroupResponse> joinGroupAtAddress(ResourceAddress addr, GroupCreateRequest request) {
        var streamName = addr.asString();
        var consumerId = "operator-" + System.nanoTime();

        return coordinator.joinGroup(request.groupId(),
                                     streamName,
                                     DEFAULT_PARTITIONS,
                                     consumerId,
                                     nodeSupplier.get().self())
                          .map(_ -> new GroupResponse(addr.asString(),
                                                      request.groupId(),
                                                      "created"));
    }

    private Result<GroupResponse> deleteGroup(String namespace, String stream, String version, String group) {
        return ResourceAddress.resourceAddress(namespace, stream, version).flatMap(addr -> leaveGroupAtAddress(addr,
                                                                                                               group));
    }

    private Result<GroupResponse> leaveGroupAtAddress(ResourceAddress addr, String group) {
        var streamName = addr.asString();
        var status = coordinator.groupStatus(group);

        if (status.isEmpty()) {
            return GROUP_NOT_FOUND.result();
        }

        var consumers = status.getOrDefault(streamName, List.of());
        var leaveResults = consumers.stream()
                                    .map(c -> coordinator.leaveGroup(group,
                                                                     streamName,
                                                                     c.consumerId()))
                                    .toList();

        return Result.allOf(leaveResults).map(_ -> new GroupResponse(addr.asString(), group, "deleted"));
    }

    private Result<DeleteResponse> deleteStream(String namespace, String stream, String version) {
        return ResourceAddress.resourceAddress(namespace, stream, version).flatMap(this::destroyAtAddress);
    }

    private Result<DeleteResponse> destroyAtAddress(ResourceAddress addr) {
        var streamName = addr.asString();

        return streamManager().destroyStream(streamName)
                            .recover(_ -> org.pragmatica.lang.Unit.unit())
                            .map(_ -> new DeleteResponse(addr.asString(),
                                                         "deleted"));
    }

    private StreamPartitionManager streamManager() {
        return nodeSupplier.get()
                           .streamPartitionManager();
    }

    private StreamReadRouter streamReadRouter() {
        return nodeSupplier.get()
                           .streamReadRouter();
    }

    private StreamWriteRouter streamWriteRouter() {
        return nodeSupplier.get()
                           .streamWriteRouter();
    }

    private static int defaultPartitionCount() {
        return DEFAULT_PARTITIONS;
    }
}
