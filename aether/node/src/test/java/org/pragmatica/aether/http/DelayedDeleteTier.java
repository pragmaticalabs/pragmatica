// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.Anchor;
import org.pragmatica.storage.AnchorName;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.BlockStat;
import org.pragmatica.storage.Cursor;
import org.pragmatica.storage.DegradeReporter;
import org.pragmatica.storage.KeyShard;
import org.pragmatica.storage.Page;
import org.pragmatica.storage.StorageTier;
import org.pragmatica.storage.TierLevel;


/// A tier that delegates everything and, once armed, finishes each read and each delete only after a fixed delay: deleting an
/// object reads its manifest before the catalog entry is removed, so the delayed read is what keeps the object visible while
/// the delete is in flight. The seam makes "the answer is observable before the delete has finished" deterministic instead of
/// a matter of scheduler luck (#2915).
final class DelayedDeleteTier implements StorageTier {
    private final StorageTier delegate;
    private final AtomicLong delayMillis = new AtomicLong();

    DelayedDeleteTier(StorageTier delegate) {
        this.delegate = delegate;
    }

    void delayBy(long millis) {
        delayMillis.set(millis);
    }

    @Override
    public Promise<Unit> delete(BlockId id) {
        var delay = delayMillis.get();

        return delay == 0
               ? delegate.delete(id)
               : deleteLater(delay, id);
    }

    private Promise<Unit> deleteLater(long delay, BlockId id) {
        var promise = Promise.<Unit> promise();

        Thread.ofVirtual().start(() -> deleteAfter(delay, id, promise));

        return promise;
    }

    private void deleteAfter(long delay, BlockId id, Promise<Unit> promise) {
        sleep(delay);
        delegate.delete(id).onResult(promise::resolve);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Promise<Option<byte[]>> get(BlockId id) {
        var delay = delayMillis.get();

        return delay == 0
               ? delegate.get(id)
               : getLater(delay, id);
    }

    private Promise<Option<byte[]>> getLater(long delay, BlockId id) {
        var promise = Promise.<Option<byte[]>> promise();

        Thread.ofVirtual().start(() -> getAfter(delay, id, promise));

        return promise;
    }

    private void getAfter(long delay, BlockId id, Promise<Option<byte[]>> promise) {
        sleep(delay);
        delegate.get(id).onResult(promise::resolve);
    }

    @Override
    public Promise<Unit> put(BlockId id, byte[] content) {
        return delegate.put(id, content);
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
    public Promise<Option<BlockStat>> stat(BlockId id) {
        return delegate.stat(id);
    }

    @Override
    public Promise<Page<BlockStat>> scan(KeyShard shard, Option<Cursor> after, int limit) {
        return delegate.scan(shard, after, limit);
    }

    @Override
    public Promise<Boolean> touch(BlockId id) {
        return delegate.touch(id);
    }

    @Override
    public Promise<Unit> putAnchor(AnchorName name, long version, byte[] value) {
        return delegate.putAnchor(name, version, value);
    }

    @Override
    public Promise<Option<Anchor>> latestAnchor(AnchorName name) {
        return delegate.latestAnchor(name);
    }

    @Override
    public Promise<Unit> pruneAnchors(AnchorName name, long below, int scanLimit) {
        return delegate.pruneAnchors(name, below, scanLimit);
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
