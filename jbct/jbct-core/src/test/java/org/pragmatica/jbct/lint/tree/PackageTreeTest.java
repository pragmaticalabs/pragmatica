package org.pragmatica.jbct.lint.tree;

import java.util.List;

import org.pragmatica.jbct.lint.tree.PackageTree.Role;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The package-tree model of #2526 (P3): `anc`, `own`, `role`, `allowed` and reference normalisation, clause by clause,
/// then the three book stages (flat; workflows; subsystems and workflows) end to end.
class PackageTreeTest {
    private static PackageTree tree(String root, String... excludes) {
        return PackageTree.packageTree(root, TreeConfig.treeConfig(List.of(excludes)));
    }

    @Nested
    class Ancestry {
        @Test
        void anc_isTrue_forEqualPackages() {
            assertThat(PackageTree.anc("com.shop", "com.shop")).isTrue();
        }

        @Test
        void anc_isTrue_forDescendant() {
            assertThat(PackageTree.anc("com.shop", "com.shop.register.io")).isTrue();
        }

        @Test
        void anc_isFalse_forSiblingSharingTextualPrefix() {
            assertThat(PackageTree.anc("com.shop", "com.shopping")).isFalse();
        }

        @Test
        void anc_isFalse_forAncestorAsSecondArgument() {
            assertThat(PackageTree.anc("com.shop.register", "com.shop")).isFalse();
        }
    }

    @Nested
    class Ownership {
        @Test
        void own_isTrue_forTheRootItself() {
            assertThat(tree("conduit").own("conduit")).isTrue();
        }

        @Test
        void own_isTrue_forAPackageBelowTheRoot() {
            assertThat(tree("conduit").own("conduit.login.io")).isTrue();
        }

        @Test
        void own_isFalse_forSiblingSharingTextualPrefix() {
            assertThat(tree("conduit").own("conduitx.login")).isFalse();
        }

        @Test
        void own_isFalse_forThirdPartyPackage() {
            assertThat(tree("org.pragmatica.aether.example.x").own("org.pragmatica.lang")).isFalse();
        }

        @Test
        void own_isFalse_forTheParentOfTheRoot() {
            assertThat(tree("com.shop").own("com")).isFalse();
        }

        @Test
        void own_isFalse_whenAnExcludeGlobMatches() {
            assertThat(tree("com.shop", "com.shop.generated.**").own("com.shop.generated.api")).isFalse();
        }

        @Test
        void own_isFalse_forTheBarePackageOfAnAnyDepthGlob() {
            assertThat(tree("com.shop", "com.shop.generated.**").own("com.shop.generated")).isFalse();
        }

        @Test
        void own_isTrue_whenNoExcludeGlobMatches() {
            assertThat(tree("com.shop", "com.shop.generated.**").own("com.shop.register")).isTrue();
        }

        @Test
        void own_usesTheSharedGlobSyntax_singleStarIsOneSegment() {
            var tree = tree("com.shop", "com.shop.*.gen");

            assertThat(tree.own("com.shop.a.gen")).isFalse();
            assertThat(tree.own("com.shop.a.b.gen")).isTrue();
        }
    }

    @Nested
    class Roles {
        @Test
        void role_isIo_forAnIoSegment() {
            assertThat(tree("com.shop").role("com.shop.register.io")).isEqualTo(Option.some(Role.IO));
        }

        @Test
        void role_isShared_forASharedSegment() {
            assertThat(tree("com.shop").role("com.shop.shared")).isEqualTo(Option.some(Role.SHARED));
        }

        @Test
        void role_isNone_forAPlainPackage() {
            assertThat(tree("com.shop").role("com.shop.register")).isEqualTo(Option.none());
        }

        @Test
        void role_isIo_whenBothRolesAppear_ioWins() {
            assertThat(tree("com.shop").role("com.shop.shared.billing.io")).isEqualTo(Option.some(Role.IO));
            assertThat(tree("com.shop").role("com.shop.io.shared")).isEqualTo(Option.some(Role.IO));
        }

        @Test
        void role_isFound_inTheMiddleOfThePackage() {
            assertThat(tree("com.shop").role("com.shop.shared.money")).isEqualTo(Option.some(Role.SHARED));
        }

        @Test
        void role_ignoresTheFirstSegment() {
            assertThat(tree("io").role("io.register")).isEqualTo(Option.none());
            assertThat(tree("shared").role("shared")).isEqualTo(Option.none());
        }

        @Test
        void role_isNone_outsideTheRoot() {
            assertThat(tree("com.shop").role("org.other.io")).isEqualTo(Option.none());
        }

        @Test
        void role_isNone_forTheRootItself() {
            assertThat(tree("com.shop").role("com.shop")).isEqualTo(Option.none());
        }

        @Test
        void role_isNotMatchedInsideALongerSegment() {
            assertThat(tree("com.shop").role("com.shop.audio")).isEqualTo(Option.none());
            assertThat(tree("com.shop").role("com.shop.shareddata")).isEqualTo(Option.none());
        }

        @Test
        void role_ignoresExcludeGlobs() {
            assertThat(tree("com.shop", "com.shop.**").role("com.shop.shared")).isEqualTo(Option.some(Role.SHARED));
        }
    }

    @Nested
    class Allowed {
        private final PackageTree tree = tree("com.shop", "com.shop.generated.**");

        @Test
        void allowed_isTrue_forATargetOutsideTheTree() {
            assertThat(tree.allowed("com.shop.login", "org.pragmatica.lang")).isTrue();
        }

        @Test
        void allowed_isTrue_forAnExcludedTarget() {
            assertThat(tree.allowed("com.shop.login", "com.shop.generated.api")).isTrue();
        }

        @Test
        void allowed_isTrue_whenTargetIsTheImportersOwnPackage() {
            assertThat(tree.allowed("com.shop.login", "com.shop.login")).isTrue();
        }

        @Test
        void allowed_isTrue_whenTargetIsAnAncestorOfTheImporter() {
            assertThat(tree.allowed("com.shop.login.io", "com.shop.login")).isTrue();
            assertThat(tree.allowed("com.shop.login", "com.shop")).isTrue();
        }

        @Test
        void allowed_isTrue_whenImporterIsAnAncestorOfTheTarget() {
            assertThat(tree.allowed("com.shop", "com.shop.login")).isTrue();
            assertThat(tree.allowed("com.shop.login", "com.shop.login.model")).isTrue();
        }

        @Test
        void allowed_isFalse_forASiblingImport() {
            assertThat(tree.allowed("com.shop.login", "com.shop.register")).isFalse();
        }

        @Test
        void allowed_isFalse_forACousinImport() {
            assertThat(tree.allowed("com.shop.login.model", "com.shop.register.model")).isFalse();
        }

        @Test
        void allowed_isFalse_forASiblingSharingTextualPrefix() {
            assertThat(tree.allowed("com.shop.login", "com.shop.login2")).isFalse();
        }

        @Test
        void allowed_isTrue_forARoleTargetWhosePrefixIsAnAncestorOfTheImporter() {
            assertThat(tree.allowed("com.shop.login.model", "com.shop.login.io")).isTrue();
            assertThat(tree.allowed("com.shop.login", "com.shop.login.io.http")).isTrue();
        }

        @Test
        void allowed_isTrue_forARoleTargetWhosePrefixIsTheImporterItself() {
            assertThat(tree.allowed("com.shop", "com.shop.shared")).isTrue();
        }

        @Test
        void allowed_isFalse_forARoleTargetOfASibling() {
            assertThat(tree.allowed("com.shop.register", "com.shop.login.io")).isFalse();
            assertThat(tree.allowed("com.shop.register", "com.shop.login.shared")).isFalse();
        }

        @Test
        void allowed_cutsAtTheFirstRoleSegmentOfEitherKind_whenBothRolesAppear() {
            var tree = tree("com.shop");

            assertThat(tree.allowed("com.shop.billing.x", "com.shop.billing.shared.y.io")).isTrue();
            assertThat(tree.allowed("com.shop.billing.shared.z", "com.shop.billing.shared.y.io")).isTrue();
            assertThat(tree.allowed("com.shop.orders", "com.shop.billing.shared.y.io")).isFalse();
        }

        @Test
        void allowed_isFalse_forAPackageThatMerelyNamesAnIoSiblingAsParent() {
            assertThat(tree.allowed("com.shop.login", "com.shop.register.io")).isFalse();
        }
    }

    @Nested
    class Targets {
        @Test
        void target_cutsAtTheFirstUpperCaseSegment_nestedTypesAreNotPackages() {
            assertThat(PackageTree.target("a.b.HoldSeat.LoadSeat")).isEqualTo(Option.some("a.b"));
        }

        @Test
        void target_keepsAllSegments_whenThereIsNoType() {
            assertThat(PackageTree.target("a.b.c")).isEqualTo(Option.some("a.b.c"));
        }

        @Test
        void target_dropsATrailingWildcard() {
            assertThat(PackageTree.target("a.b.*")).isEqualTo(Option.some("a.b"));
        }

        @Test
        void target_cutsAStaticMemberAtItsType() {
            assertThat(PackageTree.target("a.b.Seats.loadSeat")).isEqualTo(Option.some("a.b"));
        }

        @Test
        void target_isNone_forATypeInTheDefaultPackage() {
            assertThat(PackageTree.target("Seat")).isEqualTo(Option.none());
        }

        @Test
        void target_handlesAJdkType() {
            assertThat(PackageTree.target("java.util.Map.Entry")).isEqualTo(Option.some("java.util"));
        }
    }

    /// Stage 1: everything in the root package. No sub-package exists, so no import can be sideways.
    @Nested
    class FlatStage {
        private final PackageTree tree = tree("conduit");

        @Test
        void allowed_everyImport_inAFlatTree() {
            assertThat(tree.allowed("conduit", "conduit")).isTrue();
            assertThat(tree.allowed("conduit", "org.pragmatica.lang")).isTrue();
            assertThat(tree.allowed("conduit", "java.util")).isTrue();
        }
    }

    /// Stage 2: one package per workflow, with role packages inside and one shared package at the root.
    @Nested
    class WorkflowStage {
        private final PackageTree tree = tree("conduit");

        @Test
        void allowed_isFalse_loginImportsRegister() {
            assertThat(tree.allowed("conduit.login", "conduit.register")).isFalse();
        }

        @Test
        void allowed_isFalse_loginImportsRegisterIo() {
            assertThat(tree.allowed("conduit.login", "conduit.register.io")).isFalse();
        }

        @Test
        void allowed_isTrue_registerImportsItsOwnIo() {
            assertThat(tree.allowed("conduit.register", "conduit.register.io")).isTrue();
            assertThat(tree.allowed("conduit.register.model", "conduit.register.io")).isTrue();
        }

        @Test
        void allowed_isTrue_anyWorkflowImportsTheRootShared() {
            assertThat(tree.allowed("conduit.login", "conduit.shared")).isTrue();
            assertThat(tree.allowed("conduit.register.io", "conduit.shared.money")).isTrue();
        }

        @Test
        void allowed_isTrue_aWorkflowImportsTheRootPackage() {
            assertThat(tree.allowed("conduit.login", "conduit")).isTrue();
        }

        @Test
        void allowed_isTrue_theRootImportsAWorkflow() {
            assertThat(tree.allowed("conduit", "conduit.login")).isTrue();
        }

        @Test
        void allowed_isTrue_thirdPartyFromEveryWorkflow() {
            assertThat(tree.allowed("conduit.login", "org.pragmatica.lang")).isTrue();
        }
    }

    /// Stage 3: subsystems, each with its workflows and its own shared and io packages, plus a root shared package.
    @Nested
    class SubsystemStage {
        private final PackageTree tree = tree("com.shop");

        @Test
        void allowed_isTrue_aWorkflowImportsItsSubsystemShared() {
            assertThat(tree.allowed("com.shop.billing.invoice", "com.shop.billing.shared")).isTrue();
        }

        @Test
        void allowed_isTrue_aWorkflowImportsTheRootShared() {
            assertThat(tree.allowed("com.shop.orders.place", "com.shop.shared")).isTrue();
        }

        @Test
        void allowed_isFalse_aWorkflowOfAnotherSubsystemImportsASubsystemShared() {
            assertThat(tree.allowed("com.shop.orders.place", "com.shop.billing.shared")).isFalse();
        }

        @Test
        void allowed_isFalse_aWorkflowImportsASiblingWorkflowInTheSameSubsystem() {
            assertThat(tree.allowed("com.shop.billing.invoice", "com.shop.billing.refund")).isFalse();
        }

        @Test
        void allowed_isTrue_aWorkflowImportsTheIoOfItsOwnSubsystem() {
            assertThat(tree.allowed("com.shop.billing.invoice", "com.shop.billing.io")).isTrue();
        }

        @Test
        void allowed_isFalse_aWorkflowImportsTheIoOfAnotherWorkflow() {
            assertThat(tree.allowed("com.shop.billing.refund", "com.shop.billing.invoice.io")).isFalse();
        }

        @Test
        void allowed_isTrue_theSubsystemImportsItsWorkflow() {
            assertThat(tree.allowed("com.shop.billing", "com.shop.billing.invoice")).isTrue();
        }
    }
}
