package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.lint.tree.PackageTree;
import org.pragmatica.jbct.lint.tree.TreeConfig;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-TREE-01 (#2526): no sideways import inside the package tree. Every case is one clause of `allowed(P, Q)`
/// against the root `R` = `[project] basePackage`, over the three book stages: a flat tree, a tree of workflows, and
/// a tree of subsystems holding workflows.
class TreeImportRuleTest {
    private static final String RULE_ID = "JBCT-TREE-01";

    private static LintContext rooted(String root, String... excludes) {
        return LintContext.defaultContext()
                          .withTree(Option.some(PackageTree.packageTree(root,
                                                                        TreeConfig.treeConfig(List.of(excludes)))));
    }

    private static List<Diagnostic> findings(LintContext context, String source) {
        return CstLinter.cstLinter(context)
                        .lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                        .onFailure(cause -> fail("Parse failed: " + cause.message()))
                        .or(List.of())
                        .stream()
                        .filter(diagnostic -> diagnostic.ruleId()
                                                        .equals(RULE_ID))
                        .toList();
    }

    private static List<Integer> lines(LintContext context, String source) {
        return findings(context, source).stream()
                                        .map(Diagnostic::line)
                                        .toList();
    }

    @Nested
    class Sideways {
        /// The brief's own case: with R = conduit, one workflow importing its sibling is sideways.
        @Test
        void siblingWorkflow_isFlagged() {
            var found = findings(rooted("conduit"), """
                package conduit.login;
                import conduit.register.Account;
                class Login {}
                """);

            assertEquals(1, found.size());
            assertEquals(2, found.getFirst().line());
            assertEquals(DiagnosticSeverity.WARNING, found.getFirst().severity());
            assertTrue(found.getFirst().message().contains("'conduit.login'"), found.getFirst().message());
            assertTrue(found.getFirst().message().contains("'conduit.register'"), found.getFirst().message());
        }

        /// A nested type is cut at its first upper-case segment: the target is the package, never `Account.Id`.
        @Test
        void nestedTypeOfSibling_targetsTheSiblingPackage() {
            var found = findings(rooted("conduit"), """
                package conduit.login;
                import conduit.register.HoldSeat.LoadSeat;
                class Login {}
                """);

            assertEquals(1, found.size());
            assertTrue(found.getFirst().message().endsWith("'conduit.register'"), found.getFirst().message());
        }

        @Test
        void staticImportAndStarImportOfSibling_areFlagged() {
            assertEquals(List.of(2, 3), lines(rooted("conduit"), """
                package conduit.login;
                import static conduit.register.Accounts.open;
                import conduit.register.*;
                class Login {}
                """));
        }

        @Test
        void fullyQualifiedTypeOfSibling_isFlagged() {
            assertEquals(List.of(3), lines(rooted("conduit"), """
                package conduit.login;
                class Login {
                    conduit.register.Account account;
                }
                """));
        }

        /// The io package of a sibling's subtree is visible only below that sibling: its prefix before `io` is
        /// `conduit.register`, which is no ancestor of `conduit.login`.
        @Test
        void ioPackageOfSibling_isFlagged() {
            assertEquals(List.of(2), lines(rooted("conduit"), """
                package conduit.login;
                import conduit.register.io.AccountStore;
                class Login {}
                """));
        }

        /// Subsystems holding workflows: a workflow of one subsystem reaching into another subsystem's workflow.
        @Test
        void workflowOfOtherSubsystem_isFlagged() {
            assertEquals(List.of(2), lines(rooted("com.shop"), """
                package com.shop.billing.invoice;
                import com.shop.catalog.item.Item;
                class Invoice {}
                """));
        }
    }

    @Nested
    class Allowed {
        /// The brief's own case: a reference outside the tree is never judged.
        @Test
        void packageOutsideTheTree_isClean() {
            assertEquals(List.of(), lines(rooted("org.pragmatica.aether.example.x"), """
                package org.pragmatica.aether.example.x.order;
                import org.pragmatica.lang.Result;
                class Order {}
                """));
        }

        /// Flat stage: every file sits in the root itself.
        @Test
        void flatTree_referencesInsideTheRoot_areClean() {
            assertEquals(List.of(), lines(rooted("com.shop"), """
                package com.shop;
                import com.shop.Cart;
                class Order {
                    com.shop.Item item;
                }
                """));
        }

        @Test
        void ancestorAndDescendant_areClean() {
            assertEquals(List.of(), lines(rooted("conduit"), """
                package conduit.login;
                import conduit.Conduit;
                import conduit.login.token.Token;
                class Login {}
                """));
        }

        /// Workflow stage: the shared and io packages of the root are visible from every workflow below it.
        @Test
        void sharedAndIoOfAnAncestor_areClean() {
            assertEquals(List.of(), lines(rooted("com.shop"), """
                package com.shop.order;
                import com.shop.shared.Money;
                import com.shop.io.Database;
                class Order {}
                """));
        }

        /// Subsystem stage: a subsystem's shared package is visible from its own workflows.
        @Test
        void sharedOfOwnSubsystem_isClean() {
            assertEquals(List.of(), lines(rooted("com.shop"), """
                package com.shop.billing.invoice;
                import com.shop.billing.shared.TaxRate;
                class Invoice {}
                """));
        }

        /// io wins over shared: `com.shop.shared.io` has role io and its prefix before the first role segment is
        /// `com.shop`, an ancestor of the importer.
        @Test
        void ioBelowShared_prefixIsBeforeTheFirstRoleSegment() {
            assertEquals(List.of(), lines(rooted("com.shop"), """
                package com.shop.order;
                import com.shop.shared.io.Clock;
                class Order {}
                """));
        }

        @Test
        void excludedPackage_isNotJudged() {
            assertEquals(List.of(), lines(rooted("conduit", "conduit.generated.**"), """
                package conduit.login;
                import conduit.generated.api.LoginDto;
                class Login {}
                """));
        }

        /// The ancestor test is on segment boundaries: `com.shopping` is no descendant of `com.shop`, so it is
        /// outside the tree and never judged.
        @Test
        void textualPrefixOfTheRoot_isOutsideTheTree() {
            assertEquals(List.of(), lines(rooted("com.shop"), """
                package com.shop.order;
                import com.shopping.cart.Cart;
                class Order {}
                """));
        }

        /// A simple name resolves through an import; a qualified name whose first segment is a type (a nested type of
        /// the default package) leaves no package segment, so neither has a target.
        @Test
        void simpleNameAndDefaultPackageReference_haveNoTarget() {
            assertEquals(List.of(), lines(rooted("conduit"), """
                package conduit.login;
                class Login {
                    Account account;
                    Helper.Inner inner;
                }
                """));
        }
    }

    @Nested
    class Silent {
        @Test
        void contextWithoutTree_isSilent() {
            assertEquals(List.of(), lines(LintContext.defaultContext(), """
                package conduit.login;
                import conduit.register.Account;
                class Login {}
                """));
        }

        @Test
        void infrastructureMode_doesNotRunTheRule() {
            assertEquals(List.of(), lines(rooted("conduit").withProject(JbctMode.INFRASTRUCTURE, Option.some("conduit")), """
                package conduit.login;
                import conduit.register.Account;
                class Login {}
                """));
        }

        @Test
        void excludePackagesOfTheFile_isSilent() {
            assertEquals(List.of(), lines(rooted("conduit").withExcludePackages(List.of("conduit.login")), """
                package conduit.login;
                import conduit.register.Account;
                class Login {}
                """));
        }
    }
}
