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

    /// #253 SHOULD-FIX #7: every other resolution/decryption failure in this hierarchy names the key
    /// id involved; this one didn't, though the caller (`AesGcmBlockEncryptor.aesGcmBlockEncryptor`)
    /// always has it in scope.
    record InvalidKeyLength(String keyId, int actual, int expected) implements EncryptionError {
        @Override
        public String message() {
            return "Invalid key length for key id '" + keyId + "': " + actual + " bytes, expected " + expected;
        }
    }

    /// #253 SHOULD-FIX #5: the framed header's VERSION byte doesn't match this build's
    /// [EncryptingStorageTier#VERSION]. Written since the first cut of the wire format but never
    /// read until this fix. Recovery: run the build that wrote this version, or migrate the block
    /// (tracked separately -- #253 ships detection, not migration).
    record UnsupportedVersion(String blockId, int actualVersion, int expectedVersion) implements EncryptionError {
        @Override
        public String message() {
            return "Block " + blockId
                 + " has encryption format version " + actualVersion
                 + ", this build only supports version " + expectedVersion;
        }
    }

    /// #253 BLOCKING #3 (2026-09-04 ruling): `instanceName`'s local-disk directory carries the
    /// `.encryption-enabled` marker -- it holds, or held, ciphertext written under `keyId` -- but
    /// this boot supplies no keyring for it (`encrypted = false`, or `[storage.encryption]` absent
    /// entirely). The marker means "blocks here are encrypted"; returning the bare, unwrapped tier
    /// would hand back `AEC1...` framed bytes as plaintext on every read, and a `put` during this
    /// disabled window writes real plaintext that a later re-enable's legacy-plaintext guard cannot
    /// tell apart from the ciphertext already there (the marker already exists, so that guard
    /// short-circuits straight past it). Recovery: set `encrypted = true` (or `streams_encrypted =
    /// true`) and keep `keyId` resolvable in `[storage.encryption.keys]`, or migrate the directory
    /// to a fresh, unmarked one (tracked separately -- #831).
    record EncryptedTierRequiresKeyring(String instanceName, String keyId) implements EncryptionError {
        @Override
        public String message() {
            return "Storage instance '" + instanceName
                 + "' was encrypted under key id '" + keyId
                 + "' but no encryption keyring is configured for it now: set encrypted = true and keep key id '" + keyId
                 + "' in [storage.encryption.keys], or migrate to a fresh directory (#831)";
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
            return "Refusing to enable encryption at " + path
                 + ": " + existingBlockCount
                 + " existing block(s) with no encryption marker -- tier may hold plaintext data";
        }
    }
}
