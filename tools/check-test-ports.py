#!/usr/bin/env python3
"""Test-port gate: registered test port ranges must not overlap, and fixed ports in test sources should be registered.

    python3 tools/check-test-ports.py [--root DIR] [--table PATH] [--strict]

CI runs this at the pull request's MERGE ref, because that is the only place two sibling PRs claiming the same ports
can see each other (#1688 and #1703 both took 14500+; each branch's own grep was clean).

(1) Overlap (always fatal). Each TEST_PORT_ALLOCATION.md row is expanded the way the code allocates ports, per
    protocol, because the same number on TCP and on UDP does not collide (every row puts mgmt TCP and SWIM UDP both at
    base+100):
      cluster (QUIC)  UDP  base .. base+S-1                    S = max offset + nodes
      SWIM            UDP  base+100 .. base+100+S-1            CoreSwimHealthDetector.SWIM_PORT_OFFSET, or "SWIM UDP a-b"
      management      TCP  mgmt .. mgmt+S-1                    "Base Mgmt Port", or base+N
      app-http        TCP  app .. app+S-1                      "app-http N" / "app-http base+N" in Notes, if present
      scan rows ("a-b scan") reserve a .. b+100+S-1 on BOTH protocols.
    ASSUMPTION: management and app-http serve HTTP/1.1 (TCP); a test that switches either to HTTP/3 (UDP) is not modelled.
    A row that cannot be parsed is fatal too: the table is the contract, so an unreadable row must not pass silently.
(2) Unregistered literals (WARN-ONLY unless --strict). A 4-5 digit number (1024-65535) on a non-comment line of
    */src/test/**/*.java that mentions "port" must fall in a registered range. rc4 already binds fixed ports that
    the table does not list (the table says so itself), so this starts as a report; --strict makes it fatal.
    Numbers on lines that do not mention "port" are not seen.
Exit: 0 pass (warnings may be printed) | 1 overlap, unparseable row, or (with --strict) unregistered literal | 2 no table.
"""
import argparse
import os
import re
import sys

TABLE = "aether/forge/forge-tests/src/test/resources/TEST_PORT_ALLOCATION.md"
SWIM_PORT_OFFSET = 100
NUMBER = re.compile(r"(?<![\w.])(\d{4,5})(?![\w.])")


class RowError(Exception):
    pass


def parse_table(text):
    """Rows of the allocation table as dicts: name, ranges [(proto, lo, hi, kind)]."""
    rows = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 5 or cells[0] in ("Test Class", "") or set(cells[0]) <= set("-: "):
            continue
        rows.append(parse_row(cells, lineno))
    return rows


def parse_row(cells, lineno):
    name, base_cell, mgmt_cell, off_cell, notes = cells[0], cells[1], cells[2], cells[3], " | ".join(cells[4:])
    where = "TEST_PORT_ALLOCATION.md:%d (%s)" % (lineno, name)
    if not re.fullmatch(r"\d+", off_cell):
        raise RowError("%s: Max Offset %r is not a number" % (where, off_cell))
    nodes_m = re.search(r"(\d+)(?:\s*\+\s*(\d+))?\s+(?:core\s+)?nodes?\b", notes)
    if not nodes_m:
        raise RowError("%s: Notes must state the node count ('N nodes'), got %r" % (where, notes))
    span = int(off_cell) + int(nodes_m.group(1)) + int(nodes_m.group(2) or 0)
    scan = re.fullmatch(r"(\d+)\s*-\s*(\d+)\s+scan", base_cell)
    if scan:
        lo, hi = int(scan.group(1)), int(scan.group(2)) + SWIM_PORT_OFFSET + span - 1
        return {"name": name, "line": lineno, "ranges": [("udp", lo, hi, "scan"), ("tcp", lo, hi, "scan")]}
    if not re.fullmatch(r"\d+", base_cell):
        raise RowError("%s: Base Port %r is neither a number nor 'a-b scan'" % (where, base_cell))
    base = int(base_cell)

    def rel(cell, what):
        m = re.fullmatch(r"(\d+)", cell) or re.fullmatch(r"base\s*\+\s*(\d+)", cell)
        if not m:
            raise RowError("%s: %s %r is neither a number nor base+N" % (where, what, cell))
        return int(m.group(1)) if m.re.pattern == r"(\d+)" else base + int(m.group(1))

    mgmt = rel(mgmt_cell, "Base Mgmt Port")
    ranges = [("udp", base, base + span - 1, "cluster")]
    swim = re.search(r"SWIM UDP (\d+)\s*-\s*(\d+)", notes)
    ranges.append(("udp", int(swim.group(1)), int(swim.group(2)), "swim") if swim
                  else ("udp", base + SWIM_PORT_OFFSET, base + SWIM_PORT_OFFSET + span - 1, "swim"))
    ranges.append(("tcp", mgmt, mgmt + span - 1, "mgmt"))
    app = re.search(r"app-http (base\s*\+\s*\d+|\d+)", notes)
    if app:
        a = rel(app.group(1).replace(" ", ""), "app-http")
        ranges.append(("tcp", a, a + span - 1, "app-http"))
    return {"name": name, "line": lineno, "ranges": ranges}


def overlaps(rows):
    """Pairs of ranges from DIFFERENT rows that share a protocol and at least one port."""
    flat = [(r["name"], r["line"]) + rng for r in rows for rng in r["ranges"]]
    found = []
    for i, a in enumerate(flat):
        for b in flat[i + 1:]:
            if a[1] != b[1] and a[2] == b[2] and a[3] <= b[4] and b[3] <= a[4]:
                found.append((a, b))
    return found


def registered(rows, port):
    return any(lo <= port <= hi for r in rows for (_, lo, hi, _) in r["ranges"])


def unregistered_literals(root, rows):
    hits = []
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in (".git", "target", "node_modules", ".m2-local")]
        if "/src/test" not in d.replace(os.sep, "/") + "/":
            continue
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(d, f)
            with open(path, encoding="utf-8", errors="replace") as fh:
                for n, line in enumerate(fh, 1):
                    s = line.strip()
                    if s.startswith(("//", "*", "/*")) or "port" not in s.lower():
                        continue
                    for m in NUMBER.finditer(s):
                        p = int(m.group(1))
                        if 1024 <= p <= 65535 and not registered(rows, p):
                            hits.append((os.path.relpath(path, root), n, p))
    return hits


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--table", default=None)
    ap.add_argument("--strict", action="store_true", help="unregistered literals are fatal")
    args = ap.parse_args(argv)
    table = args.table or os.path.join(args.root, TABLE)
    if not os.path.isfile(table):
        print("check-test-ports: %s not found; EXAMINED NOTHING" % table, file=sys.stderr)
        return 2
    try:
        with open(table, encoding="utf-8") as fh:
            rows = parse_table(fh.read())
    except RowError as e:
        print("check-test-ports: FAIL unparseable row: %s" % e, file=sys.stderr)
        return 1
    nranges = sum(len(r["ranges"]) for r in rows)
    bad = overlaps(rows)
    for a, b in bad:
        print("check-test-ports: FAIL overlap %s %s %d-%d (%s, line %d) vs %s %d-%d (%s, line %d)"
              % (a[2].upper(), a[5], a[3], a[4], a[0], a[1], b[5], b[3], b[4], b[0], b[1]), file=sys.stderr)
    hits = unregistered_literals(args.root, rows)
    files = sorted({h[0] for h in hits})
    for path, n, p in hits:
        print("check-test-ports: %s %s:%d: port-like literal %d is in no registered range"
              % ("FAIL" if args.strict else "WARN", path, n, p))
    print("check-test-ports: %d row(s), %d range(s); %d overlap(s); %d unregistered literal(s) in %d file(s)%s"
          % (len(rows), nranges, len(bad), len(hits), len(files), "" if args.strict else " (warn-only)"))
    return 1 if bad or (args.strict and hits) else 0


if __name__ == "__main__":
    sys.exit(main())
