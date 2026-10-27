package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// The 5 GiB write and ranged read under `-Xmx256m` that the upload overhaul's A1 row names. The same child-JVM probe as
/// [ContentStoreV2LargeObjectTest], 13 times the size: tagged `Slow`, so the default build excludes it (the root pom's
/// `surefire.excludedGroups`), and run on a build host with
/// `mvn test -pl integrations/storage -Dtest=ContentStoreV2FiveGiBTest -Dgroups=Slow -Dsurefire.excludedGroups=`.
/// It needs about 5 GiB of free disk; `-Dstorage.largeObject.dir=<dir>` places that data somewhere other than the JVM's
/// temporary directory.
@Tag("Slow")
class ContentStoreV2FiveGiBTest {
    private static final long SIZE = 5L * 1024 * 1024 * 1024;
    private static final long BLOCK = 4L * 1024 * 1024;

    @Test
    void fiveGiB_writesAndReadsBackInAChildJvmWith256MiB(@TempDir Path temp) throws Exception {
        var base = System.getProperty("storage.largeObject.dir");
        var dir = base == null
                  ? temp
                  : Files.createTempDirectory(Path.of(base), "v2-5gib-");
        var outcome = LargeObjectRun.run(SIZE, dir, 120);
        var diagnostics = "child output:\n" + outcome.output();

        assertThat(outcome.exitCode()).as(diagnostics).isZero();
        assertThat(Long.parseLong(outcome.get("MAX_HEAP_MB"))).as(diagnostics).isLessThanOrEqualTo(LargeObjectRun.HEAP_MB);
        assertThat(outcome.get("READ_SHA")).as(diagnostics).isEqualTo(outcome.get("WRITE_SHA"));
        assertThat(outcome.get("STORED_SHA")).as(diagnostics).isEqualTo(outcome.get("WRITE_SHA"));
        assertThat(outcome.get("BYTES_READ")).as(diagnostics).isEqualTo(Long.toString(SIZE));
        assertThat(outcome.get("RANGES_OK")).as(diagnostics).isEqualTo("true");
        assertThat(Long.parseLong(outcome.get("BLOCK_FILES"))).as(diagnostics).isGreaterThanOrEqualTo(SIZE / BLOCK);
        System.out.println("5GiB write_ms=" + outcome.get("WRITE_MS") + " read_ms=" + outcome.get("READ_MS"));
    }
}
