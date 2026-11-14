// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// #2100 E-B: the checked `resources.toml` reader reports an unreadable entry that the lenient reader
/// logs and drops, and the lenient reader keeps answering "no file" for it.
class SliceStoreResourcesTomlCheckedTest {
    private static final String ENTRY = "META-INF/resources.toml";

    @TempDir
    Path tempDir;

    private static String randomToml() {
        var random = new Random(7);
        var text = new StringBuilder("[database]\n");

        for (var i = 0; i < 400; i++) {
            var bytes = new byte[12];

            random.nextBytes(bytes);
            text.append("k").append(i).append(" = \"").append(HexFormat.of().formatHex(bytes)).append("\"\n");
        }

        return text.toString();
    }

    private URL jar(String name, byte[] bytes) throws IOException {
        var path = tempDir.resolve(name);

        Files.write(path, bytes);

        return path.toUri().toURL();
    }

    private static byte[] jarBytes(String entryName, String content) throws IOException {
        var out = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        return out.toByteArray();
    }

    private static Object outcome(Result<?> result) {
        return result.<Object>fold(cause -> cause, value -> value);
    }

    @Test
    void checked_jarWithoutEntry_isSuccessNone() throws IOException {
        var url = jar("none.jar", jarBytes("META-INF/other.txt", "x"));

        assertThat(outcome(SliceStore.readSliceResourcesTomlChecked(url))).isEqualTo(Option.none());
    }

    @Test
    void checked_jarWithEntry_isSuccessSome() throws IOException {
        var toml = randomToml();
        var url = jar("some.jar", jarBytes(ENTRY, toml));

        assertThat(outcome(SliceStore.readSliceResourcesTomlChecked(url))).isEqualTo(Option.some(toml));
        assertThat(SliceStore.readSliceResourcesToml(url)).isEqualTo(Option.some(toml));
    }

    @Test
    void checked_entryWhoseBytesCannotBeRead_isFailureAndLenientStaysNone() throws IOException {
        var good = jarBytes(ENTRY, randomToml());
        var corrupt = good.clone();
        var dataStart = 30 + ENTRY.length();

        for (var i = dataStart + 200; i < dataStart + 400; i++) {
            corrupt[i] ^= (byte) 0x5A;
        }

        var url = jar("corrupt.jar", corrupt);

        assertThat(SliceStore.readSliceResourcesTomlChecked(url).isFailure()).describedAs("checked read of a corrupt entry").isTrue();
        assertThat(SliceStore.readSliceResourcesToml(url)).isEqualTo(Option.none());
    }
}
