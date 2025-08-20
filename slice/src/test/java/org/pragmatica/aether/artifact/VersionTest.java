package org.pragmatica.aether.artifact;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class VersionTest {

    @Test
    void version_parsing_with_valid_major_minor_patch() {
        Version.version("1.2.3")
               .onSuccess(version -> {
                   assertThat(version.major()).isEqualTo(1);
                   assertThat(version.minor()).isEqualTo(2);
                   assertThat(version.patch()).isEqualTo(3);
                   assertThat(version.qualifier()).isEmpty();
               })
               .onFailureRun(Assertions::fail);
    }

    @Test
    void version_parsing_with_qualifier() {
        Version.version("1.0.0-SNAPSHOT")
               .onSuccess(version -> {
                   assertThat(version.major()).isEqualTo(1);
                   assertThat(version.minor()).isEqualTo(0);
                   assertThat(version.patch()).isEqualTo(0);
                   assertThat(version.qualifier()).isEqualTo("-SNAPSHOT");
               })
               .onFailureRun(Assertions::fail);
    }

    @Test
    void version_parsing_with_complex_qualifier() {
        Version.version("2.1.5-beta1")
               .onSuccess(version -> {
                   assertThat(version.major()).isEqualTo(2);
                   assertThat(version.minor()).isEqualTo(1);
                   assertThat(version.patch()).isEqualTo(5);
                   assertThat(version.qualifier()).isEqualTo("-beta1");
               })
               .onFailureRun(Assertions::fail);
    }

    @Test
    void version_parsing_rejects_invalid_format() {
        Version.version("1.2")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause.message()).contains("Invalid version format"));

        Version.version("1.2.3.4")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause.message()).contains("Invalid version format"));

        Version.version("")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause.message()).contains("Invalid version format"));
    }

    @Test
    void version_parsing_rejects_non_numeric_components() {
        Version.version("a.2.3")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());

        Version.version("1.b.3")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());

        Version.version("1.2.c")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void version_parsing_rejects_negative_numbers() {
        Version.version("-1.2.3")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());

        Version.version("1.-2.3")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());

        Version.version("1.2.-3")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertThat(cause).isNotNull());
    }

    @Test
    void version_bare_version_excludes_qualifier() {
        var version = Version.version(2, 3, 4, Option.option("rc1")).unwrap();
        assertThat(version.bareVersion()).isEqualTo("2.3.4");
    }

    @Test
    void version_with_qualifier_includes_qualifier() {
        var version = Version.version(1, 0, 0, Option.option("SNAPSHOT")).unwrap();
        assertThat(version.withQualifier()).isEqualTo("1.0.0-SNAPSHOT");
    }

    @Test
    void version_with_no_qualifier_shows_bare_version() {
        var version = Version.version(1, 2, 3, Option.none()).unwrap();
        assertThat(version.withQualifier()).isEqualTo("1.2.3");
    }

    @Test
    void version_parsing_handles_edge_cases() {
        // Version with dash but no qualifier
        Version.version("1.2.3-")
               .onSuccess(version -> assertThat(version.qualifier()).isEqualTo("-"))
               .onFailureRun(Assertions::fail);

        // Version with zero components
        Version.version("0.0.0")
               .onSuccess(version -> {
                   assertThat(version.major()).isEqualTo(0);
                   assertThat(version.minor()).isEqualTo(0);
                   assertThat(version.patch()).isEqualTo(0);
               })
               .onFailureRun(Assertions::fail);
    }
}