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

    def test_the_repository_itself_is_closed(self):
        count, edges, found = closure.violations(Path(__file__).resolve().parent.parent / "pom.xml")
        self.assertGreater(count, 50, "control: the real reactor was walked")
        self.assertGreater(edges, 100, "control: real dependency edges were examined")
        self.assertEqual(found, [])


if __name__ == "__main__":
    unittest.main()
