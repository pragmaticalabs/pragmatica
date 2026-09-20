// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// Test seam for arming an interleaving around a partition WAL's fsync: reaches the manager's [PartitionWal]
/// for `(stream, partition)` and swaps its channel — the WAL's only I/O seam — for one that delegates
/// everything except `force`, which parks until released. Reflection, because neither is a production surface.
final class GatedWalFsync extends FileChannel {
    final FileChannel delegate;
    final CountDownLatch forceEntered = new CountDownLatch(1);
    final CountDownLatch forceProceed = new CountDownLatch(1);

    private GatedWalFsync(FileChannel delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    static PartitionWal walOf(StreamPartitionManager manager, String stream, int partition) throws Exception {
        var walFor = StreamPartitionManager.class.getDeclaredMethod("walFor", String.class, int.class);

        walFor.setAccessible(true);
        return ((Option<PartitionWal>) walFor.invoke(manager, stream, partition)).unwrap();
    }

    static GatedWalFsync inject(PartitionWal wal) throws ReflectiveOperationException {
        var field = PartitionWal.class.getDeclaredField("channel");

        field.setAccessible(true);
        var gate = new GatedWalFsync((FileChannel) field.get(wal));

        field.set(wal, gate);
        return gate;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        forceEntered.countDown();
        try {
            forceProceed.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delegate.force(metaData);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return delegate.read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return delegate.read(dsts, offset, length);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return delegate.read(dst, position);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return delegate.write(src);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return delegate.write(srcs, offset, length);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        return delegate.write(src, position);
    }

    @Override
    public long position() throws IOException {
        return delegate.position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        return delegate.position(newPosition);
    }

    @Override
    public long size() throws IOException {
        return delegate.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        return delegate.truncate(size);
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return delegate.transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        return delegate.transferFrom(src, position, count);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return delegate.map(mode, position, size);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return delegate.lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return delegate.tryLock(position, size, shared);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        delegate.close();
    }
}
