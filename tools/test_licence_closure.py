"""Regression checks for the licence-closure gate (tools/check-licence-closure.py) and the relicense script (#1989).

Every gate test breaks one thing in a clean synthetic tree and expects exactly that check to go red; the clean tree is the control."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gate = load("licence_closure", HERE / "check-licence-closure.py")
relicense = load("relicense", HERE / "license/relicense.py")

BSL_HEADER = ("// SPDX-License-Identifier: BUSL-1.1\n// Copyright (c) 2025 X\n"
              "// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.\n"
              "// See LICENSE in the repository root for full terms.\n")
APACHE_HEADER = ("// SPDX-License-Identifier: Apache-2.0\n// Copyright (c) 2025 X\n"
                 "// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.\n")
BSL_TEXT = "Business Source License 1.1\n\nParameters\n"
BSL_LICENSES = ("\n    <licenses>\n        <license>\n            <name>Business Source License 1.1</name>\n"
                "            <url>https://mariadb.com/bsl11/</url>\n        </license>\n    </licenses>\n")
LICENSE_MAP = ("Apache License, Version 2.0 (see LICENSE-APACHE-2.0): everything else\n\nBusiness Source License 1.1 (each module's own LICENSE):\n"
               "    bsl/core/\n\nBSL 1.1 parameters\n")


def pom(artifact, deps=(), parent=None, licenses="", modules=(), relative="../pom.xml", published=False):
    body = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
    if parent:
        body += f"<parent><groupId>g</groupId><artifactId>{parent}</artifactId><version>1</version><relativePath>{relative}</relativePath></parent>"
    body += f"<groupId>g</groupId><artifactId>{artifact}</artifactId><version>1</version>{licenses}"
    if modules:
        body += "<modules>" + "".join(f"<module>{m}</module>" for m in modules) + "</modules>"
    if deps:
        body += "<dependencies>" + "".join(
            f"<dependency><groupId>g</groupId><artifactId>{a}</artifactId>" + (f"<scope>{s}</scope>" if s else "") + "</dependency>" for a, s in deps) + "</dependencies>"
    if published:
        body += "<build><plugins><plugin><groupId>org.sonatype.central</groupId><artifactId>central-publishing-maven-plugin</artifactId></plugin></plugins></build>"
    return body + "</project>"


def write(root, rel, text):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def clean_tree(root):
    """root pom (Apache), bsl/core (BSL), lib (Apache, no BSL dep), each with one .java."""
    write(root, "tools/license/bsl-modules.txt", "# list\nbsl/core\n")
    write(root, "LICENSE", LICENSE_MAP)
    write(root, "docs/legal/bsl-header.txt", BSL_HEADER)
    write(root, "docs/legal/apache-header.txt", APACHE_HEADER)
    write(root, "docs/legal/bsl-license.txt", BSL_TEXT)
    write(root, "pom.xml", pom("root", modules=("bsl", "lib")))
    write(root, "bsl/pom.xml", pom("bsl-parent", parent="root", modules=("core",)))
    write(root, "bsl/core/pom.xml", pom("core", parent="bsl-parent", licenses=BSL_LICENSES, deps=(("lib", None),)))
    write(root, "bsl/core/LICENSE", BSL_TEXT)
    write(root, "bsl/core/src/main/java/A.java", BSL_HEADER + "class A {}\n")
    write(root, "lib/pom.xml", pom("lib", parent="root"))
    write(root, "lib/src/main/java/B.java", "class B {}\n")


class GateTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = Path(self.dir.name)
        clean_tree(self.root)

    def tearDown(self):
        self.dir.cleanup()

    def violations(self, prefix=None):
        found = gate.analyse(self.root)[0]
        return [v for v in found if prefix is None or v.startswith(prefix)]

    def test_clean_tree_is_clean(self):
        found, info, modules, edges, java = gate.analyse(self.root)
        self.assertEqual([], found)
        self.assertEqual((4, 2), (modules, java))

    def test_apache_compile_dependency_on_bsl_is_refused(self):
        write(self.root, "lib/pom.xml", pom("lib", parent="root", deps=(("core", None),)))
        self.assertEqual(1, len(self.violations("(a)")))

    def test_runtime_and_provided_scopes_count_too(self):
        for scope in ("runtime", "provided"):
            write(self.root, "lib/pom.xml", pom("lib", parent="root", deps=(("core", scope),)))
            self.assertEqual(1, len(self.violations("(a)")), scope)

    def test_test_scope_edge_from_unpublished_module_is_info_not_a_violation(self):
        write(self.root, "lib/pom.xml", pom("lib", parent="root", deps=(("core", "test"),)))
        found, info, *_ = gate.analyse(self.root)
        self.assertEqual([], found)
        self.assertEqual(1, len(info))

    def test_test_scope_edge_from_published_module_is_refused(self):
        write(self.root, "lib/pom.xml", pom("lib", parent="root", deps=(("core", "test"),), published=True))
        found, info, *_ = gate.analyse(self.root)
        self.assertEqual(1, len(found))
        self.assertEqual([], info)

    def test_compile_edge_from_unpublished_module_is_still_refused(self):
        write(self.root, "lib/pom.xml", pom("lib", parent="root", deps=(("core", "compile"),)))
        self.assertEqual(1, len(self.violations("(a)")))

    def test_apache_module_with_bsl_parent_is_refused(self):
        write(self.root, "lib/pom.xml", pom("lib", parent="core", relative="../bsl/core/pom.xml"))
        self.assertTrue(any("BSL parent" in v for v in self.violations("(a)")))

    def test_bsl_depending_on_apache_is_allowed(self):
        self.assertEqual([], self.violations("(a)"))

    def test_edge_from_a_module_outside_the_default_reactor_is_seen(self):
        write(self.root, "extra/pom.xml", pom("extra", parent="root", deps=(("core", None),)))   # listed in no <modules>
        self.assertEqual(1, len(self.violations("(a)")))

    def test_pom_fixture_under_src_is_not_a_module(self):
        write(self.root, "lib/src/test/resources/fixture/pom.xml", pom("fixture", deps=(("core", None),)))
        self.assertEqual([], self.violations("(a)"))

    def test_bsl_module_without_header_is_refused(self):
        write(self.root, "bsl/core/src/main/java/A.java", "class A {}\n")
        self.assertEqual(1, len(self.violations("(b)")))

    def test_bsl_module_with_apache_header_is_refused(self):
        write(self.root, "bsl/core/src/main/java/A.java", APACHE_HEADER + "class A {}\n")
        self.assertEqual(1, len(self.violations("(b)")))

    def test_bsl_header_in_apache_java_is_refused(self):
        write(self.root, "lib/src/main/java/B.java", BSL_HEADER + "class B {}\n")
        self.assertEqual(1, len(self.violations("(b)")))

    def test_bsl_header_in_apache_markdown_and_shell_is_refused(self):
        write(self.root, "lib/doc.md", "<!-- SPDX-License-Identifier: BUSL-1.1 -->\n")
        write(self.root, "lib/run.sh", "# SPDX-License-Identifier: BUSL-1.1\n")
        self.assertEqual(2, len(self.violations("(b)")))

    def test_files_that_discuss_the_licence_are_exempt(self):
        write(self.root, "CONTRIBUTING.md", "SPDX-License-Identifier: BUSL-1.1\n")
        self.assertEqual([], self.violations("(b)"))

    def test_path_in_list_but_not_in_license_map_is_refused(self):
        write(self.root, "tools/license/bsl-modules.txt", "bsl/core\nbsl/other\n")
        write(self.root, "bsl/other/pom.xml", pom("other", parent="bsl-parent", licenses=BSL_LICENSES))
        write(self.root, "bsl/other/LICENSE", BSL_TEXT)
        self.assertEqual(["(c) bsl/other: in bsl-modules.txt only"], self.violations("(c)"))

    def test_path_in_license_map_but_not_in_list_is_refused(self):
        write(self.root, "tools/license/bsl-modules.txt", "bsl/core\n")
        write(self.root, "LICENSE", LICENSE_MAP.replace("    bsl/core/\n", "    bsl/core/\n    lib/\n"))
        self.assertEqual(["(c) lib: in the LICENSE map only"], self.violations("(c)"))

    def test_bsl_module_without_license_file_is_refused(self):
        (self.root / "bsl/core/LICENSE").unlink()
        self.assertEqual(1, len(self.violations("(c)")))

    def test_bsl_module_pom_without_bsl_licence_is_refused(self):
        write(self.root, "bsl/core/pom.xml", pom("core", parent="bsl-parent"))
        self.assertEqual(1, len(self.violations("(c)")))

    def test_apache_pom_declaring_bsl_is_refused(self):
        write(self.root, "lib/pom.xml", pom("lib", parent="root", licenses=BSL_LICENSES))
        self.assertEqual(1, len(self.violations("(c)")))

    def test_bsl_license_file_outside_the_bsl_modules_is_refused(self):
        write(self.root, "lib/LICENSE", BSL_TEXT)
        self.assertEqual(1, len(self.violations("(c)")))

    def test_empty_tree_is_refused_not_clean(self):
        empty = tempfile.TemporaryDirectory()
        try:
            write(Path(empty.name), "tools/license/bsl-modules.txt", "bsl/core\n")
            self.assertEqual(1, len(gate.analyse(empty.name)[0]))
        finally:
            empty.cleanup()


class RelicenseTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.root = Path(self.dir.name)
        clean_tree(self.root)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        relicense.configure(self.root)

    def tearDown(self):
        self.dir.cleanup()
        relicense.configure(HERE.parent)

    def run_script(self):
        changes, unresolved = relicense.plan(False)
        for path, _, new in changes:
            if new is None:
                (self.root / path).unlink()
            else:
                relicense.write(path, new)
        return changes, unresolved

    def dirty_tree(self):
        write(self.root, "bsl/core/src/main/java/A.java", "class A {}\n")                                  # missing header
        write(self.root, "bsl/core/src/main/java/C.java", "// SPDX-License-Identifier: BUSL-1.1\nclass C {}\n")   # stub header
        write(self.root, "bsl/core/src/main/java/D.java", "/*\n * Licensed under the Apache License, Version 2.0\n */\n\nclass D {}\n")
        write(self.root, "lib/src/main/java/B.java", BSL_HEADER + "class B {}\n")                           # wrong side
        write(self.root, "lib/src/main/java/E.java", "/* Apache block */\nclass E {}\n")                  # left alone
        write(self.root, "lib/doc.md", "<!-- SPDX-License-Identifier: BUSL-1.1 -->\n<!-- Copyright (c) 2025 X -->\n\n# T\n")
        write(self.root, "lib/run.sh", "#!/bin/bash\n# SPDX-License-Identifier: BUSL-1.1\n# Copyright (c) 2025 X\n\necho hi\n")
        write(self.root, "lib/pom.xml", pom("lib", parent="root", licenses=BSL_LICENSES))
        write(self.root, "bsl/core/pom.xml", pom("core", parent="bsl-parent"))
        write(self.root, "lib/LICENSE", BSL_TEXT)
        (self.root / "bsl/core/LICENSE").unlink()

    def test_relicense_makes_the_gate_green_and_is_idempotent(self):
        self.dirty_tree()
        self.assertNotEqual([], gate.analyse(self.root)[0])
        changes, unresolved = self.run_script()
        self.assertEqual([], unresolved)
        self.assertGreaterEqual(len(changes), 8)
        self.assertEqual([], gate.analyse(self.root)[0])
        self.assertEqual([], relicense.plan(True)[0], "a second run must change 0 files")

    def test_headers_land_as_the_header_files_say(self):
        self.dirty_tree()
        self.run_script()
        read = lambda p: (self.root / p).read_text()
        self.assertEqual(BSL_HEADER + "class A {}\n", read("bsl/core/src/main/java/A.java"))
        self.assertEqual(BSL_HEADER + "class C {}\n", read("bsl/core/src/main/java/C.java"))
        self.assertEqual(BSL_HEADER + "class D {}\n", read("bsl/core/src/main/java/D.java"))
        self.assertEqual(APACHE_HEADER + "class B {}\n", read("lib/src/main/java/B.java"))
        self.assertEqual("/* Apache block */\nclass E {}\n", read("lib/src/main/java/E.java"))
        self.assertTrue(read("lib/doc.md").startswith("<!-- SPDX-License-Identifier: Apache-2.0 -->\n"))
        self.assertTrue(read("lib/run.sh").startswith("#!/bin/bash\n# SPDX-License-Identifier: Apache-2.0\n"))
        self.assertIn("\n\necho hi\n", read("lib/run.sh"))
        self.assertFalse((self.root / "lib/LICENSE").exists())

    def test_unrewritable_busl_header_is_reported_unresolved(self):
        write(self.root, "lib/odd.md", "---\ntitle: x\n---\n<!-- SPDX-License-Identifier: BUSL-1.1 -->\n")
        write(self.root, "lib/odd.sh", "echo\n# SPDX-License-Identifier: BUSL-1.1\n")
        self.assertEqual(["lib/odd.md", "lib/odd.sh"], sorted(relicense.plan(True)[1]))


if __name__ == "__main__":
    unittest.main()
