package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import static org.pragmatica.storage.EncryptionParams.encryptionParams;


/// #253: encrypts/decrypts block content around a delegate [StorageTier], transparent to callers.
///
/// **Wire format** (what [#put] stores and [#get] parses back out of the delegate tier):
///
/// ```text
/// offset  size       field
/// 0       4          MAGIC = {0x41,0x45,0x43,0x31} ("AEC1")
/// 4       1          VERSION = 1
/// 5       2          KEY_ID_LEN, unsigned big-endian short
/// 7       KEY_ID_LEN KEY_ID, UTF-8
/// 7+len   12         NONCE (the AES-GCM IV -- 96 bits, fresh random per block per
///                    [AesGcmBlockEncryptor])
/// 19+len  remainder  CIPHERTEXT, with the 16-byte GCM authentication tag appended (JCE's
///                    standard `Cipher.doFinal` output for GCM)
/// ```
///
/// `MAGIC || VERSION || KEY_ID_LEN || KEY_ID` (everything before the nonce) is passed as
/// AES-GCM associated data (AAD) on both encrypt and decrypt. AAD is authenticated but not
/// encrypted: editing any byte of it, or swapping in a different (even validly-encrypted) key
/// id, changes what the receiver authenticates against and decryption fails closed with
/// [EncryptionError.DecryptionFailed] -- a header edit or key-id swap cannot silently make a
/// block decrypt under the wrong key.
///
/// A delegate block with fewer than 4 bytes, or whose first 4 bytes aren't MAGIC, is treated as
/// **legacy plaintext** -- [#get] fails with [EncryptionError.LegacyPlaintextBlock] rather than
/// returning the raw bytes, per the owner ruling that a missing header must fail loudly. For the
/// local-disk tier, [#wrapLocalDisk] additionally refuses to wrap AT BOOT if the tier's directory
/// already holds block files with no `.encryption-enabled` marker (see its Javadoc); the DHT tier
/// has no local directory to scan and relies solely on this per-read check.
public final class EncryptingStorageTier implements StorageTier {
    static final byte[] MAGIC = {0x41, 0x45, 0x43, 0x31};
    static final byte VERSION = 1;
    static final int HEADER_FIXED_LEN = MAGIC.length + 1 + 2; // magic + version + keyIdLen
    static final int NONCE_LEN = 12;
    /// Marker file written at the root of a local-disk tier's directory the first time encryption
    /// is enabled over it, so a later boot can tell "empty/fresh directory, safe to enable" apart
    /// from "encryption was enabled here before, this IS the encrypted tier" without re-scanning.
    /// Filename starts with `.` so it can never collide with a two-hex-char shard directory name.
    static final String MARKER_FILE_NAME = ".encryption-enabled";

    private final StorageTier delegate;
    private final EncryptionKeyring keyring;

    private EncryptingStorageTier(StorageTier delegate, EncryptionKeyring keyring) {
        this.delegate = delegate;
        this.keyring = keyring;
    }

    /// Wrap a tier with no local directory to scan (the DHT tier). No boot-time legacy guard is
    /// possible here -- see the class Javadoc's coverage note; pre-existing plaintext blocks are
    /// only caught reactively, per block, on read.
    public static StorageTier wrap(StorageTier delegate, EncryptionKeyring keyring) {
        return new EncryptingStorageTier(delegate, keyring);
    }

    /// Wrap a [LocalDiskTier], first refusing (rather than silently wrapping) if `basePath` already
    /// holds block files with no `.encryption-enabled` marker -- i.e. it may hold plaintext blocks
    /// written before encryption was turned on for this instance. On first-ever enable (empty
    /// directory, or a directory holding only the marker) the marker is written immediately, at
    /// boot, rather than deferred to the first encrypted write -- this is a deliberate choice: it
    /// keeps the hot write path free of marker-file coordination and avoids an ambiguous on-disk
    /// state if the node crashes after "enabling" but before any block is ever written.
    public static Result<StorageTier> wrapLocalDisk(LocalDiskTier delegate, Path basePath, EncryptionKeyring keyring) {
        var markerPath = basePath.resolve(MARKER_FILE_NAME);

        if (FileOps.exists(markerPath)) {
            return Result.success(wrap(delegate, keyring));
        }

        return FileOps.walk(basePath, EncryptingStorageTier::isBlockFile)
                      .flatMap(existing -> existing.isEmpty()
                                            ? writeMarker(markerPath).map(_ -> wrap(delegate, keyring))
                                            : new EncryptionError.EnablingOverExistingPlaintext(basePath.toString(),
                                                                                                existing.size()).result());
    }

    private static boolean isBlockFile(Path path) {
        return FileOps.isRegularFile(path) && !path.getFileName().toString().equals(MARKER_FILE_NAME);
    }

    private static Result<Unit> writeMarker(Path markerPath) {
        return FileOps.writeBytes(markerPath, new byte[0]);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return delegate.get(id).flatMap(maybeFramed -> maybeFramed.fold(() -> Promise.success(Option.<byte[]> none()),
                                                                         framed -> decryptFramed(id, framed)));
    }

    private Promise<Option<byte[]>> decryptFramed(BlockId id, byte[] framed) {
        return parseHeader(id, framed).flatMap(header -> keyring.byKeyId(header.keyId())
                                                                 .flatMap(encryptor -> encryptor.decrypt(header.ciphertext(),
                                                                                                         encryptionParams("AES/GCM/NoPadding",
                                                                                                                          header.nonce(),
                                                                                                                          header.keyId()),
                                                                                                         header.aad())))
                                       .fold(Promise::failure, plaintext -> Promise.success(Option.some(plaintext)));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return frame(content).fold(Promise::failure, framed -> delegate.put(id, framed));
    }

    private Result<byte[]> frame(byte[] content) {
        var keyIdBytes = keyring.activeKeyId().getBytes(StandardCharsets.UTF_8);
        var header = buildHeaderPrefix(keyIdBytes);

        return keyring.active()
                      .encrypt(content, header)
                      .map(encrypted -> concat(header, encrypted.params().iv(), encrypted.ciphertext()));
    }

    private static byte[] buildHeaderPrefix(byte[] keyIdBytes) {
        var header = new byte[HEADER_FIXED_LEN + keyIdBytes.length];

        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = VERSION;
        header[MAGIC.length + 1] = (byte) ((keyIdBytes.length >> 8) & 0xFF);
        header[MAGIC.length + 2] = (byte) (keyIdBytes.length & 0xFF);
        System.arraycopy(keyIdBytes, 0, header, HEADER_FIXED_LEN, keyIdBytes.length);

        return header;
    }

    private static byte[] concat(byte[] header, byte[] nonce, byte[] ciphertext) {
        var out = new byte[header.length + nonce.length + ciphertext.length];

        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(nonce, 0, out, header.length, nonce.length);
        System.arraycopy(ciphertext, 0, out, header.length + nonce.length, ciphertext.length);

        return out;
    }

    private record ParsedHeader(String keyId, byte[] nonce, byte[] ciphertext, byte[] aad) {}

    private static Result<ParsedHeader> parseHeader(BlockId id, byte[] framed) {
        if (framed.length < HEADER_FIXED_LEN || !matchesMagic(framed)) {
            return new EncryptionError.LegacyPlaintextBlock(id.hexString()).result();
        }

        var keyIdLen = ((framed[MAGIC.length + 1] & 0xFF) << 8) | (framed[MAGIC.length + 2] & 0xFF);
        var keyIdStart = HEADER_FIXED_LEN;
        var nonceStart = keyIdStart + keyIdLen;
        var ciphertextStart = nonceStart + NONCE_LEN;

        if (framed.length < ciphertextStart) {
            return new EncryptionError.MalformedHeader(id.hexString(), "truncated before nonce/ciphertext").result();
        }

        var keyId = new String(framed, keyIdStart, keyIdLen, StandardCharsets.UTF_8);
        var nonce = Arrays.copyOfRange(framed, nonceStart, ciphertextStart);
        var ciphertext = Arrays.copyOfRange(framed, ciphertextStart, framed.length);
        var aad = Arrays.copyOfRange(framed, 0, nonceStart);

        return Result.success(new ParsedHeader(keyId, nonce, ciphertext, aad));
    }

    private static boolean matchesMagic(byte[] framed) {
        return framed[0] == MAGIC[0] && framed[1] == MAGIC[1] && framed[2] == MAGIC[2] && framed[3] == MAGIC[3];
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return delegate.delete(id);
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return delegate.exists(id);
    }

    @Override
    public TierLevel level() {
        return delegate.level();
    }

    @Override
    public long usedBytes() {
        return delegate.usedBytes();
    }

    @Override
    public long maxBytes() {
        return delegate.maxBytes();
    }

    @Override
    public boolean isShared() {
        return delegate.isShared();
    }
}
