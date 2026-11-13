package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Import-declaration parsing shared by the JBCT-ARCH rules ([ArchSupport#importedPackage], [ArchSupport#importedSimpleName]).
class ArchSupportTest {
    @Test
    void importBody_endsAtTheFirstSemicolon() {
        assertEquals("a.b", ArchSupport.importedPackage("import a.b.C; import d.e.F;"));
    }

    @Test
    void importBody_semicolonOnlyDeclaration_isEmpty() {
        assertEquals("", ArchSupport.importedSimpleName("import ;"));
    }

    @Test
    void importBody_withoutSemicolon_keepsTheWholeName() {
        assertEquals("C", ArchSupport.importedSimpleName("import a.b.C"));
    }

    @Test
    void importBody_leadingWhitespace_isTrimmed() {
        assertEquals("C", ArchSupport.importedSimpleName("\n    import a.b.C;"));
    }

    @Test
    void importBody_leadingComment_isTrimmed() {
        assertEquals("a.b", ArchSupport.importedPackage("/* note */ import a.b.C;"));
    }

    @Test
    void importBody_staticImport_dropsTheKeyword() {
        assertEquals("a.b.C", ArchSupport.importedPackage("import static a.b.C.m;"));
    }

    @Test
    void importBody_packageStartingWithStatic_isNotAStaticImport() {
        assertEquals("staticfoo", ArchSupport.importedPackage("import staticfoo.Bar;"));
    }

    @Test
    void importBody_nameSplitOverLines_isJoined() {
        assertEquals("C", ArchSupport.importedSimpleName("import a.b.\n        C;"));
    }
}
