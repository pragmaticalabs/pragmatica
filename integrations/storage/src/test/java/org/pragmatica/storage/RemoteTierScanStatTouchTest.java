package org.pragmatica.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// The A2b tier contract on [RemoteTier] over an in-memory S3 client whose clock the test moves.
class RemoteTierScanStatTouchTest extends TierScanStatTouchContract {
    private final RemoteTierTest.InMemoryS3Client s3 = new RemoteTierTest.InMemoryS3Client();

    @Override
    protected StorageTier newTier() {
        return RemoteTier.remoteTier(s3, "scan-prefix", 1 << 20);
    }

    @Override
    protected void age(BlockId id) {
        s3.clock.addAndGet(60_000L);
    }

    /// A provider that ends every LIST page after one object, while more follow: the tier lists on from the last key.
    /// The blocks share one first-byte prefix, so only listing on within that prefix can fill a page.
    @Test
    void scan_listsOnWhenTheProviderEndsAPageEarly() {
        var ids = contentsSharingOneFirstByte(6).stream()
                                                .map(this::store)
                                                .toList();

        s3.listPageCap.set(1);

        assertThat(scanAll(KeyShard.all(), 4)).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(tier.scan(KeyShard.all(), Option.none(), 4).await().unwrap().items()).hasSize(4);
    }

    private static List<byte[]> contentsSharingOneFirstByte(int count) {
        var firstByte = BlockId.blockId(bytes("short page 0")).unwrap().hash()[0];

        return IntStream.iterate(0, i -> i + 1)
                        .mapToObj(i -> bytes("short page " + i))
                        .filter(content -> BlockId.blockId(content).unwrap().hash()[0] == firstByte)
                        .limit(count)
                        .toList();
    }

    /// Objects under the prefix that are not blocks -- a boot probe, the encryption marker -- are never listed.
    @Test
    void scan_skipsObjectsThatAreNotBlocks() {
        var id = store(bytes("the block"));
        var remote = (RemoteTier) tier;

        remote.writeMarker(".aether-encryption", bytes("marker")).await().unwrap();
        s3.putObject("scan-prefix/" + id.hexString().substring(0, 2) + "/stray", bytes("stray"), "text/plain").await().unwrap();
        // A block-named object one level too shallow is not a block key either.
        s3.putObject("scan-prefix/" + id.hexString().substring(0, 2) + "/" + id.hexString(), bytes("misplaced"), "text/plain")
          .await()
          .unwrap();

        assertThat(scanAll(KeyShard.all(), 10)).containsExactly(id);
    }

    /// The S3 touch never writes the block: it writes one marker object beside it, carrying the touching node's clock.
    @Test
    void touch_writesAMarkerBesideTheBlock_andNeverTheBlock() {
        var id = store(bytes("touched in place"));
        var blockPuts = s3.blockPuts();

        assertThat(tier.touch(id).await().unwrap()).isTrue();

        assertThat(s3.blockPuts()).as("the block is not rewritten").isEqualTo(blockPuts);
        assertThat(s3.keys()).containsExactly(keyOf(id), keyOf(id) + ".touched");
        assertThat(Long.parseLong(new String(s3.getObject(keyOf(id) + ".touched").await().unwrap().unwrap(),
                                             StandardCharsets.UTF_8))).as("the marker carries the touch time")
                                                                      .isPositive();
    }

    @Test
    void touch_ofAnAbsentBlock_writesNoMarker() {
        var id = BlockId.blockId(bytes("never stored")).unwrap();

        assertThat(tier.touch(id).await().unwrap()).isFalse();

        assertThat(s3.putKeys).isEmpty();
        assertThat(s3.keys()).isEmpty();
    }

    @Test
    void delete_removesTheBlockAndItsMarker() {
        var id = store(bytes("touched, then deleted"));

        tier.touch(id).await().unwrap();
        tier.delete(id).await().unwrap();

        assertThat(s3.keys()).isEmpty();
    }

    /// Markers are never listed as blocks: neither a touched block's marker nor an orphan one, left by a touch that
    /// raced a delete of its block.
    @Test
    void scan_neverListsTouchMarkers() {
        var touched = store(bytes("touched block"));
        var untouched = store(bytes("untouched block"));
        var gone = BlockId.blockId(bytes("deleted while touched")).unwrap();

        tier.touch(touched).await().unwrap();
        s3.putObject(keyOf(gone) + ".touched", bytes("1"), "text/plain").await().unwrap();

        assertThat(scanAll(KeyShard.all(), 10)).containsExactlyInAnyOrder(touched, untouched);
        assertThat(scanAll(KeyShard.all(), 1)).containsExactlyInAnyOrder(touched, untouched);
        assertThat(tier.stat(gone).await().unwrap().isEmpty()).as("an orphan marker is no block").isTrue();
    }

    /// A provider page that ends on a block leaves its marker to the next page: the block's time is still the touch's.
    @Test
    void scan_readsTheMarkerOfABlockThatEndsAProviderPage() {
        var ids = contentsSharingOneFirstByte(3).stream()
                                                .map(this::store)
                                                .toList();

        s3.clock.addAndGet(60_000L);
        ids.forEach(id -> tier.touch(id).await().unwrap());
        s3.listPageCap.set(1);

        var scanned = scanAllStats(KeyShard.all(), 2);

        assertThat(scanned.stream().map(BlockStat::id).toList()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(scanned).allMatch(stat -> stat.writtenAtMillis() == s3.clock.get(), "every block reports its touch");
    }

    /// A block takes the time of its own marker only: an untouched block listed right before a later-written neighbour
    /// reports its own write time, in the scan and in stat alike.
    @Test
    void scan_aBlockNeverTakesTheTimeOfAnAdjacentObject() {
        var contents = contentsSharingOneFirstByte(2).stream()
                                                     .sorted(Comparator.comparing(c -> BlockId.blockId(c).unwrap().hexString()))
                                                     .toList();
        var first = store(contents.get(0));
        var firstTime = s3.clock.get();

        s3.clock.addAndGet(60_000L);
        var second = store(contents.get(1));

        var scanned = scanAllStats(KeyShard.all(), 10);

        assertThat(scanned).hasSize(2);
        assertThat(scanned.stream().filter(stat -> stat.id().equals(first)).findFirst().orElseThrow().writtenAtMillis())
            .as("the earlier block keeps its own write time").isEqualTo(firstTime);
        assertThat(tier.stat(first).await().unwrap().map(BlockStat::writtenAtMillis).or(-1L)).isEqualTo(firstTime);
        assertThat(scanned.stream().filter(stat -> stat.id().equals(second)).findFirst().orElseThrow().writtenAtMillis())
            .isEqualTo(s3.clock.get());
    }

    /// Delete removes the block first and its marker second, so a failure between the two leaves only an orphan marker,
    /// which no operation reports as a block; a retry removes it.
    @Test
    void delete_removesTheBlockBeforeItsMarker_andAMarkerFailureLeavesOnlyAnOrphan() {
        var order = new ArrayList<String>();
        var failMarker = new boolean[]{false};
        var recording = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                          new Class<?>[]{S3Client.class},
                                                          (proxy, method, args) -> {
                                                              if (method.getName().equals("deleteObject")) {
                                                                  var marker = ((String) args[0]).endsWith(".touched");
                                                                  order.add(marker ? "marker" : "block");
                                                                  if (marker && failMarker[0]) {
                                                                      return Promise.failure(Causes.cause("marker delete failed"));
                                                                  }
                                                              }
                                                              try {
                                                                  return method.invoke(s3, args);
                                                              } catch (InvocationTargetException e) {
                                                                  throw e.getCause();
                                                              }
                                                          });
        var recorded = RemoteTier.remoteTier(recording, "scan-prefix", 1 << 20);
        var content = bytes("deleted in order");
        var id = BlockId.blockId(content).unwrap();

        recorded.put(id, content).await().unwrap();
        recorded.touch(id).await().unwrap();
        recorded.delete(id).await().unwrap();

        assertThat(order).containsExactly("block", "marker");
        assertThat(s3.keys()).isEmpty();

        order.clear();
        recorded.put(id, content).await().unwrap();
        recorded.touch(id).await().unwrap();
        failMarker[0] = true;

        assertThat(recorded.delete(id).await().isFailure()).isTrue();
        assertThat(s3.keys()).as("only the orphan marker is left").containsExactly(keyOf(id) + ".touched");
        assertThat(recorded.stat(id).await().unwrap().isEmpty()).as("an orphan marker is no block").isTrue();

        failMarker[0] = false;
        recorded.delete(id).await().unwrap();
        assertThat(s3.keys()).isEmpty();
    }

    private String keyOf(BlockId id) {
        return "scan-prefix/" + id.shardPrefix() + "/" + id.hexString();
    }
}
