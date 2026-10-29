// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;

import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DHTError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public final class DhtStorageTier implements StorageTier {
    /// #858 C1: default bound a gated read waits for the post-formation admission check
    /// (`StorageFactory.verifyDhtMarker`) to resolve `readGate` before failing with
    /// [StorageError.TierNotAdmitted]. Same value as `StorageFactory#DHT_MARKER_TIMEOUT`, which bounds
    /// ONE attempt of that check. #1052: the check retries attempts that could not complete, so `readGate`
    /// can stay pending longer than this bound. Each operation waiting on it is still refused after this
    /// bound, never admitted.
    private static final TimeSpan DEFAULT_ADMISSION_TIMEOUT = timeSpan(30).seconds();

    /// How the values this tier stores relate to their keys (A6), which decides how a read reaches the DHT.
    public enum Addressing {
        /// The stored value IS the block's content, so its SHA-256 is the [BlockId]: a read takes the verified
        /// single-replica path ([DHTClient#getContentAddressed]) — one replica, hash-checked, the next on a miss or
        /// a mismatch.
        CONTENT,
        /// The stored value is not the content — ciphertext under an `EncryptingStorageTier`, whose authentication is
        /// checked above this tier — so its hash proves nothing here: a read takes the quorum [DHTClient#get].
        OPAQUE
    }

    private final DHTClient dhtClient;
    private final String keyPrefix;
    private final byte[] keyPrefixBytes;
    private final String instanceName;
    private final Promise<Unit> readGate;
    private final TimeSpan admissionTimeout;
    private final Addressing addressing;

    private DhtStorageTier(DHTClient dhtClient,
                           String keyPrefix,
                           String instanceName,
                           Promise<Unit> readGate,
                           TimeSpan admissionTimeout,
                           Addressing addressing) {
        this.dhtClient = dhtClient;
        this.keyPrefix = keyPrefix;
        this.keyPrefixBytes = (keyPrefix + "/").getBytes(StandardCharsets.UTF_8);
        this.instanceName = instanceName;
        this.readGate = readGate;
        this.admissionTimeout = admissionTimeout;
        this.addressing = addressing;
    }

    /// An ungated tier whose reads take the quorum ([Addressing#OPAQUE]).
    public static DhtStorageTier dhtStorageTier(DHTClient dhtClient, String keyPrefix) {
        return new DhtStorageTier(dhtClient,
                                  keyPrefix,
                                  keyPrefix,
                                  Promise.UNIT,
                                  DEFAULT_ADMISSION_TIMEOUT,
                                  Addressing.OPAQUE);
    }

    /// #858: the post-formation DHT-marker check (`StorageFactory.verifyDhtMarker`) resolves
    /// `readGate` only after it has verified/written this namespace's encryption marker -- so an
    /// operation issued before that point (impossible for `artifacts`/`streams`, only reachable via
    /// deployed slices which themselves need post-formation leader election, but enforced here rather
    /// than merely documented) waits instead of racing a marker check that hasn't run yet.
    ///
    /// #874: `get`, `put`, `delete` AND `exists` are all gated on the same `readGate` -- an earlier
    /// ruling scoped the guard to `get` alone, reasoning only a read could observe a not-yet-verified
    /// namespace. That missed two things. First, `AetherNode.start()` brings `managementServer`/
    /// `appHttpServer` up BEFORE `verifyDhtMarkers()` runs, so an ungated `put` reachable over HTTP
    /// (e.g. a Maven `deploy`) could persist a PLAINTEXT block into a namespace whose marker, once
    /// actually checked, says it is already encrypted -- and because the DHT is a cluster-wide shared
    /// store (`#isShared`), that block replicates to peers and survives this node's subsequent
    /// `EncryptedTierRequiresKeyring` abort. Second, `exists` IS a read -- a DHT round trip on
    /// `buildKey(id)`, never a local, gate-free check. `instanceName` is the operator-facing identity
    /// used in [StorageError.TierNotAdmitted]'s cause text -- distinct from `keyPrefix`, which is the
    /// DHT key namespace.
    ///
    /// Reads take the quorum ([Addressing#OPAQUE]); [#dhtStorageTier(DHTClient, String, String, Promise, Addressing)]
    /// chooses.
    public static DhtStorageTier dhtStorageTier(DHTClient dhtClient,
                                                String keyPrefix,
                                                String instanceName,
                                                Promise<Unit> readGate) {
        return dhtStorageTier(dhtClient, keyPrefix, instanceName, readGate, Addressing.OPAQUE);
    }

    /// The gated tier of [#dhtStorageTier(DHTClient, String, String, Promise)], with its reads chosen by
    /// `addressing` (A6): [Addressing#CONTENT] only when the value stored is the block's content.
    public static DhtStorageTier dhtStorageTier(DHTClient dhtClient,
                                                String keyPrefix,
                                                String instanceName,
                                                Promise<Unit> readGate,
                                                Addressing addressing) {
        return new DhtStorageTier(dhtClient, keyPrefix, instanceName, readGate, DEFAULT_ADMISSION_TIMEOUT, addressing);
    }

    /// #858 C1 test seam: lets tests bound the admission wait far below the 30s production default so
    /// "waits at most a bound, then fails" is provable in milliseconds. Package-private -- only
    /// `DhtStorageTierTest` (same package) needs it.
    static DhtStorageTier dhtStorageTier(DHTClient dhtClient,
                                         String keyPrefix,
                                         String instanceName,
                                         Promise<Unit> readGate,
                                         TimeSpan admissionTimeout) {
        return new DhtStorageTier(dhtClient, keyPrefix, instanceName, readGate, admissionTimeout, Addressing.OPAQUE);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return admission().flatMap(_ -> read(id));
    }

    /// A content-addressed value is read from one replica and hash-checked against the id; any other value takes
    /// the quorum (A6).
    private Promise<Option<byte[]>> read(BlockId id) {
        return addressing == Addressing.CONTENT
               ? dhtClient.getContentAddressed(buildKey(id), id.hash())
               : dhtClient.get(buildKey(id));
    }

    /// #858 C1/#874: an operation (`get`/`put`/`delete`/`exists`) arriving while `readGate` is still
    /// pending waits at most `admissionTimeout` then fails with [StorageError.TierNotAdmitted] --
    /// never an unbounded wait. An operation arriving after `readGate` was already resolved --
    /// success (admitted) or failure (the step REFUSED this tier, e.g.
    /// `EncryptionError.EncryptedTierRequiresKeyring`) -- returns/fails immediately, carrying the real
    /// cause on refusal, never waiting the bound. The `isResolved()` fast path covers the common case
    /// (ungated tiers via `Promise.UNIT`, or any call once admission has settled) without paying for
    /// `.map()`/`.timeout()`.
    ///
    /// `.timeout()` is applied to a `.map()`-derived, single-use promise -- never to `readGate`
    /// itself. `readGate` is shared and resolved from a separate call path
    /// (`StorageFactory.verifyDhtMarker`); `Promise.timeout(TimeSpan)`'s scheduled failure task
    /// resolves the promise it was called ON, so calling it directly on `readGate` would let a firing
    /// timeout permanently fail `readGate` (`resolve` is compare-and-set, first-writer-wins) for every
    /// future reader if it lost a race with the real admission. A `.map()` result is a fresh promise
    /// per call, so the scheduled failure can only ever resolve that ephemeral promise -- a harmless
    /// no-op if `readGate` settles first.
    private Promise<Unit> admission() {
        if (readGate.isResolved()) {
            return readGate;
        }

        return readGate.map(_ -> unit())
                       .timeout(admissionTimeout)
                       .mapError(cause -> cause instanceof CoreError.Timeout
                                          ? new StorageError.TierNotAdmitted(instanceName,
                                                                             admissionTimeout.millis())
                                          : cause);
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return admission().flatMap(_ -> dhtClient.put(buildKey(id), content))
                          .mapError(DhtStorageTier::tierFullOnCapacity);
    }

    /// A put the DHT refused for capacity is the tier being full (#2258, spec 3.2 item 7): the instance treats it as it does a
    /// full disk tier, so the write fails and its local-disk copy is undone. Any other cause stays as it was.
    private static Cause tierFullOnCapacity(Cause cause) {
        return cause instanceof DHTError.ReplicaFull full
               ? new StorageError.TierFull(TierLevel.REMOTE, full.usedBytes(), full.maxBytes())
               : cause;
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return admission().flatMap(_ -> dhtClient.remove(buildKey(id))
                                                 .mapToUnit());
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return admission().flatMap(_ -> probe(id));
    }

    /// The exists of a content-addressed value takes the same verified path as its get, so the two agree (A6, D5): it is
    /// positive exactly when a read would return the block. Any other value takes the quorum exists.
    private Promise<Boolean> probe(BlockId id) {
        return addressing == Addressing.CONTENT
               ? dhtClient.existsContentAddressed(buildKey(id), id.hash())
               : dhtClient.exists(buildKey(id));
    }

    @Override
    public TierLevel level() {
        return TierLevel.REMOTE;
    }

    @Override
    public long usedBytes() {
        return dhtClient.localUsage(keyPrefix).usedBytes();
    }

    @Override
    public long maxBytes() {
        return dhtClient.localUsage(keyPrefix).maxBytes();
    }

    /// #250: the DHT is a cluster-wide shared store -- a block orphaned by THIS node's
    /// local refcount may still be referenced by another node's local view. Node-local
    /// garbage collection must never delete here on that basis alone.
    @Override
    public boolean isShared() {
        return true;
    }

    private byte[] buildKey(BlockId id) {
        var hex = id.hexString().getBytes(StandardCharsets.UTF_8);
        var key = new byte[keyPrefixBytes.length + hex.length];

        System.arraycopy(keyPrefixBytes, 0, key, 0, keyPrefixBytes.length);
        System.arraycopy(hex, 0, key, keyPrefixBytes.length, hex.length);

        return key;
    }
}
