"""Regression checks for the published-set closure gate (#1219): synthetic reactors, the real checker."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("publish_closure", Path(__file__).with_name("check-publish-closure.py"))
closure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(closure)

HEAD = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
SKIP = ('<build><plugins><plugin><groupId>org.sonatype.central</groupId>'
        '<artifactId>central-publishing-maven-plugin</artifactId>'
        '<configuration><skipPublishing>{}</skipPublishing></configuration></plugin></plugins></build>')


def pom(artifact, modules=(), parent=None, skip=None, deps=(), group="org.pragmatica-lite"):
    body = HEAD
    if parent:
        body += f'<parent><groupId>{group}</groupId><artifactId>{parent}</artifactId><version>1</version></parent>'
    else:
        body += f'<groupId>{group}</groupId>'
    body += f'<artifactId>{artifact}</artifactId><version>1</version>'
    if modules:
        body += "<modules>" + "".join(f"<module>{m}</module>" for m in modules) + "</modules>"
    if deps:
        body += "<dependencies>"
        for artifact_id, scope, optional in deps:
            body += f"<dependency><groupId>{group}</groupId><artifactId>{artifact_id}</artifactId>"
            body += (f"<scope>{scope}</scope>" if scope else "") + ("<optional>true</optional>" if optional else "")
            body += "</dependency>"
        body += "</dependencies>"
    if skip is not None:
        body += SKIP.format("true" if skip else "false")
    return body + "</project>"


class PublishClosureTest(unittest.TestCase):
    def reactor(self, directory, files):
        root = Path(directory)
        for relative, content in files.items():
            path = root / relative / "pom.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        return closure.violations(root / "pom.xml")

    def test_published_module_depending_on_an_inherited_skip_is_refused(self):
        # The #1211 shape: a parent in <build><plugins> skips publishing, and every child inherits it.
        with tempfile.TemporaryDirectory() as directory:
            count, edges, found = self.reactor(directory, {
                ".": pom("root", modules=("resource", "node")),
                "resource": pom("resource", modules=("api",), parent="root", skip=True),
                "resource/api": pom("resource-api", parent="resource"),
                "node": pom("node", parent="root", deps=(("resource-api", None, False),)),
            })
            self.assertEqual(edges, 1)
            self.assertTrue(any("node (compile) depends on resource-api" in line for line in found), found)

    def test_child_that_reenables_publishing_is_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": pom("root", modules=("resource", "node")),
                "resource": pom("resource", modules=("api",), parent="root", skip=True),
                "resource/api": pom("resource-api", parent="resource", skip=False),
                "node": pom("node", parent="root", deps=(("resource-api", None, False),)),
            })
            # The api publishes, but its parent pom does not, and a consumer must resolve the parent too.
            self.assertEqual(found, [f for f in found if "its parent resource" in f])
            self.assertEqual(len(found), 1)

    def test_test_scoped_and_optional_dependencies_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": pom("root", modules=("kit", "node")),
                "kit": pom("kit", parent="root", skip=True),
                "node": pom("node", parent="root", deps=(("kit", "test", False), ("kit", None, True))),
            })
            self.assertEqual((edges, found), (0, []))

    def test_unpublished_module_may_depend_on_anything(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": pom("root", modules=("kit", "slices")),
                "kit": pom("kit", parent="root", skip=True),
                "slices": pom("slices", parent="root", skip=True, deps=(("kit", None, False),)),
            })
            self.assertEqual(found, [])

    def test_empty_relative_path_means_no_local_parent(self):
        # #1707 review: <relativePath/> tells Maven to resolve the parent from the repository, so the unpublished
        # pom sitting at ".." is not this module's parent and must not be reported as one.
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": pom("root", modules=("tool",), skip=True),
                "tool": pom("tool", parent="root", skip=False).replace("<version>1</version></parent>",
                                                                        "<version>1</version><relativePath/></parent>"),
            })
            self.assertEqual(found, [])

    def test_local_candidate_with_another_artifact_id_is_not_the_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": pom("root", modules=("tool",), skip=True),
                "tool": pom("tool", parent="external-parent", skip=False),
            })
            self.assertEqual(found, [])

    def test_absent_relative_path_still_finds_the_parent_one_level_up(self):
        # Control for the two above: the same reactor with the real parent declared IS reported.
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": pom("root", modules=("tool",), skip=True),
                "tool": pom("tool", parent="root", skip=False),
            })
            self.assertEqual(len(found), 1, found)
            self.assertIn("its parent root", found[0])

    def test_scope_omitted_takes_the_managed_scope_from_the_parent_chain(self):
        # #1707 review: the five jbct modules' test-logging dependency carries no <scope>; the root manages it as test.
        managed = ('<dependencyManagement><dependencies><dependency><groupId>org.pragmatica-lite</groupId>'
                   '<artifactId>kit</artifactId><version>1</version><scope>test</scope></dependency>'
                   '</dependencies></dependencyManagement>')
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": pom("root", modules=("kit", "node")).replace("</project>", managed + "</project>"),
                "kit": pom("kit", parent="root", skip=True),
                "node": pom("node", parent="root", deps=(("kit", None, False),)),
            })
            self.assertEqual((edges, found), (0, []))

    def test_declared_scope_overrides_the_managed_one(self):
        managed = ('<dependencyManagement><dependencies><dependency><groupId>org.pragmatica-lite</groupId>'
                   '<artifactId>kit</artifactId><version>1</version><scope>test</scope></dependency>'
                   '</dependencies></dependencyManagement>')
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": pom("root", modules=("kit", "node")).replace("</project>", managed + "</project>"),
                "kit": pom("kit", parent="root", skip=True),
                "node": pom("node", parent="root", deps=(("kit", "compile", False),)),
            })
            self.assertEqual(edges, 1)
            self.assertEqual(len(found), 1, found)

    def test_reactor_module_outside_the_group_prefix_is_still_checked(self):
        # #1707 review: membership is decided by the reactor coordinates, not by a groupId prefix.
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": pom("root", modules=("kit", "node"), group="io.example"),
                "kit": pom("kit", parent="root", skip=True, group="io.example"),
                "node": pom("node", parent="root", deps=(("kit", None, False),), group="io.example"),
            })
            self.assertEqual(edges, 1)
            self.assertTrue(any("node (compile) depends on kit" in line for line in found), found)

    def test_the_repository_itself_is_closed(self):
        count, edges, found = closure.violations(Path(__file__).resolve().parent.parent / "pom.xml")
        self.assertGreater(count, 50, "control: the real reactor was walked")
        self.assertGreater(edges, 100, "control: real dependency edges were examined")
        self.assertEqual(found, [])


if __name__ == "__main__":
    unittest.main()
