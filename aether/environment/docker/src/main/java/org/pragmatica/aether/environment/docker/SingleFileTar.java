// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;


/// A ustar archive holding exactly one regular file, built in memory (#828). `docker cp - <container>:<dir>` extracts it with the
/// owner and mode written here, so a secret reaches a created container as a file owned by the node's user, mode 0400, without
/// touching the host filesystem, any argv, or the container's environment.
interface SingleFileTar {
    int BLOCK = 512;

    static byte[] singleFileTar(String name, byte[] content, int mode, int uid, int gid) {
        return filesTar(java.util.Map.of(name, content), mode, uid, gid);
    }

    /// One regular-file entry per map entry (in iteration order), all with the same mode and owner.
    static byte[] filesTar(java.util.Map<String, byte[]> files, int mode, int uid, int gid) {
        var out = new java.io.ByteArrayOutputStream();

        files.forEach((name, content) -> entry(out, name, content, mode, uid, gid));
        out.writeBytes(new byte[2 * BLOCK]);

        return out.toByteArray();
    }

    private static void entry(java.io.ByteArrayOutputStream out,
                              String name,
                              byte[] content,
                              int mode,
                              int uid,
                              int gid) {
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
        out.writeBytes(header);
        out.writeBytes(content);
        out.writeBytes(new byte[(BLOCK - content.length % BLOCK) % BLOCK]);
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
