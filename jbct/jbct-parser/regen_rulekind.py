#!/usr/bin/env python3
"""Regenerate jbct RuleKind enum from the generated Java25ParserV6 RULE_TABLE."""
import re, sys, pathlib

parser = pathlib.Path(sys.argv[1])
enum = pathlib.Path(sys.argv[2])

src = parser.read_text()
m = re.search(r'RULE_TABLE\s*=\s*\{(.*?)\};', src, re.S)
if not m:
    sys.exit("RULE_TABLE not found")
rules = [r.strip().strip('"') for r in m.group(1).split(',')]
rules = [r for r in rules if r]


def to_const(name):
    if name == "_ROOT":
        return "ROOT"
    if name == "ERROR":
        return "ERROR"
    s = re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', name)
    s = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1_\2', s)
    return s.upper()


new = [(to_const(r), i) for i, r in enumerate(rules)]
new_names = {n for n, _ in new}

old_src = enum.read_text()
old_names = set(re.findall(r'^    ([A-Z][A-Z0-9_]*)\(', old_src, re.M))

print(f"rules in table : {len(rules)}")
print(f"old constants  : {len(old_names)}")
print(f"new constants  : {len(new_names)}")
print()
print("REMOVED (present in enum, absent from new grammar):")
removed = sorted(old_names - new_names - {"UNKNOWN"})
print("  " + (" ".join(removed) if removed else "(none)"))
print()
print("ADDED (new in grammar):")
added = sorted(new_names - old_names)
print("  " + (" ".join(added) if added else "(none)"))

if len(sys.argv) > 3 and sys.argv[3] == "--write":
    body = "\n".join(f"    {n}({i})," for n, i in new)
    out = re.sub(
        r'(public enum RuleKind \{\n).*?(\n    UNKNOWN\(-1\);)',
        lambda mm: mm.group(1) + body + mm.group(2),
        old_src, flags=re.S)
    if out == old_src:
        sys.exit("FAILED: enum body pattern did not match — inspect manually")
    enum.write_text(out)
    print("\nwrote", enum)
