package org.pragmatica.jbct.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.shared.ImportGroups.Group;

class ImportGroupsTest {
    @Test
    void classify_jdk_forJavaAndJavax() {
        assertThat(ImportGroups.classify("java.util.List", "com.example")).isEqualTo(Group.JDK);
        assertThat(ImportGroups.classify("javax.annotation.Nonnull", "com.example")).isEqualTo(Group.JDK);
    }

    @Test
    void classify_pragmatica_forOrgPragmatica() {
        assertThat(ImportGroups.classify("org.pragmatica.lang.Result", "com.example")).isEqualTo(Group.PRAGMATICA);
    }

    @Test
    void classify_pragmatica_evenWhenProjectPackageIsPragmatica() {
        assertThat(ImportGroups.classify("org.pragmatica.lang.Result", "org.pragmatica")).isEqualTo(Group.PRAGMATICA);
    }

    @Test
    void classify_thirdParty_forNonProjectExternalPackages() {
        assertThat(ImportGroups.classify("org.slf4j.Logger", "com.example")).isEqualTo(Group.THIRD_PARTY);
        assertThat(ImportGroups.classify("com.google.common.collect.ImmutableList", "org.pragmatica"))
            .isEqualTo(Group.THIRD_PARTY);
        assertThat(ImportGroups.classify("io.netty.buffer.ByteBuf", "com.example")).isEqualTo(Group.THIRD_PARTY);
        assertThat(ImportGroups.classify("net.bytebuddy.ByteBuddy", "com.example")).isEqualTo(Group.THIRD_PARTY);
    }

    @Test
    void classify_project_whenPathMatchesProjectPackage() {
        assertThat(ImportGroups.classify("com.example.domain.User", "com.example")).isEqualTo(Group.PROJECT);
    }

    @Test
    void classify_project_forUnclassifiedPrefix() {
        assertThat(ImportGroups.classify("acme.internal.Thing", "com.example")).isEqualTo(Group.PROJECT);
    }

    @Test
    void ordinal_isMonotonic_acrossBookOrder() {
        int jdk = ImportGroups.ordinal("java.util.List", false, "com.example");
        int pragmatica = ImportGroups.ordinal("org.pragmatica.lang.Result", false, "com.example");
        int thirdParty = ImportGroups.ordinal("org.slf4j.Logger", false, "com.example");
        int project = ImportGroups.ordinal("com.example.domain.User", false, "com.example");
        assertThat(jdk).isLessThan(pragmatica);
        assertThat(pragmatica).isLessThan(thirdParty);
        assertThat(thirdParty).isLessThan(project);
    }

    @Test
    void ordinal_placesStaticImportsAfterAllNonStatic() {
        int nonStaticProject = ImportGroups.ordinal("com.example.domain.User", false, "com.example");
        int staticJdk = ImportGroups.ordinal("java.util.Objects.requireNonNull", true, "com.example");
        assertThat(staticJdk).isGreaterThan(nonStaticProject);
    }

    @Test
    void ordinal_ordersStaticImportsByBookOrderWithinStaticSection() {
        int staticJdk = ImportGroups.ordinal("java.util.Objects.requireNonNull", true, "com.example");
        int staticPragmatica = ImportGroups.ordinal("org.pragmatica.lang.Result.success", true, "com.example");
        assertThat(staticJdk).isLessThan(staticPragmatica);
    }

    @Test
    void projectPackage_takesFirstTwoSegments() {
        assertThat(ImportGroups.projectPackage("com.example.usecase.test")).isEqualTo("com.example");
    }

    @Test
    void projectPackage_returnsSingleSegment_whenOnlyOnePresent() {
        assertThat(ImportGroups.projectPackage("acme")).isEqualTo("acme");
    }

    @Test
    void projectPackage_returnsEmpty_forEmptyName() {
        assertThat(ImportGroups.projectPackage("")).isEmpty();
    }

    @Test
    void isStatic_true_forStaticImport() {
        assertThat(ImportGroups.isStatic("import static java.util.Objects.requireNonNull;")).isTrue();
    }

    @Test
    void isStatic_false_forRegularImport() {
        assertThat(ImportGroups.isStatic("import java.util.List;")).isFalse();
    }

    @Test
    void stripToPath_removesKeywordsAndSemicolon() {
        assertThat(ImportGroups.stripToPath("import java.util.List;")).isEqualTo("java.util.List");
        assertThat(ImportGroups.stripToPath("import static org.pragmatica.lang.Result.success;"))
            .isEqualTo("org.pragmatica.lang.Result.success");
        assertThat(ImportGroups.stripToPath("import module java.base;")).isEqualTo("java.base");
    }
}
