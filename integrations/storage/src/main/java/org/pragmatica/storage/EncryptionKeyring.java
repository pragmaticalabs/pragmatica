package org.pragmatica.storage;

import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// #253: a resolved set of block encryptors plus which one new writes use. Construction is the
/// trust boundary -- by the time an `EncryptionKeyring` exists, every entry's secret has already
/// been resolved and validated (see `StorageEncryption.resolveKeyring` in `aether/node`, the only
/// production caller); this type itself never touches `SecretsProvider` or config parsing, so it
/// can be unit-tested with in-memory keys.
///
/// @param keys      every key in the ring, by key id -- used to decrypt existing blocks
///                  regardless of which key is currently active (key rotation keeps old keys
///                  readable without re-encrypting existing data)
/// @param activeKeyId the key id used to encrypt new writes; MUST be a key present in `keys`
public record EncryptionKeyring(Map<String, BlockEncryptor> keys, String activeKeyId) {
    public EncryptionKeyring {
        keys = Map.copyOf(keys);
    }

    public static Result<EncryptionKeyring> encryptionKeyring(Map<String, BlockEncryptor> keys, String activeKeyId) {
        return keys.containsKey(activeKeyId)
               ? Result.success(new EncryptionKeyring(keys, activeKeyId))
               : new EncryptionError.UnknownKeyId(activeKeyId).result();
    }

    /// The encryptor for the active key id. Safe to call unconditionally: the factory refuses to
    /// construct a keyring whose active id isn't present in `keys`.
    public BlockEncryptor active() {
        return keys.get(activeKeyId);
    }

    /// Look up the encryptor for a specific key id (from a stored block's header) -- may be a
    /// retired, non-active key still needed to decrypt blocks written before rotation.
    public Result<BlockEncryptor> byKeyId(String keyId) {
        return Option.option(keys.get(keyId)).toResult(new EncryptionError.UnknownKeyId(keyId));
    }
}
