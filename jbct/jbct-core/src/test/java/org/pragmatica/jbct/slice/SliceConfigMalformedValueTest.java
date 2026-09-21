package org.pragmatica.jbct.slice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1098: `instances = "3x"` used to read as absent and take the default 3; the load refuses it by name.
class SliceConfigMalformedValueTest {
    @TempDir
    Path tempDir;

    @Test
    void load_malformedInstances_refusesNamingKeyAndValue() throws IOException {
        var path = Files.writeString(tempDir.resolve("slice.toml"), "[blueprint]\ninstances = \"3x\"\n");

        SliceConfig.load(path)
                   .onSuccess(config -> fail("instances = \"3x\" must refuse the load, loaded " + config))
                   .onFailure(cause -> assertThat(cause.message()).contains("blueprint.instances")
                                                                  .contains("3x"));
    }

    @Test
    void load_wellFormedInstances_loads() throws IOException {
        var path = Files.writeString(tempDir.resolve("slice.toml"), "[blueprint]\ninstances = 5\n");

        assertThat(SliceConfig.load(path).unwrap().blueprint().instances()).isEqualTo(5);
    }
}
