package org.pragmatica.storage;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// A tier that does not provide [StorageTier#scan], [StorageTier#stat] or [StorageTier#touch] (the in-memory DHT tier
/// today) refuses them, never answers "empty" or "absent"; an encrypting tier passes them to the tier it wraps.
class StorageTierScanStatTouchDefaultsTest {
    private static final BlockId ID = BlockId.blockId("defaults".getBytes(StandardCharsets.UTF_8)).unwrap();

    /// Only the abstract operations; every A2b method is the interface default.
    private static final class BareTier implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(false);
        }

        @Override
        public TierLevel level() {
            return TierLevel.REMOTE;
        }

        @Override
        public long usedBytes() {
            return 0;
        }

        @Override
        public long maxBytes() {
            return 0;
        }
    }

    @Test
    void theDefaults_refuseWithOperationUnsupported() {
        var tier = new BareTier();

        assertThat(tier.stat(ID).await().fold(cause -> cause, _ -> null)).isInstanceOf(StorageError.OperationUnsupported.class);
        assertThat(tier.scan(KeyShard.all(), Option.none(), 10).await().fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.OperationUnsupported.class);
        assertThat(tier.touch(ID).await().fold(cause -> cause, _ -> null)).isInstanceOf(StorageError.OperationUnsupported.class);
    }

    /// A proxy answering only the interface's default methods: the instance double every other test builds lacks none.
    @Test
    void theInstanceDefault_refusesEnsureHeld() {
        var instance = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                                new Class<?>[] {StorageInstance.class},
                                                                (proxy, method, args) -> InvocationHandler.invokeDefault(proxy,
                                                                                                                         method,
                                                                                                                         args));

        assertThat(instance.ensureHeld(ID, TierLevel.REMOTE).await().fold(cause -> cause, _ -> null))
            .isInstanceOf(StorageError.OperationUnsupported.class);
    }

    @Test
    void anEncryptingTier_passesThemToTheTierItWraps() {
        var key = new byte[32];

        new SecureRandom().nextBytes(key);

        var keyring = EncryptionKeyring.encryptionKeyring(Map.of("k", BlockEncryptor.aesGcm(key, "k").unwrap()), "k").unwrap();
        var clock = new AtomicLong(1_000L);
        var inner = MemoryTier.memoryTier(1 << 20, TierLevel.LOCAL_DISK, clock::get);
        var tier = EncryptingStorageTier.wrap(inner, keyring);
        var content = "encrypted at rest".getBytes(StandardCharsets.UTF_8);
        var id = BlockId.blockId(content).unwrap();

        tier.put(id, content).await().unwrap();
        clock.set(2_000L);

        assertThat(tier.touch(id).await().unwrap()).isTrue();
        assertThat(tier.stat(id).await().unwrap()).isEqualTo(inner.stat(id).await().unwrap());
        assertThat(tier.stat(id).await().unwrap().unwrap().writtenAtMillis()).isEqualTo(2_000L);
        assertThat(tier.stat(id).await().unwrap().unwrap().size()).as("the stored frame").isGreaterThan(content.length);
        assertThat(tier.scan(KeyShard.all(), Option.none(), 10).await().unwrap().items()).isEqualTo(List.of(inner.stat(id).await().unwrap().unwrap()));
    }
}
