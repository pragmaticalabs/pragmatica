package org.pragmatica.storage;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

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
    @Test
    void scan_listsOnWhenTheProviderEndsAPageEarly() {
        var ids = IntStream.range(0, 6)
                           .mapToObj(i -> store(bytes("short page " + i)))
                           .toList();

        s3.listPageCap.set(1);

        assertThat(scanAll(KeyShard.all(), 4)).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(tier.scan(KeyShard.all(), Option.none(), 4).await().unwrap().items()).hasSize(4);
    }

    /// Objects under the prefix that are not blocks -- a boot probe, the encryption marker -- are never listed.
    @Test
    void scan_skipsObjectsThatAreNotBlocks() {
        var id = store(bytes("the block"));
        var remote = (RemoteTier) tier;

        remote.writeMarker(".aether-encryption", bytes("marker")).await().unwrap();
        s3.putObject("scan-prefix/" + id.hexString().substring(0, 2) + "/stray", bytes("stray"), "text/plain").await().unwrap();

        assertThat(scanAll(KeyShard.all(), 10)).containsExactly(id);
    }

    @Test
    void touch_sendsNoBytes() {
        var id = store(bytes("touched in place"));
        var putsBefore = s3.puts.get();

        tier.touch(id).await().unwrap();

        assertThat(s3.puts.get()).isEqualTo(putsBefore);
    }
}
