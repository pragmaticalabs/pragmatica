// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;


/// Test seam: the WAL of `(stream, partition)` gets a channel that writes normally and fails every `force`, so the
/// owner's group-commit fsync fails AFTER the frame was written and the record replicated (#2590 N3). Reflection,
/// because neither the manager's WAL nor the log's channel is a production surface (as `GatedWalFsync`).
final class FailingFsyncChannel extends FileChannel {
    private final FileChannel delegate;

    private FailingFsyncChannel(FileChannel delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    static void inject(StreamPartitionManager manager, String stream, int partition) throws ReflectiveOperationException {
        var walFor = StreamPartitionManager.class.getDeclaredMethod("walFor", String.class, int.class);

        walFor.setAccessible(true);
        var wal = ((Option<AppendLog>) walFor.invoke(manager, stream, partition)).unwrap();
        var field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        field.set(wal, new FailingFsyncChannel((FileChannel) field.get(wal)));
    }

    @Override
    public void force(boolean metaData) throws IOException {
        throw new IOException("injected fsync failure (#2590 N3 test)");
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
