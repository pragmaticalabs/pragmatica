#!/usr/bin/env python3
"""Stub tests for check-md-links.py on synthetic git repositories: a broken link is flagged (the control that makes a 0 mean something),
a good one is not, and each exclusion in the checker's docstring is exercised once."""
import importlib.util
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("check_md_links", os.path.join(HERE, "check-md-links.py"))
cml = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cml)


class CheckMdLinks(unittest.TestCase):
    def repo(self, files):
        d = tempfile.mkdtemp()
        subprocess.run(["git", "init", "-q"], cwd=d, check=True)
        for path, text in files.items():
            os.makedirs(os.path.dirname(os.path.join(d, path)) or d, exist_ok=True)
            open(os.path.join(d, path), "w").write(text)
        subprocess.run(["git", "add", "-A"], cwd=d, check=True)
        return d

    def dangling(self, files, **kw):
        return [(f, t) for f, _, t in cml.scan(self.repo(files), **kw)[2]]

    def test_broken_relative_link_is_flagged(self):
        self.assertEqual([("a/x.md", "../nope.md")], self.dangling({"a/x.md": "see [n](../nope.md)\n", "ok.md": "# ok\n"}))

    def test_good_relative_root_and_directory_links_are_not(self):
        self.assertEqual([], self.dangling({"a/x.md": "[a](../ok.md) [b](/ok.md#frag) [c](../a/)\n", "ok.md": "# ok\n"}))

    def test_reference_style_definition_is_checked(self):
        self.assertEqual([("x.md", "gone.md")], self.dangling({"x.md": "[l][1]\n\n[1]: gone.md\n"}))

    def test_urls_anchors_footnotes_placeholders_and_code_are_not_links(self):
        text = "[u](https://x.io/a.md) [h](#top) [p](url)\n\n[^n]: In CAP shorthand.\n\n`[c](gone.md)`\n\n```\n[f](gone.md)\n```\n"
        self.assertEqual([], self.dangling({"x.md": text}))

    def test_history_files_are_excluded_unless_asked(self):
        files = {"CHANGELOG.md": "[g](gone.md)\n", "changelog.d/1.md": "[g](gone.md)\n"}
        self.assertEqual([], self.dangling(files))
        self.assertEqual(2, len(self.dangling(files, include_history=True)))

    def test_scope_keeps_links_in_or_into_the_prefix(self):
        files = {"p/x.md": "[g](gone.md)\n", "q/y.md": "[g](gone.md) [h](../p/gone2.md)\n"}
        self.assertEqual([("p/x.md", "gone.md"), ("q/y.md", "../p/gone2.md")], sorted(self.dangling(files, scope=["p/"])))


if __name__ == "__main__":
    unittest.main()
