package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import static org.pragmatica.lang.Unit.unit;
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
/// `MAGIC || VERSION || KEY_ID_LEN || KEY_ID` (everything before the nonce), with the block's own
/// id appended IN MEMORY ONLY (never persisted -- it's already the tier's lookup key, so writing it
/// again on disk would be redundant), is passed as AES-GCM associated data (AAD) on both encrypt
/// and decrypt. AAD is authenticated but not encrypted: editing any header byte, swapping in a
/// different (even validly-encrypted) key id, or copying one block's stored bytes onto a different
/// block's id all change what the receiver authenticates against, and decryption fails closed with
/// [EncryptionError.DecryptionFailed] -- none of those can silently decrypt under the wrong key or
/// surface as the wrong block's plaintext.
///
/// A delegate block with fewer than 4 bytes, or whose first 4 bytes aren't MAGIC, is treated as
/// **legacy plaintext** -- [#get] fails with [EncryptionError.LegacyPlaintextBlock] rather than
/// returning the raw bytes, per the owner ruling that a missing header must fail loudly. A block
/// that DOES start with MAGIC but is too short to carry the rest of the fixed header is
/// [EncryptionError.MalformedHeader] instead -- corruption, not a legacy write in progress toward
/// migration. A header whose VERSION byte doesn't match [#VERSION] fails with
/// [EncryptionError.UnsupportedVersion] rather than an opaque [EncryptionError.DecryptionFailed].
/// For the local-disk tier, [#wrapLocalDisk] additionally refuses to wrap AT BOOT if the tier's
/// directory already holds block files with no `.encryption-enabled` marker (see its Javadoc); the
/// REVERSE direction -- booting the same tier with `encrypted = false`, or no keyring at all, while
/// that marker is still present -- is refused by [#refuseIfEncryptedWithoutKeyring] instead of
/// silently returning the bare, unwrapped tier. The DHT tier has no local directory to scan, so the
/// FORWARD direction (enabling encryption over pre-existing DHT plaintext) still relies solely on
/// the per-read legacy/version/AAD checks above; but the REVERSE direction is now covered too --
/// `StorageFactory` (`aether/node`) mirrors [#MARKER_FILE_NAME] as a per-instance DHT key under the
/// tier's key prefix and refuses boot the same way, since this module has no dependency on the DHT
/// client to do so itself.
public final class EncryptingStorageTier implements StorageTier {
    static final byte[] MAGIC = {0x41, 0x45, 0x43, 0x31};

    static final byte VERSION = 1;
    static final int HEADER_FIXED_LEN = MAGIC.length + 1 + 2;  // magic + version + keyIdLen
    static final int NONCE_LEN = 12;
    /// Marker written the first time encryption is enabled over a tier, so a later boot can tell
    /// "empty/fresh, safe to enable" apart from "encryption was enabled here before, this IS the
    /// encrypted tier" without re-scanning. For a local-disk tier this is a FILE at the directory
    /// root ([#wrapLocalDisk], [#refuseIfEncryptedWithoutKeyring]); `StorageFactory` (`aether/node`)
    /// reuses this same literal as a DHT key (`dhtKeyPrefix + "/" + MARKER_FILE_NAME`) for the
    /// equivalent DHT-side marker, so the two surfaces share one name even though this module has no
    /// dependency on the DHT client. `public` for that reuse. Starts with `.` so the disk form can
    /// never collide with a two-hex-char shard directory name; the DHT form cannot collide with a
    /// real block key either -- both share the same `<prefix>/<suffix>` shape (`DhtStorageTier`,
    /// `aether/aether-storage` -- out of this module's dependency graph, named here only for
    /// context), but a real block key's suffix is always a 64-character lowercase-hex block id,
    /// which `.encryption-enabled` can never equal (wrong length, and `.`/`n`/`r`/`y` aren't hex
    /// digits).
    public static final String MARKER_FILE_NAME = ".encryption-enabled";

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
        return armLocalDisk(delegate, basePath, keyring).flatMap(armed -> armed.commitMarker()
                                                                               .map(_ -> armed.tier()));
    }

    /// #852: [#wrapLocalDisk]'s guard and its marker write, separated. The guard is a pure refusal;
    /// the write is a permanent side effect a later boot's reverse guard keys on. A caller assembling
    /// SEVERAL tiers under one boot decision arms each first and commits only once every guard has
    /// passed, so a refused boot stamps no directory. `pendingKeyId` is empty when the marker is
    /// already there (nothing to commit).
    public record ArmedLocalDisk(StorageTier tier, Path markerPath, Option<String> pendingKeyId) {
        public Result<Unit> commitMarker() {
            return pendingKeyId.map(keyId -> writeMarker(markerPath, keyId))
                               .or(Result.success(unit()));
        }

        /// #1170: the inverse of [#commitMarker], for a boot refused AFTER the commit (the DHT marker
        /// check cannot run before cluster formation). BER: compensate by inverse, and only for what
        /// this boot did. A marker that was already present (`pendingKeyId` empty) belongs to an
        /// earlier boot and stays. A directory that holds any block beside the marker stays stamped:
        /// the marker is what stops a plain boot handing back framed ciphertext as content, so it is
        /// never removed from a directory that may hold ciphertext. The guarantee earned is "a refused
        /// boot over a still-empty directory leaves the operator's back-out to `encrypted = false`
        /// bootable". BOUND: the empty-directory check and the delete are not atomic against a
        /// concurrent block write; the boot being rolled back is already failing, so the window is
        /// the interval before its node is stopped.
        public Result<Unit> rollbackMarker() {
            return pendingKeyId.map(_ -> removeMarkerIfDirectoryEmpty(markerPath))
                               .or(Result.success(unit()));
        }
    }

    private static Result<Unit> removeMarkerIfDirectoryEmpty(Path markerPath) {
        return FileOps.walk(markerPath.getParent(),
                            EncryptingStorageTier::isBlockFile)
                      .flatMap(existing -> deleteMarkerUnlessBlocks(markerPath, existing));
    }

    private static Result<Unit> deleteMarkerUnlessBlocks(Path markerPath, List<Path> blocks) {
        return blocks.isEmpty()
               ? FileOps.deleteIfExists(markerPath).mapToUnit()
               : Result.success(unit());
    }

    /// The guard half of [#wrapLocalDisk]: refuses over pre-existing plaintext, otherwise returns the
    /// wrapped tier with its marker write still pending — see [ArmedLocalDisk].
    public static Result<ArmedLocalDisk> armLocalDisk(LocalDiskTier delegate,
                                                      Path basePath,
                                                      EncryptionKeyring keyring) {
        var markerPath = basePath.resolve(MARKER_FILE_NAME);
        var tier = wrap(delegate, keyring);

        if (FileOps.exists(markerPath)) {
            return Result.success(new ArmedLocalDisk(tier, markerPath, Option.none()));
        }

        return FileOps.walk(basePath, EncryptingStorageTier::isBlockFile).flatMap(existing -> existing.isEmpty()
                                                                                              ? Result.success(new ArmedLocalDisk(tier,
                                                                                                                                  markerPath,
                                                                                                                                  Option.some(keyring.activeKeyId())))
                                                                                              : new EncryptionError.EnablingOverExistingPlaintext(basePath.toString(),
                                                                                                                                                  existing.size()).result());
    }

    /// #253 BLOCKING #3 (2026-09-04 ruling): the reverse direction of [#wrapLocalDisk]'s guard --
    /// refuses booting `instanceName`'s local-disk directory as a PLAIN (unwrapped) tier when it
    /// already carries the `.encryption-enabled` marker from a prior encrypted boot. Called from the
    /// no-keyring branch of tier assembly, where nothing else would ever consult the marker: an
    /// absent marker means the directory was never encrypted and this is a legitimate plain tier;
    /// its presence means "blocks here are ciphertext" and a plain tier over them would silently
    /// hand back framed `AEC1...` bytes as content, or admit real plaintext during the disabled
    /// window that a later re-enable's [#wrapLocalDisk] guard cannot distinguish from the existing
    /// ciphertext (that guard short-circuits the instant the marker exists). A propagated
    /// [FileOps#readBytes] failure (marker present but unreadable) surfaces as-is rather than being
    /// folded into [EncryptionError.EncryptedTierRequiresKeyring] -- the operator needs to know the
    /// marker couldn't be read, not a fabricated key id.
    public static Result<Unit> refuseIfEncryptedWithoutKeyring(Path basePath, String instanceName) {
        var markerPath = basePath.resolve(MARKER_FILE_NAME);

        if (!FileOps.exists(markerPath)) {
            return Result.success(unit());
        }

        return FileOps.readBytes(markerPath).flatMap(bytes -> new EncryptionError.EncryptedTierRequiresKeyring(instanceName,
                                                                                                               bytes.length == 0
                                                                                                               ? "<unknown -- marker predates key-id persistence>"
                                                                                                               : new String(bytes,
                                                                                                                            StandardCharsets.UTF_8)).result());
    }

    private static boolean isBlockFile(Path path) {
        return FileOps.isRegularFile(path) && !isBookkeeping(path.getFileName().toString());
    }

    private static boolean isBookkeeping(String fileName) {
        return fileName.equals(MARKER_FILE_NAME) || fileName.equals(MountClaim.CLAIM_FILE);
    }

    /// #1190: the marker is a CREATE whose presence a later boot's fail-closed guard keys on, so it
    /// is forced to the device -- file and directory entry -- before [ArmedLocalDisk#commitMarker]
    /// returns. A crash after the return can no longer leave ciphertext blocks beside an absent
    /// marker, which a plain boot would have mounted a plain tier over. The one remaining window
    /// is a crash BEFORE the return: the marker may then be absent or torn, and a torn marker is
    /// safe (presence is the signal; [#refuseIfEncryptedWithoutKeyring] handles zero bytes) while
    /// an absent one is fail-safe -- no block was written yet: the guard only arms a marker write
    /// over an EMPTY directory, and `StorageFactory.createAll` (`aether/node`) hands out the tiers
    /// only after every marker has committed. No sibling-and-rename here: a temp file orphaned at
    /// the directory root
    /// would be counted by [#isBlockFile] as a plaintext block and refuse the next enable.
    private static Result<Unit> writeMarker(Path markerPath, String activeKeyId) {
        return FileOps.writeBytesDurable(markerPath, activeKeyId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return delegate.get(id)
                       .flatMap(maybeFramed -> maybeFramed.fold(() -> Promise.success(Option.<byte[]> none()),
                                                                framed -> decryptFramed(id, framed)));
    }

    private Promise<Option<byte[]>> decryptFramed(BlockId id, byte[] framed) {
        return decrypt(id.hexString(), framed).fold(Promise::failure,
                                                    plaintext -> Promise.success(Option.some(plaintext)));
    }

    /// `subject` is what the frame is bound to in its AAD: a block's hex id, or [#anchorSubject].
    private Result<byte[]> decrypt(String subject, byte[] framed) {
        return parseHeader(subject, framed).flatMap(header -> keyring.byKeyId(header.keyId())
                                                                     .flatMap(encryptor -> encryptor.decrypt(header.ciphertext(),
                                                                                                             encryptionParams("AES/GCM/NoPadding",
                                                                                                                              header.nonce(),
                                                                                                                              header.keyId()),
                                                                                                             header.aad())));
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return frame(id.hexString(), content).fold(Promise::failure, framed -> delegate.put(id, framed));
    }

    /// The value is encrypted as a block's content is, under the active key, and bound in its AAD to the anchor's name
    /// and version: a stored value copied onto another anchor or another version fails to decrypt. The delegate sees
    /// only ciphertext; two writes of one version differ in their bytes (fresh nonce) and decrypt to the same value.
    @Override
    public Promise<Unit> putAnchor(AnchorName name, long version, byte[] value) {
        return frame(anchorSubject(name, version), value).fold(Promise::failure,
                                                               framed -> delegate.putAnchor(name, version, framed));
    }

    /// Decrypted under the key id its frame names, so an anchor written before a key rotation reads after it, as a
    /// block does. A value that does not decrypt (damaged, or copied from another anchor or version) is
    /// [AnchorError.AnchorCorrupt]: it fails closed and reaches the operator like a damaged frame.
    @Override
    public Promise<Option<Anchor>> latestAnchor(AnchorName name) {
        return delegate.latestAnchor(name)
                       .flatMap(stored -> stored.fold(() -> Promise.success(Option.<Anchor> none()),
                                                      anchor -> decrypt(anchorSubject(name,
                                                                                      anchor.version()),
                                                                        anchor.value()).map(value -> Option.some(Anchor.anchor(anchor.version(),
                                                                                                                               value)))
                                                                       .mapError(cause -> AnchorError.AnchorCorrupt.anchorCorrupt(level(),
                                                                                                                                  name,
                                                                                                                                  anchor.version(),
                                                                                                                                  cause.message()))
                                                                       .async()));
    }

    @Override
    public Promise<Unit> pruneAnchors(AnchorName name, long below, int scanLimit) {
        return delegate.pruneAnchors(name, below, scanLimit);
    }

    /// Never a block's subject: a block's is 64 hex characters, with no space.
    private static String anchorSubject(AnchorName name, long version) {
        return "anchor " + name.value() + " " + version;
    }

    private Result<byte[]> frame(String subject, byte[] content) {
        var keyIdBytes = keyring.activeKeyId().getBytes(StandardCharsets.UTF_8);
        var header = buildHeaderPrefix(keyIdBytes);
        var aad = concat(header, subject.getBytes(StandardCharsets.UTF_8));

        return keyring.active()
                      .encrypt(content, aad)
                      .map(encrypted -> concat(header,
                                               encrypted.params().iv(),
                                               encrypted.ciphertext()));
    }

    private static byte[] buildHeaderPrefix(byte[] keyIdBytes) {
        var header = new byte[HEADER_FIXED_LEN + keyIdBytes.length];

        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = VERSION;
        header[MAGIC.length + 1] = (byte)((keyIdBytes.length >> 8) & 0xFF);
        header[MAGIC.length + 2] = (byte)(keyIdBytes.length & 0xFF);
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

    /// #253 SHOULD-FIX #4: folds the block id into the in-memory AAD without persisting it a second
    /// time -- `id` is already the tier's lookup key on disk, so writing it again into the header
    /// would be redundant, but leaving it OUT of the AAD is exactly what let a copy of one block's
    /// stored bytes authenticate under a different block's id (swap two blocks in the backing tier,
    /// `get` on either used to return the OTHER block's plaintext).
    private static byte[] concat(byte[] a, byte[] b) {
        var out = new byte[a.length + b.length];

        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);

        return out;
    }

    private record ParsedHeader(String keyId, byte[] nonce, byte[] ciphertext, byte[] aad) {}

    private static Result<ParsedHeader> parseHeader(String subject, byte[] framed) {
        if (framed.length < MAGIC.length || !matchesMagic(framed)) {
            return new EncryptionError.LegacyPlaintextBlock(subject).result();
        }

        if (framed.length < HEADER_FIXED_LEN) {
            return new EncryptionError.MalformedHeader(subject, "truncated before version/key-id-length fields").result();
        }

        if (framed[MAGIC.length] != VERSION) {
            return new EncryptionError.UnsupportedVersion(subject, framed[MAGIC.length] & 0xFF, VERSION & 0xFF).result();
        }

        var keyIdLen = ((framed[MAGIC.length + 1] & 0xFF) << 8) | (framed[MAGIC.length + 2] & 0xFF);
        var keyIdStart = HEADER_FIXED_LEN;
        var nonceStart = keyIdStart + keyIdLen;
        var ciphertextStart = nonceStart + NONCE_LEN;

        if (framed.length < ciphertextStart) {
            return new EncryptionError.MalformedHeader(subject, "truncated before nonce/ciphertext").result();
        }

        var keyId = new String(framed, keyIdStart, keyIdLen, StandardCharsets.UTF_8);
        var nonce = Arrays.copyOfRange(framed, nonceStart, ciphertextStart);
        var ciphertext = Arrays.copyOfRange(framed, ciphertextStart, framed.length);
        var aad = concat(Arrays.copyOfRange(framed, 0, nonceStart), subject.getBytes(StandardCharsets.UTF_8));

        return Result.success(new ParsedHeader(keyId, nonce, ciphertext, aad));
    }

    private static boolean matchesMagic(byte[] framed) {
        return framed[0] == MAGIC[0]
               && framed[1] == MAGIC[1]
               && framed[2] == MAGIC[2]
               && framed[3] == MAGIC[3];
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

    @Override
    public boolean isDurable() {
        return delegate.isDurable();
    }

    @Override
    public Unit release() {
        return delegate.release();
    }

    @Override
    public Unit reportTo(DegradeReporter reporter) {
        return delegate.reportTo(reporter);
    }
}
