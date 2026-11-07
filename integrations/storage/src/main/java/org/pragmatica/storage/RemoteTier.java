package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Error;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// S3-compatible storage tier: one S3 object per content-addressed block, written whole by `putObject`
/// (no multipart: a block is at most the instance's chunk size, and S3 multipart parts must be at least
/// 5 MiB except the last). Keys are sharded under the prefix: `{prefix}{hex[0:2]}/{hex[2:4]}/{fullHex}`,
/// where a non-empty prefix not ending in `/` gets one.
///
/// **Shared.** A bucket is reachable from every node, so [#isShared] is true: node-local GC never deletes
/// here on its own refcount ([StorageInstance#deleteFromPrivateTiers]), and nothing here batches deletes --
/// the only deletes are explicit ones ([StorageInstance#delete]), one object each. Cluster-wide collection
/// of a shared tier, and the `DeleteObjects` batching it would use, belong to the cluster GC (spec §A1.5).
///
/// **Durable only when built for production.** [#isDurable] is true only for the tier built by
/// [#durableRemoteTier], the construction path of a node's `[storage.<instance>.shared] kind = "s3"`
/// section: there, a resolved `put` means the PUT was answered 200, and what that is worth is the
/// provider's durability, not Aether's. The other factories (tests, the pre-#249 [RemoteTierConfig] path)
/// report false.
///
/// **Bounded and timed.** The production tier runs at most `maxInflight` requests at once (others wait,
/// without a thread, for a permit) and bounds each request by `requestTimeout`.
///
/// **Anchors** ([#putAnchor]) are objects under `{prefix}anchors/`, laid out and framed by [AnchorFrame]. The newest
/// version is the first key of a one-key LIST, so it is found in one request however many versions exist. That needs two
/// things of the endpoint: a LIST that sees a key once its PUT was answered (AWS S3 since 2020-12, MinIO), and keys
/// listed in ascending order (AWS general-purpose buckets and MinIO; NOT AWS directory buckets). `anchors/` is not a
/// two-hex-character shard, so a scan of block shards never reads it.
///
/// **Failures are classified** ([RemoteTierError]): an unreachable or failing provider is
/// [RemoteTierError.Unavailable] (retriable), never an absent block; a refusal is [RemoteTierError.Rejected].
public final class RemoteTier implements StorageTier {
    private static final String CONTENT_TYPE = "application/octet-stream";
    private static final String PROBE_DIRECTORY = ".aether-probe/";
    private static final int UNBOUNDED = Integer.MAX_VALUE;
    /// A block object's last key segment: the full lowercase hex of its id ([BlockId#hexString]).
    private static final Pattern BLOCK_NAME = Pattern.compile("[0-9a-f]{64}");
    /// A block's touch marker is `{blockKey}.touched` ([#touch]); the ascending LIST order puts it right after the block.
    private static final String TOUCH_MARKER_SUFFIX = ".touched";
    private static final String MARKER_CONTENT_TYPE = "text/plain";

    private final S3Client s3Client;
    private final String keyPrefix;
    private final long maxBytes;
    private final boolean durable;
    private final InflightLimiter limiter;
    private final Option<TimeSpan> requestTimeout;
    private final AtomicLong usedBytes = new AtomicLong(0);

    private RemoteTier(S3Client s3Client,
                       String prefix,
                       long maxBytes,
                       boolean durable,
                       int maxInflight,
                       Option<TimeSpan> requestTimeout) {
        this.s3Client = s3Client;
        this.keyPrefix = keyPrefixOf(prefix);
        this.maxBytes = maxBytes;
        this.durable = durable;
        this.limiter = InflightLimiter.inflightLimiter(maxInflight);
        this.requestTimeout = requestTimeout;
    }

    /// Creates a RemoteTier from configuration, using default S3 client. Not durable: see the class doc.
    public static RemoteTier remoteTier(RemoteTierConfig config) {
        return new RemoteTier(S3Client.s3Client(config.s3Config()),
                              config.prefix(),
                              config.maxBytes(),
                              false,
                              UNBOUNDED,
                              none());
    }

    /// Creates a RemoteTier with an externally provided S3 client (for testing). Not durable: see the class doc.
    public static RemoteTier remoteTier(S3Client s3Client, String prefix, long maxBytes) {
        return new RemoteTier(s3Client, prefix, maxBytes, false, UNBOUNDED, none());
    }

    /// The production construction path (#249): the tier a node builds from `[storage.<instance>.shared]
    /// kind = "s3"`. Durable, at most `maxInflight` concurrent requests, each bounded by `requestTimeout`.
    /// No node-local capacity: a bucket's size is the provider's business, so [#maxBytes] is unbounded.
    public static Result<RemoteTier> durableRemoteTier(S3Client s3Client,
                                                       String prefix,
                                                       int maxInflight,
                                                       TimeSpan requestTimeout) {
        return maxInflight > 0
               ? success(new RemoteTier(s3Client, prefix, Long.MAX_VALUE, true, maxInflight, some(requestTimeout)))
               : RemoteTierConfig.ConfigError.MAX_INFLIGHT_NOT_POSITIVE.result();
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        var key = s3Key(id);

        return request("GET", key, () -> s3Client.getObject(key));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        if (!reserveCapacity(content.length)) {
            return StorageError.TierFull.tierFull(TierLevel.REMOTE,
                                                  usedBytes.get(),
                                                  maxBytes)
                                        .promise();
        }

        var key = s3Key(id);

        return request("PUT", key, () -> s3Client.putObject(key, content, CONTENT_TYPE));
    }

    /// Deletes the block, then its touch marker ([#touch]). A marker written by a touch that raced this delete outlives
    /// it as an orphan: harmless, since [#stat] and [#scan] read a marker only beside its block, and swept by the reclaim
    /// phase (A4).
    @Override
    public Promise<Unit> delete(BlockId id) {
        var key = s3Key(id);
        var marker = markerKey(key);

        return request("DELETE", key, () -> s3Client.deleteObject(key)).flatMap(_ -> request("DELETE",
                                                                                             marker,
                                                                                             () -> s3Client.deleteObject(marker)));
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        var key = s3Key(id);

        return request("HEAD", key, () -> s3Client.headObject(key));
    }

    /// Two HEADs, of the block and of its touch marker ([#touch]): the block's size, and the later of the two
    /// `Last-Modified` times, which have whole-second precision.
    @Override
    public Promise<Option<BlockStat>> stat(BlockId id) {
        var key = s3Key(id);

        return request("HEAD", key, () -> s3Client.statObject(key)).flatMap(found -> found.fold(() -> Promise.success(none()),
                                                                                                block -> withMarker(id,
                                                                                                                    block)));
    }

    private Promise<Option<BlockStat>> withMarker(BlockId id, S3Client.ObjectStat block) {
        var marker = markerKey(block.key());

        return request("HEAD", marker, () -> s3Client.statObject(marker)).map(touched -> some(statOf(id, block, touched)));
    }

    /// One LIST per first-level key prefix of the shard (ascending), each resuming with `start-after`, until the page is
    /// full; a prefix wholly before the cursor is not listed. A listed object under a block prefix whose key is not a
    /// block key -- a touch marker among them -- is skipped; a block's time is the later of its own and its marker's,
    /// which the ascending order lists right after it. Needs the ascending LIST order the anchors need (see the class
    /// doc).
    @Override
    public Promise<Page<BlockStat>> scan(KeyShard shard, Option<Cursor> after, int limit) {
        return KeyShard.pageLimit(limit)
                       .flatMap(_ -> KeyShard.resumeAfter(after))
                       .async()
                       .flatMap(resume -> collect(shard.prefixes(),
                                                  0,
                                                  resume,
                                                  limit,
                                                  List.of()))
                       .map(found -> KeyShard.pageOf(found, limit));
    }

    /// Records an access without writing the block: one HEAD of the block and, only when it is held, one PUT of its
    /// touch marker `{blockKey}.touched`, whose `Last-Modified` [#stat] and [#scan] report when it is the later time. The
    /// block object itself is never written, copied or created, so a touch racing a delete of the block cannot bring
    /// it back: at worst the marker outlives the block as an orphan (see [#delete]). True means the block was held at
    /// the HEAD and the marker PUT was answered; a delete landing after the HEAD can still remove the block, which is
    /// why the reclaim phase (A4) reads the marker time as last access with a grace window longer than a touch takes.
    /// The marker's content is this node's clock at the touch, in epoch milliseconds, for diagnosis only.
    @Override
    public Promise<Boolean> touch(BlockId id) {
        var key = s3Key(id);

        return request("HEAD", key, () -> s3Client.headObject(key)).flatMap(held -> held
                                                                                    ? markTouched(key)
                                                                                    : Promise.success(false));
    }

    private Promise<Boolean> markTouched(String key) {
        var marker = markerKey(key);
        var touchedAt = Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);

        return request("PUT", marker, () -> s3Client.putObject(marker, touchedAt, MARKER_CONTENT_TYPE)).map(_ -> true);
    }

    private Promise<List<BlockStat>> collect(List<String> prefixes,
                                             int index,
                                             Option<String> resume,
                                             int limit,
                                             List<BlockStat> found) {
        if (index >= prefixes.size() || found.size() >= limit) {
            return Promise.success(found);
        }

        var prefix = prefixes.get(index);
        var before = resume.filter(hex -> prefix.compareTo(hex.substring(0, 2)) < 0).isPresent();
        var listed = before
                     ? Promise.success(List.<BlockStat> of())
                     : listUnder(prefix,
                                 resume.filter(hex -> hex.startsWith(prefix)).map(this::blockKey),
                                 none(),
                                 limit - found.size());

        return listed.flatMap(more -> collect(prefixes,
                                              index + 1,
                                              resume,
                                              limit,
                                              Stream.concat(found.stream(), more.stream()).toList()));
    }

    /// `open` is a block listed last on the previous page, whose marker, if any, this page lists first.
    private Promise<List<BlockStat>> listUnder(String prefix,
                                               Option<String> startAfter,
                                               Option<S3Client.ObjectStat> open,
                                               int wanted) {
        var listPrefix = keyPrefix + prefix + "/";

        return request("LIST", listPrefix, () -> s3Client.listObjectStats(listPrefix, startAfter, wanted)).flatMap(listing -> restOf(prefix,
                                                                                                                                     open,
                                                                                                                                     listing,
                                                                                                                                     wanted));
    }

    /// A provider may end a page early with more to come; the prefix is listed on from the last key until `wanted`
    /// blocks are found or it says nothing follows. A block a truncated page ends with stays open: its marker may come
    /// first on the next page, so it is settled there.
    private Promise<List<BlockStat>> restOf(String prefix,
                                            Option<S3Client.ObjectStat> open,
                                            S3Client.ObjectListing listing,
                                            int wanted) {
        var objects = Stream.concat(open.stream(),
                                    listing.objects().stream()).toList();
        var more = listing.truncated() && !listing.objects().isEmpty();
        var stillOpen = more
                        ? Option.option(objects.getLast()).filter(object -> isBlockKey(object.key()))
                        : Option.<S3Client.ObjectStat> none();
        var settled = objects.subList(0,
                                      objects.size() - (stillOpen.isPresent()
                                                        ? 1
                                                        : 0));
        var stats = blockStatsOf(settled);

        return more && stats.size() < wanted
               ? listUnder(prefix,
                           some(listing.objects().getLast().key()),
                           stillOpen,
                           wanted - stats.size()).map(rest -> Stream.concat(stats.stream(),
                                                                            rest.stream())
                                                                    .limit(wanted)
                                                                    .toList())
               : Promise.success(stats.stream().limit(wanted).toList());
    }

    /// The blocks among `objects` (in listing order), each with the time of the marker listed right after it, if any.
    private List<BlockStat> blockStatsOf(List<S3Client.ObjectStat> objects) {
        return IntStream.range(0,
                               objects.size())
                        .boxed()
                        .flatMap(index -> blockStatOf(objects.get(index),
                                                      index + 1 < objects.size()
                                                      ? some(objects.get(index + 1))
                                                      : none()).stream())
                        .toList();
    }

    private Option<BlockStat> blockStatOf(S3Client.ObjectStat object, Option<S3Client.ObjectStat> next) {
        var name = object.key().substring(object.key().lastIndexOf('/') + 1);

        return isBlockKey(object.key())
               ? BlockId.fromHex(name)
                        .option()
                        .map(id -> statOf(id,
                                          object,
                                          next.filter(marker -> marker.key()
                                                                      .equals(markerKey(object.key())))))
               : none();
    }

    private boolean isBlockKey(String key) {
        var name = key.substring(key.lastIndexOf('/') + 1);

        return BLOCK_NAME.matcher(name).matches() && key.equals(blockKey(name));
    }

    private static BlockStat statOf(BlockId id, S3Client.ObjectStat block, Option<S3Client.ObjectStat> marker) {
        return BlockStat.blockStat(id,
                                   block.size(),
                                   Math.max(block.lastModifiedMillis(),
                                            marker.map(S3Client.ObjectStat::lastModifiedMillis)
                                                  .or(block.lastModifiedMillis())));
    }

    private String blockKey(String hex) {
        return keyPrefix + hex.substring(0, 2) + "/" + hex.substring(2, 4) + "/" + hex;
    }

    private static String markerKey(String blockKey) {
        return blockKey + TOUCH_MARKER_SUFFIX;
    }

    @Override
    public TierLevel level() {
        return TierLevel.REMOTE;
    }

    @Override
    public long usedBytes() {
        return usedBytes.get();
    }

    @Override
    public long maxBytes() {
        return maxBytes;
    }

    /// Resolves on the provider's answer to the PUT, as [#put]. Capacity is not reserved: anchors are not blocks.
    @Override
    public Promise<Unit> putAnchor(AnchorName name, long version, byte[] value) {
        if (version < 0) {
            return AnchorError.NegativeVersion.negativeVersion(name, version).promise();
        }

        var key = anchorPrefix(name) + AnchorFrame.key(version);

        return request("PUT",
                       key,
                       () -> s3Client.putObject(key, AnchorFrame.encode(version, value), CONTENT_TYPE));
    }

    /// One LIST of at most one key under the anchor's version prefix, then one GET. None ONLY when the LIST returns no
    /// key: a listed key that is no version key fails closed ([AnchorError.AnchorKeyMalformed]), and so does a listed
    /// version whose GET finds nothing ([AnchorError.AnchorCorrupt]).
    @Override
    public Promise<Option<Anchor>> latestAnchor(AnchorName name) {
        var prefix = anchorPrefix(name);
        var versions = prefix + AnchorFrame.KEY_MARKER;

        return request("LIST", versions, () -> s3Client.listObjects(versions, 1)).flatMap(keys -> readNewest(name,
                                                                                                             prefix,
                                                                                                             keys));
    }

    /// One LIST of at most `scanLimit` keys from the newest end, then one DELETE per version below `below` in it.
    /// Versions kept sort first, so what the page holds past them is the oldest garbage; a backlog is worked off a
    /// page at a time by later calls.
    @Override
    public Promise<Unit> pruneAnchors(AnchorName name, long below, int scanLimit) {
        var prefix = anchorPrefix(name);
        var versions = prefix + AnchorFrame.KEY_MARKER;

        return request("LIST", versions, () -> s3Client.listObjects(versions, scanLimit)).flatMap(keys -> deleteBelow(prefix,
                                                                                                                      keys,
                                                                                                                      below));
    }

    private Promise<Option<Anchor>> readNewest(AnchorName name, String prefix, List<String> keys) {
        return Option.from(keys.stream().findFirst()).fold(() -> Promise.success(none()),
                                                           key -> versionUnder(prefix, key).fold(() -> malformedKey(name,
                                                                                                                    key),
                                                                                                 version -> readAnchor(name,
                                                                                                                       prefix + AnchorFrame.key(version),
                                                                                                                       version)));
    }

    private static Promise<Option<Anchor>> malformedKey(AnchorName name, String key) {
        return AnchorError.AnchorKeyMalformed.anchorKeyMalformed(TierLevel.REMOTE, name, key).promise();
    }

    private Promise<Option<Anchor>> readAnchor(AnchorName name, String key, long version) {
        return request("GET", key, () -> s3Client.getObject(key)).flatMap(frame -> frame.fold(() -> missingNewest(name,
                                                                                                                  version),
                                                                                              bytes -> AnchorFrame.decode(TierLevel.REMOTE,
                                                                                                                          name,
                                                                                                                          version,
                                                                                                                          bytes)
                                                                                                                  .map(Option::some)
                                                                                                                  .async()));
    }

    /// Listed and then gone: only a prune removes versions, and it never removes the newest, so the endpoint's LIST
    /// and GET disagree. Fails closed like a damaged frame.
    private static Promise<Option<Anchor>> missingNewest(AnchorName name, long version) {
        return AnchorError.AnchorCorrupt.anchorCorrupt(TierLevel.REMOTE, name, version, "listed but not found").promise();
    }

    private Promise<Unit> deleteBelow(String prefix, List<String> keys, long below) {
        var deletes = keys.stream()
                          .flatMap(key -> versionUnder(prefix, key).stream())
                          .filter(version -> version < below)
                          .map(version -> prefix + AnchorFrame.key(version))
                          .map(key -> request("DELETE",
                                              key,
                                              () -> s3Client.deleteObject(key)))
                          .toList();

        return Promise.allOf(deletes)
                      .flatMap(results -> Result.allOf(results.stream()).async())
                      .mapToUnit();
    }

    private static Option<Long> versionUnder(String prefix, String key) {
        return key.startsWith(prefix)
               ? AnchorFrame.versionOf(key.substring(prefix.length()))
               : none();
    }

    private String anchorPrefix(AnchorName name) {
        return keyPrefix + AnchorFrame.DIRECTORY + "/" + name.storageSegment() + "/";
    }

    /// A bucket is cluster-wide: node-local GC must never delete here on this node's refcount alone.
    @Override
    public boolean isShared() {
        return true;
    }

    /// True only for the tier built by [#durableRemoteTier]; see the class doc.
    @Override
    public boolean isDurable() {
        return durable;
    }

    /// The boot probe (#249): PUT, GET and DELETE of `{prefix}.aether-probe/{nodeId}`, with the GET required
    /// to return exactly the bytes written. Any failure fails the promise: a configured durable tier that is
    /// unreachable, refuses this node's credentials, or lacks read-after-write is a misconfiguration.
    public Promise<Unit> probe(String nodeId) {
        var key = keyPrefix + PROBE_DIRECTORY + nodeId;
        var payload = ("aether-probe " + nodeId).getBytes(StandardCharsets.UTF_8);

        return request("PUT",
                       key,
                       () -> s3Client.putObject(key, payload, CONTENT_TYPE)).flatMap(_ -> request("GET",
                                                                                                  key,
                                                                                                  () -> s3Client.getObject(key)))
                      .flatMap(readBack -> requireSame(key, payload, readBack))
                      .flatMap(_ -> request("DELETE",
                                            key,
                                            () -> s3Client.deleteObject(key)));
    }

    /// Reads the object `{prefix}{name}` (e.g. the encryption marker), absent when it does not exist.
    public Promise<Option<byte[]>> readMarker(String name) {
        var key = keyPrefix + name;

        return request("GET", key, () -> s3Client.getObject(key));
    }

    /// Writes the object `{prefix}{name}` (e.g. the encryption marker).
    public Promise<Unit> writeMarker(String name, byte[] content) {
        var key = keyPrefix + name;

        return request("PUT", key, () -> s3Client.putObject(key, content, CONTENT_TYPE));
    }

    /// Up to `limit` keys under the prefix other than boot-probe objects (any node's): what the prefix already
    /// holds. Used before encryption is first enabled over the prefix, so plaintext blocks are refused rather than
    /// stranded. Needs list-after-write from the endpoint to see objects written just before.
    public Promise<List<String>> existingObjects(int limit) {
        return request("LIST", keyPrefix, () -> s3Client.listObjects(keyPrefix, limit)).map(this::withoutProbes);
    }

    private List<String> withoutProbes(List<String> keys) {
        return keys.stream()
                   .filter(key -> !key.startsWith(keyPrefix + PROBE_DIRECTORY))
                   .toList();
    }

    private <T> Promise<T> request(String operation, String key, Fn0<Promise<T>> call) {
        return limiter.run(() -> bounded(call.apply()))
                      .mapError(cause -> classify(operation, key, cause));
    }

    private <T> Promise<T> bounded(Promise<T> call) {
        return requestTimeout.fold(() -> call, call::timeout);
    }

    private static Promise<Unit> requireSame(String key, byte[] expected, Option<byte[]> readBack) {
        return readBack.filter(bytes -> Arrays.equals(bytes, expected))
                       .map(_ -> unit())
                       .async(RemoteTierError.ProbeMismatch.FACTORY.apply(key,
                                                                          readBack.map(bytes -> bytes.length
                                                                                               + " different bytes")
                                                                                  .or("nothing")));
    }

    /// A refusal for what the request is (403, a missing bucket, a redirect -- S3 answers a request for another
    /// region with 301 -- or another 3xx/4xx except 408/429) is terminal; everything else -- transport failures,
    /// timeouts, 5xx, 408, 429 -- is the provider being unavailable.
    static Cause classify(String operation, String key, Cause cause) {
        return isRejection(cause)
               ? RemoteTierError.Rejected.FACTORY.apply(operation, key, cause)
               : RemoteTierError.Unavailable.FACTORY.apply(operation, key, cause);
    }

    private static boolean isRejection(Cause cause) {
        return switch (cause) {
            case S3Error.AccessDenied _ -> true;
            case S3Error.BucketNotFound _ -> true;
            case S3Error.ApiError api -> api.statusCode() >= 300 && api.statusCode() < 500 && api.statusCode() != 408 && api.statusCode() != 429;
            default -> false;
        };
    }

    /// Atomic CAS capacity reservation — prevents TOCTOU race.
    private boolean reserveCapacity(int contentLength) {
        long current;
        long updated;

        do {
            current = usedBytes.get();
            updated = current + contentLength;
            if (updated > maxBytes || updated < current) {
                return false;
            }
        } while (!usedBytes.compareAndSet(current, updated));

        return true;
    }

    private String s3Key(BlockId id) {
        return keyPrefix + id.shardPrefix() + "/" + id.hexString();
    }

    private static String keyPrefixOf(String prefix) {
        return prefix.isEmpty() || prefix.endsWith("/")
               ? prefix
               : prefix + "/";
    }
}
