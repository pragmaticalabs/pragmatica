package org.pragmatica.jbct.doc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTargetTest {
    @Test
    void candidates_classFirst_forUppercaseFqcn() {
        var candidates = SourceTarget.candidates("org.pragmatica.lang.Verify");
        assertThat(candidates).first()
                              .isEqualTo("org/pragmatica/lang/Verify.java");
    }

    @Test
    void candidates_packageInfoFirst_forLowercaseFqcn() {
        var candidates = SourceTarget.candidates("org.pragmatica.lang.vo");
        assertThat(candidates).first()
                              .isEqualTo("org/pragmatica/lang/vo/package-info.java");
    }

    @Test
    void candidates_searchAllPackages_forSimpleName() {
        var candidates = SourceTarget.candidates("Result");
        assertThat(candidates).hasSize(SourceTarget.SEARCH_PACKAGES.size())
                              .contains("org/pragmatica/lang/Result.java")
                              .contains("org/pragmatica/lang/vo/Result.java");
    }

    @Test
    void classPath_replacesDotsWithSlashes() {
        assertThat(SourceTarget.classPath("org.pragmatica.lang.Verify")).isEqualTo("org/pragmatica/lang/Verify.java");
    }

    @Test
    void packageInfoPath_appendsPackageInfo() {
        assertThat(SourceTarget.packageInfoPath("org.pragmatica.lang.vo"))
            .isEqualTo("org/pragmatica/lang/vo/package-info.java");
    }
}
