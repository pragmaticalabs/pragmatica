package org.pragmatica.jbct.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.core.api.AbstractStringAssert;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.jbct.lint.LintConfig;
import org.pragmatica.jbct.lint.TechnicalWords;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The `[lint.naming] add / remove` keys and the word-list defaults behind JBCT-NAM-06 and JBCT-NAM-07 (#2529).
class NamingConfigTest {
    private static final String USER_HOME_PROPERTY = "user.home";

    @TempDir
    Path tempDir;

    private String savedUserHome;

    @BeforeEach
    void redirectUserHome() {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
    }

    @Test
    void defaults_carryTheIssueWordLists_andPackageListExtendsTypeList() {
        var words = LintConfig.DEFAULT.technicalWords();

        assertThat(words.typeWords()).contains("repository", "dao", "controller", "adapter", "gateway", "client", "impl",
                                               "service", "manager", "helper", "util", "jooq", "jpa", "jdbc", "postgres",
                                               "http", "rest", "kafka")
                                     .doesNotContain("persistence", "usecase", "domain", "utils");
        assertThat(words.packageWords()).containsAll(words.typeWords())
                                        .contains("internal", "utils", "common", "persistence", "usecase", "domain",
                                                  "application", "infra", "integration", "bootstrap", "config");
    }

    @Test
    void materialize_keepsDefaultWordLists_whenNoNamingSection() {
        assertThat(config("[lint]\nfailOnWarning = true\n").lint()
                                                          .technicalWords()).isEqualTo(TechnicalWords.DEFAULT);
    }

    @Test
    void add_extendsBothLists_caseInsensitively() {
        var words = config("[lint.naming]\nadd = [\"Saga\", \"FACADE\"]\n").lint()
                                                                         .technicalWords();

        assertThat(words.typeWords()).contains("saga", "facade", "service");
        assertThat(words.packageWords()).contains("saga", "facade", "service", "persistence");
    }

    @Test
    void remove_narrowsBothLists_andWinsOverAdd() {
        var words = config("[lint.naming]\nadd = [\"Saga\"]\nremove = [\"Service\", \"persistence\", \"saga\"]\n").lint()
                                                                                                               .technicalWords();

        assertThat(words.typeWords()).doesNotContain("service", "saga").contains("client");
        assertThat(words.packageWords()).doesNotContain("service", "persistence", "saga").contains("usecase");
    }

    @Test
    void unknownKey_isRefused_namingKeyValueAndAllowedKeys() {
        refusal("[lint.naming]\nadd = [\"Saga\"]\nremov = [\"Service\"]\n").contains("lint.naming.remov",
                                                                                   "one of the keys: add, remove")
                                                                         .doesNotContain("lint.naming.add");
    }

    @Test
    void wrongTypeOrWord_isRefused_namingKeyAndValue() {
        refusal("[lint.naming]\nadd = \"Saga\"\n").contains("lint.naming.add", "Saga");
        refusal("[lint.naming]\nremove = [\"Ok\", \"not a word\"]\n").contains("lint.naming.remove",
                                                                              "not a word",
                                                                              "letters and digits");
    }

    @Test
    void multiTokenWord_isAccepted_inAddAndInRemove_andServesBothLists() {
        var words = config("[lint.naming]\nadd = [\"PostgreSQL\", \"SeatBook\"]\nremove = [\"SeatBook\"]\n").lint()
                                                                                                           .technicalWords();

        assertThat(words.typeWords()).contains("postgresql").doesNotContain("seatbook");
        assertThat(words.packageWords()).contains("postgresql").doesNotContain("seatbook");
        assertThat(TechnicalWords.tokens("PostgreSQLOrderStore")).containsExactly("Postgre", "SQL", "Order", "Store");
    }

    @Test
    void everyBuiltInWord_passesTheConfiguredWordValidation() {
        var words = LintConfig.DEFAULT.technicalWords();

        assertThat(words.packageWords()).isNotEmpty()
                                        .allSatisfy(word -> assertThat(TechnicalWords.problem(word)).isEqualTo(Option.none()));
        assertThat(words.typeWords()).allSatisfy(word -> assertThat(TechnicalWords.problem(word)).isEqualTo(Option.none()));
    }

    @Test
    void bothKeys_reachTheResolvedLists_throughTheParentChain() throws Exception {
        var root = repository("repo", "[lint.naming]\nadd = [\"Saga\"]\n");
        var module = write(root.resolve("module"), "[lint.naming]\nremove = [\"Service\"]\n");
        var words = load(module).lint()
                                .technicalWords();

        assertThat(words.typeWords()).contains("saga").doesNotContain("service");
        assertThat(words.packageWords()).contains("saga").doesNotContain("service");
    }

    @Test
    void nearerLayer_replacesTheFartherLayersSameKey_andKeepsTheOtherKey() throws Exception {
        var root = repository("repo", "[lint.naming]\nadd = [\"Saga\"]\nremove = [\"Service\"]\n");
        var module = write(root.resolve("module"), "[lint.naming]\nadd = [\"Facade\"]\n");
        var words = load(module).lint()
                                .technicalWords();

        assertThat(words.typeWords()).contains("facade", "client").doesNotContain("saga", "service");
    }

    private static JbctConfig config(String toml) {
        return TomlParser.parse(toml)
                         .flatMap(JbctConfig::fromToml)
                         .unwrap();
    }

    private static AbstractStringAssert<?> refusal(String toml) {
        return assertThat(TomlParser.parse(toml)
                                    .flatMap(JbctConfig::fromToml)
                                    .fold(Cause::message, loaded -> "NOT REFUSED, loaded " + loaded));
    }

    private static JbctConfig load(Path workingDirectory) {
        return ConfigLoader.load(Option.none(), Option.some(workingDirectory))
                           .unwrap();
    }

    private Path repository(String name, String content) throws Exception {
        var root = write(tempDir.resolve(name), content);

        Files.createDirectories(root.resolve(".git"));

        return root;
    }

    private static Path write(Path directory, String content) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(ConfigLoader.PROJECT_CONFIG_NAME), content);

        return directory;
    }
}
