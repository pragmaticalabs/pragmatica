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

import org.pragmatica.aether.api.ManagementApiResponses.StreamReplicasResponse;
import org.pragmatica.aether.api.ManagementServerError;
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
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamCreateOutcome;
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
import org.pragmatica.lang.Unit;
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
    /// #524: pre-fix hardwired publish target, preserved as the explicit default for an omitted
    /// `partition` field so existing callers see unchanged behavior.
    private static final int DEFAULT_PUBLISH_PARTITION = 0;

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

    /// `partition` is optional (absent/`null` on the wire keeps the pre-#524 default: partition 0).
    /// Management-API publish writes UNTYPED bytes — there is no event class to read an
    /// `@PartitionKey` from, so unlike the app publish path (#507) an explicit partition here is the
    /// operator choosing a target directly, never key-based routing.
    public record PublishRequest(String data, Integer partition) {}

    public record PublishResponse(String address, long offset) {}

    public record PublishBatchResponse(String address, int published, List<Long> offsets) {}

    public record GroupCreateRequest(String groupId, String initialPosition) {}

    public record GroupResponse(String address, String groupId, String status) {}

    public record GroupListResponse(String address, List<GroupSummary> groups) {}

    public record GroupSummary(String groupId, Map<String, List<ConsumerInfo>> consumersByStream) {}

    /// #1224: `partitions` is optional (absent/`null` on the wire keeps [#DEFAULT_PARTITIONS], the
    /// same default `aether streams create`'s legacy body-carried form used).
    public record CreateRequest(Integer partitions) {}

    public record CreateResponse(String address, String status) {}

    public record DeleteResponse(String address, String status) {}

    /// Spec event-stream-namespaces §16: paginated polling read used by `aether stream tail` and
    /// scripted operator polling. Each request returns events from `fromOffset` (inclusive) up to
    /// `maxEvents` records; the `nextOffset` field tells the caller where to resume on the next
    /// poll, and `hasMore` is `true` iff the page filled to `maxEvents` (meaning the producer
    /// likely has more queued than fit in this page).
    public record StreamEventsResponse(String address, List<EventEntry> events, long nextOffset, boolean hasMore) {}

    public record EventEntry(long offset, Instant timestamp, int partition, String payload) {}

    public record PartitionDetail(int partition, long headOffset, long tailOffset, long eventCount) {
        static PartitionDetail partitionDetail(StreamPartitionManager.PartitionInfo info) {
            return new PartitionDetail(info.partition(), info.headOffset(), info.tailOffset(), info.eventCount());
        }
    }

    /// Legacy CLI-facing read shape (`aether stream read`) — a flat event list, distinct from
    /// [StreamEventsResponse]'s offset-cursor pagination used by the newer polling-tail endpoint.
    /// Kept separate rather than consolidated: unifying them is a CLI-facing contract change outside
    /// this migration's scope.
    public record ReadEventsResponse(List<EventRecord> events) {
        static ReadEventsResponse readEventsResponse(List<EventRecord> events) {
            return new ReadEventsResponse(events);
        }
    }

    public record EventRecord(long offset, String data, long timestamp) {
        static EventRecord eventRecord(RawEvent event) {
            return new EventRecord(event.offset(), new String(event.data(), StandardCharsets.UTF_8), event.timestamp());
        }
    }

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
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("latest"))
                                         .toResult(this::resolveLatest)
                                         .asJson(),
                         ManagementRoutes.<StreamMetadataResponse> route(ManagementRoute.STREAMS_METADATA)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString())
                                         .toResult(this::streamMetadata)
                                         .asJson(),

        // #742 fold: registration must reproduce every trailing token
        // ManagementRoute.STREAM_GET/PARTITION/REPLICAS declare via the
        // interleaved-tokens constructor (ManagementRoute.java:400-480), not just
        // the real path params — RequestContext.matchPath binds every declared
        // slot positionally (spacer or real) and RequestRouter's arity check
        // rejects a withPath() that's short one slot. The earlier 4-arg form here
        // omitted the interior literal, making these routes unreachable via the
        // CLI-assembled URL (silent 404, not a dispatch to the wrong handler).
        ManagementRoutes.<StreamRoutes.StreamInfoResponse> route(ManagementRoute.STREAM_GET)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.spacer("info"))
                        .toResult(this::streamInfo)
                        .asJson(),
                         ManagementRoutes.<PartitionDetail> route(ManagementRoute.STREAM_PARTITION)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("partitions"),
                                                   PathParameter.aInteger())
                                         .toResult(this::partitionDetail)
                                         .asJson(),
                         ManagementRoutes.<StreamReplicasResponse> route(ManagementRoute.STREAM_REPLICAS)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("replicas"),
                                                   PathParameter.aInteger())
                                         .toResult(this::replicaDetail)
                                         .asJson(),

        // rc4 FLAG 2 ruling: `from`/`max`/`readPreference` are three independent query
        // params, not the earlier composite `readOptions=from,max,readPreference` string.
        // Raising `Route.PathBuilder4`'s query-arity ceiling (1 -> 3, `PathQueryBuilder4_3`)
        // was additive — `Fn6..Fn15` already existed in `core/Functions`, only the
        // hand-authored combinator was missing — so the composite's reason for existing
        // (the DSL had no room) no longer holds.
        //
        // #742 fold: this registration had the same spacer-interleave defect described
        // above STREAM_GET/PARTITION/REPLICAS — 4 plain path params with no slot for the
        // interior `spacer("read")` literal `ManagementRoute.STREAM_READ` declares, making
        // it unreachable via the CLI-assembled URL. Fixing it needs a 5-path + 3-query
        // builder, one slot past `PathQueryBuilder4_3`'s ceiling — hence `PathQueryBuilder5_3`
        // (integrations/http-routing/Route.java), the same kind of additive extension the
        // comment above already describes. `readLiteral` binds the trailing `spacer("read")`
        // segment — see partitionDetail's `partitionsLiteral` javadoc for why an unused
        // literal parameter is required rather than filtered out.
        ManagementRoutes.<ReadEventsResponse> route(ManagementRoute.STREAM_READ)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.spacer("read"),
                                  PathParameter.aInteger())
                        .withQuery(QueryParameter.aLong("from"),
                                   QueryParameter.aInteger("max"),
                                   QueryParameter.aString("readPreference"))
                        .to(this::readEvents)
                        .asJson(),
                         ManagementRoutes.<GroupListResponse> route(ManagementRoute.STREAMS_GROUPS_LIST)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("groups"))
                                         .toResult(this::listGroups)
                                         .asJson(),

        // ---------- Tail subscription (Wave 6B: SSE/WebSocket deferred to issue #212) ----------
        ManagementRoutes.<StreamMetadataResponse> route(ManagementRoute.STREAMS_TAIL)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.spacer("tail"))
                        .toResult(this::tailDeferred)
                        .asJson(),

        // ---------- Polling-based paginated event read (Wave 6B; RC1) ----------
        ManagementRoutes.<StreamEventsResponse> route(ManagementRoute.STREAMS_EVENTS)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.spacer("events"))
                        .withQuery(QueryParameter.aLong("fromOffset"),
                                   QueryParameter.aInteger("maxEvents"))
                        .to(this::streamEvents)
                        .asJson(),

        // ---------- Write routes (OPERATOR_AND_ABOVE for /api/streams) ----------
        // #1224: catalog-addressed create — registers the catalog entry AND materializes the rings,
        // the two writes the old body-carried STREAM_CREATE only did the second of (never appeared in
        // `streams list`, which reads the registry). Same bare 3-path-param shape as STREAMS_DELETE;
        // the POST method alone disambiguates it from STREAMS_METADATA's GET on the same bucket.
        ManagementRoutes.<CreateResponse> route(ManagementRoute.STREAMS_CREATE)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString())
                        .withBody(CreateRequest.class)
                        .toResult(this::createStream)
                        .asJson(),
                         ManagementRoutes.<PublishResponse> route(ManagementRoute.STREAMS_PUBLISH)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("publish"))
                                         .withBody(PublishRequest.class)
                                         .to((ns, stream, version, _, request) -> publishEvent(ns,
                                                                                               stream,
                                                                                               version,
                                                                                               request))
                                         .asJson(),
                         ManagementRoutes.<PublishBatchResponse> route(ManagementRoute.STREAMS_PUBLISH_BATCH)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("publish-batch"))
                                         .withBody(PublishRequest[].class)
                                         .to(this::publishBatch)
                                         .asJson(),
                         ManagementRoutes.<GroupResponse> route(ManagementRoute.STREAMS_GROUP_CREATE)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("groups"))
                                         .withBody(GroupCreateRequest.class)
                                         .toResult(this::createGroup)
                                         .asJson(),
                         ManagementRoutes.<GroupResponse> route(ManagementRoute.STREAMS_GROUP_DELETE)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("groups"),
                                                   PathParameter.aString())
                                         .toResult(this::deleteGroup)
                                         .asJson(),

        // ---------- Destructive (ADMIN_ONLY via override) ----------
        ManagementRoutes.<DeleteResponse> route(ManagementRoute.STREAMS_DELETE)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aString(),
                                  PathParameter.aString())
                        .to(this::deleteStream)
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

    private Result<StreamMetadataResponse> resolveLatest(String namespace, String stream, String latestLiteral) {
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

    /// `partitionsLiteral` binds the interleaved `spacer("partitions")` trailing segment
    /// [ManagementRoute#STREAM_PARTITION] declares in its tokens() (management-api-versioning-spec.md
    /// §3.2); `RequestContext.matchPath` binds every declared path slot positionally, spacer or real,
    /// so the handler must accept it even though the literal itself (always `"partitions"`) carries
    /// no information here.
    private Result<PartitionDetail> partitionDetail(String namespace,
                                                    String stream,
                                                    String version,
                                                    String partitionsLiteral,
                                                    Integer partition) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .flatMap(addr -> streamManager().partitionInfo(StreamManager.engineKey(addr),
                                                                             partition))
                              .map(PartitionDetail::partitionDetail);
    }

    /// #260/#261/#333 replica-state observability, catalog-identity variant — see
    /// [StreamRoutes#replicaDetails] for the flat/LOCAL sibling and [StreamRoutes#toReplicasResponse]
    /// for the shared view-to-DTO mapping. Always succeeds: an unknown address yields an empty
    /// replica set with `servedByOwner=false`, itself the operator-meaningful answer.
    /// `replicasLiteral` binds the interleaved `spacer("replicas")` trailing segment — see
    /// [#partitionDetail]'s `partitionsLiteral` javadoc for why an unused literal parameter is
    /// required rather than filtered out.
    private Result<StreamReplicasResponse> replicaDetail(String namespace,
                                                         String stream,
                                                         String version,
                                                         String replicasLiteral,
                                                         Integer partition) {
        return ResourceAddress.resourceAddress(namespace, stream, version).map(addr -> StreamRoutes.toReplicasResponse(streamReadRouter().replicaSnapshot(StreamManager.engineKey(addr),
                                                                                                                                                          partition)));
    }

    /// Catalog-scoped STREAM_GET handler — #742 fold of [StreamRoutes#streamInfo]'s flat-name legacy
    /// handler onto the catalog identity shape (namespace/stream/version). Reuses
    /// [StreamRoutes.StreamInfoResponse] and [StreamRoutes.PartitionDetail] (both package-visible)
    /// so the response shape has one definition, the same reuse pattern as [#replicaDetail] via
    /// [StreamRoutes#toReplicasResponse]. `infoLiteral` binds the trailing `spacer("info")` segment —
    /// see [#partitionDetail]'s `partitionsLiteral` javadoc.
    private Result<StreamRoutes.StreamInfoResponse> streamInfo(String namespace,
                                                               String stream,
                                                               String version,
                                                               String infoLiteral) {
        return ResourceAddress.resourceAddress(namespace, stream, version).flatMap(addr -> buildStreamInfoResponse(StreamManager.engineKey(addr)));
    }

    private Result<StreamRoutes.StreamInfoResponse> buildStreamInfoResponse(String engineKey) {
        return streamManager().streamInfo(engineKey)
                            .toResult(STREAM_NOT_FOUND)
                            .flatMap(info -> streamManager().allPartitionInfo(engineKey)
                                                          .map(partitions -> partitions.stream()
                                                                                       .map(StreamRoutes.PartitionDetail::fromPartitionInfo)
                                                                                       .toList())
                                                          .map(details -> new StreamRoutes.StreamInfoResponse(info.name(),
                                                                                                              info.partitions(),
                                                                                                              info.totalEvents(),
                                                                                                              info.totalBytes(),
                                                                                                              details)));
    }

    private Promise<ReadEventsResponse> readEvents(String namespace,
                                                   String stream,
                                                   String version,
                                                   String readLiteral,
                                                   Integer partition,
                                                   Option<Long> fromOpt,
                                                   Option<Integer> maxOpt,
                                                   Option<String> preferenceOpt) {
        var from = fromOpt.or(0L);
        var max = maxOpt.or(DEFAULT_MAX_EVENTS);
        var preference = preferenceOpt.fold(() -> ReadPreference.GOVERNOR, StreamApiRoutes::parseReadPreference);

        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .async()
                              .flatMap(addr -> readEventsAtPartition(addr, partition, from, max, preference));
    }

    private Promise<ReadEventsResponse> readEventsAtPartition(ResourceAddress addr,
                                                              Integer partition,
                                                              long fromOffset,
                                                              int maxEvents,
                                                              ReadPreference preference) {
        return streamReadRouter().read(StreamManager.engineKey(addr),
                                       partition,
                                       fromOffset,
                                       maxEvents,
                                       preference)
                               .map(StreamApiRoutes::toReadEventsResponse);
    }

    private static ReadEventsResponse toReadEventsResponse(List<RawEvent> events) {
        return ReadEventsResponse.readEventsResponse(events.stream().map(EventRecord::eventRecord).toList());
    }

    private static ReadPreference parseReadPreference(String value) {
        return switch (value.toUpperCase()) {
            case "ANY_REPLICA", "ANY-REPLICA" -> ReadPreference.ANY_REPLICA;
            case "NEAREST" -> ReadPreference.NEAREST;
            case "LINEARIZABLE" -> ReadPreference.LINEARIZABLE;
            default -> ReadPreference.GOVERNOR;
        };
    }

    private Result<GroupListResponse> listGroups(String namespace,
                                                 String stream,
                                                 String version,
                                                 String groupsLiteral) {
        return ResourceAddress.resourceAddress(namespace, stream, version).map(addr -> new GroupListResponse(addr.asString(),
                                                                                                             List.of()));
    }

    /// Wave 6B: Tail subscription via SSE/WebSocket is deferred to issue #212 — the streaming
    /// protocol layer requires chunked encoding, keep-alive, and fan-out infrastructure beyond the
    /// scope of RC1. Operators polling for new events should use GET `/events?fromOffset=…` (which
    /// is the always-available polling fallback that the `aether stream tail` CLI now drives).
    private Result<StreamMetadataResponse> tailDeferred(String namespace,
                                                        String stream,
                                                        String version,
                                                        String tailLiteral) {
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
                                                       String eventsLiteral,
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

    /// Package-visible for direct unit coverage of the [StreamManager#engineKey] round-trip
    /// property alongside [StreamRoutes#createStream] and [#deleteStream] — the entry point a real
    /// `POST /streams/{namespace}/{stream}/{version}/events` request also goes through.
    Promise<PublishResponse> publishEvent(String namespace, String stream, String version, PublishRequest request) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .async()
                              .flatMap(addr -> publishOne(addr, request).map(offset -> new PublishResponse(addr.asString(),
                                                                                                           offset)));
    }

    private Promise<PublishBatchResponse> publishBatch(String namespace,
                                                       String stream,
                                                       String version,
                                                       String publishBatchLiteral,
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

    /// Owner-routed publish to an explicit `partition` (#524: default 0 — unchanged from the earlier
    /// hardwired behavior — when the request omits it). When this node is metadata-only (#265) the
    /// write is forwarded to the partition owner via [StreamWriteRouter] instead of failing
    /// PARTITION_NOT_LOCAL on a local append; an owner node appends locally (and awaits the min-sync
    /// barrier).
    ///
    /// Engine key via [StreamManager#engineKey], not `addr.asString()`: a `system`-namespace address
    /// must resolve to the bare name the engine keys operator-created flat streams by, or this publish
    /// materializes a stream under a different key than the one STREAM_CREATE minted.
    private Promise<Long> publishOne(ResourceAddress addr, PublishRequest request) {
        var streamName = StreamManager.engineKey(addr);
        var payload = decodePayload(request.data());
        var partition = Option.option(request.partition()).or(DEFAULT_PUBLISH_PARTITION);

        return ensureStreamExists(streamName).async()
                                 .flatMap(_ -> validatePartition(streamName, partition).async())
                                 .flatMap(_ -> streamWriteRouter().publish(streamName,
                                                                           partition,
                                                                           payload,
                                                                           System.currentTimeMillis()));
    }

    /// #524 guard: an out-of-range `partition` on a Management-API publish must fail 4xx naming the
    /// valid range — never a silent write to partition 0, never a 500. Reads the committed config's
    /// DECLARED partition count (`StreamInfo#partitions`, backed by `declaredPartitions()` — the
    /// committed `StreamConfig`'s count, not the partition rings actually materialized locally).
    /// `ensureStreamExists` (run first in [#publishOne]) guarantees this entry is present on success,
    /// so an empty `streamInfo()` here means that guarantee did not hold; never guess a count in that
    /// case — report the stream unavailable instead of validating against a fabricated default.
    private Result<Unit> validatePartition(String streamName, int partition) {
        return streamManager().streamInfo(streamName)
                            .map(StreamPartitionManager.StreamInfo::partitions)
                            .toResult(new ManagementServerError.StreamUnavailable(streamName,
                                                                                  "partition count is not known"))
                            .flatMap(partitionCount -> partition >= 0 && partition < partitionCount
                                                       ? Result.unitResult()
                                                       : new ManagementServerError.InvalidPartition(partition,
                                                                                                    partitionCount).result());
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
    ///
    /// #524 SHOULD-FIX 1: a materialization failure here is never a transient race — the only failure
    /// modes `ensureStreamMaterialized` can return are permanent (capacity exhausted, or STRONG
    /// consistency requiring AHSE storage); the transient config-visibility race belongs to a different
    /// call path entirely. So it must never be swallowed: on success `streams` is guaranteed to hold
    /// the entry (making [#validatePartition]'s read safe), and on failure this reports a typed 409
    /// naming the stream and cause instead of silently proceeding with an unknown partition count.
    ///
    /// #1282: the management default is never fabricated under a reserved kind prefix — a `topic`/`entity`
    /// namespace address yields a `topic:`/`entity:` engine key, which only internal provisioning may
    /// mint. A committed config for such a name is the real resource's and is still adopted.
    private Result<Unit> ensureStreamExists(String streamName) {
        return nodeSupplier.get()
                           .kvStore()
                           .getTyped(StreamConfigKey.streamConfigKey(streamName),
                                     StreamConfigValue.class)
                           .map(value -> Result.success(value.config()))
                           .or(() -> managementDefaultConfig(streamName))
                           .flatMap(config -> materializeForPublish(streamName, config));
    }

    private static Result<StreamConfig> managementDefaultConfig(String streamName) {
        return ReservedStreamNames.requireUnreserved(streamName)
                                  .map(unreserved -> StreamConfig.streamConfig(unreserved,
                                                                               DEFAULT_PARTITIONS,
                                                                               MANAGEMENT_API_RETENTION,
                                                                               "latest"));
    }

    private Result<Unit> materializeForPublish(String streamName, StreamConfig config) {
        return streamManager().ensureStreamMaterialized(config)
                            .mapError(cause -> new ManagementServerError.StreamUnavailable(streamName,
                                                                                           cause.message()));
    }

    /// #1224: `aether stream create` / `POST /streams/{namespace}/{stream}/{version}` — the
    /// catalog-addressed replacement for the legacy body-carried `STREAM_CREATE`, which only
    /// materialized rings via [StreamPartitionManager#createStream] and never touched
    /// [StreamRegistry], so a created stream never appeared in `GET /streams` (`streams list`).
    /// This handler does both: materialize, then register — see [#materializeAndRegister].
    ///
    /// Package-private (not `private`), matching [#deleteStream]'s exact visibility, so
    /// `StreamApiRoutesCreateStreamTest` can exercise the full create path — including the
    /// catalog registration this ticket adds — without going through HTTP dispatch.
    Result<CreateResponse> createStream(String namespace, String stream, String version, CreateRequest request) {
        return ResourceAddress.resourceAddress(namespace, stream, version).flatMap(addr -> createAtAddress(addr, request));
    }

    /// Idempotent on an already-registered address (mirrors [StreamRoutes#createStreamWithConfig]'s
    /// check-exists-first shape): a repeat `create` for the same address reports `"exists"` rather
    /// than re-attempting registration and hitting [StreamRegistry.StreamRegistryError.General#ALREADY_REGISTERED].
    private Result<CreateResponse> createAtAddress(ResourceAddress addr, CreateRequest request) {
        return namespacesService.lookup(addr)
                                .map(_ -> Result.success(new CreateResponse(addr.asString(),
                                                                            "exists")))
                                .or(() -> materializeAndRegister(addr, request));
    }

    /// Registers a PERMANENT catalog reference ([StreamRegistryEntry.RegisteredByKind#OPERATOR]): no
    /// deployment owns this address, so nothing in the blueprint release path can ever reach it —
    /// removable only by explicit `stream delete` ([#destroyAtAddress]). Materializes first via the
    /// durable/explicit [StreamPartitionManager#createStream] path (not [StreamPartitionManager#ensureStreamMaterialized],
    /// which is the ASYNC publish-auto-create path used by [#ensureStreamExists]), tolerating its
    /// `STREAM_ALREADY_EXISTS` duplicate-create sentinel via [StreamCreateOutcome] like every other
    /// idempotent caller of that method.
    ///
    /// #1282: refused before anything is minted or registered when the engine key carries a reserved
    /// kind prefix ([ReservedStreamNames]) — a `topic` or `entity` namespace address would otherwise plant
    /// an operator-chosen config under a name only internal provisioning may create.
    private Result<CreateResponse> materializeAndRegister(ResourceAddress addr, CreateRequest request) {
        return ReservedStreamNames.requireUnreserved(StreamManager.engineKey(addr))
                                  .flatMap(engineKey -> mintOperatorStream(engineKey, request))
                                  .flatMap(_ -> registerCatalogEntry(addr))
                                  .map(_ -> new CreateResponse(addr.asString(),
                                                               "created"));
    }

    private Result<Unit> mintOperatorStream(String engineKey, CreateRequest request) {
        var partitions = Option.option(request.partitions()).or(DEFAULT_PARTITIONS);
        var config = StreamConfig.streamConfig(engineKey, partitions, MANAGEMENT_API_RETENTION, "latest");

        return StreamCreateOutcome.tolerateAlreadyExists(streamManager().createStream(config));
    }

    private Result<StreamRegistryEntry> registerCatalogEntry(ResourceAddress addr) {
        return namespacesService.registry()
                                .register(StreamRegistryEntry.operator(addr,
                                                                       MANAGEMENT_API_RETENTION,
                                                                       Instant.now()))
                                .mapError(cause -> mapRegistrationError(addr, cause));
    }

    private static Cause mapRegistrationError(ResourceAddress addr, Cause cause) {
        return cause == StreamRegistry.StreamRegistryError.General.ALREADY_REGISTERED
               ? new ManagementServerError.StreamAlreadyRegistered(addr.asString())
               : cause;
    }

    private Result<GroupResponse> createGroup(String namespace,
                                              String stream,
                                              String version,
                                              String groupsLiteral,
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

    private Result<GroupResponse> deleteGroup(String namespace,
                                              String stream,
                                              String version,
                                              String groupsLiteral,
                                              String group) {
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

    Promise<DeleteResponse> deleteStream(String namespace, String stream, String version) {
        return ResourceAddress.resourceAddress(namespace, stream, version)
                              .async()
                              .flatMap(this::destroyAtAddress);
    }

    /// Engine key via [StreamManager#engineKey] — same reasoning as [#publishOne]: a `system`-namespace
    /// address must resolve to the bare name the engine keys the stream by, or this deletes the wrong key.
    ///
    /// #1224: releases the catalog reference this address holds, in addition to the pre-existing
    /// physical ring teardown — a stream created before this migration (or one this handler itself
    /// never registered, e.g. a legacy `STREAM_CREATE` name) has no catalog entry, so [#releaseCatalogEntry]
    /// tolerates [StreamRegistry.StreamRegistryError.General#NOT_FOUND] as a benign no-op rather than
    /// failing the whole delete.
    private Promise<DeleteResponse> destroyAtAddress(ResourceAddress addr) {
        var streamName = StreamManager.engineKey(addr);

        return streamManager().destroyStream(streamName)
                            .async()
                            .flatMap(_ -> releaseCatalogEntry(addr))
                            .map(_ -> new DeleteResponse(addr.asString(),
                                                         "deleted"));
    }

    private Promise<Unit> releaseCatalogEntry(ResourceAddress addr) {
        return namespacesService.registry()
                                .releaseReference(addr)
                                .fold(StreamApiRoutes::tolerateMissingRegistryEntry);
    }

    private static Promise<Unit> tolerateMissingRegistryEntry(Result<StreamRegistry.ReleaseOutcome> result) {
        return result.fold(StreamApiRoutes::recoverIfNotFound,
                           _ -> Promise.success(Unit.unit()));
    }

    private static Promise<Unit> recoverIfNotFound(Cause cause) {
        return cause == StreamRegistry.StreamRegistryError.General.NOT_FOUND
               ? Promise.success(Unit.unit())
               : cause.promise();
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
