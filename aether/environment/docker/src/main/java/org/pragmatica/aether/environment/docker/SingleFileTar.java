// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;


/// A ustar archive holding exactly one regular file, built in memory (#828). `docker cp - <container>:<dir>` extracts it with the
/// owner and mode written here, so a secret reaches a created container as a file owned by the node's user, mode 0400, without
/// touching the host filesystem, any argv, or the container's environment.
interface SingleFileTar {
    int BLOCK = 512;

    static byte[] singleFileTar(String name, byte[] content, int mode, int uid, int gid) {
        var header = new byte[BLOCK];

        put(header, 0, 100, name);
        put(header, 100, 8, octal(mode, 7));
        put(header, 108, 8, octal(uid, 7));
        put(header, 116, 8, octal(gid, 7));
        put(header, 124, 12, octal(content.length, 11));
        put(header, 136, 12, octal(0, 11));
        Arrays.fill(header, 148, 156, (byte)' ');
        header[156] = '0';
        put(header, 257, 6, "ustar");
        put(header, 263, 2, "00");
        put(header, 148, 8, octal(checksum(header), 6) + "\0 ");
        var padded = (content.length + BLOCK - 1) / BLOCK * BLOCK;
        var archive = new byte[BLOCK + padded + 2 * BLOCK];

        System.arraycopy(header, 0, archive, 0, BLOCK);
        System.arraycopy(content, 0, archive, BLOCK, content.length);

        return archive;
    }

    private static int checksum(byte[] header) {
        var sum = 0;

        for (var b : header) {
            sum += b & 0xFF;
        }

        return sum;
    }

    private static String octal(int value, int digits) {
        return String.format("%0" + digits + "o", value);
    }

    private static void put(byte[] target, int offset, int length, String value) {
        var bytes = value.getBytes(StandardCharsets.US_ASCII);

        System.arraycopy(bytes, 0, target, offset, Math.min(bytes.length, length));
    }
}
