"""Tests of touched.py (the fragment-touched check) over fixture repositories, and over real rc6 commits.

Each test builds a throwaway git repository: commit `base` holds the fixture, commit `head` the edited fixture, and
touched.py runs over base..head. Every case names the verdict it pins.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, 'touched.py')
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
import touched  # noqa: E402

ENV = dict(os.environ, GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@t', GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@t',
           GIT_CONFIG_GLOBAL='/dev/null', GIT_CONFIG_SYSTEM='/dev/null')

FRAG = '''    /// ```tla M:Step
    /// Step == x' = x + 1
    /// ```
'''
BASE = '''class A {
''' + FRAG + '''    int step() {
        int a = 1;
        int b = 2;
        return a + b;
    }

    int other() {
        return 9;
    }
}
'''


class Repo:
    def __init__(self):
        self.d = tempfile.mkdtemp()
        self.g('init', '-q', '-b', 'main')

    def g(self, *args):
        p = subprocess.run(['git', '-C', self.d] + list(args), capture_output=True, text=True, env=ENV)
        assert p.returncode == 0, p.stderr
        return p.stdout.strip()

    def write(self, rel, text):
        p = os.path.join(self.d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w') as f:
            f.write(text)

    def commit(self, msg='c'):
        self.g('add', '-A')
        self.g('commit', '-q', '-m', msg)
        return self.g('rev-parse', 'HEAD')

    def run(self, base, head='HEAD', body=''):
        p = subprocess.run([sys.executable, SCRIPT, '--base', base, '--head', head, '--repo', self.d], capture_output=True,
                           text=True, env=dict(os.environ, PR_BODY=body))
        return p.returncode, p.stdout + p.stderr


class Touched(unittest.TestCase):
    def setUp(self):
        self.r = Repo()
        self.addCleanup(shutil.rmtree, self.r.d)

    def verdict(self, base_text, head_text, body='', path='A.java'):
        self.r.write(path, base_text)
        base = self.r.commit('base')
        self.r.write(path, head_text)
        self.r.commit('head')
        return self.r.run(base, body=body)

    def red(self, rc, out, *needles):
        self.assertEqual(rc, 1, out)
        self.assertIn('RED M:Step', out)
        for n in needles:
            self.assertIn(n, out)

    # --- the four verdicts of the issue ---
    def test_changed_region_with_unchanged_fragment_is_red_and_names_file_line(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;'))
        self.red(rc, out, 'A.java:5: RED M:Step region L5-9', 'changed at L7')

    def test_fragment_changed_with_the_code_is_green(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;').replace("x + 1", "x + 2"))
        self.assertEqual(rc, 0, out)
        self.assertIn('the fragment changed too', out)

    def test_reason_in_pr_body_is_green(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;'), body='text\ntla-unchanged: M: the constant is not modelled\nmore')
        self.assertEqual(rc, 0, out)
        self.assertIn('excused by tla-unchanged: the constant is not modelled', out)

    def test_change_outside_any_region_is_green(self):
        rc, out = self.verdict(BASE, BASE.replace('return 9;', 'return 10;'))
        self.assertEqual(rc, 0, out)
        self.assertIn('GREEN 0 unexcused, 0 accounted for', out)

    # --- the marker ---
    def test_marker_for_another_module_does_not_excuse(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;'), body='tla-unchanged: Other: reason')
        self.red(rc, out)

    def test_marker_without_a_reason_does_not_excuse(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;'), body='tla-unchanged: M:   \ntla-unchanged: M')
        self.red(rc, out)

    def test_marker_must_start_the_line(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;'), body='see `x tla-unchanged: M: reason` in prose')
        self.red(rc, out)

    # --- what counts as a changed line ---
    def test_blank_line_added_in_region_is_green(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', '\n        int b = 2;'))
        self.assertEqual(rc, 0, out)

    def test_comment_added_in_region_is_green(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', '// why\n        /* more */\n        int b = 2;'))
        self.assertEqual(rc, 0, out)

    def test_code_line_deleted_in_region_is_red(self):
        rc, out = self.verdict(BASE, BASE.replace('        int b = 2;\n', ''))
        self.red(rc, out)

    def test_comment_line_deleted_in_region_is_green(self):
        base = BASE.replace('int b = 2;', '// note\n        int b = 2;')
        rc, out = self.verdict(base, BASE)
        self.assertEqual(rc, 0, out)

    def test_code_replaced_by_a_comment_in_region_is_red(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', '// int b = 2;'))
        self.red(rc, out)

    def test_change_in_the_doc_comment_prose_outside_the_fragment_is_green(self):
        base = BASE.replace(FRAG, '    /// prose one\n' + FRAG)
        rc, out = self.verdict(base, base.replace('prose one', 'prose two'))
        self.assertEqual(rc, 0, out)

    def test_change_on_the_line_just_after_the_region_is_green(self):
        rc, out = self.verdict(BASE, BASE.replace('int other() {', 'int other2() {'))
        self.assertEqual(rc, 0, out)

    def test_change_on_the_declaration_line_is_red(self):
        rc, out = self.verdict(BASE, BASE.replace('int step() {', 'long step() {'))
        self.red(rc, out, 'changed at L5')

    # --- region end: a brace in a string, a char, a comment or a text block must not move it ---
    def region_cases(self, inner):
        """base: a region whose body holds `inner`, then a second method; returns the head-diff verdicts for a change in
        the region's last line (red) and in the following method (green)."""
        base = BASE.replace('        int b = 2;\n', '        int b = 2;\n' + inner + '\n')
        inside = base.replace('return a + b;', 'return a - b;')
        outside = base.replace('return 9;', 'return 10;')
        return self.verdict(base, inside), self.verdict_again(base, outside)

    def verdict_again(self, base, head):
        r = Repo()
        self.addCleanup(shutil.rmtree, r.d)
        r.write('A.java', base)
        b = r.commit('base')
        r.write('A.java', head)
        r.commit('head')
        return r.run(b)

    def test_close_brace_in_string_does_not_end_the_region_early(self):
        (rc, out), (rc2, out2) = self.region_cases('        String s = "}";')
        self.red(rc, out)
        self.assertEqual(rc2, 0, out2)

    def test_open_brace_in_string_does_not_end_the_region_late(self):
        (rc, out), (rc2, out2) = self.region_cases('        String s = "{{";')
        self.red(rc, out)
        self.assertEqual(rc2, 0, out2)

    def test_braces_in_char_literals_do_not_move_the_region(self):
        (rc, out), (rc2, out2) = self.region_cases("        char c = '}'; char d = '{'; char q = '\\''; char z = '\"';")
        self.red(rc, out)
        self.assertEqual(rc2, 0, out2)

    def test_braces_in_comments_do_not_move_the_region(self):
        (rc, out), (rc2, out2) = self.region_cases('        // } }\n        /* { { } } */')
        self.red(rc, out)
        self.assertEqual(rc2, 0, out2)

    def test_braces_and_quotes_in_a_text_block_do_not_move_the_region(self):
        (rc, out), (rc2, out2) = self.region_cases('        String t = """\n            } "quoted" \\""" {\n            """;')
        self.red(rc, out)
        self.assertEqual(rc2, 0, out2)

    def test_string_with_escaped_quote_and_brace_does_not_move_the_region(self):
        (rc, out), (rc2, out2) = self.region_cases('        String s = "a\\"}";')
        self.red(rc, out)
        self.assertEqual(rc2, 0, out2)

    def test_string_literal_probe_is_not_vacuous_a_naive_scan_would_end_early(self):
        # control of the instrument: the same fixture with the brace OUTSIDE a string must end the region early, so the
        # change after it is outside the region (green); the string case above is red only because the string is blanked.
        (rc, out), _ = self.region_cases('        }')
        self.assertEqual(rc, 0, out)

    # --- other declaration shapes ---
    def test_field_with_lambda_initialiser_extends_to_its_semicolon(self):
        base = '''class A {
    /// ```tla M:Step
    /// Step == TRUE
    /// ```
    private final Runnable step = () -> {
        int a = 1;
        System.out.println("}");
    };

    int other() { return 1; }
}
'''
        rc, out = self.verdict(base, base.replace('int a = 1;', 'int a = 2;'))
        self.red(rc, out, 'region L5-8')

    def test_class_fragment_covers_the_header_not_the_body(self):
        base = '''/// ```tla M:head
/// EXTENDS Naturals
/// ```
public final class A {
    int body() { return 1; }
}
'''
        rc, out = self.verdict(base, base.replace('return 1', 'return 2'))
        self.assertEqual(rc, 0, out)
        rc, out = self.verdict(base, base.replace('final class', 'sealed class'))
        self.assertEqual(rc, 1, out)

    def test_method_named_record_is_a_method_not_a_class_header(self):
        base = BASE.replace('int step() {', 'void record(int x) {')
        rc, out = self.verdict(base, base.replace('int b = 2;', 'int b = 3;'))
        self.red(rc, out)

    def test_package_info_fragment_covers_the_package_statement(self):
        base = '/// ```tla M:head\n/// EXTENDS Naturals\n/// ```\npackage org.x;\n'
        rc, out = self.verdict(base, base.replace('org.x', 'org.y'), path='package-info.java')
        self.assertEqual(rc, 1, out)

    def test_two_fragments_in_one_doc_comment_are_judged_separately(self):
        base = '''class A {
    /// ```tla M:Step
    /// Step == x' = x + 1
    /// ```
    /// ```tla M:Other
    /// Other == x' = x
    /// ```
    int step() {
        return 1;
    }
}
'''
        head = base.replace('return 1', 'return 2').replace('Other == x\' = x', 'Other == x\' = x + 0')
        rc, out = self.verdict(base, head)
        self.assertEqual(rc, 1, out)
        self.assertIn('RED M:Step', out)
        self.assertIn('ok M:Other', out)

    # --- files and history ---
    def test_added_fragment_with_its_code_is_green(self):
        rc, out = self.verdict('class A {}\n', BASE)
        self.assertEqual(rc, 0, out)

    def test_renamed_file_with_changed_code_and_unchanged_fragment_is_red(self):
        self.r.write('A.java', BASE)
        base = self.r.commit('base')
        self.r.g('mv', 'A.java', 'B.java')
        self.r.write('B.java', BASE.replace('class A', 'class B').replace('int b = 2;', 'int b = 3;'))
        self.r.commit('head')
        rc, out = self.r.run(base)
        self.red(rc, out, 'B.java:')

    def test_file_without_fragments_is_ignored(self):
        rc, out = self.verdict('class P { int x = 1; }\n', 'class P { int x = 2; }\n', path='P.java')
        self.assertEqual(rc, 0, out)

    def test_non_java_file_is_ignored(self):
        rc, out = self.verdict(BASE, BASE.replace('int b = 2;', 'int b = 3;'), path='A.txt')
        self.assertEqual(rc, 0, out)

    def test_work_that_landed_on_the_base_branch_after_branching_is_not_charged(self):
        self.r.write('A.java', BASE)
        fork = self.r.commit('fork')
        self.r.g('checkout', '-q', '-b', 'pr')
        self.r.write('A.java', BASE.replace('return 9;', 'return 10;'))
        self.r.commit('pr')
        self.r.g('checkout', '-q', 'main')
        self.r.write('A.java', BASE.replace('int b = 2;', 'int b = 3;'))
        self.r.commit('main moved: changes the region')
        rc, out = self.r.run('main', head='pr')
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.r.g('merge-base', 'main', 'pr'), fork)

    def test_unknown_base_is_an_error_not_green(self):
        self.r.write('A.java', BASE)
        self.r.commit('base')
        rc, out = self.r.run('no-such-rev')
        self.assertEqual(rc, 2, out)

    def test_malformed_fragment_in_head_is_an_error_not_green(self):
        self.r.write('A.java', BASE)
        base = self.r.commit('base')
        self.r.write('A.java', BASE.replace('/// ```tla M:Step', '/// ```tla Step'))
        self.r.commit('head')
        rc, out = self.r.run(base)
        self.assertEqual(rc, 2, out)

    def test_unit_blank_keeps_offsets(self):
        src = 'a "}" b /* } */ c // }\n\'}\' d\n'
        self.assertEqual(len(touched.blank(src)), len(src))
        self.assertNotIn('}', touched.blank(src))


class RealCommits(unittest.TestCase):
    """Positive control on the real rc6 history (needs full history: fetch-depth 0; TLA_REQUIRE_REAL=1 makes a missing
    commit an error instead of a skip)."""

    def commit(self, sha):
        p = subprocess.run(['git', '-C', REPO, 'cat-file', '-t', sha], capture_output=True, text=True)
        if p.returncode != 0:
            if os.environ.get('TLA_REQUIRE_REAL') == '1':
                self.fail(f'commit {sha} is not in this clone and TLA_REQUIRE_REAL=1')
            self.skipTest(f'commit {sha} is not in this clone (shallow); run with full history')

    def run_touched(self, sha, body=''):
        p = subprocess.run([sys.executable, SCRIPT, '--base', sha + '^', '--head', sha, '--repo', REPO], capture_output=True,
                           text=True, env=dict(os.environ, PR_BODY=body))
        return p.returncode, p.stdout + p.stderr

    def test_3455_rabia_announce_order_change_is_red(self):
        self.commit('ad06517f0a')
        rc, out = self.run_touched('ad06517f0a')
        self.assertEqual(rc, 1, out)
        self.assertIn('RabiaEngine.java:3640: RED RabiaSlot:Activate', out)

    def test_3455_with_a_reason_for_both_modules_is_green(self):
        self.commit('ad06517f0a')
        rc, out = self.run_touched('ad06517f0a', 'tla-unchanged: RabiaSlot: r1\ntla-unchanged: RabiaAdopt: r2')
        self.assertEqual(rc, 0, out)

    def test_3278_lifecycle_failures_outbox_change_is_red(self):
        self.commit('2ed1115811')
        rc, out = self.run_touched('2ed1115811')
        self.assertEqual(rc, 1, out)
        for part in ('Fsm2Leader:Activate', 'Fsm2Leader:EventFire', 'Fsm2Leader:Flush'):
            self.assertIn('RED ' + part, out)

    def test_3156_stalled_code_and_fragment_changed_together_is_green(self):
        self.commit('36fc480f10')
        rc, out = self.run_touched('36fc480f10')
        self.assertEqual(rc, 0, out)
        self.assertIn('Stalled:ClearStalledOf', out)


if __name__ == '__main__':
    unittest.main()
