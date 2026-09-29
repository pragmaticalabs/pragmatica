"""Tests for tools/check-test-ports.py.   python3 -B -m unittest discover -s tools -p test_check_test_ports.py"""
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("ctp", os.path.join(HERE, "check-test-ports.py"))
ctp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ctp)

HEADER = ("| Test Class | Base Port | Base Mgmt Port | Max Offset | Notes |\n"
          "|------------|-----------|----------------|------------|-------|\n")


def real_table():
    with open(os.path.join(HERE, "..", ctp.TABLE), encoding="utf-8") as fh:
        return fh.read()


def table(*rows):
    return HEADER + "".join("| %s |\n" % " | ".join(r) for r in rows)


class OverlapTest(unittest.TestCase):
    def test_repository_table_parses_and_has_no_overlap(self):
        rows = ctp.parse_table(real_table())
        self.assertGreater(len(rows), 30, "the real table must parse into its rows, not into nothing")
        self.assertEqual([], ctp.overlaps(rows))

    def test_real_table_with_one_seeded_duplicate_row_goes_red_through_the_cli(self):
        # instrument check: the gate must be able to fail on the table it actually guards
        text = real_table()
        seeded = text.replace("| ClusterFormationTest ", "| SeededCopyTest | 5000 | 5100 | 0 | 3 nodes |\n| ClusterFormationTest ", 1)
        self.assertNotEqual(text, seeded, "the seed row must land")
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, os.path.dirname(ctp.TABLE)))
            with open(os.path.join(root, ctp.TABLE), "w", encoding="utf-8") as f:
                f.write(seeded)
            self.assertEqual(1, ctp.main(["--root", root]))
            with open(os.path.join(root, ctp.TABLE), "w", encoding="utf-8") as f:
                f.write(text)
            self.assertEqual(0, ctp.main(["--root", root]), "control: the unseeded real table passes")

    def test_overlapping_pair_of_rows_goes_red(self):
        # the #1688/#1703 shape: two branches each claim 14500+
        rows = ctp.parse_table(table(("AlphaTest", "14500", "14600", "0", "5 nodes (app-http 14700)"),
                                     ("BetaTest", "14500", "14600", "0", "3 nodes (app-http 14700)")))
        pairs = {(a[0], b[0], a[5], b[5]) for a, b in ctp.overlaps(rows)}
        self.assertIn(("AlphaTest", "BetaTest", "cluster", "cluster"), pairs)
        self.assertIn(("AlphaTest", "BetaTest", "mgmt", "mgmt"), pairs)

    def test_partial_overlap_through_max_offset_and_node_count(self):
        # A spans 5000..5000+80+3-1 = 5082; B starts at 5082
        rows = ctp.parse_table(table(("A", "5000", "5100", "80", "3 nodes"), ("B", "5082", "5300", "0", "3 nodes")))
        self.assertTrue(any(a[5] == b[5] == "cluster" for a, b in ctp.overlaps(rows)))
        rows = ctp.parse_table(table(("A", "5000", "5100", "80", "3 nodes"), ("B", "5083", "5300", "0", "3 nodes")))
        self.assertFalse(any(a[5] == b[5] == "cluster" for a, b in ctp.overlaps(rows)), "adjacent is not overlapping")

    def test_swim_plus_100_collides_with_a_neighbours_cluster_block(self):
        # A's SWIM is UDP 6100..6102; B's cluster (QUIC) is UDP 6100..6102
        rows = ctp.parse_table(table(("A", "6000", "6300", "0", "3 nodes"), ("B", "6100", "6400", "0", "3 nodes")))
        self.assertIn(("swim", "cluster"), {(a[5], b[5]) for a, b in ctp.overlaps(rows)})

    def test_same_number_on_tcp_and_udp_is_not_a_collision(self):
        # A's mgmt is TCP 7100..; B's cluster is UDP 7100.. -- different protocols
        rows = ctp.parse_table(table(("A", "7000", "7100", "0", "3 nodes"), ("B", "7100", "7500", "0", "3 nodes")))
        self.assertNotIn(("mgmt", "cluster"), {(a[5], b[5]) for a, b in ctp.overlaps(rows)})

    def test_explicit_swim_range_and_relative_mgmt_are_honoured(self):
        row = ctp.parse_table(table(("A", "24300", "base+20", "0", "5 nodes (app-http base+40, SWIM UDP 24400-24404)")))[0]
        self.assertIn(("udp", 24400, 24404, "swim"), row["ranges"])
        self.assertIn(("tcp", 24320, 24324, "mgmt"), row["ranges"])
        self.assertIn(("tcp", 24340, 24344, "app-http"), row["ranges"])

    def test_scan_row_reserves_its_whole_span_on_both_protocols(self):
        rows = ctp.parse_table(table(("S", "23000-23300 scan", "base+40", "5", "3 nodes + relaunch"),
                                     ("B", "23350", "23800", "0", "3 nodes")))
        self.assertTrue(any("scan" in (a[5], b[5]) for a, b in ctp.overlaps(rows)))

    def test_unparseable_row_is_an_error_not_a_skip(self):
        with self.assertRaises(ctp.RowError):
            ctp.parse_table(table(("A", "5000", "5100", "0", "shared cluster")))   # no node count
        with self.assertRaises(ctp.RowError):
            ctp.parse_table(table(("A", "fifty", "5100", "0", "3 nodes")))


class LiteralTest(unittest.TestCase):
    def test_unregistered_port_literal_is_reported_and_registered_one_is_not(self):
        rows = ctp.parse_table(table(("A", "5000", "5100", "0", "3 nodes")))
        with tempfile.TemporaryDirectory() as root:
            d = os.path.join(root, "m", "src", "test", "java")
            os.makedirs(d)
            with open(os.path.join(d, "T.java"), "w") as f:
                f.write("class T {\n"
                        "    static final int BASE_PORT = 5001;\n"       # registered (A cluster)
                        "    static final int OTHER_PORT = 19999;\n"     # unregistered
                        "    // BASE_PORT = 29999 in a comment is ignored\n"
                        "    static final long TIMEOUT_MS = 30000;\n"    # no 'port' on the line: not seen
                        "}\n")
            hits = ctp.unregistered_literals(root, rows)
        self.assertEqual([19999], [h[2] for h in hits])

    def test_strict_turns_the_warning_into_a_failure(self):
        with tempfile.TemporaryDirectory() as root:
            os.makedirs(os.path.join(root, os.path.dirname(ctp.TABLE)))
            with open(os.path.join(root, ctp.TABLE), "w") as f:
                f.write(table(("A", "5000", "5100", "0", "3 nodes")))
            d = os.path.join(root, "m", "src", "test", "java")
            os.makedirs(d)
            with open(os.path.join(d, "T.java"), "w") as f:
                f.write("class T { static final int PORT = 19999; }\n")
            self.assertEqual(0, ctp.main(["--root", root]))
            self.assertEqual(1, ctp.main(["--root", root, "--strict"]))

    def test_missing_table_examines_nothing_and_says_so(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(2, ctp.main(["--root", root]))


if __name__ == "__main__":
    unittest.main()
