// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;

import org.pragmatica.dht.DHTClient;
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
    /// [StorageError.TierNotAdmitted]. Mirrors `StorageFactory#DHT_MARKER_TIMEOUT` -- both bound the
    /// same underlying DHT round trip, from opposite ends of the gate.
    private static final TimeSpan DEFAULT_ADMISSION_TIMEOUT = timeSpan(30).seconds();

    private final DHTClient dhtClient;
    private final byte[] keyPrefixBytes;
    private final String instanceName;
    private final Promise<Unit> readGate;
    private final TimeSpan admissionTimeout;

    private DhtStorageTier(DHTClient dhtClient, String keyPrefix, String instanceName, Promise<Unit> readGate,
                            TimeSpan admissionTimeout) {
        this.dhtClient = dhtClient;
        this.keyPrefixBytes = (keyPrefix + "/").getBytes(StandardCharsets.UTF_8);
        this.instanceName = instanceName;
        this.readGate = readGate;
        this.admissionTimeout = admissionTimeout;
    }

    public static DhtStorageTier dhtStorageTier(DHTClient dhtClient, String keyPrefix) {
        return new DhtStorageTier(dhtClient, keyPrefix, keyPrefix, Promise.UNIT, DEFAULT_ADMISSION_TIMEOUT);
    }

    /// #858: the post-formation DHT-marker check (`StorageFactory.verifyDhtMarker`) resolves
    /// `readGate` only after it has verified/written this namespace's encryption marker -- so a read
    /// issued before that point (impossible for `artifacts`/`streams`, only reachable via deployed
    /// slices which themselves need post-formation leader election, but enforced here rather than
    /// merely documented) waits instead of racing a marker check that hasn't run yet. `put`/`delete`/
    /// `exists` are ungated -- the ruling scopes the guard to reads. `instanceName` is the operator-
    /// facing identity used in [StorageError.TierNotAdmitted]'s cause text -- distinct from
    /// `keyPrefix`, which is the DHT key namespace.
    public static DhtStorageTier dhtStorageTier(DHTClient dhtClient, String keyPrefix, String instanceName, Promise<Unit> readGate) {
        return new DhtStorageTier(dhtClient, keyPrefix, instanceName, readGate, DEFAULT_ADMISSION_TIMEOUT);
    }

    /// #858 C1 test seam: lets tests bound the admission wait far below the 30s production default so
    /// "waits at most a bound, then fails" is provable in milliseconds. Package-private -- only
    /// `DhtStorageTierTest` (same package) needs it.
    static DhtStorageTier dhtStorageTier(DHTClient dhtClient, String keyPrefix, String instanceName, Promise<Unit> readGate,
                                          TimeSpan admissionTimeout) {
        return new DhtStorageTier(dhtClient, keyPrefix, instanceName, readGate, admissionTimeout);
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        return admission().flatMap(_ -> dhtClient.get(buildKey(id)));
    }

    /// #858 C1: a read arriving while `readGate` is still pending waits at most `admissionTimeout`
    /// then fails with [StorageError.TierNotAdmitted] -- never an unbounded wait. A read arriving
    /// after `readGate` was already resolved -- success (admitted) or failure (the step REFUSED this
    /// tier, e.g. `EncryptionError.EncryptedTierRequiresKeyring`) -- returns/fails immediately,
    /// carrying the real cause on refusal, never waiting the bound. The `isResolved()` fast path
    /// covers the common case (ungated tiers via `Promise.UNIT`, or any read once admission has
    /// settled) without paying for `.map()`/`.timeout()`.
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
                                           ? new StorageError.TierNotAdmitted(instanceName, admissionTimeout.millis())
                                           : cause);
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return dhtClient.put(buildKey(id), content);
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        return dhtClient.remove(buildKey(id))
                        .mapToUnit();
    }

    @Override
    public Promise<Boolean> exists(BlockId id) {
        return dhtClient.exists(buildKey(id));
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
        return Long.MAX_VALUE;
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
