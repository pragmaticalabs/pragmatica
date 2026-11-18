package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.cst.RootResolver.Request;
import org.pragmatica.jbct.lint.cst.RootResolver.RootPlan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/// What a [RootResolver] makes of its arguments: the roots and the files of the plan (#2525). What a run records per
/// file and the sentences it prints live in `RunActionTableTest` and `RunLinesTest`.
class RunOutcomesTest {
    private static final String BUSINESS = "[lint]\nmode = \"business\"\n";

    @TempDir
    Path temp;

    private String savedUserHome;

    @BeforeEach
    void isolate() throws IOException {
        savedUserHome = System.getProperty("user.home");
        System.setProperty("user.home", temp.resolve("home").toString());
        Files.createDirectories(temp.resolve(".git"));
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", savedUserHome);
    }

    private Path module(String name, String toml) throws IOException {
        var module = Files.createDirectories(temp.resolve(name));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), toml);
        return module;
    }

    private static Path source(Path module, String name, String content) throws IOException {
        var directory = Files.createDirectories(module.resolve("src/main/java/com/example"));

        return Files.writeString(directory.resolve(name), content);
    }

    private static RootPlan plan(Path... paths) {
        return RootResolver.resolve(Request.request(List.of(paths)))
                           .fold(cause -> fail("resolve failed: " + cause.message()), plan -> plan);
    }

    @Test
    void resolve_aDirectoryArgumentWrittenWithDots_isOneRootInNormalisedForm() throws IOException {
        var business = module("biz", BUSINESS);
        var file = source(business, "A.java", "class A {}\n");
        var plan = plan(temp.resolve("biz/../biz/./src"), business);

        assertEquals(List.of(file.toAbsolutePath().normalize()), plan.files());
        assertEquals(1, plan.roots().size());
        assertEquals(business.resolve("src/main/java").toAbsolutePath().normalize(), plan.roots().getFirst().path());
    }

    @Test
    void resolve_layoutAboveTheArgument_isNotTheRootOfALayoutlessModule() throws IOException {
        var above = Files.createDirectories(temp.resolve("work/src/main/java/checkout"));
        var module = Files.createDirectories(above.resolve("mod"));

        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), BUSINESS);

        var file = Files.writeString(Files.createDirectories(module.resolve("com/example")).resolve("A.java"),
                                     "class A {}\n");
        var plan = plan(module);

        assertEquals(List.of(module.toAbsolutePath().normalize()),
                     plan.roots().stream().map(root -> root.path()).toList());
        assertEquals(List.of(file.toAbsolutePath().normalize()), plan.files());
    }
}
