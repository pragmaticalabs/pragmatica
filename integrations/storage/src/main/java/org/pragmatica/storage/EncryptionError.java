package org.pragmatica.storage;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;


/// Encryption error hierarchy.
public sealed interface EncryptionError extends Cause {
    record EncryptionFailed(Throwable cause) implements EncryptionError {
        @Override
        public String message() {
            return "Encryption failed: " + Causes.fromThrowable(cause).message();
        }
    }

    record DecryptionFailed(Throwable cause) implements EncryptionError {
        @Override
        public String message() {
            return "Decryption failed: " + Causes.fromThrowable(cause).message();
        }
    }

    record InvalidKeyLength(int actual, int expected) implements EncryptionError {
        @Override
        public String message() {
            return "Invalid key length: " + actual + " bytes, expected " + expected;
        }
    }

    /// #253: a block was read from an encrypted tier but carries no encryption header -- the tier
    /// was enabled over pre-existing plaintext data (see [EncryptingStorageTier]'s boot-time guard,
    /// which refuses this for local disk; a DHT tier has no directory to scan and relies on this
    /// per-read check instead). Recovery: run the data-migration path (tracked separately -- #253
    /// ships detection, not migration) or restore the tier from a snapshot taken before encryption
    /// was enabled.
    record LegacyPlaintextBlock(String blockId) implements EncryptionError {
        @Override
        public String message() {
            return "Block " + blockId + " has no encryption header: tier was enabled over pre-existing plaintext data";
        }
    }

    /// A block's header names a key id absent from the node's configured keyring -- e.g. the key
    /// was removed from `[storage.encryption.keys]` while blocks encrypted under it still exist.
    /// Recovery: restore the missing key id to the keyring (it may re-enter the ring for reads
    /// without becoming the active key again).
    record UnknownKeyId(String keyId) implements EncryptionError {
        @Override
        public String message() {
            return "Unknown encryption key id: " + keyId + " (not present in the configured keyring)";
        }
    }

    /// A stored block's bytes are shorter than the minimum framed-header size -- corruption, or a
    /// non-block file placed under the tier's directory by something other than this tier.
    record MalformedHeader(String blockId, String reason) implements EncryptionError {
        @Override
        public String message() {
            return "Block " + blockId + " has a malformed encryption header: " + reason;
        }
    }

    /// #253 boot guard: an operator enabled encryption (`encrypted = true`) on a local-disk tier
    /// whose directory already holds block files and no `.encryption-enabled` marker -- i.e. it may
    /// hold plaintext blocks written before encryption was turned on. Boot refuses rather than
    /// silently starting to write ciphertext alongside unreadable-as-ciphertext plaintext. Recovery:
    /// migrate the existing directory's contents out-of-band (tracked separately -- #253 ships
    /// detection, not migration) then retry, or point `disk_path` at a fresh empty directory.
    record EnablingOverExistingPlaintext(String path, long existingBlockCount) implements EncryptionError {
        @Override
        public String message() {
            return "Refusing to enable encryption at " + path + ": " + existingBlockCount
                   + " existing block(s) with no encryption marker -- tier may hold plaintext data";
        }
    }
}
