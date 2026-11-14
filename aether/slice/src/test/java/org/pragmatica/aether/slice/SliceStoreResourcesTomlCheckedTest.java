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

import org.pragmatica.lang.Cause;
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

    @Test
    void checked_entryFailure_messageIsOneLineWithoutStackTrace() throws IOException {
        var good = jarBytes(ENTRY, randomToml());
        var corrupt = good.clone();
        var dataStart = 30 + ENTRY.length();

        for (var i = dataStart + 200; i < dataStart + 400; i++) {
            corrupt[i] ^= (byte) 0x5A;
        }

        var message = ((Cause) outcome(SliceStore.readSliceResourcesTomlChecked(jar("trace.jar", corrupt)))).message();

        assertThat(message).startsWith("Could not read META-INF/resources.toml from the slice jar: java.util.zip.ZipException");
        assertThat(message).doesNotContain("\n").doesNotContain("\tat ");
    }

    @Test
    void checked_fileThatIsNotAZip_isFailureDistinctFromZipWithoutEntry() throws IOException {
        var notZip = jar("not-a-zip.jar", "this is plain text, not an archive".getBytes(StandardCharsets.UTF_8));
        var emptyZip = jar("empty.jar", jarBytes("META-INF/other.txt", ""));

        var notZipOutcome = outcome(SliceStore.readSliceResourcesTomlChecked(notZip));

        assertThat(notZipOutcome).isInstanceOf(Cause.class);
        assertThat(((Cause) notZipOutcome).message()).startsWith("Slice jar cannot be opened as a zip archive:");
        assertThat(outcome(SliceStore.readSliceResourcesTomlChecked(emptyZip))).isEqualTo(Option.none());
        assertThat(SliceStore.readSliceResourcesToml(notZip)).isEqualTo(Option.none());
    }

    @Test
    void checked_nonAsciiEntry_isReadAsUtf8() throws IOException {
        var toml = "[database]\nname = \"caf\u00e9-\u4e2d\u6587\"\n";
        var url = jar("utf8.jar", jarBytes(ENTRY, toml));

        assertThat(outcome(SliceStore.readSliceResourcesTomlChecked(url))).isEqualTo(Option.some(toml));
        assertThat(SliceStore.readSliceResourcesToml(url)).isEqualTo(Option.some(toml));
    }

    @Test
    void checked_urlWithRawSpace_isReadByBothReaders() throws IOException {
        var toml = randomToml();
        var dir = Files.createDirectories(tempDir.resolve("a b"));

        Files.write(dir.resolve("o.jar"), jarBytes(ENTRY, toml));

        var url = new URL("file:" + dir + "/o.jar");

        assertThat(outcome(SliceStore.readSliceResourcesTomlChecked(url))).isEqualTo(Option.some(toml));
        assertThat(SliceStore.readSliceResourcesToml(url)).isEqualTo(Option.some(toml));
    }

    @Test
    void checked_missingFile_isZipOpenFailureAndLenientIsNone() throws IOException {
        var missing = tempDir.resolve("absent.jar");
        var url = missing.toUri().toURL();

        var message = ((Cause) outcome(SliceStore.readSliceResourcesTomlChecked(url))).message();

        assertThat(message).startsWith("Slice jar cannot be opened as a zip archive: java.nio.file.NoSuchFileException: ");
        assertThat(message).contains("absent.jar");
        assertThat(SliceStore.readSliceResourcesToml(url)).isEqualTo(Option.none());
    }

    @Test
    void checked_directoryInsteadOfJar_isZipOpenFailureAndLenientIsNone() throws IOException {
        var directory = Files.createDirectories(tempDir.resolve("dir.jar"));
        var url = directory.toUri().toURL();

        var outcome = outcome(SliceStore.readSliceResourcesTomlChecked(url));

        assertThat(outcome).isInstanceOf(Cause.class);
        assertThat(((Cause) outcome).message()).startsWith("Slice jar cannot be opened as a zip archive: ");
        assertThat(SliceStore.readSliceResourcesToml(url)).isEqualTo(Option.none());
    }
}
