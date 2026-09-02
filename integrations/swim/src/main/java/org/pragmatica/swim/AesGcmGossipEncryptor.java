package org.pragmatica.swim;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// AES-256-GCM gossip encryptor with multi-key accept support for rotation and day-boundary
/// overlap.
///
/// Encrypts alway with the single `current` key; decrypts with any key in the accepted set
/// (resolved by the wire keyId). The accepted set always contains the current key and may
/// contain additional keys (previous-day, next-day) so datagrams encrypted under a neighbouring
/// epoch's key still decrypt — see #256 (UTC-midnight gossip-key rollover lockout).
///
/// Wire format: [4-byte keyId (big-endian)][12-byte nonce][ciphertext + 16-byte GCM tag]
public final class AesGcmGossipEncryptor implements GossipEncryptor {
    private static final int KEY_SIZE = 32;
    private static final int NONCE_SIZE = 12;
    private static final int KEY_ID_SIZE = 4;
    private static final int GCM_TAG_BITS = 128;
    private static final int HEADER_SIZE = KEY_ID_SIZE + NONCE_SIZE;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final SecretKeySpec currentKey;
    private final int currentKeyId;
    private final Map<Integer, SecretKeySpec> acceptedKeys;
    private final SecureRandom secureRandom;

    private AesGcmGossipEncryptor(SecretKeySpec currentKey,
                                  int currentKeyId,
                                  Map<Integer, SecretKeySpec> acceptedKeys) {
        this.currentKey = currentKey;
        this.currentKeyId = currentKeyId;
        this.acceptedKeys = acceptedKeys;
        this.secureRandom = new SecureRandom();
    }

    /// Factory creating an encryptor with a single key.
    public static Result<GossipEncryptor> aesGcmGossipEncryptor(byte[] currentKey, int currentKeyId) {
        return aesGcmGossipEncryptor(currentKey, currentKeyId, List.of());
    }

    /// Factory creating an encryptor with current and previous keys for rotation.
    public static Result<GossipEncryptor> aesGcmGossipEncryptor(byte[] currentKey,
                                                                int currentKeyId,
                                                                byte[] previousKey,
                                                                int previousKeyId) {
        return aesGcmGossipEncryptor(currentKey, currentKeyId, List.of(new AcceptedKey(previousKeyId, previousKey)));
    }

    /// Factory creating an encryptor that encrypts with `current` and accepts `current` plus any
    /// number of additional keys for decryption. Used to widen the accept window across the
    /// UTC-midnight day-rollover boundary (#256): supplying the previous-day and next-day keys as
    /// additional accepted keys lets a node decrypt datagrams from peers booted on the adjacent
    /// day without changing what it encrypts with (wire-compatible, no rolling-upgrade break).
    public static Result<GossipEncryptor> aesGcmGossipEncryptor(byte[] currentKey,
                                                                int currentKeyId,
                                                                List<AcceptedKey> additionalKeys) {
        return validateKey(currentKey).flatMap(cur -> buildAcceptedKeys(cur, currentKeyId, additionalKeys).map(accepted -> new AesGcmGossipEncryptor(cur,
                                                                                                                                                     currentKeyId,
                                                                                                                                                     accepted)));
    }

    /// An additional key accepted for decryption, identified by its wire keyId.
    public record AcceptedKey(int keyId, byte[] key) {}

    private static Result<Map<Integer, SecretKeySpec>> buildAcceptedKeys(SecretKeySpec current,
                                                                         int currentKeyId,
                                                                         List<AcceptedKey> additionalKeys) {
        return Result.allOf(additionalKeys.stream().map(AesGcmGossipEncryptor::validateAccepted).toList()).map(validated -> collectAcceptedKeys(current,
                                                                                                                                                currentKeyId,
                                                                                                                                                validated));
    }

    private static Map<Integer, SecretKeySpec> collectAcceptedKeys(SecretKeySpec current,
                                                                   int currentKeyId,
                                                                   List<AcceptedSpec> additionalKeys) {
        var map = new LinkedHashMap<Integer, SecretKeySpec>();

        map.put(currentKeyId, current);
        additionalKeys.forEach(spec -> map.putIfAbsent(spec.keyId(), spec.key()));

        return Map.copyOf(map);
    }

    private static Result<AcceptedSpec> validateAccepted(AcceptedKey accepted) {
        return validateKey(accepted.key()).map(key -> new AcceptedSpec(accepted.keyId(), key));
    }

    private record AcceptedSpec(int keyId, SecretKeySpec key) {}

    @Override
    public Result<byte[]> encrypt(byte[] plaintext) {
        return Result.lift(GossipEncryptionError.EncryptionFailed::new, () -> doEncrypt(plaintext));
    }

    @Override
    public Result<byte[]> decrypt(byte[] ciphertext) {
        return parseHeader(ciphertext).flatMap(header -> resolveKey(header.keyId()).flatMap(key -> decryptPayload(key,
                                                                                                                  header)));
    }

    @SuppressWarnings("JBCT-EX-01")  // Adapter boundary: JCE Cipher throwing supplier for Result.lift
    private byte[] doEncrypt(byte[] plaintext) throws Exception {
        var nonce = new byte[NONCE_SIZE];

        secureRandom.nextBytes(nonce);
        var cipher = Cipher.getInstance(ALGORITHM);

        cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        var encrypted = cipher.doFinal(plaintext);

        return ByteBuffer.allocate(HEADER_SIZE + encrypted.length)
                         .putInt(currentKeyId)
                         .put(nonce)
                         .put(encrypted)
                         .array();
    }

    private static Result<ParsedHeader> parseHeader(byte[] ciphertext) {
        return Result.lift(GossipEncryptionError.DecryptionFailed::new, () -> doParse(ciphertext));
    }

    private static ParsedHeader doParse(byte[] ciphertext) {
        var buffer = ByteBuffer.wrap(ciphertext);
        var keyId = buffer.getInt();
        var nonce = new byte[NONCE_SIZE];

        buffer.get(nonce);
        var encrypted = new byte[buffer.remaining()];

        buffer.get(encrypted);

        return new ParsedHeader(keyId, nonce, encrypted);
    }

    private Result<byte[]> decryptPayload(SecretKeySpec key, ParsedHeader header) {
        return Result.lift(GossipEncryptionError.DecryptionFailed::new, () -> doDecryptPayload(key, header));
    }

    @SuppressWarnings("JBCT-EX-01")  // Adapter boundary: JCE Cipher throwing supplier for Result.lift
    private static byte[] doDecryptPayload(SecretKeySpec key, ParsedHeader header) throws Exception {
        var cipher = Cipher.getInstance(ALGORITHM);

        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, header.nonce()));

        return cipher.doFinal(header.encrypted());
    }

    private Result<SecretKeySpec> resolveKey(int keyId) {
        return Option.option(acceptedKeys.get(keyId)).toResult(new GossipEncryptionError.UnknownKeyId(keyId));
    }

    private static Result<SecretKeySpec> validateKey(byte[] key) {
        if (key.length != KEY_SIZE) {
            return new GossipEncryptionError.InvalidKeySize(key.length).result();
        }

        return Result.success(new SecretKeySpec(key, "AES"));
    }

    private record ParsedHeader(int keyId, byte[] nonce, byte[] encrypted) {}
}
