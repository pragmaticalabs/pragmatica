"""Regression checks for the published-set closure gate (#1219, #1988): synthetic reactors, the real checker.

Publishing is OPT-IN: a module publishes only when its effective Central plugin entry exists and does not skip."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("publish_closure", Path(__file__).with_name("check-publish-closure.py"))
closure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(closure)

HEAD = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
ENTRY = ('<plugin><groupId>org.sonatype.central</groupId><artifactId>central-publishing-maven-plugin</artifactId>'
         '{inherited}{configuration}</plugin>')


def entry(skip=None, inherited=None):
    configuration = "" if skip is None else f"<configuration><skipPublishing>{'true' if skip else 'false'}</skipPublishing></configuration>"
    flag = "" if inherited is None else f"<inherited>{'true' if inherited else 'false'}</inherited>"
    return ENTRY.format(inherited=flag, configuration=configuration)


def pom(artifact, modules=(), parent=None, skip=None, deps=(), group="org.pragmatica-lite", inherited=None, bound=False,
        managed_skip=None):
    """`skip` True/False declares the plugin entry with that value; `bound` declares it with no value; `inherited` adds the
    entry's <inherited>; `managed_skip` sets the <pluginManagement> default."""
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
    plugins = ""
    if skip is not None or bound or inherited is not None:
        plugins = f"<plugins>{entry(skip, inherited)}</plugins>"
    management = ""
    if managed_skip is not None:
        management = f"<pluginManagement><plugins>{entry(managed_skip)}</plugins></pluginManagement>"
    if plugins or management:
        body += f"<build>{management}{plugins}</build>"
    return body + "</project>"


def root_pom(modules, **kwargs):
    """A root that publishes itself without passing the entry on, and skips by default, as the real root does."""
    return pom("root", modules=modules, skip=False, inherited=False, managed_skip=True, **kwargs)


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
                ".": root_pom(("resource", "node")),
                "resource": pom("resource", modules=("api",), parent="root", skip=True),
                "resource/api": pom("resource-api", parent="resource"),
                "node": pom("node", parent="root", skip=False, deps=(("resource-api", None, False),)),
            })
            self.assertEqual(edges, 1)
            self.assertTrue(any("node (compile) depends on resource-api" in line for line in found), found)

    def test_child_that_reenables_publishing_is_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": root_pom(("resource", "node")),
                "resource": pom("resource", modules=("api",), parent="root", skip=True),
                "resource/api": pom("resource-api", parent="resource", skip=False),
                "node": pom("node", parent="root", skip=False, deps=(("resource-api", None, False),)),
            })
            # The api publishes, but its parent pom does not, and a consumer must resolve the parent too.
            self.assertEqual(found, [f for f in found if "its parent resource" in f])
            self.assertEqual(len(found), 1)

    def test_test_scoped_and_optional_dependencies_are_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": root_pom(("kit", "node")),
                "kit": pom("kit", parent="root", skip=True),
                "node": pom("node", parent="root", skip=False, deps=(("kit", "test", False), ("kit", None, True))),
            })
            self.assertEqual((edges, found), (0, []))

    def test_unpublished_module_may_depend_on_anything(self):
        with tempfile.TemporaryDirectory() as directory:
            _, _, found = self.reactor(directory, {
                ".": root_pom(("kit", "slices")),
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
                ".": root_pom(("kit", "node")).replace("</project>", managed + "</project>"),
                "kit": pom("kit", parent="root", skip=True),
                "node": pom("node", parent="root", skip=False, deps=(("kit", None, False),)),
            })
            self.assertEqual((edges, found), (0, []))

    def test_declared_scope_overrides_the_managed_one(self):
        managed = ('<dependencyManagement><dependencies><dependency><groupId>org.pragmatica-lite</groupId>'
                   '<artifactId>kit</artifactId><version>1</version><scope>test</scope></dependency>'
                   '</dependencies></dependencyManagement>')
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": root_pom(("kit", "node")).replace("</project>", managed + "</project>"),
                "kit": pom("kit", parent="root", skip=True),
                "node": pom("node", parent="root", skip=False, deps=(("kit", "compile", False),)),
            })
            self.assertEqual(edges, 1)
            self.assertEqual(len(found), 1, found)

    def test_reactor_module_outside_the_group_prefix_is_still_checked(self):
        # #1707 review: membership is decided by the reactor coordinates, not by a groupId prefix.
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": root_pom(("kit", "node"), group="io.example"),
                "kit": pom("kit", parent="root", skip=True, group="io.example"),
                "node": pom("node", parent="root", skip=False, deps=(("kit", None, False),), group="io.example"),
            })
            self.assertEqual(edges, 1)
            self.assertTrue(any("node (compile) depends on kit" in line for line in found), found)

    def test_a_module_that_says_nothing_is_not_published(self):
        # #1988: publishing is opt-in. A new module with no plugin entry of its own is unbound, so a published module
        # depending on it is the broken shape the gate exists for (the CONTROL: it must be reported).
        with tempfile.TemporaryDirectory() as directory:
            _, edges, found = self.reactor(directory, {
                ".": root_pom(("kit", "node")),
                "kit": pom("kit", parent="root"),
                "node": pom("node", parent="root", skip=False, deps=(("kit", None, False),)),
            })
            self.assertEqual(edges, 1)
            self.assertTrue(any("node (compile) depends on kit, which is not published" in line for line in found), found)

    def test_the_same_reactor_is_closed_once_the_dependency_opts_in(self):
        with tempfile.TemporaryDirectory() as directory:
            count, edges, found = self.reactor(directory, {
                ".": root_pom(("kit", "node")),
                "kit": pom("kit", parent="root", skip=False),
                "node": pom("node", parent="root", skip=False, deps=(("kit", None, False),)),
            })
            self.assertEqual((count, edges, found), (3, 1, []))

    def test_a_bound_plugin_without_a_value_takes_the_managed_default(self):
        with tempfile.TemporaryDirectory() as directory:
            count, _, _ = self.reactor(directory, {
                ".": root_pom(("kit",)),
                "kit": pom("kit", parent="root", bound=True),
            })
            self.assertEqual(count, 1, "the root alone: kit is bound but takes the managed skip=true")

    def test_without_any_managed_default_a_bound_plugin_publishes_as_the_plugin_does(self):
        with tempfile.TemporaryDirectory() as directory:
            count, _, _ = self.reactor(directory, {
                ".": pom("root", modules=("kit",), skip=False, inherited=False),
                "kit": pom("kit", parent="root", bound=True),
            })
            self.assertEqual(count, 2)

    def test_a_parent_that_publishes_does_not_publish_its_children(self):
        # The parent poms publish (a consumer resolves the parent), the unpublished siblings do not: <inherited>false</inherited>.
        with tempfile.TemporaryDirectory() as directory:
            count, _, found = self.reactor(directory, {
                ".": root_pom(("group",)),
                "group": pom("group", modules=("api", "internal"), parent="root", skip=False, inherited=False),
                "group/api": pom("api", parent="group", skip=False),
                "group/internal": pom("internal", parent="group"),
            })
            self.assertEqual(count, 3, "root, group and api; internal is not bound")
            self.assertEqual(found, [])

    def test_an_inherited_entry_without_the_flag_publishes_every_child(self):
        # The trap the flag exists for: without it the parent's `skipPublishing=false` flows down to children that never opted in.
        with tempfile.TemporaryDirectory() as directory:
            count, _, _ = self.reactor(directory, {
                ".": root_pom(("group",)),
                "group": pom("group", modules=("internal",), parent="root", skip=False),
                "group/internal": pom("internal", parent="group"),
            })
            self.assertEqual(count, 3)

    def test_a_non_inherited_entry_cuts_off_what_it_merged_from_above(self):
        # Maven merges the parent's entry into the child's before deciding what to pass on: a non-inherited entry in the
        # middle passes nothing, including the grandparent's.
        with tempfile.TemporaryDirectory() as directory:
            count, _, _ = self.reactor(directory, {
                ".": pom("root", modules=("mid",), skip=False, managed_skip=True),
                "mid": pom("mid", modules=("leaf",), parent="root", skip=False, inherited=False),
                "mid/leaf": pom("leaf", parent="mid"),
            })
            self.assertEqual(count, 2, "root and mid; the leaf inherits nothing")

    def test_a_module_outside_the_root_must_skip_the_default_deploy_itself(self):
        # step-composition is a standalone pom: the root's defaults never reach it, so without its own maven.deploy.skip the
        # default deploy runs and fails the release reactor (measured on the first full deploy of #1989).
        loose = pom("loose", group="org.example")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "loose").mkdir()
            (root / "pom.xml").write_text(root_pom(("loose",)))
            (root / "loose" / "pom.xml").write_text(loose)
            self.assertTrue(any("loose" in line and "maven.deploy.skip" in line for line in closure.violations(root / "pom.xml")[2]))
            (root / "loose" / "pom.xml").write_text(loose.replace("<version>1</version>", "<version>1</version><properties><maven.deploy.skip>true</maven.deploy.skip></properties>", 1))
            self.assertEqual(closure.violations(root / "pom.xml")[2], [])

    def test_the_decision_table_is_checked_against_the_poms(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.reactor(directory, {
                ".": root_pom(("kit", "tool")),
                "kit": pom("kit", parent="root", skip=False),
                "tool": pom("tool", parent="root"),
            })
            doc = root / "table.md"
            doc.write_text("\n".join(["| Module | Coordinate | Packaging | Decision | Why |", "|---|---|---|---|---|",
                                      "| `.` | `org.pragmatica-lite:root` | jar | publish | parent |",
                                      "| `kit` | `org.pragmatica-lite:kit` | jar | publish | seed |",
                                      "| `tool` | `org.pragmatica-lite:tool` | jar | skip | tooling |"]))
            self.assertEqual(closure.table_violations(root / "pom.xml", doc), [])
            doc.write_text(doc.read_text().replace("| `tool` | `org.pragmatica-lite:tool` | jar | skip |", "| `tool` | `org.pragmatica-lite:tool` | jar | publish |")
                           + "\n| `ghost` | `org.pragmatica-lite:ghost` | jar | skip | gone |")
            found = closure.table_violations(root / "pom.xml", doc)
            self.assertEqual(len(found), 2, found)
            self.assertTrue(any("tool: the poms decide 'skip'" in line for line in found), found)
            self.assertTrue(any("ghost: in the decision table, not a reactor module" in line for line in found), found)
            doc.write_text(doc.read_text().replace("| `kit` | `org.pragmatica-lite:kit` | jar | publish | seed |\n", ""))
            self.assertTrue(any("kit: decided 'publish' by the poms, absent" in line for line in closure.table_violations(root / "pom.xml", doc)))

    def test_the_repositorys_decision_table_matches_the_poms(self):
        base = Path(__file__).resolve().parent.parent
        self.assertEqual(closure.table_violations(base / "pom.xml", base / "docs" / "release" / "maven-central-publish-set.md"), [])

    def test_the_repository_itself_is_closed(self):
        count, edges, found = closure.violations(Path(__file__).resolve().parent.parent / "pom.xml")
        self.assertGreater(count, 20, "control: the real reactor was walked")
        self.assertGreater(edges, 30, "control: real dependency edges were examined")
        self.assertEqual(found, [])


if __name__ == "__main__":
    unittest.main()
