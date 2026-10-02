package org.pragmatica.jbct.slice.generator;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1778: a slice manifest that differs from run to run makes the slice jar non-reproducible, and the write-once
/// cluster repository then refuses a rebuilt jar of an unchanged commit. `Properties.store` stamps the wall-clock date
/// and writes entries in hash order; `reproducibleText` must do neither.
class ManifestReproducibleTextTest {
    @Test
    void reproducibleText_hasNoDateLine_andSortsEntries_whateverTheInsertionOrder() {
        var first = new Properties();
        var second = new Properties();

        first.setProperty("b.key", "2");
        first.setProperty("a.key", "1");
        second.setProperty("a.key", "1");
        second.setProperty("b.key", "2");

        var text = ManifestGenerator.reproducibleText(first, "Slice manifest for X").unwrap();

        assertThat(text).isEqualTo("#Slice manifest for X\na.key=1\nb.key=2\n");
        assertThat(ManifestGenerator.reproducibleText(second, "Slice manifest for X").unwrap()).isEqualTo(text);
    }

    @Test
    void reproducibleText_isIdenticalAcrossTwoCallsSeparatedInTime() throws InterruptedException {
        var props = new Properties();

        props.setProperty("slice.interface", "org.example.X");

        var one = ManifestGenerator.reproducibleText(props, "c").unwrap();

        Thread.sleep(1100);

        assertThat(ManifestGenerator.reproducibleText(props, "c").unwrap()).as("no wall-clock stamp").isEqualTo(one);
    }

    @Test
    void reproducibleText_stillRoundTripsThroughProperties() throws Exception {
        var props = new Properties();

        props.setProperty("path", "C:\\tmp\\x");
        props.setProperty("generated.by", "a=b:c");

        var loaded = new Properties();

        loaded.load(new java.io.StringReader(ManifestGenerator.reproducibleText(props, "c").unwrap()));

        assertThat(loaded).isEqualTo(props);
    }
}
